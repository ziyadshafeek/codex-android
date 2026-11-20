package dev.codex.android.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Instant
import dev.codex.android.memory.MemorySearchTool
import dev.codex.android.memory.PersonalMemory
import dev.codex.android.agent.AgentEvent
import dev.codex.android.agent.AgentLoop
import dev.codex.android.agent.AgentRuntime
import dev.codex.android.agent.PlanStore
import dev.codex.android.agent.channelEventCollector
import dev.codex.android.api.OkHttpOpenAiClient
import dev.codex.android.data.AutomationEntity
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.KeyStore
import dev.codex.android.data.TimelineMessage
import dev.codex.android.security.ApprovalGate
import dev.codex.android.security.ExecutionMode
import dev.codex.android.security.PendingAction
import dev.codex.android.security.PolicyDecision
import dev.codex.android.security.ToolRisk
import dev.codex.android.skills.SkillStore
import dev.codex.android.tools.Tool
import dev.codex.android.tools.ToolRegistry
import dev.codex.android.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/**
 * Runs one scheduled automation end-to-end (drop-in §7, WIRED — the
 * drop-in's AgentLoop call was only a comment).
 *
 * Flow:
 * 1. Load the automation; skip cleanly (success, no run) when it is
 *    missing, disabled, or no key/base URL is configured.
 * 2. Create a dedicated thread (`Auto: <name>`, AUTO mode) in the
 *    automation's workspace.
 * 3. Call [AgentRuntime.startTurn] with the automation's prompt and a
 *    runner that drives the existing [AgentLoop] (see
 *    [runAutomationTurn] — the same TurnContext bridge pattern as
 *    ChatViewModel.runAgentTurn, re-implemented here because that bridge
 *    is private to ChatViewModel and this file must not modify it).
 * 4. Wait for the runtime job to finish, then mark the thread
 *    `needs_review` + unread for the Review Inbox and record
 *    lastRunAt/lastStatus on the automation.
 *
 * The assistant summary is appended to the thread by
 * [AgentRuntime.startTurn]'s own job (exactly as for interactive runs),
 * so this worker does NOT append it a second time — it only flips the
 * thread to `needs_review` once the runtime has finished writing.
 *
 * lastStatus values: `ok`, `skipped_no_key`, `skipped_already_running`,
 * `error:timeout`, `error:run_failed` (the runner threw — the runtime
 * already recorded the error in the timeline), `error:model` (the loop
 * finished but its answer reports it could not finish).
 *
 * Safety: the run is AUTO mode, every tool call is policy-gated via
 * [AgentRuntime.policy], and approvals are auto-denied, never suspended
 * on (see [AutomationApprovalGate]) — nobody is there to tap Approve
 * in a background worker, and suspending would hang the run until
 * WorkManager kills the worker. BYOK only: the key/base URL come from
 * the user's own [KeyStore]; nothing here ships or logs a key.
 */
class AutomationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_AUTOMATION_ID = "automation_id"

        /**
         * One-shot prompt for a fired scheduled task (see TaskReceiver):
         * when set, the worker runs this prompt once instead of loading
         * a stored automation. [KEY_ONE_SHOT_TITLE] is the display title.
         */
        const val KEY_ONE_SHOT_PROMPT = "one_shot_prompt"
        const val KEY_ONE_SHOT_TITLE = "one_shot_title"

        /**
         * WorkManager stops a worker after ~10 minutes. Stop waiting
         * (and stop the run) just before that, so the timeout is recorded
         * cleanly instead of the worker being killed mid-run.
         */
        private const val WORK_TIMEOUT_MS = 9 * 60 * 1000L

        /** Step budget for the run; matches AgentRuntime.startTurn's default. */
        private const val MAX_STEPS = 25
    }

    override suspend fun doWork(): Result {
        // One-shot path (fired scheduled task): run the prompt once on a
        // fresh thread, with no stored-automation bookkeeping.
        val oneShotPrompt = inputData.getString(KEY_ONE_SHOT_PROMPT)
        if (oneShotPrompt != null) {
            return runOneShot(
                prompt = oneShotPrompt,
                title = inputData.getString(KEY_ONE_SHOT_TITLE) ?: "Scheduled task",
            )
        }
        val id = inputData.getString(KEY_AUTOMATION_ID) ?: return Result.failure()
        val db = CodexDatabase.getInstance(applicationContext)
        val automation = db.getAutomation(id) ?: return Result.success()
        if (!automation.enabled) return Result.success()
        automation.calendar?.let { schedule ->
            val slot = schedule.dueAt(Instant.now(), Instant.ofEpochMilli(automation.scheduleStartedAt),
                automation.scheduleClaimedAt?.let(Instant::ofEpochMilli)) ?: return Result.success()
            if (!db.claimCalendarSlot(automation, slot.toEpochMilli())) return Result.success()
        }

        val keyStore = KeyStore(applicationContext)
        val baseUrl = keyStore.getBaseUrl()
        val apiKey = keyStore.getApiKey()
        if (!keyStore.hasKey() || baseUrl.isBlank() || apiKey.isNullOrBlank()) {
            db.finishAutomation(automation.id, "skipped_no_key")
            return Result.success()
        }

        // Dedicated Review Inbox thread for this run. The runtime appends
        // the user prompt itself inside startTurn — do NOT append it here
        // or the timeline would show the prompt twice.
        val thread = db.createThread(
            workspaceId = automation.workspaceId,
            title = "Auto: ${automation.name}",
            mode = ExecutionMode.AUTO.name,
        )
        val started = AgentRuntime.startTurn(
            applicationContext,
            thread.id,
            automation.prompt,
            ExecutionMode.AUTO,
            MAX_STEPS,
        ) {
            runAutomationTurn(this, thread.id, automation.prompt, baseUrl, apiKey, keyStore.getModel())
        }
        if (!started) {
            // A run is already active for this thread (unique periodic
            // work normally prevents this; a manual run could collide).
            // Skip this tick rather than double-running.
            db.finishAutomation(automation.id, "skipped_already_running")
            return Result.success()
        }

        return try {
            // startTurn sets isRunning = true synchronously before it
            // returns, and the runtime clears it in its job's finally —
            // after it has appended the final answer and set the thread's
            // DB status — so observing it go false means the run (and all
            // its DB writes) is complete.
            withTimeout(WORK_TIMEOUT_MS) {
                AgentRuntime.threads.first { states ->
                    states[thread.id]?.isRunning == false
                }
            }
            finishRun(db, automation, thread.id)
        } catch (e: TimeoutCancellationException) {
            AgentRuntime.stop(thread.id)
            db.updateThreadState(thread.id, status = "error", unread = true)
            db.finishAutomation(automation.id, "error:timeout")
            if (automation.calendar == null) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            // WorkManager stopped the worker: the runtime owns its own
            // scope, so stop the run too — otherwise it would keep
            // running (and spending the user's model budget) after this
            // worker is gone. Propagate the cancellation.
            AgentRuntime.stop(thread.id)
            throw e
        }
    }

    /**
     * Runs a one-shot scheduled-task prompt (TaskReceiver) on a fresh
     * thread and lands the result in the Review Inbox, mirroring the
     * automation path minus the stored-automation bookkeeping. Without
     * this, a fired task only notified and never executed its prompt.
     */
    private suspend fun runOneShot(prompt: String, title: String): Result {
        val db = CodexDatabase.getInstance(applicationContext)
        val keyStore = KeyStore(applicationContext)
        val baseUrl = keyStore.getBaseUrl()
        val apiKey = keyStore.getApiKey()
        if (!keyStore.hasKey() || baseUrl.isBlank() || apiKey.isNullOrBlank()) {
            // No key to run with: record the miss as an error thread so
            // it is visible in History instead of vanishing silently.
            val thread = db.createThread(
                workspaceId = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID,
                title = "Scheduled: $title",
                mode = ExecutionMode.AUTO.name,
            )
            db.updateThreadState(thread.id, status = "error", unread = true)
            return Result.success()
        }
        val thread = db.createThread(
            workspaceId = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID,
            title = "Scheduled: $title",
            mode = ExecutionMode.AUTO.name,
        )
        val started = AgentRuntime.startTurn(
            applicationContext,
            thread.id,
            prompt,
            ExecutionMode.AUTO,
            MAX_STEPS,
        ) {
            runAutomationTurn(this, thread.id, prompt, baseUrl, apiKey, keyStore.getModel())
        }
        if (!started) return Result.success()
        return try {
            withTimeout(WORK_TIMEOUT_MS) {
                AgentRuntime.threads.first { states ->
                    states[thread.id]?.isRunning == false
                }
            }
            val failed = db.listThreads().firstOrNull { it.id == thread.id }?.status == "error" ||
                lastAssistantMessageFailed(db, thread.id)
            db.updateThreadState(
                thread.id,
                status = if (failed) "error" else "needs_review",
                unread = true,
            )
            if (failed) Result.retry() else Result.success()
        } catch (e: TimeoutCancellationException) {
            AgentRuntime.stop(thread.id)
            db.updateThreadState(thread.id, status = "error", unread = true)
            Result.retry()
        } catch (e: CancellationException) {
            AgentRuntime.stop(thread.id)
            throw e
        }
    }

    /**
     * Reads the finished run back from the DB and lands it in the Review
     * Inbox. A run counts as failed when the runtime recorded the thread
     * as `error` (the runner threw) or when the loop's final answer
     * reports it could not finish (AgentLoop swallows model errors into
     * its answer text instead of throwing).
     */
    private suspend fun finishRun(
        db: CodexDatabase,
        automation: AutomationEntity,
        threadId: String,
    ): Result {
        val thread = db.listThreads().firstOrNull { it.id == threadId }
        val modelFailed = lastAssistantMessageFailed(db, threadId)
        return if (thread?.status == "error" || modelFailed) {
            db.updateThreadState(threadId, status = "error", unread = true)
            db.finishAutomation(automation.id, if (thread?.status == "error") "error:run_failed" else "error:model")
            if (automation.calendar == null) Result.retry() else Result.success()
        } else {
            db.updateThreadState(threadId, status = "needs_review", unread = true)
            db.finishAutomation(automation.id, "ok")
            Result.success()
        }
    }

    private suspend fun lastAssistantMessageFailed(db: CodexDatabase, threadId: String): Boolean {
        val last = runCatching {
            db.getMessages(threadId).lastOrNull { it.role == "assistant" }
        }.getOrNull() ?: return false
        return last.kind == "error" ||
            last.content.startsWith("I could not finish:")
    }

    /**
     * The TurnContext runner bridge: drives the existing [AgentLoop]
     * (not rewritten) and translates its events into [AgentRuntime]
     * TurnContext calls — the same bridge as ChatViewModel.runAgentTurn,
     * adapted for an unattended run:
     *
     * Event → TurnContext mapping:
     * - PlanCreated     → nextStepOrThrow("Plan: …") — step 1, run start.
     * - AssistantText   → nextStepOrThrow("Model reply") + emitAssistantDelta(text).
     * - ToolStarted     → nextStepOrThrow("Tool: …") — one step per tool call.
     * - ToolFinished / PlanUpdated / Error / Finished → narration only
     *   (no step: the step was counted at ToolStarted / the reply).
     *   Finished needs no handling here: the runtime appends the final
     *   answer to the timeline itself.
     * - ApprovalRequested → NOTHING is suspended on: the
     *   [AutomationApprovalGate] already answered it (BLOCK and
     *   PROMPT_USER are both auto-denied unattended). The event is only
     *   recorded as a system note so the Review Inbox shows what was
     *   refused. Contrast ChatViewModel, where the collector calls
     *   turn.requestApproval(...) and waits for a human tap — that would
     *   hang a background worker with nobody to answer it.
     * - Every event     → drainSteeringMessages() (step boundary); a
     *   queued steering message is recorded, not applied mid-run.
     *
     * The collector is attached BEFORE loop.run() starts emitting, so no
     * early event is lost. The step budget is enforced by
     * nextStepOrThrow: its IllegalStateException fails the collector
     * child, coroutineScope cancels the loop and rethrows it, and the
     * runtime records it in the DB as an error.
     */
    private suspend fun runAutomationTurn(
        turn: AgentRuntime.TurnContext,
        threadId: String,
        goal: String,
        baseUrl: String,
        apiKey: String,
        model: String,
    ): String {
        val context = applicationContext
        val db = CodexDatabase.getInstance(context)
        // BUG 3 (same fix as ChatViewModel): a Channel, not a SharedFlow —
        // MutableSharedFlow with replay = 0 drops every event emitted
        // before the first collector subscribes. UNLIMITED so backpressure
        // never stalls the loop mid-approval.
        val events = Channel<AgentEvent>(Channel.UNLIMITED)
        val eventSink = channelEventCollector(events)

        // Load enabled skills before the run; a skills failure degrades
        // to no skills rather than failing the run.
        val skills = try {
            SkillStore(context).enabled()
        } catch (e: Exception) {
            emptyList()
        }
        val client = OkHttpOpenAiClient(baseUrl = baseUrl, apiKey = apiKey, model = model)
        val memory = PersonalMemory(context)
        val loop = AgentLoop(
            client = client,
            tools = policyGatedRegistry(context, threadId, db),
            gate = AutomationApprovalGate(threadId, db),
            planStore = PlanStore(context),
            model = model,
            skills = skills,
            recalledMemory = memory::recall,
            soulMarkdown = memory::soulForAgent,
        )

        return try {
            coroutineScope {
                // The collector is attached BEFORE loop.run() starts
                // emitting, and the Channel queues anything emitted before
                // it subscribes — so no early event is lost either way.
                val collector = launch {
                    events.receiveAsFlow().collect { event ->
                        // Drain mid-turn steering at every step boundary.
                        // AgentLoop has no injection point for mid-turn
                        // messages, so steering cannot steer the current
                        // run — it is recorded for the next run instead.
                        turn.drainSteeringMessages().forEach { msg ->
                            recordSystemNote(db, threadId, "Steering noted: $msg")
                        }
                        when (event) {
                            is AgentEvent.PlanCreated -> {
                                turn.nextStepOrThrow("Plan: ${event.plan.goal}")
                            }
                            is AgentEvent.PlanUpdated -> {
                                // No message.
                            }
                            is AgentEvent.AssistantText -> {
                                turn.nextStepOrThrow("Model reply")
                                turn.emitAssistantDelta(event.text)
                            }
                            is AgentEvent.ToolStarted -> {
                                turn.nextStepOrThrow("Tool: ${event.toolName}")
                            }
                            is AgentEvent.ToolFinished -> {
                                // No message.
                            }
                            is AgentEvent.ApprovalRequested -> {
                                // The automation gate already answered
                                // this without prompting (see
                                // AutomationApprovalGate): a BLOCKed tool
                                // is never opened for approval, and a
                                // PROMPT_USER tool is auto-denied
                                // unattended. Record which it was.
                                val decision = AgentRuntime.policy.evaluate(
                                    threadId,
                                    event.action.toolName,
                                    ToolRisk.EXTERNAL_OR_DESTRUCTIVE,
                                    ExecutionMode.AUTO,
                                )
                                if (decision == PolicyDecision.BLOCK) {
                                    recordSystemNote(
                                        db,
                                        threadId,
                                        "Blocked by policy: ${event.action.toolName}",
                                    )
                                } else {
                                    recordSystemNote(
                                        db,
                                        threadId,
                                        "Denied (unattended run): ${event.action.toolName}",
                                    )
                                }
                            }
                            is AgentEvent.ApprovalResolved -> {
                                // Already recorded above as blocked or
                                // denied; this is not a user decision.
                            }
                            is AgentEvent.Error -> {
                                // The loop turns errors into its final
                                // answer; finishRun() detects that.
                            }
                            is AgentEvent.Finished -> {
                                // The runtime appends the final answer.
                            }
                        }
                    }
                }
                try {
                    loop.run(goal, eventSink)
                } finally {
                    collector.cancel()
                    events.close()
                }
            }
        } catch (e: IllegalStateException) {
            // The step-budget throw from nextStepOrThrow (a collector
            // child failure, rethrown by coroutineScope) — or any other
            // IllegalStateException from the loop. Rethrow so AgentRuntime
            // records it in the DB timeline as an error.
            throw e
        }
    }

    private fun policyGatedRegistry(
        context: Context,
        threadId: String,
        db: CodexDatabase,
    ): ToolRegistry {
        val gated = ToolRegistry()
        val memory = PersonalMemory(context)
        val base = ToolRegistry.withBuiltins(context)
        if (memory.isRecallEnabled()) base.register(MemorySearchTool(memory))
        base.all().forEach { tool ->
            gated.register(PolicyGatedTool(tool, threadId, db))
        }
        return gated
    }
}

