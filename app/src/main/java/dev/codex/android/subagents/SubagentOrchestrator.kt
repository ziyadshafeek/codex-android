package dev.codex.android.subagents

import android.content.Context
import dev.codex.android.agent.AgentEvent
import dev.codex.android.agent.AgentLoop
import dev.codex.android.agent.AgentRuntime
import dev.codex.android.agent.PlanStore
import dev.codex.android.api.OkHttpOpenAiClient
import dev.codex.android.context.DatabaseThreadHistoryStore
import dev.codex.android.context.ThreadHistoryStore
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.WorkspaceEntity
import dev.codex.android.data.KeyStore
import dev.codex.android.security.DefaultApprovalGate
import dev.codex.android.security.ExecutionMode
import dev.codex.android.security.GlobDecision
import dev.codex.android.security.GlobPermissionEngine
import dev.codex.android.security.GlobRule
import dev.codex.android.security.PolicyDecision
import dev.codex.android.security.SecretScrubber
import dev.codex.android.security.ToolRisk
import dev.codex.android.skills.SkillStore
import dev.codex.android.tools.Tool
import dev.codex.android.tools.ToolRegistry
import dev.codex.android.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Minimal metadata for a running subagent. */
data class SubagentInfo(
    val id: String,
    val task: String,
    val parentThreadId: String?,
    val createdAtMillis: Long,
    val status: String,
    val output: String = "",
    val agentName: String = "general",
    val threadId: String? = null,
)

/**
 * Seam over [AgentRuntime] so [SubagentOrchestrator] is unit-testable on the
 * JVM (no Android `Context` is needed to fake this; the real runtime is
 * reached only through [AgentRuntimeTurnRunner]).
 *
 * NOTE: [AgentRuntime] itself does NOT implement this interface — the task
 * forbids making production runtime types implement test-only interfaces.
 * The thin adapter below is the only production implementation.
 */
interface TurnRunner {
    fun startTurn(
        threadId: String,
        goal: String,
        mode: ExecutionMode,
        maxSteps: Int = 25,
        runner: suspend AgentRuntime.TurnContext.() -> String,
    ): Boolean

    fun stopTurn(threadId: String)

    /** Suspends until the turn for [threadId] is no longer running. */
    suspend fun awaitTurnFinished(threadId: String)
}

/** Production [TurnRunner]: delegates every call to the [AgentRuntime] object.
 * It owns the application [Context] (startTurn needs one), so the
 * orchestrator itself never touches `Context` and stays JVM-testable.
 */
class AgentRuntimeTurnRunner(
    context: Context,
) : TurnRunner {
    private val appContext: Context = context.applicationContext

    override fun startTurn(
        threadId: String,
        goal: String,
        mode: ExecutionMode,
        maxSteps: Int,
        runner: suspend AgentRuntime.TurnContext.() -> String,
    ): Boolean = AgentRuntime.startTurn(appContext, threadId, goal, mode, maxSteps, runner)

    override fun stopTurn(threadId: String) {
        AgentRuntime.stop(threadId)
    }

    override suspend fun awaitTurnFinished(threadId: String) {
        // startTurn marks the thread running synchronously before it returns
        // true, so by the time this is called the entry exists; a finished
        // (or never-started) thread has isRunning != true and returns at once.
        AgentRuntime.threads.first { states -> states[threadId]?.isRunning != true }
    }
}

/**
 * Builds and drives a real [AgentLoop] for one subagent turn — the same
 * construction [dev.codex.android.ui.ChatViewModel.runAgentTurn] uses
 * (KeyStore endpoint/key/model, `ToolRegistry.withBuiltins`, `PlanStore`,
 * enabled skills, `DefaultApprovalGate`), with the subagent's effective
 * glob engine wrapped around every tool (see [GlobGatedTool]).
 */
interface SubagentLoopFactory {
    suspend fun runGoal(
        turn: AgentRuntime.TurnContext,
        threadId: String,
        goal: String,
        mode: ExecutionMode,
        effectiveRules: GlobPermissionEngine,
    ): String

    /** Steering messages drained during the last run (consumed by the caller). */
    fun drainSteeringNotes(): List<String>
}

