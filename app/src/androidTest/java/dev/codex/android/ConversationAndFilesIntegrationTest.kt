package dev.codex.android

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codex.android.attachments.AttachmentReadTool
import dev.codex.android.attachments.AttachmentReader
import dev.codex.android.attachments.FileLibrary
import dev.codex.android.context.ConversationContext
import dev.codex.android.context.summaryCutoff
import dev.codex.android.context.transcriptRecorder
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.TimelineMessage
import dev.codex.android.data.WorkspaceEntity
import dev.codex.android.tools.WorkspaceFileTools
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationAndFilesIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = CodexDatabase.getInstance(context)

    private suspend fun createThread(): String {
        db.upsertWorkspace(WorkspaceEntity(id = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID, name = "Test phone",
            kind = "phone", rootPath = "", createdAt = System.currentTimeMillis()))
        return db.createThread(CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID, "Conversation test", "AUTO").id
    }

    @Test fun wholeTranscriptIsPagedAndFailedCheckpointDoesNotReplaceIt() = runBlocking {
        val thread = createThread()
        try {
            repeat(180) { n ->
                db.appendMessage(TimelineMessage(threadId = thread, role = "user", kind = "text", content = "Unique entry #$n;"))
            }
            val tool = transcriptRecorder(db, thread)
            tool("tool_call", "workspace_write", "tracked-file.txt")
            tool("approval", "workspace_write", "Approved")
            tool("tool_result", "workspace_write", "M".repeat(4_000) + "TOOL_END_MARKER")
            val rows = db.getMessages(thread, limit = 200)
            val initial = ConversationContext(db)
            val seen = mutableListOf<String>()
            val summary = checkNotNull(initial.checkpoint(thread, 3000) {
                seen += it.substringAfter("Next transcript section:\n")
                "Checkpoint ${seen.size}"
            })
            assertTrue(seen.size > 2)
            val submitted = seen.joinToString("\n")
            repeat(180) { assertTrue("missing message $it", submitted.contains("Unique entry #$it;")) }
            assertTrue(submitted.contains("Approved"))
            assertTrue(submitted.contains("TOOL_END_MARKER"))
            assertEquals(rows.last().id, summaryCutoff(summary))
            var requests = 0
            val failure = runCatching { initial.checkpoint(thread, 3000) { if (++requests == 2) error("network failure") else "Partial" } }
            assertTrue(failure.isFailure)
            assertEquals(summary.id, ConversationContext(db).latest(thread)?.id)
            assertEquals(rows, db.getMessages(thread, limit = 200).filterNot { it.kind == "summary" })
        } finally { db.deleteThread(thread) }
    }

    @Test fun fullTextAndLatePdfPagesAreReadAndBinaryCopiesAreExact() = runBlocking {
        val thread = createThread()
        val workspace = WorkspaceFileTools(File(context.cacheDir, "full-file-test-workspace"))
        val store = FileLibrary(context, workspace)
        val previousLimit = store.limitMiB
        val saved = mutableListOf<dev.codex.android.attachments.ManagedFile>()
        val fixture = File(context.filesDir, "builds/full-files-test").apply { mkdirs() }
        try {
            store.limitMiB = previousLimit.coerceAtLeast(128)
            suspend fun stage(file: File) = store.promote(store.import(FileProvider.getUriForFile(context,
                "${context.packageName}.fileprovider", file)), thread)
            val text = stage(File(fixture, "large.txt").apply { writeText("x".repeat(30_000) + "Late text marker") })
            val pdfFile = File(fixture, "whole.pdf")
            val document = PdfDocument()
            try {
                repeat(5) { i ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(300, 300, i + 1).create())
                    page.canvas.drawText("Page marker ${i + 1}", 15f, 30f, Paint().apply { textSize = 16f })
                    document.finishPage(page)
                }
                pdfFile.outputStream().use { document.writeTo(it) }
            } finally { document.close() }
            val pdf = stage(pdfFile)
            val mp4 = File(fixture, "clip.mp4").apply { writeBytes(ByteArray(90_000) { it.toByte() }) }
            val binary = stage(mp4)
            val reader = AttachmentReader(context, store)
            assertTrue(reader.message(pdf, false).imageUrls.isEmpty())
            assertTrue(reader.message(pdf, true).imageUrls.isNotEmpty())
            assertTrue(reader.message(binary, true).imageUrls.isEmpty())
            assertTrue(reader.message(text, false).content.orEmpty().contains(text.vmPath))
            val tool = AttachmentReadTool(context, store, thread)
            val textResult = tool.execute(buildJsonObject { put("id", text.id); put("offset", 30_000) })
            assertTrue(textResult.error, textResult.ok)
            assertTrue(textResult.output.contains("Late text marker"))
            val pdfResult = tool.execute(buildJsonObject { put("id", pdf.id); put("page", 5) })
            assertTrue(pdfResult.error, pdfResult.ok)
            assertTrue(pdfResult.output, pdfResult.output.contains("Page marker 5"))
            val copy = store.save(binary).also { saved += it }
            assertArrayEquals(mp4.readBytes(), FileLibrary(context, workspace).resolve(copy).readBytes())
            store.limitMiB = 0
            assertTrue(runCatching { store.save(text) }.isFailure)
            store.delete(copy)
            saved.remove(copy)
            assertArrayEquals(mp4.readBytes(), store.resolve(binary).readBytes())
            assertTrue(store.list("library").none { it.key == copy.key })
        } finally {
            store.limitMiB = previousLimit
            for (item in saved) runCatching { store.delete(item) }
            fixture.deleteRecursively()
            workspace.rootDir.deleteRecursively()
            db.deleteThread(thread)
        }
    }
}
