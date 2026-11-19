package dev.codex.android.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Extra key carrying the [ScheduledTask.id] in the alarm broadcast. */
const val EXTRA_TASK_ID: String = "task_id"

/**
 * Persists scheduled tasks and arms/disarms their [AlarmManager] alarms.
 *
 * All public functions are regular (non-suspend) functions that are safe
 * to call from any thread — store I/O runs on a background scope and
 * never blocks the caller. Nothing here throws: alarm or store failures
 * are swallowed so scheduling can never crash the agent or a receiver.
 *
 * Exact alarms degrade gracefully: when the app may not schedule exact
 * alarms ([AlarmManager.canScheduleExactAlarms] is false on API 31+),
 * the inexact [AlarmManager.setAndAllowWhileIdle] fallback is used.
 * On API < 31 exact alarms need no permission, so exact is used directly.
 */
object TaskScheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Persists [task], then arms an alarm for [ScheduledTask.triggerAtMillis].
     * Safe to call from any thread; never throws.
     */
    fun schedule(context: Context, task: ScheduledTask) {
        val appContext = context.applicationContext ?: context
        scope.launch {
            try {
                ScheduledTaskStore(appContext).save(task)
                armAlarm(appContext, task)
            } catch (e: Exception) {
                // Swallowed — scheduling must never crash the caller.
            }
        }
    }

    /** Cancels the alarm for [taskId] and removes the task from the store. Never throws. */
    fun cancel(context: Context, taskId: String) {
        val appContext = context.applicationContext ?: context
        try {
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val pendingIntent = buildPendingIntent(appContext, taskId)
            if (alarmManager != null) {
                alarmManager.cancel(pendingIntent)
            }
            pendingIntent.cancel()
        } catch (e: Exception) {
            // Swallowed — cancelling must never crash the caller.
        }
        scope.launch {
            try {
                ScheduledTaskStore(appContext).remove(taskId)
            } catch (e: Exception) {
                // Ignored.
            }
        }
    }

    /**
     * Re-arms the alarm for every enabled task whose trigger is in the
     * future. This is what the boot receiver calls after a reboot.
     * Safe to call from any thread; never throws.
     */
    fun rescheduleAll(context: Context) {
        val appContext = context.applicationContext ?: context
        scope.launch {
            try {
                rescheduleAllNow(appContext)
            } catch (e: Exception) {
                // Ignored.
            }
        }
    }

    /**
     * Suspend version of [rescheduleAll] for callers that already run
     * inside a coroutine (e.g. [ResurrectionWorker]), so the re-arming
     * is guaranteed complete before the caller continues. Never throws.
     */
    suspend fun rescheduleAllNow(context: Context) {
        val appContext = context.applicationContext ?: context
        try {
            val now = System.currentTimeMillis()
            val tasks = ScheduledTaskStore(appContext).all()
            for (task in tasks) {
                if (task.enabled && task.triggerAtMillis > now) {
                    armAlarm(appContext, task)
                }
            }
        } catch (e: Exception) {
            // Ignored.
        }
    }

    /** Arms a single alarm for [task]; exact when permitted, inexact otherwise. */
    private fun armAlarm(context: Context, task: ScheduledTask) {
        try {
            val alarmManager =
                context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pendingIntent = buildPendingIntent(context, task.id)
            val exactAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }
            if (exactAllowed) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    task.triggerAtMillis,
                    pendingIntent,
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    task.triggerAtMillis,
                    pendingIntent,
                )
            }
        } catch (e: Exception) {
            // Swallowed — a failed arm must never crash the caller.
        }
    }

    private fun buildPendingIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, TaskReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, taskId)
        }
        // One distinct PendingIntent per task id (stable request code),
        // so cancelling/re-arming one task never touches another.
        // FLAG_IMMUTABLE is required on API 31+; the intent carries no
        // data the receiver needs to mutate.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, taskId.hashCode(), intent, flags)
    }
}
