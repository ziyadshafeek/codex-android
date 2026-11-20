package dev.codex.android.tools

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Standalone document readers: [ReadPdfTool], [ReadDocumentTool] and
 * [ReadTextFileTool]. These read files by absolute path in the workspace
 * or on device storage; the attachment-scoped reader is `attachment_read`
 * (attachments/AttachmentReadTool.kt) and is untouched by this file.
 *
 * Reading uses only the standard library plus PdfBox-Android:
 * Office Open XML files (.docx/.xlsx/.pptx) are ZIP archives of XML, so
 * java.util.zip plus a namespace-aware XML parser is enough. No Apache
 * POI is used (see DocumentWriteTools.kt for why) and nothing here
 * touches images, fonts or java.awt.
 *
 * The read helpers ([readDocxText], [readXlsxText], [readPptxText],
 * [readPdfText]) are `internal` so the document writers and the skills
 * tests can reuse them for round-trip checks.
 */

internal const val MAX_DOCUMENT_CHARS = 500_000
private const val MAX_TEXT_FILE_CHARS = 100_000
private const val MAX_TEXT_FILE_BYTES = 2 * 1024 * 1024
private const val MAX_ZIP_ENTRY_BYTES = 10_000_000

internal fun docFail(message: String): ToolResult = ToolResult(ok = false, output = "", error = message)

internal fun truncateWithNote(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    return text.take(maxChars) + "\n[truncated: output capped at $maxChars characters]"
}

