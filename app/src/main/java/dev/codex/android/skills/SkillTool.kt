package dev.codex.android.skills

import dev.codex.android.tools.Tool
import dev.codex.android.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The `skill` tool (OpenCode has one): runs one of the agent's skills
 * by id.
 *
 * Behaviour: looks the skill up in [skillsProvider] (the enabled
 * skills of the current run — the caller passes the same list the
 * agent loop was built with, so the tool can never invoke a skill the
 * loop does not know about). Missing or disabled ids fail with a
 * clear error naming the id. On success it returns the skill's
 * instructions + its allowed tools, and — when [skillScope] is given
 * — activates the skill in the scope, which the agent loop then
 * enforces: while a skill is active the model is only offered that
 * skill's tools (plus `skill` itself, to switch). Without a scope the
 * tool is instructions-only; the returned text still tells the model
 * to use only the skill's tools.
 *
 * Not sensitive: running a skill only narrows what the agent may do
 * next; the skill's own tools keep their own sensitivity.
 */
class SkillTool(
    private val skillsProvider: () -> List<Skill>,
    private val skillScope: ActiveSkillScope? = null,
) : Tool {
    override val name: String = ActiveSkillScope.SKILL_TOOL_NAME
    override val description: String =
        "Runs one of the agent's skills by id. Use it when the user asks for a skill, or when a skill matches the request better than free-form tools. Returns the skill's instructions and its allowed tools; while the skill is active only those tools are available."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("skill_id") {
                put("type", "string")
                put("description", "The id of the skill to run, e.g. \"what-time\", \"summarise-screen\", \"document\", \"image\"")
            }
            putJsonObject("input") {
                put("type", "string")
                put("description", "The user's request for that skill, e.g. the question about the document or image")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("skill_id")) }
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val skillId = args["skill_id"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult(
                ok = false,
                output = "",
                error = "Missing required argument: skill_id. Available skills: ${availableIds()}",
            )
        if (skillId.isEmpty()) {
            return ToolResult(
                ok = false,
                output = "",
                error = "skill_id must not be empty. Available skills: ${availableIds()}",
            )
        }
        val skills = skillsProvider()
        val skill = SkillRunner.findSkill(skills, skillId)
            ?: return ToolResult(
                ok = false,
                output = "",
                error = "No skill with id '$skillId'. Available skills: ${availableIds(skills)}",
            )
        if (!skill.enabled) {
            return ToolResult(
                ok = false,
                output = "",
                error = "Skill '$skillId' (${skill.name}) is disabled. Enable it in Settings > Skills and try again.",
            )
        }
        skillScope?.activate(skill)
        val input = args["input"]?.jsonPrimitive?.contentOrNull
        return ToolResult(ok = true, output = SkillRunner.buildSkillInvocation(skill, input))
    }

    private fun availableIds(skills: List<Skill> = skillsProvider()): String =
        skills.joinToString(", ") { it.id }.ifEmpty { "(none)" }
}
