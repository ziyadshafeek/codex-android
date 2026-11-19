package dev.codex.android.vcs

import dev.codex.android.linux.ProotShellEngine
import dev.codex.android.linux.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM unit tests for [GitWorktreeEngine]: command building, remote parsing, and
 * ephemeral GIT_ASKPASS safety (PAT never embedded in script, temp script deleted,
 * leaked PAT scrubbed from stderr/stdout). Never executes real git.
 */
class GitWorktreeEngineTest {

    private lateinit var tempDir: File
    private lateinit var rootfsDir: File
    private lateinit var nativeLibDir: File
    private lateinit var repoDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("codex-worktree-test").toFile()
        rootfsDir = File(tempDir, "rootfs").apply { mkdirs() }
        nativeLibDir = File(tempDir, "native").apply { mkdirs() }
        repoDir = File(tempDir, "codex-android").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private class RecordingProotEngine(
        rootfsDir: File,
        nativeLibDir: File,
        private val responder: (command: String, env: Map<String, String>) -> ShellResult,
    ) : ProotShellEngine(rootfsDir, nativeLibDir) {
        val recordedCommands = mutableListOf<String>()
        val recordedEnvs = mutableListOf<Map<String, String>>()

        override suspend fun shellExec(command: String, timeoutMs: Long): ShellResult =
            shellExecWithEnv(command, emptyMap(), timeoutMs)

        override suspend fun shellExecWithEnv(
            command: String,
            extraEnv: Map<String, String>,
            timeoutMs: Long,
            workingDirInGuest: String,
        ): ShellResult {
            recordedCommands += command
            recordedEnvs += extraEnv
            return responder(command, extraEnv)
        }
    }

    @Test
    fun commandBuilders_quotePathsAndBranchesSafely() {
        val proot = ProotShellEngine(rootfsDir, nativeLibDir)
        val engine = GitWorktreeEngine(proot, repoDir)
        val wtDir = File(tempDir, "wt-1")

        val addCmd = engine.buildAddWorktreeCommand("feat/self-host", wtDir)
        assertTrue(addCmd.contains("worktree add -B 'feat/self-host'"))
        assertTrue(addCmd.contains(wtDir.canonicalPath))

        val mergeCmd = engine.buildMergeCommand("feat/self-host")
        assertTrue(mergeCmd.contains("merge --no-ff 'feat/self-host'"))

        assertEquals("ziyadshafeek" to "codex-android", engine.parseGitHubOwnerRepo("https://github.com/ziyadshafeek/codex-android.git"))
        assertEquals("ziyadshafeek" to "codex-android", engine.parseGitHubOwnerRepo("git@github.com:ziyadshafeek/codex-android.git"))
        assertNull(engine.parseGitHubOwnerRepo("https://gitlab.com/a/b"))
    }

    @Test
    fun ephemeralAskpass_neverWritesPatToDiskDeletesScriptAndScrubsOutput() = runBlocking {
        val fakePat = "ghp_TEST_SECRET_TOKEN_9876543210"
        var capturedScriptContents = ""
        var askpassHostFile: File? = null

        val proot = RecordingProotEngine(rootfsDir, nativeLibDir) { _, env ->
            val guestPath = env["GIT_ASKPASS"] ?: ""
            assertTrue(guestPath.startsWith("/tmp/codex-askpass-"))
            assertEquals("0", env["GIT_TERMINAL_PROMPT"])
            assertEquals(fakePat, env["CODEX_EPHEMERAL_GIT_TOKEN"])
            val file = File(File(rootfsDir, "tmp"), guestPath.removePrefix("/tmp/"))
            askpassHostFile = file
            capturedScriptContents = file.readText()
            // Simulate git accidentally echoing the token in stderr.
            ShellResult(0, "pushed", "remote: debug token=$fakePat")
        }

        val engine = GitWorktreeEngine(proot, repoDir, githubPatProvider = { fakePat })
        val res = engine.runWithEphemeralAskpass("git push origin main")

        assertFalse("PAT must never be written into askpass script", capturedScriptContents.contains(fakePat))
        assertTrue(capturedScriptContents.contains("CODEX_EPHEMERAL_GIT_TOKEN"))
        assertFalse("askpass temp file must be deleted after command", askpassHostFile!!.exists())
        assertFalse("PAT must be scrubbed from stderr", res.stderr.contains(fakePat))
        assertTrue(res.stderr.contains("[REDACTED]"))
    }

    @Test
    fun ephemeralAskpass_missingPatFailsBeforeSpawning() = runBlocking {
        val proot = RecordingProotEngine(rootfsDir, nativeLibDir) { _, _ -> ShellResult(0, "", "") }
        val engine = GitWorktreeEngine(proot, repoDir, githubPatProvider = { null })
        try {
            engine.runWithEphemeralAskpass("git push")
            fail("Expected IllegalStateException when PAT is missing")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("PAT"))
        }
        assertTrue(proot.recordedCommands.isEmpty())
    }
}
