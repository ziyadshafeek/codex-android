package dev.codex.android.mcp

import dev.codex.android.data.McpServerEntity
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
 * JVM unit tests for [McpManager] with a fake [McpServerStore] and fake
 * OkHttp [Interceptor]s returning canned JSON-RPC payloads. No real
 * network call is ever made and no Android SQLite is touched: the
 * manager is constructed against the fake store, and each canned client
 * short-circuits every request before it leaves the client.
 */
class McpManagerTest {

    private data class CannedResponse(
        val code: Int,
        val body: String,
        val contentType: String = "application/json",
        val sessionId: String? = null,
    )

    /** Same routing idea as McpClientTest's interceptor, per server URL. */
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

    private fun clientFor(responses: Map<String, CannedResponse>): McpClient {
        val interceptor = CannedInterceptor(responses)
        val httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        return McpClient("http://localhost:9999/mcp", "test-token", httpClient)
    }

    private fun managerFor(
        store: FakeMcpServerStore,
        clientsByUrl: Map<String, McpClient> = emptyMap(),
        tokenProvider: (String) -> String? = { null },
    ): McpManager = McpManager(
        serverStore = store,
        tokenProvider = tokenProvider,
        clientFactory = { server, _ -> clientsByUrl.getValue(server.endpointUrl) },
    )

    // --- Registry ---

    @Test
    fun addServer_persistsRemoteServerToStore() {
        val store = FakeMcpServerStore()
        val manager = McpManager(store)
        val entity = runBlocking {
            manager.addServer("docs", "https://mcp.example.com/mcp")
        }
        assertTrue(entity.id.startsWith("mcp_"))
        assertEquals("docs", entity.name)
        assertEquals("https://mcp.example.com/mcp", entity.endpointUrl)
        assertTrue(entity.enabled)
        assertEquals(listOf(entity), store.servers)
    }

    @Test
    fun addServer_rejectsNonHttpUrl() {
        val manager = McpManager(FakeMcpServerStore())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { manager.addServer("bad", "ftp://example.com/mcp") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { manager.addServer("bad", "") }
        }
    }

