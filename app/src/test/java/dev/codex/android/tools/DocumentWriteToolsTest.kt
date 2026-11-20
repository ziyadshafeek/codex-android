package dev.codex.android.tools

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for [DocumentCreateTool] and [DocumentEditTool].
 * Every created file is read back with [ReadDocumentTool] (the
 * independent reader) and, for formulas, by inspecting the raw XML —
 * so a writer bug cannot hide behind its own output.
 */
class DocumentWriteToolsTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("codex-doc-write-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
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

    private fun readBack(path: String) = runBlocking {
        ReadDocumentTool.execute(args("path" to path))
    }

    private fun zipText(file: File, entry: String): String =
        ZipFile(file).use { zip ->
            zip.getInputStream(zip.getEntry(entry)).use { it.readBytes() }.toString(Charsets.UTF_8)
        }

    // --- docx ---

    @Test
    fun createDocx_headingsParagraphsAndTableRoundTrip() = runBlocking {
        val out = File(tempDir, "report.docx")
        val content = "# Monthly Report\n## Summary\nSales went well this month.\n\n| Item | Qty |\n| Apples | 5 |\n| Oranges | 3 |"
        val created = DocumentCreateTool.execute(
            args("format" to "docx", "output_path" to out.absolutePath, "title" to "Report", "content" to content),
        )
        assertTrue(created.ok)
        assertTrue(out.isFile && out.length() > 0)
        val read = readBack(out.absolutePath)
        assertTrue(read.ok)
        assertTrue(read.output.contains("Monthly Report"))
        assertTrue(read.output.contains("Summary"))
        assertTrue(read.output.contains("Sales went well this month."))
        assertTrue(read.output.contains("[Table]"))
        assertTrue(read.output.contains("Item | Qty"))
        assertTrue(read.output.contains("Apples | 5"))
    }

    @Test
    fun createDocx_wrongExtensionAndFormatFail() = runBlocking {
        assertFalse(
            DocumentCreateTool.execute(
                args("format" to "docx", "output_path" to File(tempDir, "x.pdf").absolutePath, "content" to "hi"),
            ).ok,
        )
        assertFalse(
            DocumentCreateTool.execute(
                args("format" to "odt", "output_path" to File(tempDir, "x.odt").absolutePath, "content" to "hi"),
            ).ok,
        )
    }

    @Test
    fun editDocx_appendAndReplace() = runBlocking {
        val out = File(tempDir, "notes.docx")
        assertTrue(
            DocumentCreateTool.execute(
                args("format" to "docx", "output_path" to out.absolutePath, "content" to "Hello world\nSecond line"),
            ).ok,
        )
        val appended = DocumentEditTool.execute(
            args("path" to out.absolutePath, "operation" to "append", "content" to "## Added\nAppended paragraph"),
        )
        assertTrue(appended.ok)
        var read = readBack(out.absolutePath)
        assertTrue(read.output.contains("Appended paragraph"))
        assertTrue(read.output.contains("Hello world"))

        val replaced = DocumentEditTool.execute(
            args("path" to out.absolutePath, "operation" to "replace", "find" to "Hello world", "replacement" to "Goodbye world"),
        )
        assertTrue(replaced.ok)
        assertTrue(replaced.output.contains("1 occurrence"))
        read = readBack(out.absolutePath)
        assertTrue(read.output.contains("Goodbye world"))
        assertFalse(read.output.contains("Hello world"))

        val missing = DocumentEditTool.execute(
            args("path" to out.absolutePath, "operation" to "replace", "find" to "no such phrase", "replacement" to "x"),
        )
        assertTrue(missing.ok)
        assertTrue(missing.output.contains("nothing changed"))
    }

    @Test
    fun editDocx_replaceFindsTextSplitAcrossRuns() = runBlocking {
        val out = File(tempDir, "split.docx")
        assertTrue(
            DocumentCreateTool.execute(
                args("format" to "docx", "output_path" to out.absolutePath, "content" to "Hello world"),
            ).ok,
        )
        // Split "Hello world" across two runs by hand, then replace must still find it.
        val entries = readPackage(out)!!
        val xml = String(entries["word/document.xml"]!!, Charsets.UTF_8)
        val split = xml.replace(
            "<w:t xml:space=\"preserve\">Hello world</w:t>",
            "<w:t xml:space=\"preserve\">Hello </w:t></w:r><w:r><w:t xml:space=\"preserve\">world</w:t>",
        )
        assertTrue(rewritePackage(out, mapOf("word/document.xml" to split.toByteArray(Charsets.UTF_8))))
        val replaced = DocumentEditTool.execute(
            args("path" to out.absolutePath, "operation" to "replace", "find" to "Hello world", "replacement" to "Hi there"),
        )
        assertTrue(replaced.ok)
        val read = readBack(out.absolutePath)
        assertTrue(read.output.contains("Hi there"))
    }

    // --- xlsx ---

    @Test
    fun createXlsx_csvAndFormulaRoundTrip() = runBlocking {
        val out = File(tempDir, "budget.xlsx")
        val content = "Item,Qty,Price\n\"Apples, red\",5,1.5\nOranges,3,2\nTotal,,=SUM(C2:C3)"
        val created = DocumentCreateTool.execute(
            args("format" to "xlsx", "output_path" to out.absolutePath, "content" to content),
        )
        assertTrue(created.ok)
        val read = readBack(out.absolutePath)
        assertTrue(read.ok)
        assertTrue(read.output.contains("[Sheet: Sheet1]"))
        assertTrue(read.output.contains("Item | Qty | Price"))
        assertTrue(read.output.contains("Apples, red | 5 | 1.5"))
        assertTrue(read.output.contains("Oranges | 3 | 2"))
        // Formula cells carry no cached value until Excel/LibreOffice
        // computes them, so the reader shows the row with an empty cell —
        // but the formula itself must be in the file.
        val sheetXml = zipText(out, "xl/worksheets/sheet1.xml")
        assertTrue(sheetXml.contains("<f>SUM(C2:C3)</f>"))
    }

    @Test
    fun editXlsx_appendRows() = runBlocking {
        val out = File(tempDir, "data.xlsx")
        assertTrue(
            DocumentCreateTool.execute(
                args("format" to "xlsx", "output_path" to out.absolutePath, "content" to "Name,Score\nAna,10"),
            ).ok,
        )
        val appended = DocumentEditTool.execute(
            args("path" to out.absolutePath, "operation" to "append", "content" to "Ben,20\nCara,30"),
        )
        assertTrue(appended.ok)
        val read = readBack(out.absolutePath)
        assertTrue(read.output.contains("Ana | 10"))
        assertTrue(read.output.contains("Ben | 20"))
        assertTrue(read.output.contains("Cara | 30"))
    }

    @Test
    fun parseCsv_quotedFieldsAndCommas() {
        val rows = parseCsv("a,\"b,c\",d\n\"e\"\"f\",g,")
        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b,c", "d"), rows[0])
        assertEquals(listOf("e\"f", "g", ""), rows[1])
    }

    // --- pptx ---

    @Test
    fun createPptx_slidesRoundTrip() = runBlocking {
        val out = File(tempDir, "deck.pptx")
        val content = "Opening\n- First point\n- Second point\n---\nClosing\n- Thanks"
        val created = DocumentCreateTool.execute(
            args("format" to "pptx", "output_path" to out.absolutePath, "content" to content),
        )
        assertTrue(created.ok)
        val read = readBack(out.absolutePath)
        assertTrue(read.ok)
        assertTrue(read.output.contains("[Slide 1]"))
        assertTrue(read.output.contains("Opening"))
        assertTrue(read.output.contains("First point"))
        assertTrue(read.output.contains("[Slide 2]"))
        assertTrue(read.output.contains("Closing"))
        assertTrue(read.output.contains("Thanks"))
    }

    @Test
    fun editPptx_appendSlide() = runBlocking {
        val out = File(tempDir, "deck2.pptx")
        assertTrue(
            DocumentCreateTool.execute(
                args("format" to "pptx", "output_path" to out.absolutePath, "content" to "One\n- a"),
            ).ok,
        )
        val appended = DocumentEditTool.execute(
            args("path" to out.absolutePath, "operation" to "append", "content" to "Two\n- b"),
        )
        assertTrue(appended.ok)
        val read = readBack(out.absolutePath)
        assertTrue(read.output.contains("[Slide 1]"))
        assertTrue(read.output.contains("[Slide 2]"))
        assertTrue(read.output.contains("Two"))
        assertTrue(read.output.contains("b"))
    }

    @Test
    fun edit_replaceOnXlsxFailsPlainly() = runBlocking {
        val out = File(tempDir, "data2.xlsx")
        assertTrue(
            DocumentCreateTool.execute(
                args("format" to "xlsx", "output_path" to out.absolutePath, "content" to "A,B\n1,2"),
            ).ok,
        )
        val result = DocumentEditTool.execute(
            args("path" to out.absolutePath, "operation" to "replace", "find" to "1", "replacement" to "9"),
        )
        assertFalse(result.ok)
    }
}
