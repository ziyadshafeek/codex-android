package dev.codex.android.ci

import java.io.File

/**
 * Autonomous self-healing build loop (Master Plan Subsystem 5, M3).
 *
 * push -> watch CI -> on failure fetch logs -> ask the fixer to patch
 * -> push again, up to [BuildLoopConfig.maxAttempts] attempts.
 *
 * The loop itself contains NO fix logic. The fix is a caller-supplied
 * seam — later the agent loop plugs in here (diagnose the failure logs
 * with the BYOK model, patch local files, return true). The loop only
 * decides when to retry, when to stop, and what the outcome was.
 */

data class BuildLoopConfig(
    /** Total push attempts (first build + retries). Default 5. */
    val maxAttempts: Int = 5,
    /** Delay between CI status polls. */
    val pollIntervalMs: Long = 15_000L,
    /** How long to wait for one CI run before giving up on it. */
    val pollTimeoutMs: Long = 30 * 60_000L,
    /** Artifact name to download on success; null = first non-expired artifact. */
    val artifactName: String? = null,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }
}

sealed class BuildLoopResult {
    /** CI went green. [artifact] is null when the run had no downloadable artifact. */
    data class Success(
        val attemptsUsed: Int,
        val run: WorkflowRun,
        val artifact: DownloadedArtifact?,
        /** Non-null when the build passed but the artifact could not be downloaded. */
        val artifactError: String?,
        /** Failure logs from the last failing attempt ("" when the first attempt passed). */
        val lastLogs: String,
    ) : BuildLoopResult()

    /** CI never went green (or the fixer declined / threw). */
    data class Failure(
        val attemptsUsed: Int,
        val run: WorkflowRun?,
        /** Cleaned failure logs from the last attempt — the fixer / user reads these. */
        val lastLogs: String,
        val reason: String,
    ) : BuildLoopResult()
}

class SelfHealingBuildLoop(
    private val engine: GitHubActionsEngine,
    private val config: BuildLoopConfig = BuildLoopConfig(),
) {

    /**
     * Runs the push -> watch -> fix loop.
     *
     * @param owner repo owner (user or org).
     * @param repo repo name.
     * @param branch branch CI builds (e.g. "main").
     * @param outputDir base dir for downloaded artifacts; the engine
     *   appends the run id (`<outputDir>/<runId>/app-debug.apk`).
     *   Integration passes `filesDir/builds/<repo>`.
     * @param push pushes the current local code (caller owns git — this
     *   loop never runs git itself; M4/M5's worktree engine does).
     * @param fixer called with (attempt, cleaned failure logs) after a
     *   failed run. Must patch local files and return true when a change
     *   was made and another push is worthwhile, or false to stop the
     *   loop early (e.g. the failure is not fixable from code — missing
     *   secret, deleted workflow, quota). May throw; a throw also stops
     *   the loop and is reported in the result.
     * @param onProgress optional (attempt, stage) callback — stages are
     *   "pushing", "watching", "fixing", "downloading".
     */
    suspend fun run(
        owner: String,
        repo: String,
        branch: String,
        outputDir: File,
        push: suspend () -> Unit,
        fixer: suspend (attempt: Int, failureLogs: String) -> Boolean,
        onProgress: ((attempt: Int, stage: String) -> Unit)? = null,
    ): BuildLoopResult {
        var lastLogs = ""
        var lastRun: WorkflowRun? = null

        for (attempt in 1..config.maxAttempts) {
            // Remember which run existed BEFORE this push, so watchRun
            // never mistakes the previous run for the new one.
            val previousRunId = runCatching { engine.latestRunId(owner, repo, branch) }.getOrNull()

            onProgress?.invoke(attempt, "pushing")
            push()

            onProgress?.invoke(attempt, "watching")
            val run = engine.watchRun(
                owner = owner,
                repo = repo,
                branch = branch,
                excludeRunId = previousRunId,
                pollIntervalMs = config.pollIntervalMs,
                pollTimeoutMs = config.pollTimeoutMs,
            )
            lastRun = run

            if (run.isSuccess) {
                onProgress?.invoke(attempt, "downloading")
                var artifact: DownloadedArtifact? = null
                var artifactError: String? = null
                try {
                    artifact = engine.downloadLatestArtifact(
                        owner = owner,
                        repo = repo,
                        runId = run.id,
                        outputDir = File(outputDir, run.id.toString()),
                        artifactName = config.artifactName,
                    )
                } catch (e: Exception) {
                    // Green build, broken download: still a success —
                    // the caller can retry the download from the run id.
                    artifactError = "Build passed but artifact download failed: ${e.message}"
                }
                return BuildLoopResult.Success(
                    attemptsUsed = attempt,
                    run = run,
                    artifact = artifact,
                    artifactError = artifactError,
                    lastLogs = lastLogs,
                )
            }

            // Failed run: fetch the cleaned logs for the fixer.
            lastLogs = runCatching { engine.getFailedLogs(owner, repo, run.id) }
                .getOrElse { "Could not fetch failure logs for run ${run.id}: ${it.message}" }

            if (attempt == config.maxAttempts) {
                return BuildLoopResult.Failure(
                    attemptsUsed = attempt,
                    run = run,
                    lastLogs = lastLogs,
                    reason = "Build still failing after ${config.maxAttempts} attempts",
                )
            }

            onProgress?.invoke(attempt, "fixing")
            val fixed = try {
                fixer(attempt, lastLogs)
            } catch (e: Exception) {
                return BuildLoopResult.Failure(
                    attemptsUsed = attempt,
                    run = run,
                    lastLogs = lastLogs,
                    reason = "Fixer threw on attempt $attempt: ${e.message}",
                )
            }
            if (!fixed) {
                return BuildLoopResult.Failure(
                    attemptsUsed = attempt,
                    run = run,
                    lastLogs = lastLogs,
                    reason = "Fixer made no change on attempt $attempt — stopping",
                )
            }
            // Loop: push the fix and watch again.
        }
        // Unreachable (maxAttempts >= 1 guarantees a return), kept for exhaustiveness.
        return BuildLoopResult.Failure(
            attemptsUsed = config.maxAttempts,
            run = lastRun,
            lastLogs = lastLogs,
            reason = "Build loop ended without a result",
        )
    }
}