    @Test
    fun addServer_rejectsBlankName() {
        val manager = McpManager(FakeMcpServerStore())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { manager.addServer("  ", "https://mcp.example.com/mcp") }
        }
    }

    @Test
    fun addServer_configPersistsRemoteAndStdioRefusedAtConnect() {
        val store = FakeMcpServerStore()
        val manager = McpManager(store)
        val stdio = runBlocking {
            manager.addServer(
                McpServerConfig(
                    id = "mcp_local1",
                    name = "local",
                    url = "",
                    transport = McpTransport.STDIO,
                )
            )
        }
        assertEquals("mcp_local1", stdio.id)
        assertEquals(listOf(stdio), store.servers)
        // Registry holds it, but connecting a stdio server is blocked (M4).
        assertFalse(runBlocking { manager.connect(McpServerConfig(stdio.id, stdio.name, stdio.endpointUrl, McpTransport.STDIO)) })
        assertFalse(runBlocking { manager.isConnected("mcp_local1") })
    }

    @Test
    fun deleteServer_removesRowAndDropsConnection() = runBlocking {
        val entity = McpServerEntity("s1", "docs", "https://docs.example.com/mcp", true)
        val store = FakeMcpServerStore(listOf(entity))
        val clients = mapOf(
            "https://docs.example.com/mcp" to clientFor(mapOf("initialize" to initializeOk)),
        )
        val manager = managerFor(store, clients)
        assertTrue(manager.connect(entity))
        assertTrue(manager.isConnected("s1"))
        assertTrue(manager.deleteServer("s1"))
        assertFalse(manager.isConnected("s1"))
        assertTrue(manager.listServers().isEmpty())
        // A second delete is a no-op returning false; unknown ids too.
        assertFalse(manager.deleteServer("s1"))
        assertFalse(manager.deleteServer("no-such-server"))
    }

    // --- Connection lifecycle ---

    @Test
    fun connectAll_connectsOnlyEnabledServers() {
        val enabled = McpServerEntity("s1", "docs", "https://docs.example.com/mcp", true)
        val disabled = McpServerEntity("s2", "off", "https://off.example.com/mcp", false)
        val store = FakeMcpServerStore(listOf(enabled, disabled))
        val clients = mapOf(
            "https://docs.example.com/mcp" to clientFor(mapOf("initialize" to initializeOk)),
            "https://off.example.com/mcp" to clientFor(mapOf("initialize" to initializeOk)),
        )
        val manager = managerFor(store, clients)
        val results = runBlocking { manager.connectAll() }
        assertEquals(mapOf("s1" to true), results)
        assertTrue(runBlocking { manager.isConnected("s1") })
        assertFalse(runBlocking { manager.isConnected("s2") })
    }

    @Test
    fun connect_returnsFalseWhenInitializeFails() {
        val server = McpServerEntity("s1", "docs", "https://docs.example.com/mcp", true)
        val store = FakeMcpServerStore(listOf(server))
        val error = CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":1,"error":{"code":-32600,"message":"Invalid Request"}}""",
        )
        val manager = managerFor(store, mapOf(server.endpointUrl to clientFor(mapOf("initialize" to error))))
        assertFalse(runBlocking { manager.connect(server) })
        assertFalse(runBlocking { manager.isConnected("s1") })
    }

    @Test
    fun connect_returnsFalseForNonHttpUrl() {
        val server = McpServerEntity("s1", "bad", "ftp://example.com/mcp", true)
        val manager = McpManager(FakeMcpServerStore(listOf(server)))
        assertFalse(runBlocking { manager.connect(server) })
    }

    @Test
    fun connect_sendsBearerTokenFromProvider() {
        val server = McpServerEntity("s1", "docs", "https://docs.example.com/mcp", true)
        val interceptor = CannedInterceptor(mapOf("initialize" to initializeOk))
        val httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val manager = McpManager(
            serverStore = FakeMcpServerStore(listOf(server)),
            tokenProvider = { id -> if (id == "s1") "tok-abc" else null },
            clientFactory = { s, token -> McpClient(s.endpointUrl, token, httpClient) },
        )
        assertTrue(runBlocking { manager.connect(server) })
        assertEquals(listOf("Bearer tok-abc"), interceptor.seenAuthHeaders)
    }

    @Test
    fun disconnect_removesServerFromAggregation() {
        val server = McpServerEntity("s1", "docs", "https://docs.example.com/mcp", true)
        val store = FakeMcpServerStore(listOf(server))
        val client = clientFor(
            mapOf(
                "initialize" to initializeOk,
                "tools/list" to toolsListResponse("read_file" to "Reads a file"),
            )
        )
        val manager = managerFor(store, mapOf(server.endpointUrl to client))
        runBlocking { manager.connectAll() }
        assertEquals(1, runBlocking { manager.listTools() }.size)
        runBlocking { manager.disconnect("s1") }
        assertFalse(runBlocking { manager.isConnected("s1") })
        assertTrue(runBlocking { manager.listTools() }.isEmpty())
    }

    // --- Tool aggregation ---

    @Test
    fun listTools_aggregatesAcrossServers() {
        val docs = McpServerEntity("s1", "docs", "https://docs.example.com/mcp", true)
        val code = McpServerEntity("s2", "code", "https://code.example.com/mcp", true)
        val store = FakeMcpServerStore(listOf(docs, code))
        val clients = mapOf(
            docs.endpointUrl to clientFor(
                mapOf(
                    "initialize" to initializeOk,
                    "tools/list" to toolsListResponse("search" to "Search docs", "fetch" to "Fetch a page"),
                )
            ),
            code.endpointUrl to clientFor(
                mapOf(
                    "initialize" to initializeOk,
                    "tools/list" to toolsListResponse("run" to "Run code"),
                )
            ),
        )
        val manager = managerFor(store, clients)
        runBlocking { manager.connectAll() }
        val tools = runBlocking { manager.listTools() }
        assertEquals(3, tools.size)
        assertEquals(listOf("search", "fetch"), tools.filter { it.serverId == "s1" }.map { it.name })
        assertEquals(listOf("run"), tools.filter { it.serverId == "s2" }.map { it.name })
    }

    @Test
    fun listTools_skipsFailingServer() {
        val good = McpServerEntity("s1", "docs", "https://docs.example.com/mcp", true)
        val bad = McpServerEntity("s2", "broken", "https://broken.example.com/mcp", true)
        val store = FakeMcpServerStore(listOf(good, bad))
        val clients = mapOf(
            good.endpointUrl to clientFor(
                mapOf(
                    "initialize" to initializeOk,
                    "tools/list" to toolsListResponse("search" to "Search docs"),
                )
            ),
            bad.endpointUrl to clientFor(
                mapOf(
                    "initialize" to initializeOk,
                    "tools/list" to CannedResponse(500, "Internal Server Error"),
                )
            ),
        )
        val manager = managerFor(store, clients)
        runBlocking { manager.connectAll() }
        val tools = runBlocking { manager.listTools() }
        assertEquals(listOf("search"), tools.map { it.name })
    }

    @Test
    fun callTool_throwsWhenNotConnected() {
        val manager = McpManager(FakeMcpServerStore())
        assertThrows(IllegalStateException::class.java) {
            runBlocking { manager.callTool("s1", "search", buildJsonObject {}) }
        }
    }

    // --- Tool adapter ---

    @Test
    fun mcpTools_namesAreNamespacedAndSanitized() {
        val manager = McpManager(FakeMcpServerStore())
        val defs = listOf(
            McpToolDefinition("s1", "read-file", "Reads a file", buildJsonObject {}),
        )
        // Pure overload uses the server id as the display name.
        val tools = manager.mcpTools(defs)
        assertEquals(listOf("mcp_s1_read_file"), tools.map { it.name })
        assertTrue(tools.single().sensitive)
        assertTrue(tools.single().description.contains("Reads a file"))
    }

    @Test
    fun mcpTools_liveNamesUseServerName() {
        val server = McpServerEntity("s1", "My Docs!", "https://docs.example.com/mcp", true)
        val store = FakeMcpServerStore(listOf(server))
        val client = clientFor(
            mapOf(
                "initialize" to initializeOk,
                "tools/list" to toolsListResponse("read-file" to "Reads a file"),
            )
        )
        val manager = managerFor(store, mapOf(server.endpointUrl to client))
        runBlocking { manager.connectAll() }
        val tools = runBlocking { manager.mcpTools() }
        // OpenCode-style sanitize + prefix, with the mcp_ collision guard.
        assertEquals(listOf("mcp_my_docs_read_file"), tools.map { it.name })
    }

    @Test
    fun mcpTools_dedupesCollidingNames() {
        val manager = McpManager(FakeMcpServerStore())
        val defs = listOf(
            McpToolDefinition("s1", "search", "A", buildJsonObject {}),
            McpToolDefinition("s1", "search", "B", buildJsonObject {}),
        )
        val tools = manager.mcpTools(defs)
        assertEquals(listOf("mcp_s1_search", "mcp_s1_search_2"), tools.map { it.name })
    }

    @Test
    fun mcpTools_executeCallsRemoteTool() {
        val server = McpServerEntity("s1", "docs", "https://docs.example.com/mcp", true)
        val store = FakeMcpServerStore(listOf(server))
        val toolCall = CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"hello"}],"isError":false}}""",
        )
        val client = clientFor(
            mapOf(
                "initialize" to initializeOk,
                "tools/list" to toolsListResponse("search" to "Search docs"),
                "tools/call" to toolCall,
            )
        )
        val manager = managerFor(store, mapOf(server.endpointUrl to client))
        runBlocking { manager.connectAll() }
        val tool = runBlocking { manager.mcpTools() }.single()
        val result = runBlocking { tool.execute(buildJsonObject {}) }
        assertTrue(result.ok)
        assertEquals("hello", result.output)
    }

    @Test
    fun mcpTools_executeReportsRemoteError() {
        val server = McpServerEntity("s1", "docs", "https://docs.example.com/mcp", true)
        val store = FakeMcpServerStore(listOf(server))
        val toolCall = CannedResponse(
            code = 200,
            body = """{"jsonrpc":"2.0","id":3,"error":{"code":-32602,"message":"Unknown tool"}}""",
        )
        val client = clientFor(
            mapOf(
                "initialize" to initializeOk,
                "tools/list" to toolsListResponse("search" to "Search docs"),
                "tools/call" to toolCall,
            )
        )
        val manager = managerFor(store, mapOf(server.endpointUrl to client))
        runBlocking { manager.connectAll() }
        val tool = runBlocking { manager.mcpTools() }.single()
        val result = runBlocking { tool.execute(buildJsonObject {}) }
        assertFalse(result.ok)
        assertTrue(result.error?.contains("Unknown tool") == true)
    }

    @Test
    fun mcpTools_executeFailsWhenServerDisconnects() {
        val server = McpServerEntity("s1", "docs", "https://docs.example.com/mcp", true)
        val store = FakeMcpServerStore(listOf(server))
        val client = clientFor(
            mapOf(
                "initialize" to initializeOk,
                "tools/list" to toolsListResponse("search" to "Search docs"),
            )
        )
        val manager = managerFor(store, mapOf(server.endpointUrl to client))
        runBlocking { manager.connectAll() }
        val tool = runBlocking { manager.mcpTools() }.single()
        runBlocking { manager.disconnect("s1") }
        val result = runBlocking { tool.execute(buildJsonObject {}) }
        assertFalse(result.ok)
        assertTrue(result.error?.contains("not connected") == true)
    }
}
