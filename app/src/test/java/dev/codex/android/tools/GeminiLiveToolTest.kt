package dev.codex.android.tools

import dev.codex.android.api.GeminiLiveClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [GeminiLiveTool] with a fake WebSocket: tool identity,
 * no-key / no-session / mic-unwired errors, and one full start →
 * send_text → stop round-trip driven by scripted server messages.
 */
class GeminiLiveToolTest {

    private class FakeWebSocket(private val listener: WebSocketListener) : WebSocket {
        val sent = mutableListOf<String>()
        override fun request(): Request = Request.Builder().url(GeminiLiveClient.LIVE_WS_URL).build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean {
            sent += text
            return true
        }

        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}

        fun open() {
            listener.onOpen(
                this,
                Response.Builder().request(request()).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build(),
            )
            listener.onMessage(this, """{"setupComplete": {}}""")
        }

        fun message(text: String) = listener.onMessage(this, text)
    }

    private class Harness(apiKey: String = "test-gemini-key") {
        val sockets = mutableListOf<FakeWebSocket>()
        val tool = GeminiLiveTool(
            apiKey = apiKey,
            httpClient = OkHttpClient(),
            socketFactory = { _, listener -> FakeWebSocket(listener).also { sockets += it } },
            micStarter = null,
        )

        fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
            pairs.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Boolean -> put(key, value)
                    is Int -> put(key, value)
                    is Long -> put(key, value)
                }
            }
        }
    }

    @Test
    fun tool_identity() {
        val tool = GeminiLiveTool(apiKey = "k")
        assertEquals("gemini_live", tool.name)
        assertFalse(tool.sensitive)
        assertTrue(tool.parametersSchema.containsKey("properties"))
        assertTrue(tool.description.contains("Gemini Live"))
    }

    @Test
    fun start_withoutKey_failsAndOpensNothing() = runBlocking {
        val h = Harness(apiKey = "")
        val result = h.tool.execute(h.args("action" to "start"))
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("API key"))
        assertTrue(h.sockets.isEmpty())
    }

    @Test
    fun sendText_withoutSession_fails() = runBlocking {
        val h = Harness()
        val result = h.tool.execute(h.args("action" to "send_text", "text" to "hello"))
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("No live session"))
    }

    @Test
    fun unknownAction_fails() = runBlocking {
        val h = Harness()
        val result = h.tool.execute(h.args("action" to "dance"))
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("Unknown action"))
    }

    @Test
    fun start_withMicButNoMicSource_failsHonestly() = runBlocking {
        val h = Harness()
        val result = h.tool.execute(h.args("action" to "start", "mic" to true))
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("Mic streaming is not wired"))
        assertTrue(h.sockets.isEmpty())
    }

    @Test
    fun stop_withoutSession_reportsNothingOpen() = runBlocking {
        val h = Harness()
        val result = h.tool.execute(h.args("action" to "stop"))
        assertTrue(result.ok)
        assertTrue(result.output.contains("No live session was open"))
    }

    @Test
    fun fullRoundTrip_startSendStop() = runBlocking {
        val h = Harness()
        val started = h.tool.execute(h.args("action" to "start", "model" to "gemini-3.8-live", "voice" to "Kore"))
        assertTrue(started.error ?: "expected start to succeed", started.ok)
        assertEquals(1, h.sockets.size)
        val socket = h.sockets[0]
        socket.open()
        // Drive the server's reply while send_text waits for turn completion.
        launch {
            delay(50)
            socket.message("""{"serverContent": {"modelTurn": {"parts": [{"text": "Hello back"}]}}}""")
            socket.message("""{"serverContent": {"outputTranscription": {"text": "Hello back"}}}""")
            socket.message("""{"serverContent": {"turnComplete": true}}""")
        }
        val reply = h.tool.execute(h.args("action" to "send_text", "text" to "Hello", "timeout_ms" to 5000))
        assertTrue(reply.error ?: "expected a reply", reply.ok)
        assertTrue(reply.output.contains("Hello back"))
        assertTrue(socket.sent.any { it.contains("Hello") })
        val status = h.tool.execute(h.args("action" to "status"))
        assertTrue(status.output.contains("OPEN"))
        assertTrue(status.output.contains("Hello back"))
        val stopped = h.tool.execute(h.args("action" to "stop"))
        assertTrue(stopped.ok)
        assertTrue(stopped.output.contains("Live session closed"))
        assertTrue(stopped.output.contains("Hello back"))
    }

    @Test
    fun sendText_timeoutReturnsWhatArrived() = runBlocking {
        val h = Harness()
        h.tool.execute(h.args("action" to "start"))
        h.sockets[0].open()
        launch {
            delay(50)
            h.sockets[0].message("""{"serverContent": {"modelTurn": {"parts": [{"text": "partial"}]}}}""")
            // No turnComplete: send_text must time out instead of hanging.
        }
        val reply = h.tool.execute(h.args("action" to "send_text", "text" to "Hi", "timeout_ms" to 1000))
        assertTrue(reply.ok)
        assertTrue(reply.output.contains("partial"))
        assertTrue(reply.output.contains("No turn completion within 1000ms"))
        val stopped = h.tool.execute(h.args("action" to "stop"))
        assertTrue(stopped.ok)
    }
}
