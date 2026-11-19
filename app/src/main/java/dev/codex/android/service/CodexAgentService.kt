package dev.codex.android.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
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
 *
 * Start/stop race (v0.7.2): [start] hands the start to the system via
 * [ContextCompat.startForegroundService], which creates the ServiceRecord
 * with fgRequired=true and delivers creation to the main thread
 * asynchronously. A [stop] landing before the main thread has run
 * [startForeground] used to call `stopService()` immediately, and the
 * system then killed the whole process with
 * ForegroundServiceDidNotStartInTimeException. [stop] therefore never
 * calls `stopService()` while a start is still in flight: it records
 * [stopRequested] and the service stops itself right after its
 * foreground promotion succeeds.
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
         * True once [startForeground] has succeeded for the current run,
         * false otherwise. Volatile so tests and [stop] can poll it from
         * any thread. Cleared in [onDestroy].
         */
        @Volatile
        var isForeground: Boolean = false
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
         * True from [start] until [startForeground] succeeds (or the
         * start is refused). While true, [stop] must NOT call
         * `stopService()`: the ServiceRecord is fgRequired and the
         * system would kill the process with
         * ForegroundServiceDidNotStartInTimeException if the service
         * were stopped before promoting itself.
         */
        @Volatile
        private var startPending: Boolean = false

        /**
         * True when [stop] was called while [startPending] — i.e. a stop
         * was requested before foreground promotion completed. Consumed
         * by [promoteToForeground] right after promotion succeeds: the
         * service then stops itself instead of lingering.
         */
        @Volatile
        private var stopRequested: Boolean = false

        /**
         * Starts the agent service as a foreground service. Safe to call
         * repeatedly. Never throws — a refused start (e.g. background
         * restrictions) is logged and swallowed.
         */
        fun start(context: Context) {
            // A repeated start while one is already in flight just
            // re-affirms the pending start; a stop requested earlier is
            // superseded by the explicit new start.
            stopRequested = false
            startPending = true
            runCatching {
                val intent = Intent(context.applicationContext, CodexAgentService::class.java)
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }.onFailure { e ->
                // No service will be created, so nothing is pending any
                // more — a later stop() must not be deferred forever.
                startPending = false
                Log.w(TAG, "Failed to start agent service: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        /**
         * Stops the agent service. No-op when it is not running. Never
         * throws.
         *
         * If a start is still in flight ([startPending] and foreground
         * promotion has not succeeded yet), `stopService()` is NOT
         * called: it would race the pending promotion and crash the
         * process. The stop is recorded in [stopRequested] and the
         * service stops itself immediately after promoting.
         */
        fun stop(context: Context) {
            if (startPending && !isForeground) {
                stopRequested = true
                Log.i(TAG, "Stop requested while start pending; deferring until foreground promotion")
                return
            }
            runCatching {
                val intent = Intent(context.applicationContext, CodexAgentService::class.java)
                context.applicationContext.stopService(intent)
            }.onFailure { e ->
                Log.w(TAG, "Failed to stop agent service: ${e.javaClass.simpleName}: ${e.message}")
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
                Log.w(TAG, "Failed to update agent notification: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Promotes this service to the foreground with the `dataSync` type
     * (the type the manifest declares) on API 34+, and no explicit type
     * below it. On success marks [isForeground], clears [startPending],
     * and — if a stop was requested while the start was in flight —
     * stops the service immediately instead of lingering. Returns true
     * when promotion succeeded.
     */
    private fun promoteToForeground(): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        return runCatching {
            ServiceCompat.startForeground(
                this,
                RUNNING_NOTIFICATION_ID,
                NotificationHelper.runningNotification(this, currentStatus),
                type,
            )
            isForeground = true
            startPending = false
            if (stopRequested) {
                stopRequested = false
                Log.i(TAG, "Stop was requested while start was pending; stopping after foreground promotion")
                stopSelf()
            }
            true
        }.onFailure { e ->
            // No promotion will happen, so the start is no longer
            // pending — a later stop() must not be deferred forever.
            startPending = false
            Log.w(TAG, "Failed to enter foreground: ${e.javaClass.simpleName}: ${e.message}")
            false
        }.getOrDefault(false)
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
        if (!promoteToForeground()) {
            // A foreground service that cannot promote itself must not
            // linger as a background service: stop cleanly.
            stopSelf()
        }
        Log.i(TAG, "Agent service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.createChannel(this)
        if (!promoteToForeground()) {
            // A foreground service that cannot promote itself must not linger
            // as a background service: stop cleanly instead of crashing.
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
        isForeground = false
        startPending = false
        stopRequested = false
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }.onFailure { e ->
            Log.w(TAG, "Failed to stop foreground: ${e.javaClass.simpleName}: ${e.message}")
        }
        Log.i(TAG, "Agent service destroyed")
        super.onDestroy()
    }
}
