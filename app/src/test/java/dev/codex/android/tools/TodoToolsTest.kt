package dev.codex.android.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
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

    @Test
    fun sanitizeThreadId_edges() {
        assertEquals(TodoStore.DEFAULT_THREAD_ID, TodoStore.sanitizeThreadId(""))
        assertEquals(TodoStore.DEFAULT_THREAD_ID, TodoStore.sanitizeThreadId("   "))
        // Path traversal collapses to underscores, then to the default.
        assertEquals(TodoStore.DEFAULT_THREAD_ID, TodoStore.sanitizeThreadId("../.."))
        assertEquals(TodoStore.DEFAULT_THREAD_ID, TodoStore.sanitizeThreadId("///"))
        assertEquals("a_b", TodoStore.sanitizeThreadId("a/b"))
        assertEquals("thr", TodoStore.sanitizeThreadId("thr αβ"))
        assertEquals("a--b__c", TodoStore.sanitizeThreadId("a--b__c"))
        assertEquals("spaced", TodoStore.sanitizeThreadId("  spaced  "))
        assertEquals("UPPER_lower-1", TodoStore.sanitizeThreadId("UPPER_lower-1"))
    }

    @Test
    fun write_emptyList_clearsToNoTodos() = runBlocking {
        val store = TodoStore(tempDir)
        val writeTool = TodoWriteTool(store) { "thr_alpha" }
        val readTool = TodoReadTool(store) { "thr_alpha" }

        val writeArgs = buildJsonObject {
            putJsonArray("todos") {
                add(buildJsonObject { put("content", "One task"); put("status", "pending") })
            }
        }
        assertTrue(writeTool.execute(writeArgs).ok)

        val clearArgs = buildJsonObject { putJsonArray("todos") {} }
        val cleared = writeTool.execute(clearArgs)
        assertTrue(cleared.ok)
        assertEquals("No todos.", cleared.output)
        assertEquals("No todos.", readTool.execute(buildJsonObject {}).output)
    }

    @Test
    fun read_corruptJsonFile_returnsEmptyNotCrash() = runBlocking {
        File(tempDir, "todos_thr_alpha.json").writeText("{corrupt json", Charsets.UTF_8)
        val store = TodoStore(tempDir)
        val readTool = TodoReadTool(store) { "thr_alpha" }
        assertEquals("No todos.", readTool.execute(buildJsonObject {}).output)
    }

    @Test
    fun write_rejectsMissingTodosAndNonObjectItems() = runBlocking {
        val store = TodoStore(tempDir)
        val writeTool = TodoWriteTool(store)

        val missing = buildJsonObject { put("other", "field") }
        val missingResult = writeTool.execute(missing)
        assertFalse(missingResult.ok)
        assertTrue(missingResult.error!!.contains("Missing required argument: todos"))

        val nonObject = buildJsonObject {
            putJsonArray("todos") { add(JsonPrimitive("not-an-object")) }
        }
        val nonObjectResult = writeTool.execute(nonObject)
        assertFalse(nonObjectResult.ok)
        assertTrue(nonObjectResult.error!!.contains("must be a JSON object"))
    }

    @Test
    fun concurrentWrites_differentThreadsStayIsolated() = runBlocking {
        val store = TodoStore(tempDir)
        val jobs = (0 until 10).map { n ->
            launch(Dispatchers.Default) {
                val writeTool = TodoWriteTool(store) { "thr_$n" }
                val args = buildJsonObject {
                    putJsonArray("todos") {
                        add(buildJsonObject { put("content", "Task $n"); put("status", "pending") })
                        add(buildJsonObject { put("content", "Task $n-b"); put("status", "completed") })
                    }
                }
                assertTrue(writeTool.execute(args).ok)
            }
        }
        jobs.forEach { it.join() }

        for (n in 0 until 10) {
            val readTool = TodoReadTool(store) { "thr_$n" }
            assertEquals(
                "1. [pending] Task $n\n2. [completed] Task $n-b",
                readTool.execute(buildJsonObject {}).output,
            )
        }
    }
}
