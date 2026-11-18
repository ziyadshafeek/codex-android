package dev.codex.android.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random

data class RetryPolicy(val maxAttempts: Int = 3, val baseDelayMs: Long = 500L, val maxDelayMs: Long = 8000L)

suspend fun <T> withRetry(
    policy: RetryPolicy = RetryPolicy(),
    isRetryable: (Throwable) -> Boolean = { it is CodexError.Network || it is CodexError.RateLimit || it is CodexError.Timeout },
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
            val jitterMs = Random.nextLong(0L, 251L)
            delay(backoff + jitterMs)
            attempt++
        }
    }
}
