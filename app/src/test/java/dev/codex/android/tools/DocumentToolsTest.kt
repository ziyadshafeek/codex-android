package dev.codex.android.tools

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** JVM unit tests for [ReadPdfTool], [ReadDocumentTool], and [ReadTextFileTool]. */
class DocumentToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun args(path: String, page: Int? = null) = buildJsonObject {
        put("path", path)
        if (page != null) put("page", page)
    }

    private fun writeZip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    /**
     * Builds a minimal valid one-page PDF. [text] is shown on the page
     * with a plain literal string; null produces a page with no text.
     */
    private fun minimalPdf(text: String?): ByteArray {
        val out = ByteArrayOutputStream()
        fun write(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        write("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        fun obj(body: String) {
            offsets += out.size()
            write("${offsets.size} 0 obj\n")
            write(body)
            write("\nendobj\n")
        }
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        )
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        val stream = if (text == null) "" else "BT /F1 24 Tf 100 700 Td ($text) Tj ET"
        obj(
            "<< /Length ${stream.length} >>\nstream\n$stream\nendstream",
        )
        val xref = out.size()
        write("xref\n0 ${offsets.size + 1}\n")
        write("0000000000 65535 f \n")
        for (offset in offsets) write("%010d 00000 n \n".format(offset))
        write("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF")
        return out.toByteArray()
    }

    @Test
    fun pdfReturnsPageCountAndKnownText() = runBlocking {
        val file = temp.newFile("hello.pdf")
        file.writeBytes(minimalPdf("Hello Codex"))
        val result = ReadPdfTool.execute(args(file.absolutePath))
        assertTrue(result.ok)
        assertTrue(result.output.contains("Pages: 1"))
        assertTrue(result.output.contains("[p. 1]"))
        assertTrue(result.output.contains("Hello Codex"))
    }

    @Test
    fun pdfSinglePageArgument() = runBlocking {
        val file = temp.newFile("hello.pdf")
        file.writeBytes(minimalPdf("Hello Codex"))
        val result = ReadPdfTool.execute(args(file.absolutePath, page = 1))
        assertTrue(result.ok)
        assertTrue(result.output.contains("Hello Codex"))
    }

    @Test
    fun pdfPageOutOfRangeReturnsError() = runBlocking {
        val file = temp.newFile("hello.pdf")
        file.writeBytes(minimalPdf("Hello Codex"))
        val result = ReadPdfTool.execute(args(file.absolutePath, page = 2))
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("out of range"))
    }

    @Test
    fun pdfWithNoTextReportsScanned() = runBlocking {
        val file = temp.newFile("blank.pdf")
        file.writeBytes(minimalPdf(null))
        val result = ReadPdfTool.execute(args(file.absolutePath))
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("scanned"))
    }

    @Test
    fun pdfMissingFileReturnsError() = runBlocking {
        val result = ReadPdfTool.execute(args(File(temp.root, "gone.pdf").absolutePath))
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("not found"))
    }

    @Test
    fun pdfMissingPathArgumentReturnsError() = runBlocking {
        val result = ReadPdfTool.execute(buildJsonObject {})
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("Missing required argument: path"))
    }

    @Test
    fun docxRoundTripReturnsTextAndTable() = runBlocking {
        val file = temp.newFile("notes.docx")
        writeZip(
            file,
            mapOf(
                "[Content_Types].xml" to
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    |<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    |<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    |<Default Extension="xml" ContentType="application/xml"/>
                    |<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    |</Types>""".trimMargin(),
                "_rels/.rels" to
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    |<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    |<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    |</Relationships>""".trimMargin(),
                "word/document.xml" to
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    |<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                    |<w:body>
                    |<w:p><w:r><w:t>Hello Docx</w:t></w:r></w:p>
                    |<w:p><w:r><w:t>Second paragraph</w:t></w:r></w:p>
                    |<w:tbl>
                    |<w:tr><w:tc><w:p><w:r><w:t>A1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B2</w:t></w:r></w:p></w:tc></w:tr>
                    |<w:tr><w:tc><w:p><w:r><w:t>C3</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>D4</w:t></w:r></w:p></w:tc></w:tr>
                    |</w:tbl>
                    |</w:body>
                    |</w:document>""".trimMargin(),
            ),
        )
        val result = ReadDocumentTool.execute(args(file.absolutePath))
        assertTrue(result.ok)
        assertTrue(result.output.contains("Hello Docx"))
        assertTrue(result.output.contains("Second paragraph"))
        assertTrue(result.output.contains("[Table]"))
        assertTrue(result.output.contains("A1 | B2"))
        assertTrue(result.output.contains("C3 | D4"))
    }

    @Test
    fun xlsxRoundTripReturnsSheetsAndRows() = runBlocking {
        val file = temp.newFile("data.xlsx")
        writeZip(
            file,
            mapOf(
                "[Content_Types].xml" to
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    |<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    |<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    |<Default Extension="xml" ContentType="application/xml"/>
                    |</Types>""".trimMargin(),
                "_rels/.rels" to
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    |<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    |<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    |</Relationships>""".trimMargin(),
                "xl/workbook.xml" to
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    |<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    |<sheets><sheet name="Data" sheetId="1" r:id="rId1"/></sheets>
                    |</workbook>""".trimMargin(),
                "xl/_rels/workbook.xml.rels" to
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    |<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    |<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    |</Relationships>""".trimMargin(),
                "xl/sharedStrings.xml" to
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    |<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2">
                    |<si><t>Name</t></si><si><t>Ada</t></si>
                    |</sst>""".trimMargin(),
                "xl/worksheets/sheet1.xml" to
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    |<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    |<sheetData>
                    |<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="inlineStr"><is><t>Age</t></is></c></row>
                    |<row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2"><v>36</v></c></row>
                    |</sheetData>
                    |</worksheet>""".trimMargin(),
            ),
        )
        val result = ReadDocumentTool.execute(args(file.absolutePath))
        assertTrue(result.ok)
        assertTrue(result.output.contains("[Sheet: Data]"))
        assertTrue(result.output.contains("Name | Age"))
        assertTrue(result.output.contains("Ada | 36"))
    }

    @Test
    fun pptxRoundTripReturnsSlides() = runBlocking {
        val file = temp.newFile("deck.pptx")
        writeZip(
            file,
            mapOf(
                "[Content_Types].xml" to
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    |<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    |<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    |<Default Extension="xml" ContentType="application/xml"/>
                    |</Types>""".trimMargin(),
                "_rels/.rels" to
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    |<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    |<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
                    |</Relationships>""".trimMargin(),
                "ppt/slides/slide1.xml" to
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    |<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                    |<p:cSld><p:spTree>
                    |<p:sp><p:txBody>
                    |<a:p><a:r><a:t>First point</a:t></a:r></a:p>
                    |<a:p><a:r><a:t>Second point</a:t></a:r></a:p>
                    |</p:txBody></p:sp>
                    |</p:spTree></p:cSld>
                    |</p:sld>""".trimMargin(),
            ),
        )
        val result = ReadDocumentTool.execute(args(file.absolutePath))
        assertTrue(result.ok)
        assertTrue(result.output.contains("[Slide 1]"))
        assertTrue(result.output.contains("First point"))
        assertTrue(result.output.contains("Second point"))
    }

    @Test
    fun documentMissingFileReturnsError() = runBlocking {
        val result = ReadDocumentTool.execute(args(File(temp.root, "gone.docx").absolutePath))
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("not found"))
    }

    @Test
    fun documentUnsupportedExtensionReturnsError() = runBlocking {
        val file = temp.newFile("notes.txt")
        file.writeText("plain text")
        val result = ReadDocumentTool.execute(args(file.absolutePath))
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("Unsupported file type"))
    }

    @Test
    fun documentLegacyBinaryFormatReturnsError() = runBlocking {
        val file = temp.newFile("old.doc")
        file.writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(), 0, 0, 0, 0))
        val result = ReadDocumentTool.execute(args(file.absolutePath))
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("older binary"))
    }

    @Test
    fun textFileReturnsContent() = runBlocking {
        val file = temp.newFile("notes.txt")
        file.writeText("Line one\nLine two\n")
        val result = ReadTextFileTool.execute(args(file.absolutePath))
        assertTrue(result.ok)
        assertTrue(result.output.contains("Line one"))
        assertTrue(result.output.contains("Line two"))
    }

    @Test
    fun textFileTruncatesLongContent() = runBlocking {
        val file = temp.newFile("long.txt")
        file.writeText("x".repeat(100_001))
        val result = ReadTextFileTool.execute(args(file.absolutePath))
        assertTrue(result.ok)
        assertTrue(result.output.contains("[truncated]"))
        assertTrue(result.output.length < 100_020)
    }

    @Test
    fun textFileMissingReturnsError() = runBlocking {
        val result = ReadTextFileTool.execute(args(File(temp.root, "gone.txt").absolutePath))
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("not found"))
    }

    @Test
    fun textFileBinaryContentReturnsError() = runBlocking {
        val file = temp.newFile("blob.bin")
        file.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0))
        val result = ReadTextFileTool.execute(args(file.absolutePath))
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("does not look like a text file"))
    }

    @Test
    fun textFileReadsUtf16LeWithBom() = runBlocking {
        val file = temp.newFile("utf16le.txt")
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "Hello UTF-16".toByteArray(Charsets.UTF_16LE)
        file.writeBytes(bytes)
        val result = ReadTextFileTool.execute(args(file.absolutePath))
        assertTrue(result.ok)
        assertTrue(result.output.contains("Hello UTF-16"))
    }

    @Test
    fun textFileReadsUtf16BeWithBom() = runBlocking {
        val file = temp.newFile("utf16be.txt")
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            "Hello UTF-16".toByteArray(Charsets.UTF_16BE)
        file.writeBytes(bytes)
        val result = ReadTextFileTool.execute(args(file.absolutePath))
        assertTrue(result.ok)
        assertTrue(result.output.contains("Hello UTF-16"))
    }

    @Test
    fun textFileBoundsHugeFileInsteadOfLoadingItWhole() = runBlocking {
        val file = temp.newFile("huge.txt")
        file.writeText("x".repeat(3_000_000))
        val result = ReadTextFileTool.execute(args(file.absolutePath))
        assertTrue(result.ok)
        assertTrue(result.output.contains("[truncated]"))
        assertTrue(result.output.length < 100_020)
    }

    @Test
    fun toolNamesAndSchemas() {
        assertEquals("read_pdf", ReadPdfTool.name)
        assertEquals("read_document", ReadDocumentTool.name)
        assertEquals("read_text_file", ReadTextFileTool.name)
        assertFalse(ReadPdfTool.sensitive)
        assertFalse(ReadDocumentTool.sensitive)
        assertFalse(ReadTextFileTool.sensitive)
        for (tool in listOf(ReadPdfTool, ReadDocumentTool, ReadTextFileTool)) {
            val required = tool.parametersSchema["required"]
            assertTrue(required.toString().contains("path"))
        }
    }
}
