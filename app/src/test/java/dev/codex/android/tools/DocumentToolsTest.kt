package dev.codex.android.tools

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
 * JVM tests for [ReadPdfTool], [ReadDocumentTool] and [ReadTextFileTool].
 * Fixtures are built by hand (PdfBox for PDFs, raw ZIP+XML for Office
 * files) so the readers are tested against independent input, not
 * against the writers in DocumentWriteTools.kt.
 */
class DocumentToolsTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("codex-doc-tools-test").toFile()
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

    private fun writeZip(name: String, entries: List<Pair<String, String>>): File {
        val file = File(tempDir, name)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((entryName, content) in entries) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    // --- read_pdf ---

    @Test
    fun readPdf_allPagesLabelledWithPageNumbers() = runBlocking {
        val file = writePdf("three.pdf", listOf("Alpha page one", "Beta page two", "Gamma page three"))
        val result = ReadPdfTool.execute(args("path" to file.absolutePath, "pages" to "all"))
        assertTrue(result.ok)
        assertTrue(result.output.contains("Pages: 3"))
        assertTrue(result.output.contains("[p. 1]"))
        assertTrue(result.output.contains("Alpha page one"))
        assertTrue(result.output.contains("[p. 2]"))
        assertTrue(result.output.contains("Beta page two"))
        assertTrue(result.output.contains("[p. 3]"))
        assertTrue(result.output.contains("Gamma page three"))
    }

    @Test
    fun readPdf_singlePageAndRange() = runBlocking {
        val file = writePdf("three.pdf", listOf("Alpha page one", "Beta page two", "Gamma page three"))
        val one = ReadPdfTool.execute(args("path" to file.absolutePath, "pages" to "2"))
        assertTrue(one.ok)
        assertTrue(one.output.contains("Beta page two"))
        assertFalse(one.output.contains("Alpha page one"))
        assertFalse(one.output.contains("Gamma page three"))
        val range = ReadPdfTool.execute(args("path" to file.absolutePath, "pages" to "1-2"))
        assertTrue(range.ok)
        assertTrue(range.output.contains("Alpha page one"))
        assertTrue(range.output.contains("Beta page two"))
        assertFalse(range.output.contains("Gamma page three"))
        val list = ReadPdfTool.execute(args("path" to file.absolutePath, "pages" to "1,3"))
        assertTrue(list.ok)
        assertTrue(list.output.contains("Alpha page one"))
        assertTrue(list.output.contains("Gamma page three"))
        assertFalse(list.output.contains("Beta page two"))
    }

    @Test
    fun readPdf_outOfRangeAndMalformedPagesFail() = runBlocking {
        val file = writePdf("two.pdf", listOf("One", "Two"))
        assertFalse(ReadPdfTool.execute(args("path" to file.absolutePath, "pages" to "99")).ok)
        assertFalse(ReadPdfTool.execute(args("path" to file.absolutePath, "pages" to "3-1")).ok)
        assertFalse(ReadPdfTool.execute(args("path" to file.absolutePath, "pages" to "abc")).ok)
        assertFalse(ReadPdfTool.execute(args("path" to file.absolutePath, "pages" to "0")).ok)
    }

    @Test
    fun readPdf_missingFileAndMissingArgFail() = runBlocking {
        assertFalse(ReadPdfTool.execute(args("path" to File(tempDir, "nope.pdf").absolutePath)).ok)
        assertFalse(ReadPdfTool.execute(buildJsonObject { }).ok)
    }

    @Test
    fun readPdf_scannedBlankPdfFailsWithoutInventingText() = runBlocking {
        val file = File(tempDir, "blank.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            doc.save(file)
        }
        val result = ReadPdfTool.execute(args("path" to file.absolutePath))
        assertFalse(result.ok)
        assertTrue(result.error?.contains("scanned") == true)
    }

    @Test
    fun readPdf_encryptedPdfFailsPlainly() = runBlocking {
        val file = File(tempDir, "locked.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 12f)
                stream.newLineAtOffset(54f, 750f)
                stream.showText("Secret text")
                stream.endText()
            }
            doc.protect(StandardProtectionPolicy("owner-pass", "user-pass", AccessPermission()))
            doc.save(file)
        }
        val result = ReadPdfTool.execute(args("path" to file.absolutePath))
        assertFalse(result.ok)
        assertTrue(result.error?.contains("encrypted") == true)
    }

    @Test
    fun parsePageSelection_legacyPageArg() {
        val args = args("page" to 2)
        assertEquals(listOf(2), parsePageSelection(args, 5))
        assertEquals(listOf(1, 2, 3, 4, 5), parsePageSelection(args("page" to 0), 5))
        assertEquals(null, parsePageSelection(args("page" to 9), 5))
    }

    // --- read_document: docx ---

    private fun docxFixture(): File = writeZip(
        "test.docx",
        listOf(
            "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
            "word/document.xml" to
                "<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:body>" +
                    "<w:p><w:r><w:t>First paragraph</w:t></w:r></w:p>" +
                    "<w:p><w:r><w:t>Second</w:t></w:r><w:r><w:t> paragraph</w:t></w:r></w:p>" +
                    "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Name</w:t></w:r></w:p></w:tc>" +
                    "<w:tc><w:p><w:r><w:t>Age</w:t></w:r></w:p></w:tc></w:tr>" +
                    "<w:tr><w:tc><w:p><w:r><w:t>Ziyad</w:t></w:r></w:p></w:tc>" +
                    "<w:tc><w:p><w:r><w:t>24</w:t></w:r></w:p></w:tc></w:tr></w:tbl>" +
                    "</w:body></w:document>",
        ),
    )

    @Test
    fun readDocument_docxParagraphsAndTable() = runBlocking {
        val result = ReadDocumentTool.execute(args("path" to docxFixture().absolutePath))
        assertTrue(result.ok)
        assertTrue(result.output.contains("First paragraph"))
        assertTrue(result.output.contains("Second paragraph"))
        assertTrue(result.output.contains("[Table]"))
        assertTrue(result.output.contains("Name | Age"))
        assertTrue(result.output.contains("Ziyad | 24"))
    }

    // --- read_document: xlsx ---

    private fun xlsxFixture(): File = writeZip(
        "test.xlsx",
        listOf(
            "xl/sharedStrings.xml" to
                "<?xml version=\"1.0\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"3\" uniqueCount=\"3\">" +
                    "<si><t>Item</t></si><si><t>Apple</t></si><si><t>Banana</t></si></sst>",
            "xl/workbook.xml" to
                "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                    "<sheets><sheet name=\"Data\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to
                "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                    "</Relationships>",
            "xl/worksheets/sheet1.xml" to
                "<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                    "<sheetData>" +
                    "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>" +
                    "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c><c r=\"B2\"><v>5</v></c><c r=\"C2\" t=\"b\"><v>1</v></c></row>" +
                    "<row r=\"3\"><c r=\"A3\" t=\"inlineStr\"><is><t>Cherry</t></is></c><c r=\"B3\"><v>7.5</v></c></row>" +
                    "</sheetData></worksheet>",
        ),
    )

    @Test
    fun readDocument_xlsxSheetsAndCellTypes() = runBlocking {
        val result = ReadDocumentTool.execute(args("path" to xlsxFixture().absolutePath))
        assertTrue(result.ok)
        assertTrue(result.output.contains("[Sheet: Data]"))
        assertTrue(result.output.contains("Item | Apple"))
        assertTrue(result.output.contains("Apple | 5 | TRUE"))
        assertTrue(result.output.contains("Cherry | 7.5"))
    }

    // --- read_document: pptx ---

    private fun pptxFixture(): File = writeZip(
        "test.pptx",
        listOf(
            "ppt/slides/slide1.xml" to
                "<?xml version=\"1.0\"?><p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
                    "<p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>Opening slide</a:t></a:r></a:p>" +
                    "<a:p><a:r><a:t>First point</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>",
            "ppt/slides/slide2.xml" to
                "<?xml version=\"1.0\"?><p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
                    "<p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>Closing slide</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>",
        ),
    )

    @Test
    fun readDocument_pptxSlidesInOrder() = runBlocking {
        val result = ReadDocumentTool.execute(args("path" to pptxFixture().absolutePath))
        assertTrue(result.ok)
        assertTrue(result.output.contains("[Slide 1]"))
        assertTrue(result.output.contains("Opening slide"))
        assertTrue(result.output.contains("First point"))
        assertTrue(result.output.contains("[Slide 2]"))
        assertTrue(result.output.contains("Closing slide"))
        assertTrue(result.output.indexOf("[Slide 1]") < result.output.indexOf("[Slide 2]"))
    }

    // --- read_document: unsupported ---

    @Test
    fun readDocument_legacyAndEncryptedAndInvalidFail() = runBlocking {
        val legacy = File(tempDir, "old.doc").apply { writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(), 0, 0)) }
        val legacyResult = ReadDocumentTool.execute(args("path" to legacy.absolutePath))
        assertFalse(legacyResult.ok)
        assertTrue(legacyResult.error?.contains("not supported") == true)

        val fakeDocx = File(tempDir, "fake.docx").apply { writeText("this is not a zip") }
        val fakeResult = ReadDocumentTool.execute(args("path" to fakeDocx.absolutePath))
        assertFalse(fakeResult.ok)

        val oleDocx = File(tempDir, "ole.docx").apply {
            writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(), 0, 0, 0, 0))
        }
        val oleResult = ReadDocumentTool.execute(args("path" to oleDocx.absolutePath))
        assertFalse(oleResult.ok)
        assertTrue(oleResult.error?.contains("encrypted") == true)

        val txt = File(tempDir, "notes.txt").apply { writeText("hello") }
        assertFalse(ReadDocumentTool.execute(args("path" to txt.absolutePath)).ok)
    }

    // --- read_text_file ---

    @Test
    fun readTextFile_utf8Utf16AndBinary() = runBlocking {
        val utf8 = File(tempDir, "a.txt").apply { writeText("Hello\nWorld", Charsets.UTF_8) }
        val utf8Result = ReadTextFileTool.execute(args("path" to utf8.absolutePath))
        assertTrue(utf8Result.ok)
        assertTrue(utf8Result.output.contains("Hello\nWorld"))

        val utf16le = File(tempDir, "b.txt").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Hello UTF-16LE".toByteArray(Charsets.UTF_16LE))
        }
        val leResult = ReadTextFileTool.execute(args("path" to utf16le.absolutePath))
        assertTrue(leResult.ok)
        assertTrue(leResult.output.contains("Hello UTF-16LE"))

        val utf16be = File(tempDir, "c.txt").apply {
            writeBytes(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Hello UTF-16BE".toByteArray(Charsets.UTF_16BE))
        }
        val beResult = ReadTextFileTool.execute(args("path" to utf16be.absolutePath))
        assertTrue(beResult.ok)
        assertTrue(beResult.output.contains("Hello UTF-16BE"))

        val binary = File(tempDir, "d.bin").apply {
            writeBytes("text\u0000binary".toByteArray(Charsets.UTF_8))
        }
        val binaryResult = ReadTextFileTool.execute(args("path" to binary.absolutePath))
        assertFalse(binaryResult.ok)
        assertTrue(binaryResult.error?.contains("binary") == true)
    }

    @Test
    fun readTextFile_twoMegabyteCap() = runBlocking {
        val big = File(tempDir, "big.txt").apply { writeText("x".repeat(3 * 1024 * 1024)) }
        val result = ReadTextFileTool.execute(args("path" to big.absolutePath))
        assertTrue(result.ok)
        assertTrue(result.output.contains("[truncated"))
        assertTrue(result.output.length <= 100_000 + 100)
    }
}
