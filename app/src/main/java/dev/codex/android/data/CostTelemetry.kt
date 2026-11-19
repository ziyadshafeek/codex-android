package dev.codex.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Aggregated token and BYOK spend telemetry. */
data class UsageTotals(
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
    val calls: Long,
)

/** One persisted row matching `cost_usage`. */
internal data class CostUsageRecord(
    val threadId: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsdMilli: Double,
    val createdAt: Long,
)

/** Per-thread accumulator used for hydration and in-memory write-through views. */
internal data class ThreadUsageSummary(
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsdMilli: Double,
    val calls: Long,
)

/**
 * Persistence seam for [CostTelemetry]. Production uses [SqliteCostUsageStore]
 * (plain [SQLiteOpenHelper], no Room); JVM unit tests supply an in-memory or
 * temp-file store.
 */
internal interface CostUsageStore {
    fun insert(record: CostUsageRecord)
    fun deleteThread(threadId: String)
    fun loadTotalsByThread(): Map<String, ThreadUsageSummary>
    fun close() {}
}

/**
 * Plain [SQLiteOpenHelper] store for `cost_usage(id, thread_id, model,
 * input_tokens, output_tokens, cost_usd_milli, created_at)` in its own
 * database (`codex_cost.db`) so it never conflicts with `codex.db`.
 */
