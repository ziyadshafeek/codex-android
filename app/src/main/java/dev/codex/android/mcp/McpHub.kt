package dev.codex.android.mcp

import dev.codex.android.agent.AgentRuntime
import dev.codex.android.core.CodexJson
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.McpServerEntity
import dev.codex.android.security.SecretScrubber
import dev.codex.android.tools.Tool
import dev.codex.android.tools.ToolRegistry
import dev.codex.android.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID

/**
 * A local (stdio) MCP server, as the user configures it: the process
 * command, its arguments, and its environment.
 *
 * This is the ONLY representation of a stdio server's command. The
 * staged H5 code carried the command in `McpServerConfig.url` /
 * `McpServerEntity.endpointUrl`, which is wrong: the v2 `mcp_servers`
 * table has no stdio columns (see [McpManager] KDoc — "a stdio server's
 * command/args/env have no columns in the v2 `mcp_servers` table yet"),
 * and [McpManager.connect] refuses any non-http(s) URL. A stdio
 * command stored as an endpoint URL would therefore be both
 * unpersistable and unconnectable, and would look like a remote server
 * to every reader of the table.
 *
 * Stdio configs therefore live OUTSIDE [McpServerEntity]: in memory
 * plus, when the integrator supplies one, a JSON file owned by
 * [StdioConfigStore] (default [InMemoryStdioConfigStore]; production
 * use [JsonFileStdioConfigStore] over e.g.
 * `File(context.filesDir, "mcp-stdio-servers.json")`). They are never
 * written to the `mcp_servers` table and never passed to [McpManager].
 */
@Serializable
data class StdioServerConfig(
    val id: String,
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
)

/**
 * Persistence seam for [StdioServerConfig]s. The in-memory
 * implementation is the default (JVM-testable, no Android); the JSON
 * file implementation is the production one. `env` may hold secrets,
 * so the file implementation writes 0600.
 */
interface StdioConfigStore {
    suspend fun load(): List<StdioServerConfig>
    suspend fun save(configs: List<StdioServerConfig>)
}

/** Volatile [StdioConfigStore]; stdio servers vanish on process death. */
class InMemoryStdioConfigStore : StdioConfigStore {
    private var configs: List<StdioServerConfig> = emptyList()

    override suspend fun load(): List<StdioServerConfig> = configs.toList()

    override suspend fun save(configs: List<StdioServerConfig>) {
        this.configs = configs.toList()
    }
}

/** [StdioConfigStore] backed by one JSON file, written owner-only (0600). */
class JsonFileStdioConfigStore(private val file: File) : StdioConfigStore {
    override suspend fun load(): List<StdioServerConfig> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyList()
        runCatching {
            CodexJson.decodeFromString<List<StdioServerConfig>>(file.readText())
        }.getOrDefault(emptyList())
    }

    override suspend fun save(configs: List<StdioServerConfig>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        file.writeText(CodexJson.encodeToString<List<StdioServerConfig>>(configs))
        restrictToOwner(file)
    }

    private fun restrictToOwner(file: File) {
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(
                file.toPath(),
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ),
            )
        }
        file.setExecutable(false, false)
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }
}

/**
 * One live stdio MCP session (a spawned server process speaking
 * JSON-RPC over stdin/stdout). This is a seam, not an implementation:
 * there is NO stdio transport in the repo or in the staged H2 batch —
 * H2's `ProotShellEngine` only offers one-shot `shellExec` /
 * `shellExecWithEnv` (no stdin streaming, no long-lived process
 * handle), so a persistent JSON-RPC session cannot be built on it
 * as-is. The integrator must supply a `ProotStdioJsonRpcTransport`
 * implementing this interface (spawning the server inside the rootfs
 * via H2's `ProotShellEngine`, e.g. a new long-lived-process API on
 * it, or a PTY session from `startPtySession`) and pass it as
 * [McpHub.stdioTransportFactory]. Until then every stdio
 * connect/list/call fails fast with an [IllegalStateException]
 * naming the missing piece, instead of silently pretending to work.
 */
interface StdioTransport {
    /** Runs the MCP `initialize` handshake; false when it fails. */
    suspend fun initialize(): Boolean

    /** `tools/list` on the spawned server. */
    suspend fun listTools(serverId: String): List<McpToolDefinition>

    /** `tools/call` on the spawned server; raw (unscrubbed) result. */
    suspend fun callTool(name: String, arguments: JsonObject): String

    /** Terminates the spawned server process. */
    fun close()
}

