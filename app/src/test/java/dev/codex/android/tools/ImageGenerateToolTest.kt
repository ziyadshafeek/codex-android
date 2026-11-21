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
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * JVM unit tests for [ImageGenerateTool] with an OkHttp Interceptor fake:
 * b64_json success, url-download success, 404 → "does not expose an image
 * endpoint", and no-key → clear error with zero network calls.
 */
class ImageGenerateToolTest {

    /** A real 1x1 transparent PNG. */
    private val tinyPngBase64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    private val tinyPng: ByteArray = Base64.getDecoder().decode(tinyPngBase64)

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

    private fun bytesResponse(request: Request, bytes: ByteArray, contentType: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", contentType)
            .body(bytes.toResponseBody(contentType.toMediaType()))
            .build()

    private fun tempDir(): File = Files.createTempDirectory("image-generate-test").toFile()

    @Test
    fun execute_b64JsonSuccessSavesPng() = runBlocking {
        var seenUrl: String? = null
        var seenAuth: String? = null
        var seenBody: String? = null
        val client = fakeClient { req ->
            seenUrl = req.url.toString()
            seenAuth = req.header("Authorization")
            seenBody = req.body?.let { body ->
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }
            jsonResponse(req, 200, """{"data":[{"b64_json":"$tinyPngBase64"}]}""")
        }
        val dir = tempDir()
        val tool = ImageGenerateTool(
            baseUrl = "https://coarena.example.com/api/v1",
            apiKey = "byok-key",
            outputDir = dir,
            httpClient = client,
        )
        val res = tool.execute(buildJsonObject {
            put("prompt", "a red bicycle")
            put("size", "1024x1024")
        })
        assertTrue(res.ok)
        assertEquals("https://coarena.example.com/api/v1/images/generations", seenUrl)
        assertEquals("Bearer byok-key", seenAuth)
        assertTrue(seenBody!!.contains("\"prompt\":\"a red bicycle\""))
        assertTrue(seenBody!!.contains("\"size\":\"1024x1024\""))
        assertTrue(seenBody!!.contains("\"n\":1"))
        val saved = dir.listFiles()!!.single()
        assertEquals("png", saved.extension)
        assertTrue(saved.readBytes().contentEquals(tinyPng))
        assertTrue(res.output.contains(saved.absolutePath))
        assertTrue(res.output.contains("${tinyPng.size} bytes"))
    }

    @Test
    fun execute_urlSuccessDownloadsAndSaves() = runBlocking {
        val client = fakeClient { req ->
            when {
                req.url.encodedPath.endsWith("/images/generations") ->
                    jsonResponse(req, 200, """{"data":[{"url":"https://images.example.com/pic.png"}]}""")
                req.url.host == "images.example.com" ->
                    bytesResponse(req, tinyPng, "image/png")
                else -> jsonResponse(req, 404, """{"error":"not found"}""")
            }
        }
        val dir = tempDir()
        val tool = ImageGenerateTool(
            baseUrl = "https://provider.example.com/v1/",
            apiKey = "byok-key",
            outputDir = dir,
            httpClient = client,
        )
        val res = tool.execute(buildJsonObject { put("prompt", "a lighthouse") })
        assertTrue(res.ok)
        val saved = dir.listFiles()!!.single()
        assertTrue(saved.readBytes().contentEquals(tinyPng))
        assertTrue(res.output.contains(saved.absolutePath))
    }

    @Test
    fun execute_urlDownloadDoesNotLeakApiKeyToThirdPartyHost() = runBlocking {
        var downloadAuth: String? = null
        val client = fakeClient { req ->
            when {
                req.url.encodedPath.endsWith("/images/generations") ->
                    jsonResponse(req, 200, """{"data":[{"url":"https://cdn.example.net/pic.png"}]}""")
                else -> {
                    downloadAuth = req.header("Authorization")
                    bytesResponse(req, tinyPng, "image/png")
                }
            }
        }
        val tool = ImageGenerateTool(
            baseUrl = "https://provider.example.com/v1",
            apiKey = "byok-key",
            outputDir = tempDir(),
            httpClient = client,
        )
        val res = tool.execute(buildJsonObject { put("prompt", "a lighthouse") })
        assertTrue(res.ok)
        assertEquals(null, downloadAuth)
    }

    @Test
    fun execute_404SaysProviderHasNoImageEndpoint() = runBlocking {
        var networkCalls = 0
        val client = fakeClient { req ->
            networkCalls++
            jsonResponse(req, 404, """{"error":{"message":"unknown endpoint","type":"not_found"}}""")
        }
        val dir = tempDir()
        val tool = ImageGenerateTool(
            baseUrl = "https://provider.example.com/v1",
            apiKey = "byok-key",
            outputDir = dir,
            httpClient = client,
        )
        val res = tool.execute(buildJsonObject { put("prompt", "a cat") })
        assertFalse(res.ok)
        assertTrue(res.error!!.contains("does not expose an image generation endpoint"))
        assertTrue(res.error!!.contains("HTTP 404"))
        assertEquals(1, networkCalls)
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun execute_405AlsoSaysNoImageEndpoint() {
        val error = ImageGenerateTool.mapHttpError(405, "Method Not Allowed")
        assertTrue(error.contains("does not expose an image generation endpoint"))
    }

    @Test
    fun execute_noKeyOrBaseUrlCallsNothing() = runBlocking {
        var networkCalls = 0
        val client = fakeClient { req ->
            networkCalls++
            jsonResponse(req, 200, """{"data":[]}""")
        }
        val args = buildJsonObject { put("prompt", "a cat") }

        val noKey = ImageGenerateTool("https://provider.example.com/v1", "", tempDir(), client)
        val r1 = noKey.execute(args)
        assertFalse(r1.ok)
        assertEquals(ImageGenerateTool.NO_PROVIDER_ERROR, r1.error)

        val noBase = ImageGenerateTool("", "byok-key", tempDir(), client)
        val r2 = noBase.execute(args)
        assertFalse(r2.ok)
        assertEquals(ImageGenerateTool.NO_PROVIDER_ERROR, r2.error)

        assertEquals(0, networkCalls)
    }

    @Test
    fun execute_invalidSizeIsError() = runBlocking {
        val tool = ImageGenerateTool(
            "https://provider.example.com/v1", "byok-key", tempDir(),
            fakeClient { req -> jsonResponse(req, 200, """{"data":[]}""") },
        )
        val res = tool.execute(buildJsonObject {
            put("prompt", "a cat")
            put("size", "huge")
        })
        assertFalse(res.ok)
        assertTrue(res.error!!.contains("Invalid size"))
    }

    @Test
    fun execute_emptyDataArrayIsErrorNotFakeImage() = runBlocking {
        val client = fakeClient { req ->
            jsonResponse(req, 200, """{"data":[]}""")
        }
        val dir = tempDir()
        val tool = ImageGenerateTool("https://provider.example.com/v1", "byok-key", dir, client)
        val res = tool.execute(buildJsonObject { put("prompt", "a cat") })
        assertFalse(res.ok)
        assertTrue(res.error!!.contains("no usable data"))
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun execute_outputPathArgIsHonoured() = runBlocking {
        val client = fakeClient { req ->
            jsonResponse(req, 200, """{"data":[{"b64_json":"$tinyPngBase64"}]}""")
        }
        val dir = tempDir()
        val tool = ImageGenerateTool("https://provider.example.com/v1", "byok-key", dir, client)
        val res = tool.execute(buildJsonObject {
            put("prompt", "a cat")
            put("output_path", "custom/name.png")
        })
        assertTrue(res.ok)
        assertTrue(File(dir, "custom/name.png").exists())
        assertTrue(res.output.contains("custom${File.separator}name.png"))
    }

    @Test
    fun parseImageResponse_supportsBothShapes() {
        val b64 = ImageGenerateTool.parseImageResponse("""{"data":[{"b64_json":"abc"}]}""")
        assertTrue(b64 is ImageGenerateTool.ImagePayload.B64)
        val url = ImageGenerateTool.parseImageResponse("""{"data":[{"url":"https://x/y.png"}]}""")
        assertTrue(url is ImageGenerateTool.ImagePayload.Url)
        assertEquals(null, ImageGenerateTool.parseImageResponse("""{"data":[]}"""))
        assertEquals(null, ImageGenerateTool.parseImageResponse("not json"))
    }

    @Test
    fun sniffExtension_detectsPngJpegWebp() {
        assertEquals("png", ImageGenerateTool.sniffExtension(tinyPng))
        assertEquals("jpg", ImageGenerateTool.sniffExtension(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        val webp = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0, 0, 0, 0,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
        )
        assertEquals("webp", ImageGenerateTool.sniffExtension(webp))
        assertEquals("png", ImageGenerateTool.sniffExtension(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun tool_nameAndSensitivity() {
        val tool = ImageGenerateTool("https://provider.example.com/v1", "k", tempDir())
        assertEquals("image-generate", tool.name)
        assertFalse(tool.sensitive)
    }
}
