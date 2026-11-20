package dev.codex.android.attachments

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.codex.android.security.SecretScrubber
import dev.codex.android.tools.Tool
import dev.codex.android.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Reader
import java.io.Writer
import java.nio.charset.CodingErrorAction

internal class AttachmentReadTool(private val context: Context, private val library: FileLibrary, private val threadId: String) : Tool {
    override val name = "attachment_read"
    override val sensitive = false
    override val description = "Read any section of a file explicitly attached to this chat. Specify its attachment id, PDF page (1-based), and UTF-16 character offset (0-based). Returns up to 3500 characters; follow next offset and page until done. An image-only PDF page may require OCR if no text is returned."
    override val parametersSchema = Json.parseToJsonElement("""{"type":"object","properties":{"id":{"type":"string"},"page":{"type":"integer","minimum":1},"offset":{"type":"integer","minimum":0}},"required":["id"]}""").jsonObject

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val id = args["id"]?.jsonPrimitive?.content
            val page = args["page"]?.jsonPrimitive?.intOrNull ?: 1
            val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 0
            require(page >= 1 && offset >= 0) { "Use a positive page and a nonnegative offset." }
            val entry = library.list(threadId).firstOrNull { it.id == id } ?: error("No such original attached to this chat.")
            val file = library.resolve(entry)
            val pdf = file.inputStream().use { it.readNBytesCompat(16).toString(Charsets.US_ASCII).startsWith("%PDF-") }
            val slice = TextSlice(offset)
            var totalPages = 1
            if (pdf) {
                PDFBoxResourceLoader.init(context.applicationContext)
                val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
                PDDocument.load(file, memory).use { document ->
                    totalPages = document.numberOfPages
                    require(page <= totalPages) { "PDF has $totalPages pages." }
                    PDFTextStripper().apply { startPage = page; endPage = page }.writeText(document, slice)
                }
            } else {
                require(page == 1) { "Page applies only to PDF files." }
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                java.io.InputStreamReader(file.inputStream(), decoder).buffered().use { slice.read(it) }
                require(slice.text().none { it.isISOControl() && it !in "\r\n\t" }) { "Not UTF-8 text. Use approved Linux tools if needed." }
            }
            val text = slice.text()
            val next = when {
                slice.more -> "Next: page=$page, offset=${offset + text.length}"
                page < totalPages -> "Next: page=${page + 1}, offset=0"
                else -> "End of file."
            }
            ToolResult(true, "${entry.name} · page $page/$totalPages · offset $offset\n${SecretScrubber.scrub(text)}\n$next")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ToolResult(false, "", "Could not read attachment: ${e.message ?: "unsupported or unreadable data"}") }
    }
}

private class TextSlice(private val offset: Int) : Writer() {
    private var seen = 0L
    private val data = StringBuilder()
    val more get() = data.length > 3500
    fun text(): String {
        var end = minOf(data.length, 3500)
        if (end > 0 && more && data[end - 1].isHighSurrogate()) end--
        return data.substring(0, end)
    }
    override fun write(cbuf: CharArray, off: Int, len: Int) {
        for (index in off until off + len) {
            if (seen++ >= offset && !more) data.append(cbuf[index])
        }
    }
    fun read(reader: Reader) {
        val buffer = CharArray(4096)
        while (!more) {
            val count = reader.read(buffer)
            if (count < 0) break
            write(buffer, 0, count)
        }
    }
    override fun flush() = Unit
    override fun close() = Unit
}
