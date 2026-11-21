package dev.codex.android.tools

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.w3c.dom.Element

/**
 * Minimal OOXML writers: [DocumentCreateTool] and [DocumentEditTool].
 *
 * ## Why no Apache POI
 * POI was evaluated and deliberately NOT used. POI 5.x for .docx/.xlsx/
 * .pptx pulls in xmlbeans, commons-compress, commons-collections and
 * commons-io (several MB of APK), references `java.awt` in its drawing,
 * font and image paths (which do not exist on Android), and needs
 * desugaring / dependency surgery that has never been proven in this
 * project's Kotlin 1.9.25 / AGP 8.7.3 toolchain. The document-model
 * APIs (XWPFDocument / XSSFWorkbook / XMLSlideShow) might work, but the
 * risk is unproven and the payoff is small: the files this app needs to
 * produce are text, tables, sheets and slides, which are all plain
 * ZIP+XML. So writing uses the same hand-built OOXML approach as the
 * readers in DocumentTools.kt — no new dependency, no AWT, and every
 * output is round-trip verified by [ReadDocumentTool] in tests (and by
 * LibreOffice in the verify pass). If a future task needs images,
 * charts or tracked changes inside Office files, re-evaluate POI then.
 * Do NOT add any `org.apache.poi` line to build.gradle.kts for this work.
 *
 * ## Content formats
 * - docx `content`: one paragraph per line. `# Title` / `## Heading`
 *   become Heading 1 / Heading 2. Lines starting and ending with `|`
 *   are table rows (`| a | b |`); consecutive rows form one table.
 * - xlsx `content`: CSV text (quoted fields supported). The first row
 *   is the header. Cells starting with `=` are written as formulas
 *   (Excel/LibreOffice compute them on open; no cached value is stored).
 * - pptx `content`: slides separated by a line containing only `---`.
 *   The first non-empty line of each slide is its title; the rest are
 *   bullet lines.
 *
 * Editing an existing file is done by unzipping the package, editing
 * the one XML part that changes, and re-zipping; every other part is
 * copied byte-for-byte, so formatting the app did not create is kept.
 */

private const val MAX_OFFICE_FILE_BYTES = 50 * 1024 * 1024

internal fun escapeXml(text: String): String {
    val out = StringBuilder(text.length + 16)
    for (c in text) {
        when (c) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '"' -> out.append("&quot;")
            '\'' -> out.append("&apos;")
            else -> out.append(c)
        }
    }
    return out.toString()
}

private fun contentTypes(vararg overrides: Pair<String, String>): String {
    val out = StringBuilder()
    out.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
    out.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
    out.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
    out.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
    for ((part, type) in overrides) {
        out.append("<Override PartName=\"$part\" ContentType=\"$type\"/>")
    }
    out.append("</Types>")
    return out.toString()
}

private fun packageRels(target: String): String =
    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"$target\"/>" +
        "</Relationships>"

private fun coreProps(title: String): String =
    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
        "<dc:title>${escapeXml(title)}</dc:title>" +
        "<dc:creator>Codex</dc:creator>" +
        "</cp:coreProperties>"

private fun appProps(title: String): String =
    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">" +
        "<Application>Codex</Application>" +
        "<TitlesOfParts><vt:vector xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\" size=\"1\" baseType=\"lpstr\">" +
        "<vt:lpstr>${escapeXml(title)}</vt:lpstr></vt:vector></TitlesOfParts>" +
        "</Properties>"

/** Writes [entries] (path to bytes, in order) as a ZIP to [out]. */
internal fun writeZip(out: File, entries: List<Pair<String, ByteArray>>) {
    out.parentFile?.mkdirs()
    ZipOutputStream(out.outputStream().buffered()).use { zip ->
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}

/** Reads every entry of an OOXML package into memory (size-capped). Returns null when too large. */
internal fun readPackage(file: File): LinkedHashMap<String, ByteArray>? {
    if (file.length() > MAX_OFFICE_FILE_BYTES) return null
    val entries = LinkedHashMap<String, ByteArray>()
    ZipFile(file).use { zip ->
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            if (entry.isDirectory) continue
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            if (bytes.size > MAX_OFFICE_FILE_BYTES) return null
            entries[entry.name] = bytes
        }
    }
    return entries
}

