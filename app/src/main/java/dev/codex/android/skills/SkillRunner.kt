package dev.codex.android.skills

/**
 * Renders skills into prompt text for the agent loop.
 *
 * Pure function: no network, no I/O, never throws for ordinary input.
 * The agent loop / wiring passes [SkillStore.enabled] (or any list) and
 * appends the result to the system prompt so the model knows which
 * skills exist, what they do, and which tools each one may use.
 */
object SkillRunner {

    /**
     * One line per skill:
     * `Skill "<name>" (<id>): <description> Instructions: <instructions> Tools: <tool1, tool2>`
     * Lines are joined by newlines. Returns "" when [skills] is empty.
     */
    fun buildSkillPrompt(skills: List<Skill>): String {
        if (skills.isEmpty()) return ""
        return skills.joinToString("\n") { skill ->
            "Skill \"${skill.name}\" (${skill.id}): ${skill.description} " +
                "Instructions: ${skill.instructions} " +
                "Tools: ${skill.toolNames.joinToString(", ")}"
        }
    }
}
