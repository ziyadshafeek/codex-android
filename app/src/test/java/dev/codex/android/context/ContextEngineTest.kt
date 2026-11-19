package dev.codex.android.context

import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.ThreadEntity
import dev.codex.android.data.TimelineMessage
import dev.codex.android.data.WorkspaceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** JVM unit tests for [ContextEngine]: /compact, /recap, /fork, /rewind, /export. */
class ContextEngineTest {

    /**
     * In-memory [ThreadHistoryStore]. Mirrors the real database contract:
     * ids are assigned on insert, and [replaceMessages] re-inserts rows, so
     * ids are NOT preserved across a rewind — callers must re-read.
     */
    private class FakeHistoryStore : ThreadHistoryStore {
        val threads = LinkedHashMap<String, ThreadEntity>()
        val messages = LinkedHashMap<String, MutableList<TimelineMessage>>()
        private var idCounter = 0L

        override suspend fun ensureWorkspace(workspace: WorkspaceEntity) = Unit

        override suspend fun listThreads(workspaceId: String): List<ThreadEntity> =
            threads.values.filter { it.workspaceId == workspaceId }

        override suspend fun createThread(workspaceId: String, title: String, mode: String): ThreadEntity {
            val entity = ThreadEntity(
                id = "thr_${threads.size + 1}",
                workspaceId = workspaceId,
                title = title,
                mode = mode,
                status = "idle",
                unread = false,
                updatedAt = System.currentTimeMillis(),
            )
            threads[entity.id] = entity
            messages[entity.id] = mutableListOf()
            return entity
        }

        override suspend fun getMessages(threadId: String): List<TimelineMessage> =
            messages[threadId].orEmpty().toList()

        override suspend fun appendMessage(message: TimelineMessage) {
            val stored = message.copy(id = ++idCounter)
            messages.getOrPut(message.threadId) { mutableListOf() } += stored
        }

        override suspend fun replaceMessages(threadId: String, messages: List<TimelineMessage>) {
            this.messages[threadId] = messages.map { it.copy(id = ++idCounter) }.toMutableList()
        }
    }

    private lateinit var tempRoot: File
    private lateinit var store: FakeHistoryStore
    private lateinit var engine: ContextEngine

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("codex-context-test").toFile()
        store = FakeHistoryStore()
        engine = ContextEngine(store, File(tempRoot, "exports"))
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private fun seedThread(): String {
        val thread = runBlocking {
            store.createThread(CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID, "Demo", "AUTO")
        }
        runBlocking {
            store.appendMessage(TimelineMessage(threadId = thread.id, role = "user", kind = "text", content = "Hello, build me a counter app."))
            store.appendMessage(TimelineMessage(threadId = thread.id, role = "assistant", kind = "text", content = "Done — created Counter.kt."))
            store.appendMessage(TimelineMessage(threadId = thread.id, role = "user", kind = "text", content = "Add a reset button please."))
        }
        return thread.id
    }

    @Test
    fun compact_preservesHistoryAsSummaryInsteadOfDeleting() = runBlocking {
        val threadId = seedThread()
        val summary = engine.compact(threadId)

        assertEquals(3, summary.messageCount)
        assertTrue(summary.summaryText.contains("3 messages"))

        // Original messages still present + compact marker appended.
        val after = store.getMessages(threadId)
        assertEquals(4, after.size)
        assertEquals("compact", after.last().kind)
    }

    @Test
    fun recap_returnsSummaryWithoutMutating() = runBlocking {
        val threadId = seedThread()
        val recap = engine.recap(threadId)

        assertTrue(recap.contains("3 messages"))
        assertEquals(3, store.getMessages(threadId).size)
    }

    @Test
    fun fork_createsSiblingThreadWithCopiedHistory() = runBlocking {
        val threadId = seedThread()
        val forkId = engine.fork(threadId)

        assertTrue(forkId.isNotBlank())
        assertTrue(forkId != threadId)

        val forked = store.getMessages(forkId)
        // 3 original + 1 fork marker.
        assertEquals(4, forked.size)
        assertEquals("fork", forked.last().kind)

        // Original thread untouched.
        assertEquals(3, store.getMessages(threadId).size)
    }

    @Test
    fun rewind_removesMessagesAfterCheckpoint() = runBlocking {
        val threadId = seedThread()
        val messages: List<TimelineMessage> = store.getMessages(threadId)
        val secondId = messages[1].id

        assertTrue(engine.rewind(threadId, secondId))
        val after = store.getMessages(threadId)
        // 2 kept + 1 rewind marker.
        assertEquals(3, after.size)
        assertTrue(after.any { it.kind == "rewind" })

        // Unknown message id returns false and changes nothing.
        assertFalse(engine.rewind(threadId, 999_999L))
    }

    @Test
    fun export_writesMarkdownFileWithAllMessages() = runBlocking {
        val threadId = seedThread()
        val file = engine.export(threadId)

        assertTrue(file.isFile)
        val text = file.readText()
        assertTrue(text.contains("Hello, build me a counter app."))
        assertTrue(text.contains("Add a reset button please."))
        assertTrue(text.contains("Messages: 3"))
    }
}
