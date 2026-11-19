package dev.codex.android.server

import android.content.Context
import dev.codex.android.agent.AgentRuntime
import dev.codex.android.core.CodexJson
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.ThreadEntity
import dev.codex.android.data.TimelineMessage
import dev.codex.android.security.ExecutionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Minimal HTTP request parsed by the local daemon (loopback-only). */
data class DaemonRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
    val remoteAddress: String,
)

/** Minimal HTTP response written by the local daemon. */
data class DaemonResponse(
    val status: Int,
    val contentType: String = "application/json",
    val body: String,
)

/**
 * Thread/message persistence seam for the daemon. The production
 * implementation is [DatabaseThreadStore] (the real [CodexDatabase]);
 * JVM tests substitute an in-memory fake — the real database is an
 * Android `SQLiteOpenHelper` and cannot run on the JVM, and there is
 * no `CodexDatabase.createInMemoryForTest()` in the repo.
 *
 * Method shapes mirror the real database exactly:
 * - `createThread` returns [ThreadEntity] (suspend); use `.id`.
 * - `appendMessage` is NOT part of this seam: the daemon never
 *   appends messages itself. `AgentRuntime.startTurn` appends the
 *   user goal and the final answer as [TimelineMessage]s (the ONLY
 *   `appendMessage` overload) — the staged code's implied direct
 *   `appendMessage(threadId, role, kind, content)` overload does not
 *   exist in the repo.
 */
interface DaemonThreadStore {
    suspend fun listThreads(
        workspaceId: String = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID,
    ): List<ThreadEntity>

    suspend fun getMessages(threadId: String, limit: Int = 200): List<TimelineMessage>

    suspend fun createThread(
        workspaceId: String = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID,
        title: String,
        mode: String,
    ): ThreadEntity
}

/** [DaemonThreadStore] backed by the real [CodexDatabase]. */
class DatabaseThreadStore(private val database: CodexDatabase) : DaemonThreadStore {
    override suspend fun listThreads(workspaceId: String): List<ThreadEntity> =
        database.listThreads(workspaceId)

    override suspend fun getMessages(threadId: String, limit: Int): List<TimelineMessage> =
        database.getMessages(threadId, limit)

    override suspend fun createThread(workspaceId: String, title: String, mode: String): ThreadEntity =
        database.createThread(workspaceId = workspaceId, title = title, mode = mode)
}

/**
 * Builds the real agent runner for a daemon turn, following the
 * `ChatViewModel.runAgentTurn` pattern: an [dev.codex.android.agent.AgentLoop]
 * constructed from KeyStore settings (`OkHttpOpenAiClient` with the
 * stored base URL / API key / model, policy-gated [dev.codex.android.tools.ToolRegistry],
 * `DefaultApprovalGate`, `PlanStore`), driven with the
 * [AgentRuntime.TurnContext] passed in as `turnContext` (the same
 * explicit-parameter shape as `SubagentLoopFactory.runGoal` — a
 * receiver-typed member on a `fun interface` does not resolve at the
 * call site, so the context is a plain parameter here) with
 * `nextStepOrThrow` / `emitAssistantDelta` / `drainSteeringMessages` /
 * `requestApproval`.
 * That function is private to `ChatViewModel` in the repo, so it cannot
 * be reused directly — the integrator must extract it (or an
 * equivalent) into a shared helper and supply it here. Until one is
 * supplied, turns are accepted and the runner returns the "no runner
 * wired" message below, which `AgentRuntime` records as the turn's
 * assistant reply (a loud, visible failure, never a silent no-op).
 */
fun interface AgentRunnerFactory {
    suspend fun runTurn(
        turnContext: AgentRuntime.TurnContext,
        threadId: String,
        goal: String,
        mode: ExecutionMode,
    ): String
}

/**
 * Seam over [AgentRuntime] turn control. The production
 * implementation is [RealTurnStarter]; JVM tests substitute a fake
 * that records calls — the real runtime needs an Android [Context],
 * starts the foreground service, and touches the real database.
 */
interface TurnStarter {
    fun startTurn(
        threadId: String,
        goal: String,
        mode: ExecutionMode,
        runner: suspend AgentRuntime.TurnContext.() -> String,
    ): Boolean

    fun stop(threadId: String)
}

