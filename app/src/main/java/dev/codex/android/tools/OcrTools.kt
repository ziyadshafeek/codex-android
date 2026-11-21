package dev.codex.android.tools

import dev.codex.android.linux.ShellResult
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OCR via Tesseract running inside the on-device Linux workspace
 * (Alpine rootfs, PRoot). Tesseract itself is NOT bundled in the APK:
 * it is installed in the rootfs on demand with
 * `RootfsManager.ensureToolchain("ocr")`, which runs
 * `apk add --no-cache tesseract-ocr poppler` (see TOOLCHAIN_PACKAGES
 * in linux/RootfsManager.kt). If Tesseract is missing, this tool fails
 * with the install instruction instead of inventing text.
 *
 * The tool never fabricates OCR output: it runs the real `tesseract`
 * binary, parses its TSV output, and reports the mean word confidence
 * alongside the recognised text. A scanned PDF page is rendered to a
 * PNG first with `pdftoppm` (poppler, installed by the same toolchain
 * entry); if `pdftoppm` is missing that fails plainly too.
 *
 * The guest shell is injected (`shell`), so tests can fake it, and the
 * host-to-guest path mapping is injected too: only files inside the
 * workspace root are visible to the guest (bound at /workspace), so a
 * file outside it cannot be OCR'd and fails with a plain error.
 */

/** Result of parsing Tesseract TSV output. */
internal data class OcrParseResult(
    val text: String,
    val meanConfidence: Double,
    val wordCount: Int,
)

/**
 * Parses `tesseract ... stdout tsv` output. Columns: level, page_num,
 * block_num, par_num, line_num, word_num, left, top, width, height,
 * conf, text. Word rows (level 5) with conf >= 0 carry the text; conf
 * -1 rows are punctuation/non-words and are excluded from the mean.
 * Returns null when no words were recognised.
 */
internal fun parseTesseractTsv(tsv: String): OcrParseResult? {
    val lines = tsv.lines()
    if (lines.isEmpty()) return null
    var currentLineKey: String? = null
    val textOut = StringBuilder()
    var confSum = 0.0
    var confCount = 0
    var wordCount = 0
    for (rawLine in lines) {
        if (rawLine.isBlank()) continue
        val cols = rawLine.split("\t")
        if (cols.size < 12) continue
        if (cols[0] == "level") continue // header row
        if (cols[0] != "5") continue // word rows only
        val conf = cols[10].toDoubleOrNull() ?: continue
        val word = cols[11]
        if (word.isBlank()) continue
        wordCount++
        val lineKey = "${cols[2]}:${cols[3]}:${cols[4]}"
        if (currentLineKey != null && lineKey != currentLineKey) textOut.append('\n')
        if (currentLineKey != null && lineKey == currentLineKey) textOut.append(' ')
        textOut.append(word)
        currentLineKey = lineKey
        if (conf >= 0) {
            confSum += conf
            confCount++
        }
    }
    if (wordCount == 0) return null
    return OcrParseResult(
        text = textOut.toString(),
        meanConfidence = if (confCount > 0) confSum / confCount else 0.0,
        wordCount = wordCount,
    )
}

/** Single-quote shell escaping for a guest path. */
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/** Language codes accepted for `-l`: `eng`, `eng+fra`, `deu`, … — anything else is rejected to block injection. */
internal fun validOcrLang(lang: String): Boolean =
    lang.matches(Regex("[a-z]{2,3}([+_][a-z]{2,3})*"))

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "tif", "tiff", "bmp", "webp")

