package dev.codex.android.ui

import dev.codex.android.commands.CommandMeta
import dev.codex.android.commands.SlashCommandRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Primary tabs of the OLED Material 3 Workbench.
 * Terminal hosts the real H2 terminal screen; Browser hosts the real M7
 * verification browser ([PreviewScreen]).
 */
enum class WorkbenchTab(val label: String, val testTag: String) {
    AGENT("Agent", "workbench_tab_agent"),
    FILES("Files", "workbench_tab_files"),
    DIFF_CI("Diff & CI", "workbench_tab_diff_ci"),
    TERMINAL("Terminal", "workbench_tab_terminal"),
    BROWSER("Browser", "workbench_tab_browser"),
}

/** One-tap composer execution mode (Build writes code; Plan stays read-only). */
enum class ComposerMode(val label: String) {
    BUILD("Build"),
    PLAN("Plan"),
}

/** Active contextual palette trigger detected in the power composer text. */
enum class ComposerTriggerKind {
    NONE,
    FILE_ATTACH,
    AGENT_MENTION,
    SHELL_COMMAND,
    SLASH_COMMAND,
}

/** Parsed composer trigger for autocomplete popups and `!cmd` routing. */
data class ComposerTrigger(
    val kind: ComposerTriggerKind,
    val query: String,
    val tokenStartIndex: Int,
)

