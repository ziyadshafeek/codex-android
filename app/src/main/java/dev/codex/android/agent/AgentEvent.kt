package dev.codex.android.agent

import dev.codex.android.core.CodexError
import dev.codex.android.security.PendingAction

/**
 * Events the agent loop emits while working on a goal. The UI collects them
 * to narrate progress; [Finished] carries the final answer and also ends
 * [AgentLoop.run].
 */
sealed class AgentEvent {
    /** A plan was created for the goal. */
    data class PlanCreated(val plan: Plan) : AgentEvent()

    /** The plan changed (a step started, finished, or failed). */
    data class PlanUpdated(val plan: Plan) : AgentEvent()

    /** A piece of assistant text arrived (streamed or whole). */
    data class AssistantText(val text: String) : AgentEvent()

    /** A tool started executing. */
    data class ToolStarted(val toolName: String) : AgentEvent()

    /** A tool finished. [output] is the (possibly truncated) result. */
    data class ToolFinished(val toolName: String, val output: String) : AgentEvent()

    /** A sensitive action is waiting for the user to approve or deny it. */
    data class ApprovalRequested(val action: PendingAction) : AgentEvent()

    /** The user answered an approval request. */
    data class ApprovalResolved(val action: PendingAction, val approved: Boolean) : AgentEvent()

    /** A non-fatal error occurred; the loop may keep going. */
    data class Error(val error: CodexError) : AgentEvent()

    /** The run is done. [answer] is the final response to the user. */
    data class Finished(val answer: String) : AgentEvent()
}