internal fun JsonObject.pathArg(): String? =
    this["path"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

internal fun JsonObject.existingFile(): File? {
    val path = pathArg() ?: return null
    val file = File(path)
    return if (file.isFile) file else null
}

/**
 * Parses a `pages` argument such as "all", "2", "1-3" or "1,3,5" into
 * the 1-based page numbers to read, or null when the value is malformed.
 * A legacy integer `page` argument (0 = all) is honoured when `pages`
 * is absent.
 */
internal fun parsePageSelection(args: JsonObject, pageCount: Int): List<Int>? {
    val pagesRaw = args["pages"]?.jsonPrimitive?.contentOrNull?.trim()
    if (pagesRaw != null) {
        if (pagesRaw.equals("all", ignoreCase = true)) return (1..pageCount).toList()
        val selected = sortedSetOf<Int>()
        for (part in pagesRaw.split(",")) {
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
    val page = args["page"]?.jsonPrimitive?.intOrNull ?: 0
    if (page < 0 || page > pageCount) return null
    return if (page == 0) (1..pageCount).toList() else listOf(page)
}

// ---------------------------------------------------------------------------
// read_pdf
// ---------------------------------------------------------------------------

/**
 * Reads a PDF file and returns its page count, metadata (title/author
 * when present), and the text of each requested page, labelled
 * `[p. N]` so the caller can cite pages.
 *
 * Text extraction uses PdfBox-Android. On the phone the app must call
 * `PDFBoxResourceLoader.init(context)` once at startup so the font
 * tables bundled in the library's assets are available; when that step
 * was missed, extraction falls back to reading the literal text strings
 * out of each page's content stream, which covers simple PDFs.
 * A PDF with no extractable text on any requested page (typically a
 * scanned, image-only PDF) fails with a plain statement instead of
 * invented text — run `ocr_image` on such a page instead.
 */
object ReadPdfTool : Tool {
    override val name: String = "read_pdf"
    override val description: String =
        "Reads a PDF file and returns its page count, metadata, and the text of each page, labelled [p. N]. " +
            "Use pages \"all\", \"2\", or a range like \"1-3\". Encrypted PDFs cannot be read; scanned (image-only) PDFs have no text layer and are reported as such."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Absolute path of the PDF file to read")
            }
            putJsonObject("pages") {
                put("type", "string")
                put("description", "Pages to read: \"all\" (default), a single page like \"2\", a range like \"1-3\", or a list like \"1,3,5\"")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.pathArg() ?: return docFail("Missing required argument: path")
        val file = File(path)
        if (!file.isFile) return docFail("File not found: $path")
        return try {
            readPdfText(file, args)
        } catch (e: InvalidPasswordException) {
            docFail("This PDF is encrypted and cannot be read.")
        } catch (e: Exception) {
            docFail("Could not read PDF: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

/** Reads [file] as a PDF and formats the result for [ReadPdfTool]. */
internal fun readPdfText(file: File, args: JsonObject): ToolResult {
    PDDocument.load(file).use { doc ->
        if (doc.isEncrypted) {
            return docFail("This PDF is encrypted and cannot be read.")
        }
        val pageCount = doc.numberOfPages
        val pages = parsePageSelection(args, pageCount)
            ?: return docFail("Invalid pages selection for a $pageCount-page PDF. Use \"all\", a page number, a range like \"1-3\", or a list like \"1,3,5\".")
        val info = doc.documentInformation
        val out = StringBuilder()
        out.appendLine("PDF: ${file.name}")
        out.appendLine("Pages: $pageCount")
        val title = info?.title?.trim().orEmpty()
        if (title.isNotEmpty()) out.appendLine("Title: $title")
        val author = info?.author?.trim().orEmpty()
        if (author.isNotEmpty()) out.appendLine("Author: $author")
        out.appendLine()
        var usedFallback = false
        var anyText = false
        for (pageNumber in pages) {
            val (text, fallbackUsed) = extractPageText(doc, pageNumber)
            if (fallbackUsed) usedFallback = true
            if (text.isNotBlank()) anyText = true
            out.appendLine("[p. $pageNumber]")
            out.appendLine(text.trimEnd())
        }
        if (!anyText) {
            return docFail(
                "No text found in the requested page(s) — this PDF looks scanned (image-only). " +
                    "No text was invented. Use ocr_image on the page image to read it.",
            )
        }
        if (usedFallback) {
            out.appendLine(
                "[Note: the full text extractor was unavailable, so the text above " +
                    "was read with a basic reader; some characters or layout may be missing.]",
            )
        }
        return ToolResult(ok = true, output = truncateWithNote(out.toString().trimEnd(), MAX_DOCUMENT_CHARS))
    }
}

/** Extracts one page's text with PDFTextStripper, plus whether the basic reader was used. */
internal fun extractPageText(doc: PDDocument, pageNumber: Int): Pair<String, Boolean> {
    return try {
        val stripper = PDFTextStripper()
        stripper.startPage = pageNumber
        stripper.endPage = pageNumber
        stripper.getText(doc) to false
    } catch (t: Throwable) {
        extractPageTextBasic(doc, pageNumber) to true
    }
}

/**
 * Basic reader used only when PDFTextStripper cannot run (for example
 * when the font tables were not initialised). Reads the literal text
 * strings out of the page's content stream. It only returns strings
 * that are actually present in the file; nothing is guessed.
 */
internal fun extractPageTextBasic(doc: PDDocument, pageNumber: Int): String {
    val pages = doc.pages.toList()
    val page = pages.getOrNull(pageNumber - 1) ?: return ""
    val stream: InputStream = try {
        page.contents ?: return ""
    } catch (e: Exception) {
        return ""
    }
    val bytes = stream.use { it.readBytes() }
    val content = String(bytes, Charsets.ISO_8859_1)
    val found = StringBuilder()
    val tjPattern = Regex("\\(((?:\\\\.|[^()\\\\])*)\\)\\s*Tj")
    for (match in tjPattern.findAll(content)) {
        found.append(unescapePdfLiteral(match.groupValues[1]))
    }
    val tjArrayPattern = Regex("\\[[^\\]]*\\]\\s*TJ")
    for (match in tjArrayPattern.findAll(content)) {
        val literalPattern = Regex("\\(((?:\\\\.|[^()\\\\])*)\\)")
        for (literal in literalPattern.findAll(match.value)) {
            found.append(unescapePdfLiteral(literal.groupValues[1]))
        }
    }
    return found.toString()
}

internal fun unescapePdfLiteral(raw: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c != '\\' || i + 1 >= raw.length) {
            out.append(c)
            i++
            continue
        }
        when (val next = raw[i + 1]) {
            'n' -> { out.append('\n'); i += 2 }
            'r' -> { out.append('\r'); i += 2 }
            't' -> { out.append('\t'); i += 2 }
            'b' -> { out.append('\b'); i += 2 }
            'f' -> { out.append('\u000C'); i += 2 }
            '(', ')', '\\' -> { out.append(next); i += 2 }
            in '0'..'7' -> {
                var end = i + 1
                var value = 0
                while (end < raw.length && end < i + 4 && raw[end] in '0'..'7') {
                    value = value * 8 + (raw[end] - '0')
                    end++
                }
                out.append(value.toChar())
                i = end
            }
            else -> { out.append(next); i += 2 }
        }
    }
    return out.toString()
}

// ---------------------------------------------------------------------------
// read_document (.docx / .xlsx / .pptx)
// ---------------------------------------------------------------------------

/**
 * Reads Word, Excel, or PowerPoint files (.docx, .xlsx, .pptx) and
 * returns their text: docx paragraphs with `[Table]` blocks, xlsx rows
 * under `[Sheet: name]` headings, pptx text under `[Slide N]`
 * headings. Legacy binary formats (.doc/.xls/.ppt) and encrypted
 * (OLE2) files are rejected with a plain error, never guessed at.
 */
object ReadDocumentTool : Tool {
    override val name: String = "read_document"
    override val description: String =
        "Reads a Word, Excel, or PowerPoint file (.docx, .xlsx, .pptx) and returns its text, tables, sheets, or slides. " +
            "Legacy .doc/.xls/.ppt files and encrypted files are not supported."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Absolute path of the .docx, .xlsx, or .pptx file to read")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.pathArg() ?: return docFail("Missing required argument: path")
        val file = File(path)
        if (!file.isFile) return docFail("File not found: $path")
        val extension = file.name.substringAfterLast('.', "").lowercase()
        return try {
            when (extension) {
                "docx", "xlsx", "pptx" -> readOfficeFile(file, extension)
                "doc", "xls", "ppt" -> docFail(
                    "The older binary Office format (.$extension) is not supported. " +
                        "Provide the file as .docx, .xlsx, or .pptx instead.",
                )
                else -> docFail(
                    "Unsupported file type: .$extension. " +
                        "Supported types are .docx, .xlsx, and .pptx.",
                )
            }
        } catch (e: Exception) {
            docFail("Could not read document: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

private fun readOfficeFile(file: File, extension: String): ToolResult {
    val header = ByteArray(4)
    file.inputStream().use { it.read(header) }
    if (header[0] == 0xD0.toByte() && header[1] == 0xCF.toByte() &&
        header[2] == 0x11.toByte() && header[3] == 0xE0.toByte()
    ) {
        return docFail(
            "This file is encrypted or in the older binary Office format " +
                "and cannot be read. Provide an unencrypted .docx, .xlsx, or .pptx file.",
        )
    }
    if (!(header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte())) {
        return docFail(
            "This file is not a valid Office document " +
                "(it is not a .docx, .xlsx, or .pptx file).",
        )
    }
    ZipFile(file).use { zip ->
        val text = when (extension) {
            "docx" -> readDocxText(zip)
            "xlsx" -> readXlsxText(zip)
            else -> readPptxText(zip)
        }
        if (text.isBlank()) {
            return docFail("No text found in this document.")
        }
        return ToolResult(ok = true, output = truncateWithNote(text, MAX_DOCUMENT_CHARS))
    }
}

/** Docx paragraphs in order, tables as `[Table]` blocks with ` | `-joined cells. */
internal fun readDocxText(zip: ZipFile): String {
    val bytes = readZipEntry(zip, "word/document.xml") ?: return ""
    val doc = parseOfficeXml(bytes)
    val body = firstDescendant(doc.documentElement, "body") ?: return ""
    val out = StringBuilder()
    for (child in childElements(body)) {
        when (localName(child)) {
            "p" -> {
                val text = paragraphText(child)
                if (text.isNotBlank()) out.appendLine(text)
            }
            "tbl" -> {
                out.appendLine("[Table]")
                for (row in childElements(child, "tr")) {
                    val cells = childElements(row, "tc").map { cellText(it) }
                    out.appendLine(cells.joinToString(" | "))
                }
            }
        }
    }
    return out.toString().trimEnd()
}

/** Xlsx rows per sheet, each sheet headed `[Sheet: name]`, cells joined with ` | `. */
internal fun readXlsxText(zip: ZipFile): String {
    val sharedStrings = readSharedStrings(zip)
    val sheets = readWorkbookSheets(zip)
    val out = StringBuilder()
    for ((sheetName, sheetPath) in sheets) {
        val bytes = readZipEntry(zip, sheetPath) ?: continue
        val doc = parseOfficeXml(bytes)
        out.appendLine("[Sheet: $sheetName]")
        val rows = doc.documentElement.getElementsByTagNameNS("*", "row")
        for (i in 0 until rows.length) {
            val row = rows.item(i) as? Element ?: continue
            val values = mutableListOf<String>()
            for (cell in childElements(row, "c")) {
                values += cellValue(cell, sharedStrings)
            }
            if (values.any { it.isNotBlank() }) {
                out.appendLine(values.joinToString(" | "))
            }
        }
    }
    return out.toString().trimEnd()
}

private fun readSharedStrings(zip: ZipFile): List<String> {
    val bytes = readZipEntry(zip, "xl/sharedStrings.xml") ?: return emptyList()
    val doc = parseOfficeXml(bytes)
    val result = mutableListOf<String>()
    val items = doc.documentElement.getElementsByTagNameNS("*", "si")
    for (i in 0 until items.length) {
        val item = items.item(i) as? Element ?: continue
        result += runText(item)
    }
    return result
}

private fun readWorkbookSheets(zip: ZipFile): List<Pair<String, String>> {
    val workbookBytes = readZipEntry(zip, "xl/workbook.xml") ?: return emptyList()
    val relsBytes = readZipEntry(zip, "xl/_rels/workbook.xml.rels")
    val targets = mutableMapOf<String, String>()
    if (relsBytes != null) {
        val relsDoc = parseOfficeXml(relsBytes)
        val rels = relsDoc.documentElement.getElementsByTagNameNS("*", "Relationship")
        for (i in 0 until rels.length) {
            val rel = rels.item(i) as? Element ?: continue
            val id = rel.getAttribute("Id")
            val target = rel.getAttribute("Target")
            if (id.isNotEmpty() && target.isNotEmpty()) {
                targets[id] = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
            }
        }
    }
    val workbook = parseOfficeXml(workbookBytes)
    val sheets = mutableListOf<Pair<String, String>>()
    val sheetElements = workbook.documentElement.getElementsByTagNameNS("*", "sheet")
    for (i in 0 until sheetElements.length) {
        val sheet = sheetElements.item(i) as? Element ?: continue
        val name = sheet.getAttribute("name")
        val relId = sheet.getAttributeNS(
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
            "id",
        )
        val target = targets[relId] ?: continue
        if (name.isNotEmpty()) sheets += name to target
    }
    return sheets
}

private fun cellValue(cell: Element, sharedStrings: List<String>): String {
    return when (cell.getAttribute("t")) {
        "s" -> {
            val index = childText(cell, "v").toIntOrNull()
            if (index != null) sharedStrings.getOrNull(index).orEmpty() else ""
        }
        "inlineStr" -> {
            val inline = firstDescendant(cell, "is")
            if (inline != null) runText(inline) else ""
        }
        "b" -> if (childText(cell, "v") == "1") "TRUE" else "FALSE"
        else -> childText(cell, "v")
    }
}

/** Pptx text per slide, each slide headed `[Slide N]`. */
internal fun readPptxText(zip: ZipFile): String {
    val slideNames = zip.entries().asSequence()
        .map { it.name }
        .filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
        .sortedBy { it.substringAfter("slide").substringBefore(".xml").toIntOrNull() ?: 0 }
        .toList()
    if (slideNames.isEmpty()) return ""
    val out = StringBuilder()
    for ((index, slideName) in slideNames.withIndex()) {
        val bytes = readZipEntry(zip, slideName) ?: continue
        val doc = parseOfficeXml(bytes)
        out.appendLine("[Slide ${index + 1}]")
        val paragraphs = doc.documentElement.getElementsByTagNameNS("*", "p")
        for (i in 0 until paragraphs.length) {
            val paragraph = paragraphs.item(i) as? Element ?: continue
            val text = runText(paragraph)
            if (text.isNotBlank()) out.appendLine(text)
        }
    }
    return out.toString().trimEnd()
}

// --- shared Office XML helpers ------------------------------------------------

/** Text of a Word paragraph: the text runs, with tabs and line breaks kept. */
internal fun paragraphText(paragraph: Element): String {
    val out = StringBuilder()
    walkTextNodes(paragraph, out)
    return out.toString()
}

/** Text of a table cell: paragraphs joined with a space. */
internal fun cellText(cell: Element): String {
    return childElements(cell, "p").map { paragraphText(it) }.joinToString(" ")
}

/** Concatenates every text run ("t" element) under [element]. */
internal fun runText(element: Element): String {
    val out = StringBuilder()
    walkTextNodes(element, out)
    return out.toString()
}

internal fun walkTextNodes(node: Node, out: StringBuilder) {
    val children = node.childNodes
    for (i in 0 until children.length) {
        val child = children.item(i)
        when {
            child is Element && localName(child) == "t" -> out.append(child.textContent)
            child is Element && localName(child) == "tab" -> out.append('\t')
            child is Element && localName(child) == "br" -> out.append('\n')
            child is Element -> walkTextNodes(child, out)
        }
    }
}

internal fun readZipEntry(zip: ZipFile, name: String): ByteArray? {
    val entry = zip.getEntry(name) ?: return null
    if (entry.size > MAX_ZIP_ENTRY_BYTES) return null
    val input = zip.getInputStream(entry) ?: return null
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    input.use {
        while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ZIP_ENTRY_BYTES) return null
            out.write(buffer, 0, read)
        }
    }
    return out.toByteArray()
}

internal fun parseOfficeXml(bytes: ByteArray): Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    setXmlFeatureQuietly(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
    setXmlFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false)
    setXmlFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false)
    setXmlFeatureQuietly(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    factory.isXIncludeAware = false
    factory.isExpandEntityReferences = false
    return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
}

private fun setXmlFeatureQuietly(factory: DocumentBuilderFactory, name: String, value: Boolean) {
    try {
        factory.setFeature(name, value)
    } catch (e: Exception) {
        // Feature unsupported by this parser; the remaining hardening still applies.
    }
}

internal fun localName(node: Node): String =
    node.localName ?: node.nodeName.substringAfter(':')

internal fun childElements(parent: Element, local: String? = null): List<Element> {
    val result = mutableListOf<Element>()
    val children = parent.childNodes
    for (i in 0 until children.length) {
        val child = children.item(i)
        if (child is Element && (local == null || localName(child) == local)) {
            result += child
        }
    }
    return result
}

internal fun firstDescendant(root: Element, local: String): Element? {
    if (localName(root) == local) return root
    val children = root.childNodes
    for (i in 0 until children.length) {
        val child = children.item(i)
        if (child is Element) {
            val found = firstDescendant(child, local)
            if (found != null) return found
        }
    }
    return null
}

internal fun childText(parent: Element, local: String): String {
    for (child in childElements(parent, local)) return child.textContent.orEmpty()
    return ""
}

// ---------------------------------------------------------------------------
// read_text_file
// ---------------------------------------------------------------------------

/**
 * Reads a plain text, Markdown, CSV, or code file. Bounded to the first
 * 2 MB of the file and 100,000 output characters, with a `[truncated]`
 * note when capped. Detects a UTF-16 BOM (LE/BE) and decodes as UTF-16
 * instead of garbling it as UTF-8; a NUL byte in the first 8 KB of a
 * non-UTF-16 file rejects it as binary instead of returning garbage.
 */
object ReadTextFileTool : Tool {
    override val name: String = "read_text_file"
    override val description: String =
        "Reads a plain text, Markdown, CSV, or code file as text (UTF-8 or UTF-16 with BOM). " +
            "Capped at 2 MB / 100,000 characters. Binary files are rejected."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Absolute path of the text file to read")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.pathArg() ?: return docFail("Missing required argument: path")
        val file = File(path)
        if (!file.isFile) return docFail("File not found: $path")
        return try {
            readTextFileBytes(file)
        } catch (e: Exception) {
            docFail("Could not read text file: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

/** Bounded, BOM-aware plain-text read used by [ReadTextFileTool]. */
internal fun readTextFileBytes(file: File): ToolResult {
    val raw = file.inputStream().use { stream ->
        val buffer = ByteArray(MAX_TEXT_FILE_BYTES + 1)
        var read = 0
        while (read < buffer.size) {
            val n = stream.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        buffer.copyOf(read)
    }
    val bytes = raw.copyOf(minOf(raw.size, MAX_TEXT_FILE_BYTES))
    val isUtf16Le = bytes.size >= 2 &&
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()
    val isUtf16Be = bytes.size >= 2 &&
        bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()
    if (!isUtf16Le && !isUtf16Be) {
        val head = bytes.take(8000).toByteArray()
        if (head.contains(0.toByte())) {
            return docFail("This file does not look like a text file (binary content detected).")
        }
    }
    val text = when {
        isUtf16Le -> String(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16LE)
        isUtf16Be -> String(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8)
    }
    return ToolResult(ok = true, output = truncateWithNote(text, MAX_TEXT_FILE_CHARS))
}
