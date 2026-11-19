package dev.codex.android.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.codex.android.data.AutomationEntity
import java.util.concurrent.TimeUnit

/**
 * Schedules [AutomationEntity] runs as unique WorkManager periodic work,
 * one unique work name per automation. Disabling (or deleting) an
 * automation cancels its work.
 *
 * Trigger model (reused from the reference implementations, behaviour
 * only — no code copied):
 * - OpenCode (MIT): scheduling lives in scheduler plugins
 *   (opencode-scheduler / opencode-tasks) — a cron expression plus
 *   conditions, supervised runs (no overlap), pause/resume, run history.
 *   The Android equivalent of "cron + OS scheduler" is WorkManager
 *   periodic work; the equivalent of "supervised, no overlap" is unique
 *   work (one worker per automation) plus [AgentRuntime]'s one-run-per-
 *   thread guard.
 * - OpenAI Codex scheduled tasks (previously "automations"): instruction
 *   + cadence (daily/hourly/custom recurrence), runs report back to a
 *   Scheduled inbox for review, and runs execute unattended with
 *   approval_policy = "never" (whatever would need approval fails
 *   instead of hanging). That is exactly what [AutomationWorker] does:
 *   the run lands in the Review Inbox as needs_review, and its approval
 *   gate auto-denies instead of suspending.
 *
 * Event triggers (Gmail/Slack/GitHub events in Codex web tasks) are NOT
 * implemented: no such event source exists on the phone. The conditions
 * an automation can express are the WorkManager constraints below
 * (Wi-Fi/unmetered, charging).
 *
 * Drop-in §7 adaptation: this file holds only the scheduler (the
 * drop-in bundled the worker class in the same listing); the pure
 * helpers [workName] and [coerceIntervalMinutes] are public so plain
 * JVM unit tests can cover them without WorkManager.
 */
object AutomationScheduler {

    /** WorkManager enforces a 15-minute minimum periodic interval. */
    const val MIN_INTERVAL_MINUTES = 15L

    /**
     * Unique work name for one automation: `codex_automation_<id>`.
     * Pure — safe to unit-test on the JVM.
     */
    fun workName(automationId: String): String = "codex_automation_$automationId"

    /**
     * Coerces a requested interval up to the platform minimum.
     * Pure — safe to unit-test on the JVM.
     */
    fun coerceIntervalMinutes(requestedMinutes: Long): Long =
        requestedMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)

    /**
     * (Re-)schedules [automation]'s periodic work, or cancels it when
     * the automation is disabled. Re-enqueueing with
     * [ExistingPeriodicWorkPolicy.UPDATE] replaces the previous request
     * (new interval/constraints) without duplicating runs.
     */
    fun sync(context: Context, automation: AutomationEntity) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (!automation.enabled) {
            wm.cancelUniqueWork(workName(automation.id))
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (automation.requireWifi) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresCharging(automation.requireCharging)
            .build()

        // Android WorkManager enforces a 15-minute minimum periodic interval.
        val interval = coerceIntervalMinutes(automation.intervalMinutes)
        val req = PeriodicWorkRequestBuilder<AutomationWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(workDataOf(AutomationWorker.KEY_AUTOMATION_ID to automation.id))
            .build()

        wm.enqueueUniquePeriodicWork(
            workName(automation.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }

    /** Cancels the periodic work for one automation, if any is enqueued. */
    fun cancel(context: Context, automationId: String) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(workName(automationId))
    }
}