/** Best-effort timeline note: a note failure must never break the run. */
private suspend fun recordSystemNote(db: CodexDatabase, threadId: String, text: String) {
    try {
        db.appendMessage(
            TimelineMessage(threadId = threadId, role = "system", kind = "text", content = text)
        )
    } catch (e: Exception) {
        // A timeline failure must never break the run.
    }
}

/**
 * Maps a tool to its policy risk using the codebase's own sensitivity
 * classification (same mapping as ChatViewModel): tools that declare
 * themselves sensitive are the mutating/external ones; everything else
 * is read-only.
 */
private fun riskFor(tool: Tool): ToolRisk =
    if (tool.sensitive) ToolRisk.EXTERNAL_OR_DESTRUCTIVE else ToolRisk.READ_ONLY

/** Best-effort audit write: an audit failure must never break the run. */
private suspend fun recordAuditQuietly(
    db: CodexDatabase,
    threadId: String,
    toolName: String,
    decision: PolicyDecision,
    summary: String,
    durationMs: Long,
) {
    try {
        db.recordAudit(
            threadId = threadId,
            toolName = toolName,
            decision = decision.name,
            summary = summary,
            durationMs = durationMs,
        )
    } catch (_: Exception) {
        // An audit failure must never break the run.
    }
}

/**
 * ApprovalGate for an unattended automation run. Unlike ChatViewModel's
 * PolicyApprovalGate — which delegates to the real gate and suspends
 * until a human taps Approve/Deny — this gate NEVER suspends, because
 * there is no human to answer it in a background worker:
 *
 * - ALLOW        → true (run it).
 * - BLOCK        → audit + false (refused, never prompted).
 * - PROMPT_USER  → audit + false (auto-denied: an unattended run must
 *   not hang waiting for an approval nobody will give).
 *
 * This is Codex's own scheduled-task rule (approval_policy = "never":
 * whatever would need approval fails) applied to the phone. A
 * PROMPT_USER tool therefore fails its call and the model is told so as
 * the tool result, exactly as a user denial would.
 */
