package dev.codex.android.kb

import android.content.Context
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.KnowledgeEntryRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One knowledge-base entry: a searchable note. `id` is the `knowledge_base` row id (`kb_…`). */
data class KnowledgeEntry(
    val id: String,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val createdAtMillis: Long,
    val updatedAtMillis: Long = createdAtMillis,
)

/** Result of [KnowledgeBase.syncExternalMarkdownEdits]. */
data class KnowledgeSyncResult(
    val updated: Int,
    val inserted: Int,
    val filesScanned: Int,
)

/**
 * Storage seam for the knowledge base. Production uses [DatabaseKnowledgeStore]
 * (the v3 `knowledge_base` table in the single `codex.db`); JVM tests use an
 * in-memory fake — there is no `CodexDatabase.createInMemoryForTest()` and no
 * Robolectric, so the database itself is never constructed in a unit test.
 */
interface KnowledgeStore {
    suspend fun insert(title: String, body: String, tags: List<String>, id: String? = null): String
    suspend fun get(id: String): KnowledgeEntryRow?
    suspend fun search(query: String): List<KnowledgeEntryRow>
    suspend fun list(): List<KnowledgeEntryRow>
    suspend fun update(id: String, title: String, body: String, tags: List<String>): Boolean
    suspend fun delete(id: String): Boolean
}

/** Production [KnowledgeStore] backed by the v3 `knowledge_base` table in `codex.db`. */
class DatabaseKnowledgeStore(
    private val database: CodexDatabase,
) : KnowledgeStore {
    override suspend fun insert(title: String, body: String, tags: List<String>, id: String?): String =
        if (id != null) database.insertKnowledgeEntry(title, body, tags, id = id)
        else database.insertKnowledgeEntry(title, body, tags)

    override suspend fun get(id: String): KnowledgeEntryRow? = database.getKnowledgeEntry(id)
    override suspend fun search(query: String): List<KnowledgeEntryRow> = database.searchKnowledgeEntries(query)
    override suspend fun list(): List<KnowledgeEntryRow> = database.listKnowledgeEntries()
    override suspend fun update(id: String, title: String, body: String, tags: List<String>): Boolean =
        database.updateKnowledgeEntry(id, title, body, tags)
    override suspend fun delete(id: String): Boolean = database.deleteKnowledgeEntry(id)
}

/**
 * Self-learning knowledge base (Milestone M6) with a parallel dual-write to
 * Markdown files for portability.
 *
 * Backend is the v3 `knowledge_base` table in the single `codex.db` — there
 * is deliberately NO second database file and NO FTS5: search is a LIKE
 * fallback over title/body/tags (see `CodexDatabase.searchKnowledgeEntries`).
 *
 * - Every successful solution is saved as a searchable note via [record].
 * - Each note is also persisted as `<id>.md` under `<knowledgeDir>/`
 *   (default `<filesDir>/.codex/knowledge/`, or
 *   `<workspaceDir>/.codex/knowledge/` when a workspace dir is given),
 *   human-readable, restorable, git-friendly.
 * - [syncExternalMarkdownEdits] picks up hand-edits to those `.md` files
 *   (and new files dropped in by the user) and writes them back to the DB.
 *
 * Markdown file format (the `kb-id` marker is what links a file to its row):
 * ```markdown
 * # Title
 *
 * <!-- kb-id: kb_abc123 -->
 *
 * body…
 * ```
 */
