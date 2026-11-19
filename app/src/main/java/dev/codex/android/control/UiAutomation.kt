package dev.codex.android.control

import dev.codex.android.service.CodexAccessibilityService
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 2 — the ONLY surface agent tools call to operate the on-screen UI.
 *
 * Thin facade over [CodexAccessibilityService]: every call checks that the
 * service is connected first and is bounded by [OPERATION_TIMEOUT_MS], so
 * the agent loop never hangs on a dead service or a slow app. The node-tree
 * work (find-by-text, screen dump, editable/scrollable lookup) lives in
 * [ScreenSnapshot] and is driven from the service; this object only owns
 * the availability contract and the exact API the tool layer codes against.
 *
 * The user must enable Codex in Settings > Accessibility before any of
 * this works; that is a user action, not something code can do for them.
 */
object UiAutomation {

    /** Upper bound for any single call, on top of the service's own timeout. */
    private const val OPERATION_TIMEOUT_MS = 5_000L

    const val NOT_ENABLED_MESSAGE =
        "Accessibility service not enabled — turn on Codex in Settings > Accessibility, then try again."

    /** True when the accessibility service is connected and usable. */
    fun isAvailable(): Boolean = CodexAccessibilityService.instance != null

    /**
     * Visible text of the current screen, or a message starting with
     * "Accessibility service not enabled" when the service is unavailable.
     */
    suspend fun readScreen(): String {
        val service = CodexAccessibilityService.instance ?: return NOT_ENABLED_MESSAGE
        return withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            service.readScreenText()
        } ?: "Accessibility service timed out while reading the screen."
    }

    /**
     * Taps the first clickable node whose text or content description
     * contains [text] (case-insensitive). False when not found or the
     * service is unavailable.
     */
    suspend fun tapText(text: String): Boolean {
        if (text.isBlank()) return false
        val service = CodexAccessibilityService.instance ?: return false
        return withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            service.tapText(text)
        } ?: false
    }

    /** Taps the screen at absolute coordinates ([x], [y]). */
    suspend fun tapAt(x: Int, y: Int): Boolean {
        val service = CodexAccessibilityService.instance ?: return false
        return withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            service.tapAt(x, y)
        } ?: false
    }

    /** Types [text] into the currently focused editable field. */
    suspend fun typeText(text: String): Boolean {
        if (text.isEmpty()) return false
        val service = CodexAccessibilityService.instance ?: return false
        return withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            service.typeText(text)
        } ?: false
    }

    /** Scrolls the focused/scrollable content down. */
    suspend fun scrollDown(): Boolean {
        val service = CodexAccessibilityService.instance ?: return false
        return withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            service.scrollDown()
        } ?: false
    }

    /** Scrolls the focused/scrollable content up. */
    suspend fun scrollUp(): Boolean {
        val service = CodexAccessibilityService.instance ?: return false
        return withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            service.scrollUp()
        } ?: false
    }

    /** System Back. */
    suspend fun goBack(): Boolean {
        val service = CodexAccessibilityService.instance ?: return false
        return withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            service.goBack()
        } ?: false
    }

    /** System Home. */
    suspend fun goHome(): Boolean {
        val service = CodexAccessibilityService.instance ?: return false
        return withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            service.goHome()
        } ?: false
    }
}
