package dev.codex.android.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the document create → read-back round trip: the
 * real proof on the emulator, where Android's DocumentBuilderFactory
 * ALWAYS throws UnsupportedOperationException from setXIncludeAware —
 * even for false — because the platform parser has no XInclude support
 * and reports specification "Unknown" version "0.0" (dd-plist #36,
 * jOOX #149). The JVM parser accepts the call, so unit tests can never
 * catch that crash; only this test can. It also proves the workspace
 * path the agent is told to use (`/workspace/...`) and bare names
 * resolve to real files.
 *
 * Fixtures are built with the app's own writers; the workspace is a
 * throwaway dir under the test app's cache dir.
 */
@RunWith(AndroidJUnit4::class)
class DocumentRoundTripInstrumentedTest {

    private lateinit var workspace: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        workspace = File(context.cacheDir, "document-round-trip-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        workspace.deleteRecursively()
    }

    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        for ((key, value) in pairs) {
            when (value) {
                is String -> put(key, value)
                is Int -> put(key, value)
                else -> throw IllegalArgumentException("Unsupported arg type: $value")
            }
        }
    }

    @Test
    fun createPptx_thenReadBackViaWorkspacePath() = runBlocking {
        val created = DocumentCreateTool(workspace).execute(
            args(
                "format" to "pptx",
                "output_path" to "/workspace/Exam_Sheet_40_Topics.pptx",
                "content" to "Exam Sheet\n- Topic one\n- Topic two\n---\nSecond Slide\n- Topic three",
            ),
        )
        assertTrue("document_create should succeed: ${created.error}", created.ok)
        assertTrue(File(workspace, "Exam_Sheet_40_Topics.pptx").isFile)

        val read = ReadDocumentTool(workspace).execute(
            args("path" to "/workspace/Exam_Sheet_40_Topics.pptx"),
        )
        assertTrue("read_document via /workspace/... should succeed: ${read.error}", read.ok)
        assertTrue(read.output.contains("[Slide 1]"))
        assertTrue(read.output.contains("Topic one"))
        assertTrue(read.output.contains("[Slide 2]"))
        assertTrue(read.output.contains("Topic three"))

        val bare = ReadDocumentTool(workspace).execute(args("path" to "Exam_Sheet_40_Topics.pptx"))
        assertTrue("read_document via bare name should succeed: ${bare.error}", bare.ok)
        assertTrue(bare.output.contains("Topic one"))
    }

    @Test
    fun createDocx_thenReadBackViaWorkspacePath() = runBlocking {
        val created = DocumentCreateTool(workspace).execute(
            args(
                "format" to "docx",
                "output_path" to "/workspace/notes.docx",
                "content" to "# Heading\nBody text here.",
            ),
        )
        assertTrue("document_create should succeed: ${created.error}", created.ok)
        val read = ReadDocumentTool(workspace).execute(args("path" to "/workspace/notes.docx"))
        assertTrue("read_document should succeed: ${read.error}", read.ok)
        assertTrue(read.output.contains("Heading"))
        assertTrue(read.output.contains("Body text here."))
    }
}
