package dev.codex.android.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import dev.codex.android.api.ChatMessage
import dev.codex.android.security.SecretScrubber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MAX_ATTACHMENTS = 3
private const val MAX_TEXT_CHARS = 6_000
private const val MAX_TEXT_BYTES = 24_000
private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
private const val MAX_PDF_BYTES = 8 * 1024 * 1024
private const val PDF_PAGES = 3

internal data class PromptAttachment(val original: ManagedFile) {
    val id get() = original.id
    val name get() = original.name
    val detail get() = "${original.bytes} bytes"
}

internal class AttachmentReader(private val context: Context, private val library: FileLibrary) {
    suspend fun message(entry: ManagedFile, imagesEnabled: Boolean): ChatMessage = withContext(Dispatchers.IO) {
        val file = library.resolve(entry)
        val header = file.inputStream().use { it.readNBytesCompat(16) }
        val mime = attachmentImageMime(header)
        val pdf = header.toString(Charsets.US_ASCII).startsWith("%PDF-")
        val path = entry.vmPath
        val details = StringBuilder("Attached file: ${entry.name} (${entry.bytes} bytes). Full original in Linux workspace: $path. " +
            "Use attachment_read with id ${entry.id} for paged UTF-8 or PDF text. Approved shell_exec/Python can inspect the original for other formats.")
        val images = if (!imagesEnabled) emptyList() else runCatching {
            when {
                mime != null && entry.bytes <= MAX_IMAGE_BYTES -> {
                    val data = file.inputStream().use { readBounded(it, MAX_IMAGE_BYTES, "Image too large.") }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                    if (bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192) listOf(dataUrl(mime, data)) else emptyList()
                }
                pdf && entry.bytes <= MAX_PDF_BYTES -> file.inputStream().use { renderPdf(it, entry.name) }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        if (images.isNotEmpty()) details.append(if (pdf) " Preview: first ${images.size} PDF pages." else " Image included inline.")
        else if (pdf || mime != null) details.append(" No inline image was sent for this file.")
        if (!pdf && mime == null && entry.bytes <= MAX_TEXT_BYTES) {
            val text = file.inputStream().use { decodeAttachmentText(readBounded(it, MAX_TEXT_BYTES, "File changed.")) }
            if (!text.isNullOrBlank()) details.append("\nText preview:\n${SecretScrubber.scrub(text.take(MAX_TEXT_CHARS))}")
        }
        ChatMessage("user", details.toString(), imageUrls = images)
    }

    private fun renderPdf(stream: InputStream, name: String): List<String> {
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
                    return images
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

internal fun InputStream.readNBytesCompat(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var used = 0
    while (used < limit) {
        val count = read(buffer, used, limit - used)
        if (count <= 0) break
        used += count
    }
    return buffer.copyOf(used)
}
