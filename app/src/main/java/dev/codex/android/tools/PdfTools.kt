package dev.codex.android.tools

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * PDF manipulation tools, all via PdfBox-Android (already a dependency):
 * [PdfCreateTool], [PdfMergeTool], [PdfSplitTool], [PdfRotateTool] and
 * [PdfFormsTool].
 *
 * Design: five small tools instead of one `pdf_tool` with an `operation`
 * parameter. Each operation has a genuinely different argument shape
 * (a text body vs. a list of inputs vs. a page range vs. a field map),
 * so separate schemas keep the model from stuffing the wrong arguments
 * into a shared one, and each tool's description tells the model exactly
 * when to pick it. The shared helpers ([loadPdf], [parsePdfPages],
 * [savePdf]) live here so the tools stay thin.
 */

internal fun loadPdf(path: String): PDDocument? {
    val file = File(path)
    if (!file.isFile) return null
    return try {
        PDDocument.load(file)
    } catch (e: InvalidPasswordException) {
        null
    } catch (e: Exception) {
        null
    }
}

internal fun savePdf(doc: PDDocument, outputPath: String): ToolResult {
    return try {
        val out = File(outputPath)
        out.parentFile?.mkdirs()
        doc.save(out)
        ToolResult(ok = true, output = "Wrote ${out.absolutePath} (${out.length()} bytes)")
    } catch (e: Exception) {
        docFail("Could not save PDF: ${e.message ?: e.javaClass.simpleName}")
    }
}

/** Parses "all", "2", "1-3" or "1,3,5" against [pageCount]; null when malformed/out of range. */
internal fun parsePdfPages(raw: String?, pageCount: Int): List<Int>? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value.equals("all", ignoreCase = true)) return (1..pageCount).toList()
    val selected = sortedSetOf<Int>()
    for (part in value.split(",")) {
        val token = part.trim()
        if (token.isEmpty()) return null
        val range = token.split("-")
        when (range.size) {
            1 -> {
                val page = range[0].toIntOrNull() ?: return null
                if (page < 1 || page > pageCount) return null
                selected += page
            }
            2 -> {
                val start = range[0].trim().toIntOrNull() ?: return null
                val end = range[1].trim().toIntOrNull() ?: return null
                if (start < 1 || end > pageCount || start > end) return null
                selected += (start..end).toList()
            }
            else -> return null
        }
    }
    return if (selected.isEmpty()) null else selected.toList()
}

private fun pdfArgsSchema(
    vararg properties: Triple<String, String, String>,
    required: List<String>,
): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        for ((name, type, description) in properties) {
            putJsonObject(name) {
                put("type", type)
                put("description", description)
            }
        }
    }
    putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
}

// ---------------------------------------------------------------------------
// pdf_create
// ---------------------------------------------------------------------------

/**
 * Creates a PDF from plain text (or Markdown-ish text: `# ` headings,
 * blank lines separate paragraphs). One A4 page per chunk of text;
 * long lines wrap at ~95 characters. No images — a text-only PDF.
 */