/** [TurnStarter] that calls the real [AgentRuntime] object. */
class RealTurnStarter(private val context: Context) : TurnStarter {
    override fun startTurn(
        threadId: String,
        goal: String,
        mode: ExecutionMode,
        runner: suspend AgentRuntime.TurnContext.() -> String,
    ): Boolean = AgentRuntime.startTurn(
        context = context,
        threadId = threadId,
        goal = goal,
        mode = mode,
        runner = runner,
    )

    override fun stop(threadId: String) {
        AgentRuntime.stop(threadId)
    }
}

/**
 * On-phone headless local daemon (Milestone M8) exposing a
 * localhost-only API for the on-device `codex` CLI and other
 * on-phone clients.
 *
 * - Binds STRICTLY to 127.0.0.1 (never 0.0.0.0); every request's
 *   remote address must be a loopback address or it is rejected
 *   with 403.
 * - Requires a per-boot 256-bit Bearer token (generated at daemon
 *   start, stored in `daemon.env` with 0600 by [CodexCli], rotated
 *   on every boot). Auth compares in constant time via
 *   [MessageDigest.isEqual].
 * - Validates `Host` and `Origin` headers (must be `localhost` or
 *   `127.0.0.1`, with an optional port).
 * - Routes: `POST /v1/turn`, `GET /v1/threads`,
 *   `GET /v1/threads/{id}/messages`, `POST /v1/stop`,
 *   `GET /v1/health` (`GET /health` is kept as a legacy alias).
 *
 * Plain `java.net`, no new dependency. Constructed with seams
 * ([DaemonThreadStore], [TurnStarter]) so the request/route logic
 * is JVM-testable; the convenience constructor wires the real
 * database + runtime for the DI root. Note the DI-note call shape
 * changed: the staged `LocalDaemon(agentRuntime, database, port)`
 * cannot work — the real `AgentRuntime.startTurn` needs an Android
 * [Context] (first parameter), and `AgentRuntime` is a singleton
 * object, so there is nothing to pass for it.
 */