/** Production [SubagentLoopFactory]. */
class DefaultSubagentLoopFactory(
    context: Context,
) : SubagentLoopFactory {
    private val appContext: Context = context.applicationContext
    private val steeringNotes = mutableListOf<String>()

    override suspend fun runGoal(
        turn: AgentRuntime.TurnContext,
        threadId: String,
        goal: String,
        mode: ExecutionMode,
        effectiveRules: GlobPermissionEngine,
    ): String {
        val keyStore = KeyStore(appContext)
        val baseUrl = keyStore.getBaseUrl()
        val apiKey = keyStore.getApiKey()
            ?: throw IllegalStateException(
                "Add your API endpoint and key in Settings first — the app never ships with a key or a provider."
            )
        val model = keyStore.getModel()
        val skills = try {
            SkillStore(appContext).enabled()
        } catch (_: Exception) {
            emptyList()
        }
        val gate = DefaultApprovalGate()
        val base = ToolRegistry.withBuiltins(appContext)
        val gated = ToolRegistry().apply {
            base.all().forEach { register(GlobGatedTool(it, effectiveRules)) }
        }
        val client = OkHttpOpenAiClient(baseUrl = baseUrl, apiKey = apiKey, model = model)
        val loop = AgentLoop(
            client = client,
            tools = gated,
            gate = gate,
            planStore = PlanStore(appContext),
            model = model,
            skills = skills,
        )
        // Same event → TurnContext mapping as ChatViewModel.runAgentTurn:
        // one step per plan / model reply / tool start; approvals go through
        // the policy first (a BLOCKed tool is never opened for approval).
        return runWithEventChannel<AgentEvent, String>(
            consume = { event ->
                turn.drainSteeringMessages().forEach { steeringNotes += it }
                when (event) {
                    is AgentEvent.PlanCreated -> turn.nextStepOrThrow("Plan: ${event.plan.goal}")
                    is AgentEvent.PlanUpdated -> Unit
                    is AgentEvent.AssistantText -> {
                        turn.nextStepOrThrow("Model reply")
                        turn.emitAssistantDelta(event.text)
                    }
                    is AgentEvent.ToolStarted -> turn.nextStepOrThrow("Tool: ${event.toolName}")
                    is AgentEvent.ToolFinished -> Unit
                    is AgentEvent.ApprovalRequested -> {
                        val decision = AgentRuntime.policy.evaluate(
                            threadId,
                            event.action.toolName,
                            ToolRisk.EXTERNAL_OR_DESTRUCTIVE,
                            mode,
                        )
                        if (decision == PolicyDecision.BLOCK) {
                            gate.answer(event.action.id, false)
                        } else {
                            val approved = turn.requestApproval(
                                event.action.toolName,
                                event.action.description,
                                event.action.argsJson,
                            )
                            gate.answer(event.action.id, approved)
                        }
                    }
                    is AgentEvent.ApprovalResolved -> Unit
                    is AgentEvent.Error -> Unit
                    is AgentEvent.Finished -> Unit
                }
            },
            produce = { events -> loop.run(goal, events) },
        )
    }

    override fun drainSteeringNotes(): List<String> {
        val notes = steeringNotes.toList()
        steeringNotes.clear()
        return notes
    }
}

/**
 * One registered tool wrapped with the subagent's effective glob engine.
 * Any path-like argument (`path`, `file_path`, `file`, `directory`, …)
 * that the engine DENYs refuses the call before the delegate ever runs;
 * ASK is left to the loop's approval gate. Every result crossing the
 * subagent → parent boundary is scrubbed (see [isolateToolResult]).
 */
internal class GlobGatedTool(
    private val delegate: Tool,
    private val engine: GlobPermissionEngine,
) : Tool {
    override val name: String get() = delegate.name
    override val description: String get() = delegate.description
    override val parametersSchema: JsonObject get() = delegate.parametersSchema
    override val sensitive: Boolean get() = delegate.sensitive

    override suspend fun execute(args: JsonObject): ToolResult {
        for (path in candidatePaths(args)) {
            if (engine.decide(path) == GlobDecision.DENY) {
                return ToolResult(
                    ok = false,
                    output = "",
                    error = "Blocked by subagent permissions: $path is denied for tool ${delegate.name}",
                )
            }
        }
        return isolateToolResult(delegate.execute(args))
    }

    private fun candidatePaths(args: JsonObject): List<String> {
        val keys = setOf(
            "path", "file_path", "filepath", "file",
            "directory", "dir", "target", "source", "destination",
        )
        return args.entries
            .filter { (key, value) ->
                key.lowercase() in keys && value is JsonPrimitive && value.isString
            }
            .map { it.value.jsonPrimitive.content }
    }
}

