package dev.codex.android.skills

import kotlinx.serialization.Serializable

/**
 * A skill is data, not code: a named bundle of instructions plus the
 * names of the tools (from [dev.codex.android.tools.ToolRegistry]) the
 * agent may use while following it. Stored on-device in SQLite
 * (table `skills`, see [SkillStore]) and never fetched over the network.
 */
@Serializable
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val toolNames: List<String>,
    val version: Int = 1,
    val enabled: Boolean = true,
)
