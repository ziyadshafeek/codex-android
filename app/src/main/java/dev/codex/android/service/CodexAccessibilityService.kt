package dev.codex.android.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Phase 2 stub — Android control via AccessibilityService.
 *
 * Does nothing yet: no events are observed and no UI is touched.
 * The manifest declaration stays commented out until Phase 2, so this
 * service is not registered, not exported, and cannot be enabled.
 */
class CodexAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Phase 2: observe UI events and drive the automation layer.
    }

    override fun onInterrupt() {
        // Phase 2: stop any in-flight automation cleanly.
    }
}
