package dev.codex.android.tools

import dev.codex.android.subagents.SubagentInfo
import dev.codex.android.subagents.SubagentOrchestrator
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The `subagent` tool: lets the chat agent spawn a background sub-agent.
 *
 * DESIGN — synchronous-with-timeout. [SubagentOrchestrator.spawnSubagent]
 * returns as soon as the sub-agent is launched (the turn itself runs on
 * the orchestrator's own job), so this tool polls
 * [SubagentOrchestrator.listSubagents] until the sub-agent reaches a
 * terminal status (`done` / `failed` / `stopped`) or the timeout elapses,
 * then returns the sub-agent's final result as the tool output. No
 * companion `subagent_status` tool is needed: the orchestrator already
 * exposes the live list, and the caller blocks here instead of having
 * to poll a second tool.
 *
 * TOOL ACCESS — the sub-agent does NOT get more than the main agent.
 * [DefaultSubagentLoopFactory][dev.codex.android.subagents.DefaultSubagentLoopFactory]
 * builds the sub-agent's registry from [ToolRegistry.withBuiltins] minus
 * `question` / `generate_app`, and wraps every tool in a
 * `GlobGatedTool` driven by the parent ∩ child permission engine, with
 * [SECRET_DENY_RULES][dev.codex.android.subagents.SubagentOrchestrator.SECRET_DENY_RULES]
 * layered on top — so a sub-agent can never widen the parent's
 * permissions and can never touch secret files. This tool itself is
 * deliberately NOT registered in [ToolRegistry.withBuiltins]: the
 * sub-agent factory builds from `withBuiltins`, so sub-agents do not get
 * a `subagent` tool and cannot spawn nested sub-agents. It is registered
 * only in the chat agent's interactive registry (see
 * `ChatViewModel.policyGatedRegistry`), where a [SubagentOrchestrator]
 * instance exists. If nested spawning is ever wanted, the factory must
 * be given the parent orchestrator and register
 * `SubagentTool(parent.nestedOrchestrator(...))` itself — that is an
 * orchestrator/factory change, not a change to this file.
 *
 * SAFETY — `sensitive = true`: spawning a whole agent turn is an
 * external/mutating action, so the policy gate prompts the user before
 * it runs in AUTO/ASK mode and blocks it in PLAN mode (it is also
 * absent from the PLAN-mode allow-list in `policyGatedRegistry`).
 *
 * FAILURE CONTRACT — a failing sub-agent is reported as `ok = false`
 * with the sub-agent's error text, a timeout kills the sub-agent and
 * reports `ok = false` instead of hanging, and a sub-agent that
 * finishes with no result is reported as an error, never as an empty
 * success. Only [TimeoutCancellationException] from our own timeout is
 * swallowed; a parent cancellation propagates untouched.
 */
class SubagentTool(
    private val orchestrator: SubagentOrchestrator,
    private val pollIntervalMillis: Long = 200,
) : Tool {
    override val name: String = "subagent"
    override val description: String =
        "Spawns a background sub-agent to carry out a goal independently (research, " +
            "implementation, review) and returns the sub-agent's final result when it " +
            "finishes. Use for work that can run on its own while you continue; do not " +
            "use it for questions that need the user, or for anything needing an " +
            "immediate answer. The sub-agent has the same tools you do (minus " +
            "interactive-only ones) and can never exceed your permissions."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("goal") {
                put("type", "string")
                put("description", "What the sub-agent should do, as a self-contained goal")
            }
            putJsonObject("timeout_seconds") {
                put("type", "integer")
                put("description", "Max seconds to wait for the sub-agent (default 300, clamped to 5-1800). On timeout the sub-agent is stopped and an error is returned.")
            }
            putJsonObject("agent") {
                put("type", "string")
                put("description", "Which sub-agent to run: general (default), explore (read-only codebase exploration), worker (implementation), or review (read-only code review)")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("goal")) }
    }
    override val sensitive: Boolean = true

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS: Int = 300
        const val MIN_TIMEOUT_SECONDS: Int = 5
        const val MAX_TIMEOUT_SECONDS: Int = 1800
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val goal = args["goal"]?.jsonPrimitive?.contentOrNull?.trim()
        if (goal.isNullOrEmpty()) {
            return ToolResult(ok = false, output = "", error = "Missing required argument: goal")
        }
        val timeoutSeconds = (args["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
        val agent = args["agent"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            .ifEmpty { "general" }

        val info: SubagentInfo = try {
            orchestrator.spawnSubagent(task = goal, agentName = agent)
        } catch (e: Exception) {
            return ToolResult(
                ok = false,
                output = "",
                error = "Failed to start sub-agent: ${e.message ?: e.javaClass.simpleName}",
            )
        }

        val finished: SubagentInfo? = try {
            withTimeout(timeoutSeconds * 1_000L) {
                var result: SubagentInfo? = null
                while (result == null) {
                    val current = orchestrator.listSubagents().firstOrNull { it.id == info.id }
                    // Row gone: the sub-agent was killed/removed from outside.
                    if (current == null) break
                    if (current.status != "running") {
                        result = current
                        break
                    }
                    delay(pollIntervalMillis)
                }
                result
            }
        } catch (e: TimeoutCancellationException) {
            orchestrator.killSubagent(info.id)
            return ToolResult(
                ok = false,
                output = "",
                error = "Sub-agent '${info.id}' did not finish within $timeoutSeconds seconds; it was stopped.",
            )
        }

        if (finished == null) {
            return ToolResult(
                ok = false,
                output = "",
                error = "Sub-agent '${info.id}' was stopped before it finished.",
            )
        }
        return when (finished.status) {
            "done" -> {
                val output = finished.output.trim()
                if (output.isEmpty()) {
                    ToolResult(
                        ok = false,
                        output = "",
                        error = "Sub-agent '${info.id}' finished without producing a result.",
                    )
                } else {
                    ToolResult(ok = true, output = output)
                }
            }
            "failed" -> ToolResult(
                ok = false,
                output = "",
                error = finished.output.trim().ifEmpty { "Sub-agent '${info.id}' failed with no error detail." },
            )
            "stopped" -> ToolResult(
                ok = false,
                output = "",
                error = "Sub-agent '${info.id}' was stopped before it finished.",
            )
            else -> ToolResult(
                ok = false,
                output = "",
                error = "Sub-agent '${info.id}' ended with unexpected status '${finished.status}'.",
            )
        }
    }
}