/** Rewrites an OOXML package, replacing/adding [changed] entries and keeping the rest byte-for-byte. */
internal fun rewritePackage(file: File, changed: Map<String, ByteArray>): Boolean {
    val entries = readPackage(file) ?: return false
    for ((name, bytes) in changed) entries[name] = bytes
    val tmp = File(file.parentFile, file.name + ".tmp")
    return try {
        writeZip(tmp, entries.toList())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        true
    } catch (e: Exception) {
        tmp.delete()
        false
    }
}

// ---------------------------------------------------------------------------
// docx
// ---------------------------------------------------------------------------

private fun docxParagraph(text: String, style: String? = null): String {
    val styleXml = if (style != null) "<w:pPr><w:pStyle w:val=\"$style\"/></w:pPr>" else ""
    return "<w:p>$styleXml<w:r><w:t xml:space=\"preserve\">${escapeXml(text)}</w:t></w:r></w:p>"
}

private fun docxTable(rows: List<List<String>>): String {
    val out = StringBuilder("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/>" +
        "<w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr><w:tblGrid>")
    val cols = rows.maxOfOrNull { it.size } ?: 0
    repeat(cols) { out.append("<w:gridCol w:w=\"2000\"/>") }
    out.append("</w:tblGrid>")
    for (row in rows) {
        out.append("<w:tr>")
        for (cell in row) {
            out.append("<w:tc><w:tcPr><w:tcW w:w=\"2000\" w:type=\"dxa\"/></w:tcPr>")
            out.append(docxParagraph(cell))
            out.append("</w:tc>")
        }
        out.append("</w:tr>")
    }
    out.append("</w:tbl>")
    return out.toString()
}

/** Parses docx `content` into body XML (paragraphs, headings, tables). */
internal fun docxBodyXml(content: String): String {
    val out = StringBuilder()
    val lines = content.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> { i++ }
            trimmed.startsWith("|") && trimmed.endsWith("|") -> {
                val rows = mutableListOf<List<String>>()
                while (i < lines.size) {
                    val rowLine = lines[i].trim()
                    if (!(rowLine.startsWith("|") && rowLine.endsWith("|"))) break
                    val cells = rowLine.removePrefix("|").removeSuffix("|")
                        .split("|").map { it.trim() }
                    // A separator row (| --- | --- |) carries no content.
                    if (cells.all { it.matches(Regex(":?-{3,}:?")) }) { i++; continue }
                    rows += cells
                    i++
                }
                if (rows.isNotEmpty()) out.append(docxTable(rows))
            }
            trimmed.startsWith("## ") -> { out.append(docxParagraph(trimmed.removePrefix("## ").trim(), "Heading2")); i++ }
            trimmed.startsWith("# ") -> { out.append(docxParagraph(trimmed.removePrefix("# ").trim(), "Heading1")); i++ }
            else -> { out.append(docxParagraph(trimmed)); i++ }
        }
    }
    return out.toString()
}

private const val DOCX_STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
    "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
    "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/>" +
    "<w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr></w:style>" +
    "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/>" +
    "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/></w:pPr>" +
    "<w:rPr><w:b/><w:sz w:val=\"32\"/><w:szCs w:val=\"32\"/></w:rPr></w:style>" +
    "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/>" +
    "<w:pPr><w:keepNext/><w:spacing w:before=\"200\" w:after=\"100\"/></w:pPr>" +
    "<w:rPr><w:b/><w:sz w:val=\"26\"/><w:szCs w:val=\"26\"/></w:rPr></w:style>" +
    "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/>" +
    "<w:tblPr><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\"/><w:left w:val=\"single\" w:sz=\"4\"/>" +
    "<w:bottom w:val=\"single\" w:sz=\"4\"/><w:right w:val=\"single\" w:sz=\"4\"/>" +
    "<w:insideH w:val=\"single\" w:sz=\"4\"/><w:insideV w:val=\"single\" w:sz=\"4\"/></w:tblBorders></w:tblPr></w:style>" +
    "</w:styles>"

