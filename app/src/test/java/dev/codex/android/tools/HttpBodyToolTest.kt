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
import java.net.InetAddress

/**
 * JVM unit tests for [HttpBodyTool]: full raw body (JSON/HTML untouched),
 * SSRF refusals reusing [WebFetchTool]'s validation, redirect limits,
 * non-text refusal naming the content type, and the 1 MB cap.
 */
class HttpBodyToolTest {

    private val publicIp: InetAddress =
        InetAddress.getByAddress("example.com", byteArrayOf(93, 184.toByte(), 216.toByte(), 34))

    private fun fakeClient(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()

    private fun response(
        request: Request,
        code: Int,
        body: String = "",
        contentType: String = "text/plain",
        location: String? = null,
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Status $code")
            .header("Content-Type", contentType)
            .body(body.toResponseBody(contentType.toMediaType()))
        if (location != null) builder.header("Location", location)
        return builder.build()
    }

    private fun tool(client: OkHttpClient): HttpBodyTool =
        HttpBodyTool(client) { listOf(publicIp) }

    @Test
    fun execute_returnsRawJsonBodyUntouched() = runBlocking {
        val json = """{"key": "value", "list": [1, 2, 3]}"""
        val client = fakeClient { req -> response(req, 200, json, "application/json") }
        val res = tool(client).execute(buildJsonObject { put("url", "https://example.com/data.json") })
        assertTrue(res.ok)
        assertEquals(json, res.output)
    }

    @Test
    fun execute_returnsRawHtmlBodyUntouched() = runBlocking {
        // WebFetchTool would strip this to plain text; http-body must not.
        val html = "<html><head><title>T</title></head><body><h1>Hi</h1><p>x</p></body></html>"
        val client = fakeClient { req -> response(req, 200, html, "text/html; charset=utf-8") }
        val res = tool(client).execute(buildJsonObject { put("url", "https://example.com/page") })
        assertTrue(res.ok)
        assertEquals(html, res.output)
    }

    @Test
    fun execute_returnsCsvAndXmlAsIs() = runBlocking {
        val csv = "a,b,c\n1,2,3\n"
        val client = fakeClient { req -> response(req, 200, csv, "text/csv") }
        val res = tool(client).execute(buildJsonObject { put("url", "https://example.com/data.csv") })
        assertTrue(res.ok)
        assertEquals(csv, res.output)
    }

    @Test
    fun execute_refusesSsrfTargetsBeforeAnyNetworkCall() = runBlocking {
        var networkCalls = 0
        val client = fakeClient { req ->
            networkCalls++
            response(req, 200, "should never be reached")
        }
        val t = tool(client)
        for (url in listOf(
            "http://localhost/admin",
            "http://127.0.0.1:9000/status",
            "http://10.0.0.1/internal",
            "http://169.254.169.254/latest/meta-data/",
            "http://metadata.google.internal/",
            "http://[::1]/",
            "file:///etc/passwd",
        )) {
            val res = t.execute(buildJsonObject { put("url", url) })
            assertFalse("Expected SSRF refusal for $url", res.ok)
            assertTrue("Expected error for $url", !res.error.isNullOrBlank())
        }
        assertEquals(0, networkCalls)
    }

    @Test
    fun execute_refusesNonTextContentTypeNamingIt() = runBlocking {
        val client = fakeClient { req -> response(req, 200, "PNG-binary", contentType = "image/png") }
        val res = tool(client).execute(buildJsonObject { put("url", "https://example.com/logo.png") })
        assertFalse(res.ok)
        assertTrue(res.error!!.contains("Refused non-text content type"))
        assertTrue(res.error!!.contains("image/png"))
    }

    @Test
    fun execute_followsRedirectsButBlocksRedirectToPrivate() = runBlocking {
        val client = fakeClient { req ->
            when (req.url.encodedPath) {
                "/a" -> response(req, 302, location = "https://example.com/b")
                "/b" -> response(req, 200, "final body", "text/plain")
                "/ssrf" -> response(req, 302, location = "http://10.0.0.9/internal")
                else -> response(req, 404)
            }
        }
        val t = tool(client)
        val ok = t.execute(buildJsonObject { put("url", "https://example.com/a") })
        assertTrue(ok.ok)
        assertEquals("final body", ok.output)

        val blocked = t.execute(buildJsonObject { put("url", "https://example.com/ssrf") })
        assertFalse(blocked.ok)
        assertTrue(blocked.error!!.contains("SSRF"))
    }

    @Test
    fun execute_capsBodyAt1MbWithTruncationNote() = runBlocking {
        val hugeBody = "C".repeat(1536 * 1024)
        val client = fakeClient { req -> response(req, 200, hugeBody, "text/plain") }
        val res = tool(client).execute(buildJsonObject { put("url", "https://example.com/big") })
        assertTrue(res.ok)
        assertTrue(res.output.startsWith("C".repeat(100)))
        assertEquals(HttpBodyTool.MAX_BODY_BYTES, res.output.substringBefore("\n\n[truncated:").toByteArray(Charsets.UTF_8).size)
        assertTrue(res.output.contains("[truncated: response body capped at 1048576 bytes]"))
    }

    @Test
    fun execute_httpErrorIsReported() = runBlocking {
        val client = fakeClient { req -> response(req, 404, "not found", "text/plain") }
        val res = tool(client).execute(buildJsonObject { put("url", "https://example.com/missing") })
        assertFalse(res.ok)
        assertTrue(res.error!!.contains("HTTP 404"))
    }

    @Test
    fun execute_missingUrlArgIsError() = runBlocking {
        val res = tool(fakeClient { req -> response(req, 200, "x") })
            .execute(buildJsonObject {})
        assertFalse(res.ok)
        assertTrue(res.error!!.contains("Missing required argument: url"))
    }

    @Test
    fun tool_nameAndSensitivity() {
        assertEquals("http-body", HttpBodyTool().name)
        assertFalse(HttpBodyTool().sensitive)
    }
}
