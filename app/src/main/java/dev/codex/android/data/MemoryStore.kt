package dev.codex.android.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device memory: chat history (`messages`) and durable facts (`facts`).
 *
 * Backed by [CodexDatabase] (SQLite, `codex.db`). Follows the [PlanStore]
 * rule: every function is `suspend`, runs on [Dispatchers.IO], and never
 * throws — a failed read returns an empty list / null, a failed write is
 * swallowed, so persistence can never break a run.
 *
 * The messages table is capped at [MAX_MESSAGES] rows: after each insert,
 * rows older than the newest 500 are deleted, so the database cannot grow
 * without limit on a low-end phone.
 */
class MemoryStore(context: Context) {

    companion object {
        /** Maximum rows kept in `messages`; older rows are pruned on insert. */
        const val MAX_MESSAGES = 500
    }

    private val dbHelper = CodexDatabase(context.applicationContext)

    /** Inserts a message row. Blank content is ignored, not stored. */
    suspend fun saveMessage(role: String, content: String) = withContext(Dispatchers.IO) {
        try {
            if (content.isBlank()) return@withContext
            val values = ContentValues().apply {
                put("role", role)
                put("content", content)
                put("created_at", System.currentTimeMillis())
            }
            dbHelper.writableDatabase.use { db ->
                db.insert(CodexDatabase.TABLE_MESSAGES, null, values)
                // Prune everything except the newest MAX_MESSAGES rows.
                db.execSQL(
                    "DELETE FROM ${CodexDatabase.TABLE_MESSAGES} " +
                        "WHERE id NOT IN (" +
                        "SELECT id FROM ${CodexDatabase.TABLE_MESSAGES} " +
                        "ORDER BY id DESC LIMIT ?" +
                        ")",
                    arrayOf(MAX_MESSAGES.toString()),
                )
            }
        } catch (e: Exception) {
            // Swallowed by design — persistence must never break a run.
        }
    }

    /**
     * The newest [limit] messages, returned OLDEST first (chronological),
     * so the caller can replay them in order. Empty list on error.
     * A non-positive [limit] returns an empty list.
     */
    suspend fun recentMessages(limit: Int = 20): List<StoredMessage> =
        withContext(Dispatchers.IO) {
            try {
                if (limit <= 0) return@withContext emptyList()
                val rows = dbHelper.readableDatabase.use { db ->
                    db.query(
                        CodexDatabase.TABLE_MESSAGES,
                        arrayOf("id", "role", "content", "created_at"),
                        null,
                        null,
                        null,
                        null,
                        "id DESC",
                        limit.toString(),
                    ).use { cursor -> cursor.toStoredMessages() }
                }
                // Query fetched newest-first; flip to chronological order.
                rows.asReversed()
            } catch (e: Exception) {
                emptyList()
            }
        }

    /** Deletes all stored messages. Never throws. */
    suspend fun clearMessages() = withContext(Dispatchers.IO) {
        try {
            dbHelper.writableDatabase.use { db ->
                db.delete(CodexDatabase.TABLE_MESSAGES, null, null)
            }
        } catch (e: Exception) {
            // Ignored.
        }
    }

    /**
     * Upserts a fact by [key] (INSERT OR REPLACE) and stamps `updated_at`.
     * Blank [key] or [value] is ignored, not stored.
     */
    suspend fun saveFact(key: String, value: String, source: String = "user") =
        withContext(Dispatchers.IO) {
            try {
                if (key.isBlank() || value.isBlank()) return@withContext
                val now = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put("key", key)
                    put("value", value)
                    put("source", source)
                    put("created_at", now)
                    put("updated_at", now)
                }
                dbHelper.writableDatabase.use { db ->
                    db.insertWithOnConflict(
                        CodexDatabase.TABLE_FACTS,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
            } catch (e: Exception) {
                // Swallowed by design.
            }
        }

    /** The fact stored under [key], or null when there is none / on error. */
    suspend fun getFact(key: String): Fact? = withContext(Dispatchers.IO) {
        try {
            dbHelper.readableDatabase.use { db ->
                db.query(
                    CodexDatabase.TABLE_FACTS,
                    arrayOf("id", "key", "value", "source", "created_at", "updated_at"),
                    "key = ?",
                    arrayOf(key),
                    null,
                    null,
                    null,
                    "1",
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.toFact() else null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** All facts, ordered by key. Empty list on error. */
    suspend fun allFacts(): List<Fact> = withContext(Dispatchers.IO) {
        try {
            dbHelper.readableDatabase.use { db ->
                db.query(
                    CodexDatabase.TABLE_FACTS,
                    arrayOf("id", "key", "value", "source", "created_at", "updated_at"),
                    null,
                    null,
                    null,
                    null,
                    "key ASC",
                ).use { cursor -> cursor.toFacts() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Deletes the fact stored under [key] (no-op when absent). Never throws. */
    suspend fun deleteFact(key: String) = withContext(Dispatchers.IO) {
        try {
            dbHelper.writableDatabase.use { db ->
                db.delete(CodexDatabase.TABLE_FACTS, "key = ?", arrayOf(key))
            }
        } catch (e: Exception) {
            // Ignored.
        }
    }

    /**
     * Facts whose `key` OR `value` contains [query] (case-insensitive LIKE).
     * An empty or blank [query] returns [allFacts]. Empty list on error.
     * `%` and `_` in the query are escaped so they match literally.
     */
    suspend fun searchFacts(query: String): List<Fact> = withContext(Dispatchers.IO) {
        try {
            if (query.isBlank()) return@withContext allFacts()
            val escaped = query
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
            val pattern = "%$escaped%"
            dbHelper.readableDatabase.use { db ->
                db.query(
                    CodexDatabase.TABLE_FACTS,
                    arrayOf("id", "key", "value", "source", "created_at", "updated_at"),
                    "key COLLATE NOCASE LIKE ? ESCAPE '\\' " +
                        "OR value COLLATE NOCASE LIKE ? ESCAPE '\\'",
                    arrayOf(pattern, pattern),
                    null,
                    null,
                    "key ASC",
                ).use { cursor -> cursor.toFacts() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun Cursor.toStoredMessages(): List<StoredMessage> {
        val out = ArrayList<StoredMessage>()
        val idIdx = getColumnIndexOrThrow("id")
        val roleIdx = getColumnIndexOrThrow("role")
        val contentIdx = getColumnIndexOrThrow("content")
        val createdIdx = getColumnIndexOrThrow("created_at")
        while (moveToNext()) {
            out.add(
                StoredMessage(
                    id = getLong(idIdx),
                    role = getString(roleIdx),
                    content = getString(contentIdx),
                    createdAtMillis = getLong(createdIdx),
                ),
            )
        }
        return out
    }

    private fun Cursor.toFact(): Fact {
        return Fact(
            id = getLong(getColumnIndexOrThrow("id")),
            key = getString(getColumnIndexOrThrow("key")),
            value = getString(getColumnIndexOrThrow("value")),
            source = getString(getColumnIndexOrThrow("source")),
            createdAtMillis = getLong(getColumnIndexOrThrow("created_at")),
            updatedAtMillis = getLong(getColumnIndexOrThrow("updated_at")),
        )
    }

    private fun Cursor.toFacts(): List<Fact> {
        val out = ArrayList<Fact>()
        while (moveToNext()) out.add(toFact())
        return out
    }
}
