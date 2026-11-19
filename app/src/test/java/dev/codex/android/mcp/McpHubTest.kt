package dev.codex.android.mcp

import dev.codex.android.data.McpServerEntity
import dev.codex.android.security.SecretScrubber
import dev.codex.android.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/**
 * JVM unit tests for [McpHub] against the REAL [McpManager]
 * (final class) built on a fake [McpServerStore] and canned OkHttp
 * interceptors — the same seam [McpManagerTest] uses. No real
 * network, no Android SQLite, no KeyStore.
 *
 * Rewritten from the staged H5 test, which was uncompilable:
 * `FakeMcpManager : McpManager` cannot exist (the manager is final),
 * it overrode members that do not exist (`httpClient`,
 * `saveBearerToken`, `getBearerToken`, `removeServer`,
 * `saveServer`), and `McpServerEntity` has no `config` parameter.
 * `CodexDatabase.createInMemoryForTest()` and
 * `AgentRuntime.createForTest()` also do not exist, so this test
 * constructs the hub with only the manager (+ a recording
 * `tokenSink`); the hub's `agentRuntime`/`database` parameters are
 * optional and unused by the hub.
 *
 * Behaviours asserted are the staged ones, adapted to the real API:
 * add/list/remove round trip (remote removal is a soft-disable —
 * the real store has no delete — so the assertion is
 * "gone from the enabled list, present-but-disabled in the full
 * list"), secret scrubbing of tool results, plus the new stdio
 * representation and the scrubbing tool adapters.
 */
class McpHubTest {

    private data class CannedResponse(
        val code: Int,
        val body: String,
        val contentType: String = "application/json",
        val sessionId: String? = null,
    )

