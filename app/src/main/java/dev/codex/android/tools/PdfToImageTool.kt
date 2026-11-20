package dev.codex.android.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.codex.android.linux.ShellResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt

/**
 * Converts PDF pages to PNG images (`name = "pdf_to_image"`).
 *
 * The agent used to have no way to do this: it told the user "there's no
 * PDF renderer or image-export tool in this workspace" and the user had to
 * tell it to install poppler/pdftoppm in the Linux workspace first. This
 * tool removes that step — nothing needs installing.
 *
 * PRIMARY PATH — Android's built-in [PdfRenderer] (no new dependency):
 * the requested pages are rendered to bitmaps at the requested DPI,
 * compressed as PNG, and saved under the workspace (default
 * `images/<pdf-name>-p<page>.png`). The result lists the PNG paths and
 * dimensions so the agent can then read them with `read_image` /
 * `attachment_read` for vision.
 *
 * FALLBACK — `pdftoppm` in the Linux workspace: only if the native
 * renderer fails (e.g. the PDF uses a feature PdfRenderer cannot
 * render). It runs through the app's existing Linux-shell path — the
 * [shell] lambda the caller injects (in chat this is the
 * `ProotShellEngine.shellExec` wrapper from `ChatViewModel`, exactly
 * the one `shell_exec` uses). The command first checks
 * `command -v pdftoppm`, so a rootfs without poppler-utils reports
 * clearly instead of silently pretending to work. With no [shell]
 * injected the fallback is unwired and the native error says so.
 *
 * Never fakes an image: a failure (missing file, encrypted PDF,
 * out-of-range page, render failure with no working fallback) is a
 * plain error, never a placeholder PNG.
 *
 * The page-range syntax mirrors [parsePdfPages] in PdfTools.kt
 * ("all", "2", "1-3", "1,3,5") but is implemented here without a
 * PdfBox dependency so this file's compile closure stays small; the
 * JVM tests pin the shared behaviour.
 */
