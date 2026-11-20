package dev.codex.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.codex.android.R

/**
 * Phase 4 — notifications for the agent survival layer.
 *
 * One channel, [CHANNEL_ID], at [NotificationManager.IMPORTANCE_LOW]:
 * the persistent foreground-service notification must show status without
 * buzzing the user, and one-shot task-complete pings share the same quiet
 * channel.
 *
 * Safety rules (do not weaken them):
 * - Never log user data or task contents. Only the notification channel
 *   and posting failures (as exception class names) are logged.
 * - Every call degrades gracefully when notifications are not permitted:
 *   posting is a caught no-op, never a crash.
 * - No network, no API keys, no secrets anywhere in this file.
 */
object NotificationHelper {

    /** Notification channel for all Codex agent notifications. */
    const val CHANNEL_ID = "codex_agent"

    /** Human-readable channel name shown in system Settings. */
    const val CHANNEL_NAME = "Codex agent"

    /** Id for one-shot "task complete" notifications. Distinct from the running id. */
    const val TASK_COMPLETE_NOTIFICATION_ID = 1002

    private const val TAG = "CodexNotify"

    /**
     * Creates the [CHANNEL_ID] channel at [NotificationManager.IMPORTANCE_LOW].
     * Safe to call repeatedly — creating an existing channel is a no-op.
     * Never throws.
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Codex agent status and completed-task alerts"
                // A persistent status notification must not buzz the user.
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }.onFailure { e ->
            Log.w(TAG, "Failed to create notification channel: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Builds the persistent foreground-service notification: title "Codex",
     * content [text], ongoing so the user cannot swipe it away while the
     * agent service is running.
     */
    fun runningNotification(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            // A plain drawable, NOT the adaptive launcher icon:
            // R.mipmap.ic_launcher is an adaptive icon and the system
            // rejects it as a notification small icon ("Bad notification
            // for startForeground", CI run 44640870701).
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Codex")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /**
     * Posts a one-shot notification for a completed scheduled task.
     * Auto-cancels when tapped. No-op (caught) when notifications are not
     * permitted or posting fails for any reason. Never throws.
     */
    fun showTaskComplete(context: Context, title: String, text: String) {
        runCatching {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                // See runningNotification: plain drawable, not the
                // adaptive launcher icon.
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            NotificationManagerCompat.from(context)
                .notify(TASK_COMPLETE_NOTIFICATION_ID, notification)
        }.onFailure { e ->
            // Posting can throw (e.g. SecurityException when the
            // POST_NOTIFICATIONS permission was revoked): swallow it —
            // a missed ping must never break the agent loop.
            Log.w(TAG, "Failed to post task-complete notification: ${e.javaClass.simpleName}")
        }
    }
}
