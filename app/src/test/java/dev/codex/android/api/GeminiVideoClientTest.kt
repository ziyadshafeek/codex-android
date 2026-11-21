package dev.codex.android.api

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * JVM tests for [GeminiVideoClient] with an OkHttp Interceptor fake:
 * argument validation, ffmpeg command builders, ffprobe parsing, and
 * the Files-upload → interaction → poll → save flow. No network, no
 * real ffmpeg, no Android APIs.
 */
class GeminiVideoClientTest {

    private val fakeVideoBytes = byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
    private val fakeVideoBase64 = Base64.getEncoder().encodeToString(fakeVideoBytes)

    private fun tempDir(): File = Files.createTempDirectory("gemini-video-test").toFile()

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

    private fun bytesResponse(request: Request, bytes: ByteArray): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "video/mp4")
            .body(bytes.toResponseBody("video/mp4".toMediaType()))
            .build()

    /** Happy-path fake: upload → ACTIVE → create → in_progress → completed with base64 video. */
    private fun happyClient(onRequest: ((Request) -> Unit)? = null): OkHttpClient {
        var polls = 0
        return fakeClient { req ->
            onRequest?.invoke(req)
            val url = req.url.toString()
            when {
                url.contains("/upload/v1beta/files") ->
                    jsonResponse(req, 200, """{"file": {"name": "files/abc123", "state": "PROCESSING"}}""")
                url.contains("/v1beta/files/abc123") ->
                    jsonResponse(req, 200, """{"name": "files/abc123", "state": "ACTIVE"}""")
                url.contains("/v1beta/interactions") && req.method == "POST" ->
                    jsonResponse(req, 200, """{"id": "v1_test", "status": "in_progress", "steps": [], "usage": {}}""")
                url.contains("/v1beta/interactions/v1_test") -> {
                    polls++
                    if (polls < 2) {
                        jsonResponse(req, 200, """{"id": "v1_test", "status": "in_progress", "steps": []}""")
                    } else {
                        jsonResponse(
                            req, 200,
                            """{"id": "v1_test", "status": "completed", "steps": [{"type": "model_output", "content": [{"type": "video", "data": "$fakeVideoBase64", "mime_type": "video/mp4"}]}]}""",
                        )
                    }
                }
                else -> jsonResponse(req, 404, """{"error": "unexpected $url"}""")
            }
        }
    }

    private fun clientFor(
        apiKey: String = "test-gemini-key",
        dir: File,
        http: OkHttpClient,
        shell: ShellRunner? = null,
    ): GeminiVideoClient = GeminiVideoClient(
        apiKey = apiKey,
        outputDir = dir,
        httpClient = http,
        shellRunner = shell,
        restBase = "https://gemini.test",
        interactionPollIntervalMs = 10L,
        filePollIntervalMs = 10L,
    )

    // ---- Validation ----

    @Test
    fun validate_acceptsPlainTextToVideo() {
        assertNull(GeminiVideoClient.validate(GeminiVideoRequest(prompt = "A cat drinking tea")))
    }

    @Test
    fun validate_rejectsBadDurations() {
        assertNotNull(GeminiVideoClient.validate(GeminiVideoRequest(prompt = "x", durationSeconds = 2)))
        assertNotNull(GeminiVideoClient.validate(GeminiVideoRequest(prompt = "x", durationSeconds = 16)))
        assertNull(GeminiVideoClient.validate(GeminiVideoRequest(prompt = "x", durationSeconds = 15)))
        assertNull(GeminiVideoClient.validate(GeminiVideoRequest(prompt = "x", durationSeconds = 3)))
    }

    @Test
    fun validate_twoFrameCapAndLastNeedsFirst() {
        assertNotNull(
            GeminiVideoClient.validate(
                GeminiVideoRequest(prompt = "x", lastFramePath = "end.png"),
            ),
        )
        assertNotNull(
            GeminiVideoClient.validate(
                GeminiVideoRequest(prompt = "x", firstFramePath = "a.png", lastFramePath = "b.png", durationSeconds = 12),
            ),
        )
        assertNull(
            GeminiVideoClient.validate(
                GeminiVideoRequest(prompt = "x", firstFramePath = "a.png", lastFramePath = "b.png", durationSeconds = 10),
            ),
        )
    }

    @Test
    fun validate_referenceLimits() {
        assertNotNull(
            GeminiVideoClient.validate(
                GeminiVideoRequest(prompt = "x", referenceImagePaths = List(7) { "r$it.png" }),
            ),
        )
        assertNotNull(
            GeminiVideoClient.validate(
                GeminiVideoRequest(prompt = "x", referenceVideoPaths = List(4) { "r$it.mp4" }),
            ),
        )
        assertNotNull(
            GeminiVideoClient.validate(
                GeminiVideoRequest(prompt = "x", referenceVideoPaths = listOf("a.mp4", "b.mp4"), durationSeconds = 12),
            ),
        )
        assertNull(
            GeminiVideoClient.validate(
                GeminiVideoRequest(prompt = "x", referenceVideoPaths = listOf("a.mp4"), durationSeconds = 15),
            ),
        )
        // Reference videos cannot edit or extend.
        assertNotNull(
            GeminiVideoClient.validate(
                GeminiVideoRequest(prompt = "x", inputVideoPath = "in.mp4", referenceVideoPaths = listOf("r.mp4")),
            ),
        )
        assertNotNull(
            GeminiVideoClient.validate(
                GeminiVideoRequest(prompt = "x", previousInteractionId = "v1_1", referenceVideoPaths = listOf("r.mp4")),
            ),
        )
    }

    @Test
    fun validate_rejectsBadResolutionAndAspect() {
        assertNotNull(GeminiVideoClient.validate(GeminiVideoRequest(prompt = "x", resolution = "8k")))
        assertNotNull(GeminiVideoClient.validate(GeminiVideoRequest(prompt = "x", aspectRatio = "4:3")))
        assertNull(GeminiVideoClient.validate(GeminiVideoRequest(prompt = "x", resolution = "4k", aspectRatio = "9:16")))
    }

    @Test
    fun validate_rejectsBlankPrompt() {
        assertNotNull(GeminiVideoClient.validate(GeminiVideoRequest(prompt = "   ")))
    }

    // ---- ffmpeg builders ----

    @Test
    fun buildPrepCommand_trimsScalesAndStripsAudio() {
        val cmd = GeminiVideoClient.buildPrepCommand(
            "my clip.mp4", "/tmp/out.mp4", durationSeconds = 10, stripAudio = true, scaleToFit = true,
        )
        assertTrue(cmd.startsWith("ffmpeg -y"))
        assertTrue(cmd.contains("-i 'my clip.mp4'"))
        assertTrue(cmd.contains("-t 10"))
        assertTrue(cmd.contains("-an"))
        assertTrue(cmd.contains("scale="))
        assertTrue(cmd.contains("libx264"))
        assertTrue(cmd.endsWith("'/tmp/out.mp4'"))
        val noStrip = GeminiVideoClient.buildPrepCommand("in.mp4", "out.mp4", stripAudio = false, scaleToFit = false)
        assertFalse(noStrip.contains("-an"))
        assertFalse(noStrip.contains("scale="))
    }

    @Test
    fun buildInspectCommand_andQuoteShellArg() {
        assertEquals(
            "ffprobe -v quiet -print_format json -show_format -show_streams 'a b.mp4'",
            GeminiVideoClient.buildInspectCommand("a b.mp4"),
        )
        assertEquals("'it'\\''s.mp4'", GeminiVideoClient.quoteShellArg("it's.mp4"))
    }

    @Test
    fun parseFfprobeJson_readsDurationSizeAndAudio() {
        val probe = GeminiVideoClient.parseFfprobeJson(
            """{"format": {"duration": "12.5"}, "streams": [
              {"codec_type": "video", "width": 1920, "height": 1080},
              {"codec_type": "audio"}]}""",
        )!!
        assertEquals(12.5, probe.durationSeconds!!, 0.001)
        assertEquals(1920, probe.width)
        assertEquals(1080, probe.height)
        assertTrue(probe.hasAudio)
        assertNull(GeminiVideoClient.parseFfprobeJson("not json"))
        val noAudio = GeminiVideoClient.parseFfprobeJson(
            """{"format": {"duration": "5.0"}, "streams": [{"codec_type": "video", "width": 640, "height": 360}]}""",
        )!!
        assertFalse(noAudio.hasAudio)
    }

    @Test
    fun mimeTypeFor_knowsImagesAndVideo() {
        assertEquals("image/png", GeminiVideoClient.mimeTypeFor("a.png"))
        assertEquals("image/jpeg", GeminiVideoClient.mimeTypeFor("a.JPG"))
        assertEquals("video/mp4", GeminiVideoClient.mimeTypeFor("a.mp4"))
        assertNull(GeminiVideoClient.mimeTypeFor("a.txt"))
        assertNull(GeminiVideoClient.mimeTypeFor("a"))
    }

    // ---- HTTP flow ----

    @Test
    fun generate_textToVideo_savesMp4() = runBlocking {
        val dir = tempDir()
        val seenUrls = mutableListOf<String>()
        val seenKeys = mutableListOf<String?>()
        val client = clientFor(dir = dir, http = happyClient { req ->
            seenUrls += req.url.toString()
            seenKeys += req.header("x-goog-api-key")
        })
        val outcome = client.generate(GeminiVideoRequest(prompt = "A cat drinking tea"))
        assertTrue(outcome.error ?: "expected success", outcome.ok)
        assertEquals("v1_test", outcome.interactionId)
        assertEquals(fakeVideoBytes.size.toLong(), outcome.bytes)
        val saved = File(outcome.outputPath!!)
        assertTrue(saved.isFile)
        assertEquals(fakeVideoBytes.toList(), saved.readBytes().toList())
        assertTrue(saved.name.endsWith(".mp4"))
        assertTrue(seenUrls.any { it.contains("/v1beta/interactions") })
        assertTrue(seenKeys.all { it == "test-gemini-key" })
        // No key in any URL.
        assertFalse(seenUrls.any { it.contains("test-gemini-key") })
    }

    @Test
    fun generate_noKeyMakesZeroCalls() = runBlocking {
        var calls = 0
        val client = clientFor(apiKey = "  ", dir = tempDir(), http = happyClient { calls++ })
        val outcome = client.generate(GeminiVideoRequest(prompt = "x"))
        assertFalse(outcome.ok)
        assertTrue(outcome.error!!.contains("API key"))
        assertEquals(0, calls)
    }

    @Test
    fun generate_authFailureReportsKeyProblem() = runBlocking {
        val client = clientFor(
            dir = tempDir(),
            http = fakeClient { req -> jsonResponse(req, 401, """{"error": {"message": "bad key"}}""") },
        )
        val outcome = client.generate(GeminiVideoRequest(prompt = "x"))
        assertFalse(outcome.ok)
        assertTrue(outcome.error!!.contains("API key"))
    }

    @Test
    fun generate_failedInteractionReportsServerError() = runBlocking {
        val client = clientFor(
            dir = tempDir(),
            http = fakeClient { req ->
                val url = req.url.toString()
                when {
                    url.contains("/v1beta/interactions") && req.method == "POST" ->
                        jsonResponse(req, 200, """{"id": "v1_bad", "status": "in_progress", "steps": []}""")
                    url.contains("/v1beta/interactions/v1_bad") ->
                        jsonResponse(req, 200, """{"id": "v1_bad", "status": "failed", "error": {"message": "region not supported"}}""")
                    else -> jsonResponse(req, 404, "{}")
                }
            },
        )
        val outcome = client.generate(GeminiVideoRequest(prompt = "x"))
        assertFalse(outcome.ok)
        assertTrue(outcome.error!!.contains("region not supported"))
    }

    @Test
    fun generate_timeoutReportsInteractionId() = runBlocking {
        val client = clientFor(
            dir = tempDir(),
            http = fakeClient { req ->
                val url = req.url.toString()
                when {
                    url.contains("/v1beta/interactions") && req.method == "POST" ->
                        jsonResponse(req, 200, """{"id": "v1_slow", "status": "in_progress", "steps": []}""")
                    url.contains("/v1beta/interactions/v1_slow") ->
                        jsonResponse(req, 200, """{"id": "v1_slow", "status": "in_progress", "steps": []}""")
                    else -> jsonResponse(req, 404, "{}")
                }
            },
        )
        val outcome = client.generate(GeminiVideoRequest(prompt = "x", timeoutMs = 1000L))
        assertFalse(outcome.ok)
        assertTrue(outcome.error!!.contains("v1_slow"))
    }

    @Test
    fun generate_completedWithoutVideoIsHonestError() = runBlocking {
        val client = clientFor(
            dir = tempDir(),
            http = fakeClient { req ->
                val url = req.url.toString()
                when {
                    url.contains("/v1beta/interactions") && req.method == "POST" ->
                        jsonResponse(req, 200, """{"id": "v1_empty", "status": "in_progress", "steps": []}""")
                    url.contains("/v1beta/interactions/v1_empty") ->
                        jsonResponse(req, 200, """{"id": "v1_empty", "status": "completed", "steps": [{"type": "model_output", "content": [{"type": "text", "text": "no video"}]}]}""")
                    else -> jsonResponse(req, 404, "{}")
                }
            },
        )
        val outcome = client.generate(GeminiVideoRequest(prompt = "x"))
        assertFalse(outcome.ok)
        assertTrue(outcome.error!!.contains("no video"))
    }

    @Test
    fun generate_videoUriIsDownloaded() = runBlocking {
        val client = clientFor(
            dir = tempDir(),
            http = fakeClient { req ->
                val url = req.url.toString()
                when {
                    url == "https://cdn.example.com/clip.mp4" -> bytesResponse(req, fakeVideoBytes)
                    url.contains("/v1beta/interactions") && req.method == "POST" ->
                        jsonResponse(req, 200, """{"id": "v1_uri", "status": "in_progress", "steps": []}""")
                    url.contains("/v1beta/interactions/v1_uri") ->
                        jsonResponse(req, 200, """{"id": "v1_uri", "status": "completed", "steps": [{"type": "model_output", "content": [{"type": "video", "uri": "https://cdn.example.com/clip.mp4"}]}]}""")
                    else -> jsonResponse(req, 404, "{}")
                }
            },
        )
        val outcome = client.generate(GeminiVideoRequest(prompt = "x"))
        assertTrue(outcome.error ?: "expected success", outcome.ok)
        assertEquals(fakeVideoBytes.toList(), File(outcome.outputPath!!).readBytes().toList())
    }

    @Test
    fun generate_longSourceVideoIsTrimmedWithFfmpeg() = runBlocking {
        val dir = tempDir()
        val source = File(dir, "long.mp4").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val commands = mutableListOf<String>()
        val shell: ShellRunner = { command ->
            commands += command
            when {
                command.startsWith("ffprobe") -> ShellOutcome(
                    0,
                    """{"format": {"duration": "25.0"}, "streams": [{"codec_type": "video", "width": 1920, "height": 1080}, {"codec_type": "audio"}]}""",
                    "",
                )
                command.startsWith("ffmpeg") -> {
                    val outPath = command.trim().removeSuffix("'").substringAfterLast("'")
                    File(outPath).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(9, 9)) }
                    ShellOutcome(0, "", "")
                }
                else -> ShellOutcome(127, "", "unknown command")
            }
        }
        val seenUploads = mutableListOf<String>()
        val client = clientFor(dir = dir, http = happyClient { req ->
            if (req.url.toString().contains("/upload/")) seenUploads += req.url.toString()
        }, shell = shell)
        val outcome = client.generate(
            GeminiVideoRequest(prompt = "Make it anime", inputVideoPath = source.absolutePath, stripAudio = true),
        )
        assertTrue(outcome.error ?: "expected success", outcome.ok)
        assertTrue(commands.any { it.startsWith("ffprobe") })
        val ffmpeg = commands.first { it.startsWith("ffmpeg") }
        assertTrue(ffmpeg.contains("-t 10"))
        assertTrue(ffmpeg.contains("-an"))
        assertTrue(seenUploads.isNotEmpty())
    }

    @Test
    fun generate_withoutShellSendsSourceAsIsWithNote() = runBlocking {
        val dir = tempDir()
        val source = File(dir, "clip.mp4").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val client = clientFor(dir = dir, http = happyClient(), shell = null)
        val outcome = client.generate(
            GeminiVideoRequest(prompt = "Make it anime", inputVideoPath = source.absolutePath),
        )
        assertTrue(outcome.error ?: "expected success", outcome.ok)
        assertNotNull(outcome.note)
        assertTrue(outcome.note!!.contains("ffmpeg is not wired"))
    }

    @Test
    fun generate_ffmpegFailureIsReportedNotFaked() = runBlocking {
        val dir = tempDir()
        val source = File(dir, "clip.mp4").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        var calls = 0
        val shell: ShellRunner = { command ->
            calls++
            if (command.startsWith("ffprobe")) {
                ShellOutcome(0, """{"format": {"duration": "25.0"}, "streams": [{"codec_type": "video", "width": 1920, "height": 1080}]}""", "")
            } else {
                ShellOutcome(1, "", "ffmpeg: No such file")
            }
        }
        val client = clientFor(dir = dir, http = happyClient(), shell = shell)
        val outcome = client.generate(
            GeminiVideoRequest(prompt = "Make it anime", inputVideoPath = source.absolutePath),
        )
        assertFalse(outcome.ok)
        assertTrue(outcome.error!!.contains("Could not prepare"))
        assertEquals(2, calls) // inspect + one failed prep; no upload happened.
    }
}
