package dev.codex.android.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.codex.android.agent.RunStateStore
import dev.codex.android.agent.RunStatus
import dev.codex.android.service.CodexAgentService
import dev.codex.android.util.DeviceProfile
import dev.codex.android.util.PerformancePolicy
import java.util.concurrent.TimeUnit

/**
 * Periodic resurrection check: re-arms every future scheduled-task alarm
 * (alarms do not survive a reboot or a force-stop on their own) and
 * restarts the agent service if a run was still RUNNING when the
 * process died. Runs on an adaptive interval — every 12 hours on
 * low-RAM devices, every 6 hours otherwise (see [PerformancePolicy]);
 * also enqueued at app start / boot by the survival layer. Never
 * throws — any failure just yields success and the next periodic run
 * retries.
 */
class ResurrectionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            TaskScheduler.rescheduleAllNow(applicationContext)
            val state = RunStateStore(applicationContext).load()
            if (state?.status == RunStatus.RUNNING) {
                CodexAgentService.start(applicationContext)
            }
            Result.success()
        } catch (e: Exception) {
            // Never throws / never retries aggressively — the next
            // periodic run picks it up again.
            Result.success()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME: String = "codex_resurrection"
        const val TAG: String = "codex_resurrection"

        /** Enqueues the unique adaptive-interval periodic resurrection work (kept if already enqueued). */
        fun enqueue(context: Context) {
            try {
                val deviceClass = DeviceProfile.detect(context).deviceClass
                val intervalHours = PerformancePolicy.resurrectionIntervalHours(deviceClass)
                val request = PeriodicWorkRequestBuilder<ResurrectionWorker>(intervalHours, TimeUnit.HOURS)
                    .addTag(TAG)
                    .build()
                WorkManager.getInstance(context.applicationContext ?: context)
                    .enqueueUniquePeriodicWork(
                        UNIQUE_WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        request,
                    )
            } catch (e: Exception) {
                // Swallowed — enqueue failure must never crash the caller.
            }
        }
    }
}
