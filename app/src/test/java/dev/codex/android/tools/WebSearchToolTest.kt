package dev.codex.android.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [WebSearchTool]: DuckDuckGo HTML fallback when no
 * endpoint/key is configured (the default), BYOK JSON path when both
 * are configured, generic JSON parsing across result envelopes, and
 * Bearer header + query parameter passing.
 */
class WebSearchToolTest {

    private fun fakeClient(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()

    private fun jsonResponse(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Status $code")
            .header("Content-Type", "application/json")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun htmlResponse(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Status $code")
            .header("Content-Type", "text/html")
            .body(body.toResponseBody("text/html".toMediaType()))
            .build()

    @Test
    fun execute_usesDuckDuckGoWhenNoEndpointOrKeyConfigured() = runBlocking {
        val ddgHtml = """
            <html><body>
            <div class="result">
            <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fnews1&amp;rut=abc">Example News 1</a>
            <a class="result__snippet" href="https://example.com/news1">Snippet for news 1.</a>
            </div>
            <div class="result">
            <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fnews2&amp;rut=def">Example <b>News</b> 2</a>
            <a class="result__snippet" href="https://example.com/news2">Snippet for news 2 &amp; more.</a>
            </div>
            </body></html>
        """.trimIndent()
        var seenUrl: String? = null
        var seenQuery: String? = null
        var seenUserAgent: String? = null
        var networkCalls = 0
        val client = fakeClient { req ->
            networkCalls++
            seenUrl = req.url.toString()
            seenQuery = req.url.queryParameter("q")
            seenUserAgent = req.header("User-Agent")
            htmlResponse(req, 200, ddgHtml)
        }

        val args = buildJsonObject { put("query", "latest news") }
        // Blank endpoint, blank key, and one-side-only all fall back to DuckDuckGo.
        for (tool in listOf(
            WebSearchTool(endpoint = "", apiKey = "", httpClient = client),
            WebSearchTool(endpoint = "   ", apiKey = "user-key", httpClient = client),
            WebSearchTool(endpoint = "https://search.example.com/api", apiKey = "", httpClient = client),
        )) {
            networkCalls = 0
            val result = tool.execute(args)
            assertTrue(result.ok)
            assertEquals(1, networkCalls)
            assertTrue(seenUrl?.startsWith("https://html.duckduckgo.com/html/") == true)
            assertEquals("latest news", seenQuery)
            assertTrue(!seenUserAgent.isNullOrBlank())
            assertTrue(result.output.contains("1. Example News 1"))
            assertTrue(result.output.contains("https://example.com/news1"))
            assertTrue(result.output.contains("Snippet for news 1."))
            assertTrue(result.output.contains("2. Example News 2"))
            assertTrue(result.output.contains("https://example.com/news2"))
            assertTrue(result.output.contains("Snippet for news 2 & more."))
        }
    }

    @Test
    fun execute_duckDuckGoErrorReturnsClearErrorNotFakeResults() = runBlocking {
        // 503, retried once, still 503 → clear error naming DuckDuckGo, no results.
        var calls = 0
        val failingClient = fakeClient { req ->
            calls++
            htmlResponse(req, 503, "Service Unavailable")
        }
        val failing = WebSearchTool(endpoint = "", apiKey = "", httpClient = failingClient)
            .execute(buildJsonObject { put("query", "latest news") })
        assertFalse(failing.ok)
        assertTrue(failing.output.isEmpty())
        assertTrue(failing.error?.contains("DuckDuckGo") == true)
        assertEquals(2, calls)

        // 200 but a bot-check page → blocked error, never invented results.
        val blockedClient = fakeClient { req ->
            htmlResponse(req, 200, "<html><body><form>Please complete the captcha to continue</form></body></html>")
        }
        val blocked = WebSearchTool(endpoint = "", apiKey = "", httpClient = blockedClient)
            .execute(buildJsonObject { put("query", "latest news") })
        assertFalse(blocked.ok)
        assertTrue(blocked.output.isEmpty())
        assertTrue(blocked.error?.contains("DuckDuckGo") == true)
        assertTrue(blocked.error?.contains("bot check") == true)
    }

    @Test
    fun parseDuckDuckGoHtml_extractsUddgUrlsAndStripsTagsAndEntities() {
        val html = """
            <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%2Bb&amp;rut=x">A &amp; B <b>bold</b></a>
            <a class="result__snippet" href="https://example.com/a">Snip &#39;quoted&#39; &amp; done.</a>
            <a class="result__a" href="https://direct.example.com/page">Direct</a>
            <a class="result__snippet" href="https://direct.example.com/page">Direct snippet.</a>
        """.trimIndent()
        val hits = WebSearchTool.parseDuckDuckGoHtml(html, 5)
        assertEquals(2, hits?.size)
        assertEquals("A & B bold", hits?.get(0)?.title)
        assertEquals("https://example.com/a+b", hits?.get(0)?.url)
        assertEquals("Snip 'quoted' & done.", hits?.get(0)?.snippet)
        assertEquals("Direct", hits?.get(1)?.title)
        assertEquals("https://direct.example.com/page", hits?.get(1)?.url)
        assertEquals("Direct snippet.", hits?.get(1)?.snippet)
        assertEquals(null, WebSearchTool.parseDuckDuckGoHtml("<html>captcha challenge</html>", 5))
        assertEquals(0, WebSearchTool.parseDuckDuckGoHtml("<html><body>no results here</body></html>", 5)?.size)
    }

    @Test
    fun execute_sendsQueryAndAuthAndParsesGenericResults() = runBlocking {
        var seenQuery: String? = null
        var seenAuth: String? = null
        val payload = """
            {
              "results": [
                {
                  "title": "Kotlin Coroutines Guide",
                  "url": "https://kotlinlang.org/docs/coroutines-guide.html",
                  "snippet": "Asynchronous programming with structured concurrency."
                },
                {
                  "name": "Android Coroutines Best Practices",
                  "link": "https://developer.android.com/kotlin/coroutines",
                  "description": "Use Dispatchers.IO for disk and network operations."
                }
              ]
            }
        """.trimIndent()
        val client = fakeClient { req ->
            seenQuery = req.url.queryParameter("q")
            seenAuth = req.header("Authorization")
            jsonResponse(req, 200, payload)
        }

        val tool = WebSearchTool(
            endpoint = "https://search.example.com/v1/search",
            apiKey = "byok-search-key",
            httpClient = client,
        )
        assertFalse(tool.sensitive)
        assertEquals("websearch", tool.name)

        val result = tool.execute(buildJsonObject { put("query", "kotlin coroutines") })
        assertTrue(result.ok)
        assertEquals("kotlin coroutines", seenQuery)
        assertEquals("Bearer byok-search-key", seenAuth)
        assertTrue(result.output.contains("1. Kotlin Coroutines Guide"))
        assertTrue(result.output.contains("https://kotlinlang.org/docs/coroutines-guide.html"))
        assertTrue(result.output.contains("Asynchronous programming with structured concurrency."))
        assertTrue(result.output.contains("2. Android Coroutines Best Practices"))
        assertTrue(result.output.contains("https://developer.android.com/kotlin/coroutines"))
    }

    @Test
    fun parseSearchResults_supportsWebResultsItemsAndTopLevelArray() {
        val braveStyle = """
            {
              "web": {
                "results": [
                  { "title": "Brave Hit", "url": "https://example.com/b", "description": "Brave desc" }
                ]
              }
            }
        """.trimIndent()
        val hits = WebSearchTool.parseSearchResults(braveStyle, 5)
        assertEquals(1, hits?.size)
        assertEquals("Brave Hit", hits?.first()?.title)

        val topLevelArray = """
            [
              { "title": "Direct Hit", "href": "https://example.com/d", "content": "Direct content" }
            ]
        """.trimIndent()
        val arrayHits = WebSearchTool.parseSearchResults(topLevelArray, 5)
        assertEquals(1, arrayHits?.size)
        assertEquals("https://example.com/d", arrayHits?.first()?.url)
        assertEquals("Direct content", arrayHits?.first()?.snippet)
    }
}
