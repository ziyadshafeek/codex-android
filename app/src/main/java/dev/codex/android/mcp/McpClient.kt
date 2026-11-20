package dev.codex.android.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
import java.util.concurrent.atomic.AtomicLong

data class McpToolDefinition(
    val serverId: String,
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/** One resource a server exposes via `resources/list`. */
data class McpResource(
    val serverId: String,
    val uri: String,
    val name: String,
    val mimeType: String?,
)

/**
 * One resource's contents from `resources/read`. MCP returns either a
 * `text` item or a `blob` item (base64); [blobBase64] non-null means the
 * content is binary and must never be dumped as text.
 */
data class McpResourceContent(
    val uri: String,
    val mimeType: String?,
    val text: String?,
    val blobBase64: String?,
) {
    val isBinary: Boolean get() = blobBase64 != null
}

class McpClient(
    private val endpointUrl: String,
    private val bearerToken: String? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val idCounter = AtomicLong(1)
    @Volatile
    private var sessionId: String? = null

    /**
     * Server-initiated notifications captured opportunistically from SSE
     * response bodies (see [McpEvent] KDoc for the honest limit: there is
     * no persistent stream, so a notification is only ever seen when it
     * rides along with a response to a request already in flight).
     * Bounded ring buffer — oldest entries are dropped past [MAX_RECORDED_EVENTS].
     */
    private val recordedEvents = ArrayDeque<McpEvent>()
    private val eventsLock = Any()

    /** Snapshot of the notifications recorded so far, oldest first. */
    fun recentNotifications(): List<McpEvent> = synchronized(eventsLock) {
        recordedEvents.toList()
    }

    /** Clears the recorded notifications (called after the hub drains them). */
    fun clearNotifications() {
        synchronized(eventsLock) { recordedEvents.clear() }
    }

    private fun recordNotification(serverId: String, rawJson: String) {
        val event = McpEvent.parseNotification(serverId, rawJson) ?: return
        synchronized(eventsLock) {
            recordedEvents += event
            while (recordedEvents.size > MAX_RECORDED_EVENTS) recordedEvents.removeFirst()
        }
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        val params = buildJsonObject {
            put("protocolVersion", "2025-03-26")
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject {
                put("name", "codex-android")
                put("version", "0.8.0")
            })
        }
        val resp = rpc("initialize", params)
        resp["error"] == null
    }

    suspend fun listTools(serverId: String): List<McpToolDefinition> = withContext(Dispatchers.IO) {
        if (sessionId == null) initialize()
        val resp = rpc("tools/list", buildJsonObject {}, serverId)
        val toolsArray = resp["result"]?.jsonObject?.get("tools")?.jsonArray ?: return@withContext emptyList()
        toolsArray.mapNotNull { elem ->
            val obj = elem.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val desc = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val schema = obj["inputSchema"]?.jsonObject ?: buildJsonObject { put("type", "object") }
            McpToolDefinition(serverId, name, desc, schema)
        }
    }

    suspend fun callTool(name: String, arguments: JsonObject): String = withContext(Dispatchers.IO) {
        if (sessionId == null) initialize()
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        val resp = rpc("tools/call", params)
        val err = resp["error"]?.jsonObject
        if (err != null) {
            return@withContext "MCP error: ${err["message"]?.jsonPrimitive?.contentOrNull ?: err}"
        }
        val contentArr = resp["result"]?.jsonObject?.get("content")?.jsonArray
        if (contentArr == null) return@withContext resp["result"]?.toString().orEmpty()
        contentArr.mapNotNull { item ->
            item.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        }.joinToString("\n")
    }

    /**
     * `resources/list` — same JSON-RPC framing as [listTools]. Returns an
     * empty list when the server exposes no resources (or no `resources`
     * key); a JSON-RPC error / HTTP failure throws, like the other calls.
     */
    suspend fun listResources(serverId: String): List<McpResource> = withContext(Dispatchers.IO) {
        if (sessionId == null) initialize()
        val resp = rpc("resources/list", buildJsonObject {}, serverId)
        val resourcesArray = resp["result"]?.jsonObject?.get("resources")?.jsonArray
            ?: return@withContext emptyList()
        resourcesArray.mapNotNull { elem ->
            val obj = elem.jsonObject
            val uri = obj["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            McpResource(
                serverId = serverId,
                uri = uri,
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: uri,
                mimeType = obj["mimeType"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    /**
     * `resources/read` for one [uri]. Returns the first content item; a
     * `blob` item marks the content binary ([McpResourceContent.isBinary]).
     * Throws [IOException] on transport failure or a JSON-RPC error, and
     * when the server returns no contents at all.
     */
    suspend fun readResource(serverId: String, uri: String): McpResourceContent =
        withContext(Dispatchers.IO) {
            if (sessionId == null) initialize()
            val params = buildJsonObject { put("uri", uri) }
            val resp = rpc("resources/read", params, serverId)
            val err = resp["error"]?.jsonObject
            if (err != null) {
                throw IOException("MCP error: ${err["message"]?.jsonPrimitive?.contentOrNull ?: err}")
            }
            val contents = resp["result"]?.jsonObject?.get("contents")?.jsonArray
            val first = contents?.firstOrNull()?.jsonObject
                ?: throw IOException("MCP resources/read returned no contents for $uri")
            McpResourceContent(
                uri = first["uri"]?.jsonPrimitive?.contentOrNull ?: uri,
                mimeType = first["mimeType"]?.jsonPrimitive?.contentOrNull,
                text = first["text"]?.jsonPrimitive?.contentOrNull,
                blobBase64 = first["blob"]?.jsonPrimitive?.contentOrNull,
            )
        }

    private fun rpc(method: String, params: JsonObject, serverId: String? = null): JsonObject {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", idCounter.getAndIncrement())
            put("method", method)
            put("params", params)
        }.toString()

        val reqBuilder = Request.Builder()
            .url(endpointUrl)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
        bearerToken?.takeIf { it.isNotBlank() }?.let {
            reqBuilder.header("Authorization", "Bearer $it")
        }
        sessionId?.let { reqBuilder.header("Mcp-Session-Id", it) }

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            response.header("Mcp-Session-Id")?.let { sessionId = it }
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("MCP HTTP ${response.code}: ${body.take(200)}")
            }
            val contentType = response.header("Content-Type").orEmpty()
            // Handle Streamable HTTP SSE envelope if server replies as text/event-stream.
            // A server may interleave notifications (JSON-RPC messages with a
            // "method" and no "id") among the response data lines: those are
            // recorded via [recordNotification] and never mistaken for the
            // response — the response is the last data line carrying an "id".
            val jsonPayload = if (contentType.contains("text/event-stream")) {
                val dataLines = body.lineSequence()
                    .filter { it.startsWith("data:") }
                    .map { it.removePrefix("data:").trim() }
                    .filter { it.startsWith("{") }
                    .toList()
                val label = serverId ?: endpointUrl
                var lastResponse: String? = null
                for (line in dataLines) {
                    val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    if (obj == null) continue
                    if (obj.containsKey("id")) {
                        lastResponse = line
                    } else if (obj.containsKey("method")) {
                        recordNotification(label, line)
                    }
                }
                lastResponse ?: throw IOException("Empty SSE MCP response")
            } else {
                body
            }
            return json.parseToJsonElement(jsonPayload).jsonObject
        }
    }

    companion object {
        /** Cap on opportunistically recorded server notifications per client. */
        const val MAX_RECORDED_EVENTS: Int = 100
    }
}
