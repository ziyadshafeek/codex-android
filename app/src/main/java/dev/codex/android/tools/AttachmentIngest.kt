package dev.codex.android.tools

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** One file the user picked in chat or shared into the app. */
data class Attachment(
    val uri: Uri,
    val name: String,
    val mimeType: String?,
    val sizeBytes: Long?,
)

/** Maximum size of a single attached file (10 MB). */
const val MAX_ATTACHMENT_BYTES: Long = 10L * 1024L * 1024L

/** Maximum number of attached files in one message. */
const val MAX_ATTACHMENTS_PER_MESSAGE: Int = 3

/** Default cap on extracted text included in a chat goal. */
const val MAX_EXTRACTED_CHARS: Int = 20_000

/**
 * Shared attachment handling for the chat composer ([dev.codex.android.ui.ChatScreen] /
 * `ChatViewModel.sendGoalWithAttachments`) and Share-to-Codex
 * ([dev.codex.android.ui.ShareActivity]).
 *
 * Both flows copy the picked file into the app's private attachments
 * folder first (the sender's URI permission grant ends with the picking
 * activity, so the agent must read from our own copy), then turn it
 * into text the agent can use: images become a saved-path marker for
 * the read_image tool, PDFs / documents / text files become their
 * extracted text, and anything else is described by name, type and
 * size. Nothing here touches the network.
 */
object AttachmentIngest {

    private val IMAGE_EXTENSIONS = setOf(
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic", ".heif",
        ".svg", ".tif", ".tiff",
    )
    private val DOCUMENT_EXTENSIONS = setOf(
        ".docx", ".xlsx", ".pptx", ".doc", ".xls", ".ppt",
    )
    private val DOCUMENT_MIMES = setOf(
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )
    private val TEXT_EXTENSIONS = setOf(
        ".txt", ".md", ".markdown", ".log", ".json", ".csv", ".xml",
        ".kt", ".java", ".py", ".js", ".ts", ".toml", ".gradle",
        ".html", ".htm",
    )

    /** True when [sizeBytes] is known and over the 10 MB per-file cap. */
    fun isOverSizeLimit(sizeBytes: Long?): Boolean =
        sizeBytes != null && sizeBytes > MAX_ATTACHMENT_BYTES

    /** True when [count] is over the 3-files-per-message cap. */
    fun exceedsAttachmentCount(count: Int): Boolean =
        count > MAX_ATTACHMENTS_PER_MESSAGE

    /** File-name part after the last dot, lower-cased, or "" when there is none. */
    private fun extensionOf(name: String): String {
        val lower = name.lowercase()
        val dot = lower.lastIndexOf('.')
        return if (dot >= 0) lower.substring(dot) else ""
    }

    private fun cleanMime(mime: String?): String? =
        mime?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    fun isImage(mime: String?, name: String): Boolean =
        cleanMime(mime)?.startsWith("image/") == true ||
            extensionOf(name) in IMAGE_EXTENSIONS

    fun isPdf(mime: String?, name: String): Boolean =
        cleanMime(mime) == "application/pdf" || extensionOf(name) == ".pdf"

    fun isDocument(mime: String?, name: String): Boolean =
        cleanMime(mime) in DOCUMENT_MIMES || extensionOf(name) in DOCUMENT_EXTENSIONS

    fun isText(mime: String?, name: String): Boolean =
        cleanMime(mime)?.startsWith("text/") == true ||
            cleanMime(mime) == "application/json" ||
            cleanMime(mime) == "application/xml" ||
            extensionOf(name) in TEXT_EXTENSIONS

