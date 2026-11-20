package dev.codex.android.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.TimelineMessage
import dev.codex.android.security.ExecutionMode
import dev.codex.android.security.PermissionPolicy
import dev.codex.android.service.CodexAgentService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PendingApproval(
    val approvalId: String,
    val threadId: String,
    val toolName: String,
    val summary: String,
    val payloadPreview: String,
)

data class ThreadRuntimeState(
    val threadId: String,
    val isRunning: Boolean = false,
    val stepIndex: Int = 0,
    val maxSteps: Int = 25,
    val statusText: String = "",
    val streamingAssistantText: String = "",
    val pendingApproval: PendingApproval? = null,
)

/**
 * Process-singleton runtime hosted by CodexAgentService so tasks survive
 * Activity backgrounding, rotation, and app-switching.
 *
 * Drop-in §6 of the v0.8.0 backend spec, verbatim except for one
 * adaptation (see the CancellationException branch below): a cancellation
 * that carries a reason surfaces that reason in the timeline instead of
 * the generic "Stopped by user." text.
 */
internal fun requiresInAppReview(toolName: String): Boolean =
    toolName in setOf("workspace_write", "shell_exec", "github_publish", "github_build")

object AgentRuntime {

    private const val APPROVAL_CHANNEL_ID = "codex_approvals"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val policy = PermissionPolicy()

    private val _threads = MutableStateFlow<Map<String, ThreadRuntimeState>>(emptyMap())
    val threads: StateFlow<Map<String, ThreadRuntimeState>> = _threads.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val steerQueues = ConcurrentHashMap<String, Channel<String>>()
    private val approvalWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /**
     * Launches a thread turn. [runner] bridges into your existing AgentLoop /
     * OkHttpOpenAiClient while receiving callbacks for streaming, steering, and approvals.
     */
    fun startTurn(
        context: Context,
        threadId: String,
        goal: String,
        mode: ExecutionMode,
        maxSteps: Int = 25,
        runner: suspend TurnContext.() -> String,
    ): Boolean {
        // ADAPTED vs the verbatim drop-in: the guard and the job
        // registration are under one synchronized block. The drop-in
        // checked activeJobs and registered the job in two separate
        // steps, so two concurrent startTurn calls for the same thread
        // could both pass the check and start two runs (reproduced in a
        // JVM harness: two near-simultaneous sends both returned true).
        // The block is short and non-blocking (launch does not block),
        // and the job itself never takes this lock, so it cannot deadlock.
        synchronized(activeJobs) {
            if (activeJobs.containsKey(threadId)) return false
            val appContext = context.applicationContext
            val db = CodexDatabase.getInstance(appContext)
            val steerChannel = Channel<String>(Channel.UNLIMITED)
            steerQueues[threadId] = steerChannel

            // Synchronous guard prevents double-tap duplicate runs.
            updateThread(threadId) {
                it.copy(
                    isRunning = true,
                    stepIndex = 0,
                    maxSteps = maxSteps,
                    statusText = "Starting…",
                    streamingAssistantText = "",
                    pendingApproval = null,
                )
            }
            CodexAgentService.start(appContext)
            syncServiceNotification(appContext)

            val job = scope.launch {
                db.updateThreadState(threadId, status = "running")
                db.appendMessage(
                    TimelineMessage(threadId = threadId, role = "user", kind = "text", content = goal)
                )
                val turnCtx = TurnContext(
                    appContext = appContext,
                    threadId = threadId,
                    mode = mode,
                    maxSteps = maxSteps,
                    steerChannel = steerChannel,
                )
                try {
                    val finalAnswer = turnCtx.runner()
                    if (finalAnswer.isNotBlank()) {
                        db.appendMessage(
                            TimelineMessage(
                                threadId = threadId,
                                role = "assistant",
                                kind = "text",
                                content = finalAnswer,
                            )
                        )
                    }
                    db.updateThreadState(threadId, status = "idle", unread = true)
                } catch (e: CancellationException) {
                    // ADAPTED vs the verbatim drop-in (which hard-coded
                    // "Stopped by user."): a cancellation that carries the
                    // step-budget reason surfaces that reason instead. The
                    // guard is deliberately narrow — a plain user stop
                    // (AgentRuntime.stop) cancels with no reason (or the
                    // generic "Job was cancelled"), so the default text is
                    // preserved for it.
                    // The coroutine is cancelled here: plain suspend DB
                    // calls would throw immediately and this cleanup would
                    // never run (no "Stopped" record, thread stuck on
                    // "running"). NonCancellable lets the record + idle
                    // status land; the cancellation is rethrown after.
                    withContext(NonCancellable) {
                        db.appendMessage(
                            TimelineMessage(
                                threadId = threadId,
                                role = "system",
                                kind = "text",
                                content = e.message
                                    ?.takeIf { it.startsWith("Stopped: reached max step budget") }
                                    ?: "Stopped by user.",
                            )
                        )
                        db.updateThreadState(threadId, status = "idle")
                    }
                    throw e
                } catch (e: Exception) {
                    db.appendMessage(
                        TimelineMessage(
                            threadId = threadId,
                            role = "assistant",
                            kind = "error",
                            content = "Error: ${e.message ?: e.javaClass.simpleName}",
                        )
                    )
                    db.updateThreadState(threadId, status = "error", unread = true)
                } finally {
                    steerQueues.remove(threadId)?.close()
                    clearThreadApproval(appContext, threadId)
                    updateThread(threadId) {
                        it.copy(isRunning = false, statusText = "", streamingAssistantText = "", pendingApproval = null)
                    }
                    syncServiceNotification(appContext)
                    // The remove + isEmpty + stop must be atomic under the
                    // same monitor startTurn registers under: otherwise a
                    // turn that registers between the check and the stop
                    // gets its foreground service stopped mid-run. Removal
                    // happens last on purpose — until it runs, a new
                    // startTurn for this thread correctly sees the old job
                    // still registered and refuses, instead of starting
                    // while this finally is still mutating thread state.
                    synchronized(activeJobs) {
                        activeJobs.remove(threadId)
                        if (activeJobs.isEmpty()) {
                            CodexAgentService.stop(appContext)
                        }
                    }
                }
            }
            activeJobs[threadId] = job
            return true
        }
    }

