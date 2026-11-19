package dev.codex.android.hooks

import dev.codex.android.core.CodexJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** When a hook fires in the agent lifecycle. */
enum class HookEvent {
    SESSION_START,
    SESSION_END,
    PRE_TOOL_USE,
    POST_TOOL_USE,
    USER_PROMPT_SUBMIT,
    STOP,
}

/** One registered hook: a shell command bound to an event (or a globbed tool name for tool hooks). */
@Serializable
data class HookDefinition(
    val id: String,
    val event: String,
    val matcher: String = "",
    val command: String,
    val timeoutMs: Long = DEFAULT_HOOK_TIMEOUT_MS,
    val enabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_HOOK_TIMEOUT_MS = 10_000L
    }
}

/** A single hook execution record. */
data class HookExecution(
    val id: String,
    val hookId: String,
    val event: String,
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val timedOut: Boolean,
)

/**
 * Hook engine (Milestone M6) that runs shell hooks on agent lifecycle events.
 *
 * A hook NEVER blocks the main flow:
 * - [fire] returns immediately (fire-and-forget on its own scope); the hook
 *   runs in the background with a hard timeout and its result is only
 *   recorded in [executions] / [lastExecutionsNote].
 * - [runHooks] is the suspending variant for callers that need the veto
 *   answer; each hook still has a hard timeout (default 10s) and an
 *   executor failure is recorded, never thrown — a failing hook can never
 *   break the tool call it was attached to.
 * - `PRE_TOOL_USE` hooks receive tool call details via environment
 *   variables (`CODEX_TOOL_NAME`, `CODEX_TOOL_INPUT_JSON`) and can veto
 *   execution by exiting non-zero — but only through [runHooks], which is
 *   the only path that returns a veto. [fire] never vetoes, and a hook
 *   that times out or crashes never vetoes either: only an explicit
 *   non-zero exit does.
 *
 * Hook definitions live in a JSON file — by convention
 * `.codex/hooks.json` in the workspace (see [hooksFileFor]) — as either
 * `{"hooks": [...]}` or a bare `[...]`. [loadHooksFile] (re)loads one;
 * the constructor loads [configFile] the same way.
 */