private class AutomationApprovalGate(
    private val threadId: String,
    private val db: CodexDatabase,
) : ApprovalGate {

    override val pending: StateFlow<PendingAction?> = MutableStateFlow(null)

    override suspend fun requestApproval(action: PendingAction): Boolean {
        val decision = AgentRuntime.policy.evaluate(
            threadId,
            action.toolName,
            ToolRisk.EXTERNAL_OR_DESTRUCTIVE,
            ExecutionMode.AUTO,
        )
        return if (decision == PolicyDecision.ALLOW) {
            true
        } else {
            recordAuditQuietly(
                db = db,
                threadId = threadId,
                toolName = action.toolName,
                decision = decision,
                summary = "Unattended run: approval auto-denied (${decision.name})",
                durationMs = 0,
            )
            false
        }
    }

    override fun answer(id: String, approved: Boolean) {
        // No-op: this gate never suspends, so there is never a pending
        // approval to answer.
    }
}

/**
 * One registered tool wrapped with the run's policy check + audit. The
 * loop executes through this wrapper, so the policy is evaluated before
 * every tool call: a BLOCK (AUTO mode, DENY rule) refuses the call and
 * the tool never runs; anything else executes and the decision is
 * audited. (Same wrapper as ChatViewModel's PolicyGatedTool, with the
 * mode fixed to AUTO for automations.)
 */