/**
 * Real tool-result passthrough for the subagent boundary (replaces the
 * staged placeholder that returned the result untouched): secrets are
 * scrubbed from both output and error text before anything from a
 * subagent reaches the parent thread, logs, or UI.
 */
fun isolateToolResult(result: ToolResult): ToolResult = result.copy(
    output = SecretScrubber.scrub(result.output),
    error = result.error?.let { SecretScrubber.scrub(it) },
)

/** One subagent definition, from a builtin or a `.codex/agents/` TOML file. */
data class AgentDefinition(
    val name: String,
    val description: String = "",
    val model: String? = null,
    val instructions: String = "",
    val tools: List<String> = emptyList(),
    val rules: List<GlobRule> = emptyList(),
    val defaultMode: ExecutionMode = ExecutionMode.AUTO,
)

/** Built-in agents (always available; a same-named TOML file overrides one). */
val BUILTIN_AGENTS: Map<String, AgentDefinition> = mapOf(
    "explore" to AgentDefinition(
        name = "explore",
        description = "Explore the codebase and report findings. Read-only.",
        instructions = "Explore the codebase and report what you find. Do not modify any file.",
        defaultMode = ExecutionMode.PLAN,
    ),
    "worker" to AgentDefinition(
        name = "worker",
        description = "Carry out the assigned implementation task.",
        instructions = "Carry out the assigned task step by step. Keep changes minimal and focused.",
        defaultMode = ExecutionMode.AUTO,
    ),
    "review" to AgentDefinition(
        name = "review",
        description = "Review a diff or a piece of code and report issues. Read-only.",
        instructions = "Review the given code or diff and report concrete issues with file and line references. Do not modify any file.",
        defaultMode = ExecutionMode.PLAN,
    ),
    "general" to AgentDefinition(
        name = "general",
        description = "General-purpose subagent.",
        instructions = "",
        defaultMode = ExecutionMode.AUTO,
    ),
)

/**
 * Minimal TOML parser for `.codex/agents/` TOML files — no TOML dependency is
 * added for this (the repo has none). Supported subset, nothing more:
 *
 * ```toml
 * name = "scout"
 * description = "…"
 * model = "gpt-4o-mini"
 * instructions = """multi-line …"""
 * tools = ["read_file", "shell"]
 * mode = "PLAN"            # PLAN | ASK | AUTO, default AUTO
 * [permissions]
 * allow = ["src/**"]
 * deny = ["**/*.key"]
 * ```
 *
 * Unknown keys/sections are ignored; `#` comments and blank lines are
 * skipped. A malformed file throws [IllegalArgumentException] — callers
 * that scan a directory ([loadAgentDefinitions]) skip such files.
 */
fun parseAgentToml(text: String, fallbackName: String): AgentDefinition {
    var name: String? = null
    var description = ""
    var model: String? = null
    var instructions = ""
    var tools: List<String> = emptyList()
    var mode = ExecutionMode.AUTO
    val allow = mutableListOf<GlobRule>()
    val deny = mutableListOf<GlobRule>()
    var section = ""

    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        i++
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        if (line.startsWith("[") && line.endsWith("]")) {
            section = line.substring(1, line.length - 1).trim()
            continue
        }
        val eq = line.indexOf('=')
        if (eq < 0) continue
        val key = line.substring(0, eq).trim()
        var value = line.substring(eq + 1).trim()
        // Triple-quoted string: consume until the closing """.
        if (value.startsWith("\"\"\"")) {
            val sb = StringBuilder(value.removePrefix("\"\"\""))
            var closed = value.removePrefix("\"\"\"").contains("\"\"\"")
            if (closed) {
                sb.setLength(sb.indexOf("\"\"\""))
            } else {
                while (i < lines.size) {
                    val next = lines[i]
                    i++
                    val closeAt = next.indexOf("\"\"\"")
                    if (closeAt >= 0) {
                        sb.append('\n').append(next.substring(0, closeAt))
                        closed = true
                        break
                    }
                    sb.append('\n').append(next)
                }
                if (!closed) throw IllegalArgumentException("Unterminated triple-quoted string for '$key'")
            }
            value = sb.toString()
            when (key) {
                "instructions" -> if (section.isEmpty()) instructions = value
                "description" -> if (section.isEmpty()) description = value
            }
            continue
        }
        val stringValue = unquote(value)
        val arrayValue = parseStringArray(value)
        when (section) {
            "" -> when (key) {
                "name" -> name = stringValue
                "description" -> description = stringValue ?: ""
                "model" -> model = stringValue
                "instructions" -> instructions = stringValue ?: ""
                "tools" -> tools = arrayValue
                "mode" -> mode = when (stringValue?.uppercase()) {
                    "PLAN" -> ExecutionMode.PLAN
                    "ASK" -> ExecutionMode.ASK
                    "AUTO", null -> ExecutionMode.AUTO
                    else -> throw IllegalArgumentException("Unknown mode '$stringValue' in agent TOML")
                }
            }
            "permissions" -> when (key) {
                "allow" -> allow += arrayValue.map { GlobRule(it, GlobDecision.ALLOW) }
                "deny" -> deny += arrayValue.map { GlobRule(it, GlobDecision.DENY) }
            }
        }
    }
    return AgentDefinition(
        name = name ?: fallbackName,
        description = description,
        model = model,
        instructions = instructions,
        tools = tools,
        rules = allow + deny,
        defaultMode = mode,
    )
}

