package dev.codex.android.scheduler

import android.content.Context
import dev.codex.android.core.CodexJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Persists [ScheduledTask]s as a JSON list in the app's private files dir.
 * Never throws — a corrupt or missing file simply yields an empty list
 * on load, and a failed save/remove is swallowed (same pattern as PlanStore).
 */
class ScheduledTaskStore(context: Context) {

    private val file: File = File(context.filesDir, "codex_scheduled_tasks.json")

    private val listSerializer = ListSerializer(ScheduledTask.serializer())

    /** All saved tasks, or an empty list when none exist / the file is unreadable. */
    suspend fun all(): List<ScheduledTask> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext emptyList()
            val raw = file.readText()
            if (raw.isBlank()) return@withContext emptyList()
            CodexJson.decodeFromString(listSerializer, raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Inserts or replaces [task] (matched by id). Never throws. */
    suspend fun save(task: ScheduledTask) = withContext(Dispatchers.IO) {
        try {
            val next = all().filter { it.id != task.id } + task
            file.writeText(CodexJson.encodeToString(listSerializer, next))
        } catch (e: Exception) {
            // Swallowed by design — persistence must never break a run.
        }
    }

    /** Removes the task with [id], if present. Never throws. */
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        try {
            val next = all().filter { it.id != id }
            file.writeText(CodexJson.encodeToString(listSerializer, next))
        } catch (e: Exception) {
            // Ignored.
        }
    }

    /** Enabled tasks whose trigger time has passed. Never throws. */
    suspend fun due(nowMillis: Long = System.currentTimeMillis()): List<ScheduledTask> =
        all().filter { it.enabled && it.triggerAtMillis <= nowMillis }

    /** Deletes every saved task. Never throws. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            // Ignored.
        }
    }
}
