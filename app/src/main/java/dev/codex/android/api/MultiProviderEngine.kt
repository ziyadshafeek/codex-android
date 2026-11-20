package dev.codex.android.api

import dev.codex.android.core.CodexError
import dev.codex.android.core.CodexJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One typed event from a streamed chat-completions call.
 *
 * Mirrors the OpenCode reference shape (Vercel AI SDK `streamText` fullStream
 * converged to a single typed event stream: text deltas + tool-call parts +
 * step/finish events — see `packages/opencode/src/session/llm.ts`), ported to
 * Kotlin Flow over an OpenAI-compatible SSE stream. OpenCode is MIT
 * licensed; this is a from-scratch Kotlin port of the *behaviour*, not a
 * copy of its TypeScript source.
 */
sealed class ChatStreamEvent {

    /** A new provider profile is being tried (first attempt, or after failover). */
    data class Started(val profileId: String, val profileName: String) : ChatStreamEvent()

    /** One incremental piece of assistant text from the SSE stream. */
    data class TextDelta(val text: String) : ChatStreamEvent()

    /** One fragment of a streamed tool call (arguments accumulate across chunks). */
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String?,
    ) : ChatStreamEvent()

    /** A tool call whose arguments are fully assembled (emitted at stream end). */
    data class ToolCallComplete(val toolCall: ToolCall) : ChatStreamEvent()

    /** This profile failed; the engine moves to the next profile, if any. */
    data class ProfileFailed(
        val profileId: String,
        val profileName: String,
        val error: CodexError,
    ) : ChatStreamEvent()

    /** The stream ended on this profile. [finishReason] is null on an empty stream. */
    data class Finished(
        val finishReason: String?,
        val profileId: String,
        val profileName: String,
    ) : ChatStreamEvent()
}

/** One failed profile attempt, carried by [StreamFailedException]. */
data class ProfileAttemptFailure(
    val profileId: String,
    val profileName: String,
    val error: CodexError,
)

/**
 * Thrown (terminating the [Flow]) when every profile in the failover chain
 * failed. [attempts] lists each profile tried, in order, with its error —
 * so the caller can report exactly what failed instead of a bare message.
 * Never contains key material: [CodexError] messages never include the key.
 */
class StreamFailedException(
    val attempts: List<ProfileAttemptFailure>,
) : Exception(
    "All ${attempts.size} provider profile(s) failed: " +
        attempts.joinToString("; ") { "${it.profileName}: ${it.error.message}" },
)

/**
 * Maps a [ReasoningEffort] to the `reasoning_effort` wire value, or null
 * when the field should be omitted. MEDIUM is the OpenAI default, so it is
 * omitted — this also keeps providers that reject an unknown
 * `reasoning_effort` field working on the default effort. LOW/HIGH are sent
 * verbatim, matching Codex CLI's `model_reasoning_effort` (`low`/`medium`/
 * `high`/`xhigh`) for the three levels this app exposes.
 */
internal fun reasoningEffortWireValue(effort: ReasoningEffort): String? = when (effort) {
    ReasoningEffort.LOW -> "low"
    ReasoningEffort.MEDIUM -> null
    ReasoningEffort.HIGH -> "high"
}

/**
 * Multi-profile BYOK engine (M1, Codex Mobile Studio v0.8.0).
 *
 * Owns the list of [ProviderProfile]s, the active profile, per-profile
 * connection tests / `/models` fetches, and TRUE SSE token streaming with
 * tool-call delta assembly and a retry/failover chain across profiles.
 *
 * Reuse (nothing here is reinvented):
 * - [ProviderService] for connection tests and `/models` (its
 *   `testAndFetchModels` / `normalizeBaseUrl`; its private `parseModelIds`
 *   is reached through that call's `ConnectionTestResult.Success`).
 * - [SseParser] for per-event SSE parsing; [OkHttpOpenAiClient]'s
 *   line-reading loop and HTTP-error mapping are mirrored, not duplicated
 *   from a shared helper (that client's mapper is private).
 * - OpenCode's multi-profile shape (MIT): named provider entries each with
 *   a base URL, a model list, and a selectable default model, referenced as
 *   `provider/model` — here a [ProviderProfile] is one such entry, and the
 *   active profile is the selected `provider/model`.
 * - Codex CLI's profile shape: separate named profiles (`~/.codex/<name>.config.toml`,
 *   `--profile <name>`), each with its own `model`, `model_provider` /
 *   `base_url`, and `model_reasoning_effort` — here one [ProviderProfile]
 *   per CLI profile, with the same three fields.
 *
 * Keys: this engine NEVER stores a key. The caller supplies [apiKeyProvider],
 * which returns the stored key for a profile (production wiring reads
 * `profile_key_<id>` from encrypted KeyStore; tests supply fake keys).
 * Keys are only ever placed in an `Authorization: Bearer` header, never
 * logged and never put in an exception message.
 *
 * Streaming consumption by the AgentLoop is a LATER integration step; this
 * class is the engine API only. Profile persistence (SharedPreferences /
 * database) is also later — profiles live in memory here, seeded via
 * [initialProfiles].
 */
