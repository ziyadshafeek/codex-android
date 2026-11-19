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
 * JVM unit tests for [WebFetchTool]: SSRF refusals (loopback, private IPs,
 * link-local, cloud metadata, localhost, DNS rebinding, redirect-to-private,
 * non-http scheme), redirect limit (max 3), non-text content-type refusal,
 * HTML stripping, and 200 KB output cap.
 */
class WebFetchToolTest {

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
        contentType: String = "text/html; charset=utf-8",
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

    @Test
    fun execute_refusesAllSsrfTargetsBeforeAnyNetworkCall() = runBlocking {
        var networkCalls = 0
        val client = fakeClient { req ->
            networkCalls++
            response(req, 200, "should never be reached")
        }
        val tool = WebFetchTool(
            httpClient = client,
            dnsResolver = { host ->
                if (host == "rebind.example.com") {
                    listOf(InetAddress.getByAddress(host, byteArrayOf(10, 0, 0, 42)))
                } else {
                    listOf(publicIp)
                }
            },
        )

        val blockedUrls = listOf(
            "http://localhost/admin",
            "http://localhost:8080/api",
            "http://sub.localhost/secret",
            "http://127.0.0.1:9000/status",
            "http://127.1.2.3/",
            "http://0.0.0.0/",
            "http://10.0.0.1/internal",
            "http://172.16.0.1/internal",
            "http://172.31.255.255/internal",
            "http://192.168.1.1/router",
            "http://169.254.169.254/latest/meta-data/",
            "http://169.254.10.20/link-local",
            "http://100.64.0.1/cgnat",
            "http://100.100.100.200/latest/meta-data/",
            "http://metadata.google.internal/computeMetadata/v1/",
            "http://[::1]:8080/",
            "http://[fe80::1]/",
            "http://[fd00:ec2::254]/latest/meta-data/",
            "http://[::ffff:127.0.0.1]/",
            "http://[::ffff:169.254.169.254]/",
            "http://rebind.example.com/secret",
            "file:///etc/passwd",
            "ftp://example.com/file.txt",
        )

        for (url in blockedUrls) {
            val res = tool.execute(buildJsonObject { put("url", url) })
            assertFalse("Expected SSRF refusal for $url", res.ok)
            assertTrue("Expected error message for $url", !res.error.isNullOrBlank())
        }
        assertEquals("Zero HTTP calls should be made for SSRF targets", 0, networkCalls)
    }

    @Test
    fun execute_stripsHtmlAndCapsAt200Kb() = runBlocking {
        val html = """
            <html>
              <head>
                <title>Ignored</title>
                <style>body { color: red; }</style>
                <script>alert("secret");</script>
              </head>
              <body>
                <h1>Hello &amp; Welcome</h1>
                <p>First paragraph with <b>bold</b> text.</p>
              </body>
            </html>
        """.trimIndent()
        val client = fakeClient { req -> response(req, 200, html, "text/html; charset=utf-8") }
        val tool = WebFetchTool(client) { listOf(publicIp) }

        val res = tool.execute(buildJsonObject { put("url", "https://example.com/doc") })
        assertTrue(res.ok)
        assertTrue(res.output.contains("Hello & Welcome"))
        assertTrue(res.output.contains("First paragraph with bold text."))
        assertFalse(res.output.contains("alert"))
        assertFalse(res.output.contains("color: red"))

        // 250 KB plain text body must be capped at 200 KB (204,800 bytes).
        val hugeBody = "A".repeat(250 * 1024)
        val hugeClient = fakeClient { req -> response(req, 200, hugeBody, "text/plain") }
        val hugeTool = WebFetchTool(hugeClient) { listOf(publicIp) }
        val hugeRes = hugeTool.execute(buildJsonObject { put("url", "https://example.com/huge") })
        assertTrue(hugeRes.ok)
        assertEquals(WebFetchTool.MAX_OUTPUT_BYTES, hugeRes.output.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun execute_followsUpToThreeRedirectsAndBlocksFourthOrSsrfRedirect() = runBlocking {
        val client = fakeClient { req ->
            when (req.url.encodedPath) {
                "/hop0" -> response(req, 302, location = "https://example.com/hop1")
                "/hop1" -> response(req, 302, location = "https://example.com/hop2")
                "/hop2" -> response(req, 302, location = "https://example.com/hop3")
                "/hop3" -> response(req, 200, "Reached after 3 redirects", "text/plain")
                "/too0" -> response(req, 302, location = "https://example.com/too1")
                "/too1" -> response(req, 302, location = "https://example.com/too2")
                "/too2" -> response(req, 302, location = "https://example.com/too3")
                "/too3" -> response(req, 302, location = "https://example.com/too4")
                "/ssrf-redirect" -> response(req, 302, location = "http://169.254.169.254/latest/meta-data/")
                else -> response(req, 404)
            }
        }
        val tool = WebFetchTool(client) { listOf(publicIp) }

        val okThree = tool.execute(buildJsonObject { put("url", "https://example.com/hop0") })
        assertTrue(okThree.ok)
        assertEquals("Reached after 3 redirects", okThree.output)

        val tooMany = tool.execute(buildJsonObject { put("url", "https://example.com/too0") })
        assertFalse(tooMany.ok)
        assertTrue(tooMany.error!!.contains("Too many redirects"))

        val ssrfRedirect = tool.execute(buildJsonObject { put("url", "https://example.com/ssrf-redirect") })
        assertFalse(ssrfRedirect.ok)
        assertTrue(ssrfRedirect.error!!.contains("SSRF"))
    }

    @Test
    fun execute_refusesNonTextContentTypes() = runBlocking {
        val client = fakeClient { req ->
            response(req, 200, "PNG-binary", contentType = "image/png")
        }
        val tool = WebFetchTool(client) { listOf(publicIp) }
        val res = tool.execute(buildJsonObject { put("url", "https://example.com/logo.png") })
        assertFalse(res.ok)
        assertTrue(res.error!!.contains("Refused non-text content type"))
    }
}