class PdfToImageTool(
    workspaceDir: File,
    private val shell: (suspend (String) -> ShellResult)? = null,
) : Tool {

    /** Production constructor: workspace under the app's files directory. */
    constructor(context: Context, shell: (suspend (String) -> ShellResult)? = null) : this(
        File(context.applicationContext.filesDir, "workspace"),
        shell,
    )

    private val files = WorkspaceFileTools(workspaceDir)
    private val rootCanonical: File = workspaceDir.canonicalFile

    override val name: String = "pdf_to_image"
    override val description: String =
        "Converts pages of a PDF file to PNG images, so a scanned or image-only PDF can be read with vision. " +
            "No installation needed: uses Android's built-in PDF renderer, with pdftoppm in the Linux workspace as a fallback. " +
            "Use when a PDF page has no extractable text (read_pdf reports no text), or the user asks for the PDF as images. " +
            "Writes PNGs under the workspace (default images/<pdf-name>-p<page>.png) and returns their paths and dimensions — " +
            "read a page back with read_image afterwards. Encrypted PDFs cannot be rendered."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Workspace-relative path of the PDF to convert, e.g. docs/report.pdf")
            }
            putJsonObject("pages") {
                put("type", "string")
                put("description", "Pages to render: \"all\", a single page like \"1\", a range like \"1-3\", or a list like \"1,3,5\" (default \"1\"). At most 20 pages per call.")
            }
            putJsonObject("dpi") {
                put("type", "integer")
                put("description", "Render resolution in dots per inch (default 150, clamped to 72-300)")
            }
            putJsonObject("output_dir") {
                put("type", "string")
                put("description", "Workspace-relative directory for the PNGs (default \"images\")")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }
    // Writes derived files into the workspace, like the other PDF tools.
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val rawPath = args["path"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@withContext fail("Missing required argument: path")
        val rawPages = args["pages"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: "1"
        val dpiArg = args["dpi"]
        if (dpiArg != null && dpiArg.jsonPrimitive.intOrNull == null) {
            return@withContext fail("dpi must be an integer (72-300).")
        }
        val requestedDpi = dpiArg?.jsonPrimitive?.intOrNull ?: DEFAULT_DPI
        val dpi = clampDpi(requestedDpi)
        val dpiNote = if (dpi != requestedDpi) "dpi $requestedDpi is outside 72-300, clamped to $dpi." else null
        val outDirRaw = args["output_dir"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_OUTPUT_DIR

        val pdfFile = try {
            files.resolveSafe(rawPath)
        } catch (e: SecurityException) {
            return@withContext fail(e.message ?: "Refused path: $rawPath")
        }
        if (!pdfFile.isFile) {
            return@withContext fail("PDF not found: $rawPath (looked under the workspace at ${pdfFile.absolutePath})")
        }
        if (!looksLikePdf(pdfFile)) {
            return@withContext fail("Not a PDF file: $rawPath (the file does not start with %PDF-)")
        }
        val outDir = try {
            files.resolveSafe(outDirRaw)
        } catch (e: SecurityException) {
            return@withContext fail(e.message ?: "Refused output_dir: $outDirRaw")
        }
        outDir.mkdirs()

        val rendered: List<RenderedPage>
        val notes = mutableListOf<String>()
        dpiNote?.let { notes += it }
        try {
            val batch = renderNative(pdfFile, rawPages, dpi, outDir)
            rendered = batch.pages
            if (batch.capped) notes += "Only the first $MAX_PAGES pages were rendered (limit $MAX_PAGES per call); call again with a narrower pages range for the rest."
            if (batch.reduced) notes += "Rendered at reduced resolution: at $dpi dpi a page would exceed the ${MAX_PIXELS / 1_000_000} megapixel bitmap limit."
        } catch (e: PdfEncryptedException) {
            return@withContext fail(e.message ?: "This PDF is encrypted and cannot be rendered.")
        } catch (e: IllegalArgumentException) {
            return@withContext fail(e.message ?: "Invalid arguments.")
        } catch (e: Exception) {
            // Native renderer failed (e.g. a PDF feature PdfRenderer
            // cannot render). Fall back to pdftoppm — or say plainly
            // that the fallback is not available.
            return@withContext renderViaPdftoppm(
                pdfFile = pdfFile,
                rawPages = rawPages,
                dpi = dpi,
                outDir = outDir,
                nativeError = e.message ?: e.javaClass.simpleName,
                notes = notes,
            )
        }

        ToolResult(ok = true, output = renderReport(pdfFile.name, dpi, rendered, notes))
    }

    /**
     * Renders [rawPages] of [pdfFile] with the built-in [PdfRenderer].
     * Throws [PdfEncryptedException] for password-protected PDFs (no
     * fallback can help there either — pdftoppm cannot open them without
     * the password), [IllegalArgumentException] for a bad page
     * selection, and anything else for a genuine render failure.
     */
    private fun renderNative(pdfFile: File, rawPages: String, dpi: Int, outDir: File): RenderedBatch {
        try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                // PdfRenderer takes ownership of its descriptor. Duplicate
                // it so the outer descriptor is also closed on error
                // (same pattern as PromptAttachments.renderPdf).
                val owned = descriptor.dup()
                val renderer = try {
                    PdfRenderer(owned)
                } catch (e: SecurityException) {
                    owned.close()
                    throw PdfEncryptedException(
                        "This PDF is encrypted (password-protected) and cannot be rendered. " +
                            "Android's built-in PDF renderer cannot open encrypted PDFs — " +
                            "remove the password first and try again.",
                    )
                } catch (e: Exception) {
                    owned.close()
                    throw IOException("Could not open ${pdfFile.name} as a PDF: ${e.message ?: e.javaClass.simpleName}", e)
                }
                renderer.use { pdf ->
                    val pageCount = pdf.pageCount
                    if (pageCount <= 0) throw IOException("${pdfFile.name} has no pages.")
                    val pages = expandPages(rawPages, pageCount)
                        ?: throw IllegalArgumentException(
                            "Invalid pages selection \"$rawPages\" for a $pageCount-page PDF. " +
                                "Use \"all\", a page number, a range like \"1-3\", or a list like \"1,3,5\".",
                        )
                    val capped = pages.size > MAX_PAGES
                    val selected = if (capped) pages.take(MAX_PAGES) else pages
                    val baseName = pdfFile.nameWithoutExtension
                    val rendered = selected.map { pageNumber ->
                        renderOnePage(pdf, pageNumber - 1, dpi, outDir, baseName)
                    }
                    return RenderedBatch(rendered, capped, rendered.any { it.reduced })
                }
            }
        } catch (e: PdfEncryptedException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Could not render ${pdfFile.name} with the built-in Android PDF renderer: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun renderOnePage(
        pdf: PdfRenderer,
        index: Int,
        dpi: Int,
        outDir: File,
        baseName: String,
    ): RenderedPage {
        pdf.openPage(index).use { page ->
            val size = renderDimensions(page.width.toDouble(), page.height.toDouble(), dpi)
            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val outFile = File(outDir, outputFileName("$baseName.pdf", index + 1))
                FileOutputStream(outFile).use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        "Failed to encode page ${index + 1} as PNG"
                    }
                }
                return RenderedPage(
                    relativePath = relativePath(outFile),
                    page = index + 1,
                    width = size.width,
                    height = size.height,
                    bytes = outFile.length(),
                    reduced = size.reduced,
                )
            } finally {
                bitmap.recycle()
            }
        }
    }

    /**
     * pdftoppm fallback, via the injected Linux [shell] (the same
     * `ProotShellEngine.shellExec` path `shell_exec` uses; the workspace
     * is bound at /workspace in the guest). Only called after the native
     * renderer has already failed, never as a first resort.
     */
    private suspend fun renderViaPdftoppm(
        pdfFile: File,
        rawPages: String,
        dpi: Int,
        outDir: File,
        nativeError: String,
        notes: List<String>,
    ): ToolResult {
        val run = shell ?: return fail(
            "Could not render ${pdfFile.name} with the built-in Android PDF renderer: $nativeError\n" +
                "The pdftoppm fallback is not wired: no Linux shell was provided when this tool was registered, " +
                "so there is no way to run pdftoppm. No image was produced — nothing was faked.",
        )
        val baseName = pdfFile.nameWithoutExtension
        val runs: List<IntRange>
        var capped = false
        if (rawPages.equals("all", ignoreCase = true)) {
            // No page count is known here (the native open failed), so
            // render from page 1 and cap at the per-call limit.
            runs = listOf(1..MAX_PAGES)
            capped = true
        } else {
            val numbers = parsePageNumbers(rawPages)
                ?: return fail(
                    "Invalid pages selection: \"$rawPages\". Use \"all\", a page number, " +
                        "a range like \"1-3\", or a list like \"1,3,5\".",
                )
            val limited = if (numbers.size > MAX_PAGES) {
                capped = true
                numbers.take(MAX_PAGES)
            } else {
                numbers
            }
            runs = contiguousRuns(limited)
        }
        val guestPdf = guestPath(pdfFile)
        val guestPrefix = guestPath(outDir) + "/" + baseName
        for (range in runs) {
            val command = "if command -v pdftoppm >/dev/null 2>&1; " +
                "then pdftoppm -png -r $dpi -f ${range.first} -l ${range.last} ${shellQuote(guestPdf)} ${shellQuote(guestPrefix)}; " +
                "else exit 127; fi"
            val result = runCatching { run(command) }.getOrElse { e ->
                return fail("Could not run pdftoppm in the Linux workspace: ${e.message ?: e.javaClass.simpleName}")
            }
            if (result.exitCode == 127) {
                return fail(
                    "Could not render ${pdfFile.name}: the built-in renderer failed ($nativeError) and " +
                        "pdftoppm is not installed in the Linux workspace (poppler-utils is missing from the rootfs). " +
                        "Install poppler-utils in the Linux workspace and try again. No image was produced.",
                )
            }
            if (result.exitCode != 0) {
                val detail = (result.stderr.ifBlank { result.stdout }).trim().take(300)
                return fail(
                    "Could not render ${pdfFile.name}: the built-in renderer failed ($nativeError) and " +
                        "pdftoppm failed too (exit ${result.exitCode}): $detail",
                )
            }
        }
        val rendered = collectPdftoppmOutputs(outDir, baseName, singlePage = runs.singleOrNull()?.singleOrNull())
        if (rendered.isEmpty()) {
            return fail(
                "pdftoppm reported success but produced no PNG files in ${relativePath(outDir)}. " +
                    "No image was produced — nothing was faked.",
            )
        }
        val allNotes = notes.toMutableList()
        if (capped) allNotes += "Only the first $MAX_PAGES pages were rendered (limit $MAX_PAGES per call); call again with a narrower pages range for the rest."
        allNotes += "Rendered with pdftoppm in the Linux workspace — the built-in Android renderer could not render this PDF ($nativeError)."
        return ToolResult(ok = true, output = renderReport(pdfFile.name, dpi, rendered, allNotes))
    }

    /**
     * Collects the PNGs pdftoppm just wrote (`<base>-<n>.png`, or a
     * bare `<base>.png` when a single page was rendered), renames them
     * to the canonical `<base>-p<n>.png`, and verifies each one is a
     * real PNG with readable dimensions. Files that are not valid PNGs
     * are dropped, never reported as images.
     */
    private fun collectPdftoppmOutputs(outDir: File, baseName: String, singlePage: Int?): List<RenderedPage> {
        val numbered = Regex("^${Regex.escape(baseName)}-(\\d+)\\.png$")
        val bare = File(outDir, "$baseName.png")
        val found = mutableMapOf<Int, File>()
        for (file in outDir.listFiles().orEmpty()) {
            val match = numbered.matchEntire(file.name) ?: continue
            found[match.groupValues[1].toInt()] = file
        }
        if (found.isEmpty() && bare.isFile && singlePage != null) {
            // Single-page render: pdftoppm omits the page number.
            found[singlePage] = bare
        }
        return found.entries.sortedBy { it.key }.mapNotNull { (page, file) ->
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull null
            val dims = pngDimensions(bytes) ?: return@mapNotNull null
            val canonical = File(outDir, outputFileName("$baseName.pdf", page))
            if (canonical != file) runCatching { file.renameTo(canonical) }
            val final = if (canonical.isFile) canonical else file
            RenderedPage(
                relativePath = relativePath(final),
                page = page,
                width = dims.first,
                height = dims.second,
                bytes = final.length(),
                reduced = false,
            )
        }
    }

    private fun renderReport(
        pdfName: String,
        dpi: Int,
        rendered: List<RenderedPage>,
        notes: List<String>,
    ): String {
        val sb = StringBuilder()
        sb.append("Rendered ${rendered.size} page(s) of $pdfName at $dpi dpi:\n")
        for (page in rendered) {
            sb.append(page.relativePath)
                .append(" — page ").append(page.page)
                .append(", ").append(page.width).append('x').append(page.height).append(" px")
                .append(", ").append(page.bytes).append(" bytes\n")
        }
        for (note in notes) sb.append("Note: ").append(note).append('\n')
        sb.append("Read a page back with read_image using the path above.")
        return sb.toString().trimEnd()
    }

    private fun relativePath(file: File): String =
        file.canonicalFile.relativeTo(rootCanonical).invariantSeparatorsPath

    /** Guest path of a workspace file: the workspace is bound at /workspace. */
    private fun guestPath(file: File): String {
        val rel = file.canonicalFile.relativeTo(rootCanonical).invariantSeparatorsPath
        return if (rel.isEmpty()) "/workspace" else "/workspace/$rel"
    }

    private fun fail(message: String): ToolResult = ToolResult(ok = false, output = "", error = message)

    companion object {
        const val DEFAULT_DPI: Int = 150
        const val MIN_DPI: Int = 72
        const val MAX_DPI: Int = 300
        const val MAX_PAGES: Int = 20
        const val MAX_PIXELS: Int = 16_000_000
        const val DEFAULT_OUTPUT_DIR: String = "images"
    }
}

