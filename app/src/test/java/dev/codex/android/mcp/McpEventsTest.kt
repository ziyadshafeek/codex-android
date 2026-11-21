package dev.codex.android.mcp

import dev.codex.android.data.McpServerEntity
import dev.codex.android.tools.McpEventsTool
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * JVM unit tests for MCP events: [McpEvent.parseNotification], the
 * opportunistic SSE notification capture in [McpClient], the pure
 * [diffNameSets] helper, and [McpEventsTool] (poll-based change
 * detection) against a real [McpHub] built on a fake store. No real
 * network, no Android SQLite, no live server.
 *
 * What is deliberately NOT asserted: live push delivery. The client
 * holds no persistent stream, so a notification can only ever arrive
 * interleaved in an SSE response body — that capture path is what is
 * tested here.
 */
class McpEventsTest {

    private data class CannedResponse(
        val code: Int,
        val body: String,
        val contentType: String = "application/json",
        val sessionId: String? = null,
    )

    /** Serves a fixed response per JSON-RPC method. */
    private class CannedInterceptor(
        private val responses: Map<String, CannedResponse>,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response =
            respond(chain, responses[chainMethod(chain)] ?: errorResponse())

        private fun chainMethod(chain: Interceptor.Chain): String? {
            val buffer = Buffer()
            chain.request().body?.writeTo(buffer)
            return runCatching {
                Json.parseToJsonElement(buffer.readUtf8())
                    .jsonObject["method"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }

        private fun errorResponse() = CannedResponse(
            404,
            """{"jsonrpc":"2.0","error":{"code":-32601,"message":"unknown method"}}""",
        )

        private fun respond(chain: Interceptor.Chain, canned: CannedResponse): Response {
            val builder = Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(canned.code)
                .message(if (canned.code == 200) "OK" else "Error")
                .header("Content-Type", canned.contentType)
                .body(canned.body.toResponseBody(canned.contentType.toMediaType()))
            canned.sessionId?.let { builder.header("Mcp-Session-Id", it) }
            return builder.build()
        }
    }

    /**
     * Serves a different canned response per method on each successive
     * call (repeating the last one once the sequence is exhausted), so a
     * tools/list that gains a tool between polls can be simulated.
     */
    private class SequencedInterceptor(
        private val sequences: Map<String, List<CannedResponse>>,
    ) : Interceptor {
        private val counters = HashMap<String, Int>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val buffer = Buffer()
            chain.request().body?.writeTo(buffer)
            val method = runCatching {
                Json.parseToJsonElement(buffer.readUtf8())
                    .jsonObject["method"]?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: return errorResponse(chain, null)
            val seq = sequences[method]
                ?: return errorResponse(chain, method)
            val idx = counters.getOrDefault(method, 0)
            counters[method] = idx + 1
            val canned = seq[minOf(idx, seq.lastIndex)]
            return respond(chain, canned)
        }

        private fun errorResponse(chain: Interceptor.Chain, method: String?): Response {
            val canned = CannedResponse(
                404,
                """{"jsonrpc":"2.0","error":{"code":-32601,"message":"unknown method $method"}}""",
            )
            return respond(chain, canned)
        }

        private fun respond(chain: Interceptor.Chain, canned: CannedResponse): Response {
            val builder = Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(canned.code)
                .message(if (canned.code == 200) "OK" else "Error")
                .header("Content-Type", canned.contentType)
                .body(canned.body.toResponseBody(canned.contentType.toMediaType()))
            canned.sessionId?.let { builder.header("Mcp-Session-Id", it) }
            return builder.build()
        }
    }

    private class FakeMcpServerStore(initial: List<McpServerEntity> = emptyList()) : McpServerStore {
        val servers = initial.toMutableList()

        override suspend fun listServers(onlyEnabled: Boolean): List<McpServerEntity> =
            if (onlyEnabled) servers.filter { it.enabled } else servers.toList()

        override suspend fun saveServer(server: McpServerEntity) {
            servers.removeAll { it.id == server.id }
            servers += server
        }

        override suspend fun deleteServer(serverId: String): Boolean =
            servers.removeAll { it.id == serverId }
    }

    private val initializeOk = CannedResponse(
        code = 200,
        body = """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{},"serverInfo":{"name":"test-server","version":"1.0"}}}""",
        sessionId = "sess-1",
    )

    private fun toolsListResponse(vararg names: String): CannedResponse {
        val toolsJson = names.joinToString(",") { name ->
            """{"name":"$name","description":"Tool $name","inputSchema":{"type":"object"}}"""
        }
        return CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":2,"result":{"tools":[$toolsJson]}}""",
        )
    }

    private val emptyResources = CannedResponse(
        code = 200,
        body = """{"jsonrpc":"2.0","id":2,"result":{"resources":[]}}""",
    )

    private fun clientWith(responses: Map<String, CannedResponse>): McpClient {
        val httpClient = OkHttpClient.Builder().addInterceptor(CannedInterceptor(responses)).build()
        return McpClient("http://localhost:9999/mcp", "test-token", httpClient)
    }

    private fun hubWith(
        servers: List<McpServerEntity>,
        interceptor: Interceptor,
    ): McpHub {
        val httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val manager = McpManager(
            serverStore = FakeMcpServerStore(servers),
            clientFactory = { server, token -> McpClient(server.endpointUrl, token, httpClient) },
        )
        return McpHub(manager)
    }

    // --- McpEvent.parseNotification ---

    @Test
    fun parseNotification_acceptsMethodWithoutId() {
        val event = McpEvent.parseNotification(
            "srv-1",
            """{"jsonrpc":"2.0","method":"notifications/tools/list_changed","params":{"x":1}}""",
            nowMillis = 5_000L,
        )
        assertEquals("srv-1", event?.serverId)
        assertEquals("notifications/tools/list_changed", event?.method)
        assertEquals(5_000L, event?.receivedAtMillis)
        assertTrue(event?.summary?.contains("x") == true)
    }

    @Test
    fun parseNotification_rejectsResponseWithId() {
        assertNull(
            McpEvent.parseNotification(
                "srv-1",
                """{"jsonrpc":"2.0","id":2,"result":{"tools":[]}}""",
            )
        )
    }

    @Test
    fun parseNotification_rejectsMalformedAndMethodless() {
        assertNull(McpEvent.parseNotification("srv-1", "not json"))
        assertNull(McpEvent.parseNotification("srv-1", """{"jsonrpc":"2.0","params":{}}"""))
        assertNull(McpEvent.parseNotification("srv-1", """{"jsonrpc":"2.0","method":"  "}"""))
    }

    @Test
    fun parseNotification_capsLongParamsSummary() {
        val longParams = "x".repeat(2_000)
        val event = McpEvent.parseNotification(
            "srv-1",
            """{"jsonrpc":"2.0","method":"notifications/progress","params":{"data":"$longParams"}}""",
        )
        assertTrue(event!!.summary.length <= McpEvent.MAX_SUMMARY_CHARS + 20)
        assertTrue(event.summary.endsWith("…[truncated]"))
    }

    // --- diffNameSets ---

    @Test
    fun diffNameSets_reportsAddedAndRemovedSorted() {
        val (added, removed) = diffNameSets(setOf("b", "a"), setOf("b", "c"))
        assertEquals(listOf("c"), added)
        assertEquals(listOf("a"), removed)
    }

    @Test
    fun diffNameSets_emptyOnIdenticalSets() {
        val (added, removed) = diffNameSets(setOf("a"), setOf("a"))
        assertTrue(added.isEmpty())
        assertTrue(removed.isEmpty())
    }

    // --- Opportunistic SSE capture in McpClient ---

    @Test
    fun sseResponse_notificationLineIsRecordedAndResponseStillParsed() {
        val sseBody = "event: message\n" +
            "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\",\"params\":{}}\n\n" +
            "event: message\n" +
            "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"search\",\"description\":\"Search\",\"inputSchema\":{\"type\":\"object\"}}]}}\n\n"
        val toolsList = CannedResponse(code = 200, body = sseBody, contentType = "text/event-stream")
        val client = clientWith(mapOf("initialize" to initializeOk, "tools/list" to toolsList))

        val tools = runBlocking { client.listTools("srv-1") }
        assertEquals(listOf("search"), tools.map { it.name })

        val events = client.recentNotifications()
        assertEquals(1, events.size)
        assertEquals("srv-1", events[0].serverId)
        assertEquals("notifications/tools/list_changed", events[0].method)
    }

    @Test
    fun sseResponse_onlyNotificationAndNoResponse_throws() {
        val sseBody = "event: message\n" +
            "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\",\"params\":{}}\n\n"
        val toolsList = CannedResponse(code = 200, body = sseBody, contentType = "text/event-stream")
        val client = clientWith(mapOf("initialize" to initializeOk, "tools/list" to toolsList))

        assertThrows(IOException::class.java) {
            runBlocking { client.listTools("srv-1") }
        }
        // The notification was still recorded before the failure surfaced.
        assertEquals(1, client.recentNotifications().size)
    }

    @Test
    fun sseResponse_trailingNotificationDoesNotShadowResponse() {
        val sseBody = "event: message\n" +
            "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}\n\n" +
            "event: message\n" +
            "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/resources/updated\",\"params\":{\"uri\":\"file:///a.md\"}}\n\n"
        val toolsList = CannedResponse(code = 200, body = sseBody, contentType = "text/event-stream")
        val client = clientWith(mapOf("initialize" to initializeOk, "tools/list" to toolsList))

        assertTrue(runBlocking { client.listTools("srv-1") }.isEmpty())
        assertEquals(listOf("notifications/resources/updated"), client.recentNotifications().map { it.method })
    }

    // --- McpEventsTool (poll-based diff) via McpHub ---

    private val docsServer = McpServerEntity("s1", "docs", "https://docs.example.com/mcp", true)

    @Test
    fun tool_noServers_reportsEmpty() {
        val hub = hubWith(emptyList(), CannedInterceptor(mapOf("initialize" to initializeOk)))
        val tool = McpEventsTool(hub)
        val result = runBlocking { tool.execute(buildJsonObject {}) }

        assertTrue(result.ok)
        assertEquals("No MCP servers registered.", result.output)
    }

    @Test
    fun tool_firstCheck_recordsBaseline_secondCheckDiffsAddedTool() {
        val interceptor = SequencedInterceptor(
            mapOf(
                "initialize" to listOf(initializeOk),
                "tools/list" to listOf(toolsListResponse("search"), toolsListResponse("search", "fetch")),
                "resources/list" to listOf(emptyResources),
            ),
        )
        val hub = hubWith(listOf(docsServer), interceptor)
        val tool = McpEventsTool(hub)

        val first = runBlocking { tool.execute(buildJsonObject { put("server_id", "s1") }) }
        assertTrue(first.ok)
        assertTrue(first.output.contains("Baseline recorded"))
        assertFalse(first.output.contains("tools added"))

        val second = runBlocking { tool.execute(buildJsonObject { put("server_id", "s1") }) }
        assertTrue(second.ok)
        assertTrue(second.output.contains("tools added: fetch"))
        assertFalse(second.output.contains("Baseline recorded"))
    }

    @Test
    fun tool_unchangedServer_reportsNoChanges() {
        val interceptor = SequencedInterceptor(
            mapOf(
                "initialize" to listOf(initializeOk),
                "tools/list" to listOf(toolsListResponse("search")),
                "resources/list" to listOf(emptyResources),
            ),
        )
        val hub = hubWith(listOf(docsServer), interceptor)
        val tool = McpEventsTool(hub)

        runBlocking { tool.execute(buildJsonObject {}) }
        val second = runBlocking { tool.execute(buildJsonObject {}) }
        assertTrue(second.ok)
        assertTrue(second.output.contains("No tool/resource changes since the last check."))
    }

    @Test
    fun tool_capturedNotificationAppearsInNextCheck() {
        val sseTools = CannedResponse(
            code = 200,
            body = "event: message\n" +
                "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\",\"params\":{}}\n\n" +
                "event: message\n" +
                "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"search\",\"description\":\"Search\",\"inputSchema\":{\"type\":\"object\"}}]}}\n\n",
            contentType = "text/event-stream",
        )
        val interceptor = SequencedInterceptor(
            mapOf(
                "initialize" to listOf(initializeOk),
                "tools/list" to listOf(sseTools),
                "resources/list" to listOf(emptyResources),
            ),
        )
        val hub = hubWith(listOf(docsServer), interceptor)
        val tool = McpEventsTool(hub)

        // The first check's own tools/list call carries the notification;
        // it is drained on the following check.
        runBlocking { tool.execute(buildJsonObject {}) }
        val second = runBlocking { tool.execute(buildJsonObject {}) }
        assertTrue(second.ok)
        assertTrue(second.output.contains("notifications/tools/list_changed"))
    }

    @Test
    fun tool_unknownServer_isAnError() {
        val hub = hubWith(listOf(docsServer), CannedInterceptor(mapOf("initialize" to initializeOk)))
        val tool = McpEventsTool(hub)
        val result = runBlocking {
            tool.execute(buildJsonObject { put("server_id", "no-such-server") })
        }

        assertFalse(result.ok)
        assertTrue(result.error?.contains("Unknown MCP server") == true)
    }

    @Test
    fun tool_descriptionStatesPollBasedLimit() {
        val hub = hubWith(emptyList(), CannedInterceptor(mapOf("initialize" to initializeOk)))
        val tool = McpEventsTool(hub)
        assertEquals("mcp-events", tool.name)
        assertTrue(tool.sensitive)
        assertTrue(tool.description.contains("POLL-BASED, not live push"))
    }
}
