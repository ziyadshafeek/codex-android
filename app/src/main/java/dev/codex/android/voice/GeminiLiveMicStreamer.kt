package dev.codex.android.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat

/**
 * Microphone capture for Gemini Live sessions ([dev.codex.android.api.GeminiLiveClient]).
 *
 * Reads 16 kHz mono 16-bit PCM — exactly the format the Live API accepts
 * for input audio — and hands each chunk to [onChunk]. The permission
 * pattern mirrors [VoiceInput]: [Manifest.permission.RECORD_AUDIO] must
 * be declared (it is, in AndroidManifest.xml) and granted at runtime;
 * without it [start] reports the problem through [onError] and captures
 * nothing. Use headphones while testing: the mic hears the model's own
 * spoken replies otherwise and the session interrupts itself.
 *
 * Android-only (AudioRecord); there is no JVM test for this file — the
 * framing and lifecycle are tested in GeminiLiveClientTest instead.
 * This file is real capture code, not a simulation: no audio is ever
 * synthesised or replayed.
 */
class GeminiLiveMicStreamer(
    private val context: Context,
    private val onChunk: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {

    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile
    private var running = false

    fun isRunning(): Boolean = running

    fun isMicPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts capture. No-op when already running. Reports a missing
     * permission or an unavailable mic through [onError] instead of
     * throwing.
     */
    fun start() {
        if (running) return
        if (!isMicPermissionGranted()) {
            onError(
                "Microphone permission is not granted — allow it in Settings to use live voice input.",
            )
            return
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            onError("Microphone is not available on this device.")
            return
        }
        val created = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
            )
        } catch (e: SecurityException) {
            onError(
                "Microphone permission is not granted — allow it in Settings to use live voice input.",
            )
            return
        } catch (e: Exception) {
            onError("Could not open the microphone: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            try {
                created.release()
            } catch (_: Exception) {
            }
            onError("Microphone could not be initialised on this device.")
            return
        }
        recorder = created
        running = true
        try {
            created.startRecording()
        } catch (e: Exception) {
            running = false
            releaseRecorder()
            onError("Could not start microphone capture: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        captureThread = Thread({
            // 100 ms of audio per chunk at 16 kHz mono 16-bit.
            val chunk = ByteArray(SAMPLE_RATE_HZ / 10 * 2)
            while (running) {
                val read = try {
                    created.read(chunk, 0, chunk.size)
                } catch (_: Exception) {
                    break
                }
                if (read < 0) break
                if (read > 0) onChunk(chunk.copyOf(read))
            }
        }, "gemini-live-mic").also { it.start() }
    }

    /** Stops capture and frees the mic. Safe to call more than once. */
    fun stop() {
        running = false
        try {
            captureThread?.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        captureThread = null
        releaseRecorder()
    }

    private fun releaseRecorder() {
        val current = recorder
        recorder = null
        try {
            current?.stop()
        } catch (_: Exception) {
        }
        try {
            current?.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Live API input sample rate: 16 kHz mono PCM16. */
        const val SAMPLE_RATE_HZ: Int = 16000
    }
}
