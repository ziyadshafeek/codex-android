package dev.codex.android.agent

import dev.codex.android.api.ChatMessage
import dev.codex.android.api.OpenAiClient
import dev.codex.android.api.ToolCall
import dev.codex.android.core.CodexError
import dev.codex.android.security.ApprovalGate
import dev.codex.android.security.PendingAction
import dev.codex.android.security.SensitivityPolicy
import dev.codex.android.skills.Skill
import dev.codex.android.skills.SkillRunner
import dev.codex.android.tools.ToolRegistry
import dev.codex.android.tools.parseToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.FlowCollector
import java.util.UUID

/**
 * Adapts a [Channel] into a [FlowCollector] for [AgentLoop.run] (BUG 3).
 * A `MutableSharedFlow` with `replay = 0` drops every event emitted
 * before the first collector subscribes — `extraBufferCapacity` buffers
 * nothing while `subscriptionCount == 0` — so the loop must emit into a
 * Channel, which queues pre-collection events in FIFO order and delivers
 * each exactly once. This bridge is the only place that conversion lives.
 */
internal fun channelEventCollector(channel: SendChannel<AgentEvent>): FlowCollector<AgentEvent> =
    FlowCollector { event -> channel.send(event) }

/**
 * The ReAct agent loop: observe (model) → think → act (tools) → observe.
 *
 * Runs a goal to completion, emitting [AgentEvent]s for the UI. A run ends
 * when the model answers without a tool call, when the turn budget is spent,
 * or when a fatal error occurs. Sensitive tools pause for user approval via
 * [ApprovalGate]; forbidden tools never execute, even with approval.
 *
 * The loop is single-threaded and cancellation-safe: cancelling the calling
 * coroutine stops the run promptly and rethrows [CancellationException].
 */
