package dev.codex.android.memory

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [MemoryForgetTool]. The storage underneath
 * [PersonalMemory.forget] cannot run in a JVM unit test (no Robolectric
 * in this module), so the tool takes the forget function as a lambda
 * and the tests substitute a fake.
 */
class MemoryForgetToolTest {

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject {
        for ((key, value) in pairs) put(key, value)
    }

    @Test
    fun forgetsExistingNote() = runBlocking {
        val forgotten = mutableListOf<String>()
        val tool = MemoryForgetTool { key -> forgotten += key; true }
        val result = tool.execute(args("key" to "garden-test"))
        assertTrue(result.ok)
        assertTrue(result.output.contains("Forgot the saved note."))
        assertTrue(forgotten == listOf("garden-test"))
    }

    @Test
    fun missingNoteFailsPlainly() = runBlocking {
        val tool = MemoryForgetTool { false }
        val result = tool.execute(args("key" to "no-such-note"))
        assertFalse(result.ok)
        assertTrue(result.error?.contains("Check its name") == true)
    }

    @Test
    fun blankKeyFailsWithoutForgetting() = runBlocking {
        val forgotten = mutableListOf<String>()
        val tool = MemoryForgetTool { key -> forgotten += key; true }
        assertFalse(tool.execute(args("key" to "   ")).ok)
        assertFalse(tool.execute(buildJsonObject {}).ok)
        assertTrue(forgotten.isEmpty())
    }
}