internal fun buildDocx(title: String, content: String): List<Pair<String, ByteArray>> {
    val documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
        "<w:body>${docxBodyXml(content)}" +
        "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>" +
        "</w:body></w:document>"
    return listOf(
        "[Content_Types].xml" to contentTypes(
            "word/document.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
            "word/styles.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml",
            "docProps/core.xml" to "application/vnd.openxmlformats-package.core-properties+xml",
            "docProps/app.xml" to "application/vnd.openxmlformats-officedocument.extended-properties+xml",
        ).toByteArray(Charsets.UTF_8),
        "_rels/.rels" to packageRels("word/document.xml").toByteArray(Charsets.UTF_8),
        "word/document.xml" to documentXml.toByteArray(Charsets.UTF_8),
        "word/styles.xml" to DOCX_STYLES.toByteArray(Charsets.UTF_8),
        "word/_rels/document.xml.rels" to
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"></Relationships>".toByteArray(Charsets.UTF_8),
        "docProps/core.xml" to coreProps(title).toByteArray(Charsets.UTF_8),
        "docProps/app.xml" to appProps(title).toByteArray(Charsets.UTF_8),
    )
}

// ---------------------------------------------------------------------------
// xlsx
// ---------------------------------------------------------------------------

/** Minimal CSV parser: quoted fields, doubled-quote escapes, commas inside quotes. */
internal fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    var field = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                field.append('"'); i += 2
            }
            c == '"' -> { inQuotes = !inQuotes; i++ }
            !inQuotes && c == ',' -> { row += field.toString(); field = StringBuilder(); i++ }
            !inQuotes && (c == '\n' || c == '\r') -> {
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                row += field.toString(); field = StringBuilder()
                rows += row; row = mutableListOf(); i++
            }
            else -> { field.append(c); i++ }
        }
    }
    if (field.isNotEmpty() || row.isNotEmpty()) {
        row += field.toString()
        rows += row
    }
    return rows.filter { it.any { cell -> cell.isNotBlank() } }
}

private fun columnLetter(index: Int): String {
    var n = index
    val out = StringBuilder()
    while (n >= 0) {
        out.append(('A'.code + (n % 26)).toChar())
        n = n / 26 - 1
    }
    return out.reverse().toString()
}

internal fun xlsxCellXml(rowNumber: Int, colIndex: Int, value: String): String {
    val ref = "${columnLetter(colIndex)}$rowNumber"
    val trimmed = value.trim()
    return when {
        trimmed.startsWith("=") && trimmed.length > 1 ->
            "<c r=\"$ref\"><f>${escapeXml(trimmed.removePrefix("="))}</f></c>"
        trimmed.matches(Regex("-?\\d+(\\.\\d+)?")) ->
            "<c r=\"$ref\" s=\"0\"><v>$trimmed</v></c>"
        else ->
            "<c r=\"$ref\" t=\"inlineStr\" s=\"0\"><is><t xml:space=\"preserve\">${escapeXml(value)}</t></is></c>"
    }
}

internal fun xlsxSheetXml(rows: List<List<String>>, startRow: Int = 1): String {
    val out = StringBuilder()
    out.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
    out.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
    out.append("<sheetData>")
    for ((rowOffset, row) in rows.withIndex()) {
        val rowNumber = startRow + rowOffset
        out.append("<row r=\"$rowNumber\">")
        for ((colIndex, cell) in row.withIndex()) {
            out.append(xlsxCellXml(rowNumber, colIndex, cell))
        }
        out.append("</row>")
    }
    out.append("</sheetData></worksheet>")
    return out.toString()
}

private const val XLSX_STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
    "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
    "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
    "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
    "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
    "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
    "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>" +
    "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"

internal fun buildXlsx(title: String, content: String): List<Pair<String, ByteArray>> {
    val rows = parseCsv(content)
    val workbookXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
        "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
    return listOf(
        "[Content_Types].xml" to contentTypes(
            "xl/workbook.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
            "xl/worksheets/sheet1.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml",
            "xl/styles.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml",
            "docProps/core.xml" to "application/vnd.openxmlformats-package.core-properties+xml",
            "docProps/app.xml" to "application/vnd.openxmlformats-officedocument.extended-properties+xml",
        ).toByteArray(Charsets.UTF_8),
        "_rels/.rels" to packageRels("xl/workbook.xml").toByteArray(Charsets.UTF_8),
        "xl/workbook.xml" to workbookXml.toByteArray(Charsets.UTF_8),
        "xl/_rels/workbook.xml.rels" to
            ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
                "</Relationships>").toByteArray(Charsets.UTF_8),
        "xl/worksheets/sheet1.xml" to xlsxSheetXml(rows).toByteArray(Charsets.UTF_8),
        "xl/styles.xml" to XLSX_STYLES.toByteArray(Charsets.UTF_8),
        "docProps/core.xml" to coreProps(title).toByteArray(Charsets.UTF_8),
        "docProps/app.xml" to appProps(title).toByteArray(Charsets.UTF_8),
    )
}

