package dev.codex.android.security

import java.util.concurrent.ConcurrentHashMap

enum class ExecutionMode {
    /** Read-only inspection; all mutating/external tools are blocked. */
    PLAN,
    /** Ask user approval before any write or external action. */
    ASK,
    /** Auto-approve safe workspace writes; still ask for destructive/external actions. */
    AUTO,
}

enum class ToolRisk {
    READ_ONLY,
    WORKSPACE_WRITE,
    EXTERNAL_OR_DESTRUCTIVE,
}

enum class ToolRule {
    ALWAYS,
    ASK,
    DENY,
}

enum class PolicyDecision {
    ALLOW,
    PROMPT_USER,
    BLOCK,
}

class PermissionPolicy(
    private val defaultRules: Map<String, ToolRule> = emptyMap(),
) {
    private val userOverrides = ConcurrentHashMap<String, ToolRule>(defaultRules)
    // Key: "$threadId::$toolName"
    private val sessionGrants = ConcurrentHashMap<String, Boolean>()

    fun setRule(toolName: String, rule: ToolRule) {
        userOverrides[toolName] = rule
    }

    fun getRule(toolName: String): ToolRule? = userOverrides[toolName]

    fun grantForSession(threadId: String, toolName: String) {
        sessionGrants["$threadId::$toolName"] = true
    }

    fun clearSession(threadId: String) {
        val prefix = "$threadId::"
        sessionGrants.keys.removeIf { it.startsWith(prefix) }
    }

    fun evaluate(
        threadId: String,
        toolName: String,
        risk: ToolRisk,
        mode: ExecutionMode,
    ): PolicyDecision {
        // Explicit user DENY always wins.
        val override = userOverrides[toolName]
        if (override == ToolRule.DENY) return PolicyDecision.BLOCK

        // Plan mode strictly forbids any mutating or external action.
        if (mode == ExecutionMode.PLAN && risk != ToolRisk.READ_ONLY) {
            return PolicyDecision.BLOCK
        }

        // Explicit user ALWAYS allows (outside PLAN mode).
        if (override == ToolRule.ALWAYS) return PolicyDecision.ALLOW

        // Session-level "Allow for this thread" grant.
        if (sessionGrants["$threadId::$toolName"] == true) return PolicyDecision.ALLOW

        // Explicit user ASK forces a prompt.
        if (override == ToolRule.ASK) return PolicyDecision.PROMPT_USER

        // Default matrix by mode + risk.
        return when (mode) {
            ExecutionMode.PLAN -> PolicyDecision.ALLOW // only READ_ONLY reaches here
            ExecutionMode.ASK -> when (risk) {
                ToolRisk.READ_ONLY -> PolicyDecision.ALLOW
                ToolRisk.WORKSPACE_WRITE -> PolicyDecision.PROMPT_USER
                ToolRisk.EXTERNAL_OR_DESTRUCTIVE -> PolicyDecision.PROMPT_USER
            }
            ExecutionMode.AUTO -> when (risk) {
                ToolRisk.READ_ONLY -> PolicyDecision.ALLOW
                ToolRisk.WORKSPACE_WRITE -> PolicyDecision.ALLOW
                ToolRisk.EXTERNAL_OR_DESTRUCTIVE -> PolicyDecision.PROMPT_USER
            }
        }
    }
}
