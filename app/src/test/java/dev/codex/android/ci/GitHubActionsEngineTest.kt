package dev.codex.android.ci

import dev.codex.android.core.CodexError
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * JVM unit tests for [GitHubActionsEngine] with a fake OkHttp
 * [Interceptor]. No real network call is ever made: the interceptor
 * short-circuits every request before it leaves the client, and no real
 * GitHub calls, PAT, or model are involved.
 */
class GitHubActionsEngineTest {

    /** Handler-based fake: each test installs a route function and records requests. */
    private class FakeGitHub(
        private val handler: (Request) -> Response,
    ) : Interceptor {
        val seenRequests = mutableListOf<Request>()
        val seenAuthHeaders = mutableListOf<String?>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            seenRequests += request
            seenAuthHeaders += request.header("Authorization")
            return handler(request)
        }
    }

    private fun jsonResponse(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .header("Content-Type", "application/json")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun redirectResponse(request: Request, location: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(302)
            .message("Found")
            .header("Location", location)
            .body("".toResponseBody(null))
            .build()

    private fun bytesResponse(request: Request, bytes: ByteArray, contentType: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", contentType)
            .body(bytes.toResponseBody(contentType.toMediaType()))
            .build()

    private fun requestBody(request: Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun engineWith(fake: FakeGitHub, token: String? = "ghp_test-token"): GitHubActionsEngine {
        val client = OkHttpClient.Builder().addInterceptor(fake).build()
        return GitHubActionsEngine(tokenProvider = { token }, httpClient = client)
    }

    private fun runJson(id: Long, status: String, conclusion: String?): String {
        val conclusionJson = if (conclusion == null) "null" else "\"$conclusion\""
        return """{"id":$id,"status":"$status","conclusion":$conclusionJson,"head_branch":"main","head_sha":"abc123","html_url":"https://github.com/o/r/actions/runs/$id","created_at":"2026-11-19T21:00:00Z"}"""
    }

    private fun runsListJson(vararg runs: String): String =
        """{"total_count":${runs.size},"workflow_runs":[${runs.joinToString(",")}]}"""

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // -- repo creation -------------------------------------------------------

    @Test
    fun createRepo_returnsRepoAndSendsBearerToken() {
        val fake = FakeGitHub { request ->
            if (request.url.encodedPath == "/user/repos" && request.method == "POST") {
                jsonResponse(
                    request, 201,
                    """{"id":42,"name":"habit-tracker","full_name":"octo/habit-tracker","html_url":"https://github.com/octo/habit-tracker","owner":{"login":"octo"}}""",
                )
            } else {
                jsonResponse(request, 404, """{"message":"Not Found"}""")
            }
        }
        val engine = engineWith(fake)
        val repo = runBlocking { engine.createRepo("habit-tracker", private = true) }

        assertEquals(42L, repo.id)
        assertEquals("octo", repo.owner)
        assertEquals("habit-tracker", repo.name)
        assertEquals("octo/habit-tracker", repo.fullName)
        assertEquals("Bearer ghp_test-token", fake.seenAuthHeaders.single())
        assertTrue(requestBody(fake.seenRequests.single()).contains("\"name\":\"habit-tracker\""))
        assertTrue(requestBody(fake.seenRequests.single()).contains("\"auto_init\":false"))
    }

    @Test
    fun missingToken_throwsAuth() {
        val fake = FakeGitHub { request -> jsonResponse(request, 200, "{}") }
        val engine = engineWith(fake, token = null)
        assertThrows(CodexError.Auth::class.java) {
            runBlocking { engine.createRepo("x") }
        }
        assertTrue(fake.seenRequests.isEmpty()) // fails before any HTTP call
    }

    @Test
    fun createRepo_maps401ToAuth() {
        val fake = FakeGitHub { request ->
            jsonResponse(request, 401, """{"message":"Bad credentials"}""")
        }
        val engine = engineWith(fake, token = "ghp_bad")
        assertThrows(CodexError.Auth::class.java) {
            runBlocking { engine.createRepo("x") }
        }
    }

    // -- watch / poll ----------------------------------------------------------

    @Test
    fun watchRun_pollsUntilCompleted() {
        val runs = ArrayDeque(
            listOf(
                runsListJson(runJson(100, "in_progress", null)),
                runsListJson(runJson(100, "completed", "success")),
            ),
        )
        val fake = FakeGitHub { request ->
            jsonResponse(request, 200, runs.removeFirst())
        }
        val engine = engineWith(fake)
        val run = runBlocking {
            engine.watchRun("o", "r", "main", pollIntervalMs = 5, pollTimeoutMs = 5_000)
        }
        assertEquals(100L, run.id)
        assertTrue(run.isSuccess)
        assertEquals(2, fake.seenRequests.size)
    }

    @Test
    fun watchRun_throwsTimeoutWhenNeverCompletes() {
        val fake = FakeGitHub { request ->
            jsonResponse(request, 200, runsListJson(runJson(100, "in_progress", null)))
        }
        val engine = engineWith(fake)
        assertThrows(CodexError.Timeout::class.java) {
            runBlocking {
                engine.watchRun("o", "r", "main", pollIntervalMs = 5, pollTimeoutMs = 50)
            }
        }
    }

    @Test
    fun watchRun_excludeRunIdIgnoresPreviousRun() {
        // First poll returns only the pre-push run (id 90); second poll
        // returns the new run (id 100) ahead of it.
        val runs = ArrayDeque(
            listOf(
                runsListJson(runJson(90, "completed", "success")),
                runsListJson(runJson(100, "completed", "success"), runJson(90, "completed", "success")),
            ),
        )
        val fake = FakeGitHub { request ->
            jsonResponse(request, 200, runs.removeFirst())
        }
        val engine = engineWith(fake)
        val run = runBlocking {
            engine.watchRun("o", "r", "main", excludeRunId = 90, pollIntervalMs = 5, pollTimeoutMs = 5_000)
        }
        assertEquals(100L, run.id)
    }

    // -- failure logs ----------------------------------------------------------

    @Test
    fun getFailedLogs_followsRedirectWithoutAuthAndExtractsTail() {
        val rawLog = "2026-11-19T21:00:01.1234567Z Download https://repo.maven.apache.org/maven2/x.jar\n" +
            "2026-11-19T21:00:02.1234567Z \u001B[31me: Main.kt: (12, 5): Unresolved reference: foo\u001B[0m\n" +
            "2026-11-19T21:00:03.1234567Z > Task :app:compileDebugKotlin FAILED\n" +
            "2026-11-19T21:00:04.1234567Z BUILD FAILED in 12s\n"
        val blobAuthHeaders = mutableListOf<String?>()
        val fake = FakeGitHub { request ->
            when {
                request.url.encodedPath.endsWith("/jobs") ->
                    jsonResponse(
                        request, 200,
                        """{"jobs":[{"id":7,"name":"build","conclusion":"failure"},{"id":8,"name":"lint","conclusion":"success"}]}""",
                    )
                request.url.encodedPath.endsWith("/jobs/7/logs") ->
                    redirectResponse(request, "https://blob.example.invalid/job-log.txt")
                request.url.host == "blob.example.invalid" -> {
                    blobAuthHeaders += request.header("Authorization")
                    bytesResponse(request, rawLog.toByteArray(Charsets.UTF_8), "text/plain")
                }
                else -> jsonResponse(request, 404, """{"message":"Not Found"}""")
            }
        }
        val engine = engineWith(fake)
        val logs = runBlocking { engine.getFailedLogs("o", "r", 100) }

        assertTrue(logs.contains("## Job: build (id 7)"))
        assertTrue(logs.contains("e: Main.kt: (12, 5): Unresolved reference: foo"))
        assertTrue(logs.contains("BUILD FAILED"))
        assertFalse(logs.contains("Download https://")) // noise stripped
        assertFalse(logs.contains("2026-11-19T21:00")) // timestamps stripped
        assertFalse(logs.contains("\u001B[")) // ANSI stripped
        assertEquals(listOf<String?>(null), blobAuthHeaders) // blob hop carries NO auth
        assertFalse(logs.contains("lint")) // only the failed job's logs
    }

    @Test
    fun getFailedLogs_reportsWhenNoJobFailed() {
        val fake = FakeGitHub { request ->
            jsonResponse(request, 200, """{"jobs":[{"id":7,"name":"build","conclusion":"success"}]}""")
        }
        val engine = engineWith(fake)
        val logs = runBlocking { engine.getFailedLogs("o", "r", 100) }
        assertTrue(logs.contains("no failed job was reported"))
    }

    // -- log extraction (pure function) ----------------------------------------

    @Test
    fun extractFailureTail_keepsErrorsAndCapsLength() {
        val raw = buildString {
            repeat(200) { append("2026-11-19T21:00:00.0000000Z some chatty gradle line $it\n") }
            append("e: Foo.kt: (3, 1): Type mismatch: inferred type is Int but String was expected\n")
            append("BUILD FAILED in 30s\n")
        }
        val tail = extractFailureTail(raw, maxChars = 500)
        assertTrue(tail.length <= 500)
        assertTrue(tail.contains("Type mismatch"))
        assertTrue(tail.contains("BUILD FAILED"))
        assertTrue(tail.contains("truncated"))
    }

    @Test
    fun extractFailureTail_emptyLogReturnsEmpty() {
        assertEquals("", extractFailureTail(""))
    }

    // -- artifacts -------------------------------------------------------------

    @Test
    fun downloadArtifact_extractsOnlyApkAndVerifiesSha() {
        val apkBytes = "fake-apk-bytes".toByteArray(Charsets.UTF_8)
        val zip = zipBytes(
            "app-debug.apk" to apkBytes,
            "readme.txt" to "hello".toByteArray(Charsets.UTF_8),
            "../evil.apk" to "evil".toByteArray(Charsets.UTF_8), // zip-slip attempt
        )
        val fake = FakeGitHub { request ->
            when {
                request.url.encodedPath.endsWith("/artifacts/55/zip") ->
                    redirectResponse(request, "https://blob.example.invalid/artifact.zip")
                request.url.host == "blob.example.invalid" ->
                    bytesResponse(request, zip, "application/zip")
                else -> jsonResponse(request, 404, """{"message":"Not Found"}""")
            }
        }
        val engine = engineWith(fake)
        val root = Files.createTempDirectory("ci-artifact-test-").toFile()
        try {
            val outDir = File(root, "builds/r/100")
            val artifact = runBlocking {
                engine.downloadArtifact("o", "r", artifactId = 55, runId = 100, outputDir = outDir)
            }
            assertEquals(1, artifact.files.size)
            val apk = artifact.apk!!
            assertEquals("app-debug.apk", apk.name)
            assertEquals(apkBytes.size.toLong(), apk.sizeBytes)
            assertEquals(sha256Hex(apk.file), apk.sha256)
            assertTrue(apk.file.readBytes().contentEquals(apkBytes))
            // Zip-slip entry never escaped the output dir.
            assertFalse(File(root, "evil.apk").exists())
            assertFalse(File(outDir, "readme.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun downloadArtifact_rejectsShaMismatch() {
        val zip = zipBytes("app-debug.apk" to "fake-apk-bytes".toByteArray(Charsets.UTF_8))
        val fake = FakeGitHub { request ->
            when {
                request.url.encodedPath.endsWith("/artifacts/55/zip") ->
                    redirectResponse(request, "https://blob.example.invalid/artifact.zip")
                request.url.host == "blob.example.invalid" ->
                    bytesResponse(request, zip, "application/zip")
                else -> jsonResponse(request, 404, """{"message":"Not Found"}""")
            }
        }
        val engine = engineWith(fake)
        val root = Files.createTempDirectory("ci-sha-test-").toFile()
        try {
            assertThrows(CodexError.Unknown::class.java) {
                runBlocking {
                    engine.downloadArtifact(
                        "o", "r", artifactId = 55, runId = 100,
                        outputDir = File(root, "builds/r/100"),
                        expectedSha256 = "00".repeat(32), // wrong on purpose
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun downloadLatestArtifact_picksNamedArtifact() {
        val zip = zipBytes("app-debug.apk" to "apk!".toByteArray(Charsets.UTF_8))
        val fake = FakeGitHub { request ->
            when {
                request.url.encodedPath.endsWith("/artifacts") ->
                    jsonResponse(
                        request, 200,
                        """{"artifacts":[{"id":55,"name":"apk","size_in_bytes":4,"expired":false},{"id":56,"name":"other","size_in_bytes":9,"expired":false}]}""",
                    )
                request.url.encodedPath.endsWith("/artifacts/55/zip") ->
                    redirectResponse(request, "https://blob.example.invalid/artifact.zip")
                request.url.host == "blob.example.invalid" ->
                    bytesResponse(request, zip, "application/zip")
                else -> jsonResponse(request, 404, """{"message":"Not Found"}""")
            }
        }
        val engine = engineWith(fake)
        val root = Files.createTempDirectory("ci-latest-test-").toFile()
        try {
            val artifact = runBlocking {
                engine.downloadLatestArtifact(
                    "o", "r", runId = 100,
                    outputDir = File(root, "builds/r/100"), artifactName = "apk",
                )
            }
            assertEquals(55L, artifact.artifactId)
            assertEquals(1, artifact.files.size)
        } finally {
            root.deleteRecursively()
        }
    }

    // -- pull requests -----------------------------------------------------------

    @Test
    fun createPr_listPrs_searchPrs_commentPr_getPrDiff() {
        val fake = FakeGitHub { request ->
            val path = request.url.encodedPath
            when {
                path == "/repos/o/r/pulls" && request.method == "POST" ->
                    jsonResponse(
                        request, 201,
                        """{"number":3,"title":"Fix build","state":"open","html_url":"https://github.com/o/r/pull/3","head":{"ref":"codex/fix"},"base":{"ref":"main"}}""",
                    )
                path == "/repos/o/r/pulls" && request.method == "GET" ->
                    jsonResponse(
                        request, 200,
                        """[{"number":3,"title":"Fix build","state":"open","html_url":"https://github.com/o/r/pull/3","head":{"ref":"codex/fix"},"base":{"ref":"main"}}]""",
                    )
                path == "/search/issues" ->
                    jsonResponse(
                        request, 200,
                        """{"total_count":1,"items":[{"number":3,"title":"Fix build","state":"open","html_url":"https://github.com/o/r/pull/3","head":{"ref":"codex/fix"},"base":{"ref":"main"}}]}""",
                    )
                path == "/repos/o/r/pulls/3" && request.method == "GET" ->
                    bytesResponse(request, "diff --git a/Main.kt b/Main.kt\n".toByteArray(Charsets.UTF_8), "text/plain")
                path == "/repos/o/r/issues/3/comments" && request.method == "POST" ->
                    jsonResponse(request, 201, """{"id":99,"html_url":"https://github.com/o/r/pull/3#issuecomment-99"}""")
                else -> jsonResponse(request, 404, """{"message":"Not Found"}""")
            }
        }
        val engine = engineWith(fake)
        runBlocking {
            val pr = engine.createPr("o", "r", "Fix build", head = "codex/fix", base = "main")
            assertEquals(3, pr.number)
            assertEquals("codex/fix", pr.headBranch)
            assertEquals("main", pr.baseBranch)

            val list = engine.listPrs("o", "r")
            assertEquals(1, list.size)
            assertEquals(3, list[0].number)

            // OpenCode github-pr-search pattern: search existing PRs by repo + query.
            val found = engine.searchPrs("o", "r", "Fix build")
            assertEquals(1, found.size)
            assertEquals("Fix build", found[0].title)
            val searchQuery = fake.seenRequests
                .first { it.url.encodedPath == "/search/issues" }.url.queryParameter("q")
            assertTrue(searchQuery!!.contains("type:pr repo:o/r"))

            val diff = engine.getPrDiff("o", "r", 3)
            assertTrue(diff.startsWith("diff --git"))

            val comment = engine.commentPr("o", "r", 3, "CI is green now.")
            assertEquals(99L, comment.id)
        }
    }

    @Test
    fun triggerWorkflow_postsDispatch() {
        val fake = FakeGitHub { request ->
            if (request.url.encodedPath.endsWith("/dispatches")) {
                jsonResponse(request, 204, "")
            } else {
                jsonResponse(request, 404, """{"message":"Not Found"}""")
            }
        }
        val engine = engineWith(fake)
        runBlocking { engine.triggerWorkflow("o", "r", "build.yml", ref = "main") }
        val dispatched = fake.seenRequests.single()
        assertTrue(requestBody(dispatched).contains("\"ref\":\"main\""))
    }
}