object PdfCreateTool : Tool {
    override val name: String = "pdf_create"
    override val description: String =
        "Creates a PDF file from plain text. Headings use '# ' lines; blank lines separate paragraphs. " +
            "Use for reports, letters, and notes the user wants as a PDF."
    override val parametersSchema: JsonObject = pdfArgsSchema(
        Triple("output_path", "string", "Absolute path to write, ending in .pdf"),
        Triple("content", "string", "The text to put in the PDF ('# ' lines become headings)"),
        Triple("title", "string", "Title stored in the PDF metadata (optional)"),
        required = listOf("output_path", "content"),
    )
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val outputPath = args["output_path"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return docFail("Missing required argument: output_path")
        val content = args["content"]?.jsonPrimitive?.contentOrNull
            ?: return docFail("Missing required argument: content")
        if (!outputPath.endsWith(".pdf", ignoreCase = true)) {
            return docFail("output_path must end in .pdf")
        }
        return try {
            PDDocument().use { doc ->
                val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (title.isNotEmpty()) doc.documentInformation.title = title
                writeTextToPdf(doc, content)
                savePdf(doc, outputPath)
            }
        } catch (e: Exception) {
            docFail("Could not create PDF: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

/** Writes [content] into [doc] as wrapped A4 pages (headings at 16pt bold, body at 11pt). */
internal fun writeTextToPdf(doc: PDDocument, content: String) {
    val font = PDType1Font.HELVETICA
    val boldFont = PDType1Font.HELVETICA_BOLD
    val margin = 54f
    val pageWidth = PDRectangle.A4.width
    val pageHeight = PDRectangle.A4.height
    val bodySize = 11f
    val headingSize = 16f
    val leading = 15f
    var page = PDPage(PDRectangle.A4)
    doc.addPage(page)
    var stream = PDPageContentStream(doc, page)
    var y = pageHeight - margin
    fun newPage() {
        stream.close()
        page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        stream = PDPageContentStream(doc, page)
        y = pageHeight - margin
    }
    fun ensureSpace(needed: Float) {
        if (y - needed < margin) newPage()
    }
    for (rawLine in content.lines()) {
        val line = rawLine.trimEnd()
        if (line.isBlank()) {
            y -= leading / 2
            continue
        }
        val isHeading = line.startsWith("# ")
        val text = if (isHeading) line.removePrefix("# ").trim() else line
        val useFont = if (isHeading) boldFont else font
        val size = if (isHeading) headingSize else bodySize
        val maxWidth = pageWidth - 2 * margin
        val wrapped = wrapLine(text, useFont, size, maxWidth)
        for (wrappedLine in wrapped) {
            ensureSpace(leading)
            stream.beginText()
            stream.setFont(useFont, size)
            stream.newLineAtOffset(margin, y)
            stream.showText(wrappedLine)
            stream.endText()
            y -= if (isHeading) leading + 6f else leading
        }
        if (isHeading) y -= 4f
    }
    stream.close()
}

/** Greedy word wrap for one line, measured with the real font metrics. */
internal fun wrapLine(
    text: String,
    font: PDType1Font,
    size: Float,
    maxWidth: Float,
): List<String> {
    val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return listOf("")
    val lines = mutableListOf<String>()
    var current = StringBuilder()
    for (word in words) {
        val candidate = if (current.isEmpty()) word else "$current $word"
        val width = font.getStringWidth(candidate) / 1000f * size
        if (width <= maxWidth || current.isEmpty()) {
            current = StringBuilder(candidate)
        } else {
            lines += current.toString()
            current = StringBuilder(word)
        }
    }
    if (current.isNotEmpty()) lines += current.toString()
    return lines
}

// ---------------------------------------------------------------------------
// pdf_merge
// ---------------------------------------------------------------------------

/**
 * Merges two or more PDFs into one, in the order given. Encrypted
 * inputs are rejected with a plain error; the output path must end
 * in .pdf.
 */
object PdfMergeTool : Tool {
    override val name: String = "pdf_merge"
    override val description: String =
        "Merges two or more PDF files into a single PDF, in the order given."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("inputs") {
                put("type", "array")
                put("description", "Absolute paths of the PDF files to merge, in order (at least 2)")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("output_path") {
                put("type", "string")
                put("description", "Absolute path to write, ending in .pdf")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("inputs")); add(JsonPrimitive("output_path")) }
    }
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val inputs = args["inputs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() } ?: emptyList()
        val outputPath = args["output_path"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return docFail("Missing required argument: output_path")
        if (inputs.size < 2) return docFail("pdf_merge needs at least 2 input PDFs.")
        if (!outputPath.endsWith(".pdf", ignoreCase = true)) {
            return docFail("output_path must end in .pdf")
        }
        for (input in inputs) {
            if (!File(input).isFile) return docFail("File not found: $input")
        }
        return try {
            val merger = PDFMergerUtility()
            for (input in inputs) merger.addSource(File(input))
            val out = File(outputPath)
            out.parentFile?.mkdirs()
            merger.destinationFileName = out.absolutePath
            merger.mergeDocuments()
            ToolResult(ok = true, output = "Merged ${inputs.size} PDFs into ${out.absolutePath} (${out.length()} bytes)")
        } catch (e: InvalidPasswordException) {
            docFail("One of the input PDFs is encrypted and cannot be merged.")
        } catch (e: Exception) {
            docFail("Could not merge PDFs: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

// ---------------------------------------------------------------------------
// pdf_split
// ---------------------------------------------------------------------------

/**
 * Splits a PDF by page range. With `per_page` false (default) it writes
 * one PDF containing the requested pages; with `per_page` true it writes
 * one PDF per page into `output_dir` named `<base>-p<N>.pdf`.
 */
object PdfSplitTool : Tool {
    override val name: String = "pdf_split"
    override val description: String =
        "Splits a PDF by page range. Default writes one PDF with the requested pages; with per_page true, writes one PDF per page into output_dir."
    override val parametersSchema: JsonObject = pdfArgsSchema(
        Triple("path", "string", "Absolute path of the PDF to split"),
        Triple("pages", "string", "Pages to keep: \"all\", \"2\", a range like \"1-3\", or a list like \"1,3,5\" (default \"all\")"),
        Triple("output_path", "string", "Absolute path to write when per_page is false, ending in .pdf"),
        Triple("output_dir", "string", "Directory to write per-page PDFs into when per_page is true"),
        Triple("per_page", "boolean", "When true, write one PDF per page instead of a single PDF"),
        required = listOf("path"),
    )
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return docFail("Missing required argument: path")
        val file = File(path)
        if (!file.isFile) return docFail("File not found: $path")
        val perPage = args["per_page"]?.jsonPrimitive?.booleanOrNull ?: false
        return try {
            PDDocument.load(file).use { doc ->
                if (doc.isEncrypted) return docFail("This PDF is encrypted and cannot be split.")
                val pageCount = doc.numberOfPages
                val pages = parsePdfPages(args["pages"]?.jsonPrimitive?.contentOrNull, pageCount)
                    ?: return docFail("Invalid pages selection for a $pageCount-page PDF.")
                if (perPage) {
                    val dir = File(
                        args["output_dir"]?.jsonPrimitive?.contentOrNull?.trim()
                            ?: return docFail("per_page needs output_dir."),
                    ).apply { mkdirs() }
                    val base = file.nameWithoutExtension
                    val written = mutableListOf<String>()
                    for (pageNumber in pages) {
                        val single = PDDocument()
                        try {
                            single.importPage(doc.getPage(pageNumber - 1))
                            val out = File(dir, "$base-p$pageNumber.pdf")
                            single.save(out)
                            written += out.absolutePath
                        } finally {
                            single.close()
                        }
                    }
                    ToolResult(ok = true, output = "Split into ${written.size} file(s):\n" + written.joinToString("\n"))
                } else {
                    val outputPath = args["output_path"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: return docFail("Missing required argument: output_path (or set per_page true with output_dir).")
                    if (!outputPath.endsWith(".pdf", ignoreCase = true)) {
                        return docFail("output_path must end in .pdf")
                    }
                    val out = PDDocument()
                    try {
                        for (pageNumber in pages) out.importPage(doc.getPage(pageNumber - 1))
                        savePdf(out, outputPath)
                    } finally {
                        out.close()
                    }
                }
            }
        } catch (e: InvalidPasswordException) {
            docFail("This PDF is encrypted and cannot be split.")
        } catch (e: Exception) {
            docFail("Could not split PDF: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

// ---------------------------------------------------------------------------
// pdf_rotate
// ---------------------------------------------------------------------------

/**
 * Rotates pages of a PDF by 90, 180 or 270 degrees (clockwise) and
 * writes the result to a new file. The input file is never modified.
 */
object PdfRotateTool : Tool {
    override val name: String = "pdf_rotate"
    override val description: String =
        "Rotates pages of a PDF by 90, 180 or 270 degrees clockwise and writes the result to a new file. The input file is never modified."
    override val parametersSchema: JsonObject = pdfArgsSchema(
        Triple("path", "string", "Absolute path of the PDF to rotate"),
        Triple("pages", "string", "Pages to rotate: \"all\", \"2\", a range like \"1-3\", or a list like \"1,3,5\" (default \"all\")"),
        Triple("degrees", "integer", "Rotation in degrees clockwise: 90, 180 or 270"),
        Triple("output_path", "string", "Absolute path to write, ending in .pdf"),
        required = listOf("path", "degrees", "output_path"),
    )
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return docFail("Missing required argument: path")
        val file = File(path)
        if (!file.isFile) return docFail("File not found: $path")
        val degrees = args["degrees"]?.jsonPrimitive?.intOrNull
            ?: return docFail("Missing required argument: degrees")
        if (degrees !in setOf(90, 180, 270)) {
            return docFail("degrees must be 90, 180 or 270.")
        }
        val outputPath = args["output_path"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return docFail("Missing required argument: output_path")
        if (!outputPath.endsWith(".pdf", ignoreCase = true)) {
            return docFail("output_path must end in .pdf")
        }
        return try {
            PDDocument.load(file).use { doc ->
                if (doc.isEncrypted) return docFail("This PDF is encrypted and cannot be rotated.")
                val pages = parsePdfPages(args["pages"]?.jsonPrimitive?.contentOrNull, doc.numberOfPages)
                    ?: return docFail("Invalid pages selection for a ${doc.numberOfPages}-page PDF.")
                for (pageNumber in pages) {
                    val page = doc.getPage(pageNumber - 1)
                    page.rotation = (page.rotation + degrees) % 360
                }
                savePdf(doc, outputPath)
            }
        } catch (e: InvalidPasswordException) {
            docFail("This PDF is encrypted and cannot be rotated.")
        } catch (e: Exception) {
            docFail("Could not rotate PDF: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

// ---------------------------------------------------------------------------
// pdf_forms
// ---------------------------------------------------------------------------

/**
 * Reads and writes AcroForm fields of a PDF. `operation` is `list`
 * (returns every field name and its current value), `get` (one field),
 * or `set` (writes the fields in `fields` and saves to `output_path`).
 * Only text fields are settable; choice/button fields are reported
 * by `list`/`get` but `set` on them fails plainly. The input file is
 * never modified — `set` always writes a new file.
 */
object PdfFormsTool : Tool {
    override val name: String = "pdf_forms"
    override val description: String =
        "Reads or writes PDF form (AcroForm) fields. Operation 'list' shows every field and value, 'get' reads one field, 'set' writes the given text fields to a new PDF."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Absolute path of the PDF with the form")
            }
            putJsonObject("operation") {
                put("type", "string")
                put("description", "One of: list, get, set")
            }
            putJsonObject("field") {
                put("type", "string")
                put("description", "Field name for the get operation")
            }
            putJsonObject("fields") {
                put("type", "object")
                put("description", "Field name to value map for the set operation")
            }
            putJsonObject("output_path") {
                put("type", "string")
                put("description", "Absolute path to write for the set operation, ending in .pdf")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("operation")) }
    }
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return docFail("Missing required argument: path")
        val file = File(path)
        if (!file.isFile) return docFail("File not found: $path")
        val operation = args["operation"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return docFail("Missing required argument: operation")
        return try {
            PDDocument.load(file).use { doc ->
                if (doc.isEncrypted) return docFail("This PDF is encrypted and its form cannot be read.")
                val form = doc.documentCatalog.acroForm
                    ?: return docFail("This PDF has no form fields.")
                when (operation) {
                    "list" -> ToolResult(ok = true, output = listFields(form))
                    "get" -> {
                        val fieldName = args["field"]?.jsonPrimitive?.contentOrNull?.trim()
                            ?: return docFail("get needs a 'field' name.")
                        val field = form.getField(fieldName)
                            ?: return docFail("No form field named \"$fieldName\".")
                        ToolResult(ok = true, output = "$fieldName: ${field.valueAsString}")
                    }
                    "set" -> {
                        val fields = args["fields"]?.jsonObject
                            ?: return docFail("set needs a 'fields' object mapping field names to values.")
                        val outputPath = args["output_path"]?.jsonPrimitive?.contentOrNull?.trim()
                            ?: return docFail("set needs an 'output_path' to write the filled PDF to.")
                        if (!outputPath.endsWith(".pdf", ignoreCase = true)) {
                            return docFail("output_path must end in .pdf")
                        }
                        val set = mutableListOf<String>()
                        for ((name, value) in fields) {
                            val field = form.getField(name)
                                ?: return docFail("No form field named \"$name\".")
                            if (field !is PDTextField) {
                                return docFail("Field \"$name\" is not a text field and cannot be set here.")
                            }
                            field.setValue(value.jsonPrimitive.contentOrNull.orEmpty())
                            set += name
                        }
                        savePdf(doc, outputPath).let { result ->
                            if (result.ok) ToolResult(ok = true, output = "Set ${set.size} field(s) (${set.joinToString(", ")}) and wrote ${File(outputPath).absolutePath}")
                            else result
                        }
                    }
                    else -> docFail("Unsupported operation: $operation. Use list, get or set.")
                }
            }
        } catch (e: InvalidPasswordException) {
            docFail("This PDF is encrypted and its form cannot be read.")
        } catch (e: Exception) {
            docFail("Could not read/write form fields: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun listFields(form: PDAcroForm): String {
        // fieldTree flattens the whole tree, so every field (including
        // nested ones, whose fullyQualifiedName already includes parents)
        // is listed exactly once.
        val lines = form.fieldTree.map { field ->
            "${field.fullyQualifiedName} (${field.fieldType}): ${field.valueAsString}"
        }
        if (lines.isEmpty()) return "This PDF has a form but no fields."
        return ("Form fields:\n" + lines.joinToString("\n"))
    }
}

/** Convenience: every PDF tool this file defines, in registration order. */
internal val PDF_TOOLS: List<Tool> = listOf(
    PdfCreateTool, PdfMergeTool, PdfSplitTool, PdfRotateTool, PdfFormsTool,
)
