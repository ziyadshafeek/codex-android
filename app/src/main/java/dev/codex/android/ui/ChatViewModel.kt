package dev.codex.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.codex.android.agent.AgentEvent
import dev.codex.android.agent.AgentLoop
import dev.codex.android.agent.AgentRuntime
import dev.codex.android.agent.PendingApproval
import dev.codex.android.agent.PlanStore
import dev.codex.android.agent.channelEventCollector
import dev.codex.android.api.OkHttpOpenAiClient
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.KeyStore
import dev.codex.android.data.MemoryStore
import dev.codex.android.data.TimelineMessage
import dev.codex.android.data.WorkspaceEntity
import dev.codex.android.security.ApprovalGate
import dev.codex.android.security.DefaultApprovalGate
import dev.codex.android.security.ExecutionMode
import dev.codex.android.security.PendingAction
import dev.codex.android.security.PolicyDecision
import dev.codex.android.security.ToolRisk
import dev.codex.android.skills.SkillStore
import dev.codex.android.tools.Tool
import dev.codex.android.tools.ToolRegistry
import dev.codex.android.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class ChatUiMessage(
    val role: String,
    val text: String,
    val isError: Boolean = false,
    /** Stable id so a restore can dedupe a message already on screen. */
    val id: String = UUID.randomUUID().toString(),
)
// roles: "user", "assistant", "system"

/** One past user goal for the History screen. */
data class HistoryEntry(val text: String, val createdAtMillis: Long)

/**
 * Merges a just-restored history batch in front of the messages already
 * on screen (BUG 1). Restored (older) first, in-session (newer) after.
 *
 * A message appended while restore was suspended can also be present in
 * the restored batch (it was persisted before restore read the DB): the
 * restored duplicate is dropped and the in-session copy keeps its place.
 * Dedupe is by stable [ChatUiMessage.id] first, then by (role, text)
 * across the restore boundary — the restored copy is a fresh object, so
 * its id differs from the in-session one. Duplicates WITHIN either side
 * are kept: genuine repeats in history are real messages.
 */
