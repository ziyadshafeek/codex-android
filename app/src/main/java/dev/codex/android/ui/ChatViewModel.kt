package dev.codex.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.codex.android.agent.AgentEvent
import dev.codex.android.agent.AgentLoop
import dev.codex.android.agent.PlanStore
import dev.codex.android.api.OkHttpOpenAiClient
import dev.codex.android.data.KeyStore
import dev.codex.android.security.ApprovalGate
import dev.codex.android.security.DefaultApprovalGate
import dev.codex.android.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiMessage(val role: String, val text: String, val isError: Boolean = false)
// roles: "user", "assistant", "system"

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    val keyStore: KeyStore = KeyStore(application)
    private val planStore: PlanStore = PlanStore(application)
    private val registry: ToolRegistry = ToolRegistry.withBuiltins(application)

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
        if (!_hasKey.value) {
            append(
                ChatUiMessage(
                    "system",
                    "Add your API key in Settings first — the app never ships with a key.",
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
                val loop = AgentLoop(
                    client = client,
                    tools = registry,
                    gate = gate,
                    planStore = planStore,
                    model = keyStore.getModel()
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
            }
        }
    }
}
