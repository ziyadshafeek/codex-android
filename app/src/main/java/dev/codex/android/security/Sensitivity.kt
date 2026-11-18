package dev.codex.android.security

import dev.codex.android.tools.Tool

/**
 * How much damage a tool can do if it runs unchecked.
 *
 * Gated categories — a tool that does any of these must declare
 * [Tool.sensitive] = true, and the agent loop must ask the user for
 * approval before running it:
 * - sending a message
 * - making a call
 * - changing a setting
 * - spending money
 * - deleting data
 *
 * The tool registry is the source of truth: this policy never silently
 * downgrades a sensitive tool to safe. [SensitivityPolicy.requiresApproval]
 * returns exactly what the tool declares.
 */
enum class Sensitivity {
    SAFE,
    SENSITIVE,
}

object SensitivityPolicy {

    /**
     * Tool names that must never execute under any circumstances, even with
     * user approval. No such tools exist yet; this is a future-proof denylist
     * the agent loop checks before running anything.
     */
    private val FORBIDDEN_TOOLS = setOf(
        "delete_all_data",
        "factory_reset",
        "send_money",
        "transfer_money",
    )

    /** True when the tool declares itself sensitive and needs user approval. */
    fun requiresApproval(tool: Tool): Boolean = tool.sensitive

    /** True when the tool name is on the never-execute denylist. */
    fun isForbidden(toolName: String): Boolean = toolName in FORBIDDEN_TOOLS

    /** [Sensitivity.SENSITIVE] if the tool declares itself sensitive, else [Sensitivity.SAFE]. */
    fun sensitivityOf(tool: Tool): Sensitivity =
        if (tool.sensitive) Sensitivity.SENSITIVE else Sensitivity.SAFE
}
