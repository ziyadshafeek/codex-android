package dev.codex.android.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream

/**
 * Subsystem 8 (Master Plan M8/M9) — MediaProjection screenshot fallback
 * for when the Accessibility tree is empty (WebViews, Canvas / game /
 * map apps that expose no nodes).
 *
 * Pattern credit: Deft (MIT, bedda-tech/deft) ships a MediaProjection
 * screenshot API alongside `AccessibilityService.takeScreenshot()`; the
 * take-screenshot path is tried FIRST (see
 * [CodexAccessibilityService.captureScreenshotToFile] — no extra
 * permission, no consent flow), and this class is the second fallback.
 *
 * CONSENT — READ THIS BEFORE WIRING IT UP:
 * - Screen capture is NEVER started silently. The user must grant it
 *   through the system consent dialog: call [captureIntent] from an
 *   Activity (e.g. via `registerForActivityResult`) and pass the result
 *   to [onConsentGranted]. No consent → [isReady] is false → every
 *   capture returns null and the agent is told how to grant it.
 * - The MediaProjection is kept alive after consent so later captures
 *   do not re-prompt; each capture creates and releases its own
 *   VirtualDisplay, and [release] stops the projection entirely.
 * - Captured bitmaps are screen contents of OTHER apps: they are written
 *   only to this app's private cacheDir ([captureToCacheFile]), never
 *   logged, never uploaded, and only handed to the model as a vision
 *   input when the agent loop supports images. Overwritten on every
 *   capture so at most one screenshot is ever stored.
 *
 * MANIFEST GAP — this file compiles standalone, but it cannot capture
 * on the target device until the app manifest gains the API 34
 * media-projection foreground-service declarations (the manifest is
 * outside this task's file scope, so a follow-up must add them):
 * ```
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
 * <service android:name=".service.CodexAgentService"
 *     android:foregroundServiceType="dataSync|mediaProjection" ... />
 * ```
 * On targetSdk 34, `createVirtualDisplay()` throws SecurityException
 * unless the caller runs a foreground service of type mediaProjection.
 * Until those two lines land, [captureScreenshot] returns null and
 * [lastError] explains why — the Accessibility `takeScreenshot()` path
 * above keeps working in the meantime.
 */
object MediaProjectionCapture {

    private const val TAG = "CodexCapture"
    private const val CAPTURE_TIMEOUT_MS = 3_000L
    private const val SCREENSHOT_FILE_NAME = "codex_screen_fallback.png"

    /** Last failure reason, for tool output. Null when the last capture worked. */
    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var projection: MediaProjection? = null

