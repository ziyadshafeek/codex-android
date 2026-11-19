package dev.codex.android.server

import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.ThreadEntity
import dev.codex.android.data.TimelineMessage
import dev.codex.android.security.ExecutionMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for [LocalDaemon]: loopback-only + Bearer
 * validation, route dispatch (no sockets).
 *
 * Rewritten from the staged H5 test, which was uncompilable:
 * `CodexDatabase.createInMemoryForTest()` and
 * `AgentRuntime.createForTest()` do not exist in the repo, the
 * staged `LocalDaemon(agentRuntime, database, port)` constructor
 * cannot work (the real `AgentRuntime.startTurn` needs an Android
 * `Context`, and `AgentRuntime` is a singleton object), and the
 * staged `db.appendMessage(threadId, "user", "text", ...)` overload
 * does not exist — the only overload takes a [TimelineMessage]
 * (constructor order: `id = 0, threadId, role, kind, content,
 * metadataJson = "{}", createdAt`), which is what the fake below
 * uses, with named arguments. This test drives the daemon through
 * its seams ([DaemonThreadStore] + [TurnStarter] fakes): no Android
 * SQLite, no foreground service, no model.
 *
 * Behaviours asserted are the staged ones, adapted to the real API:
 * validation rejects non-loopback / bad bearer / bad host; threads
 * + messages round-trip; a turn creates a thread and is accepted;
 * health + unknown routes. Added: the canonical `/v1/health`
 * route (staged used `/health` only — kept as an alias), constant-
 * time auth (same-length wrong token still 401), invalid JSON /
 * blank goal / unknown mode → 400, and `/v1/stop` reaching the
 * turn starter.
 */
class LocalDaemonTest {

    /** In-memory [DaemonThreadStore]; mirrors the real DB's shapes. */
    private class FakeThreadStore : DaemonThreadStore {
        val threads = mutableListOf<ThreadEntity>()
        val messages = mutableListOf<TimelineMessage>()
        private var counter = 0

        // Overriding functions may NOT redeclare default values
        // (Kotlin forbids it), so the fake's overrides take all
        // parameters explicitly; direct calls on the fake below pass
        // the workspace id explicitly, while calls through the
        // [DaemonThreadStore] interface (e.g. from [LocalDaemon])
        // still get the interface defaults.
        override suspend fun listThreads(workspaceId: String): List<ThreadEntity> =
            threads.filter { it.workspaceId == workspaceId }.toList()

        override suspend fun getMessages(threadId: String, limit: Int): List<TimelineMessage> =
            messages.filter { it.threadId == threadId }.take(limit)

        override suspend fun createThread(
            workspaceId: String,
            title: String,
            mode: String,
        ): ThreadEntity {
            val entity = ThreadEntity(
                id = "thr_test_${++counter}",
                workspaceId = workspaceId,
                title = title,
                mode = mode,
                status = "idle",
                unread = false,
                updatedAt = 123L,
            )
            threads += entity
            return entity
        }

        fun appendMessage(msg: TimelineMessage) {
            messages += msg
        }
    }

    /** Recording [TurnStarter]; never touches the real AgentRuntime. */
    private class FakeTurnStarter : TurnStarter {
        data class Started(val threadId: String, val goal: String, val mode: ExecutionMode)

        val started = mutableListOf<Started>()
        val stopped = mutableListOf<String>()
        var nextResult: Boolean = true

        override fun startTurn(
            threadId: String,
            goal: String,
            mode: ExecutionMode,
            runner: suspend dev.codex.android.agent.AgentRuntime.TurnContext.() -> String,
        ): Boolean {
            started += Started(threadId, goal, mode)
            return nextResult
        }

        override fun stop(threadId: String) {
            stopped += threadId
        }
    }

    private lateinit var store: FakeThreadStore
    private lateinit var starter: FakeTurnStarter
    private lateinit var daemon: LocalDaemon

    @Before
    fun setUp() {
        store = FakeThreadStore()
        starter = FakeTurnStarter()
        daemon = LocalDaemon(store, starter, port = 18787)
    }

    private fun authHeaders(): Map<String, String> = mapOf(
        "host" to "localhost:18787",
        "authorization" to "Bearer ${daemon.bearerToken}",
    )

    private fun authedRequest(method: String, path: String, body: String = ""): DaemonRequest =
        DaemonRequest(method, path, authHeaders(), body, "127.0.0.1")

    @Test
    fun bearerToken_isPerBoot256Bit() {
        // 32 random bytes -> 43 chars of unpadded base64url.
        assertEquals(43, daemon.bearerToken.length)
        assertTrue(daemon.bearerToken.matches(Regex("[A-Za-z0-9_-]{43}")))
    }

