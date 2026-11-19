package dev.codex.android.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * One tool call queued for a batch run.
 *
 * @property toolCallId the assistant message's tool-call id (e.g. the
 *   model's `tool_call_id`). It is a caller-managed label: [ParallelToolRunner]
 *   returns results in input order, so the caller zips them back to ids
 *   itself. Uniqueness is the caller's responsibility.
 * @property isReadOnly true when the call only reads state and is safe to
 *   run concurrently with other read-only calls. False for any mutating
 *   call, or any call gated on user approval: such a call is a barrier
 *   (see [ParallelToolRunner.runBatched]).
 * @property execute the call itself; invoked exactly once per batch run,
 *   unless the batch is cancelled or an earlier call fails first.
 */
data class QueuedCall<T>(
    val toolCallId: String,
    val isReadOnly: Boolean,
    val execute: suspend () -> T,
)

/**
 * Runs a batch of tool calls with read-only calls in parallel.
 *
 * Behaviour, following OpenCode's model (open-source, MIT; behaviour only,
 * no code copied): independent read-only tool calls in one assistant turn
 * run concurrently instead of one at a time, while anything that mutates
 * state — or that must ask the user first — serialises the batch around
 * itself. OpenCode v2.3 likewise runs background subagents in parallel
 * behind a `max_parallel` cap; here the cap is [maxParallelReads].
 *
 * Semantics of [runBatched]:
 * - Read-only calls run concurrently inside one [coroutineScope], bounded
 *   by a [Semaphore] of [maxParallelReads] permits, so at most that many
 *   reads execute at once.
 * - A mutating / ask-gated call ([QueuedCall.isReadOnly] == false) is a
 *   BARRIER: every pending read finishes first, the barrier call then runs
 *   alone, serially, and only afterwards do the following reads start.
 * - Results are returned in INPUT order, not completion order, so the
 *   caller can attach them to the assistant's tool-response messages
 *   deterministically.
 * - Fail-fast: if any call throws, or the batch is cancelled, the whole
 *   batch is cancelled — [coroutineScope] cancels the still-running reads
 *   and the exception (or [kotlinx.coroutines.CancellationException])
 *   propagates to the caller. No partial result list is ever returned, and
 *   calls after the failure never start.
 *
 * Pure Kotlin (stdlib + kotlinx-coroutines only): no Android APIs, no I/O.
 * The runner itself holds no mutable state and is safe to share across
 * runs; each [runBatched] invocation gets its own semaphore. Callers must
 * only queue calls that are actually safe to overlap — the runner trusts
 * [QueuedCall.isReadOnly]; it cannot verify a tool's side effects.
 *
 * This runner is deliberately NOT wired into [AgentLoop]: that loop
 * executes the model's tool calls serially today, and switching it to
 * batched execution (including how approval-gated calls become barriers)
 * is a separate integration step.
 */
class ParallelToolRunner(
    val maxParallelReads: Int = DEFAULT_MAX_PARALLEL_READS,
) {
    init {
        require(maxParallelReads >= 1) {
            "maxParallelReads must be at least 1, was $maxParallelReads"
        }
    }

    /**
     * Executes [calls] and returns one result per call, in input order.
     *
     * An empty batch returns an empty list without doing anything.
     *
     * @throws Exception whatever a failing call threw, or
     *   [kotlinx.coroutines.CancellationException] when the batch is
     *   cancelled; in both cases the batch stops and no results are
     *   returned.
     */
    suspend fun <T> runBatched(calls: List<QueuedCall<T>>): List<T> {
        if (calls.isEmpty()) return emptyList()
        val results = arrayOfNulls<Any?>(calls.size)
        val semaphore = Semaphore(maxParallelReads)

        var index = 0
        while (index < calls.size) {
            if (!calls[index].isReadOnly) {
                // Barrier: run alone, serially, between read segments.
                results[index] = calls[index].execute()
                index++
                continue
            }
            // One maximal run of consecutive read-only calls.
            var end = index
            while (end < calls.size && calls[end].isReadOnly) end++
            val values: List<T> = coroutineScope {
                val deferred = (index until end).map { i ->
                    async {
                        semaphore.withPermit { calls[i].execute() }
                    }
                }
                // awaitAll preserves the order of `deferred`, i.e. input
                // order, regardless of which read finished first. It also
                // rethrows the first failure, and coroutineScope cancels
                // the still-running siblings.
                deferred.awaitAll()
            }
            for ((offset, value) in values.withIndex()) {
                results[index + offset] = value
            }
            index = end
        }

        @Suppress("UNCHECKED_CAST")
        val typedResults: List<T> = results.map { it as T }
        return typedResults
    }

    companion object {
        /** Default bound on concurrently executing read-only calls. */
        const val DEFAULT_MAX_PARALLEL_READS = 6
    }
}
