package dev.codex.android.agent

import kotlinx.serialization.Serializable

/** One step inside a [Plan]. */
@Serializable
data class PlanStep(
    val id: String,
    val title: String,
    val status: PlanStepStatus = PlanStepStatus.PENDING,
    val detail: String? = null,
)

@Serializable
enum class PlanStepStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    SKIPPED,
}

/** A goal broken into steps, persisted so it survives process death. */
@Serializable
data class Plan(
    val id: String,
    val goal: String,
    val steps: List<PlanStep> = emptyList(),
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    fun withStepUpdated(stepId: String, status: PlanStepStatus, detail: String? = null): Plan {
        val next = steps.map { step ->
            if (step.id == stepId) step.copy(status = status, detail = detail ?: step.detail) else step
        }
        return copy(steps = next, updatedAtMillis = System.currentTimeMillis())
    }

    fun withSteps(newSteps: List<PlanStep>): Plan =
        copy(steps = newSteps, updatedAtMillis = System.currentTimeMillis())
}
