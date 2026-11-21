package dev.codex.android.tools

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for `pdf_to_image`: the real proof. Renders a REAL
 * PDF page to a PNG on the emulator with Android's built-in PdfRenderer
 * and verifies the PNG is valid (non-empty, PNG magic bytes, expected
 * dimensions for the requested DPI). Runs in CI on the emulator.
 *
 * Fixtures are built with PdfBox-Android (already a dependency); the
 * workspace is a throwaway dir under the test app's cache dir.
 */
@RunWith(AndroidJUnit4::class)
class PdfToImageToolInstrumentedTest {

    private lateinit var workspace: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        workspace = File(context.cacheDir, "pdf-to-image-test").apply {
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

    /** Writes a real multi-page A4 PDF with PdfBox; returns its workspace-relative path. */
    private fun writePdf(name: String, pageTexts: List<String>): String {
        val file = File(workspace, name)
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
        return name
    }

    /** Asserts [file] is a non-empty PNG of the expected pixel size. */
    private fun assertValidPng(file: File, expectedWidth: Int, expectedHeight: Int) {
        assertTrue("PNG missing: ${file.absolutePath}", file.isFile)
        assertTrue("PNG empty: ${file.name}", file.length() > 0)
        val bytes = file.readBytes()
        assertTrue("Not a PNG: ${file.name}", isPng(bytes))
        assertEquals(expectedWidth to expectedHeight, pngDimensions(bytes))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        assertEquals(expectedWidth, bounds.outWidth)
        assertEquals(expectedHeight, bounds.outHeight)
    }

    @Test
    fun rendersAllPagesAt150dpi() = runBlocking {
        val path = writePdf("report.pdf", listOf("Page one", "Page two"))
        val tool = PdfToImageTool(workspace)
        val result = tool.execute(args("path" to path, "pages" to "all"))
        assertTrue(result.error.orEmpty(), result.ok)
        assertTrue(result.output.contains("images/report-p1.png"))
        assertTrue(result.output.contains("images/report-p2.png"))
        assertTrue(result.output.contains("1240x1752 px"))
        // A4 is 595.28 x 841.89 pt, but PdfRenderer.Page reports integer
        // points (595 x 841), so at 150 dpi the render is 1240 x 1752 px.
        assertValidPng(File(workspace, "images/report-p1.png"), 1240, 1752)
        assertValidPng(File(workspace, "images/report-p2.png"), 1240, 1752)
    }

    @Test
    fun rendersSinglePageAt72dpi() = runBlocking {
        val path = writePdf("two.pdf", listOf("Page one", "Page two"))
        val tool = PdfToImageTool(workspace)
        val result = tool.execute(args("path" to path, "pages" to "2", "dpi" to 72))
        assertTrue(result.error.orEmpty(), result.ok)
        assertTrue(result.output.contains("images/two-p2.png"))
        assertFalse(File(workspace, "images/two-p1.png").exists())
        // A4 at 72 dpi = 595 x 841 px (PdfRenderer reports integer points).
        assertValidPng(File(workspace, "images/two-p2.png"), 595, 841)
    }

    @Test
    fun rendersRangeToCustomOutputDir() = runBlocking {
        val path = writePdf("range.pdf", listOf("One", "Two", "Three"))
        val tool = PdfToImageTool(workspace)
        val result = tool.execute(args("path" to path, "pages" to "1-2", "output_dir" to "renders"))
        assertTrue(result.error.orEmpty(), result.ok)
        assertTrue(result.output.contains("renders/range-p1.png"))
        assertTrue(result.output.contains("renders/range-p2.png"))
        assertValidPng(File(workspace, "renders/range-p1.png"), 1240, 1752)
        assertValidPng(File(workspace, "renders/range-p2.png"), 1240, 1752)
        assertFalse(File(workspace, "renders/range-p3.png").exists())
    }

    @Test
    fun missingFileIsAClearError() = runBlocking {
        val tool = PdfToImageTool(workspace)
        val result = tool.execute(args("path" to "nope.pdf"))
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("PDF not found"))
    }

    @Test
    fun outOfRangePageIsAClearError() = runBlocking {
        val path = writePdf("short.pdf", listOf("Only page"))
        val tool = PdfToImageTool(workspace)
        val result = tool.execute(args("path" to path, "pages" to "5"))
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("Invalid pages selection"))
    }

    @Test
    fun pageCapIsHonoured() = runBlocking {
        val path = writePdf("big.pdf", (1..25).map { "Page $it" })
        val tool = PdfToImageTool(workspace)
        val result = tool.execute(args("path" to path, "pages" to "all"))
        assertTrue(result.error.orEmpty(), result.ok)
        assertTrue(result.output.contains("Only the first 20 pages were rendered"))
        assertTrue(File(workspace, "images/big-p20.png").isFile)
        assertFalse(File(workspace, "images/big-p21.png").exists())
    }
}
