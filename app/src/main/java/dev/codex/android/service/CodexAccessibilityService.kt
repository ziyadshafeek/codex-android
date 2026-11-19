package dev.codex.android.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.codex.android.control.ScreenSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 2 — Android control via AccessibilityService.
 *
 * The agent never talks to this class directly: [dev.codex.android.control.UiAutomation]
 * is the only surface agent tools call, and it delegates here. This service owns
 * the connection to the system, the cached root window, and every gesture /
 * node action, so callers never have to care whether the service is bound.
 *
 * Safety rules (do not weaken them):
 * - Never log screen contents. No text, content description, package name or
 *   view id from another app is ever written to a log.
 * - Every operation is bounded: a few seconds max, then false / "" — the agent
 *   loop must never hang because an app is slow or the service is unbound.
 * - Never crash when the service is not connected or the root window is null.
 * - No network, no API keys, no secrets anywhere in this file.
 */
class CodexAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CodexA11y"

        /** How long any single operation may take before it gives up. */
        private const val OPERATION_TIMEOUT_MS = 3_000L

        /** Duration of the tap stroke inside a tap gesture. */
        private const val TAP_DURATION_MS = 100L

        /**
         * The currently connected service instance, or null when the user has
         * not enabled Codex in Settings > Accessibility (or the system
         * unbound it). Volatile so the agent loop sees connects/disconnects
         * immediately from any thread.
         */
        @Volatile
        var instance: CodexAccessibilityService? = null
            private set

        /** True when the service is connected and usable. */
        fun isConnected(): Boolean = instance != null
    }

    /** Latest root window, refreshed on accessibility events. See [withRoot]. */
    @Volatile
    private var cachedRoot: AccessibilityNodeInfo? = null
    private val cacheLock = Any()

    /** Time (uptime ms) of the last accessibility event received. */
    @Volatile
    private var lastEventTimeMs: Long = 0L

    /** In-flight tap gesture, completed by [gestureCallback] or [onInterrupt]. */
    @Volatile
    private var pendingGesture: CompletableDeferred<Boolean>? = null
    private val gestureMutex = Mutex()

    private val gestureCallback = object : GestureResultCallback() {
        override fun onCompleted(gesture: GestureDescription?) {
            pendingGesture?.complete(true)
            pendingGesture = null
        }

        override fun onCancelled(gesture: GestureDescription?) {
            pendingGesture?.complete(false)
            pendingGesture = null
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearInstance()
        super.onDestroy()
    }

    private fun clearInstance() {
        instance = null
        synchronized(cacheLock) {
            runCatching { cachedRoot?.recycle() }
            cachedRoot = null
        }
        pendingGesture?.let { if (!it.isCompleted) it.complete(false) }
        pendingGesture = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        lastEventTimeMs = android.os.SystemClock.uptimeMillis()
        // Lightweight cache only: refresh the root window reference so
        // operations have a fallback when rootInActiveWindow is briefly
        // null. Never log anything about the event or its contents.
        runCatching {
            val fresh = rootInActiveWindow ?: return@runCatching
            synchronized(cacheLock) {
                runCatching { cachedRoot?.recycle() }
                cachedRoot = fresh
            }
        }
    }

    override fun onInterrupt() {
        // Cancel any in-flight gesture cleanly; the awaiting tapAt() sees
        // false and the agent loop moves on. There is no system API to
        // revoke a dispatched gesture, so completing the deferred is the
        // whole of the cancellation.
        pendingGesture?.let { if (!it.isCompleted) it.complete(false) }
        pendingGesture = null
        Log.i(TAG, "Accessibility service interrupted")
    }

    // ------------------------------------------------------------------
    // Root-window access
    // ------------------------------------------------------------------

    /**
     * Runs [block] with the active window's root node. Prefers a fresh
     * [rootInActiveWindow]; falls back to the cached root from the last
     * accessibility event. The fresh root is recycled after [block]
     * returns; the cached root is only borrowed, never recycled here.
     * Returns null when there is no usable root, or [block] throws.
     */
    private inline fun <T> withRoot(block: (AccessibilityNodeInfo) -> T): T? {
        var root: AccessibilityNodeInfo? = null
        var owned = false
        return try {
            root = runCatching { rootInActiveWindow }.getOrNull()
            if (root == null) {
                synchronized(cacheLock) { root = cachedRoot }
            } else {
                owned = true
            }
            if (root == null) null else block(root)
        } catch (e: Exception) {
            Log.w(TAG, "Root window operation failed: ${e.javaClass.simpleName}")
            null
        } finally {
            if (owned) runCatching { root?.recycle() }
        }
    }

    // ------------------------------------------------------------------
    // Operations called by UiAutomation
    // ------------------------------------------------------------------

    /**
     * Visible text of the current screen: node text plus content
     * descriptions, deduplicated, capped at [ScreenSnapshot.MAX_CHARS].
     * Returns "" when the service is unusable or the screen is empty.
     */
    suspend fun readScreenText(): String =
        withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            withRoot { root -> ScreenSnapshot.collectText(root) } ?: ""
        } ?: ""

    /** Taps the first clickable node whose text or content description contains [text]. */
    suspend fun tapText(text: String): Boolean {
        if (text.isBlank()) return false
        return withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            withRoot { root ->
                val node = ScreenSnapshot.findByText(root, text) ?: return@withRoot false
                try {
                    runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        .getOrDefault(false)
                } finally {
                    runCatching { node.recycle() }
                }
            } ?: false
        } ?: false
    }

    /** Taps the screen at absolute coordinates ([x], [y]) via dispatchGesture. */
    suspend fun tapAt(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0) return false
        return withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            gestureMutex.withLock {
                val deferred = CompletableDeferred<Boolean>()
                pendingGesture = deferred
                val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                val gesture = GestureDescription.Builder()
                    .addStroke(StrokeDescription(path, 0, TAP_DURATION_MS))
                    .build()
                val dispatched = runCatching {
                    dispatchGesture(gesture, gestureCallback, null)
                }.getOrDefault(false)
                if (!dispatched) {
                    pendingGesture = null
                    false
                } else {
                    deferred.await()
                }
            }
        } ?: run {
            pendingGesture?.let { if (!it.isCompleted) it.complete(false) }
            pendingGesture = null
            false
        }
    }

    /** Types [text] into the currently focused editable field (falls back to any editable field). */
    suspend fun typeText(text: String): Boolean {
        if (text.isEmpty()) return false
        return withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            withRoot { root ->
                val node = ScreenSnapshot.findFocusedEditable(root) ?: return@withRoot false
                try {
                    if (!node.isFocused) {
                        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
                    }
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text,
                        )
                    }
                    runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
                        .getOrDefault(false)
                } finally {
                    runCatching { node.recycle() }
                }
            } ?: false
        } ?: false
    }

    /** Scrolls the focused/scrollable content down (forward). */
    suspend fun scrollDown(): Boolean = scroll(forward = true)

    /** Scrolls the focused/scrollable content up (backward). */
    suspend fun scrollUp(): Boolean = scroll(forward = false)

    private suspend fun scroll(forward: Boolean): Boolean {
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            withRoot { root ->
                val candidates = ScreenSnapshot.findScrollableNodes(root)
                try {
                    // A focused scroller is the one the user is looking at.
                    val ordered = candidates.sortedByDescending { it.isFocused }
                    for (node in ordered) {
                        if (runCatching { node.performAction(action) }.getOrDefault(false)) {
                            return@withRoot true
                        }
                    }
                    // Last resort: the root itself (covers full-screen scrollers
                    // whose scrollable node was not enumerated).
                    runCatching { root.performAction(action) }.getOrDefault(false)
                } finally {
                    candidates.forEach { runCatching { it.recycle() } }
                }
            } ?: false
        } ?: false
    }

    /** System Back. */
    suspend fun goBack(): Boolean = globalAction(GLOBAL_ACTION_BACK)

    /** System Home. */
    suspend fun goHome(): Boolean = globalAction(GLOBAL_ACTION_HOME)

    private suspend fun globalAction(action: Int): Boolean =
        withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            runCatching { performGlobalAction(action) }.getOrDefault(false)
        } ?: false
}
