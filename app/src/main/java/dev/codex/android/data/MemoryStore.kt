package dev.codex.android.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import kotlinx.coroutines.CancellationException
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
        const val MAX_FACTS = 200
    }

    // One shared database: getInstance, never a second helper — a
    // second CodexDatabase opens a second connection to the same file,
    // and .use on its handle would close the singleton's database.
    private val dbHelper = CodexDatabase.getInstance(context)

    /** Inserts a message row. Blank content is ignored, not stored. */
    suspend fun saveMessage(role: String, content: String) = withContext(Dispatchers.IO) {
        try {
            if (content.isBlank()) return@withContext
            val values = ContentValues().apply {
                put("role", role)
                put("content", content)
                put("created_at", System.currentTimeMillis())
            }
            dbHelper.writableDatabase.let { db ->
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
                val rows = dbHelper.readableDatabase.let { db ->
                    db.query(
                        CodexDatabase.TABLE_MESSAGES,
                        arrayOf("id", "role", "content", "created_at", "kind"),
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
            dbHelper.writableDatabase.let { db ->
                db.delete(CodexDatabase.TABLE_MESSAGES, null, null)
            }
        } catch (e: Exception) {
            // Ignored.
        }
    }

    /** Preserve creation time on edits and bound storage in the same transaction. */
    suspend fun saveFact(key: String, value: String, source: String = "user"): Boolean =
        withContext(Dispatchers.IO) {
            if (key.isBlank() || value.isBlank()) return@withContext false
            try {
                val db = dbHelper.writableDatabase
                db.beginTransaction()
                try {
                    val now = System.currentTimeMillis()
                    val values = ContentValues().apply {
                        put("value", value)
                        put("source", source)
                        put("updated_at", now)
                    }
                    val updated = db.update(CodexDatabase.TABLE_FACTS, values, "key = ?", arrayOf(key)) != 0
                    val saved = if (updated) true else {
                        val count = db.rawQuery("SELECT count(*) FROM ${CodexDatabase.TABLE_FACTS}", null)
                            .use { it.moveToFirst(); it.getInt(0) }
                        if (count >= MAX_FACTS) false else {
                            values.put("key", key)
                            values.put("created_at", now)
                            db.insertOrThrow(CodexDatabase.TABLE_FACTS, null, values) != -1L
                        }
                    }
                    db.setTransactionSuccessful()
                    saved
                } finally {
                    db.endTransaction()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }

    /** The fact stored under [key], or null when there is none / on error. */
    suspend fun getFact(key: String): Fact? = withContext(Dispatchers.IO) {
        try {
            dbHelper.readableDatabase.let { db ->
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
            dbHelper.readableDatabase.let { db ->
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

    /** True only if a row was removed. */
    suspend fun deleteFact(key: String): Boolean = deleteFacts("key = ? AND source = ?", arrayOf(key, "user")) > 0

    /** True when the database operation succeeded, even if already empty. */
    suspend fun clearFacts(): Boolean = deleteFacts("source = ?", arrayOf("user")) >= 0

    private suspend fun deleteFacts(where: String?, args: Array<String>?): Int = withContext(Dispatchers.IO) {
        try {
            dbHelper.writableDatabase.delete(CodexDatabase.TABLE_FACTS, where, args)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            -1
        }
    }

    internal enum class ReplaceResult { SAVED, CHANGED, FAILED }

    /** Update only user notes. Abort if anything changed while the editor was open. */
    internal suspend fun replaceUserFacts(expected: Map<String, String>, updated: Map<String, String>): ReplaceResult =
        withContext(Dispatchers.IO) {
            try {
                val db = dbHelper.writableDatabase
                db.beginTransaction()
                try {
                    val saved = db.query(CodexDatabase.TABLE_FACTS, arrayOf("key", "value"), "source = ?",
                        arrayOf("user"), null, null, null).use { cursor ->
                        buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
                    }
                    if (expected != saved) return@withContext ReplaceResult.CHANGED
                    val now = System.currentTimeMillis()
                    for (key in saved.keys - updated.keys) {
                        db.delete(CodexDatabase.TABLE_FACTS, "key = ? AND source = ?", arrayOf(key, "user"))
                    }
                    for ((key, text) in updated) {
                        if (saved[key] == text) continue
                        val values = ContentValues().apply { put("value", text); put("updated_at", now) }
                        if (key in saved) db.update(CodexDatabase.TABLE_FACTS, values, "key = ? AND source = ?", arrayOf(key, "user"))
                        else {
                            values.put("key", key)
                            values.put("source", "user")
                            values.put("created_at", now)
                            db.insertOrThrow(CodexDatabase.TABLE_FACTS, null, values)
                        }
                    }
                    db.setTransactionSuccessful()
                    ReplaceResult.SAVED
                } finally {
                    db.endTransaction()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ReplaceResult.FAILED
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
            dbHelper.readableDatabase.let { db ->
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
        val kindIdx = getColumnIndexOrThrow("kind")
        while (moveToNext()) {
            out.add(
                StoredMessage(
                    id = getLong(idIdx),
                    role = getString(roleIdx),
                    content = getString(contentIdx),
                    createdAtMillis = getLong(createdIdx),
                    kind = getString(kindIdx) ?: "text",
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
