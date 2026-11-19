package dev.codex.android.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for [PermissionPolicy].
 *
 * Pure logic, no Android framework, no network: the policy is a plain
 * decision matrix over [ExecutionMode], [ToolRisk], explicit [ToolRule]
 * overrides, and per-thread session grants.
 */
class PermissionPolicyTest {

    private val thread = "thr_test"

    @Test
    fun readOnly_allowedInAllModes() {
        val policy = PermissionPolicy()
        assertEquals(
            PolicyDecision.ALLOW,
            policy.evaluate(thread, "read_file", ToolRisk.READ_ONLY, ExecutionMode.PLAN),
        )
        assertEquals(
            PolicyDecision.ALLOW,
            policy.evaluate(thread, "read_file", ToolRisk.READ_ONLY, ExecutionMode.ASK),
        )
        assertEquals(
            PolicyDecision.ALLOW,
            policy.evaluate(thread, "read_file", ToolRisk.READ_ONLY, ExecutionMode.AUTO),
        )
    }

    @Test
    fun workspaceWrite_promptsInAsk_blockedInPlan_allowedInAuto() {
        val policy = PermissionPolicy()
        assertEquals(
            PolicyDecision.PROMPT_USER,
            policy.evaluate(thread, "write_file", ToolRisk.WORKSPACE_WRITE, ExecutionMode.ASK),
        )
        assertEquals(
            PolicyDecision.BLOCK,
            policy.evaluate(thread, "write_file", ToolRisk.WORKSPACE_WRITE, ExecutionMode.PLAN),
        )
        assertEquals(
            PolicyDecision.ALLOW,
            policy.evaluate(thread, "write_file", ToolRisk.WORKSPACE_WRITE, ExecutionMode.AUTO),
        )
    }

    @Test
    fun externalOrDestructive_promptsInAskAndAuto_blockedInPlan() {
        val policy = PermissionPolicy()
        assertEquals(
            PolicyDecision.PROMPT_USER,
            policy.evaluate(
                thread, "run_shell", ToolRisk.EXTERNAL_OR_DESTRUCTIVE, ExecutionMode.ASK,
            ),
        )
        assertEquals(
            PolicyDecision.PROMPT_USER,
            policy.evaluate(
                thread, "run_shell", ToolRisk.EXTERNAL_OR_DESTRUCTIVE, ExecutionMode.AUTO,
            ),
        )
        assertEquals(
            PolicyDecision.BLOCK,
            policy.evaluate(
                thread, "run_shell", ToolRisk.EXTERNAL_OR_DESTRUCTIVE, ExecutionMode.PLAN,
            ),
        )
    }

    @Test
    fun explicitDeny_alwaysWins_evenInAuto() {
        val policy = PermissionPolicy()
        policy.setRule("write_file", ToolRule.DENY)
        assertEquals(
            PolicyDecision.BLOCK,
            policy.evaluate(thread, "write_file", ToolRisk.WORKSPACE_WRITE, ExecutionMode.AUTO),
        )
        // DENY wins even for read-only tools in AUTO.
        policy.setRule("read_file", ToolRule.DENY)
        assertEquals(
            PolicyDecision.BLOCK,
            policy.evaluate(thread, "read_file", ToolRisk.READ_ONLY, ExecutionMode.AUTO),
        )
    }

    @Test
    fun explicitDeny_winsOverAlwaysAndSessionGrant() {
        val policy = PermissionPolicy()
        policy.setRule("write_file", ToolRule.DENY)
        policy.grantForSession(thread, "write_file")
        assertEquals(
            PolicyDecision.BLOCK,
            policy.evaluate(thread, "write_file", ToolRisk.WORKSPACE_WRITE, ExecutionMode.AUTO),
        )
    }

    @Test
    fun sessionGrant_allowsToolAfterGrant() {
        val policy = PermissionPolicy()
        // Before the grant, ASK mode prompts for a workspace write.
        assertEquals(
            PolicyDecision.PROMPT_USER,
            policy.evaluate(thread, "write_file", ToolRisk.WORKSPACE_WRITE, ExecutionMode.ASK),
        )
        policy.grantForSession(thread, "write_file")
        assertEquals(
            PolicyDecision.ALLOW,
            policy.evaluate(thread, "write_file", ToolRisk.WORKSPACE_WRITE, ExecutionMode.ASK),
        )
    }

    @Test
    fun sessionGrant_isScopedToItsThread() {
        val policy = PermissionPolicy()
        policy.grantForSession(thread, "write_file")
        assertEquals(
            PolicyDecision.ALLOW,
            policy.evaluate(thread, "write_file", ToolRisk.WORKSPACE_WRITE, ExecutionMode.ASK),
        )
        assertEquals(
            PolicyDecision.PROMPT_USER,
            policy.evaluate(
                "thr_other", "write_file", ToolRisk.WORKSPACE_WRITE, ExecutionMode.ASK,
            ),
        )
    }

    @Test
    fun clearSession_revokesGrant() {
        val policy = PermissionPolicy()
        policy.grantForSession(thread, "write_file")
        assertEquals(
            PolicyDecision.ALLOW,
            policy.evaluate(thread, "write_file", ToolRisk.WORKSPACE_WRITE, ExecutionMode.ASK),
        )
        policy.clearSession(thread)
        assertEquals(
            PolicyDecision.PROMPT_USER,
            policy.evaluate(thread, "write_file", ToolRisk.WORKSPACE_WRITE, ExecutionMode.ASK),
        )
    }

    @Test
    fun explicitAlways_allowsOutsidePlanMode() {
        val policy = PermissionPolicy()
        policy.setRule("run_shell", ToolRule.ALWAYS)
        assertEquals(
            PolicyDecision.ALLOW,
            policy.evaluate(
                thread, "run_shell", ToolRisk.EXTERNAL_OR_DESTRUCTIVE, ExecutionMode.ASK,
            ),
        )
        assertEquals(
            PolicyDecision.ALLOW,
            policy.evaluate(
                thread, "run_shell", ToolRisk.EXTERNAL_OR_DESTRUCTIVE, ExecutionMode.AUTO,
            ),
        )
    }

    @Test
    fun explicitAlways_doesNotOverridePlanBlock() {
        val policy = PermissionPolicy()
        policy.setRule("run_shell", ToolRule.ALWAYS)
        assertEquals(
            PolicyDecision.BLOCK,
            policy.evaluate(
                thread, "run_shell", ToolRisk.EXTERNAL_OR_DESTRUCTIVE, ExecutionMode.PLAN,
            ),
        )
    }

    @Test
    fun explicitAsk_forcesPromptEvenInAuto() {
        val policy = PermissionPolicy()
        policy.setRule("write_file", ToolRule.ASK)
        assertEquals(
            PolicyDecision.PROMPT_USER,
            policy.evaluate(thread, "write_file", ToolRisk.WORKSPACE_WRITE, ExecutionMode.AUTO),
        )
    }

    @Test
    fun defaultRules_constructor_andGetRule() {
        val policy = PermissionPolicy(mapOf("write_file" to ToolRule.DENY))
        assertEquals(ToolRule.DENY, policy.getRule("write_file"))
        assertNull(policy.getRule("unknown_tool"))
        assertEquals(
            PolicyDecision.BLOCK,
            policy.evaluate(thread, "write_file", ToolRisk.WORKSPACE_WRITE, ExecutionMode.AUTO),
        )
    }
}
