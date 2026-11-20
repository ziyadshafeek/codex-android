package dev.codex.android.attachments

import dev.codex.android.api.ChatMessage
import dev.codex.android.api.chatMessagesWire
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PromptAttachmentsTest {
    @Test fun rejectsBinaryAndUnboundedInput() {
        assertNull(decodeAttachmentText(byteArrayOf(0x41, 0x00)))
        assertNull(decodeAttachmentText(byteArrayOf(0xff.toByte())))
        assertEquals("hello\nworld", decodeAttachmentText("\uFEFFhello\nworld".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { readBounded("abcd".byteInputStream(), 3, "large") }
        assertArrayEquals("abc".toByteArray(), readBounded("abc".byteInputStream(), 3, "large"))
    }

    @Test fun encodesTextAndVisionWithoutLeakingInternalFields() {
        val image = "data:image/png;base64,YWJj"
        val wire = chatMessagesWire(listOf(ChatMessage("user", "plain"), ChatMessage("user", "Screenshot", imageUrls = listOf(image))))
        assertEquals("plain", wire[0].jsonObject.getValue("content").jsonPrimitive.content)
        val parts = wire[1].jsonObject.getValue("content").jsonArray
        assertEquals("Screenshot", parts[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(image, parts[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
        assertFalse(wire[1].jsonObject.containsKey("imageUrls"))
    }
}