internal fun mergeRestored(
    restored: List<ChatUiMessage>,
    current: List<ChatUiMessage>,
): List<ChatUiMessage> {
    val seenIds = current.map { it.id }.toSet()
    val seenContent = current.map { it.role to it.text }.toSet()
    val kept = restored.filterNot { it.id in seenIds || (it.role to it.text) in seenContent }
    return (kept + current).distinctBy { it.id }
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    // KeyStore's constructor does no I/O (prefs resolve lazily from a
    // process-wide cache — item 8), so this field initialiser is cheap
    // on the main thread; the store is warmed on IO in init below.
    val keyStore: KeyStore = KeyStore(application)
    private val planStore: PlanStore = PlanStore(application)
    private val registry: ToolRegistry = ToolRegistry.withBuiltins(application)
    private val memoryStore: MemoryStore = MemoryStore(application)
    private val skillStore: SkillStore = SkillStore(application)

    val gate: ApprovalGate = DefaultApprovalGate()

    private val _messages = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    val messages: StateFlow<List<ChatUiMessage>> = _messages.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _hasKey = MutableStateFlow(false)
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    // --- Step 3 (v0.8.0): thin UI binding over AgentRuntime. The runtime
    // owns the run (its own scope, survives Activity destruction and
    // app-switching); this ViewModel only mirrors the current thread's
    // runtime state and hands the loop to it.
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _pendingApproval = MutableStateFlow<PendingApproval?>(null)
    val pendingApproval: StateFlow<PendingApproval?> = _pendingApproval.asStateFlow()

    private val _activeThreadId = MutableStateFlow<String?>(null)
    val activeThreadId: StateFlow<String?> = _activeThreadId.asStateFlow()

    /** The codex.db thread row this chat sends into; created lazily. */
    private var currentThreadId: String? = null
    private val threadMutex = Mutex()

    init {
        // Key-store warm-up (item 8): the encrypted store is created
        // here on Dispatchers.IO, not on the main thread. _hasKey stays
        // false — banner shown — until this refresh lands, which is the
        // correct default when no key is set, so the no-key flow is
        // unaffected.
        viewModelScope.launch(Dispatchers.IO) {
            KeyStore.warmUp(getApplication<Application>())
            refreshKeyState()
        }
        viewModelScope.launch {
            try {
                planStore.loadLatest()?.let { plan ->
                    append(ChatUiMessage("system", "Resumed plan: ${plan.goal}"))
                }
            } catch (e: Exception) {
                // Never crash on a corrupt plan.
            }
        }
        // Seed built-in skills and restore recent chat history. All DB work
        // runs off the main thread, and a corrupt DB must never crash the
        // app or block the UI.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                skillStore.seedBuiltins()
                val recent = memoryStore.recentMessages(100)
                // History: distinct user goals, latest occurrence kept,
                // newest first, capped at 50.
                _history.value = recent
                    .filter { it.role == "user" }
                    .sortedByDescending { it.createdAtMillis }
                    .distinctBy { it.content }
                    .take(50)
                    .map { HistoryEntry(it.content, it.createdAtMillis) }
                // Chat restore keeps its old behaviour: newest 20 only.
                val restore = recent.takeLast(20)
                if (restore.isNotEmpty()) {
                    val restored = restore.map { stored ->
                        val role = when (stored.role) {
                            "user" -> "user"
                            "assistant" -> "assistant"
                            else -> "system"
                        }
                        ChatUiMessage(role, stored.content)
                    }
                    _messages.update { mergeRestored(restored, it) }
                }
            } catch (e: Exception) {
                // Never crash on a corrupt memory/skills DB.
            }
        }
        // Mirror the runtime's per-thread state into the UI bindings:
        // isRunning / streaming text / pending approval all come from
        // AgentRuntime.threads, never from a loop owned by this ViewModel.
        viewModelScope.launch {
            AgentRuntime.threads.collect { states ->
                val state = currentThreadId?.let { states[it] }
                _isRunning.value = state?.isRunning == true
                _streamingText.value = state?.streamingAssistantText.orEmpty()
                _pendingApproval.value = state?.pendingApproval
            }
        }
    }

    private fun append(message: ChatUiMessage) {
        _messages.update { it + message }
    }

    /**
     * Records a sent goal in this session's History (item 3): _history
     * was only seeded in init, so a goal sent this session never
     * appeared until the next launch. Newest first, de-duplicated by
     * text (a repeat moves to the top, as the init seeding does), capped
     * at 50 — mirroring the init seeding. Called ONLY after a run has
     * actually started (see sendGoal): a tap that starts nothing records
     * nothing.
     */
    private fun recordHistory(goal: String) {
        val entry = HistoryEntry(goal, System.currentTimeMillis())
        _history.update { current ->
            (listOf(entry) + current.filter { it.text != goal }).take(50)
        }
    }

    fun refreshKeyState() {
        // Both halves are required: a key alone is not enough, since a
        // run still needs a base URL to call.
        _hasKey.value = keyStore.hasKey() && keyStore.getBaseUrl().isNotBlank()
    }

    fun approvePending(approved: Boolean) {
        // The runtime owns the approval: answer it there, so the in-app
        // dialog and the notification shade resolve the same wait. The
        // runner bridge then answers the loop's gate.
        val pendingRuntime = _pendingApproval.value
        if (pendingRuntime != null) {
            AgentRuntime.answerApproval(
                getApplication(),
                pendingRuntime.approvalId,
                approved,
            )
            return
        }
        // Fallback: an approval that reached the loop's gate before the
        // runtime published its own pending state.
        val pendingGate = gate.pending.value ?: return
        gate.answer(pendingGate.id, approved)
    }

    /** Sends a mid-turn instruction to the active run, if any. */
    fun steer(text: String) {
        val threadId = currentThreadId ?: return
        AgentRuntime.steer(threadId, text)
    }

    /**
     * Synchronous Main-thread guard (BUG 2): set before any launch, reset
     * only after startTurn resolves. Two rapid taps on Main both run
     * sendGoal synchronously — the second sees the guard and returns
     * before any side effect. [AgentRuntime.startTurn] remains the atomic
     * authority (a run started elsewhere still returns false there), so
     * the racy `_isRunning.value` pre-check is gone.
     */
    private var startingTurn = false

    fun sendGoal(goal: String) {
        if (goal.isBlank()) return
        if (startingTurn) return
        startingTurn = true
        if (!_hasKey.value) {
            // No run starts on this path, so the guard is released and
            // nothing is recorded: a tap that starts nothing must not
            // pollute History or memory (BUG 2 side-effect leak). The
            // user's message and the no-key error are still shown.
            startingTurn = false
            append(ChatUiMessage("user", goal))
            append(
                ChatUiMessage(
                    "system",
                    "Add your API endpoint and key in Settings first — the app never ships with a key or a provider.",
                    isError = true
                )
            )
            return
        }
        // Capture credentials now: the runner executes in AgentRuntime's
        // scope, after this function returns.
        val baseUrl = keyStore.getBaseUrl()
        val apiKey = keyStore.getApiKey() ?: run {
            startingTurn = false
            return
        }
        val model = keyStore.getModel()
        val mode = ExecutionMode.AUTO
        viewModelScope.launch {
            try {
                val threadId = ensureThread(goal)
                // startTurn returns false when a run is already active for
                // this thread: one run per thread at a time. The user's
                // message, the History entry and the memory save happen
                // ONLY once the run has actually started, so a refused
                // second send leaves no phantom unanswered message and no
                // History or memory pollution.
                val started = AgentRuntime.startTurn(
                    getApplication(),
                    threadId,
                    goal,
                    mode,
                ) {
                    runAgentTurn(this, threadId, goal, baseUrl, apiKey, model, mode)
                }
                if (started) {
                    append(ChatUiMessage("user", goal))
                    recordHistory(goal)
                    // Fire-and-forget: a memory failure must never affect
                    // the chat.
                    launch(Dispatchers.IO) {
                        try {
                            memoryStore.saveMessage("user", goal)
                        } catch (e: Exception) {
                            // Never crash on a memory failure.
                        }
                    }
                }
            } finally {
                startingTurn = false
            }
        }
    }

    /**
     * Thread-exists-first guarantee (step 2 FK discipline): messages
     * reference threads, and threads reference workspaces, with FK
     * constraints ON — so the workspace row and the thread row must both
     * exist before AgentRuntime.startTurn appends anything for the thread.
     * The workspace is re-asserted (upsert) and the thread is created
     * here, before startTurn is ever called; the mutex makes the
     * check-then-create atomic within this ViewModel.
     */
    private suspend fun ensureThread(goal: String): String {
        currentThreadId?.let { return it }
        return threadMutex.withLock {
            currentThreadId?.let { return@withLock it }
            val db = CodexDatabase.getInstance(getApplication())
            db.upsertWorkspace(
                WorkspaceEntity(
                    id = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID,
                    name = "This Phone",
                    kind = "phone",
                    rootPath = "",
                    createdAt = System.currentTimeMillis(),
                )
            )
            val thread = db.createThread(
                workspaceId = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID,
                title = goal.take(60).ifBlank { "New thread" },
                mode = ExecutionMode.AUTO.name,
            )
            currentThreadId = thread.id
            _activeThreadId.value = thread.id
            thread.id
        }
    }

    /**
     * The TurnContext runner bridge: drives the existing AgentLoop (not
     * rewritten) and translates its events into TurnContext calls.
     *
     * Event → TurnContext mapping:
     * - PlanCreated     → nextStepOrThrow("Plan: …") — step 1, run start.
     * - AssistantText   → nextStepOrThrow("Model reply") + emitAssistantDelta(text).
     * - ToolStarted     → nextStepOrThrow("Tool: …") — one step per tool call.
     * - ToolFinished / PlanUpdated / Error / Finished → UI narration only
     *   (no step: the step was counted at ToolStarted / the reply).
     * - ApprovalRequested → policy check first (a BLOCKed tool is never
     *   opened for approval); otherwise requestApproval(...) and then
     *   answer the loop's gate with the result.
     * - Every event     → drainSteeringMessages() (step boundary).
     *
     * The collector is attached BEFORE loop.run() starts emitting, and
     * events travel through a Channel (BUG 3), which queues anything
     * emitted before the collector subscribes — so no early event is lost
     * either way. The step budget is enforced by nextStepOrThrow:
     * its IllegalStateException fails the collector child, coroutineScope
     * cancels the loop and rethrows it, and it is recorded in the DB by
     * AgentRuntime as "Error: Stopped: reached max step budget (N)".
     *
     * Streaming caveat: AgentLoop swallows the client's per-token
     * onPartialText (it is a no-op in AgentLoop.run), so AssistantText
     * events carry one full turn's text each — streamingAssistantText
     * therefore grows per model turn, not per token.
     */
    private suspend fun runAgentTurn(
        turn: AgentRuntime.TurnContext,
        threadId: String,
        goal: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        mode: ExecutionMode,
    ): String {
        val db = CodexDatabase.getInstance(getApplication())
        // BUG 3: a Channel, not a SharedFlow. MutableSharedFlow with
        // replay=0 buffers NOTHING while nobody collects — the first event
        // emitted before the collector subscribes is dropped immediately,
        // however large extraBufferCapacity is. A Channel queues every
        // pre-collection event in FIFO order and delivers each exactly
        // once. UNLIMITED (not BUFFERED): the collector does suspending
        // DB/approval work, and backpressure must never stall the loop
        // mid-approval; a run is bounded by the step budget, so the queue
        // stays small.
        val events = Channel<AgentEvent>(Channel.UNLIMITED)
        val eventSink = channelEventCollector(events)
        val finishedEmitted = AtomicBoolean(false)
        // Approval requests the policy already refused: the policy gate
        // answered them without prompting, so the collector must not open
        // a runtime approval for them — and the loop's ApprovalResolved
        // for them is not a user denial either.
        val blockedApprovalIds = mutableSetOf<String>()

        // Load enabled skills before the run; a skills failure degrades
        // to no skills rather than failing the run.
        val skills = try {
            skillStore.enabled()
        } catch (e: Exception) {
            emptyList()
        }
        val client = OkHttpOpenAiClient(baseUrl = baseUrl, apiKey = apiKey, model = model)
        val loop = AgentLoop(
            client = client,
            tools = policyGatedRegistry(threadId, mode, db),
            gate = PolicyApprovalGate(gate, threadId, mode, db),
            planStore = planStore,
            model = model,
            skills = skills,
        )

        val answer: String = try {
            coroutineScope {
                // The collector is attached BEFORE loop.run() starts
                // emitting, and the Channel queues anything emitted before
                // it subscribes — so no early event is lost either way.
                val collector = launch {
                    events.receiveAsFlow().collect { event ->
                        // Drain mid-turn steering at every step boundary.
                        // AgentLoop has no injection point for mid-turn
                        // messages, so steering cannot steer the current
                        // run — it is surfaced and recorded for the next
                        // message instead.
                        turn.drainSteeringMessages().forEach { msg ->
                            append(
                                ChatUiMessage(
                                    "system",
                                    "Steering noted: $msg (applies after this run finishes)"
                                )
                            )
                            try {
                                db.appendMessage(
                                    TimelineMessage(
                                        threadId = threadId,
                                        role = "system",
                                        kind = "text",
                                        content = "Steering noted: $msg",
                                    )
                                )
                            } catch (e: Exception) {
                                // A timeline failure must never break the run.
                            }
                        }
                        when (event) {
                            is AgentEvent.PlanCreated -> {
                                turn.nextStepOrThrow("Plan: ${event.plan.goal}")
                                append(ChatUiMessage("system", "Plan started: ${event.plan.goal}"))
                            }
                            is AgentEvent.PlanUpdated -> {
                                // No message.
                            }
                            is AgentEvent.AssistantText -> {
                                turn.nextStepOrThrow("Model reply")
                                turn.emitAssistantDelta(event.text)
                                append(ChatUiMessage("assistant", event.text))
                            }
                            is AgentEvent.ToolStarted -> {
                                turn.nextStepOrThrow("Tool: ${event.toolName}")
                                append(ChatUiMessage("system", "Running ${event.toolName}…"))
                            }
                            is AgentEvent.ToolFinished -> {
                                val output = event.output
                                val truncated = if (output.length > 300) output.take(300) + "…" else output
                                append(ChatUiMessage("system", "${event.toolName} → $truncated"))
                            }
                            is AgentEvent.ApprovalRequested -> {
                                // A tool the policy BLOCKs is never opened
                                // for approval: the policy gate already
                                // refused it without prompting, so there
                                // is nothing for the user to approve here.
                                val decision = AgentRuntime.policy.evaluate(
                                    threadId,
                                    event.action.toolName,
                                    ToolRisk.EXTERNAL_OR_DESTRUCTIVE,
                                    mode,
                                )
                                if (decision == PolicyDecision.BLOCK) {
                                    blockedApprovalIds += event.action.id
                                    append(
                                        ChatUiMessage(
                                            "system",
                                            "Blocked by policy: ${event.action.toolName}",
                                            isError = true,
                                        )
                                    )
                                } else {
                                    append(
                                        ChatUiMessage(
                                            "system",
                                            "Waiting for your approval: ${event.action.toolName}"
                                        )
                                    )
                                    val approved = turn.requestApproval(
                                        event.action.toolName,
                                        event.action.description,
                                        event.action.argsJson,
                                    )
                                    gate.answer(event.action.id, approved)
                                }
                            }
                            is AgentEvent.ApprovalResolved -> {
                                if (event.action.id in blockedApprovalIds) {
                                    blockedApprovalIds -= event.action.id
                                    // Already reported as "Blocked by
                                    // policy" above; this is not a user
                                    // denial.
                                } else {
                                    append(
                                        ChatUiMessage(
                                            "system",
                                            "You ${if (event.approved) "approved" else "denied"} ${event.action.toolName}"
                                        )
                                    )
                                }
                            }
                            is AgentEvent.Error ->
                                append(
                                    ChatUiMessage(
                                        "assistant",
                                        "Error: ${event.error.message}",
                                        isError = true
                                    )
                                )
                            is AgentEvent.Finished -> {
                                finishedEmitted.set(true)
                                append(ChatUiMessage("assistant", event.answer))
                            }
                        }
                    }
                }
                try {
                    val loopAnswer = loop.run(goal, eventSink)
                    // Give the collector a brief chance to deliver a Finished
                    // event that was emitted just before run() returned.
                    for (i in 0 until 20) {
                        if (finishedEmitted.get()) break
                        delay(10)
                    }
                    if (!finishedEmitted.get() && loopAnswer.isNotBlank()) {
                        append(ChatUiMessage("assistant", loopAnswer))
                    }
                    loopAnswer
                } finally {
                    collector.cancel()
                    events.close()
                }
            }
        } catch (e: IllegalStateException) {
            // The step-budget throw from nextStepOrThrow (a collector
            // child failure, rethrown by coroutineScope) — or any other
            // IllegalStateException from the loop. Show it in the chat,
            // then rethrow so AgentRuntime records it in the DB timeline.
            append(
                ChatUiMessage(
                    "assistant",
                    "Error: ${e.message ?: e.toString()}",
                    isError = true
                )
            )
            throw e
        } finally {
            // Persist the run's final assistant reply (never system or
            // error messages). NonCancellable so a stop/cancel still
            // persists what the user already saw; a memory failure must
            // never affect the chat.
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    _messages.value.lastOrNull {
                        it.role == "assistant" && !it.isError
                    }?.let { memoryStore.saveMessage("assistant", it.text) }
                } catch (e: Exception) {
                    // Never crash on a memory failure.
                }
            }
        }
        return answer
    }

    private fun policyGatedRegistry(threadId: String, mode: ExecutionMode, db: CodexDatabase): ToolRegistry {
        val gated = ToolRegistry()
        registry.all().forEach { tool ->
            gated.register(PolicyGatedTool(tool, threadId, mode, db))
        }
        return gated
    }
}

