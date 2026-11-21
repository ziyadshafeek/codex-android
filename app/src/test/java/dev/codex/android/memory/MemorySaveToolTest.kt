package dev.codex.android.memory

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [MemorySaveTool]. The storage underneath
 * [PersonalMemory.remember] cannot run in a JVM unit test (no Robolectric
 * in this module), so the tool takes the remember function as a lambda
 * and the tests substitute a fake that mimics its contract: it returns
 * "Remembered: <key>." on success and a plain refusal otherwise.
 */
class MemorySaveToolTest {

    private fun args(vararg pairs: Pair<String, Any?>) = buildJsonObject {
        for ((key, value) in pairs) {
            when (value) {
                is String -> put(key, value)
                null -> Unit
                else -> throw IllegalArgumentException("Unsupported arg type: $value")
            }
        }
    }

    private fun fakeRemember(): Pair<MemorySaveTool, MutableList<Pair<String?, String>>> {
        val saved = mutableListOf<Pair<String?, String>>()
        val tool = MemorySaveTool { key, note ->
            saved += key to note
            "Remembered: ${key ?: "note"}."
        }
        return tool to saved
    }

    @Test
    fun savesNoteWithExplicitKey() = runBlocking {
        val (tool, saved) = fakeRemember()
        val result = tool.execute(args("note" to "I prefer Kotlin", "key" to "preference-language"))
        assertTrue(result.ok)
        assertTrue(result.output.contains("Remembered: preference-language."))
        assertEquals(listOf("preference-language" to "I prefer Kotlin"), saved)
    }

    @Test
    fun savesNoteWithoutKey() = runBlocking {
        val (tool, saved) = fakeRemember()
        val result = tool.execute(args("note" to "The office printer is on the third floor"))
        assertTrue(result.ok)
        assertEquals(listOf(null to "The office printer is on the third floor"), saved)
    }

    @Test
    fun blankNoteFailsWithoutSaving() = runBlocking {
        val (tool, saved) = fakeRemember()
        assertFalse(tool.execute(args("note" to "   ")).ok)
        assertFalse(tool.execute(buildJsonObject {}).ok)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun overlongNoteFailsWithoutSaving() = runBlocking {
        val (tool, saved) = fakeRemember()
        val result = tool.execute(args("note" to "x".repeat(MAX_NOTE_LENGTH + 1)))
        assertFalse(result.ok)
        assertTrue(result.error?.contains("400") == true)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun refusalFromRememberSurfacesAsFailure() = runBlocking {
        val tool = MemorySaveTool { _, _ -> "That looks like a credential. It was not stored." }
        val result = tool.execute(args("note" to "my api key is xyz"))
        assertFalse(result.ok)
        assertTrue(result.error?.contains("credential") == true)
    }
}