    @Test
    fun validateRequest_rejectsNonLoopbackAndBadBearer() {
        val badRemote = authedRequest("GET", "/v1/health").copy(remoteAddress = "192.168.1.5")
        assertEquals(403, daemon.validateRequest(badRemote))

        val badToken = DaemonRequest(
            method = "GET",
            path = "/v1/health",
            headers = mapOf("host" to "localhost", "authorization" to "Bearer wrong-token"),
            body = "",
            remoteAddress = "127.0.0.1",
        )
        assertEquals(401, daemon.validateRequest(badToken))

        // Same-length wrong token: constant-time compare must still reject.
        val sameLengthWrong = "x".repeat(daemon.bearerToken.length)
        val sameLengthRequest = DaemonRequest(
            method = "GET",
            path = "/v1/health",
            headers = mapOf("host" to "localhost", "authorization" to "Bearer $sameLengthWrong"),
            body = "",
            remoteAddress = "127.0.0.1",
        )
        assertEquals(401, daemon.validateRequest(sameLengthRequest))

        val badHost = authedRequest("GET", "/v1/health").copy(
            headers = authHeaders() + ("host" to "evil.example.com"),
        )
        assertEquals(403, daemon.validateRequest(badHost))

        val badOrigin = authedRequest("GET", "/v1/health").copy(
            headers = authHeaders() + ("origin" to "http://evil.example.com"),
        )
        assertEquals(403, daemon.validateRequest(badOrigin))

        assertNull(daemon.validateRequest(authedRequest("GET", "/v1/health")))
    }

    @Test
    fun dispatch_threadsAndMessages_roundTrip() = runBlocking {
        val thread = store.createThread(
            workspaceId = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID,
            title = "Demo",
            mode = "AUTO",
        )
        // TimelineMessage field order (verified against the real data
        // class): id, threadId, role, kind, content, metadataJson, createdAt.
        store.appendMessage(
            TimelineMessage(threadId = thread.id, role = "user", kind = "text", content = "Hello daemon"),
        )

        val threadsResp = daemon.dispatch(authedRequest("GET", "/v1/threads"))
        assertEquals(200, threadsResp.status)
        assertTrue(threadsResp.body.contains(thread.id))

        val messagesResp = daemon.dispatch(authedRequest("GET", "/v1/threads/${thread.id}/messages"))
        assertEquals(200, messagesResp.status)
        assertTrue(messagesResp.body.contains("Hello daemon"))
    }

    @Test
    fun dispatch_turnCreatesThreadAndAccepts() = runBlocking {
        val resp = daemon.dispatch(
            authedRequest("POST", "/v1/turn", """{"goal":"Build a counter app"}"""),
        )
        assertEquals(202, resp.status)
        assertTrue(resp.body.contains("threadId"))

        val threads = store.listThreads(CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID)
        assertEquals(1, threads.size)
        val threadId = threads.single().id
        assertTrue(resp.body.contains(threadId))

        assertEquals(1, starter.started.size)
        assertEquals(threadId, starter.started.single().threadId)
        assertEquals("Build a counter app", starter.started.single().goal)
        assertEquals(ExecutionMode.AUTO, starter.started.single().mode)
    }

    @Test
    fun dispatch_turnWithProvidedThreadId_reusesIt() = runBlocking {
        val thread = store.createThread(
            workspaceId = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID,
            title = "Existing",
            mode = "AUTO",
        )
        val resp = daemon.dispatch(
            authedRequest("POST", "/v1/turn", """{"threadId":"${thread.id}","goal":"Continue"}"""),
        )
        assertEquals(202, resp.status)
        assertEquals(1, store.listThreads(CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID).size)
        assertEquals(thread.id, starter.started.single().threadId)
    }

    @Test
    fun dispatch_turnRejectsBadRequests() = runBlocking {
        val invalidJson = daemon.dispatch(authedRequest("POST", "/v1/turn", """{"goal":}"""))
        assertEquals(400, invalidJson.status)

        val blankGoal = daemon.dispatch(authedRequest("POST", "/v1/turn", """{"goal":"   "}"""))
        assertEquals(400, blankGoal.status)

        val badMode = daemon.dispatch(
            authedRequest("POST", "/v1/turn", """{"goal":"x","mode":"TURBO"}"""),
        )
        assertEquals(400, badMode.status)
        assertTrue(starter.started.isEmpty())
    }

    @Test
    fun dispatch_turnAlreadyRunning_returns409() = runBlocking {
        starter.nextResult = false
        val resp = daemon.dispatch(
            authedRequest("POST", "/v1/turn", """{"goal":"Build a counter app"}"""),
        )
        assertEquals(409, resp.status)
    }

    @Test
    fun dispatch_stopReachesTurnStarter() = runBlocking {
        val resp = daemon.dispatch(
            authedRequest("POST", "/v1/stop", """{"threadId":"thr_test_1"}"""),
        )
        assertEquals(200, resp.status)
        assertTrue(resp.body.contains("true"))
        assertEquals(listOf("thr_test_1"), starter.stopped)
    }

    @Test
    fun dispatch_healthAndUnknownRoutes() = runBlocking {
        val health = daemon.dispatch(authedRequest("GET", "/v1/health"))
        assertEquals(200, health.status)
        assertTrue(health.body.contains("ok"))

        // Legacy alias still answers.
        val legacyHealth = daemon.dispatch(authedRequest("GET", "/health"))
        assertEquals(200, legacyHealth.status)

        val unknown = daemon.dispatch(authedRequest("GET", "/v1/nope"))
        assertEquals(404, unknown.status)

        val unauthenticated = daemon.dispatch(
            DaemonRequest("GET", "/v1/health", mapOf("host" to "localhost"), "", "127.0.0.1"),
        )
        assertEquals(401, unauthenticated.status)
    }
}
