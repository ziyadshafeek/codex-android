package dev.codex.android.data

import dev.codex.android.core.CodexJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * JVM unit tests for [CostTelemetry] using both an in-memory store and a
 * temp-file-backed store (no Robolectric, no Mockito, no extra dependencies).
 */
class CostTelemetryTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("cost-telemetry-test-").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** Pure in-memory [CostUsageStore] that also records which threads performed writes. */
    private class InMemoryCostUsageStore : CostUsageStore {
        private val rows = mutableListOf<CostUsageRecord>()
        val writeThreads = CopyOnWriteArrayList<String>()

        @Synchronized
        override fun insert(record: CostUsageRecord) {
            writeThreads += Thread.currentThread().name
            rows += record
        }

        @Synchronized
        override fun deleteThread(threadId: String) {
            writeThreads += Thread.currentThread().name
            rows.removeAll { it.threadId == threadId }
        }

        @Synchronized
        override fun loadTotalsByThread(): Map<String, ThreadUsageSummary> {
            return rows.groupBy { it.threadId }.mapValues { (_, list) ->
                ThreadUsageSummary(
                    inputTokens = list.sumOf { it.inputTokens },
                    outputTokens = list.sumOf { it.outputTokens },
                    costUsdMilli = list.sumOf { it.costUsdMilli },
                    calls = list.size.toLong(),
                )
            }
        }
    }

    @Serializable
    private data class StoredRow(
        val threadId: String,
        val model: String,
        val inputTokens: Long,
        val outputTokens: Long,
        val costUsdMilli: Double,
        val createdAt: Long,
    )

    /** Temp-file-backed [CostUsageStore] verifying real persistence across instances. */
    private class TempFileCostUsageStore(private val file: File) : CostUsageStore {
        private val listSerializer = ListSerializer(StoredRow.serializer())

        @Synchronized
        override fun insert(record: CostUsageRecord) {
            val existing = readRows().toMutableList()
            existing += StoredRow(
                threadId = record.threadId,
                model = record.model,
                inputTokens = record.inputTokens,
                outputTokens = record.outputTokens,
                costUsdMilli = record.costUsdMilli,
                createdAt = record.createdAt,
            )
            writeRows(existing)
        }

        @Synchronized
        override fun deleteThread(threadId: String) {
            val remaining = readRows().filter { it.threadId != threadId }
            writeRows(remaining)
        }

        @Synchronized
        override fun loadTotalsByThread(): Map<String, ThreadUsageSummary> {
            return readRows().groupBy { it.threadId }.mapValues { (_, list) ->
                ThreadUsageSummary(
                    inputTokens = list.sumOf { it.inputTokens },
                    outputTokens = list.sumOf { it.outputTokens },
                    costUsdMilli = list.sumOf { it.costUsdMilli },
                    calls = list.size.toLong(),
                )
            }
        }

        private fun readRows(): List<StoredRow> {
            if (!file.isFile) return emptyList()
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) return emptyList()
            return CodexJson.decodeFromString(listSerializer, raw)
        }

        private fun writeRows(rows: List<StoredRow>) {
            file.parentFile?.mkdirs()
            file.writeText(CodexJson.encodeToString(listSerializer, rows), Charsets.UTF_8)
        }
    }

    @Test
    fun emptyDatabase_returnsZeroTotals() {
        val telemetry = CostTelemetry(InMemoryCostUsageStore())
        try {
            assertEquals(UsageTotals(0L, 0L, 0.0, 0L), telemetry.totalsForThread("t1"))
            assertEquals(UsageTotals(0L, 0L, 0.0, 0L), telemetry.totalsAll())
        } finally {
            telemetry.close()
        }
    }

    @Test
    fun recordAndTotals_aggregatesPerThreadAndGloballyOnBackgroundThread() {
        val store = InMemoryCostUsageStore()
        val callerThread = Thread.currentThread().name
        val telemetry = CostTelemetry(store)
        try {
            telemetry.record("t1", "gpt-4o", 1_000L, 500L, 0.0075)
            telemetry.record("t1", "gpt-4o", 2_000L, 1_000L, 0.015)
            telemetry.record("t2", "claude-sonnet-4-5", 500L, 250L, 0.00525)

            // Immediately visible without waiting.
            val t1 = telemetry.totalsForThread("t1")
            assertEquals(3_000L, t1.inputTokens)
            assertEquals(1_500L, t1.outputTokens)
            assertEquals(0.0225, t1.costUsd, 1e-9)
            assertEquals(2L, t1.calls)

            val t2 = telemetry.totalsForThread("t2")
            assertEquals(500L, t2.inputTokens)
            assertEquals(250L, t2.outputTokens)
            assertEquals(0.00525, t2.costUsd, 1e-9)
            assertEquals(1L, t2.calls)

            val all = telemetry.totalsAll()
            assertEquals(3_500L, all.inputTokens)
            assertEquals(1_750L, all.outputTokens)
            assertEquals(0.02775, all.costUsd, 1e-9)
            assertEquals(3L, all.calls)

            telemetry.awaitIdle()
            assertEquals(3, store.writeThreads.size)
            assertTrue(store.writeThreads.all { it != callerThread })
            assertNotEquals("main", store.writeThreads.first())
        } finally {
            telemetry.close()
        }
    }

    @Test
    fun clearThread_clearsOnlyTargetThreadAndUpdatesGlobalTotals() {
        val store = InMemoryCostUsageStore()
        val telemetry = CostTelemetry(store)
        try {
            telemetry.record("t1", "gpt-4o", 1_000L, 400L, 0.01)
            telemetry.record("t2", "gpt-4o-mini", 2_000L, 600L, 0.002)

            telemetry.clearThread("t1")

            assertEquals(UsageTotals(0L, 0L, 0.0, 0L), telemetry.totalsForThread("t1"))
            val t2 = telemetry.totalsForThread("t2")
            assertEquals(2_000L, t2.inputTokens)
            assertEquals(600L, t2.outputTokens)
            assertEquals(0.002, t2.costUsd, 1e-9)
            assertEquals(1L, t2.calls)

            val all = telemetry.totalsAll()
            assertEquals(2_000L, all.inputTokens)
            assertEquals(600L, all.outputTokens)
            assertEquals(0.002, all.costUsd, 1e-9)
            assertEquals(1L, all.calls)

            telemetry.awaitIdle()
            assertEquals(UsageTotals(0L, 0L, 0.0, 0L), telemetry.totalsForThread("t1"))
        } finally {
            telemetry.close()
        }
    }

    @Test
    fun tempFileDatabase_persistsRecordAndClearAcrossInstances() {
        val dbFile = File(tempDir, "cost_usage.json")
        val first = CostTelemetry(TempFileCostUsageStore(dbFile))
        try {
            first.record("t1", "gpt-4o", 1_200L, 300L, 0.006)
            first.record("t2", "gemini-2.5-pro", 800L, 200L, 0.003)
            first.awaitIdle()
        } finally {
            first.close()
        }

        val second = CostTelemetry(TempFileCostUsageStore(dbFile))
        try {
            val t1 = second.totalsForThread("t1")
            assertEquals(1_200L, t1.inputTokens)
            assertEquals(300L, t1.outputTokens)
            assertEquals(0.006, t1.costUsd, 1e-9)
            assertEquals(1L, t1.calls)

            val all = second.totalsAll()
            assertEquals(2_000L, all.inputTokens)
            assertEquals(500L, all.outputTokens)
            assertEquals(0.009, all.costUsd, 1e-9)
            assertEquals(2L, all.calls)

            second.clearThread("t1")
            second.awaitIdle()
        } finally {
            second.close()
        }

        val third = CostTelemetry(TempFileCostUsageStore(dbFile))
        try {
            assertEquals(UsageTotals(0L, 0L, 0.0, 0L), third.totalsForThread("t1"))
            val remaining = third.totalsAll()
            assertEquals(800L, remaining.inputTokens)
            assertEquals(200L, remaining.outputTokens)
            assertEquals(0.003, remaining.costUsd, 1e-9)
            assertEquals(1L, remaining.calls)
        } finally {
            third.close()
        }
    }
}
