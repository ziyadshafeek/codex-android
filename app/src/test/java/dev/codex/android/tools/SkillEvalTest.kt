package dev.codex.android.tools

import dev.codex.android.skills.ActiveSkillScope
import dev.codex.android.skills.Skill
import dev.codex.android.skills.SkillTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [SkillEval]: the eval harness drives skills through the
 * real [SkillTool] path (lookup, enabled check, scope activation).
 */
class SkillEvalTest {

    private val passingSkill = Skill(
        id = "demo",
        name = "Demo skill",
        description = "A skill used by tests.",
        instructions = "Use workspace_read to read the file, then workspace_write to save the summary.",
        toolNames = listOf("workspace_read", "workspace_write"),
    )

    private fun skillTool(skills: List<Skill> = listOf(passingSkill)) =
        SkillTool(skillsProvider = { skills }, skillScope = ActiveSkillScope())

    @Test
    fun skillPassingAllCases_reportsAllPass() = runBlocking {
        val cases = listOf(
            SkillEvalCase("reads", "read notes.txt", expectedTools = listOf("workspace_read")),
            SkillEvalCase("writes", "save it", expectedTools = listOf("workspace_write")),
            SkillEvalCase(
                "mentions-read",
                "what do I do first?",
                expectedOutputSubstring = "workspace_read",
            ),
        )
        val report = SkillEval.run(skillTool(), "demo", cases)
        assertEquals(null, report.skillError)
        assertEquals(3, report.passedCount)
        assertEquals(0, report.failedCount)
        assertTrue(report.format().contains("3/3 passed"))
    }

    @Test
    fun skillFailingOneCase_reportsItWithoutCrashing() = runBlocking {
        val cases = listOf(
            SkillEvalCase("reads", "read notes.txt", expectedTools = listOf("workspace_read")),
            SkillEvalCase(
                "needs-shell",
                "run the tests",
                expectedTools = listOf("shell_exec"),
                forbiddenTools = listOf("workspace_write"),
            ),
        )
        val report = SkillEval.run(skillTool(), "demo", cases)
        assertEquals(null, report.skillError)
        assertEquals(1, report.passedCount)
        assertEquals(1, report.failedCount)
        val failed = report.results.first { !it.passed }
        assertEquals("needs-shell", failed.caseName)
        assertTrue(failed.detail.contains("shell_exec"))
        assertTrue(report.format().contains("FAIL [needs-shell]"))
        assertTrue(report.format().contains("PASS [reads]"))
    }

    @Test
    fun unknownSkill_isAClearError() = runBlocking {
        val report = SkillEval.run(
            skillTool(),
            "no-such-skill",
            listOf(SkillEvalCase("any", "do it")),
        )
        assertTrue(report.skillError != null)
        assertTrue(report.skillError!!.contains("no-such-skill"))
        assertEquals(0, report.results.size)
    }

    @Test
    fun disabledSkill_isAClearError() = runBlocking {
        val disabled = passingSkill.copy(enabled = false)
        val report = SkillEval.run(
            skillTool(listOf(disabled)),
            "demo",
            listOf(SkillEvalCase("any", "do it")),
        )
        assertTrue(report.skillError != null)
        assertTrue(report.skillError!!.contains("disabled"))
    }

    @Test
    fun parseCases_rejectsMalformedJson() {
        try {
            SkillEval.parseCases("not json")
            assertFalse("expected IllegalArgumentException", true)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("JSON"))
        }
        try {
            SkillEval.parseCases("[{\"name\":\"x\"}]")
            assertFalse("expected IllegalArgumentException", true)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("input"))
        }
    }

    @Test
    fun parseCases_readsAllFields() {
        val cases = SkillEval.parseCases(
            """[{"name":"a","input":"do a","expectedTools":["t1"],"expectedOutputSubstring":"hello","forbiddenTools":["t2"]}]""",
        )
        assertEquals(1, cases.size)
        assertEquals(listOf("t1"), cases[0].expectedTools)
        assertEquals("hello", cases[0].expectedOutputSubstring)
        assertEquals(listOf("t2"), cases[0].forbiddenTools)
    }
}
