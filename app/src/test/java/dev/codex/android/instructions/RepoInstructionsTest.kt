package dev.codex.android.instructions

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for [RepoInstructions] discovery: priority, nested
 * repos, missing files, cap/truncation, and symlink-escape rejection.
 * No Android APIs are used, so these run on the plain JVM.
 */
class RepoInstructionsTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("codex-instructions-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun priority_agentsMdBeatsClaudeMd() {
        File(tempDir, "AGENTS.md").writeText("Use tabs, never spaces.")
        File(tempDir, "CLAUDE.md").writeText("Use spaces, never tabs.")
        val loaded = RepoInstructions.load(tempDir)
        assertEquals(2, loaded.files.size)
        assertEquals("AGENTS.md", loaded.files[0].relativePath)
        assertEquals("CLAUDE.md", loaded.files[1].relativePath)
        assertFalse(loaded.truncated)
        val context = loaded.toAgentContext()
        assertTrue(context.contains("Use tabs, never spaces."))
        assertTrue(context.contains("Use spaces, never tabs."))
        // AGENTS.md content comes first in the injected context.
        assertTrue(context.indexOf("Use tabs") < context.indexOf("Use spaces"))
    }

    @Test
    fun claudeMdOnly_isLoadedWhenNoAgentsMd() {
        File(tempDir, "CLAUDE.md").writeText("Be concise.")
        val loaded = RepoInstructions.load(tempDir)
        assertEquals(1, loaded.files.size)
        assertEquals("CLAUDE.md", loaded.files[0].relativePath)
        assertTrue(loaded.toAgentContext().contains("Be concise."))
    }

    @Test
    fun nestedRepo_walksUpToWorkspaceRoot_repoInstructionsFirst() {
        File(tempDir, "CLAUDE.md").writeText("Workspace rule: always run tests.")
        val repo = File(tempDir, "sub/repo").apply { mkdirs() }
        File(repo, "AGENTS.md").writeText("Repo rule: Kotlin only.")
        val loaded = RepoInstructions.load(repo, tempDir)
        assertEquals(2, loaded.files.size)
        assertEquals("sub/repo/AGENTS.md", loaded.files[0].relativePath)
        assertEquals("CLAUDE.md", loaded.files[1].relativePath)
        assertTrue(loaded.toAgentContext().contains("Repo rule: Kotlin only."))
    }

    @Test
    fun walkUp_stopsAtWorkspaceRoot() {
        // An AGENTS.md above the workspace root must never be read.
        File(tempDir, "AGENTS.md").writeText("Outside the workspace.")
        val workspace = File(tempDir, "ws").apply { mkdirs() }
        val repo = File(workspace, "repo").apply { mkdirs() }
        File(repo, "CLAUDE.md").writeText("Inside the workspace.")
        val loaded = RepoInstructions.load(repo, workspace)
        assertEquals(1, loaded.files.size)
        assertEquals("repo/CLAUDE.md", loaded.files[0].relativePath)
    }

    @Test
    fun missingFiles_returnsEmptyNotError() {
        val loaded = RepoInstructions.load(tempDir)
        assertTrue(loaded.isEmpty())
        assertTrue(loaded.files.isEmpty())
        assertFalse(loaded.truncated)
        assertEquals(0, loaded.totalChars)
        assertEquals("", loaded.toAgentContext())
    }

    @Test
    fun cap_truncatesCombinedContentWithNote() {
        val big = "x".repeat(RepoInstructions.MAX_TOTAL_CHARS + 5_000)
        File(tempDir, "AGENTS.md").writeText(big)
        File(tempDir, "CLAUDE.md").writeText("small second file")
        val loaded = RepoInstructions.load(tempDir)
        assertTrue(loaded.truncated)
        assertEquals(big.length + "small second file".length, loaded.totalChars)
        val keptChars = loaded.files.sumOf { it.content.length }
        assertEquals(RepoInstructions.MAX_TOTAL_CHARS, keptChars)
        // The second file is dropped entirely once the cap is exhausted.
        assertEquals(1, loaded.files.size)
        assertTrue(loaded.toAgentContext().contains("truncated to ${RepoInstructions.MAX_TOTAL_CHARS} characters"))
    }

    @Test
    fun symlinkEscape_rejected() {
        val outside = Files.createTempDirectory("codex-instructions-outside").toFile()
        try {
            File(outside, "AGENTS.md").writeText("Secret outside instructions.")
            Files.createSymbolicLink(
                File(tempDir, "AGENTS.md").toPath(),
                File(outside, "AGENTS.md").toPath(),
            )
            val loaded = RepoInstructions.load(tempDir)
            assertTrue(loaded.isEmpty())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun repoOutsideWorkspace_searchesRepoOnly() {
        val outsideRepo = Files.createTempDirectory("codex-instructions-repo").toFile()
        try {
            File(outsideRepo, "AGENTS.md").writeText("Repo's own rules.")
            // A parent-level AGENTS.md outside the workspace must not leak in.
            File(tempDir, "AGENTS.md").writeText("Workspace rules.")
            val loaded = RepoInstructions.load(outsideRepo, tempDir)
            assertEquals(1, loaded.files.size)
            assertEquals("Repo's own rules.", loaded.files[0].content)
        } finally {
            outsideRepo.deleteRecursively()
        }
    }
}