private fun unquote(value: String): String? {
    val t = value.trim()
    if (t.length >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
        return t.substring(1, t.length - 1)
    }
    return null
}

private fun parseStringArray(value: String): List<String> {
    val t = value.trim()
    if (!t.startsWith("[") || !t.endsWith("]")) return emptyList()
    return Regex("\"([^\"]*)\"|'([^']*)'").findAll(t).map { m ->
        m.groups[1]?.value ?: m.groups[2]?.value.orEmpty()
    }.toList()
}

/**
 * Loads agent definitions from `<agentsDir>` TOML files on top of [BUILTIN_AGENTS]
 * (a TOML file whose stem matches a builtin name overrides that builtin).
 * Unreadable or malformed files are skipped, never fatal.
 */
fun loadAgentDefinitions(agentsDir: File?): Map<String, AgentDefinition> {
    val out = BUILTIN_AGENTS.toMutableMap()
    if (agentsDir == null || !agentsDir.isDirectory) return out
    agentsDir.listFiles { f -> f.isFile && f.name.endsWith(".toml") }
        ?.sortedBy { it.name }
        ?.forEach { file ->
            runCatching {
                val def = parseAgentToml(file.readText(), file.nameWithoutExtension)
                out[def.name] = def
            }
        }
    return out
}

/**
 * Parallel subagent orchestrator for Milestone M6.
 *
 * Each subagent runs its own [AgentRuntime] thread with the parent's
 * permissions intersected with its own child permissions:
 *
 *   effective = parent ∩ child ([GlobPermissionEngine.intersect])
 *
 * The intersection can only *narrow* the child; a child can NEVER widen
 * the parent's permissions. [SECRET_DENY_RULES] are layered on top of
 * both sides at every spawn, so secret files are denied even when the
 * parent or child rules would allow them. Nested subagents intersect
 * again: [nestedOrchestrator] hands the child the already-intersected
 * engine as its parent, so depth only ever narrows.
 *
 * All writes go through the child engine: every tool in the subagent's
 * [AgentLoop] is wrapped in [GlobGatedTool], so every tool call is
 * evaluated against the intersected rules before it runs.
 */
