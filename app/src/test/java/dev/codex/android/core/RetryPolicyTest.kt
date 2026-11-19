package dev.codex.android.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
