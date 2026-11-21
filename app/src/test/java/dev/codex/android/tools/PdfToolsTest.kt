package dev.codex.android.tools

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the PDF tools in PdfTools.kt. Fixtures are built with
 * PdfBox directly; results are checked by re-loading with PdfBox and,
 * for text, with [ReadPdfTool].
 */
class PdfToolsTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("codex-pdf-tools-test").toFile()
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
                is Boolean -> put(key, value)
                else -> throw IllegalArgumentException("Unsupported arg type: $value")
            }
        }
    }

    private fun argsWithInputs(inputs: List<String>, outputPath: String) = buildJsonObject {
        putJsonArray("inputs") { inputs.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        put("output_path", outputPath)
    }

    private fun writePdf(name: String, pageTexts: List<String>): File {
        val file = File(tempDir, name)
        PDDocument().use { doc ->
            for (text in pageTexts) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(54f, 750f)
                    stream.showText(text)
                    stream.endText()
                }
            }
            doc.save(file)
        }
        return file
    }

    private fun pageCount(file: File): Int =
        PDDocument.load(file).use { it.numberOfPages }

    private fun readPdf(path: String) = runBlocking {
        ReadPdfTool(tempDir).execute(args("path" to path, "pages" to "all"))
    }

    @Test
    fun pdfCreate_textAndTitleRoundTrip() = runBlocking {
        val out = File(tempDir, "made.pdf")
        val result = PdfCreateTool(tempDir).execute(
            args(
                "output_path" to out.absolutePath,
                "title" to "My Notes",
                "content" to "# Heading One\nFirst paragraph of text.\nSecond paragraph here.",
            ),
        )
        assertTrue(result.ok)
        assertTrue(out.isFile)
        assertEquals(1, pageCount(out))
        PDDocument.load(out).use { doc ->
            assertEquals("My Notes", doc.documentInformation.title)
        }
        val read = readPdf(out.absolutePath)
        assertTrue(read.ok)
        assertTrue(read.output.contains("Heading One"))
        assertTrue(read.output.contains("First paragraph of text."))
    }

    @Test
    fun pdfCreate_rejectsNonPdfOutput() = runBlocking {
        val result = PdfCreateTool(tempDir).execute(
            args("output_path" to File(tempDir, "x.txt").absolutePath, "content" to "hi"),
        )
        assertFalse(result.ok)
    }

    @Test
    fun pdfMerge_twoFilesInOrder() = runBlocking {
        val a = writePdf("a.pdf", listOf("Alpha one", "Alpha two"))
        val b = writePdf("b.pdf", listOf("Beta one"))
        val out = File(tempDir, "merged.pdf")
        val result = PdfMergeTool(tempDir).execute(argsWithInputs(listOf(a.absolutePath, b.absolutePath), out.absolutePath))
        assertTrue(result.ok)
        assertEquals(3, pageCount(out))
        val read = readPdf(out.absolutePath)
        assertTrue(read.output.contains("Alpha one"))
        assertTrue(read.output.contains("Beta one"))
        assertTrue(read.output.indexOf("Alpha two") < read.output.indexOf("Beta one"))
    }

    @Test
    fun pdfMerge_needsTwoInputs() = runBlocking {
        val a = writePdf("a.pdf", listOf("Alpha one"))
        assertFalse(
            PdfMergeTool(tempDir).execute(argsWithInputs(listOf(a.absolutePath), File(tempDir, "m.pdf").absolutePath)).ok,
        )
    }

    @Test
    fun pdfSplit_rangeAndPerPage() = runBlocking {
        val src = writePdf("src.pdf", listOf("Page one", "Page two", "Page three"))
        val out = File(tempDir, "part.pdf")
        val range = PdfSplitTool(tempDir).execute(
            args("path" to src.absolutePath, "pages" to "2-3", "output_path" to out.absolutePath),
        )
        assertTrue(range.ok)
        assertEquals(2, pageCount(out))
        val read = readPdf(out.absolutePath)
        assertTrue(read.output.contains("Page two"))
        assertTrue(read.output.contains("Page three"))
        assertFalse(read.output.contains("Page one"))

        val dir = File(tempDir, "pages").apply { mkdirs() }
        val perPage = PdfSplitTool(tempDir).execute(
            args("path" to src.absolutePath, "pages" to "all", "per_page" to true, "output_dir" to dir.absolutePath),
        )
        assertTrue(perPage.ok)
        assertEquals(3, dir.listFiles()?.size)
        for (n in 1..3) {
            assertTrue(File(dir, "src-p$n.pdf").isFile)
            assertEquals(1, pageCount(File(dir, "src-p$n.pdf")))
        }
    }

    @Test
    fun pdfRotate_onlyRequestedPages() = runBlocking {
        val src = writePdf("rot.pdf", listOf("One", "Two", "Three"))
        val out = File(tempDir, "rotated.pdf")
        val result = PdfRotateTool(tempDir).execute(
            args("path" to src.absolutePath, "pages" to "2", "degrees" to 90, "output_path" to out.absolutePath),
        )
        assertTrue(result.ok)
        PDDocument.load(out).use { doc ->
            assertEquals(0, doc.getPage(0).rotation)
            assertEquals(90, doc.getPage(1).rotation)
            assertEquals(0, doc.getPage(2).rotation)
        }
        // Input file is untouched.
        PDDocument.load(src).use { doc ->
            assertEquals(0, doc.getPage(1).rotation)
        }
        assertFalse(
            PdfRotateTool(tempDir).execute(
                args("path" to src.absolutePath, "degrees" to 45, "output_path" to File(tempDir, "x.pdf").absolutePath),
            ).ok,
        )
    }

    private fun writeFormPdf(name: String): File {
        val file = File(tempDir, name)
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val acroForm = PDAcroForm(doc)
            doc.documentCatalog.acroForm = acroForm
            val dr = PDResources()
            dr.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
            acroForm.defaultResources = dr
            val nameField = PDTextField(acroForm)
            nameField.partialName = "full_name"
            nameField.defaultValue = ""
            nameField.defaultAppearance = "/Helv 12 Tf 0 g"
            nameField.setValue("Ada Lovelace")
            val cityField = PDTextField(acroForm)
            cityField.partialName = "city"
            cityField.defaultAppearance = "/Helv 12 Tf 0 g"
            cityField.setValue("London")
            acroForm.fields = listOf(nameField, cityField)
            doc.save(file)
        }
        return file
    }

    @Test
    fun pdfForms_listGetSet() = runBlocking {
        val src = writeFormPdf("form.pdf")
        val list = PdfFormsTool(tempDir).execute(args("path" to src.absolutePath, "operation" to "list"))
        assertTrue(list.ok)
        assertTrue(list.output.contains("full_name"))
        assertTrue(list.output.contains("Ada Lovelace"))
        assertTrue(list.output.contains("city"))

        val get = PdfFormsTool(tempDir).execute(args("path" to src.absolutePath, "operation" to "get", "field" to "city"))
        assertTrue(get.ok)
        assertTrue(get.output.contains("London"))

        val missing = PdfFormsTool(tempDir).execute(args("path" to src.absolutePath, "operation" to "get", "field" to "nope"))
        assertFalse(missing.ok)

        val filled = File(tempDir, "filled.pdf")
        val set = PdfFormsTool(tempDir).execute(
            buildJsonObject {
                put("path", src.absolutePath)
                put("operation", "set")
                put("output_path", filled.absolutePath)
                putJsonObject("fields") {
                    put("full_name", "Grace Hopper")
                }
            },
        )
        assertTrue(set.ok)
        val check = PdfFormsTool(tempDir).execute(args("path" to filled.absolutePath, "operation" to "get", "field" to "full_name"))
        assertTrue(check.output.contains("Grace Hopper"))
        // Original unchanged.
        val original = PdfFormsTool(tempDir).execute(args("path" to src.absolutePath, "operation" to "get", "field" to "full_name"))
        assertTrue(original.output.contains("Ada Lovelace"))
    }

    @Test
    fun pdfForms_noFormFailsPlainly() = runBlocking {
        val src = writePdf("plain.pdf", listOf("No form here"))
        val result = PdfFormsTool(tempDir).execute(args("path" to src.absolutePath, "operation" to "list"))
        assertFalse(result.ok)
        assertTrue(result.error?.contains("no form") == true)
    }
}