class SubagentOrchestrator private constructor(
    private val history: ThreadHistoryStore,
    private val turnRunner: TurnRunner,
    private val loopFactory: SubagentLoopFactory,
    private val parentEngine: GlobPermissionEngine,
    private val agentsDir: File?,
    private val scope: CoroutineScope,
) {
    constructor(
        history: ThreadHistoryStore,
        turnRunner: TurnRunner,
        loopFactory: SubagentLoopFactory,
        parentRules: List<GlobRule> = emptyList(),
        agentsDir: File? = null,
        scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    ) : this(
        history,
        turnRunner,
        loopFactory,
        effectiveChildRules(parentRules, emptyList()),
        agentsDir,
        scope,
    )

    /** Convenience production constructor straight from the database + app context. */
    constructor(
        context: Context,
        database: CodexDatabase,
        parentRules: List<GlobRule> = emptyList(),
    ) : this(
        DatabaseThreadHistoryStore(database),
        AgentRuntimeTurnRunner(context),
        DefaultSubagentLoopFactory(context),
        parentRules = parentRules,
    )

    private val _subagents = MutableStateFlow<List<SubagentInfo>>(emptyList())
    val subagents: StateFlow<List<SubagentInfo>> = _subagents.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val threadIds = ConcurrentHashMap<String, String>()

    /** Lazily loaded agent definitions (builtins + `.codex/agents/` TOML files). */
    val agents: Map<String, AgentDefinition> by lazy { loadAgentDefinitions(agentsDir) }

    companion object {
        /**
         * Paths that are ALWAYS denied for every subagent, whatever the
         * parent or child rules say: environment files, private keys,
         * Java keystores. This is layered on at every spawn and every
         * nesting level — it can never be widened away.
         */
        val SECRET_DENY_RULES: List<GlobRule> = listOf(
            GlobRule("**/.env*", GlobDecision.DENY),
            GlobRule("**/*.pem", GlobDecision.DENY),
            GlobRule("**/*.jks", GlobDecision.DENY),
            GlobRule("**/*.keystore", GlobDecision.DENY),
        )

        /**
         * "Allow everything except secrets" as an engine: a blanket ALLOW
         * listed BEFORE the secret DENYs, so last-match-wins inside the
         * layer keeps the secrets denied and everything else allowed.
         * This is the layer that enforces [SECRET_DENY_RULES] without
         * denying paths the rules never mention.
         */
        val SECRET_ENGINE: GlobPermissionEngine = GlobPermissionEngine(
            listOf(GlobRule("**", GlobDecision.ALLOW)) + SECRET_DENY_RULES,
        )

        /** Identity element for intersection: allows every path. */
        private val ALLOW_ALL_ENGINE: GlobPermissionEngine = GlobPermissionEngine(
            listOf(GlobRule("**", GlobDecision.ALLOW)),
        )

        /**
         * Engine for one rule list. An EMPTY list means "no restriction"
         * (allow-all), NOT default-deny: a bare [GlobPermissionEngine]
         * built from no rules decides DENY for every path (its layer has
         * no match), so intersecting with it would deny everything — which
         * would make the default `spawnSubagent(task)` (empty child rules)
         * unable to use any path-taking tool at all.
         */
        internal fun engineFor(rules: List<GlobRule>): GlobPermissionEngine =
            if (rules.isEmpty()) ALLOW_ALL_ENGINE else GlobPermissionEngine(rules)

        /**
         * Effective permission engine for parent + child rules: the child
         * can only narrow (never widen) permissions, and [SECRET_DENY_RULES]
         * are enforced on top via [SECRET_ENGINE] at every level.
         * Empty parent/child lists mean "no restriction" on that side.
         */
        fun effectiveChildRules(
            parentRules: List<GlobRule>,
            childRules: List<GlobRule>,
        ): GlobPermissionEngine {
            return engineFor(parentRules)
                .intersect(engineFor(childRules))
                .intersect(SECRET_ENGINE)
        }
    }

    /** Effective permission engine for a child, intersected from the parent's (secrets always denied). */
    fun effectiveRules(childRules: List<GlobRule>): GlobPermissionEngine =
        parentEngine.intersect(effectiveChildRules(emptyList(), childRules))

    /** Checks a path against the effective child rules. */
    fun isPathAllowed(path: String, childRules: List<GlobRule>): Boolean =
        effectiveRules(childRules).decide(path) == GlobDecision.ALLOW

    /** A child orchestrator whose parent is this one's effective engine: nesting only ever narrows. */
    fun nestedOrchestrator(childRules: List<GlobRule>): SubagentOrchestrator =
        SubagentOrchestrator(history, turnRunner, loopFactory, effectiveRules(childRules), agentsDir, scope)

    /** Spawns a bounded parallel subagent on its own isolated thread. */
    suspend fun spawnSubagent(
        task: String,
        agentName: String = "general",
        childRules: List<GlobRule> = emptyList(),
        mode: ExecutionMode? = null,
        maxSteps: Int = 25,
    ): SubagentInfo {
        val definition = agents[agentName] ?: agents.getValue("general")
        val runMode = mode ?: definition.defaultMode
        val effective = effectiveRules(childRules + definition.rules)
        val id = "sub_${UUID.randomUUID().toString().take(8)}"
        val info = SubagentInfo(
            id = id,
            task = task,
            parentThreadId = null,
            createdAtMillis = System.currentTimeMillis(),
            status = "running",
            agentName = definition.name,
        )
        val goal = if (definition.instructions.isNotBlank()) {
            "${definition.instructions}\n\nTask: $task"
        } else {
            "Subagent task: $task"
        }
        // Publish the info BEFORE launching the job: the job's first
        // updateInfo() (e.g. the start-refused -> failed path) must find
        // the row, or a fast failure is lost and the subagent reads
        // "running" forever. (CI caught this race: the launched job won
        // the scheduling race and updated a list the row had not joined.)
        // Atomic update: a plain read-modify-write here can lose to a
        // concurrent updateInfo from a finishing job (lost-update race).
        _subagents.update { it + info }
        val job = scope.launch {
            var threadId: String? = null
            try {
                // Thread-exists-first (same FK discipline as ChatViewModel):
                // the workspace row must exist before the thread row does.
                history.ensureWorkspace(
                    WorkspaceEntity(
                        id = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID,
                        name = "This Phone",
                        kind = "phone",
                        rootPath = "",
                        createdAt = System.currentTimeMillis(),
                    )
                )
                val thread = history.createThread(
                    workspaceId = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID,
                    title = "Subagent: ${task.take(40)}",
                    mode = runMode.name,
                )
                threadId = thread.id
                val tid = thread.id
                threadIds[id] = tid
                updateInfo(id) { it.copy(threadId = tid) }
                val started = turnRunner.startTurn(tid, goal, runMode, maxSteps) {
                    loopFactory.runGoal(this, tid, goal, runMode, effective)
                }
                if (!started) {
                    updateInfo(id) { it.copy(status = "failed", output = "Could not start: a turn is already running for this thread.") }
                    return@launch
                }
                turnRunner.awaitTurnFinished(tid)
                val answer = history.getMessages(tid)
                    .lastOrNull { it.role == "assistant" }
                    ?.content.orEmpty()
                val steering = loopFactory.drainSteeringNotes()
                val output = buildString {
                    append(SecretScrubber.scrub(answer))
                    steering.forEach { append("\n[steering noted: $it]") }
                }.trim()
                updateInfo(id) { it.copy(status = "done", output = output) }
            } catch (e: CancellationException) {
                updateInfo(id) { it.copy(status = "stopped") }
                throw e
            } catch (e: Exception) {
                updateInfo(id) {
                    it.copy(status = "failed", output = SecretScrubber.scrub(e.message ?: e.javaClass.simpleName))
                }
            } finally {
                threadIds.remove(id)
            }
        }
        jobs[id] = job
        return info
    }

    /** Terminates and removes the subagent with [subagentId] (cancels the job and stops its thread). */
    fun killSubagent(subagentId: String): Boolean {
        val removed = _subagents.value.firstOrNull { it.id == subagentId } ?: return false
        threadIds.remove(subagentId)?.let { turnRunner.stopTurn(it) }
        jobs.remove(subagentId)?.cancel()
        // Remove by ID, not by object equality: the cancelled job's
        // catch runs concurrently and replaces this row with a
        // status="stopped" copy, so removing the captured object (data
        // class equality, status included) silently removes nothing.
        // Both this removal and updateInfo use atomic .update: plain
        // read-modify-write lets a stale updateInfo write land after
        // the removal and resurrect the row (the lost-update half of
        // the same race — CI flake at the isEmpty assert).
        _subagents.update { current -> current.filterNot { it.id == removed.id } }
        return true
    }

    /** Returns the live snapshot of subagents. */
    fun listSubagents(): List<SubagentInfo> = _subagents.value

    /** Stops all subagents and clears the list. */
    fun clearAll() {
        threadIds.values.forEach { turnRunner.stopTurn(it) }
        threadIds.clear()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _subagents.value = emptyList()
    }

    private fun updateInfo(id: String, block: (SubagentInfo) -> SubagentInfo) {
        // Atomic update (CAS loop): a plain read-modify-write here can
        // land after a concurrent killSubagent removal and resurrect the
        // removed row — the lost-update half of the kill race.
        _subagents.update { current -> current.map { if (it.id == id) block(it) else it } }
    }
}

/**
 * Local copy of the event-channel bridge (ChatViewModel's `runWithEventChannel`
 * is `internal` to the ui package): an UNLIMITED Channel queues every event
 * emitted before the consumer attaches, and the consumer is joined before
 * the run returns, so no early or trailing event is lost.
 */
private suspend fun <T, R> runWithEventChannel(
    consume: suspend (T) -> Unit,
    produce: suspend (SendChannel<T>) -> R,
): R = coroutineScope {
    val events = Channel<T>(Channel.UNLIMITED)
    val collector = launch {
        for (event in events) {
            consume(event)
        }
    }
    val result = try {
        produce(events)
    } finally {
        events.close()
    }
    collector.join()
    result
}
