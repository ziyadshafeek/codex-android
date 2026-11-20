package dev.codex.android.api

import dev.codex.android.core.CodexError
import dev.codex.android.core.withRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
        .followRedirects(false)
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
    fun normalizeBaseUrl(raw: String): String = endpointKey(raw)

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
        val baseUrl = normalizeBaseUrl(rawBaseUrl).toHttpUrlOrNull()
        if (baseUrl == null || baseUrl.username.isNotEmpty() || baseUrl.password.isNotEmpty() ||
            baseUrl.query != null || baseUrl.fragment != null
        ) {
            return@withContext ConnectionTestResult.EndpointError(0, "Enter a valid HTTP or HTTPS Base URL.")
        }
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty() || trimmedKey.any { it.code !in 33..126 }) {
            return@withContext ConnectionTestResult.AuthError(0, "Check your API key")
        }
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("models").build())
            .get()
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $trimmedKey")
            .build()
        try {
            withRetry(isRetryable = { it is CodexError.RateLimit }) {
                val startMs = System.currentTimeMillis()
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> ConnectionTestResult.Success(
                            parseModelIds(response.body?.string().orEmpty()), System.currentTimeMillis() - startMs,
                        )
                        response.code == 401 || response.code == 403 ->
                            ConnectionTestResult.AuthError(response.code, "Check your API key")
                        response.code == 429 -> throw CodexError.RateLimit(
                            retryAfterSeconds(response.header("Retry-After")),
                        )
                        else -> ConnectionTestResult.EndpointError(
                            response.code, "Could not list models (HTTP ${response.code}). Check the Base URL.",
                        )
                    }
                }
            }
        } catch (e: CodexError.RateLimit) {
            ConnectionTestResult.EndpointError(429, "The provider is still rate limiting requests. Try again later.")
        } catch (e: IOException) {
            ConnectionTestResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun retryAfterSeconds(header: String?): Long? {
        val value = header?.trim() ?: return null
        val seconds = value.toLongOrNull() ?: runCatching {
            val wait = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() -
                Instant.now().toEpochMilli()
            (wait.coerceAtLeast(0) + 999) / 1000
        }.getOrNull()
        return seconds?.coerceIn(0, Long.MAX_VALUE / 1000)
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
