package dev.codex.android.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import dev.codex.android.data.AutomationEntity
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Schedules [AutomationEntity] runs as unique WorkManager periodic work,
 * one unique work name per automation. Disabling (or deleting) an
 * automation cancels its work.
 *
 * Interval schedules run on each eligible tick. Calendar schedules prefer the
 * first due time, then check the wall clock in their saved zone on each tick.
 * The worker claims one due slot in SQLite before invoking the agent. The OS
 * can delay either kind of work while the device or its constraints are unavailable.
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
    suspend fun sync(context: Context, automation: AutomationEntity) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (!automation.enabled) {
            wm.cancelUniqueWork(workName(automation.id)).await()
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (automation.requireWifi) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresCharging(automation.requireCharging)
            .build()

        // Android WorkManager enforces a 15-minute minimum periodic interval.
        val interval = if (automation.calendar == null) coerceIntervalMinutes(automation.intervalMinutes) else MIN_INTERVAL_MINUTES
        val builder = PeriodicWorkRequestBuilder<AutomationWorker>(interval, TimeUnit.MINUTES)
        automation.calendar?.let { calendar ->
            val now = Instant.now()
            builder.setInitialDelay(Duration.between(now, calendar.nextAfter(now)).toMillis(), TimeUnit.MILLISECONDS)
        }
        val req = builder.setConstraints(constraints)
            .setInputData(workDataOf(AutomationWorker.KEY_AUTOMATION_ID to automation.id))
            .build()

        wm.enqueueUniquePeriodicWork(
            workName(automation.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        ).await()
    }

    /** Cancels the periodic work for one automation, if any is enqueued. */
    suspend fun cancel(context: Context, automationId: String) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(workName(automationId)).await()
    }
}
