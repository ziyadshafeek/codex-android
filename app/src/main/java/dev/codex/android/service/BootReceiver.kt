package dev.codex.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.codex.android.agent.RunStateStore
import dev.codex.android.agent.RunStatus
import dev.codex.android.scheduler.TaskScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Phase 4 — resurrection after reboot.
 *
 * On [Intent.ACTION_BOOT_COMPLETED] this receiver (a) re-arms every pending
 * scheduled task via [TaskScheduler.rescheduleAll], and (b) restarts the
 * agent foreground service when a run was in progress at shutdown
 * ([RunStateStore] reports a saved state whose status is [RunStatus.RUNNING]).
 *
 * A boot receiver must never crash: everything is wrapped in try/catch,
 * and a failure in one step never blocks the other. Other broadcast
 * actions are ignored.
 *
 * [RunStateStore.load] is suspend, so step (b) runs in a coroutine under
 * [goAsync]: the broadcast stays alive until the state check finishes,
 * instead of the receiver returning early and the process being killed
 * before the resume decision is made.
 *
 * Safety rules (do not weaken them):
 * - Never log user data, task contents, or run state — only the action
 *   handled and exception class names.
 * - No network, no API keys, no secrets anywhere in this file.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CodexBoot"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext

        // (a) Re-arm pending scheduled tasks. Must not block (b) on failure.
        runCatching {
            TaskScheduler.rescheduleAll(appContext)
        }.onFailure { e ->
            Log.w(TAG, "Failed to reschedule tasks on boot: ${e.javaClass.simpleName}")
        }

        // (b) Resume the agent service only if a run was in progress.
        // RunStateStore.load() is suspend, so hold the broadcast open
        // with goAsync() until the check completes.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val savedState = RunStateStore(appContext).load()
                if (savedState?.status == RunStatus.RUNNING) {
                    CodexAgentService.start(appContext)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resume agent service on boot: ${e.javaClass.simpleName}")
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }
}
