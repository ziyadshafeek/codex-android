package dev.codex.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.codex.android.agent.AgentEvent
import dev.codex.android.agent.AgentLoop
import dev.codex.android.agent.PlanStore
import dev.codex.android.api.OkHttpOpenAiClient
import dev.codex.android.data.KeyStore
import dev.codex.android.data.MemoryStore
import dev.codex.android.security.ApprovalGate
import dev.codex.android.security.DefaultApprovalGate
import dev.codex.android.skills.SkillStore
import dev.codex.android.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiMessage(val role: String, val text: String, val isError: Boolean = false)
// roles: "user", "assistant", "system"

class ChatViewModel(application: Application) : AndroidViewModel(application) {

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

    init {
        refreshKeyState()
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
                val recent = memoryStore.recentMessages(20)
                if (recent.isNotEmpty()) {
                    val restored = recent.map { stored ->
                        val role = when (stored.role) {
                            "user" -> "user"
                            "assistant" -> "assistant"
                            else -> "system"
                        }
                        ChatUiMessage(role, stored.content)
                    }
                    _messages.update { it + restored }
                }
            } catch (e: Exception) {
                // Never crash on a corrupt memory/skills DB.
            }
        }
    }

    private fun append(message: ChatUiMessage) {
        _messages.update { it + message }
    }

    fun refreshKeyState() {
        _hasKey.value = keyStore.hasKey()
    }

    fun approvePending(approved: Boolean) {
        val pending = gate.pending.value ?: return
        gate.answer(pending.id, approved)
    }

    fun sendGoal(goal: String) {
        if (goal.isBlank()) return
        if (_isRunning.value) return
        append(ChatUiMessage("user", goal))
        // Fire-and-forget: a memory failure must never affect the chat.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                memoryStore.saveMessage("user", goal)
            } catch (e: Exception) {
                // Never crash on a memory failure.
            }
        }
        if (!_hasKey.value || keyStore.getBaseUrl().isBlank()) {
            append(
                ChatUiMessage(
                    "system",
                    "Add your API endpoint and key in Settings first — the app never ships with a key or a provider.",
                    isError = true
                )
            )
            return
        }
        viewModelScope.launch {
            _isRunning.value = true
            var finishedEmitted = false
            val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64)
            val collector = launch {
                events.collect { event ->
                    when (event) {
                        is AgentEvent.PlanCreated ->
                            append(ChatUiMessage("system", "Plan started: ${event.plan.goal}"))
                        is AgentEvent.PlanUpdated -> {
                            // No message.
                        }
                        is AgentEvent.AssistantText ->
                            append(ChatUiMessage("assistant", event.text))
                        is AgentEvent.ToolStarted ->
                            append(ChatUiMessage("system", "Running ${event.toolName}…"))
                        is AgentEvent.ToolFinished -> {
                            val output = event.output
                            val truncated = if (output.length > 300) output.take(300) + "…" else output
                            append(ChatUiMessage("system", "${event.toolName} → $truncated"))
                        }
                        is AgentEvent.ApprovalRequested ->
                            append(
                                ChatUiMessage(
                                    "system",
                                    "Waiting for your approval: ${event.action.toolName}"
                                )
                            )
                        is AgentEvent.ApprovalResolved ->
                            append(
                                ChatUiMessage(
                                    "system",
                                    "You ${if (event.approved) "approved" else "denied"} ${event.action.toolName}"
                                )
                            )
                        is AgentEvent.Error ->
                            append(
                                ChatUiMessage(
                                    "assistant",
                                    "Error: ${event.error.message}",
                                    isError = true
                                )
                            )
                        is AgentEvent.Finished -> {
                            finishedEmitted = true
                            append(ChatUiMessage("assistant", event.answer))
                        }
                    }
                }
            }
            try {
                val client = OkHttpOpenAiClient(
                    baseUrl = keyStore.getBaseUrl(),
                    apiKey = keyStore.getApiKey()!!,
                    model = keyStore.getModel()
                )
                // Load enabled skills before the run; a skills failure
                // degrades to no skills rather than failing the run.
                val skills = try {
                    withContext(Dispatchers.IO) { skillStore.enabled() }
                } catch (e: Exception) {
                    emptyList()
                }
                val loop = AgentLoop(
                    client = client,
                    tools = registry,
                    gate = gate,
                    planStore = planStore,
                    model = keyStore.getModel(),
                    skills = skills
                )
                val answer = loop.run(goal, events)
                // Give the collector a brief chance to deliver a Finished
                // event that was emitted just before run() returned.
                for (i in 0 until 20) {
                    if (finishedEmitted) break
                    delay(10)
                }
                if (!finishedEmitted && answer.isNotBlank()) {
                    append(ChatUiMessage("assistant", answer))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append(
                    ChatUiMessage(
                        "assistant",
                        "Error: ${e.message ?: e.toString()}",
                        isError = true
                    )
                )
            } finally {
                collector.cancel()
                _isRunning.value = false
                // Persist the run's final assistant reply (never system or
                // error messages). Fire-and-forget; a memory failure must
                // never affect the chat.
                val lastAssistant = _messages.value.lastOrNull {
                    it.role == "assistant" && !it.isError
                }
                if (lastAssistant != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            memoryStore.saveMessage("assistant", lastAssistant.text)
                        } catch (e: Exception) {
                            // Never crash on a memory failure.
                        }
                    }
                }
            }
        }
    }
}
