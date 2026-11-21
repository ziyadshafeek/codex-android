package dev.codex.android.tools

import dev.codex.android.api.ChatChoice
import dev.codex.android.api.ChatMessage
import dev.codex.android.api.ChatResult
import dev.codex.android.api.OpenAiClient
import dev.codex.android.api.ToolDefinition
import dev.codex.android.core.CodexError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * JVM tests for [ReadImageTool].
 *
 * The model call is a fake [OpenAiClient] (no network, no MockWebServer
 * — that is androidTestImplementation only). The image preparer is
 * injected, so no `android.graphics.Bitmap` is ever touched on the
 * JVM. What is pinned down: the image genuinely travels as an
 * `image_url` data-URL part on the same path the chat client uses,
 * the prompt reaches the model, and every failure mode is a clear
 * error rather than a faked description.
 */
class ReadImageToolTest {

    private lateinit var workspaceDir: File

    @Before
    fun setUp() {
        workspaceDir = Files.createTempDirectory("read-image-test").toFile()
    }

    @After
    fun tearDown() {
        workspaceDir.deleteRecursively()
    }

    /** Fake model client: captures the request, answers with [reply]. */
    private class FakeClient(
        private val reply: String? = "A red bicycle leaning on a wall.",
        private val failure: Throwable? = null,
    ) : OpenAiClient {
        var capturedMessages: List<ChatMessage> = emptyList()
        var capturedModel: String = ""
        var calls: Int = 0

        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>,
            model: String,
            onPartialText: ((String) -> Unit)?,
        ): ChatResult {
            calls++
            capturedMessages = messages
            capturedModel = model
            failure?.let { throw it }
            return ChatResult(
                choices = listOf(ChatChoice(text = reply, toolCalls = emptyList(), finishReason = "stop")),
            )
        }
    }

    private fun imageFile(name: String = "photo.jpg", bytes: ByteArray = byteArrayOf(1, 2, 3)): File =
        File(workspaceDir, name).apply { writeBytes(bytes) }

    private fun tool(
        client: OpenAiClient,
        prepareCalls: MutableList<File> = mutableListOf(),
    ): ReadImageTool = ReadImageTool(
        client = client,
        model = "test-vision-model",
        workspaceDir = workspaceDir,
        prepareImage = { file ->
            prepareCalls += file
            "fake-jpeg-bytes".toByteArray(Charsets.UTF_8)
        },
    )

    @Test
    fun success_returnsModelTextAndSendsImageAsDataUrl() = runBlocking {
        val client = FakeClient()
        val file = imageFile()
        val prepareCalls = mutableListOf<File>()
        val res = tool(client, prepareCalls).execute(buildJsonObject { put("path", "photo.jpg") })

        assertTrue(res.ok)
        assertEquals("A red bicycle leaning on a wall.", res.output)
        assertEquals(listOf(file.canonicalFile), prepareCalls.map { it.canonicalFile })
        assertEquals(1, client.calls)
        assertEquals("test-vision-model", client.capturedModel)
        val message = client.capturedMessages.single()
        assertEquals("user", message.role)
        assertEquals(ReadImageTool.DEFAULT_PROMPT, message.content)
        val imageUrl = message.imageUrls.single()
        assertTrue(imageUrl.startsWith("data:image/jpeg;base64,"))
        assertFalse("Base64 must be NO_WRAP — no newlines in the data URL", imageUrl.contains("\n"))
        assertEquals(
            "fake-jpeg-bytes",
            String(Base64.getDecoder().decode(imageUrl.removePrefix("data:image/jpeg;base64,")), Charsets.UTF_8),
        )
        // No tools are offered to the vision call: it is a plain description turn.
        assertTrue(client.capturedMessages.size == 1)
    }

    @Test
    fun customPrompt_isSentAsContent() = runBlocking {
        val client = FakeClient(reply = "INVOICE-42")
        imageFile()
        val res = tool(client).execute(buildJsonObject {
            put("path", "photo.jpg")
            put("prompt", "Read out the invoice number only.")
        })
        assertTrue(res.ok)
        assertEquals("INVOICE-42", res.output)
        assertEquals("Read out the invoice number only.", client.capturedMessages.single().content)
    }

    @Test
    fun visionUnsupported_returnsClearErrorNotFakeDescription() = runBlocking {
        val client = FakeClient(
            failure = CodexError.Unknown(
                "The provider rejected the image or PDF (HTTP 400). " +
                    "Use a vision-capable model or attach text instead.",
            ),
        )
        imageFile()
        val res = tool(client).execute(buildJsonObject { put("path", "photo.jpg") })
        assertFalse(res.ok)
        assertTrue(res.error!!.contains("does not support vision"))
        assertFalse(res.output.contains("bicycle"))
    }

    @Test
    fun missingFile_returnsClearErrorAndNeverCallsModel() = runBlocking {
        val client = FakeClient()
        val prepareCalls = mutableListOf<File>()
        val res = tool(client, prepareCalls).execute(buildJsonObject { put("path", "no-such-image.jpg") })
        assertFalse(res.ok)
        assertTrue(res.error!!.contains("not found"))
        assertEquals(0, client.calls)
        assertTrue(prepareCalls.isEmpty())
    }

    @Test
    fun oversize_returnsClearErrorAndNeverCallsModel() = runBlocking {
        val big = File(workspaceDir, "big.jpg")
        big.writeBytes(ByteArray((ReadImageTool.MAX_IMAGE_BYTES + 1).toInt()))
        val client = FakeClient()
        val prepareCalls = mutableListOf<File>()
        val res = tool(client, prepareCalls).execute(buildJsonObject { put("path", "big.jpg") })
        assertFalse(res.ok)
        val err = res.error!!
        assertTrue(err.contains("too large"))
        assertTrue(err.contains("10 MB"))
        assertEquals(0, client.calls)
        assertTrue(prepareCalls.isEmpty())
    }

    @Test
    fun pathEscapingWorkspace_returnsClearError() = runBlocking {
        val client = FakeClient()
        val res = tool(client).execute(buildJsonObject { put("path", "../outside.jpg") })
        assertFalse(res.ok)
        assertTrue(res.error!!.contains("escapes workspace"))
        assertEquals(0, client.calls)
    }

    @Test
    fun absolutePath_isUsedAsGiven() = runBlocking {
        val outside = Files.createTempFile("outside-image", ".jpg").toFile()
        try {
            outside.writeBytes(byteArrayOf(9, 9, 9))
            val client = FakeClient(reply = "An outside image.")
            val res = tool(client).execute(buildJsonObject { put("path", outside.absolutePath) })
            assertTrue(res.ok)
            assertEquals("An outside image.", res.output)
        } finally {
            outside.delete()
        }
    }

    @Test
    fun emptyModelText_returnsClearError() = runBlocking {
        val client = FakeClient(reply = "   ")
        imageFile()
        val res = tool(client).execute(buildJsonObject { put("path", "photo.jpg") })
        assertFalse(res.ok)
        assertTrue(res.error!!.contains("no description"))
    }

    @Test
    fun unconfigured_returnsClearErrorAndNeverCallsModel() = runBlocking {
        imageFile()
        val client = FakeClient()
        val tool = ReadImageTool(
            client = client,
            model = "test-vision-model",
            workspaceDir = workspaceDir,
            prepareImage = { "fake-jpeg-bytes".toByteArray(Charsets.UTF_8) },
            configured = false,
        )
        val res = tool.execute(buildJsonObject { put("path", "photo.jpg") })
        assertFalse(res.ok)
        assertTrue(res.error!!.contains("No model configured"))
        assertEquals(0, client.calls)
    }

    @Test
    fun missingPath_returnsClearError() = runBlocking {
        val client = FakeClient()
        val res = tool(client).execute(buildJsonObject { put("prompt", "describe this") })
        assertFalse(res.ok)
        assertTrue(res.error!!.contains("Missing required argument: path"))
        assertEquals(0, client.calls)
    }

    @Test
    fun isVisionUnsupported_matchesProviderRejectionsOnly() {
        assertTrue(
            ReadImageTool.isVisionUnsupported(
                CodexError.Unknown("The provider rejected the image or PDF (HTTP 400)."),
            ),
        )
        assertTrue(
            ReadImageTool.isVisionUnsupported(
                CodexError.Unknown("This model does not support vision."),
            ),
        )
        assertFalse(ReadImageTool.isVisionUnsupported(CodexError.Network()))
        assertFalse(ReadImageTool.isVisionUnsupported(CodexError.Auth("bad key")))
        assertFalse(ReadImageTool.isVisionUnsupported(CodexError.Timeout()))
    }
}
