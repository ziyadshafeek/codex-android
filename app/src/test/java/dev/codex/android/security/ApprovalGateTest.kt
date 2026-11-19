package dev.codex.android.security

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM unit tests for [DefaultApprovalGate].
 *
 * No Android framework, no UI, no network: the gate is pure coroutines.
 * Every suspending wait is driven with plain [runBlocking] (no
 * kotlinx-coroutines-test dependency).
 */
class ApprovalGateTest {

    private fun action(id: String = "action-1") = PendingAction(
        id = id,
        toolName = "fake_tool",
        description = "Do a fake sensitive thing",
        argsJson = "{}",
    )

    @Test
    fun answerTrue_resolvesTrue() = runBlocking {
        val gate = DefaultApprovalGate()
        val pending = async { gate.requestApproval(action()) }

        // Give the async block a chance to publish the pending action.
        delay(50)
        assertEquals(action(), gate.pending.value)

        gate.answer("action-1", true)
        assertTrue(pending.await())
        assertNull(gate.pending.value)
    }

    @Test
    fun answerFalse_resolvesFalse() = runBlocking {
        val gate = DefaultApprovalGate()
        val pending = async { gate.requestApproval(action()) }

        delay(50)
        assertEquals(action(), gate.pending.value)

        gate.answer("action-1", false)
        assertFalse(pending.await())
        assertNull(gate.pending.value)
    }

    @Test
    fun staleId_isIgnored_gateStaysPending() = runBlocking {
        val gate = DefaultApprovalGate()
        val pending = async { gate.requestApproval(action()) }

        delay(50)
        assertEquals(action(), gate.pending.value)

        // Wrong id: the gate must ignore it entirely.
        gate.answer("some-other-id", true)
        delay(50)
        assertFalse("stale answer must not resolve the wait", pending.isCompleted)
        assertEquals(action(), gate.pending.value)

        // The correct id still resolves it.
        gate.answer("action-1", false)
        assertFalse(pending.await())
        assertNull(gate.pending.value)
    }

    @Test
    fun secondRequestWhilePending_throwsIllegalStateException() = runBlocking {
        val gate = DefaultApprovalGate()
        val first = async { gate.requestApproval(action("first")) }

        delay(50)
        assertEquals("first", gate.pending.value?.id)

        try {
            gate.requestApproval(action("second"))
            fail("Expected IllegalStateException for a second pending approval")
        } catch (e: IllegalStateException) {
            // Expected: at most one approval may be pending.
        }

        // The first request is unaffected and still resolves normally.
        gate.answer("first", true)
        assertTrue(first.await())
        assertNull(gate.pending.value)
    }

    @Test
    fun cancellation_deniesAndRethrows() = runBlocking {
        val gate = DefaultApprovalGate()
        val pending = async { gate.requestApproval(action()) }

        delay(50)
        assertEquals(action(), gate.pending.value)

        pending.cancel()
        try {
            pending.await()
            fail("Expected CancellationException after cancelling the wait")
        } catch (e: CancellationException) {
            // Expected: DefaultApprovalGate rethrows cancellation (it never
            // swallows it), after completing the deferred as denied.
        }

        assertTrue(pending.isCancelled)
        assertNull("pending must be cleared after cancellation", gate.pending.value)

        // The gate is usable again after a cancelled wait.
        val next = async { gate.requestApproval(action("next")) }
        delay(50)
        gate.answer("next", true)
        assertTrue(next.await())
    }
}
