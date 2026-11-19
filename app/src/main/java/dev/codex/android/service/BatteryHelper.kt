package dev.codex.android.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Phase 4 — battery-exemption and OEM survival helpers.
 *
 * Why each step exists (and why the Settings screen explains it before
 * asking): Android may kill background apps to save battery, and on
 * Xiaomi / Oppo / Vivo / OnePlus the vendor's own task killer can do so
 * even when Android itself would not. Battery exemption keeps the OS
 * from dozing Codex, the Accessibility service is how the agent sees
 * and drives the screen (Phase 2), and notifications are how Codex
 * tells the user a task finished.
 *
 * Every function degrades gracefully: a missing system service, a
 * denied intent, or an OEM that removed a settings screen never throws
 * — checks return false and openers become no-ops.
 */
object BatteryHelper {

    /**
     * True when the system is ignoring battery optimisations for Codex.
     * False on any exception (e.g. no PowerManager on this device).
     */
    fun isExempt(context: Context): Boolean {
        return try {
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    ?: return false
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Asks the system to exempt Codex from battery optimisation, via the
     * one-tap system dialog. Returns true when the request was launched,
     * false when it could not be started — the caller falls back to
     * [openBatterySettings] in that case.
     *
     * Note: the direct request needs
     * `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` declared
     * in the manifest; without it the system rejects the intent and this
     * returns false, so the user is sent to the manual list instead.
     */
    fun requestExemption(activity: Activity): Boolean {
        return try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + activity.packageName),
            )
            activity.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Opens the system battery-optimisation list, where the user can
     * exempt Codex manually. No-op when the settings screen cannot be
     * opened on this device.
     */
    fun openBatterySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // No-op: some OEM builds remove this screen.
        }
    }

    /**
     * Opens the system Accessibility settings, where the user enables
     * the Codex accessibility service. No-op when it cannot be opened.
     */
    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // No-op: the settings screen is unavailable on this device.
        }
    }

    /**
     * True when the Codex accessibility service is currently connected.
     * The [context] is accepted for a uniform helper API; the check
     * itself reads the service's connection state. False on any
     * exception.
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        return try {
            CodexAccessibilityService.isConnected()
        } catch (e: Exception) {
            false
        }
    }
}
