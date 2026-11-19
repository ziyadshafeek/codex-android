package dev.codex.android.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.codex.android.service.AutomationWorker
import dev.codex.android.service.CodexAgentService
import dev.codex.android.service.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires when a scheduled task's alarm goes off. One-shot: if the task
 * still exists and is enabled, it posts a notification, starts the
 * agent service, enqueues [AutomationWorker] with the task's prompt so
 * the prompt is actually executed (before this, the task only notified
 * and never ran), and removes the task from the store.
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
                // Execute the prompt: a one-shot AutomationWorker run on
                // a fresh thread, landed in the Review Inbox. Without
                // this the task notified and vanished without doing any
                // work (its tool doc promises the prompt is run).
                WorkManager.getInstance(ctx).enqueueUniqueWork(
                    "scheduled-task-$taskId",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<AutomationWorker>()
                        .setInputData(
                            workDataOf(
                                AutomationWorker.KEY_ONE_SHOT_PROMPT to task.prompt,
                                AutomationWorker.KEY_ONE_SHOT_TITLE to task.title,
                            ),
                        )
                        .build(),
                )
                store.remove(taskId)
            } catch (e: Exception) {
                // Swallowed — a receiver must never crash.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
