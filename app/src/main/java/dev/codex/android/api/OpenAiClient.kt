package dev.codex.android.api

import dev.codex.android.core.CodexError
import dev.codex.android.core.CodexJson
import dev.codex.android.core.RetryPolicy
import dev.codex.android.core.withRetry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The model's chat interface. Implementations speak an OpenAI-compatible
 * `/chat/completions` endpoint. No implementation ever holds a default key;
 * the key is supplied by the caller (BYOK) at construction.
 */
interface OpenAiClient {
    /**
     * One chat turn. Returns the assistant's reply (text and/or tool calls).
     * When [onPartialText] is given, streamed text deltas are delivered to it
     * as they arrive; the returned [ChatResult] still carries the full text.
     *
     * @throws CodexError.Network, CodexError.Auth, CodexError.RateLimit,
     *   CodexError.Timeout, CodexError.Unknown on failure.
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        model: String,
        onPartialText: ((String) -> Unit)? = null,
    ): ChatResult
}

/**
 * OkHttp implementation of [OpenAiClient] against `{baseUrl}/chat/completions`.
 *
 * Streams by default (SSE); falls back cleanly if the server does not stream.
 * Retries network, rate-limit, and timeout failures with [RetryPolicy].
 * Auth failures (401/403) are never retried.
 */
class OkHttpOpenAiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) : OpenAiClient {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * Strips a pasted `/chat/completions` suffix: providers show the
         * full endpoint URL, and appending it again would request
         * `.../chat/completions/chat/completions` (404). Internal for
         * JVM tests: see OpenAiClientTest.
         */
        internal fun normaliseBaseUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            return trimmed.removeSuffix("/chat/completions").trimEnd('/')
        }
    }

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        model: String,
        onPartialText: ((String) -> Unit)?,
    ): ChatResult {
        val effectiveModel = model.ifBlank { this.model }
        // Blocking OkHttp execute() + stream reads: confine to the IO
        // dispatcher — the agent runtime drives chat() from Default.
        return withContext(Dispatchers.IO) {
            withRetry(policy = retryPolicy) {
                chatOnce(messages, tools, effectiveModel, onPartialText)
            }
        }
    }

    private suspend fun chatOnce(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        model: String,
        onPartialText: ((String) -> Unit)?,
    ): ChatResult {
        val url = "${normaliseBaseUrl(baseUrl)}/chat/completions"
        val bodyJson = buildRequestJson(messages, tools, model)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw CodexError.Network(e)
        } catch (e: Exception) {
            throw CodexError.Unknown("HTTP call failed: ${e.message}", e)
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw mapHttpError(resp.code, resp.header("Retry-After"), resp.body?.string())
            }
            val contentType = resp.header("Content-Type") ?: ""
            val body = resp.body ?: throw CodexError.Unknown("Empty response body")
            return if (contentType.contains("text/event-stream")) {
                readStream(body, onPartialText)
            } else {
                readJsonBody(body.string())
            }
        }
    }

    private fun buildRequestJson(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        model: String,
    ): String {
        val root = buildJsonObject {
            put("model", model)
            put("messages", CodexJson.encodeToJsonElement(
                kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()),
                messages,
            ))
            if (tools.isNotEmpty()) {
                put("tools", CodexJson.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(ToolDefinition.serializer()),
                    tools,
                ))
                put("tool_choice", "auto")
            }
            put("stream", true)
            put("temperature", 0.2)
        }
        return CodexJson.encodeToString(JsonObject.serializer(), root)
    }

    /** Reads a streamed SSE body, accumulating text and tool calls. */
    private fun readStream(
        body: okhttp3.ResponseBody,
        onPartialText: ((String) -> Unit)?,
    ): ChatResult {
        val text = StringBuilder()
        val toolCallIds = mutableMapOf<Int, String>()
        val toolCallNames = mutableMapOf<Int, String>()
        val toolCallArgs = mutableMapOf<Int, StringBuilder>()
        var finishReason: String? = null
        body.source().use { source ->
            while (true) {
                // exhausted() itself throws on a broken stream: guard it
                // (a mid-stream drop must be a retryable Network error,
                // not an Unknown one).
                val exhausted = try {
                    source.exhausted()
                } catch (e: IOException) {
                    throw CodexError.Network(e)
                }
                if (exhausted) break
                val line = try {
                    source.readUtf8Line() ?: break
                } catch (e: IOException) {
                    throw CodexError.Network(e)
                }
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                val delta = SseParser.parseDataLine(payload)
                if (delta.done) break
                delta.textDelta?.let {
                    text.append(it)
                    onPartialText?.invoke(it)
                }
                for ((index, frag) in delta.toolCallDeltas) {
                    frag.id?.let { toolCallIds[index] = it }
                    frag.name?.let { toolCallNames[index] = it }
                    frag.argumentsDelta?.let { piece ->
                        toolCallArgs.getOrPut(index) { StringBuilder() }.append(piece)
                    }
                }
                if (delta.finishReason != null) finishReason = delta.finishReason
            }
        }
        val calls = toolCallNames.keys.sorted().map { index ->
            ToolCall(
                id = toolCallIds[index] ?: "",
                type = "function",
                function = FunctionCall(
                    name = toolCallNames[index] ?: "",
                    arguments = toolCallArgs[index]?.toString() ?: "{}",
                ),
            )
        }
        val textOut = text.toString().ifEmpty { null }
        return ChatResult(
            choices = listOf(
                ChatChoice(text = textOut, toolCalls = calls, finishReason = finishReason),
            ),
        )
    }

    /** Reads a non-streamed JSON body into a [ChatResult]. */
    private fun readJsonBody(raw: String): ChatResult {
        val element = try {
            CodexJson.parseToJsonElement(raw)
        } catch (e: Exception) {
            throw CodexError.Unknown("Invalid JSON response: ${e.message}")
        }
        val root = element as? JsonObject ?: throw CodexError.Unknown("Invalid JSON response")
        val choicesJson = root["choices"]?.let {
            try {
                it as? kotlinx.serialization.json.JsonArray
            } catch (e: Exception) {
                null
            }
        } ?: throw CodexError.Unknown("Response has no choices")
        val choices = choicesJson.map { choiceEl ->
            val choice = choiceEl as? JsonObject ?: return@map null
            val message = choice["message"]?.let { it as? JsonObject }
            val text = (message?.get("content") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            val rawCalls = message?.get("tool_calls") as? kotlinx.serialization.json.JsonArray
            val calls = rawCalls?.mapNotNull { rawCall ->
                val callObj = rawCall as? JsonObject ?: return@mapNotNull null
                val fn = callObj["function"] as? JsonObject ?: return@mapNotNull null
                ToolCall(
                    id = (callObj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "",
                    type = "function",
                    function = FunctionCall(
                        name = (fn["name"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "",
                        arguments = (fn["arguments"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "{}",
                    ),
                )
            } ?: emptyList()
            val finishReason = (choice["finish_reason"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            ChatChoice(text = text, toolCalls = calls, finishReason = finishReason)
        }.filterNotNull()
        return ChatResult(choices = choices)
    }

    private fun mapHttpError(code: Int, retryAfter: String?, body: String?): CodexError {
        return when (code) {
            401, 403 -> CodexError.Auth("HTTP $code — check your API key")
            429 -> {
                val seconds = retryAfter?.toLongOrNull()
                CodexError.RateLimit(seconds)
            }
            408, 504 -> CodexError.Timeout()
            in 500..599 -> CodexError.Network(
                IOException("Server error HTTP $code${body?.take(200)?.let { ": $it" } ?: ""}"),
            )
            else -> CodexError.Unknown("HTTP $code${body?.take(200)?.let { ": $it" } ?: ""}")
        }
    }
}
