package dev.codex.android.mcp

import dev.codex.android.data.McpServerEntity
import dev.codex.android.tools.McpResourceTool
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
 * JVM unit tests for MCP resources: [McpClient.listResources] /
 * [McpClient.readResource] with a fake OkHttp [Interceptor] returning
 * canned JSON-RPC payloads, plus [McpResourceTool] against a real
 * [McpHub] built on a fake store (the McpHubTest pattern). No real
 * network, no Android SQLite.
 */
class McpResourcesTest {

    private data class CannedResponse(
        val code: Int,
        val body: String,
        val contentType: String = "application/json",
        val sessionId: String? = null,
    )

    private class CannedInterceptor(
        private val responses: Map<String, CannedResponse>,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            val method = runCatching {
                Json.parseToJsonElement(buffer.readUtf8())
                    .jsonObject["method"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            val canned = responses[method]
                ?: CannedResponse(404, """{"jsonrpc":"2.0","error":{"code":-32601,"message":"unknown method"}}""")
            val builder = Response.Builder()
                .request(request)
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

    private fun clientWith(responses: Map<String, CannedResponse>): McpClient {
        val httpClient = OkHttpClient.Builder().addInterceptor(CannedInterceptor(responses)).build()
        return McpClient("http://localhost:9999/mcp", "test-token", httpClient)
    }

    private fun hubWith(server: McpServerEntity, responses: Map<String, CannedResponse>): McpHub {
        val client = clientWith(responses)
        val manager = McpManager(
            serverStore = FakeMcpServerStore(listOf(server)),
            clientFactory = { _, _ -> client },
        )
        return McpHub(manager)
    }

    // --- McpClient.listResources ---

    @Test
    fun listResources_parsesCannedResourcesList() {
        val list = CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":2,"result":{"resources":[
                |{"uri":"file:///notes/todo.md","name":"todo.md","mimeType":"text/markdown"},
                |{"uri":"custom://config","name":"config"}
                |]}}""".trimMargin(),
        )
        val client = clientWith(mapOf("initialize" to initializeOk, "resources/list" to list))
        val resources = runBlocking { client.listResources("srv-1") }

        assertEquals(2, resources.size)
        assertEquals("file:///notes/todo.md", resources[0].uri)
        assertEquals("todo.md", resources[0].name)
        assertEquals("text/markdown", resources[0].mimeType)
        assertEquals("srv-1", resources[0].serverId)
        assertEquals("custom://config", resources[1].uri)
        assertEquals("config", resources[1].name)
        assertNull(resources[1].mimeType)
    }

    @Test
    fun listResources_returnsEmptyWhenResultHasNoResources() {
        val list = CannedResponse(code = 200, body = """{"jsonrpc":"2.0","id":2,"result":{}}""")
        val client = clientWith(mapOf("initialize" to initializeOk, "resources/list" to list))
        assertTrue(runBlocking { client.listResources("srv-1") }.isEmpty())
    }

    // --- McpClient.readResource ---

    @Test
    fun readResource_returnsTextContent() {
        val read = CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":2,"result":{"contents":[
                |{"uri":"file:///notes/todo.md","mimeType":"text/markdown","text":"# Todo\n- ship it"}
                |]}}""".trimMargin(),
        )
        val client = clientWith(mapOf("initialize" to initializeOk, "resources/read" to read))
        val content = runBlocking { client.readResource("srv-1", "file:///notes/todo.md") }

