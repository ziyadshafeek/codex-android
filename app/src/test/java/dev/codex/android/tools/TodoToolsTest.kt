package dev.codex.android.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** JVM unit tests for [TodoWriteTool], [TodoReadTool], and [TodoStore]. */
class TodoToolsTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("todo-tools-test-").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun writeAndRead_persistsPerThreadJsonFileAndFormatsPlainText() = runBlocking {
        var currentThread = "thr_alpha"
        val store = TodoStore(tempDir)
        val writeTool = TodoWriteTool(store) { currentThread }
        val readTool = TodoReadTool(store) { currentThread }

        assertFalse(writeTool.sensitive)
        assertFalse(readTool.sensitive)
        assertEquals("todowrite", writeTool.name)
        assertEquals("todoread", readTool.name)

        // Initially empty.
        assertEquals("No todos.", readTool.execute(buildJsonObject {}).output)

        val writeArgs = buildJsonObject {
            putJsonArray("todos") {
                add(
                    buildJsonObject {
                        put("content", "Inspect models.dev schema")
                        put("status", "completed")
                    },
                )
                add(
                    buildJsonObject {
                        put("content", "Implement ModelsCatalog")
                        put("status", "in_progress")
                    },
                )
                add(
                    buildJsonObject {
                        put("content", "Run JVM unit tests")
                        put("status", "pending")
                    },
                )
            }
        }
        val writeResult = writeTool.execute(writeArgs)
        assertTrue(writeResult.ok)
        val expectedText = """
            1. [completed] Inspect models.dev schema
            2. [in_progress] Implement ModelsCatalog
            3. [pending] Run JVM unit tests
        """.trimIndent()
        assertEquals(expectedText, writeResult.output)
        assertEquals(expectedText, readTool.execute(buildJsonObject {}).output)

        // Separate thread has its own isolated list.
        currentThread = "thr_beta"
        assertEquals("No todos.", readTool.execute(buildJsonObject {}).output)

        // Fresh TodoStore reading from the same directory reloads persisted JSON file.
        val reloadedStore = TodoStore(tempDir)
        val reloadedRead = TodoReadTool(reloadedStore) { "thr_alpha" }
        assertEquals(expectedText, reloadedRead.execute(buildJsonObject {}).output)
        assertTrue(File(tempDir, "todos_thr_alpha.json").isFile)
    }

    @Test
    fun write_rejectsInvalidStatusOrBlankContent() = runBlocking {
        val store = TodoStore(tempDir)
        val writeTool = TodoWriteTool(store)

        val badStatus = buildJsonObject {
            putJsonArray("todos") {
                add(
                    buildJsonObject {
                        put("content", "Task 1")
                        put("status", "done_ish")
                    },
                )
            }
        }
        val badResult = writeTool.execute(badStatus)
        assertFalse(badResult.ok)
        assertTrue(badResult.error!!.contains("Invalid status"))

        val blankContent = buildJsonObject {
            putJsonArray("todos") {
                add(
                    buildJsonObject {
                        put("content", "   ")
                        put("status", "pending")
                    },
                )
            }
        }
        assertFalse(writeTool.execute(blankContent).ok)
    }
}
