package dev.codex.android.agent

import android.content.Context
import dev.codex.android.core.CodexJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Persists the latest [RunState] as JSON in the app's private files dir.
 * Never throws — a corrupt or missing file simply yields null on load,
 * and a failed save is swallowed (same pattern as PlanStore).
 */
class RunStateStore(context: Context) {

    private val file: File = File(context.filesDir, "codex_run_state.json")

    /** Saves [state] as the latest run state. Never throws. */
    suspend fun save(state: RunState) = withContext(Dispatchers.IO) {
        try {
            val raw = CodexJson.encodeToString(RunState.serializer(), state)
            file.writeText(raw)
        } catch (e: Exception) {
            // Swallowed by design — persistence must never break a run.
        }
    }

    /** Loads the most recently saved run state, or null when none exists. */
    suspend fun load(): RunState? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext null
            val raw = file.readText()
            if (raw.isBlank()) return@withContext null
            CodexJson.decodeFromString(RunState.serializer(), raw)
        } catch (e: Exception) {
            null
        }
    }

    /** Clears the saved run state. Never throws. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            // Ignored.
        }
    }
}
