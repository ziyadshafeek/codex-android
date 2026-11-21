package dev.codex.android.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [BUILDER_SKILLS]: six builder skills, well-formed, and
 * every named tool actually exists in this build.
 *
 * Tool existence is verified against the names read from the sources
 * that register them — the same precedent as [BuiltInSkillsTest]:
 * - `mcp_scaffold`, `skill_eval`, `webapp_test`, `webapp_build`:
 *   `tools/BuilderTools.kt` (this batch).
 * - `workspace_write`, `workspace_read`, `shell_exec`, `github_publish`:
 *   `tools/DeveloperTools.kt`.
 * - `browser_open`, `browser_dom_snapshot`, `browser_console_logs`:
 *   `browser/BrowserSubagentTools.kt` (registered via
 *   `browserTools()` in ChatViewModel; `browser_eval` and
 *   `browser_screenshot` are filtered out there, so no skill names
 *   them — see the negative assertions below).
 *
 * Deliberately NOT named by any builder skill: `browser_eval` /
 * `browser_screenshot` (filtered out of the chat registry). The
 * `skill` tool and the document/image tools (`read_pdf`,
 * `read_document`, `read_text_file`, `read_image`) exist and are
 * registered in this build — they are simply not needed by the
 * builder skills.
 */
class BuilderSkillsTest {

    /** Tools verified (by reading the registering sources) to exist in this build. */
    private val registeredTools = setOf(
        "mcp_scaffold", "skill_eval", "webapp_test", "webapp_build",
        "workspace_write", "workspace_read", "workspace_list", "shell_exec",
        "github_publish", "github_build", "github_build_status", "workspace_offer_download",
        "browser_open", "browser_dom_snapshot", "browser_console_logs",
        "browser_click", "browser_type",
    )

    @Test
    fun builderSkills_hasSixWithUniqueIds() {
        val ids = BUILDER_SKILLS.map { it.id }
        assertEquals(
            listOf("firebase", "mcp-builder", "mobile-ads", "skill-creator", "web-artifacts-builder", "webapp-testing"),
            ids.sorted(),
        )
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyBuilderSkill_isEnabledAndHasInstructions() {
        for (skill in BUILDER_SKILLS) {
            assertTrue("skill ${skill.id} must be enabled by default", skill.enabled)
            assertTrue("skill ${skill.id} needs a description", skill.description.isNotBlank())
            assertTrue("skill ${skill.id} needs instructions", skill.instructions.isNotBlank())
            assertTrue("skill ${skill.id} must name at least one tool", skill.toolNames.isNotEmpty())
            assertEquals("skill ${skill.id} must not name the same tool twice", skill.toolNames.size, skill.toolNames.toSet().size)
        }
    }

    @Test
    fun everyNamedTool_actuallyExistsInThisBuild() {
        for (skill in BUILDER_SKILLS) {
            for (tool in skill.toolNames) {
                assertTrue(
                    "skill ${skill.id} names '$tool', which is not registered in this build",
                    tool in registeredTools,
                )
            }
        }
    }

    @Test
    fun noBuilderSkill_namesAGhostTool() {
        // The only tools a skill must never name: the two browser tools
        // the chat registry filters out (browser_eval, browser_screenshot).
        // The `skill` tool and the document/image tools exist and are
        // registered in this build — they are simply not used by builder
        // skills, so they are not in this list.
        val ghosts = listOf("browser_eval", "browser_screenshot")
        for (skill in BUILDER_SKILLS) {
            for (ghost in ghosts) {
                assertFalse("skill ${skill.id} must not name '$ghost'", ghost in skill.toolNames)
            }
        }
    }

    @Test
    fun mcpBuilderSkill_namesMcpScaffold() {
        val skill = SkillRunner.findSkill(BUILDER_SKILLS, "mcp-builder")!!
        assertTrue("mcp_scaffold" in skill.toolNames)
        assertTrue(skill.instructions.contains("mcp_scaffold"))
    }

    @Test
    fun skillCreatorSkill_namesSkillEval() {
        val skill = SkillRunner.findSkill(BUILDER_SKILLS, "skill-creator")!!
        assertTrue("skill_eval" in skill.toolNames)
        assertTrue(skill.instructions.contains("skill_eval"))
    }

    @Test
    fun webappTestingSkill_namesWebappTestAndBrowserTools() {
        val skill = SkillRunner.findSkill(BUILDER_SKILLS, "webapp-testing")!!
        assertTrue("webapp_test" in skill.toolNames)
        assertTrue("browser_open" in skill.toolNames)
        assertTrue("browser_dom_snapshot" in skill.toolNames)
        assertTrue(skill.instructions.contains("webapp_test"))
    }

    @Test
    fun webArtifactsBuilderSkill_namesWebappBuild() {
        val skill = SkillRunner.findSkill(BUILDER_SKILLS, "web-artifacts-builder")!!
        assertTrue("webapp_build" in skill.toolNames)
        assertTrue(skill.instructions.contains("webapp_build"))
    }

    @Test
    fun firebaseAndMobileAds_areAppBuildingOnly() {
        val firebase = SkillRunner.findSkill(BUILDER_SKILLS, "firebase")!!
        val mobileAds = SkillRunner.findSkill(BUILDER_SKILLS, "mobile-ads")!!
        assertTrue(firebase.instructions.contains("never for the Codex app itself"))
        assertTrue(mobileAds.instructions.contains("never for the Codex app itself"))
        assertTrue(mobileAds.instructions.contains("test ad"))
    }
}
