package dev.codex.android.tools

import dev.codex.android.api.GeminiVideoClient
import dev.codex.android.api.ShellOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * JVM tests for [GeminiVideoTool]: tool identity, argument parsing
 * (including the string-list params), and delegation to
 * [GeminiVideoClient] with a fake HTTP layer. The HTTP flow itself is
 * covered in GeminiVideoClientTest; this file pins the tool surface.
 */
class GeminiVideoToolTest {

    private val fakeVideoBytes = byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
    private val fakeVideoBase64 = Base64.getEncoder().encodeToString(fakeVideoBytes)

    private fun tempDir(): File = Files.createTempDirectory("gemini-video-tool-test").toFile()

    private fun jsonResponse(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Status $code")
            .header("Content-Type", "application/json")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun happyHttp(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            when {
                url.contains("/v1beta/interactions") && req.method == "POST" ->
                    jsonResponse(req, 200, """{"id": "v1_tool", "status": "in_progress", "steps": []}""")
                url.contains("/v1beta/interactions/v1_tool") ->
                    jsonResponse(req, 200, """{"id": "v1_tool", "status": "completed", "steps": [{"type": "model_output", "content": [{"type": "video", "data": "$fakeVideoBase64", "mime_type": "video/mp4"}]}]}""")
                else -> jsonResponse(req, 404, "{}")
            }
        })
        .build()

    private fun toolFor(dir: File, apiKey: String = "test-gemini-key"): GeminiVideoTool =
        GeminiVideoTool(
            apiKey = apiKey,
            outputDir = dir,
            httpClient = happyHttp(),
            shellRunner = null,
            clientFactory = { key, outDir, http, shell ->
                GeminiVideoClient(key, outDir, http, shell, restBase = "https://gemini.test", interactionPollIntervalMs = 10L, filePollIntervalMs = 10L)
            },
        )

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                is String -> put(key, value)
                is Boolean -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
            }
        }
    }

    @Test
    fun tool_identity() {
        val tool = GeminiVideoTool(apiKey = "k", outputDir = tempDir())
        assertEquals("gemini_video", tool.name)
        assertFalse(tool.sensitive)
        assertTrue(tool.description.contains("Gemini Omni Flash"))
    }

    @Test
    fun execute_textToVideo_savesFile() = runBlocking {
        val dir = tempDir()
        val result = toolFor(dir).execute(args("prompt" to "A cat drinking tea"))
        assertTrue(result.error ?: "expected success", result.ok)
        assertTrue(result.output.contains("Saved video to"))
        assertTrue(result.output.contains("v1_tool"))
        val path = result.output.substringAfter("Saved video to ").substringBefore(" (")
        assertTrue(File(path).isFile)
        assertEquals(fakeVideoBytes.toList(), File(path).readBytes().toList())
    }

    @Test
    fun execute_missingPrompt_fails() = runBlocking {
        val result = toolFor(tempDir()).execute(buildJsonObject {})
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("Missing required argument: prompt"))
    }

    @Test
    fun execute_invalidDuration_failsBeforeNetwork() = runBlocking {
        var calls = 0
        val counting = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                calls++
                jsonResponse(chain.request(), 200, "{}")
            })
            .build()
        val tool = GeminiVideoTool(
            apiKey = "k", outputDir = tempDir(), httpClient = counting,
            clientFactory = { key, outDir, http, shell ->
                GeminiVideoClient(key, outDir, http, shell, restBase = "https://gemini.test")
            },
        )
        val result = tool.execute(args("prompt" to "x", "duration_seconds" to 2))
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("3 to 15 seconds"))
        assertEquals(0, calls)
    }

    @Test
    fun execute_noKey_fails() = runBlocking {
        val result = toolFor(tempDir(), apiKey = "").execute(args("prompt" to "x"))
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("API key"))
    }

    @Test
    fun stringListArg_acceptsArrayAndCommaString() {
        val fromArray = buildJsonObject {
            put("reference_images", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("a.png"))
                add(kotlinx.serialization.json.JsonPrimitive("b.png"))
            })
        }
        assertEquals(listOf("a.png", "b.png"), GeminiVideoTool.stringListArg(fromArray, "reference_images"))
        val fromString = buildJsonObject { put("reference_images", "a.png, b.png") }
        assertEquals(listOf("a.png", "b.png"), GeminiVideoTool.stringListArg(fromString, "reference_images"))
        assertEquals(emptyList<String>(), GeminiVideoTool.stringListArg(buildJsonObject {}, "reference_images"))
    }

    @Test
    fun shellOutcome_shape() {
        val outcome = ShellOutcome(0, "ok", "")
        assertEquals(0, outcome.exitCode)
    }
}
