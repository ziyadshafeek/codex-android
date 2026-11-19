package dev.codex.android.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
enum class ReasoningEffort { LOW, MEDIUM, HIGH }

/**
 * Non-secret provider metadata. The actual API key is stored ONLY in
 * encrypted KeyStore under "profile_key_<id>" and never serialized here.
 */
@Serializable
data class ProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
)

sealed class ConnectionTestResult {
    data class Success(val modelIds: List<String>, val latencyMs: Long) : ConnectionTestResult()
    data class AuthError(val httpCode: Int, val message: String) : ConnectionTestResult()
    data class EndpointError(val httpCode: Int, val message: String) : ConnectionTestResult()
    data class NetworkError(val message: String) : ConnectionTestResult()
}

class ProviderService(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Normalizes user-entered base URLs: trims whitespace, removes trailing slashes,
     * and strips a mistakenly pasted "/chat/completions" suffix.
     *
     * A trailing "/v1" is deliberately PRESERVED: the models endpoint lives
     * under it (e.g. https://api.openai.com/v1/models), so stripping it
     * would break the request.
     */
    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (url.endsWith("/chat/completions")) {
            url = url.removeSuffix("/chat/completions").trimEnd('/')
        }
        return url
    }

    /**
     * Alias for [testAndFetchModels]: pings GET {baseUrl}/models with the
     * user's BYOK key to verify credentials and return available model IDs.
     * Never logs the API key.
     */
    suspend fun testConnection(
        rawBaseUrl: String,
        apiKey: String,
    ): ConnectionTestResult = testAndFetchModels(rawBaseUrl, apiKey)

    /**
     * Pings GET {baseUrl}/models with the user's BYOK key to verify credentials
     * and return available model IDs. Never logs the API key.
     */
    suspend fun testAndFetchModels(
        rawBaseUrl: String,
        apiKey: String,
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        val baseUrl = normalizeBaseUrl(rawBaseUrl)
        if (baseUrl.isBlank() || (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://"))) {
            return@withContext ConnectionTestResult.EndpointError(
                0,
                "Base URL must start with https:// or http://"
            )
        }
        val trimmedKey = apiKey.trim()
        val requestBuilder = Request.Builder()
            .url("$baseUrl/models")
            .get()
            .header("Accept", "application/json")
        if (trimmedKey.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $trimmedKey")
        }

        val startMs = System.currentTimeMillis()
        try {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val latency = System.currentTimeMillis() - startMs
                val bodyStr = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> {
                        val models = parseModelIds(bodyStr)
                        ConnectionTestResult.Success(models, latency)
                    }
                    response.code == 401 || response.code == 403 -> {
                        ConnectionTestResult.AuthError(
                            response.code,
                            "Authentication failed (HTTP ${response.code}). Check your API key."
                        )
                    }
                    else -> {
                        ConnectionTestResult.EndpointError(
                            response.code,
                            "Endpoint returned HTTP ${response.code}. Verify Base URL includes /v1 if required."
                        )
                    }
                }
            }
        } catch (e: IOException) {
            ConnectionTestResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun parseModelIds(body: String): List<String> {
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            // Supports OpenAI {"data":[{"id":...}]} and Ollama {"models":[{"name":...}]}
            val dataArr = root["data"]?.jsonArray
            if (dataArr != null) {
                dataArr.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                    .distinct()
                    .sorted()
            } else {
                root["models"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                    ?.distinct()
                    ?.sorted()
                    .orEmpty()
            }
        }.getOrDefault(emptyList())
    }
}
