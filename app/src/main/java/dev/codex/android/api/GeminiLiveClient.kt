package dev.codex.android.api

import dev.codex.android.core.CodexJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Real-time client for the Gemini Live API (BidiGenerateContent WebSocket).
 *
 * BYOK only: the caller supplies the user's own Gemini API key; no key is
 * ever baked in and none is stored here. The key is sent as the
 * `x-goog-api-key` handshake header (the same header the Gemini REST API
 * uses). If the server ever rejects the handshake, that header is the
 * first thing to re-check against the live docs — the endpoint URL and
 * the wire shape below are the documented BidiGenerateContent protocol
 * and have NOT yet been verified against a live call from this app.
 *
 * The socket transport is injected ([socketFactory]) so JVM tests can
 * drive the whole lifecycle with a fake socket and no network. Audio
 * capture lives separately in [dev.codex.android.voice.GeminiLiveMicStreamer]
 * (Android AudioRecord); this client only moves PCM bytes.
 *
 * Reconnects are real and bounded: on an unexpected close/failure the
 * client re-opens the socket and re-sends `setup`, up to
 * [maxReconnectAttempts] times with exponential back-off, and reports
 * every attempt through [Listener.onStateChange]. It never pretends a
 * dead session is alive: after the attempts are spent the state is
 * [LiveSessionState.FAILED] with the last error recorded.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val socketFactory: (Request, WebSocketListener) -> WebSocket =
        { request, wsListener -> httpClient.newWebSocket(request, wsListener) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxReconnectAttempts: Int = 3,
    private val reconnectDelayMs: (attempt: Int) -> Long = { attempt ->
        minOf(500L shl attempt, 8_000L)
    },
    private val listener: Listener? = null,
) {

    interface Listener {
        fun onEvent(event: LiveServerEvent)
        fun onStateChange(state: LiveSessionState)
        fun onError(message: String)
    }

    @Volatile
    var state: LiveSessionState = LiveSessionState.IDLE
        private set

    /** Last failure message, or null when no failure has happened yet. */
    @Volatile
    var lastError: String? = null
        private set

    private var socket: WebSocket? = null
    private var config: GeminiLiveConfig? = null
    private var reconnectAttempt = 0
    private var userClosed = false
    private var setupConfirmed = false
    private var reconnectJob: Job? = null

    /**
     * Opens the Live session. Reports failures through [Listener.onError]
     * and [state] — never throws for a missing key or a refused socket.
     * Safe to call again after [close] or a failure.
     */
    fun connect(config: GeminiLiveConfig) {
        if (state == LiveSessionState.CONNECTING || state == LiveSessionState.OPEN ||
            state == LiveSessionState.RECONNECTING
        ) return
        if (apiKey.isBlank()) {
            fail("No Gemini API key configured: add your own Gemini API key in Settings first. This client never calls anything without your own (BYOK) key.")
            return
        }
        this.config = config
        userClosed = false
        reconnectAttempt = 0
        setupConfirmed = false
        openSocket()
    }

    /** Sends one text turn into the live session. Returns false when not open. */
    fun sendText(text: String): Boolean {
        if (state != LiveSessionState.OPEN) return false
        if (text.isBlank()) return false
        return sendRaw(buildRealtimeTextInput(text))
    }

    /** Streams one chunk of 16 kHz mono 16-bit PCM audio. Returns false when not open. */
    fun sendAudioPcm16(pcm: ByteArray): Boolean {
        if (state != LiveSessionState.OPEN) return false
        if (pcm.isEmpty()) return false
        return sendRaw(buildRealtimeAudioInput(pcm))
    }

    /** Tells the server the user has stopped speaking (client-side VAD end). */
    fun sendAudioStreamEnd(): Boolean {
        if (state != LiveSessionState.OPEN) return false
        return sendRaw(buildAudioStreamEnd())
    }

    /**
     * Injects a full conversation turn mid-session (explicit user/model
     * roles). `turnComplete=true` unconditionally interrupts generation.
     */
    fun sendClientContent(text: String, turnComplete: Boolean = true): Boolean {
        if (state != LiveSessionState.OPEN) return false
        if (text.isBlank()) return false
        return sendRaw(buildClientContent(text, turnComplete))
    }

    /** Closes the session and cancels any pending reconnect. Idempotent. */
    fun close() {
        userClosed = true
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            socket?.close(1000, "client close")
        } catch (_: Exception) {
        }
        socket = null
        setState(LiveSessionState.CLOSED)
    }

    private fun openSocket() {
        setState(
            if (reconnectAttempt == 0) LiveSessionState.CONNECTING else LiveSessionState.RECONNECTING,
        )
        val request = Request.Builder()
            .url(LIVE_WS_URL)
            .header("x-goog-api-key", apiKey)
            .build()
        try {
            socket = socketFactory(request, LiveSocketListener())
        } catch (e: Exception) {
            onSocketFailure(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun sendRaw(text: String): Boolean = try {
        socket?.send(text) ?: false
    } catch (_: Exception) {
        false
    }

    private fun onSocketOpened() {
        val cfg = config ?: return
        setupConfirmed = false
        // The setup message goes out immediately; OPEN is only reported
        // once the server answers setupComplete (see the listener).
        sendRaw(buildSetupMessage(cfg))
    }

    private fun onSocketFailure(message: String) {
        socket = null
        if (userClosed) {
            setState(LiveSessionState.CLOSED)
            return
        }
        lastError = message
        if (reconnectAttempt < maxReconnectAttempts) {
            reconnectAttempt++
            val waitMs = reconnectDelayMs(reconnectAttempt)
            listener?.onError(
                "Live session dropped ($message). Reconnecting (attempt $reconnectAttempt/$maxReconnectAttempts in ${waitMs}ms)…",
            )
            setState(LiveSessionState.RECONNECTING)
            reconnectJob = scope.launch {
                delay(waitMs)
                if (!userClosed) openSocket()
            }
        } else {
            fail("Live session failed after $maxReconnectAttempts reconnect attempts: $message")
        }
    }

    private fun fail(message: String) {
        lastError = message
        setState(LiveSessionState.FAILED)
        listener?.onError(message)
    }

    private fun setState(next: LiveSessionState) {
        state = next
        listener?.onStateChange(next)
    }

    private inner class LiveSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            onSocketOpened()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = parseServerMessage(text)
            when (event) {
                is LiveServerEvent.SetupComplete -> {
                    setupConfirmed = true
                    reconnectAttempt = 0
                    setState(LiveSessionState.OPEN)
                }
                is LiveServerEvent.Error -> {
                    lastError = event.message
                    listener?.onError(event.message)
                }
                else -> Unit
            }
            listener?.onEvent(event)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // The Live API speaks text frames; a binary frame is unexpected
            // but not fatal — surface it and keep the session.
            listener?.onEvent(LiveServerEvent.Unknown(bytes.hex()))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (userClosed || code == 1000) {
                socket = null
                setState(LiveSessionState.CLOSED)
            } else {
                onSocketFailure("socket closed (code $code): ${reason.ifBlank { "no reason given" }}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onSocketFailure(t.message ?: t.javaClass.simpleName)
        }
    }

    companion object {
        /**
         * BidiGenerateContent WebSocket endpoint. Taken from the public
         * Live API documentation, NOT from the research files (they do not
         * name the URL) — verify against a live call before treating it
         * as settled.
         */
        const val LIVE_WS_URL: String =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        const val DEFAULT_MODEL: String = "gemini-3.8-live"
        const val TRANSCRIBE_MODEL: String = "gemini-3.5-transcribe-live"
        const val EXTENDED_THINKING_MODEL: String = "gemini-3.8-live-extended-thinking"

        /** Input audio the server accepts: raw PCM, 16-bit little-endian, mono, 16 kHz. */
        const val INPUT_AUDIO_MIME_TYPE: String = "audio/pcm;rate=16000"

        /** Output audio the server sends: raw PCM, 16-bit little-endian, mono, 24 kHz. */
        const val OUTPUT_AUDIO_MIME_TYPE: String = "audio/pcm;rate=24000"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // A live session stays open indefinitely.
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /** `{"setup": {...}}` — the first message of every session. */
        fun buildSetupMessage(config: GeminiLiveConfig): String {
            val root = buildJsonObject {
                putJsonObject("setup") {
                    put("model", "models/${config.model}")
                    putJsonObject("generationConfig") {
                        putJsonArray("responseModalities") {
                            add(JsonPrimitive(config.responseModality))
                        }
                        if (config.voice != null) {
                            putJsonObject("speechConfig") {
                                putJsonObject("voiceConfig") {
                                    putJsonObject("prebuiltVoiceConfig") {
                                        put("voiceName", config.voice)
                                    }
                                }
                            }
                        }
                    }
                    if (config.systemInstruction != null) {
                        putJsonObject("systemInstruction") {
                            putJsonArray("parts") {
                                add(buildJsonObject { put("text", config.systemInstruction) })
                            }
                        }
                    }
                    if (config.enableInputTranscription) {
                        putJsonObject("inputAudioTranscription") {
                            if (config.transcriptionMode != null) put("mode", config.transcriptionMode)
                        }
                    }
                    if (config.enableOutputTranscription) {
                        putJsonObject("outputAudioTranscription") {}
                    }
                }
            }
            return CodexJson.encodeToString(JsonObject.serializer(), root)
        }

        /** `{"realtimeInput": {"text": ...}}` — live text input. */
        fun buildRealtimeTextInput(text: String): String =
            CodexJson.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    putJsonObject("realtimeInput") { put("text", text) }
                },
            )

        /** `{"realtimeInput": {"audio": {"mimeType": ..., "data": base64}}}`. */
        fun buildRealtimeAudioInput(pcm16: ByteArray): String {
            val base64 = Base64.getEncoder().encodeToString(pcm16)
            return CodexJson.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    putJsonObject("realtimeInput") {
                        putJsonObject("audio") {
                            put("mimeType", INPUT_AUDIO_MIME_TYPE)
                            put("data", base64)
                        }
                    }
                },
            )
        }

        /** `{"realtimeInput": {"audioStreamEnd": true}}` — client-side VAD end. */
        fun buildAudioStreamEnd(): String =
            CodexJson.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    putJsonObject("realtimeInput") { put("audioStreamEnd", true) }
                },
            )

        /** `{"clientContent": {"turns": [...], "turnComplete": ...}}`. */
        fun buildClientContent(text: String, turnComplete: Boolean = true): String =
            CodexJson.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    putJsonObject("clientContent") {
                        putJsonArray("turns") {
                            add(buildJsonObject {
                                put("role", "user")
                                putJsonArray("parts") {
                                    add(buildJsonObject { put("text", text) })
                                }
                            })
                        }
                        put("turnComplete", turnComplete)
                    }
                },
            )

        /**
         * Parses one server message into a [LiveServerEvent]. One event
         * can carry several parts at once (audio + transcript); every
         * part is reported — the caller must not assume one event means
         * one part. Unknown shapes become [LiveServerEvent.Unknown],
         * never an exception.
         */
        fun parseServerMessage(raw: String): LiveServerEvent {
            val root = runCatching { CodexJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: return LiveServerEvent.Unknown(raw)
            if (root.containsKey("setupComplete")) return LiveServerEvent.SetupComplete
            root["goAway"]?.let { goAway ->
                val obj = goAway as? JsonObject
                val timeLeft = (obj?.get("timeLeft") as? JsonPrimitive)?.contentOrNull
                return LiveServerEvent.GoAway(timeLeft)
            }
            root["sessionResumptionUpdate"]?.let { update ->
                val obj = update as? JsonObject
                val handle = (obj?.get("newHandle") as? JsonPrimitive)?.contentOrNull
                val resumable = (obj?.get("resumable") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
                return LiveServerEvent.SessionResumption(handle, resumable)
            }
            (root["interactionStatus"] as? JsonPrimitive)?.contentOrNull?.let {
                return LiveServerEvent.InteractionStatus(it)
            }
            root["serverContent"]?.let { contentEl ->
                val content = contentEl as? JsonObject ?: return LiveServerEvent.Unknown(raw)
                (content["interactionStatus"] as? JsonPrimitive)?.contentOrNull?.let {
                    return LiveServerEvent.InteractionStatus(it)
                }
                if ((content["interrupted"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() == true) {
                    return LiveServerEvent.Interrupted
                }
                if ((content["turnComplete"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() == true) {
                    return LiveServerEvent.TurnComplete
                }
                (content["inputTranscription"] as? JsonObject)?.let { t ->
                    (t["text"] as? JsonPrimitive)?.contentOrNull?.let { text ->
                        return LiveServerEvent.InputTranscript(text, final = true)
                    }
                }
                (content["interimInputTranscription"] as? JsonObject)?.let { t ->
                    (t["text"] as? JsonPrimitive)?.contentOrNull?.let { text ->
                        return LiveServerEvent.InputTranscript(text, final = false)
                    }
                }
                (content["outputTranscription"] as? JsonObject)?.let { t ->
                    (t["text"] as? JsonPrimitive)?.contentOrNull?.let { text ->
                        return LiveServerEvent.OutputTranscript(text)
                    }
                }
                (content["modelTurn"] as? JsonObject)?.let { turn ->
                    val parts = turn["parts"] as? JsonArray ?: return LiveServerEvent.Unknown(raw)
                    for (partEl in parts) {
                        val part = partEl as? JsonObject ?: continue
                        (part["text"] as? JsonPrimitive)?.contentOrNull?.let { text ->
                            return LiveServerEvent.ModelText(text)
                        }
                        (part["inlineData"] as? JsonObject)?.let { inline ->
                            val data = (inline["data"] as? JsonPrimitive)?.contentOrNull
                            val mime = (inline["mimeType"] as? JsonPrimitive)?.contentOrNull
                            if (data != null) return LiveServerEvent.ModelAudioPcm24(data, mime)
                        }
                    }
                }
                return LiveServerEvent.Unknown(raw)
            }
            root["toolCall"]?.let { return LiveServerEvent.ToolCall(raw) }
            root["usageMetadata"]?.let { return LiveServerEvent.Usage(raw) }
            return LiveServerEvent.Unknown(raw)
        }
    }
}

/** Session parameters for [GeminiLiveClient.connect]. */
data class GeminiLiveConfig(
    val model: String = GeminiLiveClient.DEFAULT_MODEL,
    /** Prebuilt voice name (e.g. "Kore"); null keeps the server default. */
    val voice: String? = null,
    val systemInstruction: String? = null,
    /** "AUDIO" or "TEXT" — only one modality per session. */
    val responseModality: String = "AUDIO",
    val enableInputTranscription: Boolean = true,
    val enableOutputTranscription: Boolean = true,
    /** Transcription mode for the transcribe model: "smart" or "verbatim". */
    val transcriptionMode: String? = null,
)

/** Lifecycle of a [GeminiLiveClient] session. */
enum class LiveSessionState {
    IDLE, CONNECTING, RECONNECTING, OPEN, CLOSED, FAILED,
}

/** One parsed server message. See [GeminiLiveClient.parseServerMessage]. */
sealed interface LiveServerEvent {
    object SetupComplete : LiveServerEvent
    data class ModelText(val text: String) : LiveServerEvent
    /** Base64 24 kHz mono PCM audio from the model. */
    data class ModelAudioPcm24(val base64Data: String, val mimeType: String?) : LiveServerEvent
    data class InputTranscript(val text: String, val final: Boolean) : LiveServerEvent
    data class OutputTranscript(val text: String) : LiveServerEvent
    object TurnComplete : LiveServerEvent
    /** The model was interrupted — clear any queued playback. */
    object Interrupted : LiveServerEvent
    /** Extended-thinking lifecycle: "IN_PROGRESS" or "IDLE". */
    data class InteractionStatus(val status: String) : LiveServerEvent
    /** Server is ending the session; `timeLeft` is an ISO-8601 duration when present. */
    data class GoAway(val timeLeft: String?) : LiveServerEvent
    /** Handle for resuming this session after a drop; null when absent. */
    data class SessionResumption(val newHandle: String?, val resumable: Boolean?) : LiveServerEvent
    /** A function-call request from the model (raw JSON; tool execution is the caller's job). */
    data class ToolCall(val rawJson: String) : LiveServerEvent
    data class Usage(val rawJson: String) : LiveServerEvent
    data class Error(val message: String) : LiveServerEvent
    /** A message shape this client does not understand yet (raw text kept). */
    data class Unknown(val raw: String) : LiveServerEvent
}
