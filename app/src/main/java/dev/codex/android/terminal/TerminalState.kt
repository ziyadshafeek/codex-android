package dev.codex.android.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Built-from-scratch extra-key definitions for the Compose touch bar.
 * NEVER copied from GPL-3.0 `termux-app` ExtraKeysView — standard VT100/ANSI
 * escape sequences implemented cleanly in pure Kotlin.
 */
enum class ExtraKey(val label: String, val testTag: String, val isModifier: Boolean = false) {
    ESC("ESC", "terminal_key_esc"),
    TAB("TAB", "terminal_key_tab"),
    CTRL("CTRL", "terminal_key_ctrl", isModifier = true),
    ALT("ALT", "terminal_key_alt", isModifier = true),
    UP("↑", "terminal_key_up"),
    DOWN("↓", "terminal_key_down"),
    LEFT("←", "terminal_key_left"),
    RIGHT("→", "terminal_key_right"),
    PIPE("|", "terminal_key_pipe"),
    SLASH("/", "terminal_key_slash"),
    DASH("-", "terminal_key_dash"),
}

/** One terminal tab metadata entry tracked by [TerminalStateHolder]. */
data class TerminalTabMeta(
    val sessionId: String,
    val title: String,
    val isAlive: Boolean = true,
)

/** Pure UI state for [TerminalScreen]. */
data class TerminalUiState(
    val tabs: List<TerminalTabMeta> = emptyList(),
    val activeSessionId: String? = null,
    val ctrlLatched: Boolean = false,
    val altLatched: Boolean = false,
    val lastCommandOutput: String = "",
    val phantomReport: String? = null,
)

/**
 * Pure VT100/ANSI sequence mapper for Compose extra keys (JVM-testable).
 * Supports CTRL+letter control codes (`Ctrl+C` -> `\u0003`, `Ctrl+D` -> `\u0004`, etc.)
 * and ALT ESC-prefixing.
 */
object Vt100KeyEncoder {

    fun encodeExtraKey(key: ExtraKey): String? = when (key) {
        ExtraKey.ESC -> "\u001B"
        ExtraKey.TAB -> "\t"
        ExtraKey.UP -> "\u001B[A"
        ExtraKey.DOWN -> "\u001B[B"
        ExtraKey.RIGHT -> "\u001B[C"
        ExtraKey.LEFT -> "\u001B[D"
        ExtraKey.PIPE -> "|"
        ExtraKey.SLASH -> "/"
        ExtraKey.DASH -> "-"
        ExtraKey.CTRL, ExtraKey.ALT -> null
    }

    fun applyModifiers(input: String, ctrl: Boolean, alt: Boolean): String {
        if (input.isEmpty()) return ""
        var transformed = input
        if (ctrl && transformed.length == 1) {
            val ch = transformed[0].uppercaseChar()
            if (ch in 'A'..'Z') {
                transformed = (ch.code - 'A'.code + 1).toChar().toString()
            } else if (ch == '[') {
                transformed = "\u001B"
            }
        }
        return if (alt) "\u001B$transformed" else transformed
    }

    /**
     * Parses a composer `!command` shortcut into the raw Linux shell command,
     * returning null when [input] is not a `!` shortcut.
     */
    fun parseBangCommand(input: String): String? {
        val trimmed = input.trimStart()
        if (!trimmed.startsWith("!")) return null
        return trimmed.removePrefix("!").trim().takeIf { it.isNotEmpty() }
    }
}

/** Pure, JVM-testable state holder for multi-tab PTY terminal management. */
class TerminalStateHolder(initial: TerminalUiState = TerminalUiState()) {

    private val _state = MutableStateFlow(initial)
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    fun addTab(sessionId: String, title: String) {
        _state.update { current ->
            val nextTabs = current.tabs.filterNot { it.sessionId == sessionId } +
                TerminalTabMeta(sessionId = sessionId, title = title, isAlive = true)
            current.copy(tabs = nextTabs, activeSessionId = sessionId)
        }
    }

    fun selectTab(sessionId: String) {
        _state.update { current ->
            if (current.tabs.none { it.sessionId == sessionId }) current
            else current.copy(activeSessionId = sessionId)
        }
    }

    fun closeTab(sessionId: String) {
        _state.update { current ->
            val remaining = current.tabs.filterNot { it.sessionId == sessionId }
            val nextActive = when {
                current.activeSessionId != sessionId -> current.activeSessionId
                remaining.isNotEmpty() -> remaining.last().sessionId
                else -> null
            }
            current.copy(tabs = remaining, activeSessionId = nextActive)
        }
    }

    fun markSessionExited(sessionId: String) {
        _state.update { current ->
            current.copy(
                tabs = current.tabs.map {
                    if (it.sessionId == sessionId) it.copy(isAlive = false) else it
                },
            )
        }
    }

    fun toggleCtrl() {
        _state.update { it.copy(ctrlLatched = !it.ctrlLatched) }
    }

    fun toggleAlt() {
        _state.update { it.copy(altLatched = !it.altLatched) }
    }

    fun consumeLatchedModifiers(): Pair<Boolean, Boolean> {
        val prev = _state.value
        if (prev.ctrlLatched || prev.altLatched) {
            _state.update { it.copy(ctrlLatched = false, altLatched = false) }
        }
        return prev.ctrlLatched to prev.altLatched
    }

    fun setLastCommandOutput(output: String) {
        _state.update { it.copy(lastCommandOutput = output) }
    }

    fun setPhantomReport(report: String?) {
        _state.update { it.copy(phantomReport = report) }
    }
}
