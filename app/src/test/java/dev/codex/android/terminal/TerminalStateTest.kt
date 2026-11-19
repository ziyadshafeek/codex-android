package dev.codex.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for [TerminalStateHolder] and [Vt100KeyEncoder]. */
class TerminalStateTest {

    @Test
    fun vt100Encoder_encodesExtraKeysAndModifiers() {
        assertEquals("\u001B", Vt100KeyEncoder.encodeExtraKey(ExtraKey.ESC))
        assertEquals("\t", Vt100KeyEncoder.encodeExtraKey(ExtraKey.TAB))
        assertEquals("\u001B[A", Vt100KeyEncoder.encodeExtraKey(ExtraKey.UP))
        assertEquals("\u001B[D", Vt100KeyEncoder.encodeExtraKey(ExtraKey.LEFT))

        // Ctrl+C -> ASCII ETX (0x03)
        assertEquals("\u0003", Vt100KeyEncoder.applyModifiers("c", ctrl = true, alt = false))
        // Alt+b -> ESC + b
        assertEquals("\u001Bb", Vt100KeyEncoder.applyModifiers("b", ctrl = false, alt = true))
    }

    @Test
    fun parseBangCommand_extractsCommandOrReturnsNull() {
        assertEquals("pytest -q", Vt100KeyEncoder.parseBangCommand("!pytest -q"))
        assertEquals("git status", Vt100KeyEncoder.parseBangCommand("  !  git status  "))
        assertNull(Vt100KeyEncoder.parseBangCommand("!"))
        assertNull(Vt100KeyEncoder.parseBangCommand("regular prompt"))
    }

    @Test
    fun stateHolder_managesTabsAndModifierLatches() {
        val holder = TerminalStateHolder()
        holder.addTab("pty_1", "Shell 1")
        holder.addTab("pty_2", "Shell 2")
        assertEquals("pty_2", holder.state.value.activeSessionId)
        assertEquals(2, holder.state.value.tabs.size)

        holder.closeTab("pty_2")
        assertEquals("pty_1", holder.state.value.activeSessionId)
        assertEquals(1, holder.state.value.tabs.size)

        holder.markSessionExited("pty_1")
        assertFalse(holder.state.value.tabs.single().isAlive)

        holder.toggleCtrl()
        assertTrue(holder.state.value.ctrlLatched)
        val (ctrl, alt) = holder.consumeLatchedModifiers()
        assertTrue(ctrl)
        assertFalse(alt)
        assertFalse(holder.state.value.ctrlLatched)
    }
}