/** One server as the hub sees it: remote (persisted) or stdio (hub-owned). */
sealed interface HubServer {
    data class Remote(val entity: McpServerEntity) : HubServer
    data class Stdio(val config: StdioServerConfig) : HubServer
}

/**
 * Unified MCP hub for Milestone M8 (dual transport: stdio over PRoot +
 * remote HTTP), rewritten against the REAL [McpManager] (final class).
 *
 * Adaptations vs the staged H5 code (see NOTES.md):
 * - No `saveBearerToken` / `getBearerToken` / `httpClient` /
 *   `removeServer` / `server.config`: none of those exist on the real
 *   manager. Bearer tokens are NEVER stored by this hub — the real
 *   manager takes them from the `tokenProvider` lambda it was built
 *   with (Codex `bearer_token_env_var` pattern: KeyStore
 *   `mcp_token_<id>`). [addMcpServer] accepts a token only together
 *   with a [tokenSink] the integrator wires to that KeyStore write;
 *   without one it refuses the token instead of pretending to store it.
 * - Remote servers go through [McpManager.connect] / `callTool` /
 *   `listTools` / `mcpTools` — never through a hand-built [McpClient].
 *   The staged code built its own client from `server.config.url`
 *   (`McpServerEntity` has no `config` property) and the manager's
 *   (non-existent) `httpClient`.
 * - Stdio servers are [StdioServerConfig]s in [StdioConfigStore],
 *   never [McpServerEntity]s; see that class's KDoc.
 * - Removal: the real manager/store has no delete, so a remote
 *   removal disconnects and soft-disables (`enabled = false`); stdio
 *   removals are hard deletes from the hub-owned store. A future
 *   `deleteMcpServer` on `CodexDatabase`/`McpServerStore` would make
 *   remote removal a hard delete.
 * - Every MCP tool result — remote and stdio, direct calls and
 *   [Tool] adapters — passes through [SecretScrubber] before entering
 *   the agent's context. Note the real manager's own `McpTool` does
 *   NOT scrub, so this hub builds its own scrubbing adapters in
 *   [registerServerToolsAsTools] instead of reusing `mcpTools()`.
 *
 * [agentRuntime] / [database] are accepted for the DI-root call shape
 * (`McpHub(mcpManager, agentRuntime, database)`) and reserved for
 * future M8 wiring; the hub does not use them yet.
 */
