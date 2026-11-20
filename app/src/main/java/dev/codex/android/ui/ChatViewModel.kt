package dev.codex.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.codex.android.agent.AgentEvent
import dev.codex.android.agent.AgentLoop
import dev.codex.android.agent.AgentRuntime
import dev.codex.android.agent.PendingApproval
import dev.codex.android.agent.PlanStore
import dev.codex.android.agent.requiresInAppReview
import dev.codex.android.browser.VerificationBrowser
import dev.codex.android.browser.browserTools
import dev.codex.android.context.ConversationContext
import dev.codex.android.context.buildConversationContext
import dev.codex.android.context.historyBudget
import dev.codex.android.api.OkHttpOpenAiClient
import dev.codex.android.ci.GitHubActionsEngine
import dev.codex.android.linux.ProotShellEngine
import dev.codex.android.linux.RootfsBootstrap
import dev.codex.android.linux.ShellResult
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.KeyStore
import dev.codex.android.data.MemoryStore
import dev.codex.android.data.TimelineMessage
import dev.codex.android.data.WorkspaceEntity
import dev.codex.android.memory.MemoryCommand
import dev.codex.android.memory.PersonalMemory
import dev.codex.android.memory.parseMemoryCommand
import dev.codex.android.security.ApprovalGate
import dev.codex.android.security.ExecutionMode
import dev.codex.android.security.PendingAction
import dev.codex.android.security.PolicyDecision
import dev.codex.android.security.SecretScrubber
import dev.codex.android.security.ToolRisk
import dev.codex.android.skills.SkillStore
import dev.codex.android.tools.Attachment
import dev.codex.android.tools.AttachmentIngest
import dev.codex.android.tools.Tool
import dev.codex.android.tools.ToolRegistry
import dev.codex.android.tools.ToolResult
import dev.codex.android.tools.WorkspaceFileTools
import dev.codex.android.tools.registerDeveloperTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class ChatUiMessage(
    val role: String,
    val text: String,
    val isError: Boolean = false,
    /** Stable id so a restore can dedupe a message already on screen. */
    val id: String = UUID.randomUUID().toString(),
    val contextSummary: Boolean = false,
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


/**
 * Runs [produce] emitting into an unbounded [Channel] while [consume]
 * processes every event in FIFO order (BUG 3 deep fix). Unlike a
 * `MutableSharedFlow(replay = 0)`, events emitted before [consume]
 * begins collecting are buffered and never dropped. Closing the channel
 * in `finally` and joining the consumer guarantees every emitted event
 * is delivered before this function returns, while still propagating
 * consumer failures (e.g. the step-budget `IllegalStateException`) via
 * structured concurrency.
 */
internal suspend fun <T, R> runWithEventChannel(
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

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    // KeyStore's constructor does no I/O (prefs resolve lazily from a
    // process-wide cache — item 8), so this field initialiser is cheap
    // on the main thread; the store is warmed on IO in init below.
    val keyStore: KeyStore = KeyStore(application)
    private val planStore: PlanStore = PlanStore(application)
    private val registry: ToolRegistry = ToolRegistry.withBuiltins(application)
    internal val workspaceFileTools by lazy { WorkspaceFileTools(File(application.filesDir, "workspace")) }
    private val memoryStore: MemoryStore = MemoryStore(application)
    internal val personalMemory = PersonalMemory(application)
    private val memoryCommands = Mutex()
    private var chatGeneration = 0
    private val skillStore: SkillStore = SkillStore(application)

    private val _messages = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    val messages: StateFlow<List<ChatUiMessage>> = _messages.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _hasKey = MutableStateFlow(false)
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    private val _compacting = MutableStateFlow(false)
    val compacting: StateFlow<Boolean> = _compacting.asStateFlow()
    @Volatile private var previewBrowser: VerificationBrowser? = null

    fun attachBrowser(browser: VerificationBrowser?) { previewBrowser = browser }

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

    /**
     * Live rate-limit countdown (seconds remaining), shown above the
     * chat input while the client waits out a 429 retry delay. Null
     * when no rate-limit wait is in progress. Display-only: it never
     * changes the retry behaviour — the client already waits the full
     * delay before retrying.
     */
    private val _rateLimitCountdown = MutableStateFlow<Long?>(null)
    val rateLimitCountdown: StateFlow<Long?> = _rateLimitCountdown.asStateFlow()

    /** Ticker job driving [_rateLimitCountdown] down once per second. */
    private var rateLimitTicker: Job? = null

    /** The codex.db thread row this chat sends into; created lazily. */
    private var currentThreadId: String? = null
    private val threadMutex = Mutex()

    /**
     * Set by [openThread] when Share-to-Codex / the QS tile opens an
     * existing thread. The init restore then skips its message merge
     * (the opened thread's messages are already loaded wholesale and
     * must not be mixed with the global recent history).
     */
    @Volatile
    private var openedThreadId: String? = null
    @Volatile private var freshChat = false

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
                val seeded = recent
                    .filter { it.role == "user" }
                    .sortedByDescending { it.createdAtMillis }
                    .distinctBy { it.content }
                    .take(50)
                    .map { HistoryEntry(it.content, it.createdAtMillis) }
                // Merge, never wholesale-replace: a goal sent while this
                // restore was still reading (slow first DB open, skill
                // seeding) is already in _history via recordHistory and
                // must survive the seed — the restore's read predates the
                // memory save, so the seeded list does not contain it.
                // Same merge discipline as mergeRestored for messages.
                _history.update { current ->
                    val inSession = current.filter { entry ->
                        seeded.none { it.text == entry.text }
                    }
                    (inSession + seeded).take(50)
                }
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
                    _messages.update { current ->
                        // A thread opened via Share/tile replaces the
                        // messages wholesale; the global restore must
                        // not merge other threads' messages into it.
                        if (openedThreadId != null || freshChat) current
                        else mergeRestored(restored, current)
                    }
                }
            } catch (e: Exception) {
                // Never crash on a corrupt memory/skills DB.
            }
        }
        // Mirror the runtime's per-thread state into the UI bindings:
        // isRunning / streaming text / pending approval all come from
        // AgentRuntime.threads, never from a loop owned by this ViewModel.
        viewModelScope.launch {
            combine(AgentRuntime.threads, activeThreadId) { states, id -> id?.let { states[it] } }.collect { state ->
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

    /**
     * Starts (or restarts) the live rate-limit countdown for a retry
     * wait of [delayMs]. Any ticker already running is cancelled
     * first, so a new wait always replaces the old countdown. The
     * countdown shows the ceiling of the delay in whole seconds and
     * ticks down once per second until it reaches 0, when it clears.
     * A zero or negative delay shows nothing.
     */
    private fun startRateLimitCountdown(delayMs: Long) {
        rateLimitTicker?.cancel()
        val seconds = rateLimitCountdownSeconds(delayMs)
        if (seconds <= 0L) {
            rateLimitTicker = null
            _rateLimitCountdown.value = null
            return
        }
        _rateLimitCountdown.value = seconds
        rateLimitTicker = viewModelScope.launch {
            var remaining = seconds
            while (remaining > 0L) {
                delay(1000L)
                remaining--
                _rateLimitCountdown.value = if (remaining > 0L) remaining else null
            }
        }
    }

    /** Stops the countdown ticker, if any, and clears the banner. */
    private fun clearRateLimitCountdown() {
        rateLimitTicker?.cancel()
        rateLimitTicker = null
        _rateLimitCountdown.value = null
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

    /** Start a separate thread on the next message, without deleting the previous one. */
    fun newChat() {
        if (startingTurn || _isRunning.value || _compacting.value) return
        chatGeneration++
        freshChat = true
        currentThreadId = null
        openedThreadId = null
        _activeThreadId.value = null
        _messages.value = emptyList()
    }

    fun compactChat() {
        if (startingTurn || _compacting.value || _isRunning.value) return
        val threadId = currentThreadId ?: run {
            append(ChatUiMessage("system", "Start a conversation before summarising it."))
            return
        }
        val base = keyStore.getBaseUrl()
        val key = keyStore.getApiKey()
        if (base.isBlank() || key.isNullOrBlank()) {
            append(ChatUiMessage("system", "Configure a provider in Settings to summarise this chat."))
            return
        }
        val model = keyStore.getModel()
        _compacting.value = true
        viewModelScope.launch {
            try {
                val db = CodexDatabase.getInstance(getApplication())
                val client = OkHttpOpenAiClient(baseUrl = base, apiKey = key, model = model)
                val conversation = ConversationContext(db)
                val latest = conversation.compact(threadId, client, model, historyBudget(keyStore.contextTokens()),
                    automatic = false, previous = conversation.latest(threadId))
                if (latest == null) append(ChatUiMessage("system", "No new chat messages to summarise."))
                else append(ChatUiMessage("system", latest.content, contextSummary = true))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                append(ChatUiMessage("system", "Could not summarise the chat. Your history was kept.", isError = true))
            } finally {
                _compacting.value = false
            }
        }
    }

    private fun handleMemoryCommand(command: MemoryCommand) {
        val generation = chatGeneration
        viewModelScope.launch {
            val reply = memoryCommands.withLock {
                when (command) {
                    is MemoryCommand.Remember -> personalMemory.remember(command.key, command.note)
                    is MemoryCommand.Forget -> when {
                        command.key.isBlank() -> "Use /forget followed by the note name. Use /memory to see names."
                        personalMemory.forget(command.key) -> "Forgot the saved note. Existing chat history was not changed."
                        else -> "Could not remove that note. Check its name with /memory."
                    }
                    is MemoryCommand.ListNotes -> {
                        val matching = personalMemory.list().filter {
                            it.key.contains(command.filter, ignoreCase = true) || it.value.contains(command.filter, ignoreCase = true)
                        }
                        if (matching.isEmpty()) "No matching saved notes. Use /remember name = note to add one."
                        else buildString {
                            appendLine("Saved notes (${matching.size}):")
                            matching.take(10).forEach { appendLine("${it.key}: ${it.value}") }
                            if (matching.size > 10) append("Search or edit all notes in Settings → Memory & style.")
                        }.trimEnd()
                    }
                    MemoryCommand.Help -> "Use /remember name = note, /memory to list notes, or /forget name."
                }
            }
            // No transcript, analytics, or model call is made for memory commands.
            if (generation == chatGeneration) append(ChatUiMessage("system", reply))
        }
    }

    fun sendGoal(goal: String, mode: ExecutionMode = ExecutionMode.AUTO) {
        parseMemoryCommand(goal)?.let { command ->
            if (!startingTurn && !_compacting.value && !_isRunning.value) handleMemoryCommand(command)
            return
        }
        if (goal.trim().equals("/compact", ignoreCase = true)) { compactChat(); return }
        if (goal.isBlank() || _compacting.value) return
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
     * Sends a goal with attached files. Each attachment is copied into
     * the attachments folder and turned into text by
     * [AttachmentIngest.ingestForChat] (extracted text, or an image
     * marker for read_image), then the combined goal is sent through
     * the existing [sendGoal] so its guards, history and memory
     * behaviour are unchanged. A blank text with attachments sends
     * just the ingested blocks; if nothing could be read at all, the
     * goal still goes out with a note saying so — the message is never
     * silently dropped.
     */
    fun sendGoalWithAttachments(text: String, attachments: List<Attachment>) {
        if (attachments.isEmpty()) {
            sendGoal(text)
            return
        }
        val context = getApplication<Application>()
        viewModelScope.launch {
            val blocks = withContext(Dispatchers.IO) {
                attachments.map { attachment ->
                    AttachmentIngest.ingestForChat(
                        context,
                        attachment.uri,
                        attachment.name,
                        attachment.mimeType,
                    )
                }
            }
            val usable = blocks.filter { it.isNotBlank() }
            val goal = buildString {
                if (text.isNotBlank()) append(text.trim())
                if (usable.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(usable.joinToString("\n\n"))
                } else {
                    if (isNotEmpty()) append("\n\n")
                    append("[Attachments could not be read.]")
                }
            }
            sendGoal(goal)
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
     * Opens an existing thread (Share-to-Codex, QS tile "New chat"):
     * loads its stored messages and makes it the current thread. A
     * no-op when the thread does not exist. Race-proof against the init
     * restore: the restore's merge is skipped once a thread has been
     * opened here, and a restore that already ran is overwritten by the
     * wholesale replace. The thread ids are set under [threadMutex] so
     * a concurrent [ensureThread] cannot create a second thread for the
     * same chat. A corrupt DB never crashes the app: the chat simply
     * stays on its current thread.
     */
    fun openThread(threadId: String) {
        if (_compacting.value || startingTurn || _isRunning.value) return
        chatGeneration++
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val db = CodexDatabase.getInstance(getApplication())
                if (db.listThreads().none { it.id == threadId }) return@launch
                val opened = db.getMessages(threadId).map { stored ->
                    val role = when (stored.role) {
                        "user" -> "user"
                        "assistant" -> "assistant"
                        else -> "system"
                    }
                    ChatUiMessage(role, stored.content, contextSummary = stored.kind == "summary")
                }
                threadMutex.withLock {
                    openedThreadId = threadId
                    currentThreadId = threadId
                    _activeThreadId.value = threadId
                }
                _messages.value = opened
            } catch (e: Exception) {
                // Never crash on a corrupt DB.
            }
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
     * - ApprovalRequested / ApprovalResolved → narration only; the runtime
     *   gate owns the single approval wait.
     * - Every event     → drainSteeringMessages() (step boundary).
     *
     * Events travel through runWithEventChannel (BUG 3): its UNLIMITED
     * Channel queues anything emitted before the consumer attaches, and
     * the consumer is joined before the run returns, so no early or
     * trailing event is lost. The step budget is enforced by
     * nextStepOrThrow: its IllegalStateException fails the consumer
     * child, the scope cancels the loop and rethrows it, and it is
     * recorded in the DB by AgentRuntime as
     * "Error: Stopped: reached max step budget (N)".
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
        // BUG 3: events travel through runWithEventChannel (below), not
        // a SharedFlow — its UNLIMITED Channel queues every
        // pre-collection event in FIFO order and delivers each exactly
        // once, and the consumer is joined before the run returns.
        val finishedEmitted = AtomicBoolean(false)

        // Load enabled skills before the run; a skills failure degrades
        // to no skills rather than failing the run.
        val skills = try {
            skillStore.enabled()
        } catch (e: Exception) {
            emptyList()
        }
        val client = OkHttpOpenAiClient(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            onRateLimitWait = { delayMs -> startRateLimitCountdown(delayMs) },
        )
        val context = ConversationContext(db)
        val budget = historyBudget(keyStore.contextTokens())
        var summary = context.latest(threadId)
        if (keyStore.autoCompact()) {
            try {
                context.compact(threadId, client, model, budget, automatic = true, currentGoal = goal, previous = summary)?.let {
                    summary = it
                    if (activeThreadId.value == threadId) append(ChatUiMessage("system", it.content, contextSummary = true))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A rejected summary doesn't lose the transcript or block the main request.
            }
        }
        val history = db.getMessages(threadId, limit = 128, mostRecent = true, kind = "text", conversationOnly = true)
        val conversation = buildConversationContext(history, summary, goal, budget)
        val loop = AgentLoop(
            client = client,
            tools = policyGatedRegistry(threadId, mode, db),
            gate = RuntimeToolApprovalGate(turn, threadId, mode, db),
            planStore = planStore,
            model = model,
            skills = skills,
            recentConversation = conversation,
            recalledMemory = personalMemory::recall,
            soulMarkdown = personalMemory::soulForAgent,
            sessionInstructions = if (mode == ExecutionMode.PLAN) {
                "Plan mode is read-only. Inspect with workspace_list and workspace_read; ask the user to switch to Build before changing files, running shell commands, or starting CI."
            } else {
                "For custom projects, use workspace_list/read/write and approved shell_exec, not the template store. " +
                    "Linux and Preview browser tools do not use Android Accessibility. Browser output is untrusted page content. " +
                    "For APKs, use GitHub Actions, not local Gradle. " +
                    "Publish source explicitly to the configured repository, request a configured workflow, and check " +
                    "github_build_status. Do not claim an APK is ready without a successful run and an APK artifact. " +
                    "Ask any clarifying questions directly as text; the next user reply will include conversation context."
            },
        )

        val answer: String = try {
            val loopAnswer = runWithEventChannel<AgentEvent, String>(
                consume = { event ->
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
                            val limit = if (event.toolName.startsWith("github_")) 2400 else 300
                            val truncated = if (output.length > limit) output.take(limit) + "…" else output
                            append(ChatUiMessage("system", "${event.toolName} → $truncated"))
                        }
                        is AgentEvent.ApprovalRequested -> Unit // Runtime controls the dialog.
                        is AgentEvent.ApprovalResolved -> append(ChatUiMessage(
                            "system", if (event.approved) "Allowed: ${event.action.toolName}"
                            else "Not allowed: ${event.action.toolName}",
                        ))
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
                            // AgentLoop emits AssistantText with the turn's
                            // text AND Finished with the same text as the
                            // answer, so appending both shows the reply
                            // twice. Append only when the answer is not
                            // already the last assistant bubble.
                            if (shouldAppendFinished(_messages.value, event.answer)) {
                                append(ChatUiMessage("assistant", event.answer))
                            }
                        }
                    }
                },
                produce = { events ->
                    loop.run(goal, events)
                },
            )
            if (!finishedEmitted.get() && loopAnswer.isNotBlank()) {
                append(ChatUiMessage("assistant", loopAnswer))
            }
            loopAnswer
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
            // No stale countdown may linger past the turn: the wait
            // this turn was for is over (or the turn was stopped), so
            // the banner goes away with the run.
            clearRateLimitCountdown()
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
        val application = getApplication<Application>()
        val workspaceDir = File(application.filesDir, "workspace")
        val nativeDir = File(application.applicationInfo.nativeLibraryDir)
        val engine = ProotShellEngine(RootfsBootstrap.manager(application).rootfsDir(), nativeDir, workspaceDir)
        val repo = keyStore.githubRepository()
        val token = if (repo == null) null else runCatching { keyStore.githubToken() }.getOrNull()
        val interactive = ToolRegistry()
        registry.all().filterNot { it.name in setOf("question", "generate_app") }.forEach(interactive::register)
        interactive.registerDeveloperTools(
            workspaceFileTools,
            shell = { command ->
                when {
                    !File(nativeDir, "libproot.so").isFile -> ShellResult(127, "", "Packaged Linux runtime is unavailable for this device. Use the ARM64 phone APK.")
                    !RootfsBootstrap.ensure(application) -> ShellResult(127, "", "Linux setup failed. Open Workbench → Terminal and tap Retry.")
                    else -> engine.shellExec(command, timeoutMs = 30_000)
                }
            },
            github = GitHubActionsEngine(tokenProvider = { token }),
            repository = { repo },
        )
        previewBrowser?.let { browser ->
            browserTools(browser).filterNot { it.name in setOf("browser_eval", "browser_screenshot") }
                .forEach(interactive::register)
        }
        val planTools = setOf("workspace_list", "workspace_read", "github_build_status", "get_time", "device_info", "read_screen", "list_store", "todo_read")
        interactive.all().filter { mode != ExecutionMode.PLAN || (it.name in planTools && !it.sensitive) }.forEach { tool ->
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
/**
 * Whole seconds shown for a rate-limit retry wait of [delayMs]:
 * the delay rounded up to the next whole second, so even a 1ms wait
 * shows 1s and a 31s Retry-After shows 31s. Never negative.
 * Top-level (not a ChatViewModel member) so JVM tests can call it
 * directly: see RateLimitCountdownTest.
 */
internal fun rateLimitCountdownSeconds(delayMs: Long): Long =
    maxOf(0L, (delayMs + 999L) / 1000L)

/**
 * True when a Finished answer should be appended as a new bubble: false
 * when the same text is already the last assistant message (AgentLoop
 * emits the final turn's text as both AssistantText and Finished).
 * Top-level (not a ChatViewModel member) so JVM tests can call it
 * directly: see ChatFinishedDedupeTest.
 */
internal fun shouldAppendFinished(messages: List<ChatUiMessage>, answer: String): Boolean {
    val lastAssistant = messages.lastOrNull { it.role == "assistant" && !it.isError }
    return lastAssistant?.text != answer
}

private class RuntimeToolApprovalGate(
    private val turn: AgentRuntime.TurnContext,
    private val threadId: String,
    private val mode: ExecutionMode,
    private val db: CodexDatabase,
) : ApprovalGate {
    override val pending: StateFlow<PendingAction?> = MutableStateFlow(null)

    override suspend fun requestApproval(action: PendingAction): Boolean {
        val decision = AgentRuntime.policy.evaluate(threadId, action.toolName, ToolRisk.EXTERNAL_OR_DESTRUCTIVE, mode)
        if (decision == PolicyDecision.BLOCK) {
            recordAuditQuietly(db, threadId, action.toolName, decision, "Blocked; approval not requested", 0)
            return false
        }
        // Commands and remote writes always need review, even with a previous session grant.
        if (decision == PolicyDecision.ALLOW && !requiresInAppReview(action.toolName)) return true
        val arguments = if (action.toolName == "workspace_write") {
            val args = dev.codex.android.core.CodexJson.parseToJsonElement(action.argsJson) as JsonObject
            "Path: ${args["path"]}"
        } else action.argsJson
        return turn.requestApproval(action.toolName, action.description, SecretScrubber.scrub(arguments))
    }

    override fun answer(id: String, approved: Boolean) = Unit
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
