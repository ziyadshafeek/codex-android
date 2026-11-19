package dev.codex.android.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

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

    @Test
    fun execute_refusesRedirectToPrivateOnHop2AndHop3() = runBlocking {
        var networkCalls = 0
        val client = fakeClient { req ->
            networkCalls++
            when (req.url.encodedPath) {
                "/h2a" -> response(req, 302, location = "https://example.com/h2b")
                "/h2b" -> response(req, 302, location = "http://10.0.0.9/internal")
                "/h3a" -> response(req, 302, location = "https://example.com/h3b")
                "/h3b" -> response(req, 302, location = "https://example.com/h3c")
                "/h3c" -> response(req, 302, location = "http://169.254.169.254/latest/meta-data/")
                else -> response(req, 404)
            }
        }
        val tool = WebFetchTool(client) { listOf(publicIp) }

        // Private target on the 2nd hop: refused, and never fetched.
        val hop2 = tool.execute(buildJsonObject { put("url", "https://example.com/h2a") })
        assertFalse(hop2.ok)
        assertTrue(hop2.error!!.contains("SSRF"))
        assertEquals(2, networkCalls)

        // Private target on the 3rd hop: refused, and never fetched.
        val hop3 = tool.execute(buildJsonObject { put("url", "https://example.com/h3a") })
        assertFalse(hop3.ok)
        assertTrue(hop3.error!!.contains("SSRF"))
        assertEquals(5, networkCalls)
    }

    @Test
    fun validateSafeTarget_refuses6to4AndMappedIpv6FromDns() {
        // 6to4 2002:0a00:0001:: embeds private 10.0.0.1 in bytes 2..5.
        val sixToFourPrivate = InetAddress.getByAddress(
            "six.example",
            byteArrayOf(0x20, 0x02, 10, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        )
        // IPv4-mapped ::ffff:10.0.0.1.
        val mappedPrivate = InetAddress.getByAddress(
            "mapped.example",
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 10, 0, 0, 1),
        )
        // Public equivalents must stay allowed.
        val sixToFourPublic = InetAddress.getByAddress(
            "six-public.example",
            byteArrayOf(0x20, 0x02, 93, 184.toByte(), 216.toByte(), 34, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        )
        val mappedPublic = InetAddress.getByAddress(
            "mapped-public.example",
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 93, 184.toByte(), 216.toByte(), 34),
        )
        val tool = WebFetchTool(
            fakeClient { req -> response(req, 200, "ok", "text/plain") },
        ) { host ->
            when (host) {
                "six.example" -> listOf(sixToFourPrivate)
                "mapped.example" -> listOf(mappedPrivate)
                "six-public.example" -> listOf(sixToFourPublic)
                "mapped-public.example" -> listOf(mappedPublic)
                else -> listOf(publicIp)
            }
        }

        assertTrue(tool.validateSafeTarget("https://six.example/".toHttpUrl())!!.contains("SSRF"))
        assertTrue(tool.validateSafeTarget("https://mapped.example/".toHttpUrl())!!.contains("SSRF"))
        assertEquals(null, tool.validateSafeTarget("https://six-public.example/".toHttpUrl()))
        assertEquals(null, tool.validateSafeTarget("https://mapped-public.example/".toHttpUrl()))
    }

    @Test
    fun execute_refusesMissingContentTypeAndBinary() = runBlocking {
        val noTypeClient = fakeClient { req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("plain bytes".toResponseBody(null))
                .build()
        }
        val noTypeTool = WebFetchTool(noTypeClient) { listOf(publicIp) }
        val noType = noTypeTool.execute(buildJsonObject { put("url", "https://example.com/file") })
        assertFalse(noType.ok)
        assertTrue(noType.error!!.contains("Refused non-text content type"))

        val binaryClient = fakeClient { req ->
            response(req, 200, "binary", contentType = "application/octet-stream")
        }
        val binaryTool = WebFetchTool(binaryClient) { listOf(publicIp) }
        val binary = binaryTool.execute(buildJsonObject { put("url", "https://example.com/blob") })
        assertFalse(binary.ok)
        assertTrue(binary.error!!.contains("Refused non-text content type"))
    }

    @Test
    fun execute_handlesBodyLargerThanDownloadCap() = runBlocking {
        // 1.5 MB body exceeds the 1 MB download cap; the bounded reader must
        // not grow unboundedly, and the 200 KB output cap still applies.
        val hugeBody = "B".repeat(1536 * 1024)
        val client = fakeClient { req -> response(req, 200, hugeBody, "text/plain") }
        val tool = WebFetchTool(client) { listOf(publicIp) }
        val res = tool.execute(buildJsonObject { put("url", "https://example.com/big") })
        assertTrue(res.ok)
        assertEquals(WebFetchTool.MAX_OUTPUT_BYTES, res.output.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun truncateUtf8Bytes_neverSplitsMultiByteCharacter() {
        // "é" is 2 UTF-8 bytes; cutting at an odd byte must step back to a
        // whole character, never emit U+FFFD.
        val twoByte = "é".repeat(100) // 200 bytes
        val cut199 = WebFetchTool.truncateUtf8Bytes(twoByte, 199)
        assertEquals(99, cut199.length)
        assertEquals(198, cut199.toByteArray(Charsets.UTF_8).size)
        assertFalse(cut199.contains('\uFFFD'))

        // Emoji is 4 UTF-8 bytes; cutting 1 byte into it must step back.
        val fourByte = "😀".repeat(10) // 40 bytes
        val cut39 = WebFetchTool.truncateUtf8Bytes(fourByte, 39)
        assertEquals(9, cut39.length)
        assertEquals(36, cut39.toByteArray(Charsets.UTF_8).size)
        assertFalse(cut39.contains('\uFFFD'))

        // Exact fit and under-cap are untouched.
        assertEquals(twoByte, WebFetchTool.truncateUtf8Bytes(twoByte, 200))
        assertEquals("abc", WebFetchTool.truncateUtf8Bytes("abc", 10))
    }

    @Test
    fun execute_emptyBody200_returnsOkEmpty() = runBlocking {
        val client = fakeClient { req -> response(req, 200, "", "text/plain") }
        val tool = WebFetchTool(client) { listOf(publicIp) }
        val res = tool.execute(buildJsonObject { put("url", "https://example.com/empty") })
        assertTrue(res.ok)
        assertEquals("", res.output)
    }

    @Test
    fun pinnedDns_refusesReboundPrivateAddressOnSecondLookup() {
        // Simulates DNS rebinding: the validator's resolution is public, but
        // a later resolution (the one the connection would use) is private.
        // The pinned DNS must refuse the second lookup outright.
        var calls = 0
        val tool = WebFetchTool(
            fakeClient { req -> response(req, 200, "ok", "text/plain") },
        ) { host ->
            calls++
            if (calls == 1) {
                listOf(publicIp)
            } else {
                listOf(InetAddress.getByAddress(host, byteArrayOf(10, 0, 0, 42)))
            }
        }

        assertEquals(listOf(publicIp), tool.pinnedDns.lookup("rebind.example.com"))
        assertThrows(UnknownHostException::class.java) {
            tool.pinnedDns.lookup("rebind.example.com")
        }
    }

    @Test
    fun client_usesPinnedDnsNotSystemDns() {
        // Interceptor fakes never reach DNS, so the wiring proof is that the
        // client's Dns instance IS the pinned one — not Dns.SYSTEM.
        val tool = WebFetchTool(
            fakeClient { req -> response(req, 200, "ok", "text/plain") },
        ) { listOf(publicIp) }
        assertTrue(tool.client.dns === tool.pinnedDns)
        assertFalse(tool.client.dns === Dns.SYSTEM)
    }
}
