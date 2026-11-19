package dev.codex.android.agent

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM unit tests for [ParallelToolRunner].
 *
 * Pure coroutines, no Android framework, no network. Real delays are
 * used (plain [runBlocking], the repo's existing test convention) so the
 * overlap / ordering assertions observe genuine concurrency, not virtual
 * time. Delays are kept small (50–200 ms) so the suite stays fast.
 */
class ParallelToolRunnerTest {

    private val runner = ParallelToolRunner()

    @Test
    fun emptyBatch_returnsEmptyList() = runBlocking {
        assertEquals(emptyList<String>(), runner.runBatched(emptyList()))
    }

    @Test
    fun singleRead_returnsItsResult() = runBlocking {
        val result = runner.runBatched(
            listOf(QueuedCall("c1", isReadOnly = true) { "read-1" }),
        )
        assertEquals(listOf("read-1"), result)
    }

    @Test
    fun singleBarrierCall_runsAloneAndReturnsResult() = runBlocking {
        val result = runner.runBatched(
            listOf(QueuedCall("w1", isReadOnly = false) { "write-1" }),
        )
        assertEquals(listOf("write-1"), result)
    }

    @Test
    fun readsActuallyOverlap_maxConcurrencyGreaterThanOne() = runBlocking {
        val current = AtomicInteger(0)
        val max = AtomicInteger(0)
        fun trackedRead(id: String) = QueuedCall(id, isReadOnly = true) {
            val now = current.incrementAndGet()
            // Max-update without a lock: a lost update can only under-report.
            max.updateAndGet { previous -> maxOf(previous, now) }
            try {
                delay(150)
                id
            } finally {
                current.decrementAndGet()
            }
        }
        val calls = (1..4).map { trackedRead("read-$it") }
        val results = runner.runBatched(calls)
        assertEquals(listOf("read-1", "read-2", "read-3", "read-4"), results)
        assertTrue(
            "reads must overlap: max concurrent was ${max.get()}, expected > 1",
            max.get() > 1,
        )
    }

    @Test
    fun resultsComeBackInInputOrder_evenWhenLaterReadFinishesFirst() = runBlocking {
        // First read is slowest, last read is fastest: completion order is
        // the reverse of input order, results must still follow input.
        val calls = listOf(
            QueuedCall("slow", isReadOnly = true) { delay(200); "slow" },
            QueuedCall("mid", isReadOnly = true) { delay(100); "mid" },
            QueuedCall("fast", isReadOnly = true) { delay(10); "fast" },
        )
        assertEquals(listOf("slow", "mid", "fast"), runner.runBatched(calls))
    }

    @Test
    fun barrierOrdering_writeRunsOnlyAfterEarlierReadsAndBeforeLaterReads() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        fun read(id: String) = QueuedCall(id, isReadOnly = true) {
            events.add("start:$id")
            delay(120)
            events.add("end:$id")
            id
        }
        fun write(id: String) = QueuedCall(id, isReadOnly = false) {
            events.add("start:$id")
            delay(50)
            events.add("end:$id")
            id
        }
        val results = runner.runBatched(
            listOf(read("r1"), read("r2"), write("w1"), read("r3"), read("r4")),
        )
        assertEquals(listOf("r1", "r2", "w1", "r3", "r4"), results)

        val endR1 = events.indexOf("end:r1")
        val endR2 = events.indexOf("end:r2")
        val startW = events.indexOf("start:w1")
        val endW = events.indexOf("end:w1")
        val startR3 = events.indexOf("start:r3")
        val startR4 = events.indexOf("start:r4")
        assertTrue("r1 must finish before the write starts", endR1 in 0 until startW)
        assertTrue("r2 must finish before the write starts", endR2 in 0 until startW)
        assertTrue("r3 must start only after the write finishes", startR3 > endW)
        assertTrue("r4 must start only after the write finishes", startR4 > endW)
    }

    @Test
    fun barrierCalls_runSeriallyInOrder() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        fun write(id: String) = QueuedCall(id, isReadOnly = false) {
            events.add("start:$id")
            delay(60)
            events.add("end:$id")
            id
        }
        assertEquals(
            listOf("w1", "w2"),
            runner.runBatched(listOf(write("w1"), write("w2"))),
        )
        assertEquals(
            listOf("start:w1", "end:w1", "start:w2", "end:w2"),
            events.toList(),
        )
    }

    @Test
    fun semaphoreBound_maxConcurrentNeverExceedsSix() = runBlocking {
        val current = AtomicInteger(0)
        val max = AtomicInteger(0)
        val calls = (1..12).map { n ->
            QueuedCall("read-$n", isReadOnly = true) {
                val now = current.incrementAndGet()
                max.updateAndGet { previous -> maxOf(previous, now) }
                try {
                    delay(150)
                    "read-$n"
                } finally {
                    current.decrementAndGet()
                }
            }
        }
        val results = runner.runBatched(calls)
        assertEquals((1..12).map { "read-$it" }, results)
        assertTrue(
            "reads must still overlap: max concurrent was ${max.get()}",
            max.get() > 1,
        )
        assertTrue(
            "semaphore bound violated: max concurrent was ${max.get()}, expected <= 6",
            max.get() <= 6,
        )
    }

    @Test
    fun customBound_limitsConcurrency() = runBlocking {
        val bounded = ParallelToolRunner(maxParallelReads = 2)
        val current = AtomicInteger(0)
        val max = AtomicInteger(0)
        val calls = (1..6).map { n ->
            QueuedCall("read-$n", isReadOnly = true) {
                val now = current.incrementAndGet()
                max.updateAndGet { previous -> maxOf(previous, now) }
                try {
                    delay(100)
                    "read-$n"
                } finally {
                    current.decrementAndGet()
                }
            }
        }
        bounded.runBatched(calls)
        assertTrue(
            "max concurrent was ${max.get()}, expected <= 2",
            max.get() <= 2,
        )
    }

    @Test
    fun failingRead_cancelsBatchAndLaterCallsNeverStart() = runBlocking {
        val barrierStarted = AtomicInteger(0)
        val afterStarted = AtomicInteger(0)
        val calls = listOf(
            QueuedCall("slow", isReadOnly = true) { delay(200); "slow" },
            QueuedCall("boom", isReadOnly = true) {
                throw IllegalStateException("boom")
            },
            QueuedCall("write", isReadOnly = false) {
                barrierStarted.incrementAndGet()
                "write"
            },
            QueuedCall("after", isReadOnly = true) {
                afterStarted.incrementAndGet()
                "after"
            },
        )
        try {
            runner.runBatched(calls)
            fail("Expected IllegalStateException from the failing read")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals("barrier after the failure must never run", 0, barrierStarted.get())
        assertEquals("read after the failure must never run", 0, afterStarted.get())
    }

    @Test
    fun cancellation_propagatesAndStopsTheBatch() = runBlocking {
        val started = AtomicInteger(0)
        val calls = (1..4).map { n ->
            QueuedCall("read-$n", isReadOnly = true) {
                started.incrementAndGet()
                delay(5_000)
                "read-$n"
            }
        }
        val job = async { runner.runBatched(calls) }
        delay(50)
        job.cancel()
        try {
            job.await()
            fail("Expected CancellationException after cancelling the batch")
        } catch (e: CancellationException) {
            // Expected: cancellation propagates out of runBatched.
        }
        assertTrue(job.isCancelled)
        assertTrue("at least one read must have started", started.get() >= 1)
    }

    @Test
    fun invalidBound_isRejected() {
        try {
            ParallelToolRunner(maxParallelReads = 0)
            fail("Expected IllegalArgumentException for maxParallelReads = 0")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun defaultBound_isSix() {
        assertEquals(6, ParallelToolRunner.DEFAULT_MAX_PARALLEL_READS)
        assertEquals(6, ParallelToolRunner().maxParallelReads)
    }
}
