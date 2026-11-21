package dev.codex.android.tools

import dev.codex.android.core.CodexJson
import dev.codex.android.skills.SkillTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Eval harness for skills (tool: `skill_eval`, skill: skill-creator).
 *
 * Drives the skill under test through the SAME [SkillTool] path the agent
 * uses: one [SkillTool.execute] call per test case, which looks the skill
 * up, rejects a missing/disabled id, and activates it in a fresh
 * [dev.codex.android.skills.ActiveSkillScope]. The harness then checks the
 * invocation text the tool returns — the skill's instructions plus the
 * hard rule naming exactly the tools the skill may use — against each
 * case:
 * - every [SkillEvalCase.expectedTools] name must appear in that text
 *   (the skill actually offers the tool the case needs),
 * - [SkillEvalCase.expectedOutputSubstring], when given, must appear
 *   (the skill's instructions cover the case),
 * - no [SkillEvalCase.forbiddenTools] name may appear.
 *
 * Honest limit, stated in every report: this is a STATIC eval of the
 * skill definition. It proves the skill resolves, is enabled, and names
 * the right tools when invoked for real. It does NOT run a live model
 * turn, so it cannot prove the model will follow the instructions well
 * on a new prompt — the final check is always a real run.
 *
 * A failing case is reported, never thrown: [SkillEvalReport] always
 * comes back with per-case pass/fail and a summary. Only an unknown
 * skill id (or malformed cases JSON) is a harness-level error, reported
 * via [SkillEvalReport.skillError] so the tool can fail cleanly.
 */
data class SkillEvalCase(
    val name: String,
    val input: String,
    val expectedTools: List<String> = emptyList(),
    val expectedOutputSubstring: String? = null,
    val forbiddenTools: List<String> = emptyList(),
)

data class SkillEvalCaseResult(
    val caseName: String,
    val passed: Boolean,
    val detail: String,
)

data class SkillEvalReport(
    val skillId: String,
    val results: List<SkillEvalCaseResult>,
    /** Non-null when the skill itself could not be run (unknown/disabled id). */
    val skillError: String? = null,
) {
    val passedCount: Int get() = results.count { it.passed }
    val failedCount: Int get() = results.count { !it.passed }

    /** One line per case plus a summary line; safe to hand straight to the model. */
    fun format(): String = buildString {
        appendLine("Skill eval: $skillId — $passedCount/${results.size} passed")
        if (skillError != null) {
            appendLine("ERROR: $skillError")
            return@buildString
        }
        for (result in results) {
            appendLine("${if (result.passed) "PASS" else "FAIL"} [${result.caseName}] ${result.detail}")
        }
        appendLine(
            "Static eval only: this checks the skill definition through the real skill tool path " +
                "(instructions + tool list). It does not run a live model turn.",
        )
    }
}

object SkillEval {

    /**
     * Runs [cases] against the skill [skillId] through [skillTool].
     * Never throws for ordinary input: a bad skill id becomes
     * [SkillEvalReport.skillError]; a failing case becomes a FAIL line.
     */
    suspend fun run(
        skillTool: SkillTool,
        skillId: String,
        cases: List<SkillEvalCase>,
    ): SkillEvalReport {
        if (skillId.isBlank()) {
            return SkillEvalReport(skillId, emptyList(), "skill_id must not be blank.")
        }
        if (cases.isEmpty()) {
            return SkillEvalReport(skillId, emptyList(), "No test cases supplied.")
        }
        // Probe once: an unknown/disabled skill fails here, before any case runs.
        val probe = skillTool.execute(
            buildJsonObject {
                put("skill_id", skillId)
                put("input", "")
            },
        )
        if (!probe.ok) {
            return SkillEvalReport(skillId, emptyList(), probe.error ?: "Skill '$skillId' could not be run.")
        }
        val results = cases.map { case -> runCase(skillTool, skillId, case) }
        return SkillEvalReport(skillId, results)
    }

    private suspend fun runCase(
        skillTool: SkillTool,
        skillId: String,
        case: SkillEvalCase,
    ): SkillEvalCaseResult {
        val result = skillTool.execute(
            buildJsonObject {
                put("skill_id", skillId)
                put("input", case.input)
            },
        )
        if (!result.ok) {
            return SkillEvalCaseResult(
                case.name,
                false,
                "skill tool failed: ${result.error ?: "unknown error"}",
            )
        }
        val output = result.output
        val problems = mutableListOf<String>()
        for (tool in case.expectedTools) {
            if (!output.contains(tool)) problems += "expected tool '$tool' is not named by the skill"
        }
        case.expectedOutputSubstring?.let { wanted ->
            if (!output.contains(wanted)) problems += "expected text '$wanted' is missing from the skill instructions"
        }
        for (tool in case.forbiddenTools) {
            if (output.contains(tool)) problems += "forbidden tool '$tool' is named by the skill"
        }
        return if (problems.isEmpty()) {
            SkillEvalCaseResult(case.name, true, "skill invoked; all expectations met.")
        } else {
            SkillEvalCaseResult(case.name, false, problems.joinToString("; "))
        }
    }

    /**
     * Parses the `cases` argument of the `skill_eval` tool: a JSON array
     * of `{name, input, expectedTools?, expectedOutputSubstring?,
     * forbiddenTools?}` objects. Throws [IllegalArgumentException] with a
     * plain message when the JSON is malformed or a case lacks `name`/`input`.
     */
    fun parseCases(casesJson: String): List<SkillEvalCase> {
        val element = try {
            CodexJson.parseToJsonElement(casesJson)
        } catch (e: Exception) {
            throw IllegalArgumentException("cases must be a JSON array: ${e.message}")
        }
        val array = try {
            element.jsonArray
        } catch (e: Exception) {
            throw IllegalArgumentException("cases must be a JSON array of test-case objects.")
        }
        return array.mapIndexed { index, item ->
            val obj: JsonObject = try {
                item.jsonObject
            } catch (e: Exception) {
                throw IllegalArgumentException("cases[$index] must be an object with name and input.")
            }
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val input = obj["input"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (name.isBlank()) throw IllegalArgumentException("cases[$index] is missing 'name'.")
            if (input.isBlank()) throw IllegalArgumentException("cases[$index] ('$name') is missing 'input'.")
            SkillEvalCase(
                name = name,
                input = input,
                expectedTools = obj.stringList("expectedTools"),
                expectedOutputSubstring = obj["expectedOutputSubstring"]?.jsonPrimitive?.contentOrNull,
                forbiddenTools = obj.stringList("forbiddenTools"),
            )
        }
    }

    private fun JsonObject.stringList(key: String): List<String> {
        val raw = this[key] as? JsonArray ?: return emptyList()
        return raw.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }.filter { it.isNotEmpty() }
    }
}