/**
 * Maps a tool to its policy risk using the codebase's own sensitivity
 * classification: tools that declare themselves sensitive are the
 * mutating/external ones; everything else is read-only.
 *
 * NOTE: only TypeTextTool declares sensitive=true among the built-ins, so
 * in PLAN mode only it is blocked; ScheduleTask/GenerateApp declare
 * themselves non-sensitive (see their KDoc) and therefore run as
 * read-only. If a tool's sensitivity flag is ever widened, the policy
 * follows it with no change here.
 */
private fun riskFor(tool: Tool): ToolRisk =
    if (tool.sensitive) ToolRisk.EXTERNAL_OR_DESTRUCTIVE else ToolRisk.READ_ONLY

/** Best-effort audit write: an audit failure must never break the run. */
private suspend fun recordAuditQuietly(
    db: CodexDatabase,
    threadId: String,
    toolName: String,
    decision: PolicyDecision,
    summary: String,
    durationMs: Long,
) {
    try {
        db.recordAudit(
            threadId = threadId,
            toolName = toolName,
            decision = decision.name,
            summary = summary,
            durationMs = durationMs,
        )
    } catch (_: Exception) {
        // An audit failure must never break the run.
    }
}

/**
 * Policy-enforcing ApprovalGate for one run. The loop calls this for
 * sensitive tools; a tool the policy BLOCKs (PLAN mode, DENY rule) is
 * refused immediately WITHOUT prompting — the user must never be asked
 * to approve an action the policy forbids, and refusing here (instead of
 * in the collector) is what keeps the loop from suspending on an
 * approval nobody will answer. Anything else delegates to the real gate,
 * which publishes the pending action for the in-app dialog and suspends
 * until the runner bridge answers it.
 */