/** One rendered page: its workspace-relative PNG path and dimensions. */
internal data class RenderedPage(
    val relativePath: String,
    val page: Int,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val reduced: Boolean = false,
)

internal data class RenderedBatch(
    val pages: List<RenderedPage>,
    val capped: Boolean,
    val reduced: Boolean,
)

/** Marker for a password-protected PDF: no renderer here can open it. */
internal class PdfEncryptedException(message: String) : Exception(message)

/** DPI clamped to the sane range [PdfToImageTool.MIN_DPI]..[PdfToImageTool.MAX_DPI]. */
internal fun clampDpi(dpi: Int): Int = dpi.coerceIn(PdfToImageTool.MIN_DPI, PdfToImageTool.MAX_DPI)

/**
 * Parses a page selection without "all": "2", "1-3", "1,3,5".
 * Returns the sorted distinct 1-based page numbers, or null when
 * malformed or any page is < 1. No upper-bound check here — the caller
 * validates against the real page count ([expandPages]).
 */
internal fun parsePageNumbers(raw: String): List<Int>? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    val selected = sortedSetOf<Int>()
    for (part in value.split(",")) {
        val token = part.trim()
        if (token.isEmpty()) return null
        val range = token.split("-")
        when (range.size) {
            1 -> {
                val page = range[0].toIntOrNull() ?: return null
                if (page < 1) return null
                selected += page
            }
            2 -> {
                val start = range[0].trim().toIntOrNull() ?: return null
                val end = range[1].trim().toIntOrNull() ?: return null
                if (start < 1 || start > end) return null
                selected += (start..end).toList()
            }
            else -> return null
        }
    }
    return if (selected.isEmpty()) null else selected.toList()
}

