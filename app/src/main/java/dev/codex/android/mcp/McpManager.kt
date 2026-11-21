package dev.codex.android.mcp

import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.McpServerEntity
import dev.codex.android.tools.Tool
import dev.codex.android.tools.ToolResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Remote MCP (Model Context Protocol) server manager — Subsystem 7,
 * Dual-Transport MCP, remote half. Built on top of the existing
 * [McpClient] (Streamable HTTP / SSE, `Mcp-Session-Id`, bearer auth);
 * this file adds the server registry, connection lifecycle, tool
 * aggregation and the [Tool] adapter the AgentLoop consumes.
 *
 * Open-source shape reused (behaviour ported to Kotlin, no code copied):
 * - OpenCode (MIT, https://github.com/anomalyco/opencode), `opencode.json`:
 *   each server is `{ "type": "remote", "url": "<endpoint>",
 *   "headers": { "Authorization": "Bearer ..." }, "enabled": true }`
 *   under a top-level `mcp` key, keyed by a short lowercase server name
 *   that becomes the prefix of its tools. OpenCode names an MCP tool
 *   `sanitize(serverName) + "_" + sanitize(toolName)`
 *   (`packages/core/src/mcp/catalog.ts`).
 * - OpenAI Codex CLI (Apache 2.0, https://github.com/openai/codex),
 *   `~/.codex/config.toml`: `[mcp_servers.<name>]` with `url` for
 *   Streamable HTTP and `bearer_token_env_var` — the *name* of the
 *   variable holding the token, never the token itself.
 *
 * How that maps here:
 * - `name` / `url` / `enabled` -> [McpServerEntity] (`name`,
 *   `endpointUrl`, `enabled`) in the `mcp_servers` table, via the
 *   existing [CodexDatabase.upsertMcpServer] / `listMcpServers`.
 * - `transport` -> [McpTransport]; only [McpTransport.REMOTE] is
 *   supported in this file. Local `stdio` is BLOCKED until M4 (it needs
 *   the PRoot rootfs to spawn a server process on the phone); a stdio
 *   config can be registered but [connect] refuses it.
 * - `headers` / bearer token -> the [tokenProvider] lambda
 *   `(serverId: String) -> String?`, the Codex `bearer_token_env_var`
 *   pattern: the manager never stores a token. Integration wires the
 *   lambda to the encrypted store, e.g.
 *   `KeyStore.prefsFor(context).getString("mcp_token_<id>", null)` —
 *   the DB comment already reserves the `mcp_token_<id>` key. No token
 *   is ever written to SQLite, logged, or shipped as a default.
 * - Tool naming -> OpenCode's sanitize-and-prefix convention, plus an
 *   `mcp_` guard so a remote tool can never collide with a built-in
 *   tool name (`device_info`, `echo`, ...): `mcp_<server>_<tool>`.
 *
 * All MCP tools are [Tool.sensitive] = true: they call untrusted
 * remote servers, so the caller must ask the user first (approval gate).
 * No real API key, no live model call, no network in tests — see
 * [McpManagerTest] for the fake-interceptor tests.
 */
enum class McpTransport {
    /** Streamable HTTP / SSE remote server (this file). */
    REMOTE,

    /** Local stdio server process — blocked until M4 (PRoot rootfs). */
    STDIO,
}

/**
 * A server as the user configures it — the OpenCode/Codex config shape
 * (`name`, `url`, `transport`, `enabled`) in one place. Only the remote
 * fields persist to [McpServerEntity]; a stdio server's command/args/env
 * have no columns in the v2 `mcp_servers` table yet (M4 work).
 */
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val transport: McpTransport = McpTransport.REMOTE,
    val enabled: Boolean = true,
) {
    fun toEntity(): McpServerEntity = McpServerEntity(id, name, url, enabled)
}

/**
 * Persistence seam for the server registry. The production
 * implementation is [DatabaseMcpServerStore] (the existing
 * `mcp_servers` table); tests substitute an in-memory fake so no
 * Android SQLite is needed on the JVM.
 */
interface McpServerStore {
    suspend fun listServers(onlyEnabled: Boolean = false): List<McpServerEntity>
    suspend fun saveServer(server: McpServerEntity)

    /** Hard-deletes the server row. Returns true when a row was removed. */
    suspend fun deleteServer(serverId: String): Boolean
}

/** [McpServerStore] backed by the existing `mcp_servers` table. */
class DatabaseMcpServerStore(private val database: CodexDatabase) : McpServerStore {
    override suspend fun listServers(onlyEnabled: Boolean): List<McpServerEntity> =
        database.listMcpServers(onlyEnabled)

    override suspend fun saveServer(server: McpServerEntity) {
        database.upsertMcpServer(server)
    }

    override suspend fun deleteServer(serverId: String): Boolean =
        database.deleteMcpServer(serverId)
}

class McpManager(
    private val serverStore: McpServerStore,
    private val tokenProvider: (serverId: String) -> String? = { null },
    private val clientFactory: (server: McpServerEntity, bearerToken: String?) -> McpClient =
        { server, token -> McpClient(server.endpointUrl, token) },
) {
    /**
     * Convenience constructor wiring the registry to the existing
     * `mcp_servers` table. [tokenProvider] is still a lambda — pass
     * e.g. `{ id -> KeyStore.prefsFor(context).getString("mcp_token_$id", null) }`
     * from the integration site; this class never touches KeyStore.
     */
    constructor(
        database: CodexDatabase,
        tokenProvider: (serverId: String) -> String? = { null },
        clientFactory: (server: McpServerEntity, bearerToken: String?) -> McpClient =
            { server, token -> McpClient(server.endpointUrl, token) },
    ) : this(DatabaseMcpServerStore(database), tokenProvider, clientFactory)

    private val clients = LinkedHashMap<String, McpClient>()
    private val clientsMutex = Mutex()

    companion object {
        /**
         * OpenCode-style sanitiser for tool-name segments: lowercase,
         * anything outside `[a-z0-9_]` becomes `_`, leading/trailing
         * underscores trimmed. An empty result falls back to "server".
         */
        fun sanitize(value: String): String {
            val cleaned = value.lowercase().map { c ->
                if (c in 'a'..'z' || c in '0'..'9' || c == '_') c else '_'
            }.joinToString("").trim('_')
            return cleaned.ifEmpty { "server" }
        }

        /** The tool name the AgentLoop sees for a remote tool. */
        fun toolName(serverDisplayName: String, toolName: String): String =
            "mcp_${sanitize(serverDisplayName)}_${sanitize(toolName)}"
    }

    // --- Registry ---

    /** All registered servers (enabled and disabled). */
    suspend fun listServers(onlyEnabled: Boolean = false): List<McpServerEntity> =
        serverStore.listServers(onlyEnabled)

    /**
     * Registers a remote (Streamable HTTP / SSE) server. The URL must be
     * http(s); the bearer token is NOT stored here — see [tokenProvider].
     */
    suspend fun addServer(
        name: String,
        endpointUrl: String,
        enabled: Boolean = true,
    ): McpServerEntity {
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "MCP server name must not be blank" }
        val cleanUrl = endpointUrl.trim()
        require(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
            "MCP server URL must start with http:// or https://"
        }
        val entity = McpServerEntity(
            id = "mcp_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            name = cleanName,
            endpointUrl = cleanUrl,
            enabled = enabled,
        )
        serverStore.saveServer(entity)
        return entity
    }

    /** Registers a server from its config shape (see [McpServerConfig]). */
    suspend fun addServer(config: McpServerConfig): McpServerEntity {
        val entity = config.toEntity()
        require(entity.name.trim().isNotBlank()) { "MCP server name must not be blank" }
        if (config.transport == McpTransport.REMOTE) {
            require(entity.endpointUrl.startsWith("http://") || entity.endpointUrl.startsWith("https://")) {
                "MCP server URL must start with http:// or https://"
            }
        }
        serverStore.saveServer(entity)
        return entity
    }

    /**
     * Hard-deletes a registered server: drops its live connection and
     * removes the row from the store. Returns false when the id is
     * unknown (a second delete is a no-op returning false).
     */
    suspend fun deleteServer(serverId: String): Boolean {
        if (serverStore.listServers().none { it.id == serverId }) return false
        disconnect(serverId)
        return serverStore.deleteServer(serverId)
    }

    // --- Connection lifecycle ---

    /**
     * Connects one remote server: builds its [McpClient] with the bearer
     * token from [tokenProvider] and runs the MCP `initialize` handshake.
     * Returns false (never throws) when the URL is not http(s), the
     * handshake fails, or the server is unreachable.
     */
    suspend fun connect(server: McpServerEntity): Boolean {
        val url = server.endpointUrl.trim()
        if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return false
        }
        val client = clientFactory(server, tokenProvider(server.id))
        val ok = try {
            client.initialize()
        } catch (e: Exception) {
            false
        }
        if (!ok) return false
        clientsMutex.withLock { clients[server.id] = client }
        return true
    }

    /**
     * Connects a configured server. A [McpTransport.STDIO] server always
     * returns false: local stdio MCP is blocked until M4 (PRoot rootfs).
     */
    suspend fun connect(config: McpServerConfig): Boolean {
        if (config.transport == McpTransport.STDIO) return false
        return connect(config.toEntity())
    }

    /** Connects every enabled registered server; one entry per server. */
    suspend fun connectAll(): Map<String, Boolean> {
        val servers = serverStore.listServers(onlyEnabled = true)
        val results = LinkedHashMap<String, Boolean>()
        for (server in servers) {
            results[server.id] = connect(server)
        }
        return results
    }

    /** Drops a server's client (its session id is forgotten with it). */
    suspend fun disconnect(serverId: String) {
        clientsMutex.withLock { clients.remove(serverId) }
    }

    /** Drops every connected server's client. */
    suspend fun disconnectAll() {
        clientsMutex.withLock { clients.clear() }
    }

    suspend fun isConnected(serverId: String): Boolean =
        clientsMutex.withLock { clients.containsKey(serverId) }

    suspend fun connectedServerIds(): List<String> =
        clientsMutex.withLock { clients.keys.toList() }

    // --- Tools ---

    /**
     * `tools/list` aggregated across every connected server. A server
     * that fails (network error, bad payload) is skipped so one bad
     * server never hides the rest.
     */
    suspend fun listTools(): List<McpToolDefinition> {
        val snapshot = clientsMutex.withLock { clients.toMap() }
        val out = mutableListOf<McpToolDefinition>()
        for ((serverId, client) in snapshot) {
            try {
                out += client.listTools(serverId)
            } catch (e: Exception) {
                // Skip the failing server; the rest still aggregate.
            }
        }
        return out
    }

    /**
     * Calls one tool on one connected server. Throws
     * [IllegalStateException] when the server is not connected; transport
     * failures surface as [java.io.IOException] from [McpClient].
     */
    suspend fun callTool(serverId: String, name: String, arguments: JsonObject): String {
        val client = clientsMutex.withLock { clients[serverId] }
            ?: throw IllegalStateException("MCP server $serverId is not connected")
        return client.callTool(name, arguments)
    }

    // --- Resources ---

    /**
     * `resources/list` on one connected server. Throws
     * [IllegalStateException] when the server is not connected; transport
     * failures surface as [java.io.IOException] from [McpClient].
     */
    suspend fun listResources(serverId: String): List<McpResource> {
        val client = clientsMutex.withLock { clients[serverId] }
            ?: throw IllegalStateException("MCP server $serverId is not connected")
        return client.listResources(serverId)
    }

    /**
     * `resources/read` on one connected server. Throws
     * [IllegalStateException] when the server is not connected;
     * [java.io.IOException] on transport failure, a JSON-RPC error, or an
     * empty contents array.
     */
    suspend fun readResource(serverId: String, uri: String): McpResourceContent {
        val client = clientsMutex.withLock { clients[serverId] }
            ?: throw IllegalStateException("MCP server $serverId is not connected")
        return client.readResource(serverId, uri)
    }

    // --- Events (opportunistic notification capture) ---

    /**
     * Notifications the connected clients recorded opportunistically from
     * SSE response bodies since the last drain (see [McpEvent] KDoc for
     * the limit: there is no persistent stream, so this is not a live
     * feed). Drains each client's buffer as it reads it.
     */
    suspend fun recentNotifications(): List<McpEvent> {
        val snapshot = clientsMutex.withLock { clients.values.toList() }
        val out = mutableListOf<McpEvent>()
        for (client in snapshot) {
            out += client.recentNotifications()
            client.clearNotifications()
        }
        return out.sortedBy { it.receivedAtMillis }
    }

    /**
     * The connected servers' tools as AgentLoop [Tool]s, namespaced
     * `mcp_<server>_<tool>` (see [toolName]). Fetches the live tool list
     * first; use [mcpTools] with definitions to wrap an already-fetched
     * list without another round-trip.
     */
    suspend fun mcpTools(): List<Tool> {
        val definitions = listTools()
        val displayNames = serverStore.listServers().associate { it.id to it.name }
        return wrapTools(definitions) { serverId -> displayNames[serverId] ?: serverId }
    }

    /** Wraps already-fetched tool definitions as AgentLoop [Tool]s. */
    fun mcpTools(definitions: List<McpToolDefinition>): List<Tool> =
        wrapTools(definitions) { serverId -> serverId }

    private fun wrapTools(
        definitions: List<McpToolDefinition>,
        displayNameFor: (serverId: String) -> String,
    ): List<Tool> {
        val used = HashSet<String>()
        return definitions.map { def ->
            val displayName = displayNameFor(def.serverId)
            val base = toolName(displayName, def.name)
            var candidate = base
            var n = 2
            while (!used.add(candidate)) {
                candidate = "${base}_$n"
                n++
            }
            McpTool(this, def, candidate, displayName)
        }
    }
}

/**
 * One remote MCP tool exposed to the AgentLoop as a [Tool].
 *
 * `sensitive` is always true: the call goes to an untrusted remote
 * server, so the approval gate must ask the user first. `execute`
 * translates an "MCP error: ..." result (a JSON-RPC error payload from
 * [McpClient.callTool]) into a failed [ToolResult].
 */
class McpTool(
    private val manager: McpManager,
    private val definition: McpToolDefinition,
    override val name: String,
    private val serverDisplayName: String,
) : Tool {
    override val description: String =
        "[MCP $serverDisplayName] ${definition.description.ifBlank { definition.name }}"
    override val parametersSchema: JsonObject = definition.inputSchema
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        return try {
            val output = manager.callTool(definition.serverId, definition.name, args)
            if (output.startsWith("MCP error:")) {
                ToolResult(ok = false, output = "", error = output)
            } else {
                ToolResult(ok = true, output = output)
            }
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "MCP tool call failed")
        }
    }
}
