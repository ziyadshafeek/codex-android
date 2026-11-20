package dev.codex.android.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM unit tests for [withRetry] / [RetryPolicy].
 *
 * Delays are kept tiny (baseDelayMs = 1) so the tests stay fast. Note
 * [withRetry] adds up to 250 ms of random jitter per retry, so a test
 * that exhausts its attempts still takes well under a second.
 * Plain [runBlocking] only — no kotlinx-coroutines-test dependency.
 */
class RetryPolicyTest {

    private val fastPolicy = RetryPolicy(maxAttempts = 3, baseDelayMs = 1L, maxDelayMs = 10L)
    private val twoAttemptPolicy = RetryPolicy(maxAttempts = 2, baseDelayMs = 1L, maxDelayMs = 10L)

    @Test
    fun retriesNetworkUpToMaxAttempts_thenRethrows() = runBlocking {
        var attempts = 0
        try {
            withRetry(fastPolicy) {
                attempts++
                throw CodexError.Network()
            }
            fail("Expected CodexError.Network after exhausting attempts")
        } catch (e: CodexError.Network) {
            // Expected.
        }
        assertEquals("maxAttempts = 3 means 3 total calls", 3, attempts)
    }

    @Test
    fun retriesRateLimitUpToMaxAttempts_thenRethrows() = runBlocking {
        var attempts = 0
        try {
            withRetry(twoAttemptPolicy) {
                attempts++
                throw CodexError.RateLimit(retryAfterSeconds = 1L)
            }
            fail("Expected CodexError.RateLimit after exhausting attempts")
        } catch (e: CodexError.RateLimit) {
            // Expected.
        }
        assertEquals(2, attempts)
    }

    @Test
    fun retriesTimeoutUpToMaxAttempts_thenRethrows() = runBlocking {
        var attempts = 0
        try {
            withRetry(twoAttemptPolicy) {
                attempts++
                throw CodexError.Timeout()
            }
            fail("Expected CodexError.Timeout after exhausting attempts")
        } catch (e: CodexError.Timeout) {
            // Expected.
        }
        assertEquals(2, attempts)
    }

    @Test
    fun succeedsOnRetry_afterTransientFailure() = runBlocking {
        var attempts = 0
        val result = withRetry(fastPolicy) {
            attempts++
            if (attempts < 2) throw CodexError.Network()
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, attempts)
    }

    @Test
    fun doesNotRetryOnAuth_throwsImmediately() = runBlocking {
        var attempts = 0
        try {
            withRetry(fastPolicy) {
                attempts++
                throw CodexError.Auth("bad key")
            }
            fail("Expected CodexError.Auth immediately, without retry")
        } catch (e: CodexError.Auth) {
            // Expected.
        }
        assertEquals("Auth must not be retried", 1, attempts)
    }

    @Test
    fun cancellation_passesThroughWithoutRetry() = runBlocking {
        var attempts = 0
        try {
            withRetry(fastPolicy) {
                attempts++
                throw CancellationException("cancelled by caller")
            }
            fail("Expected CancellationException to pass through")
        } catch (e: CancellationException) {
            // Expected: cancellation is never retried or swallowed.
        }
        assertEquals("Cancellation must not be retried", 1, attempts)
    }

    @Test
    fun rateLimit_waitsOutRetryAfterInsteadOfBackoff() = runBlocking {
        // The bug: a RateLimit(1s) was retried after the ~1ms backoff,
        // burning the attempt while the limit was still in force.
        // The wait must be the server's Retry-After, not the backoff.
        var attempts = 0
        val reportedDelays = mutableListOf<Long>()
        val startMs = System.currentTimeMillis()
        val result = withRetry(
            policy = fastPolicy,
            onRetryWait = { delayMs, _ -> reportedDelays += delayMs },
        ) {
            attempts++
            if (attempts < 2) throw CodexError.RateLimit(retryAfterSeconds = 1L)
            "ok"
        }
        val elapsedMs = System.currentTimeMillis() - startMs
        assertEquals("ok", result)
        assertEquals(2, attempts)
        assertEquals("one retry wait before the successful attempt", 1, reportedDelays.size)
        assertTrue(
            "onRetryWait must report >= 1000ms, was ${reportedDelays[0]}",
            reportedDelays[0] >= 1000L,
        )
        assertTrue("elapsed must be >= 1s, was $elapsedMs", elapsedMs >= 1000L)
    }

    @Test
    fun rateLimitWait_isCappedAtSixtySeconds() {
        // A server asking for 300s must not block the turn for 5 minutes.
        // Pure computation — no real waiting.
        assertEquals(
            MAX_RATE_LIMIT_WAIT_MS,
            rateLimitDelayMs(fastPolicy, attempt = 0, retryAfterSeconds = 300L),
        )
        assertEquals(
            "a 60s ask is honoured in full",
            60_000L,
            rateLimitDelayMs(fastPolicy, attempt = 0, retryAfterSeconds = 60L),
        )
        assertEquals(
            "a 1s ask beats the 1ms backoff",
            1000L,
            rateLimitDelayMs(fastPolicy, attempt = 0, retryAfterSeconds = 1L),
        )
        assertEquals(
            "no Retry-After falls back to the backoff",
            1L,
            rateLimitDelayMs(fastPolicy, attempt = 0, retryAfterSeconds = null),
        )
    }

    @Test
    fun nonRateLimitRetry_keepsBackoffAndReportsIt() = runBlocking {
        // Other retryable errors keep the existing exponential backoff;
        // onRetryWait still reports the actual (backoff + jitter) delay.
        val reportedDelays = mutableListOf<Long>()
        var attempts = 0
        val result = withRetry(
            policy = fastPolicy,
            onRetryWait = { delayMs, _ -> reportedDelays += delayMs },
        ) {
            attempts++
            if (attempts < 2) throw CodexError.Network()
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, reportedDelays.size)
        assertTrue(
            "network retry delay must stay within backoff + jitter, was ${reportedDelays[0]}",
            reportedDelays[0] in 1L..261L,
        )
    }
}
