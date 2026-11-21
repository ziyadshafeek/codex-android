package dev.codex.android.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * JVM tests for [GeminiLiveClient] with a fake WebSocket: message
 * framing (setup / realtime input / client content), server-message
 * parsing, and the session lifecycle (open → setup → reconnect →
 * close). No network, no Android APIs, no microphone.
 */
class GeminiLiveClientTest {

    /** Fake WebSocket the test drives by hand. */
    private class FakeWebSocket(private val listener: WebSocketListener) : WebSocket {
        val sent = mutableListOf<String>()
        var closeCode: Int? = null
        var cancelled = false

        override fun request(): Request = Request.Builder().url(GeminiLiveClient.LIVE_WS_URL).build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean {
            sent += text
            return true
        }

        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean {
            closeCode = code
            return true
        }

        override fun cancel() {
            cancelled = true
        }

        fun deliverOpen() {
            listener.onOpen(this, Response.Builder().request(request()).protocol(okhttp3.Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
        }

        fun deliverMessage(text: String) = listener.onMessage(this, text)
        fun deliverFailure(message: String) =
            listener.onFailure(this, RuntimeException(message), null)

        fun deliverClosed(code: Int, reason: String) = listener.onClosed(this, code, reason)
    }

    private class Harness(
        apiKey: String = "test-gemini-key",
        maxReconnectAttempts: Int = 3,
    ) {
        val events = mutableListOf<LiveServerEvent>()
        val states = mutableListOf<LiveSessionState>()
        val errors = mutableListOf<String>()
        val sockets = mutableListOf<FakeWebSocket>()
        val requests = mutableListOf<Request>()

        val client = GeminiLiveClient(
            apiKey = apiKey,
            httpClient = OkHttpClient(),
            socketFactory = { request, listener ->
                requests += request
                FakeWebSocket(listener).also { sockets += it }
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            maxReconnectAttempts = maxReconnectAttempts,
            reconnectDelayMs = { 0L },
            listener = object : GeminiLiveClient.Listener {
                override fun onEvent(event: LiveServerEvent) {
                    synchronized(events) { events += event }
                }

                override fun onStateChange(state: LiveSessionState) {
                    synchronized(states) { states += state }
                }

                override fun onError(message: String) {
                    synchronized(errors) { errors += message }
                }
            },
        )

        fun openSession(model: String = GeminiLiveClient.DEFAULT_MODEL) {
            client.connect(GeminiLiveConfig(model = model, voice = "Kore", systemInstruction = "Be helpful."))
            sockets.last().deliverOpen()
            sockets.last().deliverMessage("""{"setupComplete": {}}""")
        }
    }

    private fun parse(text: String): JsonObject =
        dev.codex.android.core.CodexJson.parseToJsonElement(text) as JsonObject

    @Test
    fun buildSetupMessage_hasModelVoiceAndInstruction() {
        val raw = GeminiLiveClient.buildSetupMessage(
            GeminiLiveConfig(model = "gemini-3.8-live", voice = "Kore", systemInstruction = "Be helpful."),
        )
        val setup = parse(raw)["setup"]!!.jsonObject
        assertEquals("models/gemini-3.8-live", setup["model"]!!.jsonPrimitive.content)
        val generation = setup["generationConfig"]!!.jsonObject
        assertEquals("AUDIO", generation["responseModalities"]!!.jsonArray[0].jsonPrimitive.content)
        val speechConfig = generation["speechConfig"]!!.jsonObject
        val voiceConfig = speechConfig["voiceConfig"]!!.jsonObject
        val prebuilt = voiceConfig["prebuiltVoiceConfig"]!!.jsonObject
        val voiceName = prebuilt["voiceName"]!!.jsonPrimitive.content
        assertEquals("Kore", voiceName)
        val instruction = setup["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0]
            .jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("Be helpful.", instruction)
        assertTrue(setup.containsKey("inputAudioTranscription"))
        assertTrue(setup.containsKey("outputAudioTranscription"))
    }

    @Test
    fun buildSetupMessage_omitsVoiceWhenNull() {
        val raw = GeminiLiveClient.buildSetupMessage(GeminiLiveConfig(model = "gemini-3.5-transcribe-live"))
        val setup = parse(raw)["setup"]!!.jsonObject
        assertFalse(setup["generationConfig"]!!.jsonObject.containsKey("speechConfig"))
    }

    @Test
    fun buildRealtimeAudioInput_isBase64Pcm16() {
        val pcm = byteArrayOf(1, 0, 2, 0, 3, 0)
        val raw = GeminiLiveClient.buildRealtimeAudioInput(pcm)
        val audio = parse(raw)["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        assertEquals(Base64.getEncoder().encodeToString(pcm), audio["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun buildRealtimeTextInput_andAudioStreamEnd_andClientContent() {
        val textRaw = parse(GeminiLiveClient.buildRealtimeTextInput("hello"))
        assertEquals("hello", textRaw["realtimeInput"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        val endRaw = parse(GeminiLiveClient.buildAudioStreamEnd())
        assertEquals("true", endRaw["realtimeInput"]!!.jsonObject["audioStreamEnd"]!!.jsonPrimitive.content)
        val ccRaw = parse(GeminiLiveClient.buildClientContent("context", turnComplete = true))
        val turns = ccRaw["clientContent"]!!.jsonObject["turns"]!!.jsonArray
        assertEquals("user", turns[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("true", ccRaw["clientContent"]!!.jsonObject["turnComplete"]!!.jsonPrimitive.content)
    }

    @Test
    fun parseServerMessage_modelTurnTextAndAudio() {
        val textEvent = GeminiLiveClient.parseServerMessage(
            """{"serverContent": {"modelTurn": {"parts": [{"text": "Hi there"}]}}}""",
        )
        assertEquals(LiveServerEvent.ModelText("Hi there"), textEvent)
        val audioEvent = GeminiLiveClient.parseServerMessage(
            """{"serverContent": {"modelTurn": {"parts": [{"inlineData": {"mimeType": "audio/pcm;rate=24000", "data": "AAEC"}}]}}}""",
        )
        assertEquals(LiveServerEvent.ModelAudioPcm24("AAEC", "audio/pcm;rate=24000"), audioEvent)
    }

    @Test
    fun parseServerMessage_transcriptionsTurnCompleteInterrupted() {
        assertEquals(
            LiveServerEvent.InputTranscript("hello", final = true),
            GeminiLiveClient.parseServerMessage("""{"serverContent": {"inputTranscription": {"text": "hello"}}}"""),
        )
        assertEquals(
            LiveServerEvent.InputTranscript("hel", final = false),
            GeminiLiveClient.parseServerMessage("""{"serverContent": {"interimInputTranscription": {"text": "hel"}}}"""),
        )
        assertEquals(
            LiveServerEvent.OutputTranscript("hi"),
            GeminiLiveClient.parseServerMessage("""{"serverContent": {"outputTranscription": {"text": "hi"}}}"""),
        )
        assertEquals(
            LiveServerEvent.TurnComplete,
            GeminiLiveClient.parseServerMessage("""{"serverContent": {"turnComplete": true}}"""),
        )
        assertEquals(
            LiveServerEvent.Interrupted,
            GeminiLiveClient.parseServerMessage("""{"serverContent": {"interrupted": true}}"""),
        )
        assertEquals(
            LiveServerEvent.SetupComplete,
            GeminiLiveClient.parseServerMessage("""{"setupComplete": {}}"""),
        )
        assertEquals(
            LiveServerEvent.GoAway("PT30S"),
            GeminiLiveClient.parseServerMessage("""{"goAway": {"timeLeft": "PT30S"}}"""),
        )
        assertEquals(
            LiveServerEvent.InteractionStatus("IDLE"),
            GeminiLiveClient.parseServerMessage("""{"serverContent": {"interactionStatus": "IDLE"}}"""),
        )
    }

    @Test
    fun parseServerMessage_unknownShapeNeverThrows() {
        val event = GeminiLiveClient.parseServerMessage("""{"weird": [1, 2, 3]}""")
        assertTrue(event is LiveServerEvent.Unknown)
        val garbage = GeminiLiveClient.parseServerMessage("not json at all")
        assertTrue(garbage is LiveServerEvent.Unknown)
    }

    @Test
    fun connect_sendsSetupAndOpensOnSetupComplete() {
        val h = Harness()
        h.client.connect(GeminiLiveConfig(model = "gemini-3.8-live", voice = "Kore"))
        assertEquals(LiveSessionState.CONNECTING, h.client.state)
        val socket = h.sockets.last()
        // OkHttp stores ws(s) URLs as http(s) in Request.url, so assert
        // the https form of the BidiGenerateContent endpoint.
        assertEquals(
            "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
            h.requests.last().url.toString(),
        )
        assertEquals("test-gemini-key", h.requests.last().header("x-goog-api-key"))
        socket.deliverOpen()
        assertEquals(1, socket.sent.size)
        val setup = parse(socket.sent[0])["setup"]!!.jsonObject
        assertEquals("models/gemini-3.8-live", setup["model"]!!.jsonPrimitive.content)
        socket.deliverMessage("""{"setupComplete": {}}""")
        assertEquals(LiveSessionState.OPEN, h.client.state)
        assertTrue(h.states.contains(LiveSessionState.OPEN))
    }

    @Test
    fun sendText_sendsRealtimeInputOnlyWhenOpen() {
        val h = Harness()
        assertFalse(h.client.sendText("too early"))
        h.openSession()
        assertTrue(h.client.sendText("hello"))
        val sent = parse(h.sockets.last().sent.last())
        assertEquals("hello", sent["realtimeInput"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(h.client.sendAudioPcm16(byteArrayOf(0, 1, 0, 2)))
        assertTrue(h.client.sendAudioStreamEnd())
        assertFalse(h.client.sendText("   "))
    }

    @Test
    fun serverEvents_reachListener() {
        val h = Harness()
        h.openSession()
        h.sockets.last().deliverMessage("""{"serverContent": {"modelTurn": {"parts": [{"text": "Hi"}]}}}""")
        h.sockets.last().deliverMessage("""{"serverContent": {"outputTranscription": {"text": "Hi"}}}""")
        h.sockets.last().deliverMessage("""{"serverContent": {"turnComplete": true}}""")
        assertTrue(h.events.contains(LiveServerEvent.ModelText("Hi")))
        assertTrue(h.events.contains(LiveServerEvent.OutputTranscript("Hi")))
        assertTrue(h.events.contains(LiveServerEvent.TurnComplete))
    }

    @Test
    fun unexpectedClose_reconnectsAndResendsSetup() = runBlocking {
        val h = Harness()
        h.openSession()
        assertEquals(LiveSessionState.OPEN, h.client.state)
        h.sockets.last().deliverFailure("network dropped")
        // Reconnect is async with a 0ms delay in this harness; wait for it.
        var waited = 0
        while (h.sockets.size < 2 && waited < 2000) {
            kotlinx.coroutines.delay(10)
            waited += 10
        }
        assertEquals(2, h.sockets.size)
        assertTrue(h.states.contains(LiveSessionState.RECONNECTING))
        h.sockets.last().deliverOpen()
        assertEquals(1, h.sockets.last().sent.size)
        assertTrue(h.sockets.last().sent[0].contains("setup"))
        h.sockets.last().deliverMessage("""{"setupComplete": {}}""")
        assertEquals(LiveSessionState.OPEN, h.client.state)
    }

    @Test
    fun reconnectAttempts_exhaustedBecomesFailed() = runBlocking {
        val h = Harness(maxReconnectAttempts = 1)
        h.openSession()
        h.sockets.last().deliverFailure("drop 1")
        var waited = 0
        while (h.sockets.size < 2 && waited < 2000) {
            kotlinx.coroutines.delay(10)
            waited += 10
        }
        assertEquals(2, h.sockets.size)
        h.sockets.last().deliverFailure("drop 2")
        waited = 0
        while (h.client.state != LiveSessionState.FAILED && waited < 2000) {
            kotlinx.coroutines.delay(10)
            waited += 10
        }
        assertEquals(LiveSessionState.FAILED, h.client.state)
        assertTrue(h.errors.isNotEmpty())
        assertEquals(2, h.sockets.size) // No further reconnects.
    }

    @Test
    fun close_stopsReconnectsAndReportsClosed() = runBlocking {
        val h = Harness()
        h.openSession()
        h.client.close()
        assertEquals(LiveSessionState.CLOSED, h.client.state)
        h.sockets.last().deliverFailure("late failure after close")
        kotlinx.coroutines.delay(50)
        assertEquals(1, h.sockets.size)
        assertEquals(LiveSessionState.CLOSED, h.client.state)
    }

    @Test
    fun blankKey_failsWithoutOpeningSocket() {
        val h = Harness(apiKey = "   ")
        h.client.connect(GeminiLiveConfig())
        assertEquals(LiveSessionState.FAILED, h.client.state)
        assertTrue(h.sockets.isEmpty())
        assertTrue(h.errors.any { it.contains("API key") })
    }

    @Test
    fun cleanClose_reportsClosed() {
        val h = Harness()
        h.openSession()
        h.sockets.last().deliverClosed(1000, "")
        assertEquals(LiveSessionState.CLOSED, h.client.state)
    }
}
