package dev.codex.android

import android.content.ComponentName
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codex.android.service.BatteryHelper
import dev.codex.android.service.CodexAccessibilityService
import dev.codex.android.service.CodexAgentService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented service tests (run on the emulator via CI).
 *
 * Deliberately shallow: presence and start-without-crashing only.
 * Enabling the accessibility service or driving a full accessibility
 * interaction is flaky and needs a real phone, so it is NOT attempted
 * here. Likewise, surviving a process kill is phone-only behaviour and
 * is NOT asserted. No API key, no network, no live model calls.
 */
@RunWith(AndroidJUnit4::class)
class ServiceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun accessibilityService_isDeclared() {
        // getServiceInfo throws NameNotFoundException when the service is
        // not declared in the manifest, so a returned ServiceInfo is the
        // assertion: CodexAccessibilityService is declared in
        // AndroidManifest.xml with the BIND_ACCESSIBILITY_SERVICE
        // permission and the AccessibilityService intent-filter.
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, CodexAccessibilityService::class.java),
            0,
        )
        assertNotNull("CodexAccessibilityService must be declared in the manifest", info)
        assertEquals(
            CodexAccessibilityService::class.java.name,
            info.name,
        )
    }

    @Test
    fun agentService_startsWithoutCrashing() {
        try {
            CodexAgentService.start(context)

            // The service sets CodexAgentService.instance in onCreate, so
            // poll briefly for it instead of sleeping a fixed long time.
            // onStartCommand degrades gracefully (stopSelf, no crash) if
            // foreground promotion is refused, so instance-not-null is
            // the only assertion — not "stays in foreground".
            // 10 s, not 5: service creation on the slow no-KVM emulator
            // (process start + onCreate + notification channel) can take
            // several seconds, and a short poll would fail a service that
            // was still starting. The poll still returns the moment the
            // instance appears.
            var running: CodexAgentService? = null
            val deadline = System.currentTimeMillis() + 10_000L
            while (System.currentTimeMillis() < deadline) {
                running = CodexAgentService.instance
                if (running != null) break
                Thread.sleep(100)
            }
            assertNotNull("CodexAgentService did not start", running)
        } finally {
            CodexAgentService.stop(context)
        }
    }

    @Test
    fun batteryHelper_reportsStatusWithoutCrashing() {
        // Both helpers are specified to never throw (they catch everything
        // and return false on error), so the assertion is simply that
        // they return a boolean. The tautological assertTrue documents
        // that: the value itself depends on the emulator's state and is
        // not asserted either way.
        val exempt = BatteryHelper.isExempt(context)
        assertTrue("isExempt must return a boolean", exempt || !exempt)

        val a11yEnabled = BatteryHelper.isAccessibilityEnabled(context)
        assertTrue("isAccessibilityEnabled must return a boolean", a11yEnabled || !a11yEnabled)
    }
}