/**
 * Expands a page selection against [pageCount]: "all" (any case) or
 * blank means every page; otherwise [parsePageNumbers] plus an
 * upper-bound check. Null when malformed or out of range. Same syntax
 * and semantics as [parsePdfPages] in PdfTools.kt.
 */
internal fun expandPages(raw: String?, pageCount: Int): List<Int>? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value.equals("all", ignoreCase = true)) return (1..pageCount).toList()
    val pages = parsePageNumbers(value) ?: return null
    if (pages.any { it > pageCount }) return null
    return pages
}

/** Bitmap size in pixels for a PDF page ([widthPt] x [heightPt] points) at [dpi]. */
internal data class PdfImageSize(val width: Int, val height: Int, val reduced: Boolean)

/**
 * Bitmap size for a PDF page at [dpi] (PDF points are 1/72 inch).
 * If the uncapped size would exceed [PdfToImageTool.MAX_PIXELS], the
 * dimensions are scaled down proportionally and [PdfImageSize.reduced]
 * is true, so a huge page cannot OOM a low-end phone.
 */
internal fun renderDimensions(widthPt: Double, heightPt: Double, dpi: Int): PdfImageSize {
    val scale = dpi / 72.0
    var width = (widthPt * scale).roundToInt().coerceAtLeast(1)
    var height = (heightPt * scale).roundToInt().coerceAtLeast(1)
    var reduced = false
    val pixels = width.toLong() * height.toLong()
    if (pixels > PdfToImageTool.MAX_PIXELS) {
        val shrink = kotlin.math.sqrt(PdfToImageTool.MAX_PIXELS.toDouble() / pixels)
        width = (width * shrink).roundToInt().coerceAtLeast(1)
        height = (height * shrink).roundToInt().coerceAtLeast(1)
        reduced = true
    }
    return PdfImageSize(width, height, reduced)
}