        assertEquals("file:///notes/todo.md", content.uri)
        assertEquals("text/markdown", content.mimeType)
        assertEquals("# Todo\n- ship it", content.text)
        assertFalse(content.isBinary)
        assertNull(content.blobBase64)
    }

    @Test
    fun readResource_blobMarksBinary() {
        val read = CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":2,"result":{"contents":[
                |{"uri":"file:///img/logo.png","mimeType":"image/png","blob":"iVBORw0KGgo="}
                |]}}""".trimMargin(),
        )
        val client = clientWith(mapOf("initialize" to initializeOk, "resources/read" to read))
        val content = runBlocking { client.readResource("srv-1", "file:///img/logo.png") }

        assertTrue(content.isBinary)
        assertEquals("iVBORw0KGgo=", content.blobBase64)
        assertNull(content.text)
    }

    @Test
    fun readResource_jsonRpcErrorThrows() {
        val read = CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":2,"error":{"code":-32002,"message":"Resource not found"}}""",
        )
        val client = clientWith(mapOf("initialize" to initializeOk, "resources/read" to read))
        assertThrows(IOException::class.java) {
            runBlocking { client.readResource("srv-1", "file:///nope.md") }
        }
    }

    @Test
    fun readResource_emptyContentsThrows() {
        val read = CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":2,"result":{"contents":[]}}""",
        )
        val client = clientWith(mapOf("initialize" to initializeOk, "resources/read" to read))
        assertThrows(IOException::class.java) {
            runBlocking { client.readResource("srv-1", "file:///empty.md") }
        }
    }

    // --- McpResourceTool via McpHub ---

    private val docsServer = McpServerEntity("s1", "docs", "https://docs.example.com/mcp", true)

    private fun resourcesListResponse(): CannedResponse = CannedResponse(
        code = 200,
        body = """{"jsonrpc":"2.0","id":2,"result":{"resources":[
            |{"uri":"file:///notes/todo.md","name":"todo.md","mimeType":"text/markdown"}
            |]}}""".trimMargin(),
    )

    @Test
    fun tool_listWithoutUri_returnsUriNameAndMimeType() {
        val hub = hubWith(
            docsServer,
            mapOf("initialize" to initializeOk, "resources/list" to resourcesListResponse()),
        )
        val tool = McpResourceTool(hub)
        val result = runBlocking {
            tool.execute(buildJsonObject { put("server_id", "s1") })
        }

        assertTrue(result.ok)
        assertTrue(result.output.contains("file:///notes/todo.md"))
        assertTrue(result.output.contains("todo.md"))
        assertTrue(result.output.contains("text/markdown"))
    }

    @Test
    fun tool_listEmpty_reportsNoResources() {
        val hub = hubWith(
            docsServer,
            mapOf(
                "initialize" to initializeOk,
                "resources/list" to CannedResponse(
                    code = 200,
                    body = """{"jsonrpc":"2.0","id":2,"result":{"resources":[]}}""",
                ),
            ),
        )
        val tool = McpResourceTool(hub)
        val result = runBlocking {
            tool.execute(buildJsonObject { put("server_id", "s1") })
        }

        assertTrue(result.ok)
        assertTrue(result.output.contains("exposes no resources"))
    }

    @Test
    fun tool_readWithUri_returnsText() {
        val read = CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":2,"result":{"contents":[
                |{"uri":"file:///notes/todo.md","mimeType":"text/markdown","text":"hello resource"}
                |]}}""".trimMargin(),
        )
        val hub = hubWith(docsServer, mapOf("initialize" to initializeOk, "resources/read" to read))
        val tool = McpResourceTool(hub)
        val result = runBlocking {
            tool.execute(buildJsonObject {
                put("server_id", "s1")
                put("uri", "file:///notes/todo.md")
            })
        }

        assertTrue(result.ok)
        assertTrue(result.output.contains("hello resource"))
    }

    @Test
    fun tool_readBinary_reportsErrorInsteadOfDumping() {
        val read = CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":2,"result":{"contents":[
                |{"uri":"file:///img/logo.png","mimeType":"image/png","blob":"iVBORw0KGgo="}
                |]}}""".trimMargin(),
        )
        val hub = hubWith(docsServer, mapOf("initialize" to initializeOk, "resources/read" to read))
        val tool = McpResourceTool(hub)
        val result = runBlocking {
            tool.execute(buildJsonObject {
                put("server_id", "s1")
                put("uri", "file:///img/logo.png")
            })
        }

        assertFalse(result.ok)
        assertTrue(result.error?.contains("binary") == true)
        assertFalse(result.error?.contains("iVBORw0KGgo=") == true)
    }

    @Test
    fun tool_readLongText_isCappedWithTruncationNote() {
        val longText = "x".repeat(60_000)
        val read = CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":2,"result":{"contents":[
                |{"uri":"file:///big.txt","mimeType":"text/plain","text":"$longText"}
                |]}}""".trimMargin(),
        )
        val hub = hubWith(docsServer, mapOf("initialize" to initializeOk, "resources/read" to read))
        val tool = McpResourceTool(hub)
        val result = runBlocking {
            tool.execute(buildJsonObject {
                put("server_id", "s1")
                put("uri", "file:///big.txt")
            })
        }

        assertTrue(result.ok)
        assertTrue(result.output.contains("resource truncated at ${McpResourceTool.MAX_READ_CHARS} chars"))
        assertTrue(result.output.length <= McpResourceTool.MAX_READ_CHARS + 200)
    }

    @Test
    fun tool_unknownServer_isAnError() {
        val hub = hubWith(docsServer, mapOf("initialize" to initializeOk))
        val tool = McpResourceTool(hub)
        val result = runBlocking {
            tool.execute(buildJsonObject { put("server_id", "no-such-server") })
        }

        assertFalse(result.ok)
        assertTrue(result.error?.contains("Unknown MCP server") == true)
    }

    @Test
    fun tool_missingServerId_isAnError() {
        val hub = hubWith(docsServer, mapOf("initialize" to initializeOk))
        val tool = McpResourceTool(hub)
        val result = runBlocking { tool.execute(buildJsonObject {}) }

        assertFalse(result.ok)
        assertTrue(result.error?.contains("server_id") == true)
    }

    @Test
    fun tool_isSensitiveAndNamed() {
        val hub = hubWith(docsServer, mapOf("initialize" to initializeOk))
        val tool = McpResourceTool(hub)
        assertEquals("mcp-resource", tool.name)
        assertTrue(tool.sensitive)
    }
}
