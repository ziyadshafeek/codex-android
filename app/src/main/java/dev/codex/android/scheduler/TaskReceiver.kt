package dev.codex.android.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.codex.android.service.CodexAgentService
import dev.codex.android.service.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires when a scheduled task's alarm goes off. One-shot: if the task
 * still exists and is enabled, it posts a notification, starts the
 * agent service, and removes the task from the store.
 *
 * Never crashes — every step is wrapped in try/catch, and the async
 * work runs under [goAsync] so the receiver stays alive until it
 * finishes (or the system reclaims it).
 */
class TaskReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context?.applicationContext ?: context ?: return
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID) ?: return
        val pendingResult = goAsync()
        scope.launch {
            try {
                val store = ScheduledTaskStore(ctx)
                val task = store.all().firstOrNull { it.id == taskId } ?: return@launch
                if (!task.enabled) return@launch
                NotificationHelper.showTaskComplete(ctx, task.title, task.prompt)
                CodexAgentService.start(ctx)
                store.remove(taskId)
            } catch (e: Exception) {
                // Swallowed — a receiver must never crash.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