// ---------------------------------------------------------------------------
// pptx
// ---------------------------------------------------------------------------

private const val PPTX_THEME = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
    "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Codex Theme\">" +
    "<a:themeElements><a:clrScheme name=\"Office\">" +
    "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>" +
    "<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
    "<a:dk2><a:srgbClr val=\"1F497D\"/></a:dk2><a:lt2><a:srgbClr val=\"EEECE1\"/></a:lt2>" +
    "<a:accent1><a:srgbClr val=\"4F81BD\"/></a:accent1><a:accent2><a:srgbClr val=\"C0504D\"/></a:accent2>" +
    "<a:accent3><a:srgbClr val=\"9BBB59\"/></a:accent3><a:accent4><a:srgbClr val=\"8064A2\"/></a:accent4>" +
    "<a:accent5><a:srgbClr val=\"4BACC6\"/></a:accent5><a:accent6><a:srgbClr val=\"F79646\"/></a:accent6>" +
    "<a:hlink><a:srgbClr val=\"0000FF\"/></a:hlink><a:folHlink><a:srgbClr val=\"800080\"/></a:folHlink>" +
    "</a:clrScheme>" +
    "<a:fontScheme name=\"Office\"><a:majorFont><a:latin typeface=\"Calibri\"/></a:majorFont>" +
    "<a:minorFont><a:latin typeface=\"Calibri\"/></a:minorFont></a:fontScheme>" +
    "<a:fmtScheme name=\"Office\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
    "</a:fillStyleLst><a:lnStyleLst><a:ln w=\"9525\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>" +
    "</a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
    "<a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme>" +
    "</a:themeElements></a:theme>"

private fun pptxTextParagraph(text: String, sizeHundredths: Int, bold: Boolean): String {
    val boldXml = if (bold) " b=\"1\"" else ""
    return "<a:p><a:r><a:rPr lang=\"en-GB\" sz=\"$sizeHundredths\"$boldXml/>" +
        "<a:t>${escapeXml(text)}</a:t></a:r></a:p>"
}

private fun pptxTextBox(id: Int, name: String, x: Long, y: Long, cx: Long, cy: Long, paragraphs: String): String =
    "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr txBox=\"1\"/>" +
        "<p:nvSpPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
        "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>" +
        "<p:txBody><a:bodyPr/><a:lstStyle/>$paragraphs</p:txBody></p:sp>"

/** One slide: title line + bullet lines. Slide size is 13.33 x 7.5 in (12192000 x 6858000 EMU). */
internal fun pptxSlideXml(title: String, bullets: List<String>): String {
    val titlePara = pptxTextParagraph(title, 3200, true)
    val bulletParas = bullets.joinToString("") { line ->
        "<a:p><a:pPr lvl=\"0\"><a:buChar char=\"•\"/></a:pPr><a:r><a:rPr lang=\"en-GB\" sz=\"2000\"/>" +
            "<a:t>${escapeXml(line)}</a:t></a:r></a:p>"
    }
    return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
        "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
        "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
        "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" +
        pptxTextBox(2, "Title", 457200, 274638, 11277600, 1143000, titlePara) +
        pptxTextBox(3, "Content", 457200, 1600200, 11277600, 4572000, bulletParas) +
        "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
}

private const val PPTX_MASTER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
    "<p:sldMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
    "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
    "<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>" +
    "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
    "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" +
    "</p:spTree></p:cSld>" +
    "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
    "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>" +
    "<p:txStyles><p:titleStyle><a:lvl1pPr algn=\"l\"><a:defRPr sz=\"3200\"/></a:lvl1pPr></p:titleStyle>" +
    "<p:bodyStyle><a:lvl1pPr><a:defRPr sz=\"2000\"/></a:lvl1pPr></p:bodyStyle>" +
    "<p:otherStyle><a:lvl1pPr><a:defRPr sz=\"1800\"/></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>"

private const val PPTX_LAYOUT = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
    "<p:sldLayout xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
    "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" type=\"blank\" showMasterSp=\"0\" showMasterShapes=\"0\">" +
    "<p:cSld name=\"Blank\"><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
    "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" +
    "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"

