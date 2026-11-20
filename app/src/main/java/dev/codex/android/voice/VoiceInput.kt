package dev.codex.android.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * Voice input via the platform [SpeechRecognizer] (M8, Subsystem 8 of the
 * Codex Mobile Studio master plan).
 *
 * - Zero API keys, zero app network calls: recognition is done entirely by
 *   the device's own speech-recognition service. (On most devices that
 *   service is the Google app, which may send audio to Google's servers —
 *   that is the platform recognizer's own behaviour, not a call this app
 *   makes, and there is no BYOK/cloud key involved.)
 * - Degrades gracefully: if no recognizer exists on the device, if the
 *   microphone permission is not granted, or if recognition fails, the
 *   [Listener.onError] callback fires with a plain-language message and
 *   nothing crashes. The recognizer is never left running: it is created
 *   fresh per session and destroyed on result, error, or [destroy].
 *
 * NOTE: [Manifest.permission.RECORD_AUDIO] must be declared in
 * AndroidManifest.xml and granted at runtime for this to hear anything.
 * Until that lands, [startListening] reports the missing permission via
 * [Listener.onError] instead of crashing — the mic button stays usable
 * and the chat keeps working.
 */
class VoiceInput(
    private val context: Context,
    private val listener: Listener,
) {

    interface Listener {
        /** Live interim transcript while the user is still speaking. */
        fun onPartialResult(partial: String)

        /** Final transcript for one utterance. */
        fun onFinalResult(transcript: String)

        /** Human-readable failure; listening has stopped. Never crashes. */
        fun onError(message: String)

        /** True while the recognizer is actively listening. */
        fun onListeningChanged(listening: Boolean)
    }

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var stopRequested = false
    private var destroyed = false

    /** True when the device has any speech-recognition service at all. */
    fun isRecognitionAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(context)
    } catch (_: Exception) {
        false
    }

    fun isListening(): Boolean = listening

    /**
     * Starts one listening session. Safe to call repeatedly: a second call
     * while already listening is a no-op. Failures (no recognizer, no
     * microphone permission, recognizer error) are reported via
     * [Listener.onError], never thrown.
     */
    fun startListening() {
        if (destroyed || listening) return
        if (!isRecognitionAvailable()) {
            listener.onError("Speech recognition is not available on this device.")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            listener.onError(
                "Microphone permission is not granted — allow it in Settings to use voice input."
            )
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            val created = SpeechRecognizer.createSpeechRecognizer(context)
            if (created == null) {
                listener.onError("Speech recognition is not available on this device.")
                return
            }
            stopRequested = false
            created.setRecognitionListener(RecognitionListenerImpl())
            created.startListening(intent)
            recognizer = created
            listening = true
            listener.onListeningChanged(true)
        } catch (e: SecurityException) {
            // Permission revoked between the check and startListening.
            releaseRecognizer()
            listener.onError(
                "Microphone permission is not granted — allow it in Settings to use voice input."
            )
        } catch (e: Exception) {
            releaseRecognizer()
            listener.onError("Could not start voice input: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Ends the current session; any speech so far is delivered as a final result. */
    fun stopListening() {
        if (!listening) return
        stopRequested = true
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
            // A dead recognizer is already stopped, but it is still
            // holding its resources: release it here (onResults/onError
            // will not fire for a dead recognizer), so the next
            // startListening() does not create a second recognizer
            // while this one still holds the mic.
            releaseRecognizer()
            listener.onListeningChanged(false)
        }
    }

    /** Cancels any session and frees the recognizer. Safe to call more than once. */
    fun destroy() {
        destroyed = true
        listening = false
        releaseRecognizer()
    }

    private fun releaseRecognizer() {
        val current = recognizer
        recognizer = null
        listening = false
        try {
            current?.cancel()
        } catch (_: Exception) {
            // Already dead; nothing to cancel.
        }
        try {
            current?.destroy()
        } catch (_: Exception) {
            // Already destroyed; nothing to free.
        }
    }

    private fun firstResult(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private inner class RecognitionListenerImpl : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (destroyed) return
            firstResult(partialResults)?.let { listener.onPartialResult(it) }
        }

        override fun onResults(results: Bundle?) {
            if (destroyed) return
            val transcript = firstResult(results).orEmpty()
            releaseRecognizer()
            if (transcript.isNotBlank()) listener.onFinalResult(transcript)
            listener.onListeningChanged(false)
        }

        override fun onError(error: Int) {
            if (destroyed) return
            // stopListening() on a session with nothing usable can surface
            // as a client error; the user asked to stop, so stay silent.
            val userStopped = stopRequested && error == SpeechRecognizer.ERROR_CLIENT
            stopRequested = false
            releaseRecognizer()
            if (userStopped) {
                listener.onListeningChanged(false)
                return
            }
            listener.onError(errorMessage(error))
            listener.onListeningChanged(false)
        }
    }

    companion object {
        fun errorMessage(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_AUDIO ->
                "Audio recording error — check the microphone and try again."
            SpeechRecognizer.ERROR_CLIENT ->
                "Voice input was interrupted — try again."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Microphone permission is not granted — allow it in Settings to use voice input."
            SpeechRecognizer.ERROR_NETWORK ->
                "Speech recognition needs a network connection right now — try again."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Speech recognition timed out — try again."
            SpeechRecognizer.ERROR_NO_MATCH ->
                "Didn't catch that — try speaking again."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                "Speech recognition is busy — try again in a moment."
            SpeechRecognizer.ERROR_SERVER ->
                "Speech recognition failed on the device — try again."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                "Didn't hear anything — try speaking again."
            else -> "Voice input failed (error $error) — try again."
        }
    }
}
