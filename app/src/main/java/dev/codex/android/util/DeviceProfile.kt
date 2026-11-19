package dev.codex.android.util

import android.app.ActivityManager
import android.content.Context

/**
 * Coarse device class used to scale the app's background work and
 * memory use to what the phone can actually afford.
 */
enum class DeviceClass {
    LOW,
    MEDIUM,
    HIGH,
}

/**
 * Snapshot of the device's hardware class, as detected at call time.
 *
 * @param deviceClass coarse class derived from RAM and core count.
 * @param totalRamMb total device RAM in MiB (0 when it could not be read).
 * @param isLowRamDevice whether the OS flags this as a low-RAM device.
 * @param cpuCores logical CPU cores reported by the runtime (0 when unknown).
 */
data class DeviceInfo(
    val deviceClass: DeviceClass,
    val totalRamMb: Long,
    val isLowRamDevice: Boolean,
    val cpuCores: Int,
)

/**
 * Detects the device class from the OS-reported RAM and core count.
 *
 * Never throws and never crashes when a system service is unavailable:
 * any failure degrades to a safe [DeviceClass.MEDIUM] default, so the
 * caller always gets a usable answer.
 */
object DeviceProfile {

    fun detect(context: Context): DeviceInfo {
        return try {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val isLowRam = activityManager?.isLowRamDevice ?: false
            val totalRamMb = if (activityManager != null) {
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                memoryInfo.totalMem / (1024L * 1024L)
            } else {
                0L
            }
            val cpuCores = Runtime.getRuntime().availableProcessors()

            val deviceClass = when {
                isLowRam || totalRamMb < 4096 -> DeviceClass.LOW
                totalRamMb >= 8192 && cpuCores >= 8 -> DeviceClass.HIGH
                else -> DeviceClass.MEDIUM
            }
            DeviceInfo(deviceClass, totalRamMb, isLowRam, cpuCores)
        } catch (e: Exception) {
            // Degrade to a safe default — never crash on detection.
            DeviceInfo(DeviceClass.MEDIUM, 0, false, 0)
        }
    }
}