    /**
     * The system tells us when the projection dies (user revokes it from
     * the status bar, the system reclaims it). Without this callback the
     * stored projection stays non-null but dead: isReady() lies and every
     * capture fails until the process restarts.
     */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            projection = null
            appContext = null
            lastError = "Screen-capture consent ended — grant it again to capture"
        }
    }

    /** Application context captured at consent time (for metrics + cacheDir). */
    @Volatile
    private var appContext: Context? = null

    /** True once the user has granted screen-capture consent in this process. */
    fun isReady(): Boolean = projection != null

    /**
     * The system consent intent. Launch it from an Activity — the system
     * shows the "Start recording / casting?" dialog. The result MUST go
     * to [onConsentGranted]; there is no other way to obtain a projection.
     */
    fun captureIntent(context: Context): Intent {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return manager.createScreenCaptureIntent()
    }

    /**
     * Handles the consent Activity result. Returns true when a usable
     * [MediaProjection] was obtained. False when the user declined
     * ([Activity.RESULT_OK] not set) or the system refused.
     */
    fun onConsentGranted(context: Context, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) {
            lastError = "Screen-capture consent was not granted"
            return false
        }
        return try {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val obtained = manager.getMediaProjection(resultCode, data)
            if (obtained == null) {
                lastError = "System refused the screen-capture request"
                false
            } else {
                release()
                obtained.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
                projection = obtained
                appContext = context.applicationContext
                lastError = null
                Log.i(TAG, "Screen-capture consent granted")
                true
            }
        } catch (e: Exception) {
            // SecurityException here means the manifest foreground-service
            // declarations (see the file header) are still missing.
            lastError = "Screen capture unavailable: ${e.javaClass.simpleName}"
            Log.w(TAG, "getMediaProjection failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Captures one frame of the current screen, or null when consent is
     * missing, the projection is dead, or no frame arrived in time.
     * The projection is kept alive for reuse; the VirtualDisplay and
     * ImageReader are released after every capture. The context captured
     * at consent time ([onConsentGranted]) supplies display metrics.
     */
    suspend fun captureScreenshot(): Bitmap? {
        val active = projection ?: run {
            lastError = "Screen-capture permission not granted — grant it in Codex settings first"
            return null
        }
        val context = appContext ?: run {
            lastError = "Screen-capture permission not granted — grant it in Codex settings first"
            return null
        }
        val metrics = DisplayMetrics()
        runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
        }
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) {
            lastError = "Could not determine screen size"
            return null
        }
        val handlerThread = HandlerThread("CodexCapture").apply { start() }
        try {
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val display: VirtualDisplay? = runCatching {
                active.createVirtualDisplay(
                    "CodexCapture",
                    width,
                    height,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    Handler(handlerThread.looper),
                )
            }.getOrNull()
            if (display == null) {
                lastError = "Screen capture failed — the app needs a mediaProjection foreground service (manifest)"
                reader.close()
                return null
            }
            try {
                val bitmap = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                    var result: Bitmap? = null
                    while (result == null) {
                        val image = runCatching { reader.acquireLatestImage() }.getOrNull()
                        if (image != null) {
                            try {
                                result = imageToBitmap(image, width, height)
                            } finally {
                                runCatching { image.close() }
                            }
                        } else {
                            delay(50)
                        }
                    }
                    result
                }
                if (bitmap == null) lastError = "Screen capture timed out — no frame arrived"
                return bitmap
            } finally {
                runCatching { display.release() }
                runCatching { reader.close() }
            }
        } catch (e: Exception) {
            lastError = "Screen capture failed: ${e.javaClass.simpleName}"
            Log.w(TAG, "captureScreenshot failed: ${e.javaClass.simpleName}")
            return null
        } finally {
            runCatching { handlerThread.quitSafely() }
        }
    }

    /**
     * Captures one frame and stores it as a PNG in this app's private
     * cacheDir (overwriting any previous fallback screenshot). Returns
     * the file, or null when capture failed (see [lastError]).
     * Intended for the read_screen fallback: the tool output names the
     * file so a vision-capable step can read it later.
     */
    suspend fun captureToCacheFile(): File? {
        val bitmap = captureScreenshot() ?: return null
        val context = appContext ?: return null
        return try {
            val file = File(context.cacheDir, SCREENSHOT_FILE_NAME)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file
        } catch (e: Exception) {
            lastError = "Could not save screenshot: ${e.javaClass.simpleName}"
            null
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    /** Stops the projection and drops consent state. Call on logout / teardown. */
    fun release() {
        val current = projection
        // Unregister first so our own stop() does not re-enter onStop()
        // (which would clear state we are already clearing).
        runCatching { current?.unregisterCallback(projectionCallback) }
        runCatching { current?.stop() }
        projection = null
        appContext = null
    }

    /**
     * Converts one [android.media.Image] (RGBA_8888) to a [Bitmap],
     * cropping off the row-stride padding Android adds to the buffer.
     */
    private fun imageToBitmap(
        image: android.media.Image,
        width: Int,
        height: Int,
    ): Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return if (paddedWidth != width) {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            runCatching { bitmap.recycle() }
            cropped
        } else {
            bitmap
        }
    }
}
