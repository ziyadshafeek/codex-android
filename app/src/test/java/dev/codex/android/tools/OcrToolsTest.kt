package dev.codex.android.tools

import dev.codex.android.linux.ShellResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the OCR tool. The guest shell is faked, so no real
 * Tesseract or Linux workspace is needed: these tests pin down argument
 * handling, the missing-Tesseract / missing-poppler errors, and the
 * TSV parsing that turns Tesseract output into text + confidence.
 * A live Tesseract run is a phone/CI check, not a JVM unit test.
 */
class OcrToolsTest {

    private lateinit var tempDir: File
    private lateinit var workspace: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("codex-ocr-test").toFile()
        workspace = File(tempDir, "workspace").apply { mkdirs() }
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

    private val sampleTsv = listOf(
        "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext",
        "1\t1\t0\t0\t0\t0\t0\t0\t100\t50\t-1\t",
        "5\t1\t1\t1\t1\t1\t10\t10\t40\t12\t92\tHello",
        "5\t1\t1\t1\t1\t2\t55\t10\t40\t12\t88\tworld",
        "5\t1\t1\t1\t2\t1\t10\t30\t30\t12\t-1\t,",
        "5\t1\t1\t1\t2\t2\t45\t30\t50\t12\t76\tagain",
    ).joinToString("\n")

    private fun fakeShell(
        tesseractPresent: Boolean = true,
        pdftoppmPresent: Boolean = true,
        tsvOutput: String = sampleTsv,
    ): suspend (String) -> ShellResult = { command ->
        when {
            command.contains("command -v tesseract") ->
                if (tesseractPresent) ShellResult(0, "/usr/bin/tesseract\n", "") else ShellResult(0, "MISSING\n", "")
            command.contains("command -v pdftoppm") ->
                if (pdftoppmPresent) ShellResult(0, "/usr/bin/pdftoppm\n", "") else ShellResult(0, "MISSING\n", "")
            command.startsWith("pdftoppm") -> ShellResult(0, "", "")
            command.startsWith("ls /tmp/ocr-page-") -> ShellResult(0, "/tmp/ocr-page-1.png\n", "")
            command.startsWith("tesseract") -> ShellResult(0, tsvOutput, "")
            command.startsWith("rm -f") -> ShellResult(0, "", "")
            else -> ShellResult(127, "", "unexpected command: $command")
        }
    }

    private fun tool(
        tesseractPresent: Boolean = true,
        pdftoppmPresent: Boolean = true,
        tsvOutput: String = sampleTsv,
    ): OcrImageTool {
        val shell = fakeShell(tesseractPresent, pdftoppmPresent, tsvOutput)
        return ocrToolForWorkspace(shell, workspace)
    }

    private fun imageInWorkspace(name: String = "receipt.png"): File =
        File(workspace, name).apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) }

    // --- TSV parsing ---

    @Test
    fun parseTesseractTsv_wordsLinesAndMeanConfidence() {
        val parsed = parseTesseractTsv(sampleTsv)!!
        assertEquals("Hello world\n, again", parsed.text)
        assertEquals(4, parsed.wordCount)
        // Mean over conf >= 0 only: (92 + 88 + 76) / 3.
        assertEquals(85.333, parsed.meanConfidence, 0.01)
    }

    @Test
    fun parseTesseractTsv_noWordsReturnsNull() {
        assertNull(parseTesseractTsv("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"))
        assertNull(parseTesseractTsv(""))
    }

    @Test
    fun shellQuote_escapesSingleQuotes() {
        assertEquals("'plain'", shellQuote("plain"))
        assertEquals("'it'\\''s'", shellQuote("it's"))
    }

    @Test
    fun validOcrLang_acceptsCodesAndRejectsInjection() {
        assertTrue(validOcrLang("eng"))
        assertTrue(validOcrLang("eng+fra"))
        assertTrue(validOcrLang("deu"))
        assertFalse(validOcrLang("eng; rm -rf /"))
        assertFalse(validOcrLang("eng\$(id)"))
        assertFalse(validOcrLang(""))
    }

    // --- tool behaviour with a fake shell ---

    @Test
    fun ocrImage_happyPathReturnsTextAndConfidence() = runBlocking {
        val result = tool().execute(args("path" to imageInWorkspace().absolutePath))
        assertTrue(result.ok)
        assertTrue(result.output.contains("Hello world"))
        assertTrue(result.output.contains("85%"))
        assertTrue(result.output.contains("4 words"))
    }

    @Test
    fun ocrImage_missingTesseractFailsWithInstallHint() = runBlocking {
        val result = tool(tesseractPresent = false).execute(args("path" to imageInWorkspace().absolutePath))
        assertFalse(result.ok)
        assertTrue(result.error?.contains("tesseract-ocr") == true)
        assertFalse(result.error?.contains("Hello") == true)
    }

    @Test
    fun ocrImage_noRecognisedTextFailsHonestly() = runBlocking {
        val result = tool(tsvOutput = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n")
            .execute(args("path" to imageInWorkspace().absolutePath))
        assertFalse(result.ok)
        assertTrue(result.error?.contains("no text") == true)
    }

    @Test
    fun ocrImage_outsideWorkspaceFails() = runBlocking {
        val outside = File(tempDir, "outside.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val result = tool().execute(args("path" to outside.absolutePath))
        assertFalse(result.ok)
        assertTrue(result.error?.contains("outside the workspace") == true)
    }

    @Test
    fun ocrImage_unsupportedTypeAndBadLangFail() = runBlocking {
        val txt = File(workspace, "notes.txt").apply { writeText("hello") }
        assertFalse(tool().execute(args("path" to txt.absolutePath)).ok)
        assertFalse(
            tool().execute(args("path" to imageInWorkspace().absolutePath, "lang" to "eng;id")).ok,
        )
    }

    @Test
    fun ocrImage_pdfPageRendersThenOcrRuns() = runBlocking {
        val pdf = File(workspace, "scan.pdf").apply { writeBytes(byteArrayOf('%'.code.toByte(), 'P'.code.toByte())) }
        val seen = mutableListOf<String>()
        val shell: suspend (String) -> ShellResult = { command ->
            seen += command
            fakeShell()(command)
        }
        val ocr = ocrToolForWorkspace(shell, workspace)
        val result = ocr.execute(args("path" to pdf.absolutePath, "page" to 1))
        assertTrue(result.ok)
        assertTrue(result.output.contains("Hello world"))
        assertTrue(seen.any { it.contains("pdftoppm") })
        assertTrue(seen.any { it.contains("/tmp/ocr-page-1.png") })
    }

    @Test
    fun ocrImage_pdfWithoutPdftoppmFailsWithPopplerHint() = runBlocking {
        val pdf = File(workspace, "scan.pdf").apply { writeBytes(byteArrayOf(1, 2)) }
        val result = tool(pdftoppmPresent = false).execute(args("path" to pdf.absolutePath, "page" to 1))
        assertFalse(result.ok)
        assertTrue(result.error?.contains("poppler") == true)
    }

    @Test
    fun ocrToolForWorkspace_missingFileFailsAndPresentFileSucceeds() = runBlocking {
        val mapped = ocrToolForWorkspace(fakeShell(), workspace)
        val missing = File(workspace, "gone.png")
        assertFalse(mapped.execute(args("path" to missing.absolutePath)).ok)
        assertTrue(mapped.execute(args("path" to imageInWorkspace().absolutePath)).ok)
    }
}
