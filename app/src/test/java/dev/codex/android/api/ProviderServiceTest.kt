package dev.codex.android.api

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * JVM unit tests for [ProviderService].
 *
 * No real network, no real API key: every HTTP call is answered by a fake
 * [Interceptor] returning canned JSON (or throwing [IOException] to simulate
 * a dead host). Suspended calls are driven with plain [runBlocking], matching
 * the existing test convention (see ApprovalGateTest).
 */
class ProviderServiceTest {

    private val jsonMediaType = "application/json".toMediaType()

    private fun fakeClient(
        code: Int,
        body: String,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("fake")
                    .body(body.toResponseBody(jsonMediaType))
                    .build()
            },
        )
        .build()

    private fun throwingClient(message: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { throw IOException(message) },
        )
        .build()

    @Test
    fun presetsUseExactRequestedEndpointsAndModels() {
        assertEquals("https://coarena.ai/api/v1", ProviderPreset.COARENA.baseUrl)
        assertEquals("gpt-6-luna", ProviderPreset.COARENA.model)
        assertEquals("https://integrate.api.nvidia.com/v1", ProviderPreset.NVIDIA_NIM.baseUrl)
        assertEquals("nvidia/nemotron-3-ultra-550b-a55b", ProviderPreset.NVIDIA_NIM.model)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/", ProviderPreset.AI_STUDIO.baseUrl)
        assertEquals("gemini-4-hydrogen", ProviderPreset.AI_STUDIO.model)
        assertEquals(ProviderPreset.AI_STUDIO, ProviderPreset.forUrl("https://generativelanguage.googleapis.com/v1beta/openai"))
        assertEquals(ProviderPreset.CUSTOM, ProviderPreset.forUrl(""))
    }

    @Test
    fun blankApiKeyNeverMakesARequest() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { throw AssertionError("Must not request without a key") }.build()
        val result = ProviderService(client).testAndFetchModels("https://api.example.com/v1", " ")
        assertEquals(ConnectionTestResult.AuthError(0, "Check your API key"), result)
    }

    @Test
    fun listModelsSendsBearerAndRetriesAfterRateLimit() = runBlocking {
        val times = mutableListOf<Long>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("GET", request.method)
            assertEquals("/v1beta/openai/models", request.url.encodedPath)
            assertEquals("Bearer test-key", request.header("Authorization"))
            times += System.nanoTime()
            val limited = times.size == 1
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (limited) 429 else 200).message("Test")
                .header("Retry-After", "1")
                .body("""{"data":[{"id":"gemini-4-hydrogen"}]}""".toResponseBody(jsonMediaType)).build()
        }.build()
        val result = ProviderService(client).testAndFetchModels(ProviderPreset.AI_STUDIO.baseUrl!!, " test-key ")
        assertEquals(listOf("gemini-4-hydrogen"), (result as ConnectionTestResult.Success).modelIds)
        assertEquals(2, times.size)
        assertTrue("Retry was sent before Retry-After elapsed", times[1] - times[0] >= 950_000_000L)
    }

    // --- normalizeBaseUrl ---

    @Test
    fun normalizeBaseUrl_trailingSlashesRemoved() {
        val service = ProviderService()
        assertEquals(
            "https://api.example.com/v1",
            service.normalizeBaseUrl("https://api.example.com/v1///"),
        )
    }

    @Test
    fun normalizeBaseUrl_chatCompletionsSuffixRemoved() {
        val service = ProviderService()
        assertEquals(
            "https://api.example.com/v1",
            service.normalizeBaseUrl("https://api.example.com/v1/chat/completions"),
        )
        assertEquals(
            "https://api.example.com/v1",
            service.normalizeBaseUrl("https://api.example.com/v1/chat/completions/"),
        )
    }

    @Test
    fun normalizeBaseUrl_v1SuffixPreserved() {
        // /v1 must stay: the models endpoint lives under it
        // (https://api.openai.com/v1/models). Stripping it would 404.
        val service = ProviderService()
        assertEquals(
            "https://api.openai.com/v1",
            service.normalizeBaseUrl("https://api.openai.com/v1"),
        )
    }

    @Test
    fun normalizeBaseUrl_alreadyCleanUnchanged() {
        val service = ProviderService()
        assertEquals(
            "https://api.example.com/v1",
            service.normalizeBaseUrl("  https://api.example.com/v1  "),
        )
    }

    // --- parseModelIds via testAndFetchModels (OpenAI + Ollama shapes) ---

    @Test
    fun testConnection_openAiShape_returnsSortedDistinctIds() = runBlocking {
        val body = """{"object":"list","data":[
            {"id":"gpt-4o","object":"model"},
            {"id":"gpt-4o-mini","object":"model"},
            {"id":"gpt-4o","object":"model"}
        ]}"""
        val service = ProviderService(fakeClient(200, body))
        val result = service.testAndFetchModels("https://api.example.com/v1", "fake-key")

        assertTrue("expected Success, got $result", result is ConnectionTestResult.Success)
        val success = result as ConnectionTestResult.Success
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), success.modelIds)
        assertTrue("latency must be non-negative", success.latencyMs >= 0)
    }

    @Test
    fun testConnection_ollamaShape_returnsSortedDistinctNames() = runBlocking {
        val body = """{"models":[
            {"name":"llama3.1:latest"},
            {"name":"mistral:7b"},
            {"name":"llama3.1:latest"}
        ]}"""
        val service = ProviderService(fakeClient(200, body))
        val result = service.testAndFetchModels("http://localhost:11434/api", "fake-key")

        assertTrue("expected Success, got $result", result is ConnectionTestResult.Success)
        assertEquals(
            listOf("llama3.1:latest", "mistral:7b"),
            (result as ConnectionTestResult.Success).modelIds,
        )
    }

    @Test
    fun testConnection_emptyData_returnsEmptyList() = runBlocking {
        val service = ProviderService(fakeClient(200, """{"data":[]}"""))
        val result = service.testAndFetchModels("https://api.example.com/v1", "fake-key")

        assertTrue("expected Success, got $result", result is ConnectionTestResult.Success)
        assertEquals(emptyList<String>(), (result as ConnectionTestResult.Success).modelIds)
    }

    @Test
    fun testConnection_alias_delegatesToTestAndFetchModels() = runBlocking {
        val body = """{"data":[{"id":"model-a"}]}"""
        val service = ProviderService(fakeClient(200, body))
        val result = service.testConnection("https://api.example.com/v1", "fake-key")

        assertTrue("expected Success, got $result", result is ConnectionTestResult.Success)
        assertEquals(listOf("model-a"), (result as ConnectionTestResult.Success).modelIds)
    }

    // --- HTTP error mapping ---

    @Test
    fun testConnection_401_returnsAuthError() = runBlocking {
        val service = ProviderService(fakeClient(401, """{"error":{"message":"bad key"}}"""))
        val result = service.testAndFetchModels("https://api.example.com/v1", "wrong-key")

        assertTrue("expected AuthError, got $result", result is ConnectionTestResult.AuthError)
        assertEquals(401, (result as ConnectionTestResult.AuthError).httpCode)
    }

    @Test
    fun testConnection_403_returnsAuthError() = runBlocking {
        val service = ProviderService(fakeClient(403, """{"error":{"message":"forbidden"}}"""))
        val result = service.testAndFetchModels("https://api.example.com/v1", "wrong-key")

        assertTrue("expected AuthError, got $result", result is ConnectionTestResult.AuthError)
        assertEquals(403, (result as ConnectionTestResult.AuthError).httpCode)
    }

    @Test
    fun testConnection_404_returnsEndpointError() = runBlocking {
        val service = ProviderService(fakeClient(404, "not found"))
        val result = service.testAndFetchModels("https://api.example.com/wrong", "fake-key")

        assertTrue("expected EndpointError, got $result", result is ConnectionTestResult.EndpointError)
        assertEquals(404, (result as ConnectionTestResult.EndpointError).httpCode)
    }

    @Test
    fun testConnection_ioException_returnsNetworkError() = runBlocking {
        val service = ProviderService(throwingClient("Unable to resolve host"))
        val result = service.testAndFetchModels("https://bad-host.invalid/v1", "fake-key")

        assertTrue("expected NetworkError, got $result", result is ConnectionTestResult.NetworkError)
        assertEquals("Unable to resolve host", (result as ConnectionTestResult.NetworkError).message)
    }

    @Test
    fun testConnection_blankBaseUrl_returnsEndpointError() = runBlocking {
        val service = ProviderService(fakeClient(200, """{"data":[]}"""))
        val result = service.testAndFetchModels("   ", "fake-key")

        assertTrue("expected EndpointError, got $result", result is ConnectionTestResult.EndpointError)
        assertEquals(0, (result as ConnectionTestResult.EndpointError).httpCode)
    }

    @Test
    fun testConnection_nonHttpScheme_returnsEndpointError() = runBlocking {
        val service = ProviderService(fakeClient(200, """{"data":[]}"""))
        val result = service.testAndFetchModels("ftp://api.example.com/v1", "fake-key")

        assertTrue("expected EndpointError, got $result", result is ConnectionTestResult.EndpointError)
        assertEquals(0, (result as ConnectionTestResult.EndpointError).httpCode)
    }
}
