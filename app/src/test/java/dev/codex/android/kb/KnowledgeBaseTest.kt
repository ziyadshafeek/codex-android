package dev.codex.android.kb

import dev.codex.android.data.KnowledgeEntryRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** JVM unit tests for [KnowledgeBase]: LIKE search, dual-write Markdown files, delete cleanup, sync. */
class KnowledgeBaseTest {

    /** In-memory [KnowledgeStore] with LIKE-style (case-insensitive substring) search. */
    private class FakeKnowledgeStore : KnowledgeStore {
        val rows = LinkedHashMap<String, KnowledgeEntryRow>()
        private var counter = 0

        override suspend fun insert(title: String, body: String, tags: List<String>, id: String?): String {
            val rowId = id ?: "kb_test_${++counter}"
            val now = System.currentTimeMillis()
            rows[rowId] = KnowledgeEntryRow(
                id = rowId,
                title = title,
                body = body,
                tags = tags,
                createdAt = rows[rowId]?.createdAt ?: now,
                updatedAt = now,
            )
            return rowId
        }

        override suspend fun get(id: String): KnowledgeEntryRow? = rows[id]

        override suspend fun search(query: String): List<KnowledgeEntryRow> {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            return rows.values.filter { row ->
                row.title.contains(q, ignoreCase = true) ||
                    row.body.contains(q, ignoreCase = true) ||
                    row.tags.any { it.contains(q, ignoreCase = true) }
            }
        }

        override suspend fun list(): List<KnowledgeEntryRow> = rows.values.toList()

        override suspend fun update(id: String, title: String, body: String, tags: List<String>): Boolean {
            val existing = rows[id] ?: return false
            rows[id] = existing.copy(title = title, body = body, tags = tags, updatedAt = System.currentTimeMillis())
            return true
        }

        override suspend fun delete(id: String): Boolean = rows.remove(id) != null
    }

    private lateinit var tempRoot: File
    private lateinit var store: FakeKnowledgeStore
    private lateinit var kb: KnowledgeBase

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("codex-kb-test").toFile()
        store = FakeKnowledgeStore()
        kb = KnowledgeBase(store, File(tempRoot, "knowledge"))
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun recordAndSearch_findsBySubstringAndDualWritesMarkdown() = runBlocking {
        val entry = kb.record("Fix Room DB expiry", "Render free DB expires after 30 days; migrate to Neon.")
        assertTrue(entry.id.isNotBlank())

        val hits = kb.search("Render free DB")
        assertEquals(1, hits.size)
        assertEquals("Fix Room DB expiry", hits[0].title)

        // Dual-write: a Markdown file exists under knowledgeDir/.
        val mdFiles = File(tempRoot, "knowledge").listFiles { f -> f.name.endsWith(".md") }
        assertTrue(!mdFiles.isNullOrEmpty())
        assertTrue(mdFiles.first().readText().contains("Render free DB expires"))
        assertTrue(mdFiles.first().readText().contains("<!-- kb-id: ${entry.id} -->"))
    }

    @Test
    fun search_blankQueryReturnsEmpty() = runBlocking {
        kb.record("Some note", "some content")
        assertTrue(kb.search("").isEmpty())
        assertTrue(kb.search("   ").isEmpty())
    }

    @Test
    fun delete_removesRowAndMarkdownFile() = runBlocking {
        val entry = kb.record("Temp note", "temporary content")
        assertEquals(1, kb.listAll().size)

        assertTrue(kb.delete(entry.id))
        assertTrue(kb.listAll().isEmpty())
        assertFalse(kb.delete(entry.id))

        val mdFiles = File(tempRoot, "knowledge").listFiles { f -> f.name.endsWith(".md") }
        assertTrue(mdFiles.isNullOrEmpty())
    }

    @Test
    fun exportMarkdown_writesOneFilePerEntry() = runBlocking {
        kb.record("Note one", "first body")
        kb.record("Note two", "second body")

        val files = kb.exportMarkdown()
        assertEquals(2, files.size)
        assertTrue(files.all { it.isFile })
        val combined = files.joinToString("\n") { it.readText() }
        assertTrue(combined.contains("first body"))
        assertTrue(combined.contains("second body"))
    }

    @Test
    fun syncExternalMarkdownEdits_picksUpHandEditsAndNewFiles() = runBlocking {
        val entry = kb.record("Sync note", "original body")
        val mdFile = File(File(tempRoot, "knowledge"), "${entry.id}.md")
        assertTrue(mdFile.isFile)

        // Hand-edit the Markdown file's body in place.
        mdFile.writeText("# Sync note\n\n<!-- kb-id: ${entry.id} -->\n\nedited body here\n")
        val firstSync = kb.syncExternalMarkdownEdits()
        assertEquals(1, firstSync.updated)
        assertEquals(0, firstSync.inserted)
        assertEquals("edited body here", kb.search("edited body here")[0].content)

        // Drop in a brand-new file with no marker: it is inserted and gets a marker.
        val newFile = File(File(tempRoot, "knowledge"), "hand-added.md")
        newFile.writeText("# Hand added\n\nfresh content\n")
        val secondSync = kb.syncExternalMarkdownEdits()
        assertEquals(0, secondSync.updated)
        assertEquals(1, secondSync.inserted)
        assertEquals(2, kb.listAll().size)
        assertTrue(kb.search("fresh content").size == 1)
        assertTrue(newFile.readText().contains("<!-- kb-id: "))
    }
}