/** Splits pptx `content` into (title, bullets) per slide on `---` lines. */
internal fun parseSlides(content: String): List<Pair<String, List<String>>> {
    val slides = mutableListOf<Pair<String, List<String>>>()
    var title: String? = null
    val bullets = mutableListOf<String>()
    fun flush() {
        if (title != null || bullets.isNotEmpty()) {
            slides += (title ?: "Slide ${slides.size + 1}") to bullets.toList()
        }
        title = null
        bullets.clear()
    }
    for (rawLine in content.lines()) {
        val line = rawLine.trim()
        when {
            line == "---" -> flush()
            line.isEmpty() -> Unit
            title == null -> title = line.removePrefix("# ").trim()
            else -> bullets += line.removePrefix("- ").removePrefix("• ").trim()
        }
    }
    flush()
    return slides.ifEmpty { listOf("Slide 1" to emptyList()) }
}

private fun pptxPresentationXml(slideCount: Int): String {
    val ids = (1..slideCount).joinToString("") { n ->
        "<p:sldId id=\"${255 + n}\" r:id=\"rId${n + 3}\"/>"
    }
    return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
        "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
        "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
        "<p:sldIdLst>$ids</p:sldIdLst>" +
        "<p:sldSz cx=\"12192000\" cy=\"6858000\" type=\"screen4x3\"/>" +
        "<p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>"
}

private fun pptxPresentationRels(slideCount: Int): String {
    val slides = (1..slideCount).joinToString("") { n ->
        "<Relationship Id=\"rId${n + 3}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide$n.xml\"/>"
    }
    return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"theme/theme1.xml\"/>" +
        "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/presProps\" Target=\"presProps.xml\"/>" +
        slides +
        "</Relationships>"
}

internal fun buildPptx(title: String, content: String): List<Pair<String, ByteArray>> {
    val slides = parseSlides(content)
    val overrides = mutableListOf(
        "ppt/presentation.xml" to "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
        "ppt/slideMasters/slideMaster1.xml" to "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml",
        "ppt/slideLayouts/slideLayout1.xml" to "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml",
        "ppt/theme/theme1.xml" to "application/vnd.openxmlformats-officedocument.theme+xml",
        "ppt/presProps.xml" to "application/vnd.openxmlformats-officedocument.presentationml.presProps+xml",
        "docProps/core.xml" to "application/vnd.openxmlformats-package.core-properties+xml",
        "docProps/app.xml" to "application/vnd.openxmlformats-officedocument.extended-properties+xml",
    )
    for (n in slides.indices) {
        overrides += "ppt/slides/slide${n + 1}.xml" to
            "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
    }
    val entries = mutableListOf(
        "[Content_Types].xml" to contentTypes(*overrides.toTypedArray()).toByteArray(Charsets.UTF_8),
        "_rels/.rels" to packageRels("ppt/presentation.xml").toByteArray(Charsets.UTF_8),
        "ppt/presentation.xml" to pptxPresentationXml(slides.size).toByteArray(Charsets.UTF_8),
        "ppt/_rels/presentation.xml.rels" to pptxPresentationRels(slides.size).toByteArray(Charsets.UTF_8),
        "ppt/slideMasters/slideMaster1.xml" to PPTX_MASTER.toByteArray(Charsets.UTF_8),
        "ppt/slideMasters/_rels/slideMaster1.xml.rels" to
            ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/>" +
                "</Relationships>").toByteArray(Charsets.UTF_8),
        "ppt/slideLayouts/slideLayout1.xml" to PPTX_LAYOUT.toByteArray(Charsets.UTF_8),
        "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to
            ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/>" +
                "</Relationships>").toByteArray(Charsets.UTF_8),
        "ppt/theme/theme1.xml" to PPTX_THEME.toByteArray(Charsets.UTF_8),
        "ppt/presProps.xml" to
            ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<p:presentationPr xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"/>").toByteArray(Charsets.UTF_8),
        "docProps/core.xml" to coreProps(title).toByteArray(Charsets.UTF_8),
        "docProps/app.xml" to appProps(title).toByteArray(Charsets.UTF_8),
    )
    for ((index, slide) in slides.withIndex()) {
        val n = index + 1
        entries += "ppt/slides/slide$n.xml" to
            pptxSlideXml(slide.first, slide.second).toByteArray(Charsets.UTF_8)
        entries += "ppt/slides/_rels/slide$n.xml.rels" to
            ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
                "</Relationships>").toByteArray(Charsets.UTF_8)
    }
    return entries
}

