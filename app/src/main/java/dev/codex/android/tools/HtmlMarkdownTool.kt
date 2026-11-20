package dev.codex.android.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Pure HTML → Markdown converter (`name = "html-markdown"`).
 *
 * No new dependency: a small hand-written tokenizer + tree builder +
 * renderer. JVM-testable — this file imports nothing from Android.
 *
 * Supported: headings, paragraphs, bold/italic/strikethrough, links,
 * images, unordered/ordered lists (nested), inline code, fenced code
 * blocks (language taken from a `language-*` / `lang-*` class),
 * blockquotes, horizontal rules, tables (pipe format), line breaks.
 * `<script>`, `<style>`, `<head>`, `<title>`, `<noscript>` and
 * `<template>` subtrees are dropped entirely. HTML entities are decoded.
 */
object HtmlMarkdownTool : Tool {

    override val name: String = "html-markdown"
    override val description: String =
        "Converts HTML to Markdown (headings, lists, links, images, code blocks, " +
            "blockquotes, tables). Script/style content is stripped. Output is capped at 100,000 characters."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("html") {
                put("type", "string")
                put("description", "HTML source to convert to Markdown")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("html"))
        }
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val html = args["html"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: html")
        val markdown = HtmlToMarkdown.convert(html)
        if (markdown.length <= MAX_OUTPUT_CHARS) {
            return ToolResult(ok = true, output = markdown)
        }
        val cut = markdown.substring(0, MAX_OUTPUT_CHARS)
            .let { if (it.isNotEmpty() && Character.isHighSurrogate(it.last())) it.dropLast(1) else it }
        return ToolResult(
            ok = true,
            output = cut + "\n\n[truncated: output capped at $MAX_OUTPUT_CHARS characters]",
        )
    }

    /** Maximum Markdown output characters before the truncation note is appended. */
    const val MAX_OUTPUT_CHARS: Int = 100_000
}

/** Stateless HTML → Markdown converter. Pure Kotlin, no Android APIs. */
object HtmlToMarkdown {

    fun convert(html: String): String {
        if (html.isBlank()) return ""
        val root = HtmlParser.parse(html)
        val rendered = renderBlocks(root.children)
        return MULTIPLE_NEWLINES_REGEX.replace(rendered, "\n\n").trim()
    }

    // ---- Tree ----

    private sealed interface HtmlNode
    private data class HtmlText(val text: String) : HtmlNode
    private data class HtmlElement(
        val tag: String,
        val attrs: Map<String, String>,
        val children: MutableList<HtmlNode> = mutableListOf(),
    ) : HtmlNode

    private object HtmlParser {
        private val VOID_TAGS = setOf(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr",
        )
        private val SKIP_TAGS = setOf("script", "style", "head", "title", "noscript", "template")
        private val TAG_OPEN_REGEX = Regex("""^<\s*([A-Za-z][A-Za-z0-9-]*)""")
        private val TAG_CLOSE_REGEX = Regex("""^</\s*([A-Za-z][A-Za-z0-9-]*)\s*>""")
        private val ATTR_REGEX =
            Regex("""([A-Za-z_:][\w:.-]*)(?:\s*=\s*("[^"]*"|'[^']*'|[^\s"'=<>`]+))?""")

        fun parse(html: String): HtmlElement {
            val root = HtmlElement("root", emptyMap())
            val stack = ArrayDeque<HtmlElement>().apply { addLast(root) }
            var i = 0
            val n = html.length
            while (i < n) {
                val lt = html.indexOf('<', i)
                if (lt == -1) {
                    addText(stack, html.substring(i))
                    break
                }
                if (lt > i) addText(stack, html.substring(i, lt))
                // Comment: <!-- ... -->
                if (html.startsWith("<!--", lt)) {
                    val end = html.indexOf("-->", lt + 4)
                    i = if (end == -1) n else end + 3
                    continue
                }
                // Doctype / other <!...> declaration.
                if (html.startsWith("<!", lt)) {
                    val end = html.indexOf('>', lt + 2)
                    i = if (end == -1) n else end + 1
                    continue
                }
                val rest = html.substring(lt)
                val closeMatch = TAG_CLOSE_REGEX.find(rest)
                if (closeMatch != null) {
                    val tag = closeMatch.groupValues[1].lowercase()
                    // Pop up to (and including) the matching open tag.
                    var idx = stack.size - 1
                    while (idx > 0 && stack[idx].tag != tag) idx--
                    if (idx > 0) {
                        while (stack.size - 1 >= idx) stack.removeLast()
                    }
                    i = lt + closeMatch.value.length
                    continue
                }
                val openMatch = TAG_OPEN_REGEX.find(rest)
                if (openMatch == null) {
                    // Lone '<' — treat it as literal text.
                    addText(stack, "<")
                    i = lt + 1
                    continue
                }
                val tag = openMatch.groupValues[1].lowercase()
                val gt = findTagEnd(html, lt)
                if (gt == -1) {
                    addText(stack, html.substring(lt))
                    break
                }
                val inside = html.substring(lt + openMatch.value.length, gt)
                val selfClosing = inside.trimEnd().endsWith("/") || tag in VOID_TAGS
                val attrs = parseAttrs(inside)
                if (tag in SKIP_TAGS && !selfClosing) {
                    // Skip the whole subtree up to the matching close tag.
                    val closeTag = Regex("(?i)</\\s*$tag\\s*>")
                    val close = closeTag.find(html, gt + 1)
                    i = if (close != null) close.range.last + 1 else n
                    continue
                }
                val element = HtmlElement(tag, attrs)
                stack.last().children.add(element)
                if (!selfClosing) stack.addLast(element)
                i = gt + 1
            }
            return root
        }