    /** Injects a mid-turn user instruction without cancelling the active run. */
    fun steer(threadId: String, instruction: String): Boolean {
        val queue = steerQueues[threadId] ?: return false
        return queue.trySend(instruction.trim()).isSuccess
    }

    /** Immediately cancels an active thread run. */
    fun stop(threadId: String) {
        activeJobs[threadId]?.cancel()
    }

    /** Resolves an approval from either the in-app ApprovalCard or the notification shade. */
    fun answerApproval(
        context: Context,
        approvalId: String,
        approved: Boolean,
        allowForSession: Boolean = false,
    ) {
        val pending = _threads.value.values.firstOrNull { it.pendingApproval?.approvalId == approvalId }
            ?.pendingApproval
        if (pending != null) {
            if (approved && allowForSession) {
                policy.grantForSession(pending.threadId, pending.toolName)
            }
            cancelApprovalNotification(context.applicationContext, approvalId)
            updateThread(pending.threadId) { it.copy(pendingApproval = null) }
        }
        approvalWaiters.remove(approvalId)?.complete(approved)
    }

    private fun updateThread(threadId: String, block: (ThreadRuntimeState) -> ThreadRuntimeState) {
        _threads.update { current ->
            val existing = current[threadId] ?: ThreadRuntimeState(threadId)
            current + (threadId to block(existing))
        }
    }

    private fun syncServiceNotification(context: Context) {
        val active = _threads.value.values.filter { it.isRunning }
        val text = when (active.size) {
            0 -> CodexAgentService.DEFAULT_STATUS
            1 -> active.first().statusText.ifBlank { "Codex is running" }
            else -> "Codex · ${active.size} active threads"
        }
        CodexAgentService.updateStatus(context, text)
    }

    private fun clearThreadApproval(context: Context, threadId: String) {
        val approvalId = _threads.value[threadId]?.pendingApproval?.approvalId ?: return
        cancelApprovalNotification(context, approvalId)
        approvalWaiters.remove(approvalId)?.complete(false)
    }

