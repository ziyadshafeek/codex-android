package dev.codex.android.memory

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for `memory_save`: the real proof on the emulator.
 * Saves a note through the tool against the real [PersonalMemory]
 * (SQLite + SharedPreferences), then proves it persisted and is
 * findable by [MemorySearchTool] — the exact flow that failed on the
 * phone ("save that to memory" → "I don't have a memory-saving tool").
 * The model choosing to call the tool in chat is proven only by the
 * user's phone test; this proves the mechanism it calls.
 */
@RunWith(AndroidJUnit4::class)
class MemorySaveToolInstrumentedTest {

    private lateinit var memory: PersonalMemory

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        memory = PersonalMemory(context)
        memory.setRecallEnabled(true)
    }

    @After
    fun tearDown() {
        // Block body (not `= runBlocking { ... }`): JUnit requires @After
        // methods to return void, and runBlocking would return the Boolean
        // from memory.clear().
        runBlocking { memory.clear() }
    }

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject {
        for ((key, value) in pairs) put(key, value)
    }

    @Test
    fun saveThenSearchFindsIt() = runBlocking {
        val tool = MemorySaveTool(memory::remember)
        val saved = tool.execute(
            args("note" to "The garden test plant is Marigold", "key" to "garden-test"),
        )
        assertTrue("memory_save should succeed: ${saved.error}", saved.ok)
        assertTrue(saved.output.contains("Remembered: garden-test."))

        val listed = memory.list()
        assertTrue(listed.any { it.key == "garden-test" && it.value.contains("Marigold") })

        val search = MemorySearchTool(memory).execute(args("query" to "garden plant"))
        assertTrue("memory_search should find the saved note", search.ok)
        assertTrue(search.output.contains("Marigold"))
    }

    @Test
    fun saveWithoutKeyThenRecallFindsIt() = runBlocking {
        val tool = MemorySaveTool(memory::remember)
        val saved = tool.execute(args("note" to "I prefer dark mode"))
        assertTrue("memory_save should succeed: ${saved.error}", saved.ok)
        val recalled = memory.recall("which display mode do I prefer?")
        assertNotNull("recall should surface the saved preference", recalled)
        assertTrue(recalled!!.contains("dark mode"))
    }

    @Test
    fun forgetRemovesNoteSoSearchFindsNothing() = runBlocking {
        val saver = MemorySaveTool(memory::remember)
        val saved = saver.execute(
            args("note" to "The garden test plant is Basil", "key" to "garden-forget-test"),
        )
        assertTrue("memory_save should succeed: ${saved.error}", saved.ok)
        val searchBefore = MemorySearchTool(memory).execute(args("query" to "garden plant"))
        assertTrue(searchBefore.output.contains("Basil"))

        val forgetter = MemoryForgetTool(memory::forget)
        val forgotten = forgetter.execute(args("key" to "garden-forget-test"))
        assertTrue("memory_forget should succeed: ${forgotten.error}", forgotten.ok)
        assertTrue(memory.list().none { it.key == "garden-forget-test" })
        val searchAfter = MemorySearchTool(memory).execute(args("query" to "garden plant"))
        assertTrue(searchAfter.output.contains("No saved notes match"))
    }
}