class LocalDaemon(
    private val threadStore: DaemonThreadStore,
    private val turnStarter: TurnStarter,
    private val runnerFactory: AgentRunnerFactory? = null,
    private val port: Int = DEFAULT_PORT,
) {
    /**
     * DI-root constructor: wires the real database and runtime.
     * `context` is required — the real `AgentRuntime.startTurn`
     * needs it. Supply [runnerFactory] from the ChatViewModel
     * AgentLoop pattern (see [AgentRunnerFactory]); without one,
     * turns are accepted but run no agent (loudly, see above).
     */
    constructor(
        context: Context,
        database: CodexDatabase,
        port: Int = DEFAULT_PORT,
        runnerFactory: AgentRunnerFactory? = null,
    ) : this(DatabaseThreadStore(database), RealTurnStarter(context), runnerFactory, port)

    companion object {
        const val DEFAULT_PORT = 8787
        const val LOOPBACK_HOST = "127.0.0.1"
        const val BEARER_SCHEME = "Bearer "
    }

    /** Per-boot token, generated at construction and rotated on every daemon restart. */
    val bearerToken: String = generateBearerToken()

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var scopeJob = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.IO + scopeJob)

    private fun generateBearerToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Returns true when the daemon is actively listening. */
    fun isRunning(): Boolean = serverSocket?.isBound == true && serverSocket?.isClosed == false

    /** Starts listening on 127.0.0.1:[port]. Safe to call after [stop]. */
    fun start() {
        if (isRunning()) return
        if (scopeJob.isCancelled) {
            scopeJob = SupervisorJob()
            scope = CoroutineScope(Dispatchers.IO + scopeJob)
        }
        val socket = ServerSocket(port, 50, InetAddress.getByName(LOOPBACK_HOST))
        serverSocket = socket
        acceptJob = scope.launch {
            while (isRunning()) {
                try {
                    val client = socket.accept()
                    launch { handleConnection(client) }
                } catch (_: Exception) {
                    // Socket closed during shutdown; exit loop.
                    break
                }
            }
        }
    }

    /** Stops listening. In-flight turns are cancelled via the runtime. */
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        scopeJob.cancel()
    }

    /** Pure request-validation helper: loopback-only + Bearer token + Host/Origin allowlist. */
    fun validateRequest(request: DaemonRequest): Int? {
        val remote = request.remoteAddress
        if (remote != LOOPBACK_HOST && remote != "::1" && remote != "localhost") {
            return 403
        }
        val host = request.headers["host"]?.trim()?.lowercase().orEmpty()
        if (host.isNotBlank() && hostWithoutPort(host) !in setOf("localhost", "127.0.0.1", "::1")) {
            return 403
        }
        val origin = request.headers["origin"]?.trim()?.lowercase().orEmpty()
        if (origin.isNotBlank() &&
            !origin.startsWith("http://localhost") &&
            !origin.startsWith("http://127.0.0.1") &&
            !origin.startsWith("http://[::1]")
        ) {
            return 403
        }
        val expected = (BEARER_SCHEME + bearerToken).toByteArray(Charsets.UTF_8)
        val provided = request.headers["authorization"].orEmpty().toByteArray(Charsets.UTF_8)
        // Constant-time compare (MessageDigest.isEqual): no early-exit
        // timing signal for a guessed token. The staged `!=` string
        // comparison leaked exactly that.
        if (!MessageDigest.isEqual(expected, provided)) {
            return 401
        }
        return null
    }

    private fun hostWithoutPort(host: String): String {
        val h = host.trim()
        if (h.startsWith("[")) return h.substringBefore("]").removePrefix("[")
        return h.substringBefore(":")
    }

    /** Pure route dispatcher (testable without sockets). */
    suspend fun dispatch(request: DaemonRequest): DaemonResponse {
        val validationError = validateRequest(request)
        if (validationError != null) {
            return DaemonResponse(
                status = validationError,
                body = CodexJson.encodeToString(
                    ErrorResponse.serializer(),
                    ErrorResponse(error = if (validationError == 401) "unauthorized" else "forbidden"),
                ),
            )
        }
        return when {
            request.method == "GET" && (request.path == "/v1/health" || request.path == "/health") ->
                DaemonResponse(200, body = """{"status":"ok"}""")

            request.method == "GET" && request.path == "/v1/threads" -> {
                val threads = threadStore.listThreads(CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID)
                val json = CodexJson.encodeToString(
                    ThreadListResponse.serializer(),
                    ThreadListResponse(threads = threads.map { it.toDto() }),
                )
                DaemonResponse(200, body = json)
            }

            request.method == "GET" && request.path.startsWith("/v1/threads/") && request.path.endsWith("/messages") -> {
                val threadId = request.path
                    .removePrefix("/v1/threads/")
                    .removeSuffix("/messages")
                    .trim('/')
                if (threadId.isBlank() || threadId.contains('/')) {
                    return DaemonResponse(400, body = """{"error":"invalid thread id"}""")
                }
                val messages = threadStore.getMessages(threadId)
                val json = CodexJson.encodeToString(
                    MessageListResponse.serializer(),
                    MessageListResponse(messages = messages.map { it.toDto() }),
                )
                DaemonResponse(200, body = json)
            }

            request.method == "POST" && request.path == "/v1/turn" -> {
                val parsed = runCatching {
                    CodexJson.decodeFromString(TurnRequest.serializer(), request.body)
                }.getOrNull()
                if (parsed == null) {
                    return DaemonResponse(400, body = """{"error":"invalid turn request"}""")
                }
                val goal = parsed.goal.trim()
                if (goal.isBlank()) {
                    return DaemonResponse(400, body = """{"error":"goal must not be blank"}""")
                }
                val mode = runCatching {
                    ExecutionMode.valueOf(parsed.mode.trim().uppercase())
                }.getOrNull()
                if (mode == null) {
                    return DaemonResponse(400, body = """{"error":"unknown mode: ${parsed.mode}"}""")
                }
                // Real createThread returns ThreadEntity (suspend) — the
                // staged code assigned its (non-existent) String return
                // directly to threadId. Use .id.
                val threadId = parsed.threadId?.trim()?.takeIf { it.isNotBlank() }
                    ?: threadStore.createThread(
                        workspaceId = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID,
                        title = goal.take(60),
                        mode = mode.name,
                    ).id
                // Real startTurn signature:
                // startTurn(context, threadId, goal, mode, maxSteps, runner).
                // The staged call startTurn(threadId, goal) does not exist.
                // The runner drives a real AgentLoop via runnerFactory
                // (ChatViewModel pattern); AgentRuntime itself appends the
                // user goal + final answer as TimelineMessages.
                val accepted = turnStarter.startTurn(threadId, goal, mode) {
                    val factory = runnerFactory
                    if (factory != null) {
                        factory.runTurn(this, threadId, goal, mode)
                    } else {
                        "Turn accepted, but no agent runner is wired. " +
                            "Supply an AgentRunnerFactory built on the ChatViewModel AgentLoop pattern."
                    }
                }
                if (!accepted) {
                    return DaemonResponse(409, body = """{"error":"turn already running for thread"}""")
                }
                val json = CodexJson.encodeToString(
                    TurnResponse.serializer(),
                    TurnResponse(threadId = threadId, accepted = true),
                )
                DaemonResponse(202, body = json)
            }

            request.method == "POST" && request.path == "/v1/stop" -> {
                val parsed = runCatching {
                    CodexJson.decodeFromString(StopRequest.serializer(), request.body)
                }.getOrNull()
                if (parsed == null) {
                    return DaemonResponse(400, body = """{"error":"invalid stop request"}""")
                }
                turnStarter.stop(parsed.threadId)
                DaemonResponse(200, body = """{"stopped":true}""")
            }

            else -> DaemonResponse(404, body = """{"error":"not found"}""")
        }
    }

    private suspend fun handleConnection(client: Socket) {
        client.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(socket.getOutputStream(), true, Charsets.UTF_8)
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore("?")
            val headers = mutableMapOf<String, String>()
            var line: String?
            var contentLength = 0
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val idx = line!!.indexOf(':')
                if (idx > 0) {
                    val key = line!!.substring(0, idx).trim().lowercase()
                    val value = line!!.substring(idx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") contentLength = value.toIntOrNull() ?: 0
                }
            }
            val bodyChars = CharArray(contentLength.coerceAtLeast(0))
            var read = 0
            while (read < bodyChars.size) {
                val n = reader.read(bodyChars, read, bodyChars.size - read)
                if (n <= 0) break
                read += n
            }
            val body = String(bodyChars, 0, read)
            val remoteAddress = socket.inetAddress?.hostAddress ?: ""
            val request = DaemonRequest(method, path, headers, body, remoteAddress)
            val response = dispatch(request)
            writer.print("HTTP/1.1 ${response.status} ${statusText(response.status)}\r\n")
            writer.print("Content-Type: ${response.contentType}\r\n")
            writer.print("Content-Length: ${response.body.toByteArray(Charsets.UTF_8).size}\r\n")
            writer.print("Connection: close\r\n\r\n")
            writer.print(response.body)
            writer.flush()
        }
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        else -> "OK"
    }

    @Serializable
    data class ErrorResponse(val error: String)

    @Serializable
    data class ThreadDto(
        val id: String,
        val title: String,
        val mode: String,
        val status: String,
        val unread: Boolean,
        val updatedAt: Long,
    )

    @Serializable
    data class ThreadListResponse(val threads: List<ThreadDto> = emptyList())

    @Serializable
    data class MessageDto(
        val id: Long,
        val threadId: String,
        val role: String,
        val kind: String,
        val content: String,
        val createdAt: Long,
    )

    @Serializable
    data class MessageListResponse(val messages: List<MessageDto> = emptyList())

    @Serializable
    data class TurnRequest(
        val threadId: String? = null,
        val goal: String = "",
        val mode: String = "AUTO",
    )

    @Serializable
    data class TurnResponse(val threadId: String, val accepted: Boolean)

    @Serializable
    data class StopRequest(val threadId: String)
}

private fun ThreadEntity.toDto(): LocalDaemon.ThreadDto = LocalDaemon.ThreadDto(
    id = id,
    title = title,
    mode = mode,
    status = status,
    unread = unread,
    updatedAt = updatedAt,
)

private fun TimelineMessage.toDto(): LocalDaemon.MessageDto = LocalDaemon.MessageDto(
    id = id,
    threadId = threadId,
    role = role,
    kind = kind,
    content = content,
    createdAt = createdAt,
)
