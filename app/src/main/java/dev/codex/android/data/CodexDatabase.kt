package dev.codex.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class WorkspaceEntity(
    val id: String,
    val name: String,
    val kind: String, // "phone", "local", "github", "mcp_remote"
    val rootPath: String,
    val createdAt: Long,
)

data class ThreadEntity(
    val id: String,
    val workspaceId: String,
    val title: String,
    val mode: String, // "PLAN", "ASK", "AUTO"
    val status: String, // "idle", "running", "waiting_approval", "needs_review", "error"
    val unread: Boolean,
    val updatedAt: Long,
)

data class TimelineMessage(
    val id: Long = 0,
    val threadId: String,
    val role: String, // "user", "assistant", "tool", "system"
    val kind: String, // "text", "tool_call", "diff", "plan", "error"
    val content: String,
    val metadataJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
)

data class AutomationEntity(
    val id: String,
    val name: String,
    val workspaceId: String,
    val prompt: String,
    val intervalMinutes: Long,
    val requireWifi: Boolean,
    val requireCharging: Boolean,
    val enabled: Boolean,
    val lastRunAt: Long?,
    val lastStatus: String?,
)

data class McpServerEntity(
    val id: String,
    val name: String,
    val endpointUrl: String,
    val enabled: Boolean,
    // Auth bearer token is stored in KeyStore under "mcp_token_<id>", NOT in SQLite.
)

