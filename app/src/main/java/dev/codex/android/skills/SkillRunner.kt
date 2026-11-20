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

    /** Finds the skill with [id] in [skills], or null when there is none. Pure. */
    fun findSkill(skills: List<Skill>, id: String): Skill? =
        skills.firstOrNull { it.id == id }

    /**
     * The text the `skill` tool returns when a skill is invoked: the
     * skill's instructions, the user's request for it, and the hard
     * rule that only its tools may be used until it is done. Pure.
     * The agent loop enforces the tool list as well (see
     * [dev.codex.android.agent.AgentLoop] + [ActiveSkillScope]); this
     * text is what the model reads, the scope is what binds it.
     */
    fun buildSkillInvocation(skill: Skill, input: String?): String = buildString {
        append("Running skill \"${skill.name}\" (${skill.id}).\n")
        append("Instructions: ${skill.instructions}\n")
        if (!input.isNullOrBlank()) append("User request: $input\n")
        val tools = skill.toolNames.joinToString(", ")
        if (tools.isEmpty()) {
            append("This skill needs no tools — answer from the conversation.\n")
        } else {
            append("For this skill, use ONLY these tools: $tools. ")
            append("Do not call any other tool until the skill is done. ")
        }
        append("To run a different skill instead, call the skill tool again with that skill's id.")
    }
}
