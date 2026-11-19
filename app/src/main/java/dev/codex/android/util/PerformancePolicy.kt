package dev.codex.android.util

/**
 * Battery- and memory-friendly defaults, scaled by device class.
 *
 * Low-end phones get less background work, less restored history, and a
 * smaller agent turn budget, so the app saves battery and RAM where it
 * matters most instead of treating every phone like a flagship. These
 * are pure functions with no I/O, so they are trivially testable and
 * can never fail at runtime.
 */
object PerformancePolicy {

    /**
     * How often the resurrection worker re-arms alarms and checks for a
     * run that died with the process. LOW devices check every 12 hours
     * instead of 6: waking a low-RAM phone half as often saves battery
     * and avoids competing with foreground apps for scarce memory, and
     * a missed resurrection is only ever delayed, never lost — the next
     * run, app start, or boot receiver picks it up.
     */
    fun resurrectionIntervalHours(deviceClass: DeviceClass): Long {
        return when (deviceClass) {
            DeviceClass.LOW -> 12
            DeviceClass.MEDIUM -> 6
            DeviceClass.HIGH -> 6
        }
    }

    /**
     * How many recent chat messages to restore on launch. LOW devices
     * restore 10 instead of 20: fewer rows read from SQLite and fewer
     * views held in memory on phones where both are scarce, while still
     * giving the user their recent conversation back.
     */
    fun historyLimit(deviceClass: DeviceClass): Int {
        return when (deviceClass) {
            DeviceClass.LOW -> 10
            DeviceClass.MEDIUM -> 20
            DeviceClass.HIGH -> 20
        }
    }

    /**
     * Agent loop turn budget (the documented default for a future
     * wiring; the loop currently hardcodes 12). LOW devices get 8
     * turns instead of 12: fewer API round-trips and less accumulated
     * context per run, so a run finishes sooner and holds less in
     * memory on a constrained phone.
     */
    fun maxTurns(deviceClass: DeviceClass): Int {
        return when (deviceClass) {
            DeviceClass.LOW -> 8
            DeviceClass.MEDIUM -> 12
            DeviceClass.HIGH -> 12
        }
    }
}
