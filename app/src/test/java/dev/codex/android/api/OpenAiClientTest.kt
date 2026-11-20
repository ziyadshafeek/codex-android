package dev.codex.android.api

import dev.codex.android.core.CodexError
import dev.codex.android.core.RetryPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM unit tests for [OkHttpOpenAiClient]: [OkHttpOpenAiClient.normaliseBaseUrl]
 * plus the 429 / Retry-After behaviour. No real network: every HTTP call is
 * answered by a fake [Interceptor]. Plain [runBlocking] only.
 */
class OpenAiClientTest {

    @Test
    fun plainBaseUrl_isUnchanged() {
        assertEquals(
            "https://api.openai.com/v1",
            OkHttpOpenAiClient.normaliseBaseUrl("https://api.openai.com/v1"),
        )
    }

    @Test
    fun trailingSlash_isTrimmed() {
        assertEquals(
            "https://api.openai.com/v1",
            OkHttpOpenAiClient.normaliseBaseUrl("https://api.openai.com/v1/"),
        )
    }

    @Test
    fun fullEndpointSuffix_isStripped() {
        assertEquals(
            "https://api.openai.com/v1",
            OkHttpOpenAiClient.normaliseBaseUrl("https://api.openai.com/v1/chat/completions"),
        )
        assertEquals(
            "https://api.openai.com/v1",
            OkHttpOpenAiClient.normaliseBaseUrl("https://api.openai.com/v1/chat/completions/"),
        )
    }

    @Test
    fun whitespace_isTrimmed() {
        assertEquals(
            "https://example.com/v1",
            OkHttpOpenAiClient.normaliseBaseUrl("  https://example.com/v1/chat/completions  "),
        )
    }

    private fun fakeResponse(
        request: Request,
        code: Int,
        contentType: String,
        body: String,
        retryAfter: String? = null,
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("fake")
            .header("Content-Type", contentType)
            .body(body.toResponseBody(contentType.toMediaType()))
        if (retryAfter != null) builder.header("Retry-After", retryAfter)
        return builder.build()
    }

    private fun client(
        handler: (Request) -> Response,
        retryPolicy: RetryPolicy = RetryPolicy(),
        onRateLimitWait: ((Long) -> Unit)? = null,
    ): OkHttpOpenAiClient {
        val httpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()
        return OkHttpOpenAiClient(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            httpClient = httpClient,
            retryPolicy = retryPolicy,
            onRateLimitWait = onRateLimitWait,
        )
    }

    private val successJson =
        """{"choices":[{"message":{"content":"hi"},"finish_reason":"stop"}]}"""

    @Test
    fun rateLimit429_withoutRetries_throwsRateLimitWithSeconds() = runBlocking {
        // A 429 with Retry-After: 2 must surface as CodexError.RateLimit(2).
        val client = client(
            handler = { request ->
                fakeResponse(request, 429, "application/json", "{}", retryAfter = "2")
            },
            retryPolicy = RetryPolicy(maxAttempts = 1),
        )
        try {
            client.chat(
                messages = listOf(ChatMessage(role = "user", content = "hi")),
                tools = emptyList(),
                model = "test-model",
            )
            fail("Expected CodexError.RateLimit")
        } catch (e: CodexError.RateLimit) {
            assertEquals(2L, e.retryAfterSeconds)
        }
    }

    @Test
    fun rateLimit429_withRetryAfterHeader_waitsItOutThenSucceeds() = runBlocking {
        // The bug: the client retried after ~0.5s, burned all attempts,
        // and failed the turn. Now the 2s Retry-After is waited out and
        // the turn succeeds on the next attempt.
        var calls = 0
        val waits = mutableListOf<Long>()
        val client = client(
            handler = { request ->
                calls++
                if (calls == 1) {
                    fakeResponse(request, 429, "application/json", "{}", retryAfter = "2")
                } else {
                    fakeResponse(request, 200, "application/json", successJson)
                }
            },
            onRateLimitWait = { waits += it },
        )
        val startMs = System.currentTimeMillis()
        val result = client.chat(
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            tools = emptyList(),
            model = "test-model",
        )
        val elapsedMs = System.currentTimeMillis() - startMs
        assertEquals("hi", result.choices.single().text)
        assertEquals(2, calls)
        assertEquals("one rate-limit wait before the successful attempt", 1, waits.size)
        assertTrue("onRateLimitWait must receive >= 2000ms, was ${waits[0]}", waits[0] >= 2000L)
        assertTrue("elapsed must be >= 2s, was $elapsedMs", elapsedMs >= 2000L)
    }

    @Test
    fun networkRetry_doesNotInvokeOnRateLimitWait() = runBlocking {
        // onRateLimitWait fires ONLY for rate limits, never for other
        // retryable errors (the countdown UI must not appear for those).
        var calls = 0
        val waits = mutableListOf<Long>()
        val client = client(
            handler = { request ->
                calls++
                if (calls == 1) {
                    fakeResponse(request, 500, "application/json", "{}", retryAfter = null)
                } else {
                    fakeResponse(request, 200, "application/json", successJson)
                }
            },
            onRateLimitWait = { waits += it },
        )
        val result = client.chat(
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            tools = emptyList(),
            model = "test-model",
        )
        assertEquals("hi", result.choices.single().text)
        assertEquals(2, calls)
        assertTrue("onRateLimitWait must stay silent for a 500 retry", waits.isEmpty())
    }
}