    private fun postApprovalNotification(context: Context, approval: PendingApproval) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            APPROVAL_CHANNEL_ID,
            "Codex approvals",
            NotificationManager.IMPORTANCE_HIGH,
        )
        nm.createNotificationChannel(channel)

        fun actionIntent(approved: Boolean, reqCode: Int): PendingIntent {
            val intent = Intent(context, ApprovalActionReceiver::class.java).apply {
                action = if (approved) ApprovalActionReceiver.ACTION_APPROVE else ApprovalActionReceiver.ACTION_DENY
                putExtra(ApprovalActionReceiver.EXTRA_APPROVAL_ID, approval.approvalId)
            }
            return PendingIntent.getBroadcast(
                context,
                reqCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notifId = approval.approvalId.hashCode()
        val notification = NotificationCompat.Builder(context, APPROVAL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // replace with your app icon
            .setContentTitle("Approval needed: ${approval.toolName}")
            .setContentText(approval.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${approval.summary}\n${approval.payloadPreview}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(0, "Deny", actionIntent(false, notifId + 2))
        if (!requiresInAppReview(approval.toolName)) {
            notification.addAction(0, "Approve", actionIntent(true, notifId + 1))
        }
        nm.notify(notifId, notification.build())
    }

    private fun cancelApprovalNotification(context: Context, approvalId: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(approvalId.hashCode())
    }

    class TurnContext internal constructor(
        private val appContext: Context,
        val threadId: String,
        val mode: ExecutionMode,
        val maxSteps: Int,
        private val steerChannel: Channel<String>,
    ) {
        private var steps = 0

        /** Call at the start of every LLM/tool step to enforce the max-step budget. */
        fun nextStepOrThrow(label: String) {
            steps++
            if (steps > maxSteps) {
                throw IllegalStateException("Stopped: reached max step budget ($maxSteps)")
            }
            updateThread(threadId) { it.copy(stepIndex = steps, statusText = "Step $steps/$maxSteps: $label") }
            syncServiceNotification(appContext)
        }

        /** Interactive progress follows model requests, not tool narration or text chunks. */
        fun reportModelStep(step: Int, finishing: Boolean) {
            updateThread(threadId) { it.copy(stepIndex = step, statusText = if (finishing) "Preparing progress summary…" else "Step $step/$maxSteps · Working…") }
            syncServiceNotification(appContext)
        }

        fun clearAssistantDelta() {
            updateThread(threadId) { it.copy(streamingAssistantText = "") }
        }

        /** Append streamed token delta to the live assistant bubble. */
        fun emitAssistantDelta(delta: String) {
            updateThread(threadId) { it.copy(streamingAssistantText = it.streamingAssistantText + delta) }
        }

        /** Drains any mid-turn user steering messages sent while the loop is running. */
        fun drainSteeringMessages(): List<String> {
            val out = mutableListOf<String>()
            while (true) {
                val msg = steerChannel.tryReceive().getOrNull() ?: break
                if (msg.isNotBlank()) out += msg
            }
            return out
        }

        /** Suspends until user taps Approve/Deny in-app OR from the notification shade. */
        suspend fun requestApproval(
            toolName: String,
            summary: String,
            payloadPreview: String,
        ): Boolean {
            val id = "apr_${UUID.randomUUID().toString().take(8)}"
            val pending = PendingApproval(id, threadId, toolName, summary, payloadPreview)
            val deferred = CompletableDeferred<Boolean>()
            approvalWaiters[id] = deferred
            updateThread(threadId) { it.copy(pendingApproval = pending, statusText = "Waiting approval: $toolName") }
            CodexDatabase.getInstance(appContext).updateThreadState(threadId, status = "waiting_approval")
            postApprovalNotification(appContext, pending)
            syncServiceNotification(appContext)
            return try {
                deferred.await()
            } finally {
                CodexDatabase.getInstance(appContext).updateThreadState(threadId, status = "running")
            }
        }
    }
}

/** Handles Approve / Deny taps directly from Android's notification shade. */
class ApprovalActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_APPROVE = "dev.codex.android.ACTION_APPROVE"
        const val ACTION_DENY = "dev.codex.android.ACTION_DENY"
        const val EXTRA_APPROVAL_ID = "approval_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID) ?: return
        val approved = intent.action == ACTION_APPROVE
        AgentRuntime.answerApproval(context, approvalId, approved)
    }
}