    /**
     * Keeps letters, digits, dot, dash and underscore; anything else
     * becomes "_". A blank result falls back to "file".
     */
    fun sanitizeFileName(name: String): String {
        val cleaned = name.trim().map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_') ch else '_'
        }.joinToString("").trim('.').take(180)
        return cleaned.ifBlank { "file" }
    }

    /** Truncates [text] to [maxChars] characters, with a note when cut. */
    fun capExtractedText(text: String, maxChars: Int = MAX_EXTRACTED_CHARS): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "\n[truncated]"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    /** Known size of [uri] via OpenableColumns, or null when unavailable. */
    private fun querySize(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null, null, null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Copies the content at [uri] into
     * `filesDir/attachments/<uuid>_<sanitised name>` and returns the file.
     *
     * Enforces the 10 MB cap: throws [IllegalArgumentException] when the
     * known size is over the cap, or when the bytes actually read run
     * over it. A failed copy deletes the partial file before throwing.
     */
    fun copyToAttachments(context: Context, uri: Uri, displayName: String): File {
        if (isOverSizeLimit(querySize(context, uri))) {
            throw IllegalArgumentException(
                "File \"$displayName\" is larger than 10 MB and was not attached."
            )
        }
        val dir = File(context.filesDir, "attachments").apply {
            if (!exists()) mkdirs()
        }
        val safeName = sanitizeFileName(displayName.ifBlank { "file" })
        val file = File(dir, "${UUID.randomUUID()}_$safeName")
        var copied: Long = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        copied += read
                        if (copied > MAX_ATTACHMENT_BYTES) {
                            throw IllegalArgumentException(
                                "File \"$displayName\" is larger than 10 MB and was not attached."
                            )
                        }
                        output.write(buf, 0, read)
                    }
                }
            } ?: throw IllegalArgumentException("Could not open \"$displayName\".")
        } catch (e: Exception) {
            runCatching { file.delete() }
            throw e
        }
        return file
    }

    /**
     * Copies [uri] into the attachments folder and turns it into a text
     * block for the agent, capped at [maxExtractedChars] characters of
     * extracted text (20,000 by default; Share-to-Codex passes 32,768).
     *
     * - Image: a marker line with the saved path, for the read_image
     *   tool. The vision model is never called here; the agent calls
     *   read_image when the user asks about the image.
     * - PDF / document / text: the extracted text, in the form
     *   "[Attached file: <name> (<kind>):]\n<text>".
     * - Anything else: name, type and size only.
     *
     * Never throws: any failure comes back as a "[Could not read ...]"
     * note so the caller can still send the message.
     */
    suspend fun ingestForChat(
        context: Context,
        uri: Uri,
        name: String,
        mimeType: String?,
        maxExtractedChars: Int = MAX_EXTRACTED_CHARS,
    ): String {
        return try {
            val file = copyToAttachments(context, uri, name)
            val path = file.absolutePath
            when {
                isImage(mimeType, name) ->
                    "[Attached image: $name — saved at $path. " +
                        "Use the read_image tool with this path to analyse it.]"
                isPdf(mimeType, name) -> extractBlock(
                    name, "PDF",
                    ReadPdfTool.execute(buildJsonObject { put("path", path) }),
                    maxExtractedChars,
                )
                isDocument(mimeType, name) -> extractBlock(
                    name, "document",
                    ReadDocumentTool.execute(buildJsonObject { put("path", path) }),
                    maxExtractedChars,
                )
                isText(mimeType, name) -> extractBlock(
                    name, "text file",
                    ReadTextFileTool.execute(buildJsonObject { put("path", path) }),
                    maxExtractedChars,
                )
                else -> {
                    val mimeLabel = mimeType ?: "unknown type"
                    "[Attached file: $name ($mimeLabel, ${formatSize(file.length())}) " +
                        "— no text extraction for this type.]"
                }
            }
        } catch (e: Exception) {
            "[Could not read $name: ${e.message ?: "unknown error"}]"
        }
    }

    private fun extractBlock(
        name: String,
        kind: String,
        result: ToolResult,
        maxChars: Int,
    ): String {
        if (!result.ok) {
            val detail = result.error?.takeIf { it.isNotBlank() }
                ?: result.output.takeIf { it.isNotBlank() }
                ?: "unknown error"
            return "[Could not read $name: $detail]"
        }
        return "[Attached file: $name ($kind):]\n${capExtractedText(result.output, maxChars)}"
    }
}