private class PolicyGatedTool(
    private val delegate: Tool,
    private val threadId: String,
    private val db: CodexDatabase,
) : Tool {

    override val name: String get() = delegate.name
    override val description: String get() = delegate.description
    override val parametersSchema: JsonObject get() = delegate.parametersSchema
    override val sensitive: Boolean get() = delegate.sensitive

    override suspend fun execute(args: JsonObject): ToolResult {
        val decision = AgentRuntime.policy.evaluate(
            threadId,
            delegate.name,
            riskFor(delegate),
            ExecutionMode.AUTO,
        )
        if (decision == PolicyDecision.BLOCK) {
            recordAuditQuietly(
                db = db,
                threadId = threadId,
                toolName = delegate.name,
                decision = PolicyDecision.BLOCK,
                summary = "Blocked by policy (AUTO mode / DENY rule)",
                durationMs = 0,
            )
            return ToolResult(
                ok = false,
                output = "",
                error = "Blocked by policy: ${delegate.name} is not allowed in AUTO mode.",
            )
        }
        val startMs = System.currentTimeMillis()
        val result = delegate.execute(args)
        val durationMs = System.currentTimeMillis() - startMs
        recordAuditQuietly(
            db = db,
            threadId = threadId,
            toolName = delegate.name,
            decision = decision,
            summary = "Policy: ${decision.name}; executed in ${durationMs}ms",
            durationMs = durationMs,
        )
        return result
    }
}
