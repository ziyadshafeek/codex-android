package dev.codex.android.ci

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * JVM unit tests for [SelfHealingBuildLoop] with a fake OkHttp
 * [Interceptor] driving the [GitHubActionsEngine]. No real network,
 * no real GitHub, no real PAT, no model — the "fixer" is a test lambda.
 */
class SelfHealingBuildLoopTest {

    private class FakeGitHub(private val handler: (Request) -> Response) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = handler(chain.request())
    }

    private fun jsonResponse(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
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

    private fun runJson(id: Long, status: String, conclusion: String?): String {
        val conclusionJson = if (conclusion == null) "null" else "\"$conclusion\""
        return """{"id":$id,"status":"$status","conclusion":$conclusionJson,"head_branch":"main","head_sha":"abc","html_url":"https://github.com/o/r/actions/runs/$id","created_at":"2026-11-19T21:00:00Z"}"""
    }

    private fun runsListJson(vararg runs: String): String =
        """{"total_count":${runs.size},"workflow_runs":[${runs.joinToString(",")}]}"""

    private fun zipWithApk(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("app-debug.apk"))
            zip.write("fake-apk".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private val failureLog =
        "2026-11-19T21:00:02.0000000Z e: Main.kt: (12, 5): Unresolved reference: foo\n" +
            "2026-11-19T21:00:03.0000000Z BUILD FAILED in 12s\n"

    /**
     * Full fake GitHub: a queue of runs-list responses (consumed in
     * order by latestRunId + watchRun polls), plus fixed jobs/logs/
     * artifacts/zip responses.
     */
    private fun loopEngine(runsQueue: ArrayDeque<String>): Pair<GitHubActionsEngine, MutableList<String>> {
        val fixerCalls = mutableListOf<String>()
        val fake = FakeGitHub { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/actions/runs") -> jsonResponse(request, 200, runsQueue.removeFirst())
                path.endsWith("/jobs") ->
                    jsonResponse(request, 200, """{"jobs":[{"id":7,"name":"build","conclusion":"failure"}]}""")
                path.endsWith("/jobs/7/logs") ->
                    redirectResponse(request, "https://blob.example.invalid/job-log.txt")
                path.endsWith("/artifacts") ->
                    jsonResponse(request, 200, """{"artifacts":[{"id":55,"name":"apk","size_in_bytes":8,"expired":false}]}""")
                path.endsWith("/artifacts/55/zip") ->
                    redirectResponse(request, "https://blob.example.invalid/artifact.zip")
                request.url.host == "blob.example.invalid" && path.endsWith("job-log.txt") ->
                    bytesResponse(request, failureLog.toByteArray(Charsets.UTF_8), "text/plain")
                request.url.host == "blob.example.invalid" ->
                    bytesResponse(request, zipWithApk(), "application/zip")
                else -> jsonResponse(request, 404, """{"message":"Not Found"}""")
            }
        }
        val client = OkHttpClient.Builder().addInterceptor(fake).build()
        val engine = GitHubActionsEngine(tokenProvider = { "ghp_test-token" }, httpClient = client)
        return engine to fixerCalls
    }

    private fun testConfig(maxAttempts: Int = 5) = BuildLoopConfig(
        maxAttempts = maxAttempts,
        pollIntervalMs = 5,
        pollTimeoutMs = 5_000,
    )

    @Test
    fun loop_succeedsAfterOneFix() {
        val runsQueue = ArrayDeque(
            listOf(
                // Attempt 1: previous run, then new run in_progress, then failed.
                runsListJson(runJson(90, "completed", "success")),
                runsListJson(runJson(100, "in_progress", null)),
                runsListJson(runJson(100, "completed", "failure")),
                // Attempt 2: previous run, then new run in_progress, then green.
                runsListJson(runJson(100, "completed", "failure")),
                runsListJson(runJson(101, "in_progress", null)),
                runsListJson(runJson(101, "completed", "success")),
            ),
        )
        val (engine, _) = loopEngine(runsQueue)
        val loop = SelfHealingBuildLoop(engine, testConfig())
        val root = Files.createTempDirectory("ci-loop-test-").toFile()
        try {
            var pushes = 0
            var fixerAttempts = 0
            var fixerLogs = ""
            val result = runBlocking {
                loop.run(
                    owner = "o", repo = "r", branch = "main",
                    outputDir = File(root, "builds/r"),
                    push = { pushes++ },
                    fixer = { _, logs ->
                        fixerAttempts++
                        fixerLogs = logs
                        true // pretend the fix was applied
                    },
                )
            }
            val success = result as BuildLoopResult.Success
            assertEquals(2, success.attemptsUsed)
            assertEquals(2, pushes)
            assertEquals(1, fixerAttempts)
            assertTrue(fixerLogs.contains("Unresolved reference: foo"))
            assertEquals(101L, success.run.id)
            assertNull(success.artifactError)
            assertNotNull(success.artifact)
            val artifact = success.artifact!!
            assertNotNull(artifact.apk)
            assertEquals("app-debug.apk", artifact.apk!!.name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun loop_stopsWhenFixerDeclines() {
        val runsQueue = ArrayDeque(
            listOf(
                runsListJson(runJson(90, "completed", "success")),
                runsListJson(runJson(100, "in_progress", null)),
                runsListJson(runJson(100, "completed", "failure")),
            ),
        )
        val (engine, _) = loopEngine(runsQueue)
        val loop = SelfHealingBuildLoop(engine, testConfig())
        val root = Files.createTempDirectory("ci-loop-decline-").toFile()
        try {
            var pushes = 0
            val result = runBlocking {
                loop.run(
                    owner = "o", repo = "r", branch = "main",
                    outputDir = File(root, "builds/r"),
                    push = { pushes++ },
                    fixer = { _, _ -> false }, // nothing fixable from code
                )
            }
            val failure = result as BuildLoopResult.Failure
            assertEquals(1, failure.attemptsUsed)
            assertEquals(1, pushes)
            assertTrue(failure.reason.contains("no change"))
            assertTrue(failure.lastLogs.contains("Unresolved reference: foo"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun loop_failsAfterMaxAttempts() {
        val runsQueue = ArrayDeque(
            listOf(
                // Attempt 1 fails.
                runsListJson(runJson(90, "completed", "success")),
                runsListJson(runJson(100, "in_progress", null)),
                runsListJson(runJson(100, "completed", "failure")),
                // Attempt 2 fails too.
                runsListJson(runJson(100, "completed", "failure")),
                runsListJson(runJson(101, "in_progress", null)),
                runsListJson(runJson(101, "completed", "failure")),
            ),
        )
        val (engine, _) = loopEngine(runsQueue)
        val loop = SelfHealingBuildLoop(engine, testConfig(maxAttempts = 2))
        val root = Files.createTempDirectory("ci-loop-max-").toFile()
        try {
            var pushes = 0
            var fixerCalls = 0
            val result = runBlocking {
                loop.run(
                    owner = "o", repo = "r", branch = "main",
                    outputDir = File(root, "builds/r"),
                    push = { pushes++ },
                    fixer = { _, _ ->
                        fixerCalls++
                        true
                    },
                )
            }
            val failure = result as BuildLoopResult.Failure
            assertEquals(2, failure.attemptsUsed)
            assertEquals(2, pushes)
            assertEquals(1, fixerCalls) // fixer runs between attempts, not after the last
            assertTrue(failure.reason.contains("after 2 attempts"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun loop_firstAttemptGreenReturnsImmediately() {
        val runsQueue = ArrayDeque(
            listOf(
                runsListJson(runJson(90, "completed", "success")),
                runsListJson(runJson(100, "completed", "success")),
            ),
        )
        val (engine, _) = loopEngine(runsQueue)
        val loop = SelfHealingBuildLoop(engine, testConfig())
        val root = Files.createTempDirectory("ci-loop-green-").toFile()
        try {
            var fixerCalls = 0
            val result = runBlocking {
                loop.run(
                    owner = "o", repo = "r", branch = "main",
                    outputDir = File(root, "builds/r"),
                    push = {},
                    fixer = { _, _ ->
                        fixerCalls++
                        true
                    },
                )
            }
            val success = result as BuildLoopResult.Success
            assertEquals(1, success.attemptsUsed)
            assertEquals(0, fixerCalls)
            assertNotNull(success.artifact?.apk)
        } finally {
            root.deleteRecursively()
        }
    }
}
