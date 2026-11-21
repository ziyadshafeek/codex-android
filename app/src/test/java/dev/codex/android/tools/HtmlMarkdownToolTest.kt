package dev.codex.android.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for [HtmlToMarkdown] and [HtmlMarkdownTool]. */
class HtmlMarkdownToolTest {

    private fun convert(html: String): String = HtmlToMarkdown.convert(html)

    private fun toolOutput(html: String): String = runBlocking {
        val res = HtmlMarkdownTool.execute(buildJsonObject { put("html", html) })
        assertTrue(res.ok)
        res.output
    }

    @Test
    fun convert_headingsAndParagraphs() {
        val md = convert("<h1>Title</h1><h3>Small</h3><p>Body text.</p>")
        assertEquals("# Title\n\n### Small\n\nBody text.", md)
    }

    @Test
    fun convert_boldItalicStrikethroughAndInlineCode() {
        val md = convert("<p><b>bold</b> <i>italic</i> <strong>strong</strong> <em>em</em> <s>gone</s> <code>x()</code></p>")
        assertEquals("**bold** *italic* **strong** *em* ~~gone~~ `x()`", md)
    }

    @Test
    fun convert_linksAndImages() {
        val md = convert("<p>See <a href=\"https://example.com\">the site</a> and <img src=\"pic.png\" alt=\"a cat\">.</p>")
        assertTrue(md.contains("[the site](https://example.com)"))
        assertTrue(md.contains("![a cat](pic.png)"))
    }

    @Test
    fun convert_unorderedAndOrderedListsNested() {
        val md = convert(
            "<ul><li>one</li><li>two<ul><li>nested</li></ul></li></ul>" +
                "<ol><li>first</li><li>second</li></ol>",
        )
        assertTrue(md.contains("- one"))
        assertTrue(md.contains("- two"))
        assertTrue(md.contains("  - nested"))
        assertTrue(md.contains("1. first"))
        assertTrue(md.contains("2. second"))
    }

    @Test
    fun convert_fencedCodeBlockPreservesLanguage() {
        val md = convert("<pre><code class=\"language-kotlin\">fun main() {\n  println(1)\n}</code></pre>")
        assertTrue(md.contains("```kotlin\nfun main() {\n  println(1)\n}\n```"))
        // Plain <pre> without a language class gets a bare fence.
        assertTrue(convert("<pre>plain\ntext</pre>").contains("```\nplain\ntext\n```"))
    }

    @Test
    fun convert_blockquoteHrAndLineBreak() {
        val md = convert("<blockquote><p>quoted line</p></blockquote><hr><p>one<br>two</p>")
        assertTrue(md.contains("> quoted line"))
        assertTrue(md.contains("---"))
        assertTrue(md.contains("one\ntwo"))
    }

    @Test
    fun convert_tablePipeFormat() {
        val md = convert(
            "<table><tr><th>Name</th><th>Age</th></tr>" +
                "<tr><td>Ana</td><td>30</td></tr></table>",
        )
        assertEquals("| Name | Age |\n| --- | --- |\n| Ana | 30 |", md)
    }

    @Test
    fun convert_tableEscapesPipesInCells() {
        val md = convert("<table><tr><th>A|B</th></tr><tr><td>x</td></tr></table>")
        assertTrue(md.contains("| A\\|B |"))
    }

    @Test
    fun convert_stripsScriptStyleHeadAndComments() {
        val md = convert(
            "<html><head><title>Secret Title</title><style>p { color: red; }</style></head>" +
                "<body><!-- hidden --><script>alert(\"x\");</script><p>Visible</p></body></html>",
        )
        assertEquals("Visible", md)
        assertFalse(md.contains("alert"))
        assertFalse(md.contains("color"))
        assertFalse(md.contains("Secret Title"))
        assertFalse(md.contains("hidden"))
    }

    @Test
    fun convert_decodesHtmlEntities() {
        val md = convert("<p>Fish &amp; Chips &lt;tag&gt; &quot;quoted&quot; &#39;apos&#39; &#x41; &nbsp;end &copy; &mdash; done</p>")
        assertTrue(md.contains("Fish & Chips <tag> \"quoted\" 'apos' A"))
        assertTrue(md.contains("©"))
        assertTrue(md.contains("—"))
    }

    @Test
    fun convert_nestedFormattingAndLinkWithImage() {
        val md = convert("<p><a href=\"https://example.com\"><strong>Bold link</strong></a></p>")
        assertEquals("[**Bold link**](https://example.com)", md)
    }

    @Test
    fun convert_emptyAndTextOnly() {
        assertEquals("", convert(""))
        assertEquals("", convert("   "))
        assertEquals("plain text", convert("plain text"))
        assertEquals("Hello", convert("<div><span>Hello</span></div>"))
    }

    @Test
    fun convert_malformedHtmlDoesNotCrash() {
        // Unclosed tags, stray close tags, lone '<'.
        val md = convert("<p>unclosed <b>bold <div>nested</p></div> a < b")
        assertTrue(md.contains("unclosed"))
        assertTrue(md.contains("**bold nested**"))
    }

    @Test
    fun tool_capsOutputAt100kCharsWithNote() = runBlocking {
        val big = "<p>" + "word ".repeat(30_000) + "</p>" // ~150k chars of markdown
        val res = HtmlMarkdownTool.execute(buildJsonObject { put("html", big) })
        assertTrue(res.ok)
        assertTrue(res.output.length <= HtmlMarkdownTool.MAX_OUTPUT_CHARS + 100)
        assertTrue(res.output.contains("[truncated: output capped at 100000 characters]"))
    }

    @Test
    fun tool_missingHtmlArgIsError() = runBlocking {
        val res = HtmlMarkdownTool.execute(buildJsonObject {})
        assertFalse(res.ok)
        assertTrue(res.error!!.contains("Missing required argument: html"))
    }

    @Test
    fun tool_nameAndSensitivity() {
        assertEquals("html-markdown", HtmlMarkdownTool.name)
        assertFalse(HtmlMarkdownTool.sensitive)
    }
}
