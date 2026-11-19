package dev.codex.android.tools

import dev.codex.android.core.CodexJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * BYOK web search tool (`name = "websearch"`).
 *
 * Strictly BYOK: [endpoint] and [apiKey] are supplied by the user's own
 * settings; no provider, endpoint, or key is ever hardcoded. When either
 * [endpoint] or [apiKey] is blank, [execute] immediately returns the error
 * `"no search endpoint configured"`.
 */
class WebSearchTool(
    private val endpoint: String,
    private val apiKey: String,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) : Tool {

    override val name: String = "websearch"
    override val description: String =
        "Searches the web using the user's configured BYOK search endpoint and returns " +
            "matching results (title, URL, and snippet)."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "Search query string")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Maximum number of results to return (default 5, max 20)")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("query"))
        }
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val cleanEndpoint = endpoint.trim()
        val cleanKey = apiKey.trim()
        if (cleanEndpoint.isEmpty() || cleanKey.isEmpty()) {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = NO_ENDPOINT_ERROR,
            )
        }

        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return@withContext ToolResult(
                ok = false,
                output = "",
                error = "Missing required argument: query",
            )
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        if (!cleanEndpoint.startsWith("https://") && !cleanEndpoint.startsWith("http://")) {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = "Search endpoint must start with https:// or http://",
            )
        }
        val baseHttpUrl = cleanEndpoint.toHttpUrlOrNull()
            ?: return@withContext ToolResult(
                ok = false,
                output = "",
                error = "Invalid search endpoint URL",
            )

        val requestUrl = baseHttpUrl.newBuilder()
            .addQueryParameter("q", query)
            .build()

        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $cleanKey")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ToolResult(
                        ok = false,
                        output = "",
                        error = "Search endpoint returned HTTP ${response.code}",
                    )
                }
                val body = response.body?.string().orEmpty()
                val results = parseSearchResults(body, limit)
                    ?: return@withContext ToolResult(
                        ok = false,
                        output = "",
                        error = "Search endpoint returned invalid JSON",
                    )
                if (results.isEmpty()) {
                    return@withContext ToolResult(ok = true, output = "No results found.")
                }
                val formatted = results.mapIndexed { index, item ->
                    buildString {
                        append(index + 1).append(". ").append(item.title)
                        if (item.url.isNotEmpty()) append("\n   ").append(item.url)
                        if (item.snippet.isNotEmpty()) append("\n   ").append(item.snippet)
                    }
                }.joinToString("\n\n")
                ToolResult(ok = true, output = formatted)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ToolResult(
                ok = false,
                output = "",
                error = "Search request failed: ${e.message ?: e.javaClass.simpleName}",
            )
        } catch (e: Exception) {
            ToolResult(
                ok = false,
                output = "",
                error = "Search failed: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    internal data class SearchHit(
        val title: String,
        val url: String,
        val snippet: String,
    )

    companion object {
        const val NO_ENDPOINT_ERROR: String = "no search endpoint configured"
        private const val DEFAULT_LIMIT = 5
        private const val MAX_LIMIT = 20

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        /**
         * Generic JSON search response parser supporting top-level arrays as
         * well as `results`, `items`, `organic`, `data`, `value`, and
         * `web.results` envelopes.
         */
        internal fun parseSearchResults(rawJson: String, limit: Int): List<SearchHit>? {
            if (rawJson.isBlank()) return null
            val root: JsonElement = runCatching {
                CodexJson.parseToJsonElement(rawJson)
            }.getOrNull() ?: return null

            val array: JsonArray = when (root) {
                is JsonArray -> root
                is JsonObject -> {
                    (root["results"] as? JsonArray)
                        ?: (root["items"] as? JsonArray)
                        ?: (root["organic"] as? JsonArray)
                        ?: (root["data"] as? JsonArray)
                        ?: (root["value"] as? JsonArray)
                        ?: (root["web"]?.let { it as? JsonObject }?.get("results") as? JsonArray)
                        ?: JsonArray(emptyList())
                }
                else -> return null
            }

            val hits = mutableListOf<SearchHit>()
            for (element in array) {
                if (hits.size >= limit) break
                val obj = element as? JsonObject ?: continue
                val title = firstString(obj, "title", "name")
                val url = firstString(obj, "url", "link", "href")
                val snippet = firstString(obj, "snippet", "description", "content", "text", "abstract")
                if (title.isEmpty() && url.isEmpty() && snippet.isEmpty()) continue
                hits += SearchHit(
                    title = title.ifEmpty { url.ifEmpty { "(untitled)" } },
                    url = url,
                    snippet = snippet,
                )
            }
            return hits
        }

        private fun firstString(obj: JsonObject, vararg keys: String): String {
            for (key in keys) {
                val value = (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()
                if (!value.isNullOrEmpty()) return value
            }
            return ""
        }
    }
}
