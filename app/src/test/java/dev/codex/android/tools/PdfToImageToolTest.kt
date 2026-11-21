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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the PDF-to-image tool (PdfToImageTool.kt).
 *
 * `android.graphics.Bitmap` / `PdfRenderer` are stubs on the JVM, so the
 * real render is proven by the instrumented test instead. What runs here:
 * the pure logic (page-range parsing, DPI clamping, dimensions, output
 * naming, shell quoting, pdftoppm run grouping, the PNG-output contract)
 * plus the tool's argument validation and the pdftoppm-fallback wiring,
 * using a fake shell. The native path on the JVM always fails at the
 * Android stub, so those tests assert the failure is a clean error —
 * never a crash and never a faked image.
 */
class PdfToImageToolTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("codex-pdf-to-image-test").toFile()
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

    /** Minimal file that starts with the %PDF- magic (content is irrelevant on the JVM). */
    private fun writeFakePdf(name: String): File {
        val file = File(tempDir, name)
        file.writeBytes("%PDF-1.4\n%fake pdf for tests\n".toByteArray(Charsets.US_ASCII))
        return file
    }

    // ------------------------------------------------------------------
    // Page-range parsing
    // ------------------------------------------------------------------

    @Test
    fun parsePageNumbers_singleRangeList() {
        assertEquals(listOf(2), parsePageNumbers("2"))
        assertEquals(listOf(1, 2, 3), parsePageNumbers("1-3"))
        assertEquals(listOf(1, 3, 5), parsePageNumbers("1,3,5"))
        assertEquals(listOf(1, 2, 3, 5), parsePageNumbers("1-3,5"))
        assertEquals(listOf(1, 3), parsePageNumbers("3,1"))
        assertEquals(listOf(2), parsePageNumbers(" 2 "))
    }

    @Test
    fun parsePageNumbers_invalid() {
        assertNull(parsePageNumbers(""))
        assertNull(parsePageNumbers("0"))
        assertNull(parsePageNumbers("-1"))
        assertNull(parsePageNumbers("3-1"))
        assertNull(parsePageNumbers("abc"))
        assertNull(parsePageNumbers("1-"))
        assertNull(parsePageNumbers("1,,2"))
        assertNull(parsePageNumbers("1-3-5"))
        assertNull(parsePageNumbers("1.5"))
    }

    @Test
    fun expandPages_allAndBounds() {
        assertEquals(listOf(1, 2, 3, 4, 5), expandPages("all", 5))
        assertEquals(listOf(1, 2, 3, 4, 5), expandPages("ALL", 5))
        assertEquals(listOf(1, 2, 3, 4, 5), expandPages(null, 5))
        assertEquals(listOf(1, 2, 3, 4, 5), expandPages("", 5))
        assertEquals(listOf(2), expandPages("2", 5))
        assertEquals(listOf(1, 2, 3), expandPages("1-3", 5))
        assertNull(expandPages("6", 5))
        assertNull(expandPages("1-6", 5))
        assertNull(expandPages("abc", 5))
    }

    // ------------------------------------------------------------------
    // DPI, dimensions, naming, quoting, runs
    // ------------------------------------------------------------------

    @Test
    fun clampDpi_bounds() {
        assertEquals(72, clampDpi(72))
        assertEquals(150, clampDpi(150))
        assertEquals(300, clampDpi(300))
        assertEquals(72, clampDpi(50))
        assertEquals(72, clampDpi(0))
        assertEquals(300, clampDpi(500))
    }

    @Test
    fun renderDimensions_a4At150dpi() {
        // A4 is 595.28 x 841.89 pt; at 150 dpi that is 1240 x 1754 px.
        val size = renderDimensions(595.28, 841.89, 150)
        assertEquals(1240, size.width)
        assertEquals(1754, size.height)
        assertFalse(size.reduced)
    }

    @Test
    fun renderDimensions_a4At72dpi() {
        val size = renderDimensions(595.28, 841.89, 72)
        assertEquals(595, size.width)
        assertEquals(842, size.height)
        assertFalse(size.reduced)
    }

    @Test
    fun renderDimensions_hugePageIsCapped() {
        val size = renderDimensions(10_000.0, 10_000.0, 300)
        assertTrue(size.reduced)
        assertTrue(size.width.toLong() * size.height <= PdfToImageTool.MAX_PIXELS)
    }

    @Test
    fun outputFileName_shapes() {
        assertEquals("report-p1.png", outputFileName("report.pdf", 1))
        assertEquals("my notes-p12.png", outputFileName("my notes.pdf", 12))
        assertEquals("a.b.c-p3.png", outputFileName("a.b.c.pdf", 3))
    }

    @Test
    fun shellQuote_escapesSingleQuotes() {
        assertEquals("'simple'", shellQuote("simple"))
        assertEquals("'a'\\''b'", shellQuote("a'b"))
        assertEquals("'/workspace/my file.pdf'", shellQuote("/workspace/my file.pdf"))
    }

    @Test
    fun contiguousRuns_groups() {
        assertEquals(listOf(1..3, 5..6, 9..9), contiguousRuns(listOf(1, 2, 3, 5, 6, 9)))
        assertEquals(listOf(2..2), contiguousRuns(listOf(2)))
        assertEquals(emptyList<IntRange>(), contiguousRuns(emptyList()))
        assertEquals(listOf(1..5), contiguousRuns(listOf(1, 2, 3, 4, 5)))
    }

    // ------------------------------------------------------------------
    // PNG-output contract
    // ------------------------------------------------------------------

    @Test
    fun pngDimensions_readsIhdr() {
        val png = minimalPngBytes(1240, 1754)
        assertTrue(isPng(png))
        assertEquals(1240 to 1754, pngDimensions(png))
    }

    @Test
    fun pngDimensions_rejectsNonPng() {
        assertNull(pngDimensions(byteArrayOf(1, 2, 3, 4)))
        assertNull(pngDimensions("%PDF-1.4\n".toByteArray(Charsets.US_ASCII)))
        assertNull(pngDimensions(ByteArray(0)))
        assertFalse(isPng("%PDF-1.4\n".toByteArray(Charsets.US_ASCII)))
    }

    // ------------------------------------------------------------------
    // Argument validation / error paths (execute)
    // ------------------------------------------------------------------

    @Test
    fun execute_missingPathArg() = runBlocking {
        val result = PdfToImageTool(tempDir).execute(args())
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("Missing required argument: path"))
    }

    @Test
    fun execute_missingFile() = runBlocking {
        val result = PdfToImageTool(tempDir).execute(args("path" to "nope.pdf"))
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("PDF not found: nope.pdf"))
    }

    @Test
    fun execute_nonPdfFile() = runBlocking {
        File(tempDir, "notes.txt").writeText("hello")
        val result = PdfToImageTool(tempDir).execute(args("path" to "notes.txt"))
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("Not a PDF file"))
    }

    @Test
    fun execute_pathEscapeRejected() = runBlocking {
        val result = PdfToImageTool(tempDir).execute(args("path" to "../secret.pdf"))
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("escapes workspace"))
    }

    @Test
    fun execute_badDpiArg() = runBlocking {
        writeFakePdf("doc.pdf")
        val result = PdfToImageTool(tempDir).execute(args("path" to "doc.pdf", "dpi" to "high"))
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("dpi must be an integer"))
    }

    @Test
    fun execute_realPdfOnJvmFailsCleanlyWithoutShell() = runBlocking {
        // On the JVM the Android PdfRenderer is a stub, so the native path
        // must fail — but as a clean ToolResult error, never a crash, and
        // with the fallback reported as unwired (no shell injected).
        writeFakePdf("doc.pdf")
        val result = PdfToImageTool(tempDir).execute(args("path" to "doc.pdf"))
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("Could not render doc.pdf"))
        assertTrue(result.error.orEmpty().contains("fallback is not wired"))
        assertTrue(File(tempDir, "images").listFiles().orEmpty().isEmpty())
    }

    // ------------------------------------------------------------------
    // pdftoppm fallback wiring (fake shell)
    // ------------------------------------------------------------------

    @Test
    fun execute_fallbackReportsPdftoppmMissing() = runBlocking {
        writeFakePdf("doc.pdf")
        var sawCommand: String? = null
        val tool = PdfToImageTool(tempDir) { command ->
            sawCommand = command
            ShellResult(exitCode = 127, stdout = "", stderr = "")
        }
        val result = tool.execute(args("path" to "doc.pdf"))
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("pdftoppm is not installed"))
        assertNotNull(sawCommand)
        assertTrue(sawCommand!!.contains("command -v pdftoppm"))
        assertTrue(sawCommand!!.contains("pdftoppm -png -r 150 -f 1 -l 1"))
    }

    @Test
    fun execute_fallbackReportsPdftoppmFailure() = runBlocking {
        writeFakePdf("doc.pdf")
        val tool = PdfToImageTool(tempDir) { _ ->
            ShellResult(exitCode = 1, stdout = "", stderr = "pdftoppm: bad pdf")
        }
        val result = tool.execute(args("path" to "doc.pdf", "pages" to "2"))
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("pdftoppm failed too (exit 1)"))
        assertTrue(result.error.orEmpty().contains("bad pdf"))
    }

    @Test
    fun execute_fallbackCollectsAndRenamesPngs() = runBlocking {
        writeFakePdf("report.pdf")
        val tool = PdfToImageTool(tempDir) { command ->
            // Fake pdftoppm: pretend it wrote its usual <base>-<n>.png files.
            assertTrue(command.contains("pdftoppm -png -r 150 -f 1 -l 2"))
            val outDir = File(tempDir, "images").apply { mkdirs() }
            File(outDir, "report-1.png").writeBytes(minimalPngBytes(1240, 1754))
            File(outDir, "report-2.png").writeBytes(minimalPngBytes(1240, 1754))
            ShellResult(exitCode = 0, stdout = "", stderr = "")
        }
        val result = tool.execute(args("path" to "report.pdf", "pages" to "1-2"))
        assertTrue(result.error.orEmpty(), result.ok)
        assertTrue(result.output.contains("images/report-p1.png"))
        assertTrue(result.output.contains("images/report-p2.png"))
        assertTrue(result.output.contains("1240x1754 px"))
        assertTrue(result.output.contains("pdftoppm"))
        assertTrue(File(tempDir, "images/report-p1.png").isFile)
        assertTrue(File(tempDir, "images/report-p2.png").isFile)
        assertFalse(File(tempDir, "images/report-1.png").exists())
    }

    @Test
    fun execute_fallbackSinglePageBarePng() = runBlocking {
        writeFakePdf("solo.pdf")
        val tool = PdfToImageTool(tempDir) { command ->
            assertTrue(command.contains("-f 3 -l 3"))
            val outDir = File(tempDir, "images").apply { mkdirs() }
            // pdftoppm omits the page number for a single-page render.
            File(outDir, "solo.png").writeBytes(minimalPngBytes(595, 842))
            ShellResult(exitCode = 0, stdout = "", stderr = "")
        }
        val result = tool.execute(args("path" to "solo.pdf", "pages" to "3", "dpi" to 72))
        assertTrue(result.error.orEmpty(), result.ok)
        assertTrue(result.output.contains("images/solo-p3.png"))
        assertTrue(File(tempDir, "images/solo-p3.png").isFile)
    }

    @Test
    fun execute_fallbackDropsNonPngOutputs() = runBlocking {
        writeFakePdf("bad.pdf")
        val tool = PdfToImageTool(tempDir) { _ ->
            val outDir = File(tempDir, "images").apply { mkdirs() }
            File(outDir, "bad-1.png").writeBytes("not a png".toByteArray(Charsets.US_ASCII))
            ShellResult(exitCode = 0, stdout = "", stderr = "")
        }
        val result = tool.execute(args("path" to "bad.pdf"))
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("produced no PNG files"))
    }

    @Test
    fun execute_fallbackRespectsOutputDir() = runBlocking {
        writeFakePdf("doc.pdf")
        val tool = PdfToImageTool(tempDir) { _ ->
            val outDir = File(tempDir, "renders").apply { mkdirs() }
            File(outDir, "doc-1.png").writeBytes(minimalPngBytes(100, 100))
            ShellResult(exitCode = 0, stdout = "", stderr = "")
        }
        val result = tool.execute(args("path" to "doc.pdf", "output_dir" to "renders"))
        assertTrue(result.error.orEmpty(), result.ok)
        assertTrue(result.output.contains("renders/doc-p1.png"))
    }
}
