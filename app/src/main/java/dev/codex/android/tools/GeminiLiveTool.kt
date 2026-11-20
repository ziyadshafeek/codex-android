package dev.codex.android.tools

import android.content.Context
import dev.codex.android.api.GeminiLiveClient
import dev.codex.android.api.GeminiLiveConfig
import dev.codex.android.api.LiveServerEvent
import dev.codex.android.api.LiveSessionState
import dev.codex.android.core.CodexJson
import dev.codex.android.data.KeyStore
import dev.codex.android.voice.GeminiLiveMicStreamer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Minimal handle for a mic capture source feeding a live session. */
interface LiveMicHandle {
    fun start()
    fun stop()
}

/**
 * Live voice/text session with the Gemini Live API (`name = "gemini_live"`).
 *
 * One tool instance holds at most one session at a time; the agent drives
 * it across turns: `action=start` opens the WebSocket, `action=send_text`
 * sends one message and waits for the reply (up to `timeout_ms`),
 * `action=status` reports the state and transcript so far, and
 * `action=stop` closes the session and returns the full transcript.
 *
 * BYOK only: the Gemini API key comes from the constructor (the
 * coordinator passes the user's own Settings key) — never baked in, and
 * a blank key fails before any socket is opened. Mic streaming needs a
 * [micStarter] (the coordinator wires [GeminiLiveMicStreamer] with the
 * app Context); without one, `mic=true` fails with a plain message
 * instead of pretending to hear anything. Model speech arrives as
 * 24 kHz PCM audio plus a live transcript; this tool returns the
 * transcript text (it does not play audio) and reports how many audio
 * bytes arrived, so a reply is never claimed from audio that was not
 * transcribed.
 */
