package dev.codex.android.skills

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the `skill` tool ([SkillTool]): resolution, errors,
 * and scope activation. No Android APIs, no I/O — the tool takes a
 * plain `() -> List<Skill>` provider, so tests pass
 * [SkillStore.BUILTINS] directly. The agent-loop side of scoping
 * (offered tools + enforcement) is proven in SkillScopingTest.
 */
class SkillToolTest {

    private fun tool(
        skills: List<Skill> = SkillStore.BUILTINS,
        scope: ActiveSkillScope? = null,
    ) = SkillTool(skillsProvider = { skills }, skillScope = scope)

    private fun args(skillId: String, input: String? = null) = buildJsonObject {
        put("skill_id", skillId)
        if (input != null) put("input", input)
    }

    @Test
    fun knownSkill_resolvesReturnsInstructionsAndActivatesScope() = runBlocking {
        val scope = ActiveSkillScope()
        val result = tool(scope = scope).execute(args("what-time", "what time is it?"))
        assertTrue(result.ok)
        assertTrue(result.output.contains("Use get_time"))
        assertTrue(result.output.contains("what time is it?"))
        assertTrue(result.output.contains("ONLY these tools: get_time"))
        assertEquals("what-time", scope.activeSkill?.id)
        assertEquals(setOf("get_time", "skill"), scope.allowedToolNames())
    }

    @Test
    fun unknownSkillId_clearErrorNamingTheId() = runBlocking {
        val scope = ActiveSkillScope()
        val result = tool(scope = scope).execute(args("no-such-skill"))
        assertFalse(result.ok)
        val err = result.error!!
        assertTrue(err.contains("no-such-skill"))
        assertTrue(err.contains("No skill with id"))
        assertTrue(err.contains("what-time"))
        assertEquals(null, scope.activeSkill)
    }

    @Test
    fun disabledSkill_clearErrorNamingTheId() = runBlocking {
        val disabled = SkillStore.BUILTINS.map {
            if (it.id == "what-time") it.copy(enabled = false) else it
        }
        val scope = ActiveSkillScope()
        val result = tool(skills = disabled, scope = scope).execute(args("what-time"))
        assertFalse(result.ok)
        val err = result.error!!
        assertTrue(err.contains("what-time"))
        assertTrue(err.contains("disabled"))
        assertEquals(null, scope.activeSkill)
    }

    @Test
    fun missingSkillId_clearError() = runBlocking {
        val result = tool().execute(buildJsonObject {})
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("skill_id"))
    }

    @Test
    fun switchingSkills_replacesTheActiveSkill() = runBlocking {
        val scope = ActiveSkillScope()
        val skillTool = tool(scope = scope)
        assertTrue(skillTool.execute(args("what-time")).ok)
        assertEquals("what-time", scope.activeSkill?.id)
        assertTrue(skillTool.execute(args("pdf", "summarise this PDF")).ok)
        assertEquals("pdf", scope.activeSkill?.id)
        assertEquals(
            setOf("read_pdf", "pdf_create", "pdf_merge", "pdf_split", "pdf_rotate", "pdf_forms", "pdf_to_image", "ocr_image", "attachment_read", "skill"),
            scope.allowedToolNames(),
        )
    }

    @Test
    fun noScope_instructionsOnlyStillWorks() = runBlocking {
        val result = tool(scope = null).execute(args("summarise-screen"))
        assertTrue(result.ok)
        assertTrue(result.output.contains("read_screen"))
    }
}