class AgentLoop(
    private val client: OpenAiClient,
    private val tools: ToolRegistry,
    private val gate: ApprovalGate,
    private val planStore: PlanStore,
    private val model: String,
    private val skills: List<Skill> = emptyList(),
) {

    /**
     * The system prompt for this run: [SYSTEM_PROMPT], plus the enabled
     * skills' instructions when any are present.
     */
    private val systemPrompt: String =
        if (skills.isEmpty()) {
            SYSTEM_PROMPT
        } else {
            SYSTEM_PROMPT + "\n\nAvailable skills:\n" + SkillRunner.buildSkillPrompt(skills)
        }

    companion object {
        /** Hard cap on model turns per goal, so a run always terminates. */
        const val MAX_TURNS = 12

        private const val SYSTEM_PROMPT =
            "You are Codex, an autonomous agent on the user's Android phone. " +
                "Achieve the user's goal step by step. Use the tools available to you " +
                "when they help; when you have the final answer, reply with it as plain " +
                "text and do not call any more tools. Be concise. Never invent a tool " +
                "that is not listed. If a tool call fails or the user denies an action, " +
                "explain what happened and offer the next best step."
    }

    /**
     * Runs [goal] to completion. Emits progress events to [events] and
     * returns the final answer text. Never returns null.
     */
    suspend fun run(goal: String, events: FlowCollector<AgentEvent>): String {
        val plan = Plan(
            id = UUID.randomUUID().toString(),
            goal = goal,
            steps = listOf(
                PlanStep(id = "understand", title = "Understand the goal", status = PlanStepStatus.DONE),
                PlanStep(id = "act", title = "Take action", status = PlanStepStatus.RUNNING),
                PlanStep(id = "answer", title = "Deliver the answer", status = PlanStepStatus.PENDING),
            ),
        )
        planStore.save(plan)
        events.emit(AgentEvent.PlanCreated(plan))

        val messages = mutableListOf(
            ChatMessage(role = "system", content = systemPrompt),
            ChatMessage(role = "user", content = goal),
        )
        val toolDefinitions = tools.definitions()
        var currentPlan = plan
        var lastAnswer = ""

        for (turn in 0 until MAX_TURNS) {
            val result = try {
                client.chat(
                    messages = messages,
                    tools = toolDefinitions,
                    model = model,
                    onPartialText = { partial ->
                        // Streamed text is surfaced live; the final text is
                        // re-emitted as AssistantText below when the turn ends.
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: CodexError) {
                events.emit(AgentEvent.Error(e))
                return finishWithError(e, currentPlan, events)
            } catch (e: Exception) {
                val wrapped = CodexError.Unknown(e.message ?: e.toString(), e)
                events.emit(AgentEvent.Error(wrapped))
                return finishWithError(wrapped, currentPlan, events)
            }

            val choice = result.choices.firstOrNull()
            if (choice == null) {
                val error = CodexError.Unknown("The model returned no reply")
                events.emit(AgentEvent.Error(error))
                return finishWithError(error, currentPlan, events)
            }

            // Record the assistant's turn in history (text and/or tool calls).
            messages.add(
                ChatMessage(
                    role = "assistant",
                    content = choice.text,
                    toolCalls = choice.toolCalls.ifEmpty { null },
                ),
            )
            choice.text?.let { text ->
                if (text.isNotBlank()) {
                    lastAnswer = text
                    events.emit(AgentEvent.AssistantText(text))
                }
            }

            if (choice.toolCalls.isEmpty()) {
                // No tool call: this text is the final answer.
                val answer = choice.text?.takeIf { it.isNotBlank() }
                    ?: lastAnswer.takeIf { it.isNotBlank() }
                    ?: "Done."
                currentPlan = currentPlan
                    .withStepUpdated("act", PlanStepStatus.DONE)
                    .withStepUpdated("answer", PlanStepStatus.DONE)
                planStore.save(currentPlan)
                events.emit(AgentEvent.PlanUpdated(currentPlan))
                events.emit(AgentEvent.Finished(answer))
                return answer
            }

            // Execute each requested tool call in order.
            for (call in choice.toolCalls) {
                val parsed = try {
                    parseToolCall(call, tools)
                } catch (e: CodexError.MalformedToolCall) {
                    events.emit(AgentEvent.Error(e))
                    messages.add(
                        ChatMessage(
                            role = "tool",
                            content = "Tool call failed: ${e.message}. Fix the call and try again, or answer without tools.",
                            toolCallId = call.id,
                            name = call.function.name,
                        ),
                    )
                    continue
                }
                val toolName = parsed.tool.name
                if (SensitivityPolicy.isForbidden(toolName)) {
                    val denial = "Tool '$toolName' is never allowed."
                    events.emit(
                        AgentEvent.Error(CodexError.ToolFailed(toolName, denial)),
                    )
                    messages.add(
                        ChatMessage(
                            role = "tool",
                            content = denial,
                            toolCallId = call.id,
                            name = toolName,
                        ),
                    )
                    continue
                }
                events.emit(AgentEvent.ToolStarted(toolName))
                val output = runToolWithGate(call, parsed, events)
                val outputText = output ?: "(no output)"
                events.emit(AgentEvent.ToolFinished(toolName, outputText))
                messages.add(
                    ChatMessage(
                        role = "tool",
                        content = outputText,
                        toolCallId = call.id,
                        name = toolName,
                    ),
                )
            }
            // Loop: the model sees the tool results and decides the next step.
        }

        // Turn budget spent without a final answer.
        val answer = lastAnswer.takeIf { it.isNotBlank() }
            ?: "I ran out of steps before finishing. Please try a smaller goal."
        currentPlan = currentPlan
            .withStepUpdated("act", PlanStepStatus.FAILED, "Turn budget spent")
            .withStepUpdated("answer", PlanStepStatus.DONE)
        planStore.save(currentPlan)
        events.emit(AgentEvent.PlanUpdated(currentPlan))
        events.emit(AgentEvent.Finished(answer))
        return answer
    }

    /**
     * Runs one parsed tool call, asking the user first when the tool is
     * sensitive. Returns the tool output, or null when the user denied it
     * (the denial is reported back to the model as the tool result).
     */
    private suspend fun runToolWithGate(
        call: ToolCall,
        parsed: dev.codex.android.tools.ParsedToolCall,
        events: FlowCollector<AgentEvent>,
    ): String? {
        val tool = parsed.tool
        if (SensitivityPolicy.requiresApproval(tool)) {
            val action = PendingAction(
                id = UUID.randomUUID().toString(),
                toolName = tool.name,
                description = tool.description,
                argsJson = dev.codex.android.core.CodexJson.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    parsed.args,
                ),
            )
            events.emit(AgentEvent.ApprovalRequested(action))
            val approved = try {
                gate.requestApproval(action)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                events.emit(
                    AgentEvent.Error(
                        CodexError.Unknown("Approval failed: ${e.message}"),
                    ),
                )
                return "Approval failed: ${e.message}"
            }
            events.emit(AgentEvent.ApprovalResolved(action, approved))
            if (!approved) {
                return "The user denied this action."
            }
        }
        val result = try {
            tool.execute(parsed.args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "Tool '${tool.name}' failed: ${e.message}"
        }
        return if (result.ok) {
            result.output
        } else {
            "Tool '${tool.name}' failed: ${result.error ?: "unknown error"}"
        }
    }

    private suspend fun finishWithError(
        error: CodexError,
        plan: Plan,
        events: FlowCollector<AgentEvent>,
    ): String {
        val failed = plan
            .withStepUpdated("act", PlanStepStatus.FAILED, error.message)
            .withStepUpdated("answer", PlanStepStatus.DONE)
        planStore.save(failed)
        events.emit(AgentEvent.PlanUpdated(failed))
        val answer = "I could not finish: ${error.message}"
        events.emit(AgentEvent.Finished(answer))
        return answer
    }
}
