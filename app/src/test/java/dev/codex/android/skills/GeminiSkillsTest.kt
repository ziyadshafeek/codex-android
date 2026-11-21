package dev.codex.android.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the four Gemini skills in [GeminiSkills.GEMINI_SKILLS].
 *
 * Pure-data tests: no Android APIs, no I/O. The tool-name contract is
 * pinned hardest — every tool a skill names must exist in this build
 * once the coordinator applies the register lines in the handover:
 * - `attachment_read`: registered per chat thread (ChatViewModel).
 * - `webfetch`: ToolRegistry.withBuiltins. `shell_exec`,
 *   `workspace_list`, `workspace_read`: registerDeveloperTools.
 * - `image-generate`: class exists (tools/ImageGenerateTool.kt);
 *   coordinator registers it (handover line).
 * - `gemini_live`, `gemini_video`: new tools built in this pass;
 *   coordinator registers them (handover lines).
 * Ghost tools (`read_image`, `read_pdf`, …) and the CLI binary name
 * (`gemini-api` — a shell program, not a tool) must never be named.
 */
class GeminiSkillsTest {

    /** Tools that exist once the coordinator applies the handover register lines. */
    private val registeredTools = setOf(
        "attachment_read",
        "webfetch",
        "image-generate",
        "shell_exec",
        "workspace_list",
        "workspace_read",
        "gemini_live",
        "gemini_video",
    )

    @Test
    fun gemini_hasFourSkillsWithUniqueIds() {
        val ids = GeminiSkills.GEMINI_SKILLS.map { it.id }
        assertEquals(
            listOf("gemini-api-cli", "gemini-api-dev", "gemini-live-api-dev", "gemini-omni-flash-api"),
            ids.sorted(),
        )
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyGeminiSkill_isEnabledAndHasInstructions() {
        for (skill in GeminiSkills.GEMINI_SKILLS) {
            assertTrue("skill ${skill.id} must be enabled by default", skill.enabled)
            assertTrue("skill ${skill.id} needs a description", skill.description.isNotBlank())
            assertTrue("skill ${skill.id} needs instructions", skill.instructions.isNotBlank())
            assertTrue("skill ${skill.id} must name at least one tool", skill.toolNames.isNotEmpty())
        }
    }

    @Test
    fun everyNamedTool_actuallyExistsInThisBuild() {
        for (skill in GeminiSkills.GEMINI_SKILLS) {
            for (tool in skill.toolNames) {
                assertTrue(
                    "skill ${skill.id} names '$tool', which is not registered in this build",
                    tool in registeredTools,
                )
            }
        }
    }

    @Test
    fun noGhostTools_namedAnywhere() {
        // The Gemini skills teach the Gemini API path, not document or
        // image reading: they must not name the read_* tools (which do
        // exist in this build, but belong to the document/image skills)
        // or "gemini-api" (the CLI binary name, not a tool).
        val outOfScope = listOf("read_image", "read_pdf", "read_document", "read_text_file", "gemini-api")
        for (skill in GeminiSkills.GEMINI_SKILLS) {
            for (ghost in outOfScope) {
                assertFalse("skill ${skill.id} must not name '$ghost'", ghost in skill.toolNames)
                // "gemini-api" is the CLI binary name, which legitimately
                // appears in the CLI skill's prose — but only as a shell
                // command, never as a tool. The toolNames check above
                // covers the binding; here only the tool-named ghosts
                // (read_*) are banned from prose too.
                if (ghost != "gemini-api") {
                    assertFalse(
                        "skill ${skill.id} instructions must not name '$ghost'",
                        skill.instructions.contains(ghost),
                    )
                }
            }
        }
    }

    @Test
    fun apiDevSkill_namesOpenAiPathAndImageGenerate() {
        val skill = SkillRunner.findSkill(GeminiSkills.GEMINI_SKILLS, "gemini-api-dev")!!
        assertEquals(listOf("attachment_read", "webfetch", "image-generate"), skill.toolNames)
        assertTrue(skill.instructions.contains(GeminiSkills.GEMINI_OPENAI_BASE_URL))
        assertTrue(skill.instructions.contains("gemini-4-hydrogen"))
        assertTrue(skill.instructions.contains("image-generate"))
        assertTrue(skill.instructions.contains("gemini-2.5"))
        assertFalse(skill.instructions.contains("read_image"))
    }

    @Test
    fun apiCliSkill_namesShellExecAndChecksTheBinaryFirst() {
        val skill = SkillRunner.findSkill(GeminiSkills.GEMINI_SKILLS, "gemini-api-cli")!!
        assertTrue(skill.toolNames.contains("shell_exec"))
        assertTrue(skill.instructions.contains("command -v gemini-api"))
        assertTrue(skill.instructions.contains("whoami"))
        assertTrue(skill.instructions.contains("install.sh"))
    }

    @Test
    fun liveSkill_namesOnlyGeminiLiveTool() {
        val skill = SkillRunner.findSkill(GeminiSkills.GEMINI_SKILLS, "gemini-live-api-dev")!!
        assertEquals(listOf("gemini_live"), skill.toolNames)
        assertTrue(skill.instructions.contains("gemini_live"))
        assertTrue(skill.instructions.contains("gemini-3.8-live"))
    }

    @Test
    fun omniSkill_namesGeminiVideoAndLimits() {
        val skill = SkillRunner.findSkill(GeminiSkills.GEMINI_SKILLS, "gemini-omni-flash-api")!!
        assertEquals(listOf("gemini_video", "shell_exec"), skill.toolNames)
        assertTrue(skill.instructions.contains("gemini_video"))
        assertTrue(skill.instructions.contains("gemini-omni-1.2-flash"))
        assertTrue(skill.instructions.contains("30 seconds"))
    }

    @Test
    fun skillPrompt_listsAllFourGeminiSkills() {
        val prompt = SkillRunner.buildSkillPrompt(GeminiSkills.GEMINI_SKILLS)
        for (id in listOf("gemini-api-dev", "gemini-api-cli", "gemini-live-api-dev", "gemini-omni-flash-api")) {
            assertTrue(prompt.contains(id))
        }
    }

    @Test
    fun geminiConstants_matchProviderPreset() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/",
            GeminiSkills.GEMINI_OPENAI_BASE_URL,
        )
        assertEquals("gemini-4-hydrogen", GeminiSkills.GEMINI_DEFAULT_MODEL)
    }
}
