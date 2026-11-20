package dev.codex.android.ci

import dev.codex.android.core.CodexError
import dev.codex.android.core.CodexJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Autonomous GitHub Actions CI engine (Master Plan Subsystem 5, M3).
 *
 * Pure OkHttp + kotlinx-serialization — no new Gradle dependencies.
 * The GitHub PAT comes from [tokenProvider] (integration wires it to
 * KeyStore); this engine never stores a token and never ships one.
 * BYOK only; no default key, no Coarena, no live model calls here.
 *
 * Open-source note: the PR-search approach in [searchPrs] mirrors
 * OpenCode's `.opencode/tool/github-pr-search.ts` (MIT) — search
 * existing PRs by repo + query to avoid duplicates and gather cross-PR
 * context. Behaviour ported, no code copied (TypeScript tool -> Kotlin
 * REST calls against GitHub's public API). The log-extraction approach
 * in [extractFailureTail] follows the same grep-the-failed-log pattern
 * every CI loop uses (`gh run view --log-failed` + error-line grep);
 * no reusable open-source Android CI-build loop library exists, so the
 * push -> watch -> fetch-logs -> download-artifact loop is built here.
 */

// ---------------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------------

data class GitHubRepo(
    val id: Long,
    val owner: String,
    val name: String,
    val fullName: String,
    val htmlUrl: String,
)

data class WorkflowRun(
    val id: Long,
    val status: String, // queued | in_progress | completed
    val conclusion: String?, // success | failure | cancelled | null while running
    val headBranch: String?,
    val headSha: String?,
    val htmlUrl: String,
    val createdAt: String?,
) {
    val isCompleted: Boolean get() = status == "completed"
    val isSuccess: Boolean get() = isCompleted && conclusion == "success"
    val isFailure: Boolean get() = isCompleted && conclusion != null && conclusion != "success"
}

data class JobRun(
    val id: Long,
    val name: String,
    val conclusion: String?,
)

data class ArtifactInfo(
    val id: Long,
    val name: String,
    val sizeInBytes: Long,
    val expired: Boolean,
)

data class DownloadedFile(
    val name: String,
    val file: File,
    val sha256: String,
    val sizeBytes: Long,
)

data class DownloadedArtifact(
    val artifactId: Long,
    val runId: Long,
    val dir: File,
    val files: List<DownloadedFile>,
) {
    /** The first APK in the artifact, if any. */
    val apk: DownloadedFile? get() = files.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
}

data class PullRequest(
    val number: Int,
    val title: String,
    val state: String,
    val htmlUrl: String,
    val headBranch: String?,
    val baseBranch: String?,
)

data class PrComment(
    val id: Long,
    val htmlUrl: String,
)

// ---------------------------------------------------------------------------
// Log extraction (pure, JVM-testable — no network)
// ---------------------------------------------------------------------------

private val ANSI_ESCAPE_REGEX = Regex("\u001B\\[[0-9;]*[A-Za-z]")
private val LOG_TIMESTAMP_PREFIX_REGEX =
    Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z\\s+")

/** Lines that mark the start of a useful error region in a CI log. */
private val ERROR_LINE_PATTERNS = listOf(
    Regex("^e: .*", RegexOption.IGNORE_CASE), // Kotlin compiler error: e: Main.kt: (12, 5): ...
    Regex(".*\\berror\\b.*:.*", RegexOption.IGNORE_CASE),
    Regex(".*\\berror\\[.*\\].*", RegexOption.IGNORE_CASE),
    Regex(".*FAILURE:.*"),
    Regex(".*BUILD FAILED.*"),
    Regex(".*Execution failed for task.*"),
    Regex(".*\\* What went wrong:.*"),
    Regex(".*\\* Exception is:.*"),
    Regex(".*Caused by:.*"),
    Regex(".*> Task .* FAILED.*"),
    Regex(".*tests? completed, \\d+ failed.*", RegexOption.IGNORE_CASE),
    Regex(".*There (was|were) .* (failure|failures).*", RegexOption.IGNORE_CASE),
    Regex(".*FAILED$", RegexOption.IGNORE_CASE),
    Regex(".*A failure occurred.*"),
)

/** Pure noise lines that never help a fixer — dropped before matching. */
private val NOISE_LINE_PATTERNS = listOf(
    Regex("^Download https?://.*"),
    Regex("^> Configure project.*"),
    Regex("^> Task :.* UP-TO-DATE$"),
)

/**
 * Extracts the compiler / Gradle / test error tails from a raw Actions
 * job log, cleaned for a fixer model's token budget.
 *
 * - Strips ANSI colour codes and GitHub's per-line timestamps.
 * - Keeps every error-matching line plus up to [contextLines] lines
 *   after it, plus the final [tailLines] lines of the log (the build
 *   summary usually lives there).
 * - Caps the result at [maxChars]; when over budget the oldest text is
 *   dropped (the most recent errors matter most) and a truncation marker
 *   is prepended. A log with no error line at all still yields its final
 *   [tailLines] lines, so the fixer always has something to read.
 */
fun extractFailureTail(
    rawLog: String,
    maxChars: Int = 12_000,
    contextLines: Int = 2,
    tailLines: Int = 25,
): String {
    val lines = rawLog.lines().map { line ->
        var clean = ANSI_ESCAPE_REGEX.replace(line, "")
        clean = LOG_TIMESTAMP_PREFIX_REGEX.replace(clean, "")
        if (clean.length > 500) clean.substring(0, 500) + "… [line truncated]"
        else clean
    }.filter { it.isNotBlank() && NOISE_LINE_PATTERNS.none { pattern -> pattern.matches(it) } }
    if (lines.isEmpty()) return ""

    val keep = BooleanArray(lines.size)
    for (i in lines.indices) {
        if (ERROR_LINE_PATTERNS.any { it.matches(lines[i]) || it.containsMatchIn(lines[i]) }) {
            for (j in i..minOf(i + contextLines, lines.size - 1)) keep[j] = true
        }
    }
    // Always keep the final tail of the log — the summary lives there.
    for (i in maxOf(0, lines.size - tailLines) until lines.size) keep[i] = true

    val kept = lines.filterIndexed { index, _ -> keep[index] }
    if (kept.isEmpty()) return ""
    val joined = kept.joinToString("\n")
    if (joined.length <= maxChars) return joined
    val marker = "[… log truncated to the last $maxChars chars for token budget …]\n"
    return marker + joined.takeLast(maxChars - marker.length)
}

/** SHA-256 hex of a file's bytes (also used by [ApkInstaller]). */
fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

/**
 * Talks to the GitHub REST API (api.github.com) with a BYOK PAT.
 *
 * @param tokenProvider returns the user's GitHub PAT, or null when none
 *   is configured. Called fresh for every request so a rotated token is
 *   picked up without rebuilding the engine.
 * @param httpClient OkHttp client; callers/tests may inject one with a
 *   fake interceptor. Must not add an Authorization header itself — the
 *   engine adds the PAT per request, and deliberately omits it for blob
 *   downloads (see [downloadBytesViaRedirect]).
 */
class GitHubActionsEngine(
    private val tokenProvider: () -> String?,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val apiBaseUrl: String = "https://api.github.com",
) {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val JSON_ACCEPT = "application/vnd.github+json"
        private const val API_VERSION = "2022-11-28"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Client that does NOT follow redirects — the engine follows them by hand. */
        private fun OkHttpClient.noRedirect(): OkHttpClient = newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    // -- request plumbing ---------------------------------------------------

    private fun authToken(): String = tokenProvider()?.takeIf { it.isNotBlank() }
        ?: throw CodexError.Auth("GitHub token is missing — add a personal access token in Settings (BYOK).")

    private fun authedBuilder(url: String, accept: String = JSON_ACCEPT): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${authToken()}")
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", API_VERSION)

    private fun jsonBody(root: JsonObject): okhttp3.RequestBody =
        CodexJson.encodeToString(JsonObject.serializer(), root).toRequestBody(JSON_MEDIA_TYPE)

    /** Executes [request] on IO; IO failures become [CodexError.Network]. */
    private suspend fun execute(request: Request, client: OkHttpClient = httpClient): Response =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                throw CodexError.Network(e)
            }
        }

    /**
     * Maps a non-2xx response to the matching [CodexError]. Consumes the
     * error body for the message (error path only — success bodies are
     * left for the caller).
     */
    private fun checkResponse(response: Response, what: String): Response {
        if (response.isSuccessful) return response
        val bodySnippet = runCatching { response.body?.string()?.take(300) }.getOrNull()
        val detail = "$what failed: HTTP ${response.code}" +
            (bodySnippet?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
        throw when (response.code) {
            401, 403 -> {
                val remaining = response.header("X-RateLimit-Remaining")
                if (response.code == 403 && remaining == "0") {
                    CodexError.RateLimit(response.header("Retry-After")?.toLongOrNull())
                } else {
                    CodexError.Auth(detail)
                }
            }
            404 -> CodexError.Unknown(detail)
            429 -> CodexError.RateLimit(response.header("Retry-After")?.toLongOrNull())
            408, 504 -> CodexError.Timeout()
            in 500..599 -> CodexError.Network(IOException(detail))
            else -> CodexError.Unknown(detail)
        }
    }

    private suspend fun getJson(url: String, what: String): JsonObject {
        val response = execute(authedBuilder(url).get().build())
        checkResponse(response, what).use { resp ->
            val raw = resp.body?.string() ?: throw CodexError.Unknown("$what: empty response")
            return CodexJson.parseToJsonElement(raw) as? JsonObject
                ?: throw CodexError.Unknown("$what: response was not a JSON object")
        }
    }

    private suspend fun postJson(url: String, body: JsonObject, what: String): JsonObject? {
        val response = execute(authedBuilder(url).post(jsonBody(body)).build())
        checkResponse(response, what).use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (raw.isBlank()) return null // e.g. 204 No Content (workflow dispatch)
            return CodexJson.parseToJsonElement(raw) as? JsonObject
                ?: throw CodexError.Unknown("$what: response was not a JSON object")
        }
    }

    /**
     * Follows a 302 from a GitHub API download endpoint (job logs,
     * artifact zips) to its blob host.
     *
     * GitHub answers these endpoints with a 302 redirect to a temporary
     * Azure blob URL. Two rules, both load-bearing:
     * 1. Fetch the `Location` with redirects disabled on the first hop,
     *    so the engine sees the real blob URL.
     * 2. GET the blob URL with NO Authorization header — the blob host
     *    rejects requests carrying one.
     * Returns the blob response (caller reads/closes it). A direct 200
     * from the API endpoint is also accepted.
     */
    private suspend fun downloadBytesViaRedirect(url: String, what: String): Response {
        val first = execute(authedBuilder(url).get().build(), httpClient.noRedirect())
        if (first.isSuccessful) return first
        if (first.code != 302 && first.code != 301) {
            checkResponse(first, what) // throws the mapped error
            throw CodexError.Unknown("$what: unexpected redirect handling")
        }
        val location = first.header("Location")
        first.close()
        if (location.isNullOrBlank()) {
            throw CodexError.Unknown("$what: redirect had no Location header")
        }
        // Blob fetch: same client family (so test interceptors still apply)
        // but a request with NO Authorization header, as the blob host demands.
        val blobRequest = Request.Builder()
            .url(location)
            .header("Accept", "*/*")
            .get()
            .build()
        val blob = execute(blobRequest)
        if (!blob.isSuccessful) {
            blob.close()
            throw CodexError.Unknown("$what: blob download failed: HTTP ${blob.code}")
        }
        return blob
    }

    // -- repos / workflows ---------------------------------------------------

    /**
     * POST /user/repos — creates a repo under the token owner's account.
     * `autoInit` stays false by default: an auto-created README would
     * make the caller's first `git push` fail (non-fast-forward) until
     * they pull. Pass true only when the caller will clone, not push.
     */
    suspend fun createRepo(
        name: String,
        private: Boolean = true,
        description: String? = null,
        autoInit: Boolean = false,
    ): GitHubRepo {
        val body = buildJsonObject {
            put("name", name)
            put("private", private)
            put("auto_init", autoInit)
            if (description != null) put("description", description)
        }
        val json = postJson("$apiBaseUrl/user/repos", body, "Create repo")
            ?: throw CodexError.Unknown("Create repo: empty response")
        return GitHubRepo(
            id = json["id"]?.jsonPrimitive?.longOrNull ?: 0L,
            owner = json["owner"]?.jsonObject?.get("login")?.jsonPrimitive?.contentOrNull ?: "",
            name = json["name"]?.jsonPrimitive?.contentOrNull ?: name,
            fullName = json["full_name"]?.jsonPrimitive?.contentOrNull ?: name,
            htmlUrl = json["html_url"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }

    /** Publishes the supplied text files in one commit without force-pushing. */
    internal suspend fun publishFiles(
        owner: String, repo: String, branch: String, message: String, files: Map<String, String>,
    ): String {
        require(branch.matches(Regex("codex/[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) { "Use a codex/<name> branch" }
        require(files.isNotEmpty())
        val base = "$apiBaseUrl/repos/$owner/$repo"
        val defaultBranch = getJson(base, "Get repository").stringField("default_branch")
        require(branch != defaultBranch) { "Cannot publish directly to the default branch" }
        fun refUrl(ref: String) = base.toHttpUrl().newBuilder()
            .addPathSegments("git/ref/heads").addPathSegments(ref).build().toString()
        val existing = execute(authedBuilder(refUrl(branch)).get().build()).use { response ->
            if (response.code == 404) null else {
                checkResponse(response, "Get branch")
                CodexJson.parseToJsonElement(response.body!!.string()).jsonObject["object"]!!.jsonObject.stringField("sha")
            }
        }
        val parent = existing ?: getJson(refUrl(defaultBranch), "Get default branch")["object"]!!
            .jsonObject.stringField("sha")
        val parentTree = getJson("$base/git/commits/$parent", "Read base commit")["tree"]!!
            .jsonObject.stringField("sha")
        val tree = postJson("$base/git/trees", buildJsonObject {
            put("base_tree", parentTree)
            put("tree", kotlinx.serialization.json.JsonArray(files.map { (path, content) ->
                buildJsonObject {
                    put("path", path)
                    put("mode", "100644")
                    put("type", "blob")
                    put("content", content)
                }
            }))
        }, "Create source tree")!!.stringField("sha")
        val commit = postJson("$base/git/commits", buildJsonObject {
            put("message", message)
            put("tree", tree)
            put("parents", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(parent))))
        }, "Commit workspace files")!!.stringField("sha")
        if (existing == null) {
            postJson("$base/git/refs", buildJsonObject {
                put("ref", "refs/heads/$branch")
                put("sha", commit)
            }, "Create build branch")
        } else {
            val url = base.toHttpUrl().newBuilder().addPathSegments("git/refs/heads/$branch").build()
            val payload = buildJsonObject { put("sha", commit); put("force", false) }
            checkResponse(execute(authedBuilder(url.toString()).patch(jsonBody(payload)).build()), "Update build branch").close()
        }
        return commit
    }

    private fun JsonObject.stringField(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw CodexError.Unknown("GitHub response missing $name")

    /** POST .../actions/workflows/{workflowId}/dispatches — manually triggers a workflow. */
    suspend fun triggerWorkflow(
        owner: String,
        repo: String,
        workflowId: String,
        ref: String,
        inputs: Map<String, String> = emptyMap(),
    ) {
        val body = buildJsonObject {
            put("ref", ref)
            if (inputs.isNotEmpty()) {
                put(
                    "inputs",
                    buildJsonObject { inputs.forEach { (k, v) -> put(k, v) } },
                )
            }
        }
        postJson(
            "$apiBaseUrl/repos/$owner/$repo/actions/workflows/$workflowId/dispatches",
            body,
            "Trigger workflow $workflowId",
        )
    }

    /** GET .../actions/runs?branch=... — newest first. */
    suspend fun listRuns(owner: String, repo: String, branch: String, perPage: Int = 10): List<WorkflowRun> {
        // Branch is a query value, not a path segment: build it with
        // addQueryParameter so branches containing `/` (e.g.
        // `codex/self-heal`) are encoded instead of breaking the URL.
        val url = apiBaseUrl.toHttpUrl().newBuilder()
            .addPathSegments("repos/$owner/$repo/actions/runs")
            .addQueryParameter("branch", branch)
            .addQueryParameter("per_page", perPage.toString())
            .build()
            .toString()
        val json = getJson(url, "List workflow runs")
        return (json["workflow_runs"]?.jsonArray ?: emptyList()).mapNotNull { it as? JsonObject }
            .map { parseRun(it) }
    }

    /** GET .../actions/runs/{runId} — a single run. */
    suspend fun getRun(owner: String, repo: String, runId: Long): WorkflowRun =
        parseRun(getJson("$apiBaseUrl/repos/$owner/$repo/actions/runs/$runId", "Get workflow run"))

    /** Newest run id for [branch], or null when the repo has no runs yet. */
    suspend fun latestRunId(owner: String, repo: String, branch: String): Long? =
        listRuns(owner, repo, branch, perPage = 1).firstOrNull()?.id

    private fun parseRun(json: JsonObject): WorkflowRun = WorkflowRun(
        id = json["id"]?.jsonPrimitive?.longOrNull ?: 0L,
        status = json["status"]?.jsonPrimitive?.contentOrNull ?: "",
        conclusion = json["conclusion"]?.jsonPrimitive?.contentOrNull,
        headBranch = json["head_branch"]?.jsonPrimitive?.contentOrNull,
        headSha = json["head_sha"]?.jsonPrimitive?.contentOrNull,
        htmlUrl = json["html_url"]?.jsonPrimitive?.contentOrNull ?: "",
        createdAt = json["created_at"]?.jsonPrimitive?.contentOrNull,
    )

    /**
     * Polls GET .../actions/runs?branch=... until a run reaches
     * `status == "completed"` (or [pollTimeoutMs] elapses).
     *
     * @param runId when given, polls that single run instead of the
     *   branch's latest run.
     * @param excludeRunId when given (and [runId] is null), ignores that
     *   run id while polling — the self-healing loop passes the run that
     *   existed *before* its push, so a just-pushed run is never confused
     *   with the previous one.
     * @throws CodexError.Timeout when no run completes in time.
     */
    suspend fun watchRun(
        owner: String,
        repo: String,
        branch: String,
        runId: Long? = null,
        excludeRunId: Long? = null,
        pollIntervalMs: Long = 15_000L,
        pollTimeoutMs: Long = 30 * 60_000L,
        onProgress: ((WorkflowRun) -> Unit)? = null,
    ): WorkflowRun {
        val deadline = System.currentTimeMillis() + pollTimeoutMs
        while (true) {
            val run: WorkflowRun? = if (runId != null) {
                getRun(owner, repo, runId)
            } else {
                listRuns(owner, repo, branch, perPage = 5)
                    .firstOrNull { excludeRunId == null || it.id != excludeRunId }
            }
            if (run != null) {
                onProgress?.invoke(run)
                if (run.isCompleted) return run
            }
            if (System.currentTimeMillis() + pollIntervalMs > deadline) {
                throw CodexError.Timeout()
            }
            delay(pollIntervalMs)
        }
    }

    // -- failure logs --------------------------------------------------------

    /** GET .../actions/runs/{runId}/jobs — jobs for a run. */
    suspend fun listJobs(owner: String, repo: String, runId: Long): List<JobRun> {
        val json = getJson("$apiBaseUrl/repos/$owner/$repo/actions/runs/$runId/jobs", "List run jobs")
        return (json["jobs"]?.jsonArray ?: emptyList()).mapNotNull { it as? JsonObject }
            .map {
                JobRun(
                    id = it["id"]?.jsonPrimitive?.longOrNull ?: 0L,
                    name = it["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    conclusion = it["conclusion"]?.jsonPrimitive?.contentOrNull,
                )
            }
    }

    /** Downloads one job's raw log text (302 -> blob, no auth on the blob hop). */
    suspend fun downloadJobLog(owner: String, repo: String, jobId: Long): String {
        val response = downloadBytesViaRedirect(
            "$apiBaseUrl/repos/$owner/$repo/actions/jobs/$jobId/logs",
            "Download job log",
        )
        response.use { resp ->
            return resp.body?.string() ?: ""
        }
    }

    /**
     * Fetches the failed jobs' logs for [runId] and returns the cleaned
     * error tails ([extractFailureTail]) concatenated per job, within
     * token budget. Returns a short note when the run failed but no job
     * reports a failure (e.g. logs expired — GitHub keeps them ~90 days).
     */
    suspend fun getFailedLogs(
        owner: String,
        repo: String,
        runId: Long,
        maxCharsPerJob: Int = 12_000,
    ): String {
        val jobs = listJobs(owner, repo, runId)
        val failed = jobs.filter { it.conclusion == "failure" || it.conclusion == "cancelled" }
        if (failed.isEmpty()) {
            return "Run $runId failed, but no failed job was reported by GitHub " +
                "(conclusions: ${jobs.joinToString { "${it.name}=${it.conclusion}" }})."
        }
        val out = StringBuilder()
        for (job in failed) {
            val raw = runCatching { downloadJobLog(owner, repo, job.id) }
                .getOrElse { "[log unavailable for job '${job.name}': ${it.message}]" }
            val tail = extractFailureTail(raw, maxChars = maxCharsPerJob)
            out.append("## Job: ${job.name} (id ${job.id})\n")
            out.append(if (tail.isBlank()) "[no error lines found in log]\n" else tail).append("\n\n")
        }
        return out.toString().trimEnd()
    }

    // -- artifacts -----------------------------------------------------------

    /** GET .../actions/runs/{runId}/artifacts — artifacts for a run. */
    suspend fun listArtifacts(owner: String, repo: String, runId: Long): List<ArtifactInfo> {
        val json = getJson(
            "$apiBaseUrl/repos/$owner/$repo/actions/runs/$runId/artifacts",
            "List run artifacts",
        )
        return (json["artifacts"]?.jsonArray ?: emptyList()).mapNotNull { it as? JsonObject }
            .map {
                ArtifactInfo(
                    id = it["id"]?.jsonPrimitive?.longOrNull ?: 0L,
                    name = it["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    sizeInBytes = it["size_in_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    expired = (it["expired"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                )
            }
    }

    /**
     * Downloads an artifact ZIP (302 -> blob, no auth on the blob hop),
     * extracts the `.apk` / `.aab` files into [outputDir], and computes
     * each file's SHA-256.
     *
     * Zip-slip guard: entries resolving outside [outputDir] are skipped.
     * Non-APK/AAB entries are skipped. When [expectedSha256] is given it
     * must match the first extracted APK's hash, else [CodexError.Unknown]
     * is thrown and the extracted files are left in place for inspection.
     */
    suspend fun downloadArtifact(
        owner: String,
        repo: String,
        artifactId: Long,
        runId: Long,
        outputDir: File,
        expectedSha256: String? = null,
    ): DownloadedArtifact {
        outputDir.mkdirs()
        val response = downloadBytesViaRedirect(
            "$apiBaseUrl/repos/$owner/$repo/actions/artifacts/$artifactId/zip",
            "Download artifact $artifactId",
        )
        val tmpZip = File.createTempFile("artifact-$artifactId-", ".zip", outputDir.parentFile ?: outputDir)
        try {
            response.use { resp ->
                val body = resp.body ?: throw CodexError.Unknown("Download artifact: empty response")
                body.byteStream().use { input ->
                    FileOutputStream(tmpZip).use { out -> input.copyTo(out) }
                }
            }
            val extracted = extractApks(tmpZip, outputDir)
            if (extracted.isEmpty()) {
                throw CodexError.Unknown(
                    "Artifact $artifactId contains no .apk/.aab file (files were skipped or absent)",
                )
            }
            if (expectedSha256 != null) {
                val firstApk = extracted.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                    ?: extracted.first()
                if (!firstApk.sha256.equals(expectedSha256, ignoreCase = true)) {
                    throw CodexError.Unknown(
                        "SHA-256 mismatch for ${firstApk.name}: expected $expectedSha256, got ${firstApk.sha256}",
                    )
                }
            }
            return DownloadedArtifact(artifactId = artifactId, runId = runId, dir = outputDir, files = extracted)
        } finally {
            tmpZip.delete()
        }
    }

    /** Picks an artifact by [artifactName] (or the first non-expired one) and downloads it. */
    suspend fun downloadLatestArtifact(
        owner: String,
        repo: String,
        runId: Long,
        outputDir: File,
        artifactName: String? = null,
        expectedSha256: String? = null,
    ): DownloadedArtifact {
        val artifacts = listArtifacts(owner, repo, runId).filter { !it.expired }
        val chosen = if (artifactName != null) {
            artifacts.firstOrNull { it.name == artifactName }
                ?: throw CodexError.Unknown("No artifact named '$artifactName' on run $runId")
        } else {
            artifacts.firstOrNull()
                ?: throw CodexError.Unknown("No artifacts on run $runId")
        }
        return downloadArtifact(owner, repo, chosen.id, runId, outputDir, expectedSha256)
    }

    private fun extractApks(zipFile: File, outputDir: File): List<DownloadedFile> {
        val found = mutableListOf<DownloadedFile>()
        val outputCanonical = outputDir.canonicalFile
        ZipInputStream(FileInputStream(zipFile)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                val isPackage = !entry.isDirectory &&
                    (name.endsWith(".apk", ignoreCase = true) || name.endsWith(".aab", ignoreCase = true))
                if (isPackage) {
                    // Zip-slip guard: the entry must stay inside outputDir.
                    val target = File(outputDir, name).canonicalFile
                    if (target.path.startsWith(outputCanonical.path + File.separator)) {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out -> zip.copyTo(out) }
                        found.add(
                            DownloadedFile(
                                name = File(name).name,
                                file = target,
                                sha256 = sha256Hex(target),
                                sizeBytes = target.length(),
                            ),
                        )
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return found
    }

    // -- pull requests -------------------------------------------------------

    /** POST .../pulls — opens a pull request. */
    suspend fun createPr(
        owner: String,
        repo: String,
        title: String,
        head: String,
        base: String,
        body: String? = null,
    ): PullRequest {
        val requestBody = buildJsonObject {
            put("title", title)
            put("head", head)
            put("base", base)
            if (body != null) put("body", body)
        }
        val json = postJson("$apiBaseUrl/repos/$owner/$repo/pulls", requestBody, "Create pull request")
            ?: throw CodexError.Unknown("Create pull request: empty response")
        return parsePr(json)
    }

    /**
     * GET .../pulls?state=... — PRs for a repo, newest first.
     * Note: this endpoint returns a bare JSON array, so it parses the
     * raw body directly instead of going through [getJson] (object-only).
     */
    suspend fun listPrs(owner: String, repo: String, state: String = "open"): List<PullRequest> {
        val response = execute(
            authedBuilder("$apiBaseUrl/repos/$owner/$repo/pulls?state=$state&per_page=30").get().build(),
        )
        checkResponse(response, "List pull requests").use { resp ->
            val raw = resp.body?.string() ?: return emptyList()
            val array = CodexJson.parseToJsonElement(raw).jsonArray
            return array.mapNotNull { it as? JsonObject }.map { parsePr(it) }
        }
    }

    /**
     * Searches existing PRs in a repo — the OpenCode `github-pr-search`
     * pattern (MIT): `GET /search/issues?q=type:pr+repo:owner/repo+query`.
     * Used to avoid opening a duplicate PR and to gather cross-PR context.
     */
    suspend fun searchPrs(owner: String, repo: String, query: String, perPage: Int = 10): List<PullRequest> {
        val q = "type:pr repo:$owner/$repo $query"
        val url = apiBaseUrl.toHttpUrl().newBuilder()
            .addPathSegments("search/issues")
            .addQueryParameter("q", q)
            .addQueryParameter("per_page", perPage.toString())
            .build()
            .toString()
        val json = getJson(url, "Search pull requests")
        return (json["items"]?.jsonArray ?: emptyList()).mapNotNull { it as? JsonObject }
            .map { parsePr(it) }
    }

    /** GET .../pulls/{number} with the diff media type — raw unified diff text. */
    suspend fun getPrDiff(owner: String, repo: String, number: Int): String {
        val response = execute(
            authedBuilder(
                "$apiBaseUrl/repos/$owner/$repo/pulls/$number",
                accept = "application/vnd.github.diff",
            ).get().build(),
        )
        checkResponse(response, "Get PR diff").use { resp ->
            return resp.body?.string() ?: ""
        }
    }

    /** POST .../issues/{number}/comments — comments on a PR (PRs share the issues API). */
    suspend fun commentPr(owner: String, repo: String, number: Int, body: String): PrComment {
        val requestBody = buildJsonObject { put("body", body) }
        val json = postJson(
            "$apiBaseUrl/repos/$owner/$repo/issues/$number/comments",
            requestBody,
            "Comment on PR",
        ) ?: throw CodexError.Unknown("Comment on PR: empty response")
        return PrComment(
            id = json["id"]?.jsonPrimitive?.longOrNull ?: 0L,
            htmlUrl = json["html_url"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }

    private fun parsePr(json: JsonObject): PullRequest = PullRequest(
        number = json["number"]?.jsonPrimitive?.intOrNull ?: 0,
        title = json["title"]?.jsonPrimitive?.contentOrNull ?: "",
        state = json["state"]?.jsonPrimitive?.contentOrNull ?: "",
        htmlUrl = json["html_url"]?.jsonPrimitive?.contentOrNull ?: "",
        headBranch = json["head"]?.jsonObject?.get("ref")?.jsonPrimitive?.contentOrNull,
        baseBranch = json["base"]?.jsonObject?.get("ref")?.jsonPrimitive?.contentOrNull,
    )
}