class HookEngine(
    private val configFile: File,
    // NOTE: scope comes before shellExecutor on purpose — shellExecutor is
    // the last parameter, so callers can pass it as a trailing lambda.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val shellExecutor: suspend (command: String, env: Map<String, String>, timeoutMs: Long) -> HookExecution? = { _, _, _ -> null },
) {
    private val hooks = ConcurrentHashMap<String, HookDefinition>()
    private val _executions = MutableStateFlow<List<HookExecution>>(emptyList())
    val executions: StateFlow<List<HookExecution>> = _executions.asStateFlow()

    init {
        loadFromDisk()
    }

    companion object {
        /** Conventional location of the workspace hook config: `<workspaceDir>/.codex/hooks.json`. */
        fun hooksFileFor(workspaceDir: File): File = File(workspaceDir, ".codex/hooks.json")
    }

    /** Registers (or replaces) a hook definition. */
    fun registerHook(definition: HookDefinition) {
        require(definition.command.isNotBlank()) { "Hook command must not be blank" }
        hooks[definition.id] = definition
        saveToDisk()
    }

    /** Removes a hook by id. Returns true if it existed. */
    fun unregisterHook(hookId: String): Boolean {
        val removed = hooks.remove(hookId) != null
        if (removed) saveToDisk()
        return removed
    }

    /** Returns all registered hooks (sorted by id for deterministic ordering). */
    fun listHooks(): List<HookDefinition> = hooks.values.sortedBy { it.id }

    /**
     * Fire-and-forget: runs all enabled hooks matching [event] in the
     * background and returns immediately — the caller is never blocked and
     * no veto is possible on this path. Failures and timeouts are recorded
     * in [executions] only. Returns the background [Job] (for tests).
     */
    fun fire(
        event: HookEvent,
        toolName: String? = null,
        toolInputJson: String? = null,
    ): Job = scope.launch {
        runCatching { runHooks(event, toolName, toolInputJson) }
    }

    /**
     * Runs all enabled hooks matching [event] (+ optional [toolName] glob for tool hooks).
     * Returns true if ANY hook vetoed the action (non-zero exit on PRE_TOOL_USE).
     * Never throws: a hook timeout or executor failure is recorded as a
     * failed execution and the remaining hooks still run.
     */
    suspend fun runHooks(
        event: HookEvent,
        toolName: String? = null,
        toolInputJson: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val matching = hooks.values
            .filter { it.enabled && it.event == event.name }
            .filter { def ->
                if (event == HookEvent.PRE_TOOL_USE || event == HookEvent.POST_TOOL_USE) {
                    if (def.matcher.isBlank()) true else globMatches(def.matcher, toolName.orEmpty())
                } else true
            }
            .sortedBy { it.id }

        var vetoed = false
        for (def in matching) {
            val env = buildMap {
                put("CODEX_HOOK_EVENT", event.name)
                if (toolName != null) put("CODEX_TOOL_NAME", toolName)
                if (toolInputJson != null) put("CODEX_TOOL_INPUT_JSON", toolInputJson)
            }
            val started = System.currentTimeMillis()
            var executorError: String? = null
            val result = withTimeoutOrNull(def.timeoutMs.coerceAtLeast(1)) {
                try {
                    shellExecutor(def.command, env, def.timeoutMs)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Timeout cancellation — must propagate so withTimeoutOrNull reports it.
                    throw e
                } catch (e: Exception) {
                    // Executor failure: recorded below, never thrown — a failing
                    // hook must never break the tool call it was attached to.
                    executorError = e.message ?: e.javaClass.simpleName
                    null
                }
            }
            val duration = System.currentTimeMillis() - started
            val exec = when {
                result != null -> result.copy(durationMs = duration)
                executorError != null -> HookExecution(
                    id = "exec_${UUID.randomUUID().toString().take(8)}",
                    hookId = def.id,
                    event = event.name,
                    command = def.command,
                    exitCode = -1,
                    stdout = "",
                    stderr = "Hook failed: $executorError",
                    durationMs = duration,
                    timedOut = false,
                )
                else -> HookExecution(
                    id = "exec_${UUID.randomUUID().toString().take(8)}",
                    hookId = def.id,
                    event = event.name,
                    command = def.command,
                    exitCode = -1,
                    stdout = "",
                    stderr = "Hook timed out after ${def.timeoutMs}ms (never blocks main flow)",
                    durationMs = duration,
                    timedOut = true,
                )
            }
            _executions.value = _executions.value + exec
            // Only an explicit non-zero exit vetoes. A hook that timed out
            // or whose executor failed (result == null) never vetoes — a
            // failure must never block the tool call.
            if (event == HookEvent.PRE_TOOL_USE && result != null && result.exitCode != 0) {
                vetoed = true
            }
        }
        vetoed
    }

    /** Returns a short human-readable note summarizing the last hook executions (for the transcript). */
    fun lastExecutionsNote(limit: Int = 3): String {
        val recent = _executions.value.takeLast(limit)
        if (recent.isEmpty()) return ""
        return recent.joinToString("\n") { exec ->
            "[hook ${exec.event} ${exec.hookId}] exit=${exec.exitCode}${if (exec.timedOut) " (timeout)" else ""} ${exec.stdout.trim().take(80)}"
        }
    }

    /**
     * (Re)loads hook definitions from [file] — `{"hooks": [...]}` or a bare
     * `[...]` — merging by id (a loaded hook replaces a same-id one).
     * Missing/unreadable/malformed files load zero hooks and never throw.
     * Returns the number of hooks loaded.
     */
    fun loadHooksFile(file: File): Int {
        if (!file.isFile) return 0
        val text = runCatching { file.readText() }.getOrNull() ?: return 0
        val loaded: List<HookDefinition> = runCatching {
            CodexJson.decodeFromString(HookDefinitionList.serializer(), text).hooks
        }.getOrElse {
            runCatching {
                CodexJson.decodeFromString(ListSerializer(HookDefinition.serializer()), text)
            }.getOrNull() ?: return 0
        }
        loaded.forEach { hooks[it.id] = it }
        return loaded.size
    }

    private fun loadFromDisk() {
        loadHooksFile(configFile)
    }

    private fun saveToDisk() {
        runCatching {
            configFile.parentFile?.mkdirs()
            val list = HookDefinitionList(hooks.values.sortedBy { it.id })
            configFile.writeText(CodexJson.encodeToString(HookDefinitionList.serializer(), list))
        }
    }

    /** Simple glob matcher supporting `*` and `?` (case-sensitive). */
    internal fun globMatches(pattern: String, value: String): Boolean {
        val regex = buildString {
            append('^')
            for (c in pattern) {
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
            append('$')
        }
        return Regex(regex).matches(value)
    }

    @Serializable
    private data class HookDefinitionList(val hooks: List<HookDefinition> = emptyList())
}
