package dev.codex.android.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM unit tests for [scaledDimensions], [buildVisionRequestJson], and the
 * file-validation error paths of [ReadImageTool].
 *
 * No Android framework classes are exercised here: [scaledDimensions] and
 * [buildVisionRequestJson] are pure, and the [ReadImageTool] error paths
 * under test return before any bitmap decoding or network call. The tool is
 * built with no context — an Android `Context` cannot be instantiated on
 * the JVM at all (its stub constructor throws), and [ReadImageTool] takes
 * a nullable context for exactly that reason; the error paths under test
 * never reach the settings read. The happy path through Bitmap decoding
 * and a real model call is verified by CI and the phone test, not here.
 */
class ImageToolsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("image-tools-test-").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun tool(): ReadImageTool = ReadImageTool()

    // scaledDimensions

    @Test
    fun scaledDimensions_landscapeDownscalesKeepingAspectRatio() {
        assertEquals(1568 to 1176, scaledDimensions(4000, 3000, 1568))
    }

    @Test
    fun scaledDimensions_portraitDownscalesKeepingAspectRatio() {
        assertEquals(1176 to 1568, scaledDimensions(3000, 4000, 1568))
    }

    @Test
    fun scaledDimensions_squareDownscalesToMaxSide() {
        assertEquals(1568 to 1568, scaledDimensions(2000, 2000, 1568))
    }

    @Test
    fun scaledDimensions_alreadySmallIsNotUpscaled() {
        assertEquals(800 to 600, scaledDimensions(800, 600, 1568))
        assertEquals(100 to 100, scaledDimensions(100, 100, 1568))
    }

    @Test
    fun scaledDimensions_exactBoundaryIsUnchanged() {
        assertEquals(1568 to 1000, scaledDimensions(1568, 1000, 1568))
        assertEquals(1568 to 1568, scaledDimensions(1568, 1568, 1568))
    }

    @Test
    fun scaledDimensions_justOverBoundaryDownscales() {
        assertEquals(1568 to 999, scaledDimensions(1569, 1000, 1568))
    }

    @Test
    fun scaledDimensions_thinImageKeepsAtLeastOnePixel() {
        assertEquals(1 to 1568, scaledDimensions(1, 2000, 1568))
        assertEquals(1568 to 1, scaledDimensions(2000, 1, 1568))
    }

    // buildVisionRequestJson

    @Test
    fun buildVisionRequestJson_containsImageUrlDataUriPromptModelAndNoStream() {
        val parsed = json.parseToJsonElement(
            buildVisionRequestJson("gpt-4o-mini", "What is in this photo?", "QUJD", "image/jpeg"),
        ).jsonObject

        assertEquals("gpt-4o-mini", parsed["model"]?.jsonPrimitive?.content)
        assertEquals("false", parsed["stream"]?.jsonPrimitive?.content)

        val messages = parsed["messages"]?.jsonArray
        assertEquals(1, messages?.size)
        val message = messages?.get(0)?.jsonObject
        assertEquals("user", message?.get("role")?.jsonPrimitive?.content)

        val content = message?.get("content")?.jsonArray
        assertEquals(2, content?.size)

        val textPart = content?.get(0)?.jsonObject
        assertEquals("text", textPart?.get("type")?.jsonPrimitive?.content)
        assertEquals("What is in this photo?", textPart?.get("text")?.jsonPrimitive?.content)

        val imagePart = content?.get(1)?.jsonObject
        assertEquals("image_url", imagePart?.get("type")?.jsonPrimitive?.content)
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            imagePart?.get("image_url")?.jsonObject?.get("url")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun buildVisionRequestJson_passesThroughMimeType() {
        val parsed = json.parseToJsonElement(
            buildVisionRequestJson("some-model", "Describe it.", "AAAA", "image/png"),
        ).jsonObject
        val content = parsed["messages"]?.jsonArray
            ?.get(0)?.jsonObject?.get("content")?.jsonArray
        assertEquals(
            "data:image/png;base64,AAAA",
            content?.get(1)?.jsonObject?.get("image_url")?.jsonObject
                ?.get("url")?.jsonPrimitive?.content,
        )
    }

    // ReadImageTool error paths (no bitmap decoding, no network)

    @Test
    fun readImage_missingFileReturnsError() = runBlocking {
        val missing = File(tempDir, "no-such-image.jpg").absolutePath
        val result = tool().execute(buildJsonObject { put("path", missing) })

        assertFalse(result.ok)
        assertEquals("", result.output)
        assertTrue(result.error?.contains("not found", ignoreCase = true) == true)
    }

    @Test
    fun readImage_oversizedFileReturnsError() = runBlocking {
        val big = File(tempDir, "big.jpg")
        Files.write(big.toPath(), ByteArray(MAX_IMAGE_BYTES.toInt() + 1))

        val result = tool().execute(buildJsonObject { put("path", big.absolutePath) })

        assertFalse(result.ok)
        assertEquals("", result.output)
        assertTrue(result.error?.contains("10 MB") == true)
    }

    @Test
    fun readImage_missingPathArgumentReturnsError() = runBlocking {
        val result = tool().execute(buildJsonObject {})

        assertFalse(result.ok)
        assertEquals("", result.output)
        assertTrue(result.error?.contains("path") == true)
    }

    @Test
    fun readImage_directoryPathReturnsError() = runBlocking {
        val result = tool().execute(buildJsonObject { put("path", tempDir.absolutePath) })

        assertFalse(result.ok)
        assertEquals("", result.output)
        assertTrue(result.error?.contains("not a file", ignoreCase = true) == true)
    }

    @Test
    fun readImage_toolMetadata() {
        val readImage = tool()
        assertEquals("read_image", readImage.name)
        assertFalse(readImage.sensitive)
        val required = readImage.parametersSchema["required"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
        assertTrue(required?.contains("path") == true)
    }
}
