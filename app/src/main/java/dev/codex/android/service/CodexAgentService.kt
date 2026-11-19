package dev.codex.android.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Phase 4 — foreground service that keeps the agent runtime alive.
 *
 * This is the survival layer, not the agent itself: it does NOT run the
 * agent loop in this phase (the loop runs from the Activity). Its job is
 * to hold a foreground slot with a persistent low-priority notification so
 * the system treats Codex as user-visible work and is far less likely to
 * kill it on low-end phones, and to come back via [START_STICKY] when it
 * is killed anyway.
 *
 * Safety rules (do not weaken them):
 * - Never log user data or task contents — only lifecycle events and
 *   exception class names.
 * - Every public entry point degrades gracefully when permissions are
 *   missing or the system refuses the start: it logs and returns, never
 *   crashes. [updateStatus] is explicitly safe to call when the service
 *   is not running.
 * - No network, no API keys, no secrets anywhere in this file.
 */
class CodexAgentService : Service() {

    companion object {
        private const val TAG = "CodexAgentService"

        /** Notification id of the persistent running notification. */
        const val RUNNING_NOTIFICATION_ID = 1001

        /** Default status text shown in the persistent notification. */
        const val DEFAULT_STATUS = "Codex is running"

        /**
         * The currently running service instance, or null when it is not
         * running. Volatile so [updateStatus] sees start/stop immediately
         * from any thread.
         */
        @Volatile
        var instance: CodexAgentService? = null
            private set

        /**
         * Latest status text. Kept at companion level so [updateStatus]
         * can store it even when the service is not running — the next
         * [onStartCommand] then shows it instead of the default.
         */
        @Volatile
        var currentStatus: String = DEFAULT_STATUS
            private set

        /**
         * Starts the agent service as a foreground service. Safe to call
         * repeatedly. Never throws — a refused start (e.g. background
         * restrictions) is logged and swallowed.
         */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context.applicationContext, CodexAgentService::class.java)
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }.onFailure { e ->
                Log.w(TAG, "Failed to start agent service: ${e.javaClass.simpleName}")
            }
        }

        /** Stops the agent service. No-op when it is not running. Never throws. */
        fun stop(context: Context) {
            runCatching {
                val intent = Intent(context.applicationContext, CodexAgentService::class.java)
                context.applicationContext.stopService(intent)
            }.onFailure { e ->
                Log.w(TAG, "Failed to stop agent service: ${e.javaClass.simpleName}")
            }
        }

        /**
         * Re-posts the running notification with new status [text].
         * Safe to call when the service is not running: the text is stored
         * in [currentStatus] and simply shown on the next start.
         * Never throws.
         */
        fun updateStatus(context: Context, text: String) {
            currentStatus = text
            val running = instance ?: return
            runCatching {
                running.startForeground(
                    RUNNING_NOTIFICATION_ID,
                    NotificationHelper.runningNotification(running, text),
                )
            }.onFailure { e ->
                Log.w(TAG, "Failed to update agent notification: ${e.javaClass.simpleName}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Promote to foreground IMMEDIATELY, in onCreate rather than
        // waiting for onStartCommand: on a slow/low-end device (or a
        // slow emulator) the system can deliver onStartCommand late,
        // and if startForeground() has not run within the foreground-
        // service timeout the system kills the app with
        // ForegroundServiceDidNotStartInTimeException. Promoting here
        // satisfies the timeout as soon as the service exists.
        NotificationHelper.createChannel(this)
        runCatching {
            startForeground(
                RUNNING_NOTIFICATION_ID,
                NotificationHelper.runningNotification(this, currentStatus),
            )
        }.onFailure { e ->
            // A foreground service that cannot promote itself must not
            // linger as a background service: stop cleanly.
            Log.w(TAG, "Failed to enter foreground in onCreate: ${e.javaClass.simpleName}")
            stopSelf()
        }
        Log.i(TAG, "Agent service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.createChannel(this)
        runCatching {
            startForeground(
                RUNNING_NOTIFICATION_ID,
                NotificationHelper.runningNotification(this, currentStatus),
            )
        }.onFailure { e ->
            // A foreground service that cannot promote itself must not linger
            // as a background service: stop cleanly instead of crashing.
            Log.w(TAG, "Failed to enter foreground: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.i(TAG, "Agent service started in foreground")
        // Restart if the system kills us: the boot receiver and the
        // Activity re-attach state on the next start.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }.onFailure { e ->
            Log.w(TAG, "Failed to stop foreground: ${e.javaClass.simpleName}")
        }
        Log.i(TAG, "Agent service destroyed")
        super.onDestroy()
    }
}
