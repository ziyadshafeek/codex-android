package dev.codex.android.agent

import android.content.Context
import dev.codex.android.core.CodexJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Minimal plan-persistence contract [AgentLoop] depends on. Extracted
 * from [PlanStore] (BUG 3 follow-up) so the loop is JVM-testable with a
 * fake repository instead of a real [Context] + files dir.
 */
interface PlanRepository {
    suspend fun loadLatest(): Plan?
    suspend fun save(plan: Plan)
    suspend fun clear()
}

/**
 * Persists the latest [Plan] as JSON in the app's private files dir, so a
 * run resumes exactly where it left off after the process is killed.
 * Never throws — a corrupt or missing file simply yields null on load,
 * and a failed save is swallowed (the in-memory plan still works).
 */
class PlanStore(context: Context) : PlanRepository {

    private val file: File = File(context.filesDir, "codex_latest_plan.json")

    /** Loads the most recently saved plan, or null when none exists. */
    override suspend fun loadLatest(): Plan? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext null
            val raw = file.readText()
            if (raw.isBlank()) return@withContext null
            CodexJson.decodeFromString(Plan.serializer(), raw)
        } catch (e: Exception) {
            null
        }
    }

    /** Saves [plan] as the latest plan. Never throws. */
    override suspend fun save(plan: Plan) = withContext(Dispatchers.IO) {
        try {
            val raw = CodexJson.encodeToString(Plan.serializer(), plan)
            file.writeText(raw)
        } catch (e: Exception) {
            // Swallowed by design — persistence must never break a run.
        }
    }

    /** Clears the saved plan. Never throws. */
    override suspend fun clear() = withContext(Dispatchers.IO) {
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            // Ignored.
        }
    }
}
