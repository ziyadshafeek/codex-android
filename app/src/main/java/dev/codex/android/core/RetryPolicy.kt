package dev.codex.android.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random

data class RetryPolicy(val maxAttempts: Int = 3, val baseDelayMs: Long = 500L, val maxDelayMs: Long = 8000L)

/** Longest a single retry wait may last, even when the server asks for more. */
const val MAX_RATE_LIMIT_WAIT_MS: Long = 60_000L

/**
 * The wait before a retry after a rate limit: the longer of the
 * exponential backoff and the server's Retry-After, capped at
 * [MAX_RATE_LIMIT_WAIT_MS] even when the server asks for more.
 * Pure function (no waiting) so the cap is unit-testable instantly.
 */
internal fun rateLimitDelayMs(
    policy: RetryPolicy,
    attempt: Int,
    retryAfterSeconds: Long?,
): Long {
    val backoff = min(policy.maxDelayMs, policy.baseDelayMs * (1L shl attempt))
    val retryAfterMs = (retryAfterSeconds ?: 0L)
        .coerceAtLeast(0L)
        .coerceAtMost(MAX_RATE_LIMIT_WAIT_MS / 1000L) * 1000L
    return maxOf(backoff, retryAfterMs)
}

suspend fun <T> withRetry(
    policy: RetryPolicy = RetryPolicy(),
    isRetryable: (Throwable) -> Boolean = { it is CodexError.Network || it is CodexError.RateLimit || it is CodexError.Timeout },
    onRetryWait: ((delayMs: Long, error: Throwable) -> Unit)? = null,
    block: suspend () -> T
): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!isRetryable(e) || attempt >= policy.maxAttempts - 1) throw e
            val backoff = min(policy.maxDelayMs, policy.baseDelayMs * (1L shl attempt))
            // A rate limit carries the server's own wait time (Retry-After):
            // honour it instead of the short exponential backoff, so the
            // next attempt is not burned while the limit is still in force.
            val delayMs = if (e is CodexError.RateLimit) {
                rateLimitDelayMs(policy, attempt, e.retryAfterSeconds)
            } else {
                backoff
            }
            // No jitter on a server-directed wait: the reported delay must
            // be the delay actually waited, so a countdown UI stays exact.
            val jitterMs = if (e is CodexError.RateLimit) 0L else Random.nextLong(0L, 251L)
            val totalDelayMs = delayMs + jitterMs
            onRetryWait?.invoke(totalDelayMs, e)
            delay(totalDelayMs)
            attempt++
        }
    }
}