        private fun addText(stack: ArrayDeque<HtmlElement>, raw: String) {
            if (raw.isEmpty()) return
            stack.last().children.add(HtmlText(decodeHtmlEntities(raw)))
        }

        /** Finds the '>' ending a tag, skipping over quoted attribute values. */
        private fun findTagEnd(html: String, lt: Int): Int {
            var i = lt + 1
            var quote = '\u0000'
            while (i < html.length) {
                val c = html[i]
                if (quote != '\u0000') {
                    if (c == quote) quote = '\u0000'
                } else if (c == '"' || c == '\'') {
                    quote = c
                } else if (c == '>') {
                    return i
                }
                i++
            }
            return -1
        }

        private fun parseAttrs(inside: String): Map<String, String> {
            val attrs = LinkedHashMap<String, String>()
            for (match in ATTR_REGEX.findAll(inside)) {
                val name = match.groupValues[1].lowercase()
                var value = match.groupValues[2]
                if (value.length >= 2 &&
                    ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'")))
                ) {
                    value = value.substring(1, value.length - 1)
                }
                attrs[name] = decodeHtmlEntities(value)
            }
            return attrs
        }
    }

    // ---- Rendering ----

    private val BLOCK_LEVEL_TAGS = setOf(
        "h1", "h2", "h3", "h4", "h5", "h6", "p", "div", "section", "article",
        "header", "footer", "main", "ul", "ol", "blockquote", "pre", "table",
        "hr", "figure", "figcaption", "form", "nav", "aside", "address",
        "fieldset", "dl", "dt", "dd", "li",
    )
    private val BLOCK_TAGS = BLOCK_LEVEL_TAGS
    private val MULTIPLE_NEWLINES_REGEX = Regex("""\n{3,}""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    private fun renderBlocks(nodes: List<HtmlNode>): String {
        val out = StringBuilder()
        // Consecutive inline-level nodes (stray text, <span>, <a>, ...) are
        // coalesced into one paragraph instead of one paragraph each.
        val pendingInline = mutableListOf<HtmlNode>()
        fun flushInline() {
            if (pendingInline.isEmpty()) return
            val text = renderInline(pendingInline).trim()
            if (text.isNotEmpty()) out.append(text).append("\n\n")
            pendingInline.clear()
        }
        for (node in nodes) {
            if (node is HtmlElement && node.tag in BLOCK_LEVEL_TAGS) {
                flushInline()
                renderBlockElement(node, out)
            } else {
                pendingInline.add(node)
            }
        }
        flushInline()
        return out.toString()
    }

    private fun renderBlockElement(el: HtmlElement, out: StringBuilder) {
        when (el.tag) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = el.tag[1].digitToInt()
                val text = renderInline(el.children).trim()
                if (text.isNotEmpty()) out.append("#".repeat(level)).append(' ').append(text).append("\n\n")
            }
            "p" -> {
                val text = renderInline(el.children).trim()
                if (text.isNotEmpty()) out.append(text).append("\n\n")
            }
            "ul", "ol" -> {
                out.append(renderList(el, indent = 0)).append('\n')
            }
            "pre" -> {
                val lang = codeLanguage(el)
                val raw = rawText(el).trim('\n')
                out.append("```").append(lang).append('\n').append(raw).append("\n```\n\n")
            }
            "blockquote" -> {
                val inner = renderBlocks(el.children).trim()
                if (inner.isNotEmpty()) {
                    for (line in inner.lines()) {
                        if (line.isBlank()) out.append(">\n") else out.append("> ").append(line).append('\n')
                    }
                    out.append('\n')
                }
            }
            "hr" -> out.append("---\n\n")
            "table" -> {
                val table = renderTable(el)
                if (table.isNotEmpty()) out.append(table).append('\n')
            }
            "dt" -> {
                val text = renderInline(el.children).trim()
                if (text.isNotEmpty()) out.append("**").append(text).append("**\n\n")
            }
            "dd" -> {
                val text = renderInline(el.children).trim()
                if (text.isNotEmpty()) out.append(text).append("\n\n")
            }
            "li" -> {
                // A bare <li> outside a list: render as a bullet.
                val text = renderInline(el.children.filterNot { it is HtmlElement && it.tag in setOf("ul", "ol") }).trim()
                if (text.isNotEmpty()) out.append("- ").append(text).append('\n')
                for (child in el.children) {
                    if (child is HtmlElement && child.tag in setOf("ul", "ol")) {
                        out.append(renderList(child, indent = 2))
                    }
                }
                out.append('\n')
            }
            "br" -> out.append('\n')
            "div", "section", "article", "header", "footer", "main", "figure",
            "figcaption", "form", "nav", "aside", "address", "fieldset", "dl" -> {
                out.append(renderBlocks(el.children))
            }
            else -> {
                // Unknown element: if it contains block children, render
                // them as blocks; otherwise render inline as a paragraph.
                val hasBlockChild = el.children.any {
                    it is HtmlElement && it.tag in BLOCK_TAGS
                }
                if (hasBlockChild) {
                    out.append(renderBlocks(el.children))
                } else {
                    val text = renderInline(el.children).trim()
                    if (text.isNotEmpty()) out.append(text).append("\n\n")
                }
            }
        }
    }

    private fun renderList(el: HtmlElement, indent: Int): String {
        val out = StringBuilder()
        val items = el.children.filterIsInstance<HtmlElement>().filter { it.tag == "li" }
        var number = 1
        for (li in items) {
            val inlineParts = li.children.filterNot {
                it is HtmlElement && it.tag in setOf("ul", "ol")
            }
            val nested = li.children.filterIsInstance<HtmlElement>()
                .filter { it.tag in setOf("ul", "ol") }
            val marker = if (el.tag == "ol") "${number++}. " else "- "
            val first = renderInline(inlineParts).trim()
            out.append(" ".repeat(indent)).append(marker)
            if (first.isNotEmpty()) out.append(first)
            out.append('\n')
            for (sub in nested) out.append(renderList(sub, indent + 2))
        }
        return out.toString()
    }

    private fun renderTable(el: HtmlElement): String {
        val rows = mutableListOf<HtmlElement>()
        fun collect(node: HtmlNode) {
            if (node is HtmlElement) {
                if (node.tag == "tr") rows.add(node)
                else node.children.forEach(::collect)
            }
        }
        el.children.forEach(::collect)
        if (rows.isEmpty()) return ""
        fun cells(row: HtmlElement): List<String> =
            row.children.filterIsInstance<HtmlElement>()
                .filter { it.tag == "th" || it.tag == "td" }
                .map { cell ->
                    renderInline(cell.children).trim()
                        .replace("|", "\\|")
                        .replace('\n', ' ')
                }
        val parsed = rows.map(::cells).filter { it.isNotEmpty() }
        if (parsed.isEmpty()) return ""
        val width = parsed.maxOf { it.size }
        fun rowLine(values: List<String>): String {
            val padded = values + List(width - values.size) { "" }
            return "| " + padded.joinToString(" | ") + " |"
        }
        val out = StringBuilder()
        out.append(rowLine(parsed[0])).append('\n')
        out.append("| ").append(List(width) { "---" }.joinToString(" | ")).append(" |\n")
        for (row in parsed.drop(1)) out.append(rowLine(row)).append('\n')
        return out.toString().trimEnd() + "\n"
    }

    private fun codeLanguage(pre: HtmlElement): String {
        val classes = sequenceOf(pre.attrs["class"])
            .plus(pre.children.filterIsInstance<HtmlElement>().filter { it.tag == "code" }.map { it.attrs["class"] })
            .filterNotNull()
            .flatMap { it.split(WHITESPACE_REGEX) }
        for (cls in classes) {
            if (cls.startsWith("language-")) return cls.removePrefix("language-")
            if (cls.startsWith("lang-")) return cls.removePrefix("lang-")
        }
        return ""
    }

    private fun renderInline(nodes: List<HtmlNode>): String {
        val out = StringBuilder()
        for (node in nodes) {
            when (node) {
                is HtmlText -> out.append(collapseWhitespace(node.text))
                is HtmlElement -> renderInlineElement(node, out)
            }
        }
        return out.toString()
    }

    private fun renderInlineElement(el: HtmlElement, out: StringBuilder) {
        when (el.tag) {
            "strong", "b" -> {
                val inner = renderInline(el.children).trim()
                if (inner.isNotEmpty()) out.append("**").append(inner).append("**")
            }
            "em", "i" -> {
                val inner = renderInline(el.children).trim()
                if (inner.isNotEmpty()) out.append('*').append(inner).append('*')
            }
            "s", "del", "strike" -> {
                val inner = renderInline(el.children).trim()
                if (inner.isNotEmpty()) out.append("~~").append(inner).append("~~")
            }
            "code" -> {
                val raw = rawText(el)
                if (raw.isNotBlank()) out.append('`').append(collapseWhitespace(raw).trim()).append('`')
            }
            "a" -> {
                val text = renderInline(el.children).trim()
                val href = el.attrs["href"].orEmpty().trim()
                when {
                    href.isEmpty() -> out.append(text)
                    text.isEmpty() -> out.append("[").append(href).append("](").append(href).append(')')
                    else -> out.append('[').append(text).append("](").append(href).append(')')
                }
            }
            "img" -> {
                val alt = el.attrs["alt"].orEmpty()
                val src = el.attrs["src"].orEmpty()
                out.append("![").append(alt).append("](").append(src).append(')')
            }
            "br" -> out.append('\n')
            "script", "style", "head", "title", "noscript", "template" -> Unit
            else -> out.append(renderInline(el.children))
        }
    }

    /** Raw concatenated text of a subtree (no whitespace collapsing, no markup). */
    private fun rawText(node: HtmlNode): String = when (node) {
        is HtmlText -> node.text
        is HtmlElement -> node.children.joinToString("") { rawText(it) }
    }

    private fun collapseWhitespace(text: String): String =
        WHITESPACE_REGEX.replace(text, " ")

    // ---- Entities ----

    private val NAMED_ENTITIES = mapOf(
        "quot" to "\"", "apos" to "'", "amp" to "&", "lt" to "<", "gt" to ">",
        "nbsp" to " ", "copy" to "©", "reg" to "®", "trade" to "™",
        "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "laquo" to "«", "raquo" to "»", "times" to "×", "divide" to "÷",
        "plusmn" to "±", "deg" to "°", "micro" to "µ", "middot" to "·",
        "bull" to "•", "dagger" to "†", "Dagger" to "‡", "permil" to "‰",
        "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
        "curren" to "¤", "sect" to "§", "para" to "¶", "iexcl" to "¡",
        "iquest" to "¿", "szlig" to "ß", "agrave" to "à", "egrave" to "è",
        "eacute" to "é", "aacute" to "á", "ouml" to "ö", "uuml" to "ü",
        "ntilde" to "ñ", "ccedil" to "ç",
    )
    private val NAMED_ENTITY_REGEX = Regex("""&([A-Za-z][A-Za-z0-9]+);""")
    private val NUMERIC_ENTITY_DEC_REGEX = Regex("""&#(\d{1,7});""")
    private val NUMERIC_ENTITY_HEX_REGEX = Regex("""(?i)&#x([0-9a-f]{1,6});""")

    internal fun decodeHtmlEntities(input: String): String {
        if (!input.contains('&')) return input
        // "&amp;" last: decoding it first would double-decode "&amp;lt;" to "<".
        var out = NAMED_ENTITY_REGEX.replace(input) { match ->
            val name = match.groupValues[1]
            if (name == "amp") match.value else NAMED_ENTITIES[name] ?: match.value
        }
        out = NUMERIC_ENTITY_DEC_REGEX.replace(out) { match ->
            val code = match.groupValues[1].toIntOrNull()
            if (code != null && code in 1..0x10FFFF && code !in 0xD800..0xDFFF) {
                String(Character.toChars(code))
            } else {
                match.value
            }
        }
        out = NUMERIC_ENTITY_HEX_REGEX.replace(out) { match ->
            val code = match.groupValues[1].toIntOrNull(16)
            if (code != null && code in 1..0x10FFFF && code !in 0xD800..0xDFFF) {
                String(Character.toChars(code))
            } else {
                match.value
            }
        }
        return out.replace("&amp;", "&")
    }
}
