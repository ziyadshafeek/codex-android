package dev.codex.android.agent

import kotlinx.serialization.Serializable

/** Lifecycle status of the current (or most recent) agent run. */
@Serializable
enum class RunStatus {
    IDLE,
    RUNNING,
    PAUSED,
    DONE,
    FAILED,
}

/**
 * Persisted run state, so a run interrupted by process death or reboot
 * can be resumed — or, via [dev.codex.android.scheduler.ResurrectionWorker],
 * the agent service can be restarted when a run was still RUNNING.
 */
@Serializable
data class RunState(
    val goal: String,
    val planId: String,
    val status: RunStatus,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)
