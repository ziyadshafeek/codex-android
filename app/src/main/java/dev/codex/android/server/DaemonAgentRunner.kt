package dev.codex.android.server

import android.content.Context
import dev.codex.android.agent.AgentRuntime
import dev.codex.android.security.ExecutionMode
import dev.codex.android.security.GlobDecision
import dev.codex.android.security.GlobPermissionEngine
import dev.codex.android.security.GlobRule
import dev.codex.android.subagents.DefaultSubagentLoopFactory

/**
 * Real [AgentRunnerFactory] for daemon turns: drives the same AgentLoop
 * construction `ChatViewModel.runAgentTurn` uses (KeyStore endpoint/key/
 * model, `ToolRegistry.withBuiltins`, `PlanStore`, enabled skills,
 * `DefaultApprovalGate`), already extracted as
 * [DefaultSubagentLoopFactory] — so daemon turns run the real agent
 * loop instead of the "no agent runner is wired" reply.
 *
 * The daemon is the main agent, not a child subagent, so tools run
 * under an allow-all glob engine — the same posture as the chat (no
 * glob restriction on the main agent). The policy gate inside the loop
 * still refuses BLOCKed tools, and an approval suspends on
 * `TurnContext.requestApproval` until answered in-app or from the
 * notification shade. A missing API key throws from the loop factory
 * (loud, recorded as a turn error by `AgentRuntime`) instead of
 * returning a fake answer.
 */
class DaemonAgentRunnerFactory(context: Context) : AgentRunnerFactory {
    private val loopFactory = DefaultSubagentLoopFactory(context)

    private val allowAllEngine = GlobPermissionEngine(
        listOf(GlobRule("**", GlobDecision.ALLOW)),
    )

    override suspend fun runTurn(
        turnContext: AgentRuntime.TurnContext,
        threadId: String,
        goal: String,
        mode: ExecutionMode,
    ): String = loopFactory.runGoal(turnContext, threadId, goal, mode, allowAllEngine)
}