class OcrImageTool(
    private val shell: suspend (String) -> ShellResult,
    /** Maps a host absolute path to its guest path (e.g. under /workspace), or null when unmappable. */
    private val toGuestPath: (String) -> String?,
) : Tool {
    override val name: String = "ocr_image"
    override val description: String =
        "Reads the text in an image (or a scanned PDF page) with Tesseract OCR running in the Linux workspace. " +
            "Returns the recognised text with a mean word-confidence note. " +
            "The file must be inside the workspace; Tesseract must be installed there (apk add tesseract-ocr) or the tool reports that instead of guessing."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Absolute path of the image (png/jpg/tif/bmp/webp) or PDF file, inside the workspace")
            }
            putJsonObject("page") {
                put("type", "integer")
                put("description", "For a PDF input: 1-based page to OCR (default 1)")
            }
            putJsonObject("lang") {
                put("type", "string")
                put("description", "Tesseract language(s), e.g. \"eng\" or \"eng+fra\" (default \"eng\")")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.pathArg() ?: return docFail("Missing required argument: path")
        val file = File(path)
        if (!file.isFile) return docFail("File not found: $path")
        val lang = args["lang"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { "eng" }
        if (!validOcrLang(lang)) {
            return docFail("Invalid lang \"$lang\". Use Tesseract language codes like \"eng\" or \"eng+fra\".")
        }
        val extension = file.name.substringAfterLast('.', "").lowercase()
        val isPdf = extension == "pdf"
        if (!isPdf && extension !in IMAGE_EXTENSIONS) {
            return docFail("Unsupported file type: .$extension. OCR works on images (png/jpg/tif/bmp/webp) and PDF pages.")
        }
        val guestPath = toGuestPath(path)
            ?: return docFail("This file is outside the workspace, so the Linux workspace cannot see it. Move it into the workspace first.")
        // 1. Tesseract must exist in the guest.
        val check = shell("command -v tesseract || echo MISSING")
        if (check.exitCode != 0) {
            return docFail("Could not check for Tesseract in the Linux workspace: ${check.stderr.trim().ifEmpty { "shell exited ${check.exitCode}" }}")
        }
        if (check.stdout.contains("MISSING")) {
            return docFail(
                "Tesseract is not installed in the Linux workspace, so no OCR was attempted. " +
                    "Install it from the Terminal tab with: apk add tesseract-ocr " +
                    "(or run the 'ocr' toolchain install), then try again.",
            )
        }
        // 2. Resolve the image to OCR: a PDF page is rendered first.
        val imageGuestPath: String
        if (isPdf) {
            val page = args["page"]?.jsonPrimitive?.intOrNull ?: 1
            if (page < 1) return docFail("page must be 1 or higher.")
            val rendered = renderPdfPage(guestPath, page)
                ?: return docFail(
                    "Could not render page $page of this PDF for OCR. " +
                        "The Linux workspace needs poppler for this: apk add poppler.",
                )
            imageGuestPath = rendered
        } else {
            imageGuestPath = guestPath
        }
        // 3. Run Tesseract in TSV mode so a real confidence can be reported.
        val result = shell(
            "tesseract ${shellQuote(imageGuestPath)} stdout --psm 3 -l $lang tsv",
        )
        if (result.exitCode != 0) {
            return docFail(
                "Tesseract failed (exit ${result.exitCode}): " +
                    result.stderr.trim().take(500).ifEmpty { "no error detail" },
            )
        }
        val parsed = parseTesseractTsv(result.stdout)
            ?: return docFail("Tesseract recognised no text in this image. It may be blank, handwritten, or in a language other than \"$lang\".")
        val confidence = parsed.meanConfidence
        val confidenceNote = when {
            confidence >= 85 -> "high confidence"
            confidence >= 70 -> "moderate confidence — check the text against the image"
            else -> "LOW confidence — the text below may contain errors; check it against the image"
        }
        return ToolResult(
            ok = true,
            output = parsed.text +
                "\n[OCR: ${parsed.wordCount} words, mean word confidence ${"%.0f".format(confidence)}% ($confidenceNote), Tesseract lang=$lang. " +
                "This is machine-read text, not a human transcription.]",
        )
    }

    /** Renders one PDF page to a PNG in the guest (/tmp) with pdftoppm; returns the guest PNG path or null. */
    private suspend fun renderPdfPage(guestPdfPath: String, page: Int): String? {
        val check = shell("command -v pdftoppm || echo MISSING")
        if (check.exitCode != 0 || check.stdout.contains("MISSING")) return null
        val prefix = "/tmp/ocr-page"
        val rendered = shell(
            "rm -f /tmp/ocr-page-*.png; pdftoppm -png -r 300 -f $page -l $page ${shellQuote(guestPdfPath)} $prefix",
        )
        if (rendered.exitCode != 0) return null
        val listed = shell("ls /tmp/ocr-page-*.png 2>/dev/null | head -1")
        if (listed.exitCode != 0) return null
        val png = listed.stdout.trim().lineSequence().firstOrNull().orEmpty()
        return png.ifEmpty { null }
    }
}

/** Convenience factory: builds an [OcrImageTool] whose guest paths are workspace-relative.
 * A host path under [workspaceRoot] maps to `/workspace/<relative>`,
 * anything else maps to null (unmappable). The [shell] is the same
 * `suspend (String) -> ShellResult` the `shell_exec` developer tool
 * uses (a ProotShellEngine shellExec bound to the workspace). */
internal fun ocrToolForWorkspace(
    shell: suspend (String) -> ShellResult,
    workspaceRoot: File,
): OcrImageTool {
    val root = workspaceRoot.canonicalFile
    return OcrImageTool(shell) { hostPath ->
        val file = File(hostPath).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) && file.path != root.path) null
        else "/workspace/" + file.relativeTo(root).invariantSeparatorsPath
    }
}
