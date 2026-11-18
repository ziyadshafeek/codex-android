package dev.codex.android.security

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A single sensitive action awaiting the user's yes/no decision.
 *
 * @param id unique id for this request; the UI echoes it back via [ApprovalGate.answer].
 * @param toolName name of the tool that wants to run.
 * @param description human-readable summary of what the tool would do.
 * @param argsJson the tool arguments, serialised as JSON, for display/audit.
 */
data class PendingAction(
    val id: String,
    val toolName: String,
    val description: String,
    val argsJson: String,
)

/**
 * Gate for sensitive tool calls. The agent loop calls [requestApproval] and
 * suspends until the user answers; the UI observes [pending] and calls
 * [answer] with the user's decision.
 */
interface ApprovalGate {
    /** The action currently awaiting a decision, or null when none is pending. */
    val pending: StateFlow<PendingAction?>

    /**
     * Publish [action] as pending and suspend until the user answers.
     * Returns true when approved, false when denied or when the wait is cancelled.
     *
     * @throws IllegalStateException if another action is already pending.
     */
    suspend fun requestApproval(action: PendingAction): Boolean

    /**
     * Record the user's decision for the pending action with the given [id].
     * Stale ids (not matching the current pending action) are ignored.
     */
    fun answer(id: String, approved: Boolean)
}

/**
 * Single-pending-action implementation of [ApprovalGate].
 *
 * The agent loop is single-threaded, so at most one approval is ever in
 * flight; a second [requestApproval] while one is pending fails loudly
 * with [IllegalStateException] rather than silently overwriting it.
 */
class DefaultApprovalGate : ApprovalGate {

    private val _pending = MutableStateFlow<PendingAction?>(null)
    override val pending: StateFlow<PendingAction?> = _pending

    private var currentDeferred: CompletableDeferred<Boolean>? = null

    override suspend fun requestApproval(action: PendingAction): Boolean {
        if (_pending.value != null) {
            throw IllegalStateException("Another approval is already pending")
        }
        val deferred = CompletableDeferred<Boolean>()
        currentDeferred = deferred
        _pending.value = action
        try {
            return deferred.await()
        } catch (e: CancellationException) {
            // Deny on cancellation, then rethrow — never swallow cancellation.
            deferred.complete(false)
            throw e
        } finally {
            if (_pending.value?.id == action.id) {
                _pending.value = null
            }
            if (currentDeferred === deferred) {
                currentDeferred = null
            }
        }
    }

    override fun answer(id: String, approved: Boolean) {
        val current = _pending.value ?: return
        if (current.id != id) return
        currentDeferred?.complete(approved)
        _pending.value = null
        currentDeferred = null
    }
}