private class PolicyApprovalGate(
    private val realGate: ApprovalGate,
    private val threadId: String,
    private val mode: ExecutionMode,
    private val db: CodexDatabase,
) : ApprovalGate {

    override val pending: StateFlow<PendingAction?> get() = realGate.pending

    override suspend fun requestApproval(action: PendingAction): Boolean {
        val decision = AgentRuntime.policy.evaluate(
            threadId,
            action.toolName,
            ToolRisk.EXTERNAL_OR_DESTRUCTIVE,
            mode,
        )
        if (decision == PolicyDecision.BLOCK) {
            recordAuditQuietly(
                db = db,
                threadId = threadId,
                toolName = action.toolName,
                decision = PolicyDecision.BLOCK,
                summary = "Blocked by policy (${mode.name} mode / DENY rule): approval not requested",
                durationMs = 0,
            )
            return false
        }
        return realGate.requestApproval(action)
    }

    override fun answer(id: String, approved: Boolean) = realGate.answer(id, approved)
}

/**
 * One registered tool wrapped with the run's policy check + audit. The
 * loop executes through this wrapper, so the policy is evaluated before
 * every tool call: a BLOCK (PLAN mode, DENY rule) refuses the call and
 * the tool never runs; anything else executes and the decision is
 * audited. A PROMPT_USER decision executes here only because the loop's
 * gate already prompted for it — under the risk mapping, sensitive tools
 * are the only ones that can reach PROMPT_USER, and the loop gates
 * exactly those, with the runner bridge answering the prompt via the
 * runtime (notification or in-app dialog).
 */