private class SqliteCostUsageStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION),
    CostUsageStore {

    companion object {
        const val DATABASE_NAME = "codex_cost.db"
        const val DATABASE_VERSION = 1
        const val TABLE_COST_USAGE = "cost_usage"
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_COST_USAGE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                thread_id TEXT NOT NULL,
                model TEXT NOT NULL,
                input_tokens INTEGER NOT NULL,
                output_tokens INTEGER NOT NULL,
                cost_usd_milli REAL NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_cost_usage_thread ON $TABLE_COST_USAGE(thread_id)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 initial schema.
    }

    // Both SQLiteOpenHelper and CostUsageStore declare close(): override it
    // explicitly so the call is unambiguous; the helper's close is the one
    // that releases the database.
    override fun close() {
        super<SQLiteOpenHelper>.close()
    }

    override fun insert(record: CostUsageRecord) {
        val values = ContentValues().apply {
            put("thread_id", record.threadId)
            put("model", record.model)
            put("input_tokens", record.inputTokens)
            put("output_tokens", record.outputTokens)
            put("cost_usd_milli", record.costUsdMilli)
            put("created_at", record.createdAt)
        }
        writableDatabase.insert(TABLE_COST_USAGE, null, values)
    }

    override fun deleteThread(threadId: String) {
        writableDatabase.delete(TABLE_COST_USAGE, "thread_id = ?", arrayOf(threadId))
    }

    override fun loadTotalsByThread(): Map<String, ThreadUsageSummary> {
        val result = LinkedHashMap<String, ThreadUsageSummary>()
        readableDatabase.rawQuery(
            """
            SELECT thread_id,
                   COALESCE(SUM(input_tokens), 0),
                   COALESCE(SUM(output_tokens), 0),
                   COALESCE(SUM(cost_usd_milli), 0),
                   COUNT(*)
            FROM $TABLE_COST_USAGE
            GROUP BY thread_id
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val threadId = cursor.getString(0)
                result[threadId] = ThreadUsageSummary(
                    inputTokens = cursor.getLong(1),
                    outputTokens = cursor.getLong(2),
                    costUsdMilli = cursor.getDouble(3),
                    calls = cursor.getLong(4),
                )
            }
        }
        return result
    }
}

/**
 * Tracks per-thread and global token usage and estimated BYOK spend.
 *
 * All SQLite reads/writes run on a dedicated single-threaded background
 * executor; [record], [clearThread], [totalsForThread], and [totalsAll] never
 * perform disk I/O on the Main thread. An in-memory pending-operation overlay
 * keeps reads immediately consistent with writes that are still flushing on
 * the background thread.
 */
class CostTelemetry internal constructor(
    private val store: CostUsageStore,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {

    constructor(context: Context) : this(SqliteCostUsageStore(context))

    private sealed class PendingOp {
        abstract val seq: Long
        abstract val threadId: String

        data class Record(
            override val seq: Long,
            override val threadId: String,
            val inputTokens: Long,
            val outputTokens: Long,
            val costUsdMilli: Double,
        ) : PendingOp()

        data class Clear(
            override val seq: Long,
            override val threadId: String,
        ) : PendingOp()
    }

    private class MutableBucket(
        var inputTokens: Long = 0L,
        var outputTokens: Long = 0L,
        var costUsdMilli: Double = 0.0,
        var calls: Long = 0L,
    ) {
        fun copy(): MutableBucket = MutableBucket(inputTokens, outputTokens, costUsdMilli, calls)
    }

    private val stateLock = Any()
    private val persistedByThread = LinkedHashMap<String, MutableBucket>()
    private val pendingOps = ArrayDeque<PendingOp>()
    private val seqGen = AtomicLong(1L)

    @Volatile
    private var hydrated: Boolean = false
    private val hydrationLatch = CountDownLatch(1)

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "codex-cost-telemetry").apply { isDaemon = true }
    }

    init {
        // Hydrate persisted rows on the background thread before any queued
        // writes execute (FIFO single-thread executor guarantees ordering).
        writer.execute {
            try {
                val loaded = runCatching { store.loadTotalsByThread() }.getOrDefault(emptyMap())
                synchronized(stateLock) {
                    for ((threadId, summary) in loaded) {
                        persistedByThread[threadId] = MutableBucket(
                            inputTokens = summary.inputTokens,
                            outputTokens = summary.outputTokens,
                            costUsdMilli = summary.costUsdMilli,
                            calls = summary.calls,
                        )
                    }
                    hydrated = true
                }
            } finally {
                hydrationLatch.countDown()
            }
        }
    }

    /** Records one model call asynchronously on the background writer thread. */
    fun record(
        threadId: String,
        model: String,
        inputTokens: Long,
        outputTokens: Long,
        costUsd: Double,
    ) {
        val safeInput = inputTokens.coerceAtLeast(0L)
        val safeOutput = outputTokens.coerceAtLeast(0L)
        val safeCostUsd = if (costUsd.isFinite()) costUsd.coerceAtLeast(0.0) else 0.0
        val costMilli = safeCostUsd * 1000.0
        val now = clockMs()
        val seq = seqGen.getAndIncrement()
        val op = PendingOp.Record(seq, threadId, safeInput, safeOutput, costMilli)

        synchronized(stateLock) {
            pendingOps.addLast(op)
        }
        writer.execute {
            val ok = runCatching {
                store.insert(
                    CostUsageRecord(
                        threadId = threadId,
                        model = model,
                        inputTokens = safeInput,
                        outputTokens = safeOutput,
                        costUsdMilli = costMilli,
                        createdAt = now,
                    ),
                )
            }.isSuccess
            synchronized(stateLock) {
                pendingOps.removeAll { it.seq == seq }
                if (ok) {
                    val bucket = persistedByThread.getOrPut(threadId) { MutableBucket() }
                    bucket.inputTokens += safeInput
                    bucket.outputTokens += safeOutput
                    bucket.costUsdMilli += costMilli
                    bucket.calls += 1L
                }
            }
        }
    }

    /** Returns aggregated usage for [threadId]. Never blocks the Main thread. */
    fun totalsForThread(threadId: String): UsageTotals {
        awaitHydrationIfOffMain()
        synchronized(stateLock) {
            val bucket = persistedByThread[threadId]?.copy() ?: MutableBucket()
            for (op in pendingOps) {
                if (op.threadId != threadId) continue
                when (op) {
                    is PendingOp.Clear -> {
                        bucket.inputTokens = 0L
                        bucket.outputTokens = 0L
                        bucket.costUsdMilli = 0.0
                        bucket.calls = 0L
                    }
                    is PendingOp.Record -> {
                        bucket.inputTokens += op.inputTokens
                        bucket.outputTokens += op.outputTokens
                        bucket.costUsdMilli += op.costUsdMilli
                        bucket.calls += 1L
                    }
                }
            }
            return UsageTotals(
                inputTokens = bucket.inputTokens,
                outputTokens = bucket.outputTokens,
                costUsd = bucket.costUsdMilli / 1000.0,
                calls = bucket.calls,
            )
        }
    }

    /** Returns aggregated usage across all threads. Never blocks the Main thread. */
    fun totalsAll(): UsageTotals {
        awaitHydrationIfOffMain()
        synchronized(stateLock) {
            val working = LinkedHashMap<String, MutableBucket>(persistedByThread.size)
            for ((tid, b) in persistedByThread) {
                working[tid] = b.copy()
            }
            for (op in pendingOps) {
                when (op) {
                    is PendingOp.Clear -> working.remove(op.threadId)
                    is PendingOp.Record -> {
                        val bucket = working.getOrPut(op.threadId) { MutableBucket() }
                        bucket.inputTokens += op.inputTokens
                        bucket.outputTokens += op.outputTokens
                        bucket.costUsdMilli += op.costUsdMilli
                        bucket.calls += 1L
                    }
                }
            }
            var input = 0L
            var output = 0L
            var costMilli = 0.0
            var calls = 0L
            for (bucket in working.values) {
                input += bucket.inputTokens
                output += bucket.outputTokens
                costMilli += bucket.costUsdMilli
                calls += bucket.calls
            }
            return UsageTotals(
                inputTokens = input,
                outputTokens = output,
                costUsd = costMilli / 1000.0,
                calls = calls,
            )
        }
    }

    /** Clears all recorded usage for [threadId] asynchronously on the background thread. */
    fun clearThread(threadId: String) {
        val seq = seqGen.getAndIncrement()
        val op = PendingOp.Clear(seq, threadId)
        synchronized(stateLock) {
            pendingOps.addLast(op)
        }
        writer.execute {
            val ok = runCatching { store.deleteThread(threadId) }.isSuccess
            synchronized(stateLock) {
                pendingOps.removeAll { it.seq == seq }
                if (ok) {
                    persistedByThread.remove(threadId)
                }
            }
        }
    }

    /** Waits for all queued background writes to finish. Used in JVM tests. */
    internal fun awaitIdle() {
        val barrier = CountDownLatch(1)
        writer.execute { barrier.countDown() }
        barrier.await(5, TimeUnit.SECONDS)
    }

    /** Stops the background writer and closes the underlying store. */
    internal fun close() {
        writer.shutdown()
        runCatching { writer.awaitTermination(5, TimeUnit.SECONDS) }
        runCatching { store.close() }
    }

    private fun awaitHydrationIfOffMain() {
        if (hydrated || isMainThreadSafe()) return
        runCatching { hydrationLatch.await(2, TimeUnit.SECONDS) }
    }

    private fun isMainThreadSafe(): Boolean = try {
        val main = Looper.getMainLooper()
        main != null && Looper.myLooper() == main
    } catch (_: Throwable) {
        // JVM unit tests do not mock android.os.Looper.
        false
    }
}
