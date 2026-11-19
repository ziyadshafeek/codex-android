package dev.codex.android.context

import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.ThreadEntity
import dev.codex.android.data.TimelineMessage
import dev.codex.android.data.WorkspaceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Minimal session summary returned by the context engine. */
data class SessionSummary(
    val threadId: String,
    val messageCount: Int,
    val summaryText: String,
)

/**
 * Storage seam for thread history. Production uses [DatabaseThreadHistoryStore]
 * (real [CodexDatabase] methods only); JVM tests use an in-memory fake —
 * there is no `CodexDatabase.createInMemoryForTest()` and no Robolectric,
 * so the database itself is never constructed in a unit test.
 */
interface ThreadHistoryStore {
    suspend fun ensureWorkspace(workspace: WorkspaceEntity)
    suspend fun listThreads(workspaceId: String): List<ThreadEntity>
    suspend fun createThread(workspaceId: String, title: String, mode: String): ThreadEntity
    suspend fun getMessages(threadId: String): List<TimelineMessage>
    suspend fun appendMessage(message: TimelineMessage)
    suspend fun replaceMessages(threadId: String, messages: List<TimelineMessage>)
}

/** Production [ThreadHistoryStore] backed by the real [CodexDatabase]. */
class DatabaseThreadHistoryStore(
    private val database: CodexDatabase,
) : ThreadHistoryStore {
    override suspend fun ensureWorkspace(workspace: WorkspaceEntity) {
        database.upsertWorkspace(workspace)
    }

    override suspend fun listThreads(workspaceId: String): List<ThreadEntity> =
        database.listThreads(workspaceId)

    override suspend fun createThread(workspaceId: String, title: String, mode: String): ThreadEntity =
        database.createThread(workspaceId, title, mode)

    override suspend fun getMessages(threadId: String): List<TimelineMessage> =
        database.getMessages(threadId)

    override suspend fun appendMessage(message: TimelineMessage) {
        database.appendMessage(message)
    }

    override suspend fun replaceMessages(threadId: String, messages: List<TimelineMessage>) {
        database.replaceMessages(threadId, messages)
    }
}

/**
 * Context management (Milestone M6) replacing the old "clear history" behavior:
 * - `/compact`: compresses history into a summary and records a checkpoint, instead of
 *   deleting everything (fixes OpenCode #33300).
 * - `/recap`: reconstructs and returns a compact summary of the session so far.
 * - `/fork`: creates a sibling thread branching from the current thread.
 * - `/rewind`: restores the thread to a previous checkpoint message ID.
 * - `/export`: exports the full thread history as a Markdown file.
 *
 * All history goes through [ThreadHistoryStore]; all work runs on
 * [Dispatchers.IO] — never `runBlocking`, never the Main thread.
 */
class ContextEngine(
    private val history: ThreadHistoryStore,
    private val workspaceDir: File? = null,
) {
    /** Production constructor: history lives in the real [CodexDatabase]. */
    constructor(database: CodexDatabase, workspaceDir: File? = null) :
        this(DatabaseThreadHistoryStore(database), workspaceDir)

    /** Compresses history into a summary: checkpoints the thread and records a compact record. */
    suspend fun compact(threadId: String): SessionSummary = withContext(Dispatchers.IO) {
        val messages = history.getMessages(threadId)
        val summary = buildSummary(threadId, messages)
        history.appendMessage(
            TimelineMessage(threadId = threadId, role = "system", kind = "compact", content = summary)
        )
        SessionSummary(
            threadId = threadId,
            messageCount = messages.size,
            summaryText = summary,
        )
    }

    /** Returns a concise recap of the thread's history (no destructive operations). */
    suspend fun recap(threadId: String): String = withContext(Dispatchers.IO) {
        val messages = history.getMessages(threadId)
        val summary = buildSummary(threadId, messages)
        if (summary.isBlank()) "Thread $threadId has no messages yet." else summary
    }

    /**
     * Creates a sibling thread that inherits the same workspace, then forks the current
     * history into the new thread. Returns the new thread ID.
     */
    suspend fun fork(threadId: String): String = withContext(Dispatchers.IO) {
        val threads = history.listThreads(CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID)
        val current = threads.firstOrNull { it.id == threadId }
        val newTitle = "Fork of ${current?.title ?: threadId}"
        val newThread = history.createThread(
            workspaceId = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID,
            title = newTitle,
            mode = current?.mode ?: "AUTO",
        )
        val newThreadId = newThread.id
        val messages = history.getMessages(threadId)
        for (message in messages) {
            history.appendMessage(message.copy(id = 0, threadId = newThreadId))
        }
        history.appendMessage(
            TimelineMessage(
                threadId = newThreadId,
                role = "system",
                kind = "fork",
                content = "Forked from thread $threadId (${messages.size} messages).",
            )
        )
        newThreadId
    }

    /**
     * Rewinds the thread to [messageId]: deletes all messages after it (reversible only
     * if the caller has exported the thread first).
     *
     * NOTE: [ThreadHistoryStore.replaceMessages] re-inserts rows, so message
     * ids are NOT preserved across a rewind — callers must re-read the
     * thread afterwards rather than reuse old ids.
     */
    suspend fun rewind(threadId: String, messageId: Long): Boolean = withContext(Dispatchers.IO) {
        val messages = history.getMessages(threadId)
        if (messages.none { it.id == messageId }) return@withContext false
        val kept = messages.filter { it.id <= messageId }
        val removedCount = messages.size - kept.size
        history.replaceMessages(threadId, kept)
        history.appendMessage(
            TimelineMessage(
                threadId = threadId,
                role = "system",
                kind = "rewind",
                content = "Rewound to message $messageId (removed $removedCount message(s)).",
            )
        )
        true
    }

    /**
     * Exports the full thread as a Markdown document into the workspace export folder.
     * Returns the exported file.
     */
    suspend fun export(threadId: String): File = withContext(Dispatchers.IO) {
        val exportDir = workspaceDir ?: File(System.getProperty("java.io.tmpdir", "."), "codex-exports")
        exportDir.mkdirs()
        val messages = history.getMessages(threadId)
        val sanitized = threadId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outFile = File(exportDir, "$sanitized.md")
        val sb = StringBuilder()
        sb.appendLine("# Codex Thread Export — $threadId")
        sb.appendLine()
        sb.appendLine("Messages: ${messages.size}")
        sb.appendLine()
        for (msg in messages) {
            sb.appendLine("## ${msg.role} [${msg.kind}]")
            sb.appendLine()
            sb.appendLine(msg.content)
            sb.appendLine()
        }
        FileOutputStream(outFile).use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
        outFile
    }

    private fun buildSummary(threadId: String, messages: List<TimelineMessage>): String {
        if (messages.isEmpty()) return "Thread $threadId: no messages."
        val lines = messages.takeLast(5).map { msg ->
            "[${msg.role}] ${msg.content.lines().firstOrNull().orEmpty().take(100)}"
        }
        return buildString {
            appendLine("Thread $threadId summary (${messages.size} messages):")
            lines.forEach { appendLine("- $it") }
        }.trimEnd()
    }
}
