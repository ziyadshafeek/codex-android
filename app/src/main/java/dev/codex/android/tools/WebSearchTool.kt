package dev.codex.android.tools

import dev.codex.android.core.CodexError
import dev.codex.android.core.CodexJson
import dev.codex.android.core.RetryPolicy
import dev.codex.android.core.withRetry
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
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Web search tool (`name = "websearch"`).
 *
 * Works with no configuration: when no BYOK search endpoint + key are
 * supplied, it searches the keyless DuckDuckGo HTML endpoint
 * (`https://html.duckduckgo.com/html/?q=...`, no API key) and parses
 * the result HTML for title / URL / snippet.
 *
 * BYOK override: when [endpoint] and [apiKey] are both non-blank, the
 * tool instead calls that endpoint (GET `?q=<query>`, Bearer auth)
 * and parses the generic JSON response, exactly as before. No search
 * endpoint/key setting exists in KeyStore or Settings yet, so in
 * practice the tool runs on DuckDuckGo until one is wired in.
 *
 * DuckDuckGo failures are reported plainly, never faked: an
 * unreachable / rate-limited endpoint is retried once (via the
 * shared [withRetry] policy, which honours the server's Retry-After)
 * and then returns a clear error naming DuckDuckGo and the cause.
 */
class WebSearchTool(
    private val endpoint: String,
    private val apiKey: String,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val duckDuckGoBaseUrl: String = DUCKDUCKGO_HTML_URL,
) : Tool {

    override val name: String = "websearch"
    override val description: String =
        "Searches the web and returns matching results (title, URL, and snippet). " +
            "Works with no setup using DuckDuckGo; an optional BYOK search endpoint " +
            "and key in Settings switches to that provider instead."
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
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return@withContext ToolResult(
                ok = false,
                output = "",
                error = "Missing required argument: query",
            )
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val cleanEndpoint = endpoint.trim()
        val cleanKey = apiKey.trim()
        return@withContext if (cleanEndpoint.isNotEmpty() && cleanKey.isNotEmpty()) {
            executeByok(cleanEndpoint, cleanKey, query, limit)
        } else {
            executeDuckDuckGo(query, limit)
        }
    }

    /**
     * BYOK path: the user's own search endpoint, GET `?q=<query>` with
     * Bearer auth, generic JSON response. Unchanged from before the
     * DuckDuckGo fallback existed.
     */
    private fun executeByok(endpoint: String, key: String, query: String, limit: Int): ToolResult {
        if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) {
            return ToolResult(
                ok = false,
                output = "",
                error = "Search endpoint must start with https:// or http://",
            )
        }
        val baseHttpUrl = endpoint.toHttpUrlOrNull()
            ?: return ToolResult(
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
            .header("Authorization", "Bearer $key")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return ToolResult(
                        ok = false,
                        output = "",
                        error = "Search endpoint returned HTTP ${response.code}",
                    )
                }
                val body = response.body?.string().orEmpty()
                val results = parseSearchResults(body, limit)
                    ?: return ToolResult(
                        ok = false,
                        output = "",
                        error = "Search endpoint returned invalid JSON",
                    )
                formatResults(results)
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

    /**
     * Keyless default: DuckDuckGo HTML. One retry via the shared retry
     * policy ([RetryPolicy] maxAttempts = 2), which honours the
     * server's Retry-After on a 429. After the retry, a clear error
     * naming DuckDuckGo and the cause — never a fake result.
     */
    private suspend fun executeDuckDuckGo(query: String, limit: Int): ToolResult {
        val baseHttpUrl = duckDuckGoBaseUrl.toHttpUrlOrNull()
            ?: return ToolResult(
                ok = false,
                output = "",
                error = "DuckDuckGo search failed: invalid search URL",
            )
        val requestUrl = baseHttpUrl.newBuilder()
            .addQueryParameter("q", query)
            .build()
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("Accept", "text/html,application/xhtml+xml")
            .header("User-Agent", DUCKDUCKGO_USER_AGENT)
            .build()

        val html: String = try {
            withRetry(RetryPolicy(maxAttempts = 2)) {
                fetchDuckDuckGoHtml(request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CodexError.RateLimit) {
            return ToolResult(
                ok = false,
                output = "",
                error = "DuckDuckGo rate-limited the request — try again shortly",
            )
        } catch (e: CodexError.Network) {
            return ToolResult(
                ok = false,
                output = "",
                error = "DuckDuckGo search failed: ${e.cause?.message ?: "network error"}",
            )
        } catch (e: CodexError.Unknown) {
            return ToolResult(
                ok = false,
                output = "",
                error = "DuckDuckGo search failed: ${e.message}",
            )
        } catch (e: Exception) {
            return ToolResult(
                ok = false,
                output = "",
                error = "DuckDuckGo search failed: ${e.message ?: e.javaClass.simpleName}",
            )
        }

        val hits = parseDuckDuckGoHtml(html, limit)
            ?: return ToolResult(
                ok = false,
                output = "",
                error = "DuckDuckGo blocked the request (bot check) — try again later",
            )
        return formatResults(hits)
    }

    /**
     * One DuckDuckGo HTML fetch. Retryable failures throw [CodexError]
     * so [withRetry] retries them once: 429 → RateLimit (honours
     * Retry-After), 5xx / transport failure → Network. Any other HTTP
     * status is not transient, so it throws Unknown and is not retried.
     */
    private fun fetchDuckDuckGoHtml(request: Request): String {
        try {
            httpClient.newCall(request).execute().use { response ->
                return when {
                    response.isSuccessful -> response.body?.string().orEmpty()
                    response.code == 429 -> throw CodexError.RateLimit(
                        response.header("Retry-After")?.toLongOrNull(),
                    )
                    response.code in 500..599 -> throw CodexError.Network(
                        IOException("DuckDuckGo returned HTTP ${response.code}"),
                    )
                    else -> throw CodexError.Unknown("DuckDuckGo returned HTTP ${response.code}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CodexError) {
            throw e
        } catch (e: IOException) {
            throw CodexError.Network(e)
        }
    }

    private fun formatResults(results: List<SearchHit>): ToolResult {
        if (results.isEmpty()) {
            return ToolResult(ok = true, output = "No results found.")
        }
        val formatted = results.mapIndexed { index, item ->
            buildString {
                append(index + 1).append(". ").append(item.title)
                if (item.url.isNotEmpty()) append("\n   ").append(item.url)
                if (item.snippet.isNotEmpty()) append("\n   ").append(item.snippet)
            }
        }.joinToString("\n\n")
        return ToolResult(ok = true, output = formatted)
    }

    internal data class SearchHit(
        val title: String,
        val url: String,
        val snippet: String,
    )

    companion object {
        const val DUCKDUCKGO_HTML_URL: String = "https://html.duckduckgo.com/html/"
        private const val DUCKDUCKGO_USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
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

        /**
         * DuckDuckGo HTML result parser. Each result is a `result__a`
         * link (title + href, usually a `duckduckgo.com/l/?uddg=`
         * redirect wrapping the real URL) paired with a `result__snippet`
         * link, in document order.
         *
         * Returns null when the page is a bot-check / captcha page
         * instead of results — the caller reports that plainly rather
         * than inventing results from it. Returns an empty list when
         * the page parsed fine but held no results.
         */
        internal fun parseDuckDuckGoHtml(html: String, limit: Int): List<SearchHit>? {
            if (html.isBlank()) return emptyList()
            val lower = html.lowercase()
            if ("captcha" in lower || "anomaly" in lower) return null
            val links = findResultAnchors(html, "result__a")
            val snippets = findResultAnchors(html, "result__snippet")
            val hits = mutableListOf<SearchHit>()
            for ((index, link) in links.withIndex()) {
                if (hits.size >= limit) break
                val (href, titleHtml) = link
                val title = stripHtmlTags(unescapeHtmlEntities(titleHtml)).trim()
                val url = extractDuckDuckGoUrl(unescapeHtmlEntities(href))
                val snippet = snippets.getOrNull(index)?.second
                    ?.let { stripHtmlTags(unescapeHtmlEntities(it)).trim() }
                    .orEmpty()
                if (title.isEmpty() && url.isEmpty() && snippet.isEmpty()) continue
                hits += SearchHit(
                    title = title.ifEmpty { url.ifEmpty { "(untitled)" } },
                    url = url,
                    snippet = snippet,
                )
            }
            return hits
        }

        /** Anchors whose class list contains [className], as (href, inner HTML). */
        private fun findResultAnchors(html: String, className: String): List<Pair<String, String>> {
            val anchor = Regex(
                """<a\b([^>]*)>(.*?)</a>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
            )
            val classAttr = Regex("""class\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            val hrefAttr = Regex("""href\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            val out = mutableListOf<Pair<String, String>>()
            for (match in anchor.findAll(html)) {
                val attrs = match.groupValues[1]
                val classes = classAttr.find(attrs)?.groupValues?.get(1)
                    .orEmpty().split("\\s+".toRegex())
                if (className !in classes) continue
                val href = hrefAttr.find(attrs)?.groupValues?.get(1).orEmpty()
                out += href to match.groupValues[2]
            }
            return out
        }

        /**
         * The real URL behind a DuckDuckGo result link. DDG wraps it in
         * its redirect (`//duckduckgo.com/l/?uddg=<url-encoded>&rut=...`);
         * anything else is used as-is (protocol-relative and relative
         * links resolved against duckduckgo.com).
         */
        internal fun extractDuckDuckGoUrl(href: String): String {
            val clean = href.trim()
            if (clean.isEmpty()) return ""
            val uddgIndex = clean.indexOf("uddg=")
            if (uddgIndex >= 0) {
                val encoded = clean.substring(uddgIndex + 5).substringBefore("&")
                if (encoded.isEmpty()) return ""
                // '+' is a literal plus inside a percent-encoded URL, not a
                // space — protect it before URLDecoder runs.
                return runCatching {
                    URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: encoded
            }
            return when {
                clean.startsWith("//") -> "https:$clean"
                clean.startsWith("http://") || clean.startsWith("https://") -> clean
                clean.startsWith("/") -> "https://duckduckgo.com$clean"
                else -> clean
            }
        }

        internal fun stripHtmlTags(text: String): String =
            text.replace(Regex("<[^>]*>"), "")

        /**
         * Unescapes the HTML entities DuckDuckGo emits in titles and
         * snippets. `&amp;` goes last so `&amp;#39;` (literal `&#39;`)
         * is not double-decoded into `'`.
         */
        internal fun unescapeHtmlEntities(text: String): String {
            var out = text
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
            out = Regex("&#(\\d+);").replace(out) { match ->
                match.groupValues[1].toIntOrNull()
                    ?.let { runCatching { it.toChar().toString() }.getOrNull() }
                    ?: match.value
            }
            out = Regex("&#x([0-9a-fA-F]+);").replace(out) { match ->
                match.groupValues[1].toIntOrNull(16)
                    ?.let { runCatching { it.toChar().toString() }.getOrNull() }
                    ?: match.value
            }
            return out.replace("&amp;", "&")
        }
    }
}
