package dev.codex.android.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Proves the vision path: an attached image's bytes genuinely reach
 * the chat-completions request.
 *
 * The proof runs through [chatMessagesWire] — the exact function
 * `OkHttpOpenAiClient.buildRequestJson` calls to serialise messages
 * into the request body — with a real 1x1 PNG's bytes. It asserts
 * the wire payload carries an `image_url` part whose base64 decodes
 * byte-for-byte to the original PNG (not just a filename or a
 * claim), and that the internal `imageUrls` field never leaks onto
 * the wire. (buildRequestJson itself is private, so this tests the
 * function it delegates to; PromptAttachmentsTest covers the same
 * wire shape from the attachment side.)
 */
class VisionWireTest {

    /** A real 1x1 transparent PNG (67 bytes), not a placeholder. */
    private val pngBytes = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
        0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
        0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
        0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
        0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )

    @Test
    fun attachedImageBytes_reachTheRequestAsImageUrlPart() {
        val dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes)
        val wire = chatMessagesWire(
            listOf(ChatMessage("user", "What is in this picture?", imageUrls = listOf(dataUrl))),
        )
        val message = wire[0].jsonObject
        // Content must be a parts array (vision shape), not a plain string.
        val parts = message.getValue("content").jsonArray
        assertEquals("text", parts[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("What is in this picture?", parts[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("image_url", parts[1].jsonObject.getValue("type").jsonPrimitive.content)
        val url = parts[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
        // The URL is the image itself, byte-identical to the attachment.
        assertTrue(url.startsWith("data:image/png;base64,"))
        assertArrayEquals(pngBytes, Base64.getDecoder().decode(url.removePrefix("data:image/png;base64,")))
        // Internal field never leaks onto the wire.
        assertFalse(message.containsKey("imageUrls"))
        // And the serialised JSON the HTTP client actually sends has it.
        assertTrue(Json.encodeToString(JsonArray.serializer(), wire).contains("data:image/png;base64,"))
    }

    @Test
    fun textOnlyMessage_staysAPlainString() {
        val wire = chatMessagesWire(listOf(ChatMessage("user", "plain")))
        assertEquals("plain", wire[0].jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun pdfPreviewPage_reachesTheRequestAsJpegImageUrl() {
        // AttachmentReader renders PDF pages as JPEG data URLs; the wire
        // must carry those exactly like a photo.
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
        val dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)
        val wire = chatMessagesWire(
            listOf(ChatMessage("user", "Attached file: notes.pdf.", imageUrls = listOf(dataUrl))),
        )
        val parts = wire[0].jsonObject.getValue("content").jsonArray
        val url = parts[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
        assertArrayEquals(jpeg, Base64.getDecoder().decode(url.removePrefix("data:image/jpeg;base64,")))
    }
}