class KnowledgeBase(
    private val store: KnowledgeStore,
    private val knowledgeDir: File,
) {
    /** Production constructor: DB-backed store, Markdown under `<filesDir>/.codex/knowledge/`. */
    constructor(database: CodexDatabase, context: Context) :
        this(DatabaseKnowledgeStore(database), File(context.filesDir, ".codex/knowledge"))

    /** Production constructor with a workspace dir: Markdown under `<workspaceDir>/.codex/knowledge/`. */
    constructor(database: CodexDatabase, context: Context, workspaceDir: File?) :
        this(
            DatabaseKnowledgeStore(database),
            if (workspaceDir != null) File(workspaceDir, ".codex/knowledge")
            else File(context.filesDir, ".codex/knowledge"),
        )

    /** Saves a new note (DB row + dual-write `.md` file). Returns the saved entry. */
    suspend fun record(title: String, body: String, tags: List<String> = emptyList()): KnowledgeEntry =
        withContext(Dispatchers.IO) {
            require(title.isNotBlank()) { "Knowledge title must not be blank" }
            val trimmedTitle = title.trim()
            val id = store.insert(trimmedTitle, body, tags)
            val row = store.get(id)
            writeMarkdownFile(id, trimmedTitle, body)
            KnowledgeEntry(
                id = id,
                title = trimmedTitle,
                content = body,
                tags = tags,
                createdAtMillis = row?.createdAt ?: System.currentTimeMillis(),
                updatedAtMillis = row?.updatedAt ?: System.currentTimeMillis(),
            )
        }

    /** LIKE search over title/body/tags. Blank query returns an empty list (never "all"). */
    suspend fun search(query: String): List<KnowledgeEntry> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        store.search(query.trim()).map { it.toEntry() }
    }

    /** Lists all notes, most recently updated first. */
    suspend fun listAll(): List<KnowledgeEntry> = withContext(Dispatchers.IO) {
        store.list().map { it.toEntry() }
    }

    /** Deletes a note by id (also deletes its dual-written `.md` file). */
    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = store.delete(id)
        if (deleted) {
            File(knowledgeDir, "$id.md").delete()
            // Also drop any file that references this id via its marker (e.g. renamed file).
            knowledgeDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
                ?.forEach { file ->
                    if (readMarkerId(file) == id) file.delete()
                }
        }
        deleted
    }

    /**
     * (Re)writes one `.md` file per entry into [knowledgeDir] and returns the
     * files written, sorted by name. This is the dual-write target of [record]
     * and the source [syncExternalMarkdownEdits] reads back.
     */
    suspend fun exportMarkdown(): List<File> = withContext(Dispatchers.IO) {
        knowledgeDir.mkdirs()
        store.list().map { row ->
            val file = File(knowledgeDir, "${row.id}.md")
            file.writeText(toMarkdown(row.id, row.title, row.body))
            file
        }.sortedBy { it.name }
    }

    /**
     * Reads every `.md` file under [knowledgeDir] back into the DB:
     * - file with a known `kb-id` marker whose title/body changed → row updated;
     * - file with an unknown marker id → row inserted under that id;
     * - file with no marker → row inserted (new id) and the file rewritten
     *   with its marker, so a second sync treats it as known.
     * Files that cannot be read are skipped, never fatal.
     */
    suspend fun syncExternalMarkdownEdits(): KnowledgeSyncResult = withContext(Dispatchers.IO) {
        knowledgeDir.mkdirs()
        val files = knowledgeDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.sortedBy { it.name }.orEmpty()
        var updated = 0
        var inserted = 0
        for (file in files) {
            val parsed = runCatching { parseMarkdown(file.readText()) }.getOrNull() ?: continue
            val (title, body) = parsed
            if (title.isBlank() && body.isBlank()) continue
            val markerId = readMarkerId(file)
            val existing = markerId?.let { store.get(it) }
            // A title-less file keeps the stored title (or the file name for a new row) — never blank-wipes.
            val effectiveTitle = title.ifBlank { existing?.title ?: file.nameWithoutExtension }
            if (markerId != null) {
                if (existing != null) {
                    if (existing.title != effectiveTitle || existing.body != body) {
                        if (store.update(markerId, effectiveTitle, body, existing.tags)) updated++
                    }
                } else {
                    store.insert(effectiveTitle, body, emptyList(), id = markerId)
                    inserted++
                }
            } else {
                val id = store.insert(effectiveTitle, body, emptyList())
                file.writeText(toMarkdown(id, effectiveTitle, body))
                inserted++
            }
        }
        KnowledgeSyncResult(updated = updated, inserted = inserted, filesScanned = files.size)
    }

    /** Writes the portable Markdown dual-write for one note. */
    private fun writeMarkdownFile(id: String, title: String, body: String) {
        knowledgeDir.mkdirs()
        File(knowledgeDir, "$id.md").writeText(toMarkdown(id, title, body))
    }

    private fun toMarkdown(id: String, title: String, body: String): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine("<!-- kb-id: $id -->")
        appendLine()
        append(body)
        if (!body.endsWith("\n")) appendLine()
    }

    /** Parses a Markdown file into (title, body); the `kb-id` marker line is stripped from the body. */
    private fun parseMarkdown(text: String): Pair<String, String> {
        val lines = text.lines()
        var title = ""
        val bodyLines = mutableListOf<String>()
        var sawTitle = false
        for (line in lines) {
            when {
                !sawTitle && line.startsWith("# ") -> {
                    title = line.removePrefix("# ").trim()
                    sawTitle = true
                }
                line.trim().startsWith("<!--") && line.contains("kb-id:") -> Unit // marker, skipped
                else -> bodyLines += line
            }
        }
        return title to bodyLines.joinToString("\n").trim()
    }

    private fun readMarkerId(file: File): String? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return Regex("<!--\\s*kb-id:\\s*(\\S+)\\s*-->").find(text)?.groupValues?.getOrNull(1)
    }

    private fun KnowledgeEntryRow.toEntry(): KnowledgeEntry = KnowledgeEntry(
        id = id,
        title = title,
        content = body,
        tags = tags,
        createdAtMillis = createdAt,
        updatedAtMillis = updatedAt,
    )
}