// ---------------------------------------------------------------------------
// document_create
// ---------------------------------------------------------------------------

/**
 * Creates a new .docx, .xlsx or .pptx file from text content (see the
 * file header for the per-format content syntax). The output path must
 * end in the matching extension; parent directories are created.
 * Writes are real files the user can open in Word/Excel/PowerPoint or
 * LibreOffice — nothing here is a stub.
 */
class DocumentCreateTool(private val workspaceRoot: File) : Tool {
    override val name: String = "document_create"
    override val description: String =
        "Creates a new Word (.docx), Excel (.xlsx) or PowerPoint (.pptx) file from text content. " +
            "Docx: one paragraph per line, '# ' headings, '| a | b |' table rows. " +
            "Xlsx: CSV text, first row is the header, cells starting with '=' become formulas. " +
            "Pptx: slides separated by a '---' line, first line of each slide is its title."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("format") {
                put("type", "string")
                put("description", "One of: docx, xlsx, pptx")
            }
            putJsonObject("output_path") {
                put("type", "string")
                put("description", "Path to write, ending in .docx, .xlsx or .pptx: an absolute on-phone path, a /workspace/... path, or a workspace-relative path")
            }
            putJsonObject("title") {
                put("type", "string")
                put("description", "Document title stored in its properties (optional)")
            }
            putJsonObject("content") {
                put("type", "string")
                put("description", "The document content in the per-format syntax described above")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("format")); add(JsonPrimitive("output_path")); add(JsonPrimitive("content")) }
    }
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val format = args["format"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return docFail("Missing required argument: format")
        val outputPath = args["output_path"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return docFail("Missing required argument: output_path")
        val content = args["content"]?.jsonPrimitive?.contentOrNull
            ?: return docFail("Missing required argument: content")
        val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (format !in setOf("docx", "xlsx", "pptx")) {
            return docFail("Unsupported format: $format. Use docx, xlsx or pptx.")
        }
        if (!outputPath.endsWith(".$format", ignoreCase = true)) {
            return docFail("output_path must end in .$format")
        }
        return try {
            val out = try {
                resolveDocumentPath(outputPath, workspaceRoot)
            } catch (e: SecurityException) {
                return docFail(e.message ?: "Invalid path: $outputPath")
            }
            val entries = when (format) {
                "docx" -> buildDocx(title.ifEmpty { out.nameWithoutExtension }, content)
                "xlsx" -> buildXlsx(title.ifEmpty { out.nameWithoutExtension }, content)
                else -> buildPptx(title.ifEmpty { out.nameWithoutExtension }, content)
            }
            writeZip(out, entries)
            ToolResult(ok = true, output = "Created $format file: ${out.absolutePath} (${out.length()} bytes)")
        } catch (e: Exception) {
            docFail("Could not create document: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

// ---------------------------------------------------------------------------
// document_edit
// ---------------------------------------------------------------------------

/**
 * Edits an existing .docx/.xlsx/.pptx file in place.
 *
 * - `append`: docx appends paragraphs/headings/tables (same syntax as
 *   document_create); xlsx appends CSV rows after the last row; pptx
 *   appends slides (same `---` syntax as document_create).
 * - `replace` (docx only): replaces every occurrence of `find` with
 *   `replacement` in the document text. Runs split across XML runs are
 *   handled: the replacement is done per paragraph over its joined run
 *   text, so a phrase split across runs is still found.
 *
 * Only the XML part that changes is rewritten; every other part of the
 * package is copied byte-for-byte.
 */
class DocumentEditTool(private val workspaceRoot: File) : Tool {
    override val name: String = "document_edit"
    override val description: String =
        "Edits an existing .docx, .xlsx or .pptx file. Operation 'append' adds paragraphs (docx), CSV rows (xlsx) or slides (pptx). " +
            "Operation 'replace' (docx only) replaces text throughout the document."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Path of the .docx, .xlsx, or .pptx file to edit: an absolute on-phone path, a /workspace/... path, or a workspace-relative path")
            }
            putJsonObject("operation") {
                put("type", "string")
                put("description", "One of: append, replace (docx only)")
            }
            putJsonObject("content") {
                put("type", "string")
                put("description", "Content to append (paragraphs, CSV rows, or slides, per format)")
            }
            putJsonObject("find") {
                put("type", "string")
                put("description", "Text to find (replace operation)")
            }
            putJsonObject("replacement") {
                put("type", "string")
                put("description", "Replacement text (replace operation)")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("operation")) }
    }
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.pathArg() ?: return docFail("Missing required argument: path")
        val file = try {
            resolveDocumentPath(path, workspaceRoot)
        } catch (e: SecurityException) {
            return docFail(e.message ?: "Invalid path: $path")
        }
        if (!file.isFile) return docFail("File not found: $path")
        val operation = args["operation"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return docFail("Missing required argument: operation")
        val extension = file.name.substringAfterLast('.', "").lowercase()
        if (extension !in setOf("docx", "xlsx", "pptx")) {
            return docFail("Unsupported file type: .$extension. Use .docx, .xlsx or .pptx.")
        }
        return try {
            when (operation) {
                "append" -> appendToDocument(file, extension, args["content"]?.jsonPrimitive?.contentOrNull)
                "replace" -> replaceInDocx(file, extension, args)
                else -> docFail("Unsupported operation: $operation. Use append or replace.")
            }
        } catch (e: Exception) {
            docFail("Could not edit document: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun appendToDocument(file: File, extension: String, content: String?): ToolResult {
        if (content.isNullOrBlank()) return docFail("Missing required argument: content (for append)")
        when (extension) {
            "docx" -> {
                val entries = readPackage(file) ?: return docFail("File is too large to edit.")
                val xml = String(entries["word/document.xml"] ?: return docFail("Not a valid .docx file."), Charsets.UTF_8)
                val marker = "</w:body>"
                val insertAt = xml.lastIndexOf(marker)
                if (insertAt < 0) return docFail("Not a valid .docx file.")
                val updated = xml.substring(0, insertAt) + docxBodyXml(content) + xml.substring(insertAt)
                if (!rewritePackage(file, mapOf("word/document.xml" to updated.toByteArray(Charsets.UTF_8)))) {
                    return docFail("Failed to write the edited file.")
                }
                return ToolResult(ok = true, output = "Appended content to ${file.name}")
            }
            "xlsx" -> {
                val entries = readPackage(file) ?: return docFail("File is too large to edit.")
                val sheetBytes = entries["xl/worksheets/sheet1.xml"]
                    ?: return docFail("This workbook has no sheet1.xml; only workbooks created by document_create (or with a first sheet at xl/worksheets/sheet1.xml) can be appended to.")
                val rows = parseCsv(content)
                val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(sheetBytes))
                val sheetData = doc.documentElement.getElementsByTagNameNS("*", "sheetData").item(0) as? Element
                    ?: return docFail("Not a valid .xlsx sheet.")
                var maxRow = 0
                val existing = sheetData.getElementsByTagNameNS("*", "row")
                for (i in 0 until existing.length) {
                    val r = (existing.item(i) as? Element)?.getAttribute("r")?.toIntOrNull() ?: 0
                    if (r > maxRow) maxRow = r
                }
                val rowXml = xlsxSheetXml(rows, startRow = maxRow + 1)
                val rowFragment = rowXml.substringAfter("<sheetData>").substringBefore("</sheetData>")
                val updated = String(sheetBytes, Charsets.UTF_8)
                    .replace("</sheetData>", "$rowFragment</sheetData>")
                if (!rewritePackage(file, mapOf("xl/worksheets/sheet1.xml" to updated.toByteArray(Charsets.UTF_8)))) {
                    return docFail("Failed to write the edited file.")
                }
                return ToolResult(ok = true, output = "Appended ${rows.size} row(s) to ${file.name}")
            }
            else -> { // pptx
                val slides = parseSlides(content)
                val entries = readPackage(file) ?: return docFail("File is too large to edit.")
                val presentationBytes = entries["ppt/presentation.xml"]
                    ?: return docFail("Not a valid .pptx file.")
                val presentation = String(presentationBytes, Charsets.UTF_8)
                val existingCount = Regex("ppt/slides/slide\\d+\\.xml").findAll(entries.keys.joinToString(" ")).count()
                val slideCount = Regex("<p:sldId ").findAll(presentation).count().coerceAtLeast(existingCount)
                val changed = mutableMapOf<String, ByteArray>()
                val typesBytes = entries["[Content_Types].xml"]?.let { String(it, Charsets.UTF_8) }
                    ?: return docFail("Not a valid .pptx file.")
                var updatedTypes = typesBytes
                var updatedPresentation = presentation
                var updatedRels = String(
                    entries["ppt/_rels/presentation.xml.rels"]
                        ?: return docFail("Not a valid .pptx file."),
                    Charsets.UTF_8,
                )
                for ((offset, slide) in slides.withIndex()) {
                    val n = slideCount + offset + 1
                    changed["ppt/slides/slide$n.xml"] = pptxSlideXml(slide.first, slide.second).toByteArray(Charsets.UTF_8)
                    changed["ppt/slides/_rels/slide$n.xml.rels"] =
                        ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
                            "</Relationships>").toByteArray(Charsets.UTF_8)
                    updatedTypes = updatedTypes.replace(
                        "</Types>",
                        "<Override PartName=\"/ppt/slides/slide$n.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/></Types>",
                    )
                    updatedPresentation = updatedPresentation.replace(
                        "</p:sldIdLst>",
                        "<p:sldId id=\"${255 + n}\" r:id=\"rId${n + 3}\"/></p:sldIdLst>",
                    )
                    updatedRels = updatedRels.replace(
                        "</Relationships>",
                        "<Relationship Id=\"rId${n + 3}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide$n.xml\"/></Relationships>",
                    )
                }
                changed["[Content_Types].xml"] = updatedTypes.toByteArray(Charsets.UTF_8)
                changed["ppt/presentation.xml"] = updatedPresentation.toByteArray(Charsets.UTF_8)
                changed["ppt/_rels/presentation.xml.rels"] = updatedRels.toByteArray(Charsets.UTF_8)
                if (!rewritePackage(file, changed)) {
                    return docFail("Failed to write the edited file.")
                }
                return ToolResult(ok = true, output = "Appended ${slides.size} slide(s) to ${file.name}")
            }
        }
    }

    private fun replaceInDocx(file: File, extension: String, args: JsonObject): ToolResult {
        if (extension != "docx") {
            return docFail("Replace is only supported for .docx files.")
        }
        val find = args["find"]?.jsonPrimitive?.contentOrNull
        val replacement = args["replacement"]?.jsonPrimitive?.contentOrNull
        if (find.isNullOrEmpty() || replacement == null) {
            return docFail("Replace needs both 'find' and 'replacement'.")
        }
        val entries = readPackage(file) ?: return docFail("File is too large to edit.")
        val xmlBytes = entries["word/document.xml"] ?: return docFail("Not a valid .docx file.")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
        var replacements = 0
        val paragraphs = doc.documentElement.getElementsByTagNameNS(
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main", "p",
        )
        for (i in 0 until paragraphs.length) {
            val paragraph = paragraphs.item(i) as? Element ?: continue
            val runs = paragraph.getElementsByTagNameNS(
                "http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t",
            )
            val runElements = (0 until runs.length).mapNotNull { runs.item(it) as? Element }
            if (runElements.isEmpty()) continue
            val joined = runElements.joinToString("") { it.textContent.orEmpty() }
            if (!joined.contains(find)) continue
            val replaced = joined.replace(find, replacement)
            replacements += joined.split(find).size - 1
            runElements.first().textContent = replaced
            for (extra in runElements.drop(1)) extra.textContent = ""
        }
        if (replacements == 0) {
            return ToolResult(ok = true, output = "No occurrences of \"$find\" found in ${file.name}; nothing changed.")
        }
        val out = ByteArrayOutputStream()
        val transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "UTF-8")
        transformer.transform(
            javax.xml.transform.dom.DOMSource(doc),
            javax.xml.transform.stream.StreamResult(out),
        )
        if (!rewritePackage(file, mapOf("word/document.xml" to out.toByteArray()))) {
            return docFail("Failed to write the edited file.")
        }
        return ToolResult(ok = true, output = "Replaced $replacements occurrence(s) of \"$find\" in ${file.name}")
    }
}

/** Convenience: every tool this file defines, in registration order. */
internal fun documentWriteTools(workspaceRoot: File): List<Tool> =
    listOf(DocumentCreateTool(workspaceRoot), DocumentEditTool(workspaceRoot))

/** Convenience: every standalone document-reading tool, in registration order. */
internal fun documentReadTools(workspaceRoot: File): List<Tool> =
    listOf(ReadPdfTool(workspaceRoot), ReadDocumentTool(workspaceRoot), ReadTextFileTool(workspaceRoot))
