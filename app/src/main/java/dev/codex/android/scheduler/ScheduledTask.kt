package dev.codex.android.scheduler

import kotlinx.serialization.Serializable

/** A one-shot task the agent should run at [triggerAtMillis] (wall-clock epoch millis). */
@Serializable
data class ScheduledTask(
    val id: String,
    val title: String,
    val prompt: String,
    val triggerAtMillis: Long,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val enabled: Boolean = true,
)
