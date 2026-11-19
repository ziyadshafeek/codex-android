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
 * JVM unit tests for [WebSearchTool]: blank-endpoint / blank-key error,
 * generic JSON parsing across result envelopes, and Bearer header + query
 * parameter passing.
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

    @Test
    fun execute_returnsNoSearchEndpointConfiguredWhenEndpointOrKeyBlank() = runBlocking {
        var networkCalls = 0
        val client = fakeClient { req ->
            networkCalls++
            jsonResponse(req, 200, """{"results":[]}""")
        }
        val args = buildJsonObject { put("query", "kotlin coroutines") }

        val blankEndpoint = WebSearchTool(endpoint = "   ", apiKey = "user-key", httpClient = client)
        val r1 = blankEndpoint.execute(args)
        assertFalse(r1.ok)
        assertEquals("no search endpoint configured", r1.error)

        val blankKey = WebSearchTool(endpoint = "https://search.example.com/api", apiKey = "", httpClient = client)
        val r2 = blankKey.execute(args)
        assertFalse(r2.ok)
        assertEquals("no search endpoint configured", r2.error)

        val bothBlank = WebSearchTool(endpoint = "", apiKey = "", httpClient = client)
        val r3 = bothBlank.execute(args)
        assertFalse(r3.ok)
        assertEquals("no search endpoint configured", r3.error)

        assertEquals(0, networkCalls)
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