private class PolicyGatedTool(
    private val delegate: Tool,
    private val threadId: String,
    private val mode: ExecutionMode,
    private val db: CodexDatabase,
) : Tool {

    override val name: String get() = delegate.name
    override val description: String get() = delegate.description
    override val parametersSchema: JsonObject get() = delegate.parametersSchema
    override val sensitive: Boolean get() = delegate.sensitive

    override suspend fun execute(args: JsonObject): ToolResult {
        val decision = AgentRuntime.policy.evaluate(
            threadId,
            delegate.name,
            riskFor(delegate),
            mode,
        )
        if (decision == PolicyDecision.BLOCK) {
            recordAuditQuietly(
                db = db,
                threadId = threadId,
                toolName = delegate.name,
                decision = PolicyDecision.BLOCK,
                summary = "Blocked by policy (${mode.name} mode / DENY rule)",
                durationMs = 0,
            )
            return ToolResult(
                ok = false,
                output = "",
                error = "Blocked by policy: ${delegate.name} is not allowed in ${mode.name} mode.",
            )
        }
        val startMs = System.currentTimeMillis()
        val result = delegate.execute(args)
        val durationMs = System.currentTimeMillis() - startMs
        recordAuditQuietly(
            db = db,
            threadId = threadId,
            toolName = delegate.name,
            decision = decision,
            summary = "Policy: ${decision.name}; executed in ${durationMs}ms",
            durationMs = durationMs,
        )
        return result
    }
}