class GeminiLiveTool(
    private val apiKey: String,
    private val httpClient: OkHttpClient = GeminiLiveClient.defaultHttpClient(),
    private val socketFactory: (Request, WebSocketListener) -> WebSocket =
        { request, listener -> httpClient.newWebSocket(request, listener) },
    private val micStarter: ((onChunk: (ByteArray) -> Unit, onError: (String) -> Unit) -> LiveMicHandle)? = null,
) : Tool {

    /**
     * Convenience constructor wired to the app's settings: the key comes
     * from [KeyStore], and mic capture is the real AudioRecord streamer.
     */
    constructor(
        context: Context,
        httpClient: OkHttpClient = GeminiLiveClient.defaultHttpClient(),
    ) : this(
        apiKey = KeyStore(context.applicationContext).getApiKey() ?: "",
        httpClient = httpClient,
        micStarter = { onChunk, onError ->
            val streamer = GeminiLiveMicStreamer(context.applicationContext, onChunk, onError)
            object : LiveMicHandle {
                override fun start() = streamer.start()
                override fun stop() = streamer.stop()
            }
        },
    )

    override val name: String = "gemini_live"
    override val description: String =
        "Runs a real-time Gemini Live API session (voice + text over WebSocket) with the user's own Gemini API key. " +
            "Actions: start (open a session: model, voice, system_instruction, mic), send_text (send one message and wait for the reply), " +
            "status (session state and transcript so far), stop (close and return the full transcript). " +
            "Returns live transcriptions; never claims a reply that did not arrive."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "start | send_text | status | stop")
            }
            putJsonObject("model") {
                put("type", "string")
                put("description", "Live model: gemini-3.8-live (default), gemini-3.8-live-extended-thinking, gemini-3.5-transcribe-live")
            }
            putJsonObject("voice") {
                put("type", "string")
                put("description", "Optional prebuilt voice name, e.g. Kore")
            }
            putJsonObject("system_instruction") {
                put("type", "string")
                put("description", "Optional role instruction for the session")
            }
            putJsonObject("response_modality") {
                put("type", "string")
                put("description", "AUDIO (default) or TEXT — only one modality per session")
            }
            putJsonObject("text") {
                put("type", "string")
                put("description", "Message text for action=send_text")
            }
            putJsonObject("mic") {
                put("type", "boolean")
                put("description", "true to stream the microphone into the session (needs mic permission; only on action=start)")
            }
            putJsonObject("timeout_ms") {
                put("type", "integer")
                put("description", "How long send_text waits for a reply (default 15000, max 60000)")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("action")) }
    }
    override val sensitive: Boolean = false

    private val lock = Any()
    private var client: GeminiLiveClient? = null
    private var mic: LiveMicHandle? = null
    private var micWanted = false
    private val transcript = StringBuilder()
    private var modelAudioBytes = 0L
    @Volatile
    private var pendingTurn: CompletableDeferred<Unit>? = null

    private val liveListener = object : GeminiLiveClient.Listener {
        override fun onEvent(event: LiveServerEvent) {
            when (event) {
                is LiveServerEvent.SetupComplete -> Unit
                is LiveServerEvent.ModelText -> appendLine("Gemini: ${event.text}")
                is LiveServerEvent.ModelAudioPcm24 -> {
                    val bytes = runCatching {
                        java.util.Base64.getDecoder().decode(event.base64Data.replace("\\s".toRegex(), ""))
                    }.getOrNull()?.size ?: 0
                    modelAudioBytes += bytes
                    appendLine("Gemini spoke (${bytes} bytes of audio).")
                }
                is LiveServerEvent.InputTranscript -> appendLine(
                    "You (${if (event.final) "final" else "interim"}): ${event.text}",
                )
                is LiveServerEvent.OutputTranscript -> appendLine("Gemini: ${event.text}")
                is LiveServerEvent.TurnComplete -> completeTurn()
                is LiveServerEvent.InteractionStatus -> {
                    if (event.status == "IDLE") completeTurn()
                }
                is LiveServerEvent.Interrupted -> appendLine("[Gemini was interrupted.]")
                is LiveServerEvent.GoAway -> appendLine("[Server is ending the session${event.timeLeft?.let { " in $it" } ?: ""}.]")
                is LiveServerEvent.SessionResumption -> appendLine(
                    "[Session resumption handle received${if (event.resumable == true) "" else " (not resumable)"}.]",
                )
                is LiveServerEvent.Error -> appendLine("[Live error: ${event.message}]")
                is LiveServerEvent.ToolCall -> appendLine("[Gemini requested a function call; this tool does not execute live function calls.]")
                is LiveServerEvent.Usage -> Unit
                is LiveServerEvent.Unknown -> Unit
            }
        }

        override fun onStateChange(state: LiveSessionState) {
            if (state == LiveSessionState.OPEN && micWanted) {
                try {
                    mic?.start()
                } catch (_: Exception) {
                }
            }
            if (state == LiveSessionState.FAILED || state == LiveSessionState.CLOSED) {
                completeTurn()
            }
        }

        override fun onError(message: String) {
            appendLine("[Live error: $message]")
            completeTurn()
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return@withContext ToolResult(ok = false, output = "", error = "Missing required argument: action")
        when (action) {
            "start" -> startSession(args)
            "send_text" -> sendText(args)
            "status" -> ToolResult(ok = true, output = statusText())
            "stop" -> stopSession()
            else -> ToolResult(
                ok = false,
                output = "",
                error = "Unknown action \"$action\": expected start, send_text, status or stop",
            )
        }
    }

    private fun startSession(args: JsonObject): ToolResult {
        synchronized(lock) {
            if (client != null) {
                return ToolResult(
                    ok = false,
                    output = "",
                    error = "A live session is already open — use action=stop first.",
                )
            }
        }
        if (apiKey.isBlank()) {
            return ToolResult(ok = false, output = "", error = "No Gemini API key configured: add your own Gemini API key in Settings first. This tool never calls anything without your own (BYOK) key.")
        }
        val micRequested = (args["mic"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
        if (micRequested && micStarter == null) {
            return ToolResult(
                ok = false,
                output = "",
                error = "Mic streaming is not wired in this build: no microphone source was supplied to gemini_live. Start the session with mic=false for text, or wire a mic source first.",
            )
        }
        val model = args["model"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() } ?: GeminiLiveClient.DEFAULT_MODEL
        val config = GeminiLiveConfig(
            model = model,
            voice = args["voice"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
            systemInstruction = args["system_instruction"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() },
            responseModality = args["response_modality"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it in setOf("AUDIO", "TEXT") } ?: "AUDIO",
            enableInputTranscription = true,
            enableOutputTranscription = true,
        )
        val liveClient = GeminiLiveClient(
            apiKey = apiKey,
            httpClient = httpClient,
            socketFactory = socketFactory,
            listener = liveListener,
        )
        synchronized(lock) {
            client = liveClient
            transcript.clear()
            modelAudioBytes = 0L
            micWanted = micRequested
            mic = if (micRequested) {
                micStarter?.invoke(
                    { pcm -> liveClient.sendAudioPcm16(pcm) },
                    { message -> appendLine("[Mic error: $message]") },
                )
            } else {
                null
            }
        }
        liveClient.connect(config)
        return ToolResult(
            ok = true,
            output = "Live session starting (model $model, modality ${config.responseModality}" +
                (if (micRequested) ", mic streaming on" else ", text only") +
                "). Use action=status to check it opened, then action=send_text.",
        )
    }

    private suspend fun sendText(args: JsonObject): ToolResult {
        val liveClient = synchronized(lock) { client }
            ?: return ToolResult(ok = false, output = "", error = "No live session is open — use action=start first.")
        if (liveClient.state != LiveSessionState.OPEN) {
            return ToolResult(
                ok = false,
                output = "",
                error = "Live session is ${liveClient.state} (not open)${liveClient.lastError?.let { ": $it" } ?: ""}. Use action=status to check, or action=stop and start a fresh session.",
            )
        }
        val text = args["text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: text")
        val timeoutMs = ((args["timeout_ms"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?: DEFAULT_SEND_TIMEOUT_MS).coerceIn(1_000L, 60_000L)
        val mark = synchronized(lock) { transcript.length }
        appendLine("You: $text")
        val turn = CompletableDeferred<Unit>()
        pendingTurn = turn
        if (!liveClient.sendText(text)) {
            pendingTurn = null
            return ToolResult(ok = false, output = "", error = "Could not send text: the live session is no longer open.")
        }
        val finished = withTimeoutOrNull(timeoutMs) { turn.await(); true } ?: false
        val fresh = synchronized(lock) { transcript.substring(mark) }.trim()
        val suffix = if (finished) "" else "\n[No turn completion within ${timeoutMs}ms — the reply may still be arriving; use action=status to check.]"
        return ToolResult(
            ok = true,
            output = (fresh + suffix).trim().ifBlank { "[No reply arrived yet.]" },
        )
    }

    private fun stopSession(): ToolResult {
        val liveClient = synchronized(lock) { client }
        try {
            mic?.stop()
        } catch (_: Exception) {
        }
        liveClient?.close()
        val full = statusText()
        synchronized(lock) {
            client = null
            mic = null
            micWanted = false
        }
        return ToolResult(
            ok = true,
            output = if (liveClient == null) "No live session was open." else "Live session closed.\n$full",
        )
    }

    private fun statusText(): String {
        val liveClient = synchronized(lock) { client }
        val state = liveClient?.state?.name ?: "no session"
        val body = synchronized(lock) { transcript.toString() }.trim()
        return buildString {
            appendLine("Live session state: $state")
            if (modelAudioBytes > 0) appendLine("Model audio received: $modelAudioBytes bytes")
            liveClient?.lastError?.let { appendLine("Last error: $it") }
            append(if (body.isBlank()) "Transcript: (empty)" else "Transcript:\n$body")
        }
    }

    private fun appendLine(line: String) {
        synchronized(lock) { transcript.appendLine(line) }
    }

    private fun completeTurn() {
        pendingTurn?.complete(Unit)
        pendingTurn = null
    }

    companion object {
        const val DEFAULT_SEND_TIMEOUT_MS: Long = 15_000L
    }
}
