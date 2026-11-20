package dev.codex.android.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure-instruction skills in [InstructionSkills].
 *
 * Pure-data tests: no Android APIs, no I/O. The hard contract is the
 * tool-name one — every tool a skill names must actually exist in
 * this build (verified from tools/Tool.kt withBuiltins, the developer
 * tools in tools/DeveloperTools.kt, the per-chat attachment_read /
 * memory_search registrations in ui/ChatViewModel.kt, and the new
 * read_image / image_enhance tools plus the image-generate tool
 * file). A skill with no fitting real tool names an empty list and
 * says so in its instructions — it never names a tool that does not
 * exist.
 */
class InstructionSkillsTest {

    /** Tools verified (by reading the sources above) to exist in this build. */
    private val existingTools = setOf(
        // ToolRegistry.withBuiltins (tools/Tool.kt)
        "get_time", "device_info", "echo", "open_app", "tap", "type_text",
        "read_screen", "scroll", "go_back", "press_home", "schedule_task",
        "generate_app", "list_store", "question", "todowrite", "todoread",
        "webfetch", "websearch", "swipe", "tap_node", "send_intent",
        "clipboard_get", "clipboard_set",
        // Developer tools (tools/DeveloperTools.kt)
        "workspace_list", "workspace_read", "workspace_offer_download",
        "workspace_write", "shell_exec", "github_publish", "github_build",
        "github_build_status",
        // Per-chat registrations (ui/ChatViewModel.kt)
        "attachment_read", "memory_search",
        // New tools from this task + the image-generate tool file
        "read_image", "image_enhance", "image-generate",
    )

    /** Tools that do not exist in this build and must never be named anywhere. */
    private val ghosts = listOf("create_file", "str_replace")

    @Test
    fun instructionSkills_hasTenSkillsWithUniqueIds() {
        val ids = InstructionSkills.ALL.map { it.id }
        assertEquals(
            listOf(
                "changelog-generator",
                "content-research-writer",
                "discernment-nudge",
                "doc-coauthoring",
                "file-organizer",
                "frontend-design",
                "internal-comms",
                "lead-research-assistant",
                "raffle-winner-picker",
                "twitter-algorithm-optimizer",
            ),
            ids.sorted(),
        )
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyInstructionSkill_isEnabledAndHasInstructions() {
        for (skill in InstructionSkills.ALL) {
            assertTrue("skill ${skill.id} must be enabled by default", skill.enabled)
            assertTrue("skill ${skill.id} needs a description", skill.description.isNotBlank())
            assertTrue("skill ${skill.id} needs instructions", skill.instructions.isNotBlank())
        }
    }

    @Test
    fun everyNamedTool_actuallyExistsInThisBuild() {
        for (skill in InstructionSkills.ALL) {
            for (tool in skill.toolNames) {
                assertTrue(
                    "skill ${skill.id} names '$tool', which does not exist in this build",
                    tool in existingTools,
                )
            }
        }
    }

    @Test
    fun noSkill_namesAGhostTool() {
        for (skill in InstructionSkills.ALL) {
            for (ghost in ghosts) {
                assertFalse("skill ${skill.id} must not name '$ghost'", ghost in skill.toolNames)
                assertFalse("skill ${skill.id} must not mention '$ghost'", skill.instructions.contains(ghost))
            }
        }
    }

    @Test
    fun toolNames_matchEachSkillsRealNeeds() {
        assertEquals(
            emptyList<String>(),
            SkillRunner.findSkill(InstructionSkills.ALL, "discernment-nudge")!!.toolNames,
        )
        assertEquals(
            listOf("workspace_write", "workspace_read", "workspace_list", "attachment_read"),
            SkillRunner.findSkill(InstructionSkills.ALL, "doc-coauthoring")!!.toolNames,
        )
        assertEquals(
            listOf("workspace_write", "workspace_read", "workspace_list", "image-generate"),
            SkillRunner.findSkill(InstructionSkills.ALL, "frontend-design")!!.toolNames,
        )
        assertEquals(
            listOf("workspace_write"),
            SkillRunner.findSkill(InstructionSkills.ALL, "internal-comms")!!.toolNames,
        )
        assertEquals(
            listOf("websearch"),
            SkillRunner.findSkill(InstructionSkills.ALL, "twitter-algorithm-optimizer")!!.toolNames,
        )
        assertEquals(
            listOf("websearch", "webfetch", "workspace_write", "workspace_read"),
            SkillRunner.findSkill(InstructionSkills.ALL, "content-research-writer")!!.toolNames,
        )
        assertEquals(
            listOf("shell_exec", "workspace_read", "workspace_write"),
            SkillRunner.findSkill(InstructionSkills.ALL, "changelog-generator")!!.toolNames,
        )
        assertEquals(
            listOf("websearch", "webfetch", "workspace_write"),
            SkillRunner.findSkill(InstructionSkills.ALL, "lead-research-assistant")!!.toolNames,
        )
        assertEquals(
            listOf("workspace_read", "shell_exec", "workspace_write"),
            SkillRunner.findSkill(InstructionSkills.ALL, "raffle-winner-picker")!!.toolNames,
        )
        assertEquals(
            listOf("workspace_list", "workspace_read", "shell_exec", "workspace_write"),
            SkillRunner.findSkill(InstructionSkills.ALL, "file-organizer")!!.toolNames,
        )
    }

    @Test
    fun discernmentNudge_teachesExactLeadInAndOncePerConversation() {
        val skill = SkillRunner.findSkill(InstructionSkills.ALL, "discernment-nudge")!!
        assertTrue(skill.instructions.contains("A few things worth a second look:"))
        assertTrue(skill.instructions.contains("at most once per conversation"))
        assertTrue(skill.instructions.contains("This skill needs no tools"))
        val invocation = SkillRunner.buildSkillInvocation(skill, "should I buy this?")
        assertTrue(invocation.contains("needs no tools"))
    }

    @Test
    fun changelogGenerator_usesGitLogAndFiltersNoise() {
        val skill = SkillRunner.findSkill(InstructionSkills.ALL, "changelog-generator")!!
        assertTrue(skill.instructions.contains("git log"))
        assertTrue(skill.instructions.contains("Filter the noise"))
        assertTrue(skill.instructions.contains("Breaking Changes"))
    }

    @Test
    fun raffleWinnerPicker_requiresRealRandomDraw() {
        val skill = SkillRunner.findSkill(InstructionSkills.ALL, "raffle-winner-picker")!!
        assertTrue(skill.instructions.contains("real random source"))
        assertTrue(skill.instructions.contains("shuf"))
        assertTrue(skill.instructions.contains("never by picking a favourite"))
    }

    @Test
    fun fileOrganizer_neverDeletesWithoutApproval() {
        val skill = SkillRunner.findSkill(InstructionSkills.ALL, "file-organizer")!!
        assertTrue(skill.instructions.contains("never move or delete anything before they approve"))
        assertTrue(skill.instructions.contains("Never delete"))
    }

    @Test
    fun skillPrompt_listsAllTenInstructionSkills() {
        val prompt = SkillRunner.buildSkillPrompt(InstructionSkills.ALL)
        for (skill in InstructionSkills.ALL) {
            assertTrue(prompt.contains(skill.id))
        }
    }

    @Test
    fun findSkill_returnsNullForUnknownId() {
        assertEquals(null, SkillRunner.findSkill(InstructionSkills.ALL, "no-such-skill"))
    }
}
