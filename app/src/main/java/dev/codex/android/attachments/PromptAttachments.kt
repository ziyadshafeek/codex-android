package dev.codex.android.attachments

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import dev.codex.android.api.ChatMessage
import dev.codex.android.security.SecretScrubber
import dev.codex.android.tools.WorkspaceFileTools
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MAX_ATTACHMENTS = 3
private const val MAX_TEXT_CHARS = 6_000
private const val MAX_TEXT_BYTES = 24_000
private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
private const val MAX_PDF_BYTES = 8 * 1024 * 1024
private const val PDF_PAGES = 3

internal data class PromptAttachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val detail: String,
    val text: String? = null,
    val images: List<String> = emptyList(),
) {
    fun asMessage() = ChatMessage("user", "Attached file: $name ($detail)\n" +
        (text ?: "The following images are the attachment contents."), imageUrls = images)
}

internal class AttachmentReader(private val context: Context) {
    suspend fun read(uri: Uri): PromptAttachment = withContext(Dispatchers.IO) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Choose a file from Android Files." }
        val resolver = context.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull().let { safeName(it ?: "Attachment") }
        resolver.openInputStream(uri)?.buffered()?.use { read(it, name) }
            ?: error("Could not open $name.")
    }

    suspend fun readWorkspace(workspace: WorkspaceFileTools, path: String): PromptAttachment = withContext(Dispatchers.IO) {
        val file = workspace.resolveSafe(path)
        require(file.isFile) { "Workspace file ${safeName(path)} no longer exists." }
        val name = safeName(path)
        file.inputStream().buffered().use { read(it, name) }
    }

    private fun read(stream: InputStream, name: String): PromptAttachment {
        stream.mark(32)
        val header = ByteArray(16)
        var offset = 0
        while (offset < header.size) {
            val size = stream.read(header, offset, header.size - offset)
            if (size <= 0) break
            offset += size
        }
        stream.reset()
        val imageType = attachmentImageMime(header.copyOf(offset))
        return when {
            imageType != null -> {
                val data = readBounded(stream, MAX_IMAGE_BYTES, "$name exceeds the 2 MB image limit.")
                val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, info)
                require(info.outWidth in 1..8192 && info.outHeight in 1..8192) { "$name is unreadable or its dimensions are too large." }
                PromptAttachment(name = name, detail = "Image · vision model required", images = listOf(dataUrl(imageType, data)))
            }
            String(header, 0, offset, Charsets.US_ASCII).startsWith("%PDF-") -> renderPdf(stream, name)
            else -> {
                val data = readBounded(stream, MAX_TEXT_BYTES, "$name is too large for a text attachment. Choose a smaller excerpt.")
                val text = decodeAttachmentText(data) ?: error("$name is not supported. Choose UTF-8 text, JPEG, PNG, WebP or PDF.")
                require(text.isNotBlank()) { "$name is empty." }
                require(text.length <= MAX_TEXT_CHARS) { "$name exceeds $MAX_TEXT_CHARS characters. Choose a smaller excerpt." }
                PromptAttachment(name = name, detail = "Text · ${text.length} characters", text = SecretScrubber.scrub(text))
            }
        }
    }

    private fun renderPdf(stream: InputStream, name: String): PromptAttachment {
        val temp = File.createTempFile("prompt-pdf-", ".pdf", context.cacheDir)
        try {
            temp.writeBytes(readBounded(stream, MAX_PDF_BYTES, "$name exceeds the 8 MB PDF limit."))
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                // PdfRenderer takes ownership of its descriptor. Duplicate it so the outer descriptor is also closed on error.
                val owned = descriptor.dup()
                val renderer = try { PdfRenderer(owned) } catch (_: Exception) {
                    owned.close()
                    error("Could not open $name. The PDF may be encrypted or damaged.")
                }
                renderer.use { pdf ->
                    require(pdf.pageCount > 0) { "$name has no pages." }
                    val count = minOf(pdf.pageCount, PDF_PAGES)
                    val images = List(count) { index ->
                        pdf.openPage(index).use { page ->
                            val scale = minOf(2f, 1500f / maxOf(page.width, page.height))
                            val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val bytes = ByteArrayOutputStream()
                                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bytes)) { "Could not render $name." }
                                dataUrl("image/jpeg", bytes.toByteArray())
                            } finally { bitmap.recycle() }
                        }
                    }
                    return PromptAttachment(name = name, detail = "PDF · $count of ${pdf.pageCount} pages · vision required", images = images)
                }
            }
        } finally { temp.delete() }
    }
}

internal fun readBounded(stream: InputStream, limit: Int, message: String): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = stream.read(buffer)
        if (read == -1) return output.toByteArray()
        require(output.size() + read <= limit) { message }
        output.write(buffer, 0, read)
    }
}

internal fun decodeAttachmentText(bytes: ByteArray): String? = runCatching {
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    text.takeUnless { it.any { c -> c.isISOControl() && c !in "\r\n\t" } }
}.getOrNull()

internal fun safeName(name: String): String = SecretScrubber.scrub(name.filterNot(Char::isISOControl).trim().take(120))
    .ifBlank { "Attachment" }

internal fun attachmentImageMime(bytes: ByteArray): String? = when {
    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
    bytes.size >= 8 && bytes.copyOf(8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
    bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
    else -> null
}

private fun dataUrl(mime: String, bytes: ByteArray) = "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
