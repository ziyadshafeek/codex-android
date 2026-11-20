package dev.codex.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for [rateLimitCountdownSeconds]: the whole-second
 * countdown shown above the chat input while the client waits out a
 * rate-limit retry delay. The delay is always rounded up, so the
 * banner never shows 0s for a wait that is still running.
 */
class RateLimitCountdownTest {

    @Test
    fun zeroDelay_showsZeroSeconds() {
        assertEquals(0L, rateLimitCountdownSeconds(0L))
    }

    @Test
    fun oneMillisecond_roundsUpToOneSecond() {
        assertEquals(1L, rateLimitCountdownSeconds(1L))
    }

    @Test
    fun exactlyOneSecond_showsOneSecond() {
        assertEquals(1L, rateLimitCountdownSeconds(1000L))
    }

    @Test
    fun justOverOneSecond_roundsUpToTwoSeconds() {
        assertEquals(2L, rateLimitCountdownSeconds(1001L))
    }

    @Test
    fun thirtyOneSeconds_showsThirtyOneSeconds() {
        assertEquals(31L, rateLimitCountdownSeconds(31000L))
    }

    @Test
    fun negativeDelay_clampsToZero() {
        assertEquals(0L, rateLimitCountdownSeconds(-1L))
        assertEquals(0L, rateLimitCountdownSeconds(-31000L))
    }
}
