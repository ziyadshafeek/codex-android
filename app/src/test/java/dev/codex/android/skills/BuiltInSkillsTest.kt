package dev.codex.android.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the built-in skills in [SkillStore.BUILTINS].
 *
 * Pure-data tests: no Android APIs, no I/O. The one thing they pin
 * down hardest is the tool-name contract — every tool a skill names
 * must actually exist in this build:
 * - `read_screen`, `open_app`, `get_time`, `webfetch`, `websearch`,
 *   `image-generate`, `session-search`, the document tools
 *   (`read_pdf`, `read_document`, `read_text_file`, `pdf_create`,
 *   `pdf_merge`, `pdf_split`, `pdf_rotate`, `pdf_forms`,
 *   `document_create`, `document_edit`, `pdf_to_image`), `where_is`,
 *   `html-markdown`, `http-body`, `model-policy`: registered in
 *   `ToolRegistry.withBuiltins` (tools/Tool.kt).
 * - `workspace_list`, `workspace_read`, `workspace_write`,
 *   `workspace_offer_download`, `shell_exec`, `github_publish`,
 *   `github_build`, `github_build_status`: registered by
 *   `registerDeveloperTools` (tools/DeveloperTools.kt).
 * - `edit`, `apply_patch`, `glob`, `grep`, `search`, `file-diff`,
 *   `write_file`, `uninstall_package`, `clear_workspace`,
 *   `mcp_scaffold`, `skill_eval`, `webapp_test`, `webapp_build`,
 *   `read_image`, `image_enhance`, `ocr_image`, `gemini_live`,
 *   `gemini_video`, `skill`, `subagent`, `mcp-events`,
 *   `mcp-resource`, `memory_search`, `attachment_read`,
 *   `browser_open`, `browser_dom_snapshot`, `browser_console_logs`:
 *   registered per chat thread in ChatViewModel.policyGatedRegistry
 *   (ui/ChatViewModel.kt). `browser_eval` is deliberately NOT
 *   registered in chat (filtered out there), so no skill may name it.
 */
class BuiltInSkillsTest {

    /** Tools verified (by reading Tool.kt + ChatViewModel.kt) to be registered. */
    private val registeredTools = setOf(
        "read_screen", "open_app", "get_time", "webfetch", "websearch",
        "image-generate", "session-search", "read_pdf", "read_document",
        "read_text_file", "pdf_create", "pdf_merge", "pdf_split",
        "pdf_rotate", "pdf_forms", "document_create", "document_edit",
        "pdf_to_image", "where_is", "html-markdown", "http-body",
        "model-policy", "workspace_list", "workspace_read",
        "workspace_write", "workspace_offer_download", "shell_exec",
        "github_publish", "github_build", "github_build_status",
        "edit", "apply_patch", "glob", "grep", "search", "file-diff",
        "write_file", "uninstall_package", "clear_workspace",
        "mcp_scaffold", "skill_eval", "webapp_test", "webapp_build",
        "read_image", "image_enhance", "ocr_image", "gemini_live",
        "gemini_video", "skill", "subagent", "mcp-events", "mcp-resource",
        "memory_search", "attachment_read", "browser_open",
        "browser_dom_snapshot", "browser_console_logs",
    )

    private val expectedIds = listOf(
        "changelog-generator", "content-research-writer", "discernment-nudge",
        "doc-coauthoring", "docx", "file-organizer", "firebase",
        "frontend-design", "gemini-api-cli", "gemini-api-dev",
        "gemini-live-api-dev", "gemini-omni-flash-api", "image",
        "image-enhancer", "image-generate", "internal-comms",
        "invoice-organizer", "lead-research-assistant", "mcp-builder",
        "mobile-ads", "open-app-and-read", "pdf", "pptx",
        "raffle-winner-picker", "skill-creator", "summarise-screen",
        "tailored-resume-generator", "twitter-algorithm-optimizer",
        "web-artifacts-builder", "webapp-testing", "what-time", "xlsx",
    )

    @Test
    fun builtins_hasThirtyTwoSkillsWithUniqueIds() {
        val ids = SkillStore.BUILTINS.map { it.id }
        assertEquals(expectedIds, ids.sorted())
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyBuiltin_isEnabledAndHasInstructions() {
        for (skill in SkillStore.BUILTINS) {
            assertTrue("skill ${skill.id} must be enabled by default", skill.enabled)
            assertTrue("skill ${skill.id} needs a description", skill.description.isNotBlank())
            assertTrue("skill ${skill.id} needs instructions", skill.instructions.isNotBlank())
        }
        // Exactly one pure-instruction skill names no tools (it reasons
        // from the conversation); every other skill must name at least one.
        val empty = SkillStore.BUILTINS.filter { it.toolNames.isEmpty() }.map { it.id }
        assertEquals(listOf("discernment-nudge"), empty)
    }

    @Test
    fun everyNamedTool_actuallyExistsInThisBuild() {
        for (skill in SkillStore.BUILTINS) {
            for (tool in skill.toolNames) {
                assertTrue(
                    "skill ${skill.id} names '$tool', which is not registered in this build",
                    tool in registeredTools,
                )
            }
        }
    }

    @Test
    fun noSkillNamesBrowserEval() {
        for (skill in SkillStore.BUILTINS) {
            assertFalse(
                "skill ${skill.id} must not name 'browser_eval' — the chat registry filters it out",
                "browser_eval" in skill.toolNames || skill.instructions.contains("browser_eval"),
            )
        }
    }

    @Test
    fun pdfSkill_namesRealPdfToolsAndPdfToImage() {
        val skill = SkillRunner.findSkill(SkillStore.BUILTINS, "pdf")!!
        assertEquals(
            listOf("read_pdf", "pdf_create", "pdf_merge", "pdf_split", "pdf_rotate", "pdf_forms", "pdf_to_image", "ocr_image", "attachment_read"),
            skill.toolNames,
        )
        assertTrue(skill.instructions.contains("read_pdf"))
        assertTrue(skill.instructions.contains("[p. N]"))
    }

    @Test
    fun imageSkill_namesReadImageTool() {
        val skill = SkillRunner.findSkill(SkillStore.BUILTINS, "image")!!
        assertTrue("image skill must name 'read_image'", "read_image" in skill.toolNames)
        assertTrue("image skill must name 'attachment_read'", "attachment_read" in skill.toolNames)
        assertTrue(skill.instructions.contains("read_image"))
    }

    @Test
    fun skillPrompt_listsAllSkills() {
        val prompt = SkillRunner.buildSkillPrompt(SkillStore.BUILTINS)
        for (id in expectedIds) {
            assertTrue("skill prompt must list '$id'", prompt.contains(id))
        }
    }

    @Test
    fun findSkill_returnsNullForUnknownId() {
        assertEquals(null, SkillRunner.findSkill(SkillStore.BUILTINS, "no-such-skill"))
    }

    @Test
    fun skillInvocation_returnsInstructionsAndAllowedToolsOnly() {
        val skill = SkillRunner.findSkill(SkillStore.BUILTINS, "what-time")!!
        val invocation = SkillRunner.buildSkillInvocation(skill, "what time is it in Kolkata?")
        assertTrue(invocation.contains("Use get_time"))
        assertTrue(invocation.contains("what time is it in Kolkata?"))
        assertTrue(invocation.contains("ONLY these tools: get_time"))
    }
}
