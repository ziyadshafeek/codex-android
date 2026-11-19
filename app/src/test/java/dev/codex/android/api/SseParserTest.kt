package dev.codex.android.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [SseParser.parseDataLine].
 *
 * The parser takes the payload *without* the `data:` prefix, so every
 * input below is a bare payload (or `[DONE]`). No network, no API key,
 * no model: all inputs are literal strings.
 */
class SseParserTest {

    @Test
    fun textDeltaLine_yieldsTextDelta() {
        val delta = SseParser.parseDataLine(
            """{"choices":[{"delta":{"content":"hi"}}]}""",
        )
        assertEquals("hi", delta.textDelta)
        assertFalse(delta.done)
        assertNull(delta.finishReason)
        assertTrue(delta.toolCallDeltas.isEmpty())
    }

    @Test
    fun doneLine_yieldsDone() {
        val delta = SseParser.parseDataLine("[DONE]")
        assertTrue(delta.done)
        assertNull(delta.textDelta)
        assertNull(delta.finishReason)
    }

    @Test
    fun doneLine_withSurroundingWhitespace_yieldsDone() {
        assertTrue(SseParser.parseDataLine("  [DONE]  ").done)
    }

    @Test
    fun emptyLine_yieldsEmptyDelta_neverThrows() {
        val delta = SseParser.parseDataLine("")
        assertEquals(StreamDelta(), delta)
    }

    @Test
    fun malformedLine_yieldsEmptyDelta_neverThrows() {
        val delta = SseParser.parseDataLine("this is not json {")
        assertEquals(StreamDelta(), delta)
    }

    @Test
    fun nonObjectLine_yieldsEmptyDelta_neverThrows() {
        assertEquals(StreamDelta(), SseParser.parseDataLine("null"))
        assertEquals(StreamDelta(), SseParser.parseDataLine("""[1, 2]"""))
    }

    @Test
    fun lineWithoutChoices_yieldsEmptyDelta() {
        assertEquals(StreamDelta(), SseParser.parseDataLine("""{"object":"chat.completion.chunk"}"""))
        assertEquals(StreamDelta(), SseParser.parseDataLine("""{"choices":[]}"""))
    }

    @Test
    fun toolCallDeltaLine_yieldsIndexNameAndArgumentsFragment() {
        val delta = SseParser.parseDataLine(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"echo","arguments":"{\"text\":"}}]}}]}""",
        )
        assertNull(delta.textDelta)
        assertFalse(delta.done)
        val toolCall = delta.toolCallDeltas[0]
        assertEquals("call_1", toolCall?.id)
        assertEquals("echo", toolCall?.name)
        assertEquals("{\"text\":", toolCall?.argumentsDelta)
    }

    @Test
    fun finishReasonLine_yieldsFinishReason() {
        val delta = SseParser.parseDataLine(
            """{"choices":[{"finish_reason":"stop","delta":{}}]}""",
        )
        assertEquals("stop", delta.finishReason)
        assertNull(delta.textDelta)
        assertFalse(delta.done)
    }

    @Test
    fun finishReasonWithoutDelta_yieldsFinishReason() {
        val delta = SseParser.parseDataLine(
            """{"choices":[{"finish_reason":"tool_calls"}]}""",
        )
        assertEquals("tool_calls", delta.finishReason)
        assertNull(delta.textDelta)
    }
}
