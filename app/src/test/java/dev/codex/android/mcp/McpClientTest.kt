package dev.codex.android.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * JVM unit tests for [McpClient] with a fake OkHttp [Interceptor] that
 * returns canned JSON-RPC payloads. No real network call is ever made:
 * the interceptor short-circuits every request before it leaves the client.
 */
class McpClientTest {

    private data class CannedResponse(
        val code: Int,
        val body: String,
        val contentType: String = "application/json",
        val sessionId: String? = null,
    )

    /**
     * Routes each request to a canned response by the JSON-RPC "method"
     * in its body, and records the Mcp-Session-Id / Authorization headers
     * seen on every request so session handling can be asserted.
     */
    private class CannedInterceptor(
        private val responses: Map<String, CannedResponse>,
    ) : Interceptor {
        val seenSessionIds = mutableListOf<String?>()
        val seenAuthHeaders = mutableListOf<String?>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            val method = runCatching {
                Json.parseToJsonElement(buffer.readUtf8())
                    .jsonObject["method"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            seenSessionIds += request.header("Mcp-Session-Id")
            seenAuthHeaders += request.header("Authorization")
            val canned = responses[method]
                ?: CannedResponse(404, """{"jsonrpc":"2.0","error":{"code":-32601,"message":"unknown method"}}""")
            val builder = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(canned.code)
                .message(if (canned.code == 200) "OK" else "Error")
                // Content-Type must be a real response HEADER: McpClient reads
                // response.header("Content-Type") to detect the SSE envelope, and
                // ResponseBody's media type alone does not populate it.
                .header("Content-Type", canned.contentType)
                .body(canned.body.toResponseBody(canned.contentType.toMediaType()))
            canned.sessionId?.let { builder.header("Mcp-Session-Id", it) }
            return builder.build()
        }
    }

    private val initializeOk = CannedResponse(
        code = 200,
        body = """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{},"serverInfo":{"name":"test-server","version":"1.0"}}}""",
        sessionId = "sess-123",
    )

    private fun clientWith(
        interceptor: CannedInterceptor,
        bearerToken: String? = "test-token",
    ): McpClient {
        val httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        return McpClient("http://localhost:9999/mcp", bearerToken, httpClient)
    }

    @Test
    fun initialize_returnsTrueOnValidResult() {
        val interceptor = CannedInterceptor(mapOf("initialize" to initializeOk))
        val client = clientWith(interceptor)
        assertTrue(runBlocking { client.initialize() })
    }

    @Test
    fun initialize_returnsFalseOnErrorPayload() {
        val error = CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":1,"error":{"code":-32600,"message":"Invalid Request"}}""",
        )
        val interceptor = CannedInterceptor(mapOf("initialize" to error))
        val client = clientWith(interceptor)
        assertFalse(runBlocking { client.initialize() })
    }

    @Test
    fun initialize_sendsBearerTokenAndProtocolVersion() {
        val interceptor = CannedInterceptor(mapOf("initialize" to initializeOk))
        val client = clientWith(interceptor, bearerToken = "secret-abc")
        runBlocking { client.initialize() }
        assertEquals("Bearer secret-abc", interceptor.seenAuthHeaders.single())
    }

    @Test
    fun listTools_parsesCannedToolsList() {
        val toolsList = CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":2,"result":{"tools":[
                |{"name":"read_file","description":"Reads a file","inputSchema":{"type":"object","properties":{"path":{"type":"string"}}}},
                |{"name":"run_shell","description":"Runs a shell command","inputSchema":{"type":"object"}}
                |]}}""".trimMargin(),
        )
        val interceptor = CannedInterceptor(
            mapOf("initialize" to initializeOk, "tools/list" to toolsList)
        )
        val client = clientWith(interceptor)
        val tools = runBlocking { client.listTools("srv-1") }

        assertEquals(2, tools.size)
        assertEquals("read_file", tools[0].name)
        assertEquals("Reads a file", tools[0].description)
        assertEquals("srv-1", tools[0].serverId)
        assertEquals("object", tools[0].inputSchema["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("run_shell", tools[1].name)
        assertEquals("Runs a shell command", tools[1].description)
    }

    @Test
    fun listTools_sendsSessionIdFromInitialize() {
        val toolsList = CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":2,"result":{"tools":[]}}""",
        )
        val interceptor = CannedInterceptor(
            mapOf("initialize" to initializeOk, "tools/list" to toolsList)
        )
        val client = clientWith(interceptor)
        runBlocking { client.listTools("srv-1") }

        // First request (initialize) has no session yet; the tools/list
        // request must carry the Mcp-Session-Id from the initialize response.
        assertEquals(listOf(null, "sess-123"), interceptor.seenSessionIds)
    }

    @Test
    fun listTools_returnsEmptyWhenResultHasNoTools() {
        val toolsList = CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":2,"result":{}}""",
        )
        val interceptor = CannedInterceptor(
            mapOf("initialize" to initializeOk, "tools/list" to toolsList)
        )
        val client = clientWith(interceptor)
        assertTrue(runBlocking { client.listTools("srv-1") }.isEmpty())
    }

    @Test
    fun callTool_returnsJoinedTextContent() {
        val toolCall = CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":2,"result":{"content":[
                |{"type":"text","text":"first "},
                |{"type":"text","text":"second"}
                |],"isError":false}}""".trimMargin(),
        )
        val interceptor = CannedInterceptor(
            mapOf("initialize" to initializeOk, "tools/call" to toolCall)
        )
        val client = clientWith(interceptor)
        val result = runBlocking {
            client.callTool("read_file", buildJsonObject { put("path", "a.txt") })
        }
        assertEquals("first \nsecond", result)
    }

    @Test
    fun callTool_returnsErrorStringOnJsonRpcError() {
        val toolCall = CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"Unknown tool"}}""",
        )
        val interceptor = CannedInterceptor(
            mapOf("initialize" to initializeOk, "tools/call" to toolCall)
        )
        val client = clientWith(interceptor)
        val result = runBlocking { client.callTool("nope", buildJsonObject {}) }
        assertTrue(result.contains("Unknown tool"))
    }

    @Test
    fun callTool_handlesSseEnvelope() {
        val sseBody = "event: message\n" +
            "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"sse hello\"}]}}\n\n"
        val toolCall = CannedResponse(
            code = 200,
            body = sseBody,
            contentType = "text/event-stream",
        )
        val interceptor = CannedInterceptor(
            mapOf("initialize" to initializeOk, "tools/call" to toolCall)
        )
        val client = clientWith(interceptor)
        val result = runBlocking { client.callTool("read_file", buildJsonObject {}) }
        assertEquals("sse hello", result)
    }

    @Test
    fun httpErrorStatus_throwsIOException() {
        val toolCall = CannedResponse(code = 500, body = "Internal Server Error")
        val interceptor = CannedInterceptor(
            mapOf("initialize" to initializeOk, "tools/call" to toolCall)
        )
        val client = clientWith(interceptor)
        assertThrows(IOException::class.java) {
            runBlocking { client.callTool("read_file", buildJsonObject {}) }
        }
    }

    @Test
    fun initialize_httpErrorStatus_throwsIOException() {
        val error = CannedResponse(code = 503, body = "Service Unavailable")
        val interceptor = CannedInterceptor(mapOf("initialize" to error))
        val client = clientWith(interceptor)
        assertThrows(IOException::class.java) {
            runBlocking { client.initialize() }
        }
    }
}
