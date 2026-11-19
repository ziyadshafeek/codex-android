package dev.codex.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The single shared SQLite database for Codex on-device memory.
 *
 * Plain [SQLiteOpenHelper] on purpose — no Room, no new dependency, so the
 * APK stays small on low-end phones. No network, no API key, no secrets.
 *
 * Tables (exact names are part of the contract — [MemoryStore] and the
 * SkillStore both use them, so do not rename them):
 * - `messages`: chat history, replayed in chronological order.
 * - `facts`: durable user facts, keyed by a unique `key`.
 * - `skills`: installed skills (owned by SkillStore; the table lives here
 *   so there is exactly one database file and one helper).
 */
class CodexDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "codex.db"
        const val DATABASE_VERSION = 1

        const val TABLE_MESSAGES = "messages"
        const val TABLE_FACTS = "facts"
        const val TABLE_SKILLS = "skills"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_MESSAGES (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "role TEXT NOT NULL, " +
                "content TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL" +
                ")",
        )
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema — there is nothing to migrate.
        // Future versions add their migration steps here, guarded by
        // `if (oldVersion < N)`, and bump DATABASE_VERSION.
    }
}
