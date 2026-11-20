package dev.codex.android.skills

/**
 * Which skill, if any, the agent is currently running under.
 *
 * Set by the `skill` tool when the model invokes a skill, read by
 * [dev.codex.android.agent.AgentLoop] to scope the toolset for the
 * rest of that run. One instance is shared by the tool and the loop
 * of a single run (wired by the caller); it is never global state, so
 * two runs can never scope each other's tools. A fresh run starts
 * with no active skill.
 */
class ActiveSkillScope {

    /** The skill now in force, or null when no skill is active. */
    @Volatile
    var activeSkill: Skill? = null
        private set

    /** Activates [skill] for the rest of the run. */
    fun activate(skill: Skill) {
        activeSkill = skill
    }

    /** Drops the active skill; the full toolset is available again. */
    fun clear() {
        activeSkill = null
    }

    /**
     * Tool names the model may call while a skill is active: the
     * skill's own [Skill.toolNames] plus the `skill` tool itself (so
     * the model can switch to another skill). Null when no skill is
     * active, meaning "no scoping".
     */
    fun allowedToolNames(): Set<String>? =
        activeSkill?.let { (it.toolNames + SKILL_TOOL_NAME).toSet() }

    companion object {
        /** Name of the tool that activates a skill. */
        const val SKILL_TOOL_NAME = "skill"
    }
}
