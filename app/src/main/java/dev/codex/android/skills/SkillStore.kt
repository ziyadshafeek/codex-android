package dev.codex.android.skills

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import dev.codex.android.core.CodexJson
import dev.codex.android.data.CodexDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString

/**
 * Persists [Skill]s in the `skills` table of [CodexDatabase] (`codex.db`).
 *
 * Every function is suspend, runs its DB work on [Dispatchers.IO], and
 * never throws: on any error it returns a safe default (null / empty
 * list / no-op) so a storage failure can never break an agent run.
 *
 * Table contract (owned by [CodexDatabase]):
 * `skills(id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL,
 * instructions TEXT NOT NULL, tools_json TEXT NOT NULL,
 * version INTEGER NOT NULL, enabled INTEGER NOT NULL)`.
 * `tools_json` holds a JSON array of tool-name strings.
 */
class SkillStore(context: Context) {

    // One shared database: getInstance, never a second helper (see MemoryStore).
    private val database = CodexDatabase.getInstance(context)

    /** Upserts [skill] by `id`. Never throws. */
    suspend fun save(skill: Skill) = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put("id", skill.id)
                put("name", skill.name)
                put("description", skill.description)
                put("instructions", skill.instructions)
                put("tools_json", encodeToolNames(skill.toolNames))
                put("version", skill.version)
                put("enabled", if (skill.enabled) 1 else 0)
            }
            database.writableDatabase.insertWithOnConflict(
                TABLE,
                null,
                values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
            )
        } catch (e: Exception) {
            // Swallowed by design — persistence must never break a run.
        }
    }

    /** Returns the skill with the given [id], or null when absent / on error. */
    suspend fun get(id: String): Skill? = withContext(Dispatchers.IO) {
        try {
            database.readableDatabase.query(
                TABLE,
                COLUMNS,
                "id = ?",
                arrayOf(id),
                null,
                null,
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.toSkill() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** All skills, ordered by name. Empty list on error. */
    suspend fun all(): List<Skill> = withContext(Dispatchers.IO) {
        try {
            database.readableDatabase.query(
                TABLE,
                COLUMNS,
                null,
                null,
                null,
                null,
                "name ASC",
            ).use { cursor -> cursor.toSkillList() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Only enabled skills (`enabled = 1`), ordered by name. Empty list on error. */
    suspend fun enabled(): List<Skill> = withContext(Dispatchers.IO) {
        try {
            database.readableDatabase.query(
                TABLE,
                COLUMNS,
                "enabled = 1",
                null,
                null,
                null,
                "name ASC",
            ).use { cursor -> cursor.toSkillList() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Deletes the skill with the given [id], if present. Never throws. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        try {
            database.writableDatabase.delete(TABLE, "id = ?", arrayOf(id))
        } catch (e: Exception) {
            // Swallowed by design.
        }
    }

    /**
     * Inserts any built-in skills that are missing from the table,
     * without touching rows that already exist — so user edits to a
     * built-in skill are never overwritten, and a new built-in still
     * appears on an existing install whose table was seeded earlier
     * with fewer skills. Never throws.
     */
    suspend fun seedBuiltins() = withContext(Dispatchers.IO) {
        try {
            val db = database.writableDatabase
            val existingIds = db.query(
                TABLE,
                arrayOf("id"),
                null,
                null,
                null,
                null,
                null,
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0))
                    }
                }
            }
            for (skill in BUILTINS) {
                if (skill.id in existingIds) continue
                val values = ContentValues().apply {
                    put("id", skill.id)
                    put("name", skill.name)
                    put("description", skill.description)
                    put("instructions", skill.instructions)
                    put("tools_json", encodeToolNames(skill.toolNames))
                    put("version", skill.version)
                    put("enabled", if (skill.enabled) 1 else 0)
                }
                db.insertWithOnConflict(
                    TABLE,
                    null,
                    values,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
        } catch (e: Exception) {
            // Swallowed by design.
        }
    }

    private fun encodeToolNames(toolNames: List<String>): String =
        CodexJson.encodeToString(ListSerializer(String.serializer()), toolNames)

    private fun decodeToolNames(raw: String): List<String> = try {
        CodexJson.decodeFromString(ListSerializer(String.serializer()), raw)
    } catch (e: Exception) {
        emptyList()
    }

    private fun Cursor.toSkill(): Skill = Skill(
        id = getString(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        description = getString(getColumnIndexOrThrow("description")),
        instructions = getString(getColumnIndexOrThrow("instructions")),
        toolNames = decodeToolNames(getString(getColumnIndexOrThrow("tools_json"))),
        version = getInt(getColumnIndexOrThrow("version")),
        enabled = getInt(getColumnIndexOrThrow("enabled")) == 1,
    )

    private fun Cursor.toSkillList(): List<Skill> {
        val skills = mutableListOf<Skill>()
        while (moveToNext()) {
            skills.add(toSkill())
        }
        return skills
    }

    companion object {
        private const val TABLE = "skills"
        private val COLUMNS = arrayOf(
            "id",
            "name",
            "description",
            "instructions",
            "tools_json",
            "version",
            "enabled",
        )

        /** The built-in skills seeded by [seedBuiltins] on a fresh install. */
        val BUILTINS: List<Skill> = listOf(
            Skill(
                id = "summarise-screen",
                name = "Summarise screen",
                description = "Reads the current screen and summarises what is on it.",
                instructions = "Use read_screen to read the visible text, then reply with a short summary of what is on the screen in plain language.",
                toolNames = listOf("read_screen"),
                version = 1,
                enabled = true,
            ),
            Skill(
                id = "open-app-and-read",
                name = "Open app and read",
                description = "Opens an app by name and reads what is on its first screen.",
                instructions = "First use open_app to open the requested app, wait briefly, then use read_screen to read what is shown and report it.",
                toolNames = listOf("open_app", "read_screen"),
                version = 1,
                enabled = true,
            ),
            Skill(
                id = "what-time",
                name = "What time is it",
                description = "Tells the current date and time.",
                instructions = "Use get_time and report the result.",
                toolNames = listOf("get_time"),
                version = 1,
                enabled = true,
            ),
            Skill(
                id = "document",
                name = "Document analysis",
                description = "Reads PDFs, Word, Excel, PowerPoint and text files and answers from them, citing pages.",
                instructions = "When the user attaches or shares a document, or asks about a document, read it with the matching tool before answering: use read_pdf for PDF files and cite the page for each fact as [p. 3]; use read_document for Word, Excel and PowerPoint files; use read_text_file for plain text, Markdown and CSV files. Answer only from what the file actually contains. If a file is scanned or encrypted and no text can be read, say so clearly and do not invent text. Quote numbers and names from the source instead of paraphrasing them away.",
                toolNames = listOf("read_pdf", "read_document", "read_text_file"),
                version = 1,
                enabled = true,
            ),
            Skill(
                id = "image",
                name = "Image analysis",
                description = "Describes images, reads the text in them, and answers questions about what they show.",
                instructions = "When the user attaches or shares an image, or asks about one, use read_image with the saved file path and a clear prompt for what is needed: describe what the image shows, read any text visible in it, or answer the specific question asked. Describe only what is actually visible in the image. If the image cannot be seen, say so clearly instead of guessing.",
                toolNames = listOf("read_image"),
                version = 1,
                enabled = true,
            ),
        )
    }
}