/** One row of the v3 `knowledge_base` table (H4). `tags` is a plain list; it is stored comma-joined. */
data class KnowledgeEntryRow(
    val id: String,
    val title: String,
    val body: String,
    val tags: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * The single shared SQLite database for Codex on-device memory.
 *
 * Plain [SQLiteOpenHelper] on purpose — no Room, no new dependency, so the
 * APK stays small on low-end phones. No network, no API key, no secrets.
 *
 * Tables (exact names are part of the contract — [MemoryStore] and the
 * SkillStore both use them, so do not rename them):
 * - `messages`: chat history, replayed in chronological order (v2 adds
 *   `thread_id`, `kind`, `metadata_json` — see below).
 * - `facts`: durable user facts, keyed by a unique `key`.
 * - `skills`: installed skills (owned by SkillStore; the table lives here
 *   so there is exactly one database file and one helper).
 * - `workspaces`, `threads`, `automations`, `mcp_servers`, `audit_log`:
 *   v2 backend tables (multi-thread / workspace / automation / MCP audit),
 *   merged in from the drop-in backend spec — there is exactly one database
 *   file (`codex.db`) and one helper, never a second database.
 *
 * v2 -> v3 migration notes (see [onUpgrade]):
 * - Adds the `knowledge_base` table (H4 knowledge base) and
 *   [replaceMessages] (H4 context rewind). Both are additive only:
 *   no existing table, column, or row is touched.
 * - `knowledge_base` columns: `id TEXT PRIMARY KEY`, `title`,
 *   `body`, `tags` (comma-joined, '' when empty), `created_at`,
 *   `updated_at` (epoch millis). Search is a LIKE fallback over
 *   title/body/tags — there is deliberately NO FTS5 table: the
 *   knowledge base lives in this single database (`codex.db`), never
 *   in a second database file.
 *
 * v1 -> v2 migration notes (see [onUpgrade]):
 * - The v1 `messages` DDL is preserved except for the three added columns.
 *   `thread_id` is deliberately NULLABLE (the drop-in had it NOT NULL):
 *   pre-v2 rows have no thread, and [MemoryStore.saveMessage] inserts only
 *   role/content/created_at, so a NOT NULL column with no default would
 *   break every existing insert. `kind` / `metadata_json` are NOT NULL
 *   with defaults ('text' / '{}'), so those same inserts keep working
 *   unchanged and pick up the defaults.
 * - `facts` and `skills` are untouched by the migration.
 */
class CodexDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "codex.db"
        const val DATABASE_VERSION = 3

        const val TABLE_MESSAGES = "messages"
        const val TABLE_FACTS = "facts"
        const val TABLE_SKILLS = "skills"
        const val TABLE_WORKSPACES = "workspaces"
        const val TABLE_THREADS = "threads"
        const val TABLE_AUTOMATIONS = "automations"
        const val TABLE_MCP_SERVERS = "mcp_servers"
        const val TABLE_AUDIT_LOG = "audit_log"
        const val TABLE_KNOWLEDGE_BASE = "knowledge_base"

        const val DEFAULT_PHONE_WORKSPACE_ID = "ws_phone"

        @Volatile
        private var instance: CodexDatabase? = null

        fun getInstance(context: Context): CodexDatabase {
            return instance ?: synchronized(this) {
                instance ?: CodexDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        // v2 tables first: threads references workspaces, messages references
        // threads, so dependency order matters while FK constraints are on.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_WORKSPACES (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                kind TEXT NOT NULL,
                root_path TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_THREADS (
                id TEXT PRIMARY KEY,
                workspace_id TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
                title TEXT NOT NULL,
                mode TEXT NOT NULL DEFAULT 'AUTO',
                status TEXT NOT NULL DEFAULT 'idle',
                unread INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // v1 messages DDL, extended with the v2 columns (see class KDoc for
        // why thread_id is nullable and kind/metadata_json are defaulted).
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_MESSAGES (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "thread_id TEXT REFERENCES threads(id) ON DELETE CASCADE, " +
                "role TEXT NOT NULL, " +
                "kind TEXT NOT NULL DEFAULT 'text', " +
                "content TEXT NOT NULL, " +
                "metadata_json TEXT NOT NULL DEFAULT '{}', " +
                "created_at INTEGER NOT NULL" +
                ")",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_thread ON messages(thread_id, id)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_FACTS (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "key TEXT NOT NULL UNIQUE, " +
                "value TEXT NOT NULL, " +
                "source TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL" +
                ")",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_SKILLS (" +
                "id TEXT PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "description TEXT NOT NULL, " +
                "instructions TEXT NOT NULL, " +
                "tools_json TEXT NOT NULL, " +
                "version INTEGER NOT NULL, " +
                "enabled INTEGER NOT NULL" +
                ")",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_AUTOMATIONS (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                workspace_id TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
                prompt TEXT NOT NULL,
                interval_minutes INTEGER NOT NULL,
                require_wifi INTEGER NOT NULL DEFAULT 0,
                require_charging INTEGER NOT NULL DEFAULT 0,
                enabled INTEGER NOT NULL DEFAULT 1,
                last_run_at INTEGER,
                last_status TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_MCP_SERVERS (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                endpoint_url TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_AUDIT_LOG (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                thread_id TEXT NOT NULL,
                tool_name TEXT NOT NULL,
                decision TEXT NOT NULL,
                summary TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_KNOWLEDGE_BASE (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                tags TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // Seed built-in "This Phone" workspace so the app works immediately.
        seedPhoneWorkspace(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 -> v2: add the new tables (idempotent), migrate messages,
            // and seed the "This Phone" workspace if it does not exist.
            // facts/skills are untouched.
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_WORKSPACES (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    root_path TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_THREADS (
                    id TEXT PRIMARY KEY,
                    workspace_id TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
                    title TEXT NOT NULL,
                    mode TEXT NOT NULL DEFAULT 'AUTO',
                    status TEXT NOT NULL DEFAULT 'idle',
                    unread INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_AUTOMATIONS (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    workspace_id TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
                    prompt TEXT NOT NULL,
                    interval_minutes INTEGER NOT NULL,
                    require_wifi INTEGER NOT NULL DEFAULT 0,
                    require_charging INTEGER NOT NULL DEFAULT 0,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    last_run_at INTEGER,
                    last_status TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_MCP_SERVERS (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    endpoint_url TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_AUDIT_LOG (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    thread_id TEXT NOT NULL,
                    tool_name TEXT NOT NULL,
                    decision TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    duration_ms INTEGER NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            // SQLite has no ADD COLUMN IF NOT EXISTS: guard each ALTER by
            // checking PRAGMA table_info first, and swallow failures so a
            // re-run (e.g. partial upgrade) is safe. Existing rows keep
            // their data; new columns are nullable/defaulted (see KDoc).
            addColumnIfMissing(db, TABLE_MESSAGES, "thread_id TEXT")
            addColumnIfMissing(db, TABLE_MESSAGES, "kind TEXT NOT NULL DEFAULT 'text'")
            addColumnIfMissing(db, TABLE_MESSAGES, "metadata_json TEXT NOT NULL DEFAULT '{}'")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_thread ON messages(thread_id, id)")
            seedPhoneWorkspace(db)
        }
        if (oldVersion < 3) {
            // v2 -> v3: add the knowledge_base table only (idempotent).
            // No existing table is altered and no row is touched.
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_KNOWLEDGE_BASE (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    tags TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    private fun seedPhoneWorkspace(db: SQLiteDatabase) {
        val cv = ContentValues().apply {
            put("id", DEFAULT_PHONE_WORKSPACE_ID)
            put("name", "This Phone")
            put("kind", "phone")
            put("root_path", "")
            put("created_at", System.currentTimeMillis())
        }
        // CONFLICT_IGNORE (not REPLACE): never overwrite an existing
        // ws_phone row, and safe to run on both fresh installs and upgrades.
        db.insertWithOnConflict("workspaces", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIdx >= 0 && c.getString(nameIdx) == column) return true
            }
        }
        return false
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, columnDef: String) {
        val column = columnDef.substringBefore(' ').trim()
        if (column.isEmpty() || hasColumn(db, table, column)) return
        try {
            db.execSQL("ALTER TABLE $table ADD COLUMN $columnDef")
        } catch (e: Exception) {
            // Column already exists (e.g. partial re-run) — safe to ignore.
        }
    }

    // --- Workspaces ---

    suspend fun listWorkspaces(): List<WorkspaceEntity> = withContext(Dispatchers.IO) {
        val result = mutableListOf<WorkspaceEntity>()
        readableDatabase.rawQuery(
            "SELECT id, name, kind, root_path, created_at FROM workspaces ORDER BY created_at ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                result += WorkspaceEntity(
                    id = c.getString(0),
                    name = c.getString(1),
                    kind = c.getString(2),
                    rootPath = c.getString(3),
                    createdAt = c.getLong(4),
                )
            }
        }
        result
    }

    suspend fun upsertWorkspace(ws: WorkspaceEntity) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("id", ws.id)
            put("name", ws.name)
            put("kind", ws.kind)
            put("root_path", ws.rootPath)
            put("created_at", ws.createdAt)
        }
        writableDatabase.insertWithOnConflict("workspaces", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // --- Threads ---

    suspend fun createThread(
        workspaceId: String = DEFAULT_PHONE_WORKSPACE_ID,
        title: String = "New thread",
        mode: String = "AUTO",
    ): ThreadEntity = withContext(Dispatchers.IO) {
        val entity = ThreadEntity(
            id = "thr_${UUID.randomUUID().toString().take(12)}",
            workspaceId = workspaceId,
            title = title,
            mode = mode,
            status = "idle",
            unread = false,
            updatedAt = System.currentTimeMillis(),
        )
        val cv = ContentValues().apply {
            put("id", entity.id)
            put("workspace_id", entity.workspaceId)
            put("title", entity.title)
            put("mode", entity.mode)
            put("status", entity.status)
            put("unread", 0)
            put("updated_at", entity.updatedAt)
        }
        writableDatabase.insert("threads", null, cv)
        entity
    }

    suspend fun listThreads(workspaceId: String? = null): List<ThreadEntity> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<ThreadEntity>()
            val query = if (workspaceId != null) {
                "SELECT id, workspace_id, title, mode, status, unread, updated_at FROM threads WHERE workspace_id = ? ORDER BY updated_at DESC"
            } else {
                "SELECT id, workspace_id, title, mode, status, unread, updated_at FROM threads ORDER BY updated_at DESC"
            }
            val args = if (workspaceId != null) arrayOf(workspaceId) else null
            readableDatabase.rawQuery(query, args).use { c ->
                while (c.moveToNext()) {
                    result += ThreadEntity(
                        id = c.getString(0),
                        workspaceId = c.getString(1),
                        title = c.getString(2),
                        mode = c.getString(3),
                        status = c.getString(4),
                        unread = c.getInt(5) != 0,
                        updatedAt = c.getLong(6),
                    )
                }
            }
            result
        }

    suspend fun updateThreadState(
        threadId: String,
        status: String? = null,
        title: String? = null,
        unread: Boolean? = null,
    ) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            status?.let { put("status", it) }
            title?.let { put("title", it) }
            unread?.let { put("unread", if (it) 1 else 0) }
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("threads", cv, "id = ?", arrayOf(threadId))
    }

    suspend fun deleteThread(threadId: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("threads", "id = ?", arrayOf(threadId))
    }

    // --- Messages ---

    suspend fun appendMessage(msg: TimelineMessage): Long = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("thread_id", msg.threadId)
            put("role", msg.role)
            put("kind", msg.kind)
            put("content", msg.content)
            put("metadata_json", msg.metadataJson)
            put("created_at", msg.createdAt)
        }
        val rowId = writableDatabase.insert("messages", null, cv)
        updateThreadState(msg.threadId)
        rowId
    }

    suspend fun getMessages(threadId: String, limit: Int = 200, mostRecent: Boolean = false, kind: String? = null): List<TimelineMessage> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<TimelineMessage>()
            readableDatabase.rawQuery(
                """
                SELECT id, thread_id, role, kind, content, metadata_json, created_at
                FROM messages
                WHERE thread_id = ? ${if (kind != null) "AND kind = ?" else ""}
                ORDER BY id ${if (mostRecent) "DESC" else "ASC"}
                LIMIT ?
                """.trimIndent(),
                listOfNotNull(threadId, kind, limit.toString()).toTypedArray()
            ).use { c ->
                while (c.moveToNext()) {
                    result += TimelineMessage(
                        id = c.getLong(0),
                        // Pre-v2 rows have NULL thread_id/kind/metadata_json;
                        // map them to sensible defaults. (Such rows are only
                        // reachable here if their thread_id was later set.)
                        threadId = c.getString(1) ?: "",
                        role = c.getString(2),
                        kind = c.getString(3) ?: "text",
                        content = c.getString(4),
                        metadataJson = c.getString(5) ?: "{}",
                        createdAt = c.getLong(6),
                    )
                }
            }
            if (mostRecent) result.asReversed() else result
        }

    /**
     * Replaces ALL messages of [threadId] with [messages], in order, in a
     * single transaction (H4 context rewind). Message ids are NOT preserved:
     * each row is re-inserted and gets a fresh autoincrement id, so callers
     * must re-read with [getMessages] afterwards (rewind does exactly that
     * via the returned marker). An empty list deletes every message.
     */
    suspend fun replaceMessages(threadId: String, messages: List<TimelineMessage>) =
        withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete("messages", "thread_id = ?", arrayOf(threadId))
                for (msg in messages) {
                    val cv = ContentValues().apply {
                        put("thread_id", threadId)
                        put("role", msg.role)
                        put("kind", msg.kind)
                        put("content", msg.content)
                        put("metadata_json", msg.metadataJson)
                        put("created_at", msg.createdAt)
                    }
                    db.insert("messages", null, cv)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            updateThreadState(threadId)
        }

    // --- Knowledge base (v3, H4) ---

    suspend fun insertKnowledgeEntry(
        title: String,
        body: String,
        tags: List<String> = emptyList(),
        id: String = "kb_${UUID.randomUUID().toString().take(12)}",
    ): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("id", id)
            put("title", title)
            put("body", body)
            put("tags", tags.joinToString(","))
            put("created_at", now)
            put("updated_at", now)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_KNOWLEDGE_BASE, null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
        id
    }

    suspend fun getKnowledgeEntry(id: String): KnowledgeEntryRow? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT id, title, body, tags, created_at, updated_at FROM $TABLE_KNOWLEDGE_BASE WHERE id = ?",
            arrayOf(id)
        ).use { c ->
            if (!c.moveToFirst()) return@withContext null
            rowToKnowledgeEntry(c)
        }
    }

    /**
     * LIKE search over title/body/tags (case-insensitive for ASCII in
     * SQLite LIKE). The query is escaped so `%`/`_`/`\` in user input
     * match literally. Blank query returns an empty list (never "all").
     * This is the ONLY search: there is no FTS5 table by design.
     */
    suspend fun searchKnowledgeEntries(query: String): List<KnowledgeEntryRow> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext emptyList()
            val like = "%${escapeLike(trimmed)}%"
            val result = mutableListOf<KnowledgeEntryRow>()
            readableDatabase.rawQuery(
                """
                SELECT id, title, body, tags, created_at, updated_at
                FROM $TABLE_KNOWLEDGE_BASE
                WHERE title LIKE ? ESCAPE '\' OR body LIKE ? ESCAPE '\' OR tags LIKE ? ESCAPE '\'
                ORDER BY updated_at DESC
                """.trimIndent(),
                arrayOf(like, like, like)
            ).use { c ->
                while (c.moveToNext()) result += rowToKnowledgeEntry(c)
            }
            result
        }

    suspend fun listKnowledgeEntries(): List<KnowledgeEntryRow> = withContext(Dispatchers.IO) {
        val result = mutableListOf<KnowledgeEntryRow>()
        readableDatabase.rawQuery(
            "SELECT id, title, body, tags, created_at, updated_at FROM $TABLE_KNOWLEDGE_BASE ORDER BY updated_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) result += rowToKnowledgeEntry(c)
        }
        result
    }

    suspend fun updateKnowledgeEntry(
        id: String,
        title: String,
        body: String,
        tags: List<String> = emptyList(),
    ): Boolean = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("title", title)
            put("body", body)
            put("tags", tags.joinToString(","))
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(TABLE_KNOWLEDGE_BASE, cv, "id = ?", arrayOf(id)) > 0
    }

    suspend fun deleteKnowledgeEntry(id: String): Boolean = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_KNOWLEDGE_BASE, "id = ?", arrayOf(id)) > 0
    }

    private fun rowToKnowledgeEntry(c: android.database.Cursor): KnowledgeEntryRow {
        val tagsRaw = c.getString(3).orEmpty()
        return KnowledgeEntryRow(
            id = c.getString(0),
            title = c.getString(1),
            body = c.getString(2),
            tags = if (tagsRaw.isBlank()) emptyList() else tagsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            createdAt = c.getLong(4),
            updatedAt = c.getLong(5),
        )
    }

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    // --- Automations ---

    suspend fun upsertAutomation(a: AutomationEntity) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("id", a.id)
            put("name", a.name)
            put("workspace_id", a.workspaceId)
            put("prompt", a.prompt)
            put("interval_minutes", a.intervalMinutes)
            put("require_wifi", if (a.requireWifi) 1 else 0)
            put("require_charging", if (a.requireCharging) 1 else 0)
            put("enabled", if (a.enabled) 1 else 0)
            if (a.lastRunAt != null) put("last_run_at", a.lastRunAt) else putNull("last_run_at")
            if (a.lastStatus != null) put("last_status", a.lastStatus) else putNull("last_status")
        }
        writableDatabase.insertWithOnConflict("automations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun getAutomation(id: String): AutomationEntity? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            """
            SELECT id, name, workspace_id, prompt, interval_minutes, require_wifi,
                   require_charging, enabled, last_run_at, last_status
            FROM automations WHERE id = ?
            """.trimIndent(),
            arrayOf(id)
        ).use { c ->
            if (!c.moveToFirst()) return@withContext null
            AutomationEntity(
                id = c.getString(0),
                name = c.getString(1),
                workspaceId = c.getString(2),
                prompt = c.getString(3),
                intervalMinutes = c.getLong(4),
                requireWifi = c.getInt(5) != 0,
                requireCharging = c.getInt(6) != 0,
                enabled = c.getInt(7) != 0,
                lastRunAt = if (c.isNull(8)) null else c.getLong(8),
                lastStatus = if (c.isNull(9)) null else c.getString(9),
            )
        }
    }

    suspend fun listAutomations(): List<AutomationEntity> = withContext(Dispatchers.IO) {
        val out = mutableListOf<AutomationEntity>()
        readableDatabase.rawQuery(
            """
            SELECT id, name, workspace_id, prompt, interval_minutes, require_wifi,
                   require_charging, enabled, last_run_at, last_status
            FROM automations ORDER BY name ASC
            """.trimIndent(),
            null
        ).use { c ->
            while (c.moveToNext()) {
                out += AutomationEntity(
                    id = c.getString(0),
                    name = c.getString(1),
                    workspaceId = c.getString(2),
                    prompt = c.getString(3),
                    intervalMinutes = c.getLong(4),
                    requireWifi = c.getInt(5) != 0,
                    requireCharging = c.getInt(6) != 0,
                    enabled = c.getInt(7) != 0,
                    lastRunAt = if (c.isNull(8)) null else c.getLong(8),
                    lastStatus = if (c.isNull(9)) null else c.getString(9),
                )
            }
        }
        out
    }

    // --- MCP Servers & Audit Log ---

    suspend fun upsertMcpServer(server: McpServerEntity) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("id", server.id)
            put("name", server.name)
            put("endpoint_url", server.endpointUrl)
            put("enabled", if (server.enabled) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("mcp_servers", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun listMcpServers(onlyEnabled: Boolean = false): List<McpServerEntity> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<McpServerEntity>()
            val sql = if (onlyEnabled) {
                "SELECT id, name, endpoint_url, enabled FROM mcp_servers WHERE enabled = 1 ORDER BY name ASC"
            } else {
                "SELECT id, name, endpoint_url, enabled FROM mcp_servers ORDER BY name ASC"
            }
            readableDatabase.rawQuery(sql, null).use { c ->
                while (c.moveToNext()) {
                    out += McpServerEntity(
                        id = c.getString(0),
                        name = c.getString(1),
                        endpointUrl = c.getString(2),
                        enabled = c.getInt(3) != 0,
                    )
                }
            }
            out
        }

    /**
     * Hard-deletes an MCP server row. Returns true when a row was
     * removed, false when the id was unknown. The caller drops the
     * live connection first (see [dev.codex.android.mcp.McpManager.deleteServer]).
     */
    suspend fun deleteMcpServer(serverId: String): Boolean = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_MCP_SERVERS, "id = ?", arrayOf(serverId)) > 0
    }

    suspend fun recordAudit(
        threadId: String,
        toolName: String,
        decision: String,
        summary: String,
        durationMs: Long,
    ) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("thread_id", threadId)
            put("tool_name", toolName)
            put("decision", decision)
            put("summary", summary.take(300))
            put("duration_ms", durationMs)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("audit_log", null, cv)
    }
}
