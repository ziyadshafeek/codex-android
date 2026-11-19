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
        val resp = rpc("tools/list", buildJsonObject {})
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

    private fun rpc(method: String, params: JsonObject): JsonObject {
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
            val jsonPayload = if (contentType.contains("text/event-stream")) {
                body.lineSequence()
                    .filter { it.startsWith("data:") }
                    .map { it.removePrefix("data:").trim() }
                    .lastOrNull { it.startsWith("{") }
                    ?: throw IOException("Empty SSE MCP response")
            } else {
                body
            }
            return json.parseToJsonElement(jsonPayload).jsonObject
        }
    }
}