    /** Canned JSON-RPC responder, copied from the McpManagerTest pattern. */
    private class CannedInterceptor(
        private val responses: Map<String, CannedResponse>,
    ) : Interceptor {
        val seenAuthHeaders = mutableListOf<String?>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            val method = runCatching {
                Json.parseToJsonElement(buffer.readUtf8())
                    .jsonObject["method"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            seenAuthHeaders += request.header("Authorization")
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

    /** In-memory [McpServerStore] — the interface is designed for fakes. */
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

    private fun toolsListResponse(vararg tools: Pair<String, String>): CannedResponse {
        val toolsJson = tools.joinToString(",") { (name, desc) ->
            """{"name":"$name","description":"$desc","inputSchema":{"type":"object","properties":{}}}"""
        }
        return CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":2,"result":{"tools":[$toolsJson]}}""",
        )
    }

    private fun toolCallResponse(text: String): CannedResponse {
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
        return CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"$escaped"}],"isError":false}}""",
        )
    }

    private fun clientFor(url: String, responses: Map<String, CannedResponse>): McpClient {
        val httpClient = OkHttpClient.Builder().addInterceptor(CannedInterceptor(responses)).build()
        return McpClient(url, "test-token", httpClient)
    }

    /** Hub under test: real manager on the fake store, recording tokenSink. */
    private class Fixture(
        clientsByUrl: Map<String, McpClient> = emptyMap(),
    ) {
        val store = FakeMcpServerStore()
        val tokens = mutableMapOf<String, String>()
        val manager = McpManager(
            serverStore = store,
            tokenProvider = { id -> tokens[id] },
            clientFactory = { server, _ -> clientsByUrl.getValue(server.endpointUrl) },
        )
        val hub = McpHub(
            mcpManager = manager,
            tokenSink = { id, token -> tokens[id] = token },
        )
    }

    @Test
    fun addListRemoveServer_roundTrip() = runBlocking {
        val f = Fixture()
        val entity = f.hub.addMcpServer(
            name = "demo-server",
            url = "https://mcp.example.com/sse",
            transport = McpTransport.REMOTE,
            bearerToken = "test-token-123",
        )
        assertEquals("demo-server", entity.name)
        assertEquals("https://mcp.example.com/sse", entity.endpointUrl)
        assertTrue(entity.enabled)
        assertTrue(entity.id.startsWith("mcp_"))
        // The token went to the tokenSink (KeyStore mcp_token_<id> in
        // production), never into SQLite: the stored entity carries no token.
        assertEquals("test-token-123", f.tokens[entity.id])

        val listed = f.hub.listMcpServers()
        assertEquals(1, listed.size)
        assertEquals(entity.id, listed[0].id)

        assertTrue(f.hub.removeMcpServer(entity.id))
        // Hard delete: the server is gone from the full list as well
        // as the enabled list, and a second removal is a no-op.
        assertTrue(f.hub.listMcpServers(onlyEnabled = true).isEmpty())
        assertTrue(f.hub.listMcpServers().isEmpty())
        assertFalse(f.hub.removeMcpServer(entity.id))
    }

    @Test
    fun addMcpServer_tokenWithoutSink_throws() {
        val hub = McpHub(mcpManager = McpManager(FakeMcpServerStore()))
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                hub.addMcpServer("x", "https://mcp.example.com/mcp", bearerToken = "tok")
            }
        }
    }

    @Test
    fun addMcpServer_stdioTransport_rejectedUseAddStdioServer() {
        val f = Fixture()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                f.hub.addMcpServer("local", "npx -y some-server", transport = McpTransport.STDIO)
            }
        }
    }

    @Test
    fun addStdioServer_roundTrip_keptOutOfRemoteRegistry() = runBlocking {
        val f = Fixture()
        val config = f.hub.addStdioServer(
            name = "local-docs",
            command = "npx",
            args = listOf("-y", "@example/mcp-server"),
            env = mapOf("DOCS_ROOT" to "/workspace/docs"),
        )
        assertEquals("local-docs", config.name)
        assertEquals("npx", config.command)
        assertEquals(listOf("-y", "@example/mcp-server"), config.args)
        assertTrue(config.id.startsWith("mcp_stdio_"))

        // Stdio configs never enter the remote registry / DB table.
        assertTrue(f.hub.listMcpServers().isEmpty())
        assertEquals(listOf(config), f.hub.listStdioServers())
        assertEquals(1, f.hub.listAllServers().size)
        assertTrue(f.hub.listAllServers().single() is HubServer.Stdio)

        assertTrue(f.hub.removeMcpServer(config.id))
        assertTrue(f.hub.listStdioServers().isEmpty())
    }

    @Test
    fun callMcpTool_stdioWithoutTransport_throws() {
        val f = Fixture()
        val config = runBlocking { f.hub.addStdioServer("local", "npx") }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { f.hub.callMcpTool(config.id, "search", buildJsonObject {}) }
        }
    }

    @Test
    fun listTools_returnsRemoteToolNames() = runBlocking {
        val url = "https://mcp.example.com/mcp"
        val client = clientFor(
            url,
            mapOf(
                "initialize" to initializeOk,
                "tools/list" to toolsListResponse("search" to "Search docs", "read-file" to "Reads a file"),
            ),
        )
        val f = Fixture(mapOf(url to client))
        val entity = f.hub.addMcpServer("demo", url)
        assertEquals(listOf("search", "read-file"), f.hub.listTools(entity.id))
    }

    @Test
    fun callMcpTool_scrubsSecretsFromResult() = runBlocking {
        val url = "https://mcp.example.com/mcp"
        val leaked = "Result: sk-test-secret-key-12345 and ghp_leakedtoken123"
        val client = clientFor(
            url,
            mapOf(
                "initialize" to initializeOk,
                "tools/call" to toolCallResponse(leaked),
            ),
        )
        val f = Fixture(mapOf(url to client))
        val entity = f.hub.addMcpServer("demo", url)
        val result = f.hub.callMcpTool(entity.id, "search", buildJsonObject {})
        assertFalse(result.contains("sk-test-secret-key-12345"))
        assertFalse(result.contains("ghp_leakedtoken123"))
        assertTrue(result.contains(SecretScrubber.REDACTED))
        assertTrue(result.startsWith("Result:"))
    }

    @Test
    fun registerServerToolsAsTools_registersScrubbedNamespacedTool() = runBlocking {
        val url = "https://mcp.example.com/mcp"
        val client = clientFor(
            url,
            mapOf(
                "initialize" to initializeOk,
                "tools/list" to toolsListResponse("search" to "Search docs"),
                "tools/call" to toolCallResponse("hello"),
            ),
        )
        val f = Fixture(mapOf(url to client))
        val entity = f.hub.addMcpServer("demo", url)
        val registry = ToolRegistry()
        val names = f.hub.registerServerToolsAsTools(entity.id, registry)
        assertEquals(listOf("mcp_demo_search"), names)
        val tool = registry.get("mcp_demo_search")
        assertTrue(tool != null)
        assertTrue(tool!!.sensitive)
        val result = tool.execute(buildJsonObject {})
        assertTrue(result.ok)
        assertEquals("hello", result.output)
    }
}
