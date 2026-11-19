package dev.codex.android.api

import dev.codex.android.core.CodexError
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM unit tests for [MultiProviderEngine].
 *
 * No real network, no real API key: every HTTP call is answered by a fake
 * [Interceptor] routing on request host/path (profile A lives at
 * a.example.com, profile B at b.example.com). Suspended calls and Flows
 * are driven with plain [runBlocking], matching the existing test
 * convention (see ProviderServiceTest).
 */
class MultiProviderEngineTest {

    private val profileA = ProviderProfile(
        id = "a",
        name = "Alpha",
        baseUrl = "https://a.example.com/v1",
        model = "model-a",
    )
    private val profileB = ProviderProfile(
        id = "b",
        name = "Beta",
        baseUrl = "https://b.example.com/v1",
        model = "model-b",
        reasoningEffort = ReasoningEffort.HIGH,
    )

    private val userMessage = listOf(ChatMessage(role = "user", content = "hi"))

    private val sseText = """
        data: {"choices":[{"delta":{"content":"Hello "}}]}

        data: {"choices":[{"delta":{"content":"world"}}]}

        data: {"choices":[{"finish_reason":"stop","delta":{}}]}

        data: [DONE]

    """.trimIndent()

    /** Tool-call arguments split across two SSE events; must assemble to {"path":"a.txt"}. */
    private val sseToolCall = """
        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":"{\"path\":"}}]}}]}

        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"a.txt\"}"}}]}}]}

        data: {"choices":[{"finish_reason":"tool_calls","delta":{}}]}

        data: [DONE]

    """.trimIndent()