class MultiProviderEngine(
    private val providerService: ProviderService = ProviderService(),
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val apiKeyProvider: (ProviderProfile) -> String? = { null },
    initialProfiles: List<ProviderProfile> = emptyList(),
    initialActiveProfileId: String? = null,
) {
    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val lock = Any()

    /** Insertion-ordered profiles, keyed by id. Guarded by [lock]. */
    private val profilesById = LinkedHashMap<String, ProviderProfile>()

    /** Id of the active profile, or null when no profiles exist. Guarded by [lock]. */
    private var activeProfileId: String? = null

    init {
        for (profile in initialProfiles) {
            val clean = validated(profile)
            profilesById[clean.id] = clean
        }
        activeProfileId = when {
            initialActiveProfileId != null && profilesById.containsKey(initialActiveProfileId) ->
                initialActiveProfileId
            profilesById.isNotEmpty() -> profilesById.keys.first()
            else -> null
        }
    }

    // --- Profile management ---

    /** All profiles, in the order they were added. */
    fun profiles(): List<ProviderProfile> = synchronized(lock) { profilesById.values.toList() }

    /** The active profile, or null when none is configured. */
    fun activeProfile(): ProviderProfile? = synchronized(lock) {
        activeProfileId?.let { profilesById[it] }
    }

    /**
     * Adds a profile, or replaces the existing profile with the same id
     * (upsert — editing a profile in Settings re-saves it under its id).
     * The base URL is normalised via [ProviderService.normalizeBaseUrl].
     * The first profile added becomes active automatically.
     *
     * @throws IllegalArgumentException when id, base URL, or model is blank.
     */
    fun addProfile(profile: ProviderProfile): ProviderProfile {
        val clean = validated(profile)
        synchronized(lock) {
            profilesById[clean.id] = clean
            // Active is only null when no profiles exist, so a null here
            // means this is the first profile — it becomes active.
            if (activeProfileId == null) {
                activeProfileId = clean.id
            }
        }
        return clean
    }

    /**
     * Removes a profile. Returns false when no profile with [id] exists.
     * Removing the active profile activates the first remaining profile
     * (or none, when the list becomes empty).
     */
    fun removeProfile(id: String): Boolean = synchronized(lock) {
        if (profilesById.remove(id) == null) return false
        if (activeProfileId == id) {
            activeProfileId = profilesById.keys.firstOrNull()
        }
        true
    }

    /** Makes [id] the active profile. Returns false when unknown. */
    fun setActiveProfile(id: String): Boolean = synchronized(lock) {
        if (!profilesById.containsKey(id)) return false
        activeProfileId = id
        true
    }

    /** Changes a profile's reasoning effort. Returns false when unknown. */
    fun updateReasoningEffort(id: String, effort: ReasoningEffort): Boolean = synchronized(lock) {
        val current = profilesById[id] ?: return false
        profilesById[id] = current.copy(reasoningEffort = effort)
        true
    }

    /** Changes a profile's model (e.g. after picking from `/models`). Returns false when unknown. */
    fun updateModel(id: String, model: String): Boolean = synchronized(lock) {
        if (model.isBlank()) return false
        val current = profilesById[id] ?: return false
        profilesById[id] = current.copy(model = model.trim())
        true
    }

    // --- Connection test / /models (delegates to ProviderService) ---

    /**
     * Tests [profile]'s endpoint with [apiKey] and returns the available
     * model ids (OpenAI `{"data":[{"id":...}]}` and Ollama
     * `{"models":[{"name":...}]}` shapes, via [ProviderService]).
     */
    suspend fun testConnection(
        profile: ProviderProfile,
        apiKey: String,
    ): ConnectionTestResult = providerService.testAndFetchModels(profile.baseUrl, apiKey)

    /** Alias for [testConnection]: fetches the `/models` list for [profile]. */
    suspend fun fetchModels(
        profile: ProviderProfile,
        apiKey: String,
    ): ConnectionTestResult = testConnection(profile, apiKey)

    /**
     * Tests the active profile using its stored key.
     * Returns null when no profile is active; returns an [ConnectionTestResult.AuthError]
     * when the active profile has no stored key.
     */
    suspend fun testActiveConnection(): ConnectionTestResult? {
        val profile = activeProfile() ?: return null
        val apiKey = apiKeyProvider(profile)?.trim().orEmpty()
        if (apiKey.isEmpty()) {
            return ConnectionTestResult.AuthError(
                0,
                "No API key stored for profile \"${profile.name}\".",
            )
        }
        return testConnection(profile, apiKey)
    }

    // --- Streaming ---

    /**
     * Streams one chat turn as a [Flow] of [ChatStreamEvent]s.
     *
     * The chain is the requested profile first (or the active profile when
     * [profileId] is null), then every other configured profile in add
     * order. On an auth failure (401/403), a network/timeout failure, a
     * rate limit (429), or a 5xx, the engine emits
     * [ChatStreamEvent.ProfileFailed] and tries the next profile; the flow
     * terminates successfully as soon as one profile streams. When every
     * profile fails, the flow throws [StreamFailedException] carrying each
     * attempt. A missing key skips that profile (recorded as an auth
     * failure) without any HTTP call. Non-retryable failures (e.g. HTTP
     * 400 — a malformed request that would fail on every profile) abort
     * the chain immediately.
     *
     * Cancellation propagates as [kotlinx.coroutines.CancellationException]
     * and is never treated as a failover trigger.
     *
     * @throws IllegalStateException when no profiles are configured.
     * @throws StreamFailedException when every profile in the chain failed.
     */
    fun streamChat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        profileId: String? = null,
    ): Flow<ChatStreamEvent> {
        return flow {
            val chain = resolveChain(profileId)
            if (chain.isEmpty()) {
                throw IllegalStateException("No provider profiles configured — add a BYOK profile first")
            }
            val failures = mutableListOf<ProfileAttemptFailure>()
            for (profile in chain) {
                val apiKey = apiKeyProvider(profile)?.trim().orEmpty()
                if (apiKey.isEmpty()) {
                    val error = CodexError.Auth("No API key stored for profile \"${profile.name}\"")
                    failures += ProfileAttemptFailure(profile.id, profile.name, error)
                    emit(ChatStreamEvent.ProfileFailed(profile.id, profile.name, error))
                    continue
                }
                emit(ChatStreamEvent.Started(profile.id, profile.name))
                try {
                    streamWithProfile(profile, apiKey, messages, tools)
                    return@flow
                } catch (e: CodexError) {
                    if (!isFailoverEligible(e)) throw e
                    failures += ProfileAttemptFailure(profile.id, profile.name, e)
                    emit(ChatStreamEvent.ProfileFailed(profile.id, profile.name, e))
                }
            }
            throw StreamFailedException(failures)
        }.flowOn(Dispatchers.IO)
    }

    /** Failover order: requested/active profile first, then the rest in add order. */
    private fun resolveChain(profileId: String?): List<ProviderProfile> = synchronized(lock) {
        if (profilesById.isEmpty()) return emptyList()
        val firstId = profileId ?: activeProfileId
        val first = firstId?.let { profilesById[it] }
        val rest = profilesById.values.filter { it.id != first?.id }
        if (first == null) rest else listOf(first) + rest
    }

    private fun isFailoverEligible(e: CodexError): Boolean = when (e) {
        is CodexError.Auth,
        is CodexError.Network,
        is CodexError.Timeout,
        is CodexError.RateLimit,
        -> true
        else -> false
    }

    /**
     * One streaming attempt against a single profile. Emits text deltas and
     * tool-call fragments as they arrive, then the fully assembled tool
     * calls and [ChatStreamEvent.Finished]. Throws [CodexError] on failure.
     */
    private suspend fun FlowCollector<ChatStreamEvent>.streamWithProfile(
        profile: ProviderProfile,
        apiKey: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
    ) {
        val baseUrl = providerService.normalizeBaseUrl(profile.baseUrl)
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(buildRequestJson(messages, tools, profile).toRequestBody(JSON_MEDIA_TYPE))
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
            if (contentType.contains("text/event-stream")) {
                readSseStream(body, profile)
            } else {
                // Server answered 200 without streaming: degrade gracefully to
                // a single JSON chat-completions body (same shape OpenAiClient
                // accepts as its non-streaming fallback).
                readJsonBody(body.string(), profile)
            }
        }
    }

    /** Reads an SSE body, emitting deltas and assembling tool calls. */
    private suspend fun FlowCollector<ChatStreamEvent>.readSseStream(
        body: okhttp3.ResponseBody,
        profile: ProviderProfile,
    ) {
        val toolCallIds = mutableMapOf<Int, String>()
        val toolCallNames = mutableMapOf<Int, String>()
        val toolCallArgs = mutableMapOf<Int, StringBuilder>()
        var finishReason: String? = null
        body.source().use { source ->
            while (!source.exhausted()) {
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
                delta.textDelta?.let { emit(ChatStreamEvent.TextDelta(it)) }
                for ((index, frag) in delta.toolCallDeltas) {
                    frag.id?.let { toolCallIds[index] = it }
                    frag.name?.let { toolCallNames[index] = it }
                    frag.argumentsDelta?.let { piece ->
                        toolCallArgs.getOrPut(index) { StringBuilder() }.append(piece)
                    }
                    emit(
                        ChatStreamEvent.ToolCallDelta(
                            index = index,
                            id = frag.id,
                            name = frag.name,
                            argumentsDelta = frag.argumentsDelta,
                        ),
                    )
                }
                if (delta.finishReason != null) finishReason = delta.finishReason
            }
        }
        emitAssembledToolCalls(toolCallIds, toolCallNames, toolCallArgs)
        emit(ChatStreamEvent.Finished(finishReason, profile.id, profile.name))
    }

    /** Parses a non-streamed JSON chat-completions body into the same events. */
    private suspend fun FlowCollector<ChatStreamEvent>.readJsonBody(
        raw: String,
        profile: ProviderProfile,
    ) {
        val (text, calls, finishReason) = try {
            parseNonStreamingJson(raw)
        } catch (e: Exception) {
            throw CodexError.Unknown("Invalid JSON response: ${e.message}")
        }
        text?.let { emit(ChatStreamEvent.TextDelta(it)) }
        for (call in calls) {
            emit(ChatStreamEvent.ToolCallComplete(call))
        }
        emit(ChatStreamEvent.Finished(finishReason, profile.id, profile.name))
    }

    private suspend fun FlowCollector<ChatStreamEvent>.emitAssembledToolCalls(
        ids: Map<Int, String>,
        names: Map<Int, String>,
        args: Map<Int, StringBuilder>,
    ) {
        for (index in names.keys.sorted()) {
            emit(
                ChatStreamEvent.ToolCallComplete(
                    ToolCall(
                        id = ids[index] ?: "",
                        type = "function",
                        function = FunctionCall(
                            name = names[index] ?: "",
                            arguments = args[index]?.toString() ?: "{}",
                        ),
                    ),
                ),
            )
        }
    }

    /** First choice of a non-streamed chat-completions body: (text, tool calls, finish reason). */
    private fun parseNonStreamingJson(raw: String): Triple<String?, List<ToolCall>, String?> {
        val root = CodexJson.parseToJsonElement(raw).jsonObject
        val choices = root["choices"]?.jsonArray
            ?: throw IllegalArgumentException("Response has no choices")
        val choice = choices.firstOrNull()?.jsonObject
            ?: throw IllegalArgumentException("Response has no choices")
        val message = choice["message"]?.jsonObject
        val text = (message?.get("content") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        val rawCalls = message?.get("tool_calls")?.jsonArray
        val calls = rawCalls?.mapNotNull { rawCall ->
            val callObj = rawCall as? JsonObject ?: return@mapNotNull null
            val fn = callObj["function"]?.jsonObject ?: return@mapNotNull null
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
        return Triple(text, calls, finishReason)
    }

    private fun buildRequestJson(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        profile: ProviderProfile,
    ): String {
        val root = buildJsonObject {
            put("model", profile.model)
            put(
                "messages",
                chatMessagesWire(messages),
            )
            if (tools.isNotEmpty()) {
                put(
                    "tools",
                    CodexJson.encodeToJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(ToolDefinition.serializer()),
                        tools,
                    ),
                )
                put("tool_choice", "auto")
            }
            put("stream", true)
            put("temperature", 0.2)
            reasoningEffortWireValue(profile.reasoningEffort)?.let { put("reasoning_effort", it) }
        }
        return CodexJson.encodeToString(JsonObject.serializer(), root)
    }

    /** Mirrors [OkHttpOpenAiClient]'s HTTP-error mapping (that mapper is private). */
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

    private fun validated(profile: ProviderProfile): ProviderProfile {
        require(profile.id.isNotBlank()) { "Profile id must not be blank" }
        val baseUrl = providerService.normalizeBaseUrl(profile.baseUrl)
        require(baseUrl.isNotBlank()) { "Profile baseUrl must not be blank" }
        require(profile.model.isNotBlank()) { "Profile model must not be blank" }
        return profile.copy(id = profile.id.trim(), baseUrl = baseUrl, model = profile.model.trim())
    }
}