/** Display-friendly cost summary decoupled from numeric token width in CostTelemetry. */
data class CostStatsSummary(
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val costUsd: Double = 0.0,
    val calls: Long = 0L,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

/** Pure UI state for [WorkbenchScreen]. */
data class WorkbenchUiState(
    val activeTab: WorkbenchTab = WorkbenchTab.AGENT,
    val composerMode: ComposerMode = ComposerMode.BUILD,
    val composerText: String = "",
    val attachedFiles: List<String> = emptyList(),
    val showThinking: Boolean = false,
    val showDetails: Boolean = false,
    val usedContextTokens: Long = 0L,
    val contextWindowTokens: Int = 128_000,
    val isRunning: Boolean = false,
    val activeTrigger: ComposerTrigger = ComposerTrigger(ComposerTriggerKind.NONE, "", 0),
) {
    val contextPercent: Int
        get() = computeContextPercent(usedContextTokens, contextWindowTokens)

    val isBangCommand: Boolean
        get() = composerText.trimStart().startsWith("!") && composerText.trimStart().length > 1
}

/** Built-in subagent mentions surfaced by `@agent` autocomplete. */
val DEFAULT_SUBAGENT_MENTIONS: List<String> = listOf(
    "explore",
    "worker",
    "review",
    "general",
    "browser",
)

/**
 * Detects the active composer shortcut trigger from [input]:
 * - Leading `!` -> inline Linux shell shortcut (`!pytest`, `!git status`)
 * - Leading `/` (first token only) -> slash command palette
 * - Trailing `@token` -> agent mention when matching a known agent, otherwise fuzzy `@file` attach
 */
fun detectComposerTrigger(
    input: String,
    knownAgents: List<String> = DEFAULT_SUBAGENT_MENTIONS,
): ComposerTrigger {
    val trimmedStart = input.trimStart()
    val leadingSpaces = input.length - trimmedStart.length
    if (trimmedStart.startsWith("!")) {
        return ComposerTrigger(
            kind = ComposerTriggerKind.SHELL_COMMAND,
            query = trimmedStart.removePrefix("!").trimStart(),
            tokenStartIndex = leadingSpaces,
        )
    }
    if (trimmedStart.startsWith("/") && !trimmedStart.contains(' ') && !trimmedStart.contains('\n')) {
        return ComposerTrigger(
            kind = ComposerTriggerKind.SLASH_COMMAND,
            query = trimmedStart.removePrefix("/"),
            tokenStartIndex = leadingSpaces,
        )
    }
    val tokenStart = input.lastIndexOfAny(charArrayOf(' ', '\n', '\t')) + 1
    val currentToken = input.substring(tokenStart)
    if (currentToken.startsWith("@")) {
        val query = currentToken.removePrefix("@")
        val matchesAgent = query.isNotEmpty() && knownAgents.any {
            it.startsWith(query, ignoreCase = true)
        }
        return ComposerTrigger(
            kind = if (matchesAgent) ComposerTriggerKind.AGENT_MENTION else ComposerTriggerKind.FILE_ATTACH,
            query = query,
            tokenStartIndex = tokenStart,
        )
    }
    return ComposerTrigger(ComposerTriggerKind.NONE, "", input.length)
}

/**
 * Case-insensitive fuzzy subsequence matcher for `@file` attachment.
 * Ranks exact substring matches above subsequence matches, then shorter paths first.
 */
fun fuzzyFilterFiles(files: List<String>, query: String, limit: Int = 8): List<String> {
    if (limit <= 0) return emptyList()
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return files.take(limit)

    data class Scored(val path: String, val score: Int)

    fun score(path: String): Int? {
        val lower = path.lowercase()
        val fileName = lower.substringAfterLast('/')
        if (fileName == needle) return 0
        if (fileName.startsWith(needle)) return 1
        if (fileName.contains(needle)) return 2
        if (lower.contains(needle)) return 3
        // Subsequence match
        var qIdx = 0
        var gaps = 0
        var lastMatch = -1
        for (i in lower.indices) {
            if (qIdx < needle.length && lower[i] == needle[qIdx]) {
                if (lastMatch >= 0) gaps += (i - lastMatch - 1)
                lastMatch = i
                qIdx++
            }
        }
        return if (qIdx == needle.length) 10 + gaps else null
    }

    return files.mapNotNull { path -> score(path)?.let { Scored(path, it) } }
        .sortedWith(compareBy<Scored> { it.score }.thenBy { it.path.length }.thenBy { it.path })
        .take(limit)
        .map { it.path }
}

/** Filters available `@agent` mentions by case-insensitive prefix. */
fun filterAgentMentions(agents: List<String>, query: String): List<String> {
    val needle = query.trim().removePrefix("@").lowercase()
    if (needle.isEmpty()) return agents
    return agents.filter { it.lowercase().startsWith(needle) }
}

/** Filters slash commands via the existing [SlashCommandRouter.autocomplete]. */
fun filterSlashPalette(query: String): List<CommandMeta> =
    SlashCommandRouter.autocomplete(query)

/** Replaces the active trigger token in [input] with [replacement] + trailing space. */
fun applyComposerCompletion(
    input: String,
    trigger: ComposerTrigger,
    replacement: String,
): String {
    if (trigger.kind == ComposerTriggerKind.NONE) return input
    val safeStart = trigger.tokenStartIndex.coerceIn(0, input.length)
    val prefix = input.substring(0, safeStart)
    return "$prefix$replacement "
}

/** Computes clamped 0..100 context window usage percentage. */
fun computeContextPercent(usedTokens: Long, contextWindow: Int): Int {
    if (contextWindow <= 0 || usedTokens <= 0L) return 0
    return ((usedTokens.toDouble() / contextWindow.toDouble()) * 100.0)
        .toInt()
        .coerceIn(0, 100)
}

/**
 * Pure, JVM-testable state holder driving [WorkbenchScreen].
 */
class WorkbenchStateHolder(
    initialState: WorkbenchUiState = WorkbenchUiState(),
    private val knownAgents: List<String> = DEFAULT_SUBAGENT_MENTIONS,
) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<WorkbenchUiState> = _state.asStateFlow()

    fun selectTab(tab: WorkbenchTab) {
        _state.update { it.copy(activeTab = tab) }
    }

    fun toggleComposerMode() {
        _state.update { current ->
            val next = if (current.composerMode == ComposerMode.BUILD) {
                ComposerMode.PLAN
            } else {
                ComposerMode.BUILD
            }
            current.copy(composerMode = next)
        }
    }

    fun setComposerMode(mode: ComposerMode) {
        _state.update { it.copy(composerMode = mode) }
    }

    fun updateComposerText(text: String) {
        _state.update { current ->
            current.copy(
                composerText = text,
                activeTrigger = detectComposerTrigger(text, knownAgents),
            )
        }
    }

    fun insertPin(pinToken: String) {
        val clean = pinToken.trim()
        if (clean.isEmpty()) return
        _state.update { current ->
            val nextText = if (current.composerText.isBlank()) {
                "$clean "
            } else {
                "${current.composerText.trimEnd()} $clean "
            }
            current.copy(
                composerText = nextText,
                activeTrigger = detectComposerTrigger(nextText, knownAgents),
            )
        }
    }

    fun attachFile(relativePath: String) {
        val clean = relativePath.trim()
        if (clean.isEmpty()) return
        _state.update { current ->
            if (clean in current.attachedFiles) current
            else current.copy(attachedFiles = current.attachedFiles + clean)
        }
    }

    fun removeAttachedFile(relativePath: String) {
        _state.update { current ->
            current.copy(attachedFiles = current.attachedFiles.filterNot { it == relativePath })
        }
    }

    fun applyCompletion(replacement: String, attachAsFile: String? = null) {
        _state.update { current ->
            val nextText = applyComposerCompletion(current.composerText, current.activeTrigger, replacement)
            val nextAttached = if (attachAsFile != null && attachAsFile !in current.attachedFiles) {
                current.attachedFiles + attachAsFile
            } else {
                current.attachedFiles
            }
            current.copy(
                composerText = nextText,
                attachedFiles = nextAttached,
                activeTrigger = detectComposerTrigger(nextText, knownAgents),
            )
        }
    }

    fun clearComposer() {
        _state.update {
            it.copy(
                composerText = "",
                attachedFiles = emptyList(),
                activeTrigger = ComposerTrigger(ComposerTriggerKind.NONE, "", 0),
            )
        }
    }

    fun toggleThinking() {
        _state.update { it.copy(showThinking = !it.showThinking) }
    }

    fun toggleDetails() {
        _state.update { it.copy(showDetails = !it.showDetails) }
    }

    fun updateContextUsage(usedTokens: Long, contextWindow: Int) {
        _state.update {
            it.copy(
                usedContextTokens = usedTokens.coerceAtLeast(0L),
                contextWindowTokens = contextWindow.coerceAtLeast(1),
            )
        }
    }

    fun setRunning(running: Boolean) {
        _state.update { it.copy(isRunning = running) }
    }
}