    /** handler answers each request with (HTTP code, content type, body). */
    private fun fakeClient(handler: (Request) -> Triple<Int, String, String>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val (code, contentType, body) = handler(request)
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("fake")
                        .body(body.toResponseBody(contentType.toMediaType()))
                        .build()
                },
            )
            .build()

    private fun streamingClient(
        handler: (Request) -> Triple<Int, String, String>,
    ): OkHttpClient = fakeClient(handler)

    private fun engine(
        client: OkHttpClient,
        keys: Map<String, String?> = mapOf("a" to "key-a", "b" to "key-b"),
        profiles: List<ProviderProfile> = listOf(profileA, profileB),
        providerService: ProviderService = ProviderService(),
    ): MultiProviderEngine = MultiProviderEngine(
        providerService = providerService,
        httpClient = client,
        apiKeyProvider = { keys[it.id] },
        initialProfiles = profiles,
    )

    private fun requestBodyUtf8(request: Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    // --- Profile management ---

    @Test
    fun profiles_addListSwitchActive() {
        val engine = MultiProviderEngine(initialProfiles = listOf(profileA))
        assertEquals(listOf(profileA), engine.profiles())
        assertEquals(profileA, engine.activeProfile())

        engine.addProfile(profileB)
        assertEquals(listOf(profileA, profileB), engine.profiles())
        assertEquals(profileA, engine.activeProfile())

        assertTrue(engine.setActiveProfile("b"))
        assertEquals(profileB, engine.activeProfile())
        assertFalse(engine.setActiveProfile("missing"))
        assertEquals(profileB, engine.activeProfile())
    }

    @Test
    fun profiles_firstAddedBecomesActive() {
        val engine = MultiProviderEngine()
        assertNull(engine.activeProfile())
        engine.addProfile(profileB)
        assertEquals(profileB, engine.activeProfile())
    }

    @Test
    fun profiles_upsertReplacesSameId() {
        val engine = MultiProviderEngine(initialProfiles = listOf(profileA))
        engine.addProfile(profileA.copy(model = "model-a2"))
        assertEquals(1, engine.profiles().size)
        assertEquals("model-a2", engine.profiles()[0].model)
        // Active profile is still "a", now holding the replaced copy.
        assertEquals("a", engine.activeProfile()?.id)
        assertEquals("model-a2", engine.activeProfile()?.model)
    }

    @Test
    fun profiles_removeActiveFallsBackToFirstRemaining() {
        val engine = MultiProviderEngine(initialProfiles = listOf(profileA, profileB))
        assertTrue(engine.removeProfile("a"))
        assertEquals(profileB, engine.activeProfile())
        assertTrue(engine.removeProfile("b"))
        assertNull(engine.activeProfile())
        assertTrue(engine.profiles().isEmpty())
        assertFalse(engine.removeProfile("b"))
    }

    @Test
    fun profiles_blankIdRejected() {
        val engine = MultiProviderEngine()
        assertThrows(IllegalArgumentException::class.java) {
            engine.addProfile(profileA.copy(id = "  "))
        }
    }

    @Test
    fun profiles_blankBaseUrlOrModelRejected() {
        val engine = MultiProviderEngine()
        assertThrows(IllegalArgumentException::class.java) {
            engine.addProfile(profileA.copy(baseUrl = "   "))
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.addProfile(profileA.copy(model = ""))
        }
    }

    @Test
    fun profiles_baseUrlNormalisedOnAdd() {
        val engine = MultiProviderEngine()
        val added = engine.addProfile(profileA.copy(baseUrl = "https://a.example.com/v1/chat/completions/"))
        assertEquals("https://a.example.com/v1", added.baseUrl)
    }

    @Test
    fun profiles_updateReasoningEffortAndModel() {
        val engine = MultiProviderEngine(initialProfiles = listOf(profileA))
        assertTrue(engine.updateReasoningEffort("a", ReasoningEffort.LOW))
        assertEquals(ReasoningEffort.LOW, engine.profiles()[0].reasoningEffort)
        assertTrue(engine.updateModel("a", "model-a2"))
        assertEquals("model-a2", engine.profiles()[0].model)
        assertFalse(engine.updateReasoningEffort("missing", ReasoningEffort.HIGH))
        assertFalse(engine.updateModel("missing", "x"))
        assertFalse(engine.updateModel("a", "   "))
    }

    // --- Reasoning effort wire value ---

    @Test
    fun reasoningEffortWireValue_mediumOmitted_lowHighSent() {
        assertEquals("low", reasoningEffortWireValue(ReasoningEffort.LOW))
        assertNull(reasoningEffortWireValue(ReasoningEffort.MEDIUM))
        assertEquals("high", reasoningEffortWireValue(ReasoningEffort.HIGH))
    }

    @Test
    fun streamChat_requestBodyCarriesReasoningEffortOnlyWhenNonDefault() = runBlocking {
        val bodies = mutableMapOf<String, String>()
        val client = streamingClient { request ->
            bodies[request.url.host] = requestBodyUtf8(request)
            Triple(200, "text/event-stream", sseText)
        }
        // Profile B is HIGH: effort must be on the wire.
        engine(client, profiles = listOf(profileB)).streamChat(userMessage).toList()
        val bodyB = bodies["b.example.com"].orEmpty()
        assertTrue("expected reasoning_effort high in: $bodyB", bodyB.contains("\"reasoning_effort\":\"high\""))
        assertTrue(bodyB.contains("\"model\":\"model-b\""))
        assertTrue(bodyB.contains("\"stream\":true"))

        // Profile A is MEDIUM (default): effort must be omitted.
        bodies.clear()
        engine(client, profiles = listOf(profileA)).streamChat(userMessage).toList()
        val bodyA = bodies["a.example.com"].orEmpty()
        assertFalse("MEDIUM effort must be omitted, got: $bodyA", bodyA.contains("reasoning_effort"))
    }

    // --- Streaming: happy paths ---

    @Test
    fun streamChat_textDeltasAndFinished() = runBlocking {
        val client = streamingClient { request ->
            assertEquals("/v1/chat/completions", request.url.encodedPath)
            assertEquals("Bearer key-b", request.header("Authorization"))
            Triple(200, "text/event-stream", sseText)
        }
        val testEngine = engine(client, profiles = listOf(profileB))
        val events = testEngine.streamChat(userMessage).toList()

        assertEquals(
            listOf("Hello ", "world"),
            events.filterIsInstance<ChatStreamEvent.TextDelta>().map { it.text },
        )
        assertEquals(
            listOf(ChatStreamEvent.Started("b", "Beta")),
            events.filterIsInstance<ChatStreamEvent.Started>(),
        )
        val finished = events.filterIsInstance<ChatStreamEvent.Finished>()
        assertEquals(1, finished.size)
        assertEquals("stop", finished[0].finishReason)
        assertEquals("b", finished[0].profileId)
        assertEquals("Beta", finished[0].profileName)
        assertTrue(events.filterIsInstance<ChatStreamEvent.ToolCallComplete>().isEmpty())
    }

    @Test
    fun streamChat_toolCallFragmentsAssembleAcrossEvents() = runBlocking {
        val client = streamingClient { Triple(200, "text/event-stream", sseToolCall) }
        val events = engine(client, profiles = listOf(profileB)).streamChat(userMessage).toList()

        val fragments = events.filterIsInstance<ChatStreamEvent.ToolCallDelta>()
        assertEquals(2, fragments.size)
        assertEquals("call_1", fragments[0].id)
        assertEquals("read_file", fragments[0].name)
        assertEquals("{\"path\":", fragments[0].argumentsDelta)
        assertNull(fragments[1].id)
        assertEquals("\"a.txt\"}", fragments[1].argumentsDelta)

        val completed = events.filterIsInstance<ChatStreamEvent.ToolCallComplete>()
        assertEquals(1, completed.size)
        assertEquals("call_1", completed[0].toolCall.id)
        assertEquals("read_file", completed[0].toolCall.function.name)
        assertEquals("{\"path\":\"a.txt\"}", completed[0].toolCall.function.arguments)

        val finished = events.filterIsInstance<ChatStreamEvent.Finished>()
        assertEquals("tool_calls", finished.single().finishReason)
    }

    @Test
    fun streamChat_emptyStreamFinishesWithNullReason() = runBlocking {
        val client = streamingClient { Triple(200, "text/event-stream", "data: [DONE]\n") }
        val events = engine(client, profiles = listOf(profileB)).streamChat(userMessage).toList()
        assertEquals(1, events.filterIsInstance<ChatStreamEvent.Finished>().size)
        assertNull(events.filterIsInstance<ChatStreamEvent.Finished>().single().finishReason)
        assertTrue(events.filterIsInstance<ChatStreamEvent.TextDelta>().isEmpty())
    }

    @Test
    fun streamChat_nonStreamingJsonBodyDegradesGracefully() = runBlocking {
        val jsonBody = """{"choices":[{"message":{"content":"Hi there","tool_calls":[]},"finish_reason":"stop"}]}"""
        val client = streamingClient { Triple(200, "application/json", jsonBody) }
        val events = engine(client, profiles = listOf(profileB)).streamChat(userMessage).toList()
        assertEquals(
            listOf("Hi there"),
            events.filterIsInstance<ChatStreamEvent.TextDelta>().map { it.text },
        )
        assertEquals("stop", events.filterIsInstance<ChatStreamEvent.Finished>().single().finishReason)
    }

    // --- Streaming: failover ---

    @Test
    fun streamChat_authFailureFailsOverToNextProfile() = runBlocking {
        val hits = mutableListOf<String>()
        val client = streamingClient { request ->
            hits += request.url.host
            when (request.url.host) {
                "a.example.com" -> Triple(401, "application/json", """{"error":{"message":"bad key"}}""")
                else -> Triple(200, "text/event-stream", sseText)
            }
        }
        val events = engine(client).streamChat(userMessage).toList()

        assertEquals(listOf("a.example.com", "b.example.com"), hits)
        assertEquals(
            listOf(ChatStreamEvent.Started("a", "Alpha"), ChatStreamEvent.Started("b", "Beta")),
            events.filterIsInstance<ChatStreamEvent.Started>(),
        )
        val failed = events.filterIsInstance<ChatStreamEvent.ProfileFailed>()
        assertEquals(1, failed.size)
        assertEquals("a", failed[0].profileId)
        assertTrue("expected Auth error, got ${failed[0].error}", failed[0].error is CodexError.Auth)
        // The successful profile is reported on Finished.
        assertEquals("b", events.filterIsInstance<ChatStreamEvent.Finished>().single().profileId)
        assertEquals(
            listOf("Hello ", "world"),
            events.filterIsInstance<ChatStreamEvent.TextDelta>().map { it.text },
        )
    }

    @Test
    fun streamChat_requestedProfileTriedFirst() = runBlocking {
        val hits = mutableListOf<String>()
        val client = streamingClient { request ->
            hits += request.url.host
            Triple(200, "text/event-stream", sseText)
        }
        // Active is A, but B is requested: B must be tried first and win.
        engine(client).streamChat(userMessage, profileId = "b").toList()
        assertEquals(listOf("b.example.com"), hits)
    }

    @Test
    fun streamChat_missingKeySkipsProfileWithoutHttpCall() = runBlocking {
        val hits = mutableListOf<String>()
        val client = streamingClient { request ->
            hits += request.url.host
            Triple(200, "text/event-stream", sseText)
        }
        // Profile A has no key; only B may be called.
        val testEngine = engine(client, keys = mapOf("a" to null, "b" to "key-b"))
        val events = testEngine.streamChat(userMessage).toList()
        assertEquals(listOf("b.example.com"), hits)
        val failed = events.filterIsInstance<ChatStreamEvent.ProfileFailed>()
        assertEquals(1, failed.size)
        assertEquals("a", failed[0].profileId)
        assertTrue(failed[0].error is CodexError.Auth)
        assertEquals("b", events.filterIsInstance<ChatStreamEvent.Finished>().single().profileId)
    }

    @Test
    fun streamChat_allProfilesFailThrowsStreamFailedWithAttempts() = runBlocking {
        val client = streamingClient { request ->
            when (request.url.host) {
                "a.example.com" -> Triple(401, "application/json", """{"error":{"message":"bad key"}}""")
                else -> Triple(500, "application/json", "boom")
            }
        }
        try {
            engine(client).streamChat(userMessage).toList()
            fail("expected StreamFailedException")
        } catch (e: StreamFailedException) {
            assertEquals(listOf("a", "b"), e.attempts.map { it.profileId })
            assertTrue(e.attempts[0].error is CodexError.Auth)
            assertTrue(e.attempts[1].error is CodexError.Network)
            assertTrue(e.message!!.contains("Alpha"))
            assertTrue(e.message!!.contains("Beta"))
        }
    }

    @Test
    fun streamChat_networkFailureFailsOver() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    if (request.url.host == "a.example.com") {
                        throw java.io.IOException("Unable to resolve host")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("fake")
                        .body(sseText.toResponseBody("text/event-stream".toMediaType()))
                        .build()
                },
            )
            .build()
        val events = engine(client).streamChat(userMessage).toList()
        assertEquals("b", events.filterIsInstance<ChatStreamEvent.Finished>().single().profileId)
        val failed = events.filterIsInstance<ChatStreamEvent.ProfileFailed>()
        assertEquals(1, failed.size)
        assertTrue(failed[0].error is CodexError.Network)
    }

    @Test
    fun streamChat_nonRetryableErrorAbortsChainImmediately() = runBlocking {
        val hits = mutableListOf<String>()
        val client = streamingClient { request ->
            hits += request.url.host
            Triple(400, "application/json", """{"error":{"message":"bad request"}}""")
        }
        try {
            engine(client).streamChat(userMessage).toList()
            fail("expected CodexError.Unknown for HTTP 400")
        } catch (e: CodexError.Unknown) {
            // Expected: a 400 would fail on every profile, so no failover.
        }
        assertEquals(listOf("a.example.com"), hits)
    }

    @Test
    fun streamChat_noProfilesThrows() {
        val client = streamingClient { Triple(200, "text/event-stream", sseText) }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { engine(client, profiles = emptyList()).streamChat(userMessage).toList() }
        }
    }

    // --- Connection test / /models delegation ---

    @Test
    fun testConnection_delegatesToProviderService() = runBlocking {
        val modelsBody = """{"object":"list","data":[{"id":"model-b"},{"id":"model-a"}]}"""
        val modelsClient = fakeClient { request ->
            assertEquals("/v1/models", request.url.encodedPath)
            Triple(200, "application/json", modelsBody)
        }
        val testEngine = engine(
            client = modelsClient,
            providerService = ProviderService(modelsClient),
        )
        val result = testEngine.testConnection(profileB, "key-b")
        assertTrue("expected Success, got $result", result is ConnectionTestResult.Success)
        assertEquals(listOf("model-a", "model-b"), (result as ConnectionTestResult.Success).modelIds)
    }

    @Test
    fun fetchModels_aliasMatchesTestConnection() = runBlocking {
        val modelsClient = fakeClient { Triple(200, "application/json", """{"data":[{"id":"m1"}]}""") }
        val testEngine = engine(
            client = modelsClient,
            providerService = ProviderService(modelsClient),
        )
        val result = testEngine.fetchModels(profileA, "key-a")
        assertTrue(result is ConnectionTestResult.Success)
        assertEquals(listOf("m1"), (result as ConnectionTestResult.Success).modelIds)
    }

    @Test
    fun testActiveConnection_noStoredKeyReturnsAuthError() = runBlocking {
        val testEngine = engine(
            client = streamingClient { Triple(200, "text/event-stream", sseText) },
            keys = mapOf("a" to null, "b" to "key-b"),
        )
        val result = testEngine.testActiveConnection()
        assertTrue("expected AuthError, got $result", result is ConnectionTestResult.AuthError)
        assertEquals(0, (result as ConnectionTestResult.AuthError).httpCode)
    }

    @Test
    fun testActiveConnection_noActiveProfileReturnsNull() = runBlocking {
        val testEngine = engine(
            client = streamingClient { Triple(200, "text/event-stream", sseText) },
            profiles = emptyList(),
        )
        assertNull(testEngine.testActiveConnection())
    }
}