class McpHub(
    private val mcpManager: McpManager,
    @Suppress("unused") private val agentRuntime: AgentRuntime? = null,
    @Suppress("unused") private val database: CodexDatabase? = null,
    private val stdioStore: StdioConfigStore = InMemoryStdioConfigStore(),
    private val stdioTransportFactory: ((StdioServerConfig) -> StdioTransport)? = null,
    /**
     * Where a per-server bearer token goes on [addMcpServer], e.g.
     * `{ id, token -> keyStorePrefs.edit().putString("mcp_token_$id", token).apply() }`.
     * The token is then read back by the `tokenProvider` the
     * [McpManager] was constructed with. Null (the default) means
     * "no token storage is wired": passing a token then throws.
     */
    private val tokenSink: ((serverId: String, token: String) -> Unit)? = null,
) {
    private val stdioMutex = Mutex()
    private val stdioConfigs = LinkedHashMap<String, StdioServerConfig>()
    private var stdioLoaded = false
    private val stdioTransports = HashMap<String, StdioTransport>()

    private suspend fun loadStdioLocked() {
        if (!stdioLoaded) {
            stdioStore.load().forEach { stdioConfigs[it.id] = it }
            stdioLoaded = true
        }
    }

    private suspend fun saveStdioLocked() {
        stdioStore.save(stdioConfigs.values.toList())
    }

    // --- Registration ---

    /**
     * Registers a REMOTE (Streamable HTTP / SSE) MCP server and
     * returns its stored entity. [bearerToken] is handed to
     * [tokenSink] (KeyStore `mcp_token_<id>`); it is never stored in
     * SQLite, never logged, and never kept by this hub.
     *
     * @throws IllegalArgumentException when [transport] is [McpTransport.STDIO]
     *   — a stdio server's command must never be carried in a URL field;
     *   use [addStdioServer] instead.
     * @throws IllegalStateException when a token is supplied but no
     *   [tokenSink] is wired.
     */
    suspend fun addMcpServer(
        name: String,
        url: String,
        transport: McpTransport = McpTransport.REMOTE,
        bearerToken: String? = null,
    ): McpServerEntity = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "MCP server name must not be blank" }
        require(url.isNotBlank()) { "MCP server URL must not be blank" }
        require(transport == McpTransport.REMOTE) {
            "STDIO servers must be added with addStdioServer(name, command, args, env): " +
                "a stdio command must never be carried in the URL field"
        }
        val token = bearerToken?.trim().orEmpty()
        if (token.isNotBlank() && tokenSink == null) {
            throw IllegalStateException(
                "No bearer-token storage is wired: McpManager has no saveBearerToken. " +
                    "Pass a tokenSink (KeyStore mcp_token_<id>) to McpHub, or supply the token " +
                    "via the tokenProvider the McpManager was constructed with."
            )
        }
        val entity = mcpManager.addServer(name.trim(), url.trim(), enabled = true)
        if (token.isNotBlank()) tokenSink?.invoke(entity.id, token)
        entity
    }

    /** Registers a local stdio MCP server; returns its stored config. */
    suspend fun addStdioServer(
        name: String,
        command: String,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        enabled: Boolean = true,
    ): StdioServerConfig {
        require(name.isNotBlank()) { "MCP server name must not be blank" }
        require(command.isNotBlank()) { "MCP server command must not be blank" }
        val config = StdioServerConfig(
            id = "mcp_stdio_" + UUID.randomUUID().toString().replace("-", "").take(12),
            name = name.trim(),
            command = command.trim(),
            args = args.toList(),
            env = env.toMap(),
            enabled = enabled,
        )
        stdioMutex.withLock {
            loadStdioLocked()
            stdioConfigs[config.id] = config
            saveStdioLocked()
        }
        return config
    }

    /** All registered REMOTE servers (enabled and disabled). */
    suspend fun listMcpServers(onlyEnabled: Boolean = false): List<McpServerEntity> =
        withContext(Dispatchers.IO) { mcpManager.listServers(onlyEnabled) }

    /** All registered stdio servers (hub-owned store, never the DB). */
    suspend fun listStdioServers(onlyEnabled: Boolean = false): List<StdioServerConfig> {
        stdioMutex.withLock {
            loadStdioLocked()
            return stdioConfigs.values.filter { !onlyEnabled || it.enabled }.toList()
        }
    }

    /** Every registered server, remote and stdio. */
    suspend fun listAllServers(): List<HubServer> {
        val remote = listMcpServers().map { HubServer.Remote(it) }
        val stdio = listStdioServers().map { HubServer.Stdio(it) }
        return remote + stdio
    }

    /**
     * Removes a registered server by id. Stdio servers are hard-deleted
     * from the hub-owned store. Remote servers are disconnected and
     * soft-disabled (`enabled = false`): the real [McpServerStore] has
     * no delete, so a disabled row is the closest the current API
     * allows; it disappears from `listMcpServers(onlyEnabled = true)`
     * and from [McpManager.connectAll]. Returns false when the id is
     * unknown.
     */
    suspend fun removeMcpServer(serverId: String): Boolean = withContext(Dispatchers.IO) {
        stdioMutex.withLock {
            loadStdioLocked()
            if (stdioConfigs.remove(serverId) != null) {
                stdioTransports.remove(serverId)?.close()
                saveStdioLocked()
                return@withContext true
            }
        }
        val server = mcpManager.listServers().firstOrNull { it.id == serverId } ?: return@withContext false
        // Already removed (soft-disabled): a second removal is a no-op.
        if (!server.enabled) return@withContext false
        mcpManager.disconnect(serverId)
        mcpManager.addServer(
            McpServerConfig(
                id = server.id,
                name = server.name,
                url = server.endpointUrl,
                transport = McpTransport.REMOTE,
                enabled = false,
            )
        )
        true
    }

    /** Drops a server's live connection (remote client / stdio process). */
    suspend fun disconnect(serverId: String) {
        stdioMutex.withLock { stdioTransports.remove(serverId)?.close() }
        mcpManager.disconnect(serverId)
    }

    // --- Connection / tools ---

    private suspend fun remoteServer(serverId: String): McpServerEntity =
        mcpManager.listServers().firstOrNull { it.id == serverId }
            ?: throw IllegalArgumentException("Unknown MCP server: $serverId")

    private suspend fun stdioConfig(serverId: String): StdioServerConfig? {
        stdioMutex.withLock {
            loadStdioLocked()
            return stdioConfigs[serverId]
        }
    }

    private suspend fun stdioTransportFor(config: StdioServerConfig): StdioTransport {
        val factory = stdioTransportFactory ?: throw IllegalStateException(
            "No stdio MCP transport is wired: stdio servers need a ProotStdioJsonRpcTransport " +
                "built on H2's ProotShellEngine (not yet implemented). " +
                "Register the server with addStdioServer, then supply stdioTransportFactory to McpHub."
        )
        return stdioMutex.withLock { stdioTransports.getOrPut(config.id) { factory(config) } }
    }

    /** Connects one server (handshake); false when the handshake fails. */
    suspend fun connectServer(serverId: String): Boolean = withContext(Dispatchers.IO) {
        val stdio = stdioConfig(serverId)
        if (stdio != null) {
            if (!stdio.enabled) return@withContext false
            return@withContext runCatching { stdioTransportFor(stdio).initialize() }.getOrDefault(false)
        }
        val server = remoteServer(serverId)
        mcpManager.connect(server)
    }

    private suspend fun ensureConnectedRemote(server: McpServerEntity) {
        if (!mcpManager.isConnected(server.id)) {
            val ok = mcpManager.connect(server)
            if (!ok) throw IllegalStateException("MCP server '${server.name}' failed to initialize")
        }
    }

    /**
     * Lists tool names exposed by [serverId] (transport-appropriate
     * client). Names are identifiers, not content; the scrubbing
     * guarantee applies to tool *results* ([callMcpTool]).
     */
    suspend fun listTools(serverId: String): List<String> = withContext(Dispatchers.IO) {
        val stdio = stdioConfig(serverId)
        if (stdio != null) {
            return@withContext stdioTransportFor(stdio).listTools(serverId).map { it.name }
        }
        val server = remoteServer(serverId)
        ensureConnectedRemote(server)
        mcpManager.listTools().filter { it.serverId == serverId }.map { it.name }
    }

    /**
     * Calls an MCP tool by name with JSON [args] and returns the
     * scrubbed result string. The raw result is NEVER passed to the
     * agent unscrubbed.
     */
    suspend fun callMcpTool(
        serverId: String,
        toolName: String,
        args: JsonObject,
    ): String = withContext(Dispatchers.IO) {
        val stdio = stdioConfig(serverId)
        if (stdio != null) {
            val raw = stdioTransportFor(stdio).callTool(toolName, args)
            return@withContext SecretScrubber.scrub(raw)
        }
        val server = remoteServer(serverId)
        ensureConnectedRemote(server)
        val raw = mcpManager.callTool(serverId, toolName, args)
        SecretScrubber.scrub(raw)
    }

    /**
     * Registers all tools from [serverId] as scrubbing [Tool]s in the
     * agent's [ToolRegistry], so the agent can call them like
     * built-ins. Names follow the manager's `mcp_<server>_<tool>`
     * convention. The adapters scrub both outputs and error strings,
     * and are `sensitive = true` (untrusted remote/local servers).
     * Built on the manager's live tool definitions — NOT on
     * `mcpTools()`, whose `McpTool` adapters do not scrub.
     */
    suspend fun registerServerToolsAsTools(
        serverId: String,
        registry: ToolRegistry,
    ): List<String> = withContext(Dispatchers.IO) {
        val stdio = stdioConfig(serverId)
        val displayName: String
        val definitions: List<McpToolDefinition>
        if (stdio != null) {
            displayName = stdio.name
            definitions = stdioTransportFor(stdio).listTools(serverId)
        } else {
            val server = remoteServer(serverId)
            displayName = server.name
            ensureConnectedRemote(server)
            definitions = mcpManager.listTools().filter { it.serverId == serverId }
        }
        val used = HashSet<String>()
        val names = mutableListOf<String>()
        for (def in definitions) {
            val base = McpManager.toolName(displayName, def.name)
            var candidate = base
            var n = 2
            while (!used.add(candidate)) {
                candidate = "${base}_$n"
                n++
            }
            names += candidate
            val tool = object : Tool {
                override val name: String = candidate
                override val description: String =
                    "[MCP $displayName] ${def.description.ifBlank { def.name }}"
                override val parametersSchema: JsonObject = def.inputSchema
                override val sensitive: Boolean = true

                override suspend fun execute(args: JsonObject): ToolResult {
                    return runCatching {
                        val result = callMcpTool(serverId, def.name, args)
                        ToolResult(ok = true, output = result)
                    }.getOrElse { e ->
                        ToolResult(
                            ok = false,
                            output = "",
                            error = SecretScrubber.scrub(e.message ?: "MCP call failed"),
                        )
                    }
                }
            }
            registry.register(tool)
        }
        names
    }
}
