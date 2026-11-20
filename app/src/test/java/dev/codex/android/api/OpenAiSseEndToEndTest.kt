package dev.codex.android.api

import dev.codex.android.agent.AgentEvent
import dev.codex.android.agent.AgentLoop
import dev.codex.android.agent.Plan
import dev.codex.android.agent.PlanRepository
import dev.codex.android.security.DefaultApprovalGate
import dev.codex.android.tools.ToolRegistry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate test: the agent loop end to end over the REAL OpenAI-compatible SSE
 * protocol, with no network and no key.
 *
 * A canned [Interceptor] answers `{baseUrl}/chat/completions` with a
 * byte-for-byte SSE stream (`data: {...}` frames + `data: [DONE]`), exactly
 * as a streaming provider would. The production [OkHttpOpenAiClient] parses
 * it (HTTP framing, Content-Type gate, [SseParser], delta accumulation) and
 * the production [AgentLoop] consumes the result. Nothing here fakes the
 * client or the loop: only the transport is canned.
 *
 * Plain JUnit 4 + [runBlocking] only.
 */
class OpenAiSseEndToEndTest {

    /** Byte-for-byte SSE payload a streaming provider returns. */
    private val ssePayload = """
        data: {"choices":[{"delta":{"content":"Hello "}}]}

        data: {"choices":[{"delta":{"content":"world"}}]}

        data: {"choices":[{"finish_reason":"stop","delta":{}}]}

        data: [DONE]

    """.trimIndent()

    private class CapturingSseInterceptor(private val payload: String) : Interceptor {
        var requestUrl: String? = null
        var authorization: String? = null
        var requestBody: String? = null
        var calls: Int = 0

        override fun intercept(chain: Interceptor.Chain): Response {
            calls++
            val request = chain.request()
            requestUrl = request.url.toString()
            authorization = request.header("Authorization")
            requestBody = request.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "text/event-stream")
                .body(payload.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }
    }

    private class InMemoryPlanRepository : PlanRepository {
        var latest: Plan? = null
        override suspend fun loadLatest(): Plan? = latest
        override suspend fun save(plan: Plan) {
            latest = plan
        }

        override suspend fun clear() {
            latest = null
        }
    }

    private fun sseClient(interceptor: CapturingSseInterceptor): OkHttpOpenAiClient {
        val httpClient = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
        return OkHttpOpenAiClient(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            httpClient = httpClient,
        )
    }

    @Test
    fun sseStream_drivesRealClient_returnsFullTextAndPartials() = runBlocking {
        val interceptor = CapturingSseInterceptor(ssePayload)
        val client = sseClient(interceptor)
        val partials = mutableListOf<String>()

        val result = client.chat(
            messages = listOf(ChatMessage(role = "user", content = "Say hello")),
            tools = emptyList(),
            model = "test-model",
            onPartialText = { partials.add(it) },
        )

        assertEquals(1, interceptor.calls)
        assertEquals("https://example.invalid/v1/chat/completions", interceptor.requestUrl)
        assertEquals("Bearer test-key", interceptor.authorization)
        assertTrue(
            "request must ask for a stream",
            interceptor.requestBody?.contains("\"stream\":true") == true,
        )
        val choice = result.choices.single()
        assertEquals("Hello world", choice.text)
        assertEquals("stop", choice.finishReason)
        assertTrue(choice.toolCalls.isEmpty())
        assertEquals(listOf("Hello ", "world"), partials)
    }

    @Test
    fun agentLoop_consumesRealSseStream_returnsFinalAnswer() = runBlocking {
        val interceptor = CapturingSseInterceptor(ssePayload)
        val client = sseClient(interceptor)
        val loop = AgentLoop(
            client = client,
            tools = ToolRegistry(),
            gate = DefaultApprovalGate(),
            planStore = InMemoryPlanRepository(),
            model = "test-model",
        )
        val events = Channel<AgentEvent>(Channel.UNLIMITED)

        val answer = loop.run("Say hello", events)
        events.close()
        val drained = mutableListOf<AgentEvent>()
        for (event in events) drained.add(event)

        assertEquals("Hello world", answer)
        assertEquals(1, interceptor.calls)
        assertTrue(
            "loop must emit the final answer",
            drained.filterIsInstance<AgentEvent.Finished>().single().answer == "Hello world",
        )
        assertTrue(
            "loop must surface the streamed text",
            drained.filterIsInstance<AgentEvent.AssistantText>().any { it.text == "Hello world" },
        )
    }
}