/** Canonical PNG name for [page] of [pdfName]: `<base>-p<page>.png`. */
internal fun outputFileName(pdfName: String, page: Int): String =
    "${File(pdfName).nameWithoutExtension}-p$page.png"

// NOTE: shellQuote lives in OcrTools.kt (same package) — do not
// redeclare it here; a duplicate top-level definition breaks the
// whole-tree compile.

/** Groups sorted page numbers into contiguous runs for pdftoppm `-f`/`-l`. */
internal fun contiguousRuns(pages: List<Int>): List<IntRange> {
    if (pages.isEmpty()) return emptyList()
    val runs = mutableListOf<IntRange>()
    var start = pages[0]
    var prev = pages[0]
    for (page in pages.drop(1)) {
        if (page == prev + 1) {
            prev = page
        } else {
            runs += start..prev
            start = page
            prev = page
        }
    }
    runs += start..prev
    return runs
}

/** True when [file] starts with the `%PDF-` magic. */
internal fun looksLikePdf(file: File): Boolean {
    if (!file.isFile) return false
    val header = ByteArray(5)
    val read = file.inputStream().use { it.read(header) }
    return read == 5 && header.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))
}

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
)

/** True when [bytes] start with the 8-byte PNG signature. */
internal fun isPng(bytes: ByteArray): Boolean =
    bytes.size >= PNG_SIGNATURE.size && bytes.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)

/**
 * Width x height from a PNG's IHDR chunk (bytes 16-23, big-endian),
 * or null when [bytes] are not a PNG or are too short. Pure and
 * JVM-testable: the PNG-output contract without needing Bitmap.
 */
internal fun pngDimensions(bytes: ByteArray): Pair<Int, Int>? {
    if (!isPng(bytes) || bytes.size < 24) return null
    // IHDR must be the first chunk: length (4) + "IHDR" (4) at bytes 8-15.
    if (String(bytes, 8 + 4, 4, Charsets.US_ASCII) != "IHDR") return null
    fun u32(offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    val width = u32(16)
    val height = u32(20)
    if (width <= 0 || height <= 0) return null
    return width to height
}

/** Minimal valid PNG bytes (signature + IHDR + IEND) for tests. */
internal fun minimalPngBytes(width: Int, height: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    out.write(PNG_SIGNATURE)
    fun u32(value: Int) {
        out.write(byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        ))
    }
    // IHDR: length 13, type, width, height, bit depth 8, colour type 2, compression 0, filter 0, interlace 0, CRC (zeros — readers here don't check it).
    u32(13)
    out.write("IHDR".toByteArray(Charsets.US_ASCII))
    u32(width)
    u32(height)
    out.write(byteArrayOf(8, 2, 0, 0, 0))
    u32(0)
    // IEND.
    u32(0)
    out.write("IEND".toByteArray(Charsets.US_ASCII))
    u32(0)
    return out.toByteArray()
}
