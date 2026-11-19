package dev.codex.android.tools

import dev.codex.android.linux.ProotShellEngine
import dev.codex.android.linux.ShellResult
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for [WorkspaceFileTools].
 *
 * JUnit 4 has no @TempDir (that is JUnit 5), so the workspace root is a
 * [Files.createTempDirectory] directory deleted in [tearDown]. No Android
 * APIs are used, so these run on the plain JVM.
 */
class WorkspaceFileToolsTest {

    private lateinit var tempDir: File
    private lateinit var tools: WorkspaceFileTools

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("codex-workspace-test").toFile()
        tools = WorkspaceFileTools(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** Runs [block] and fails unless it throws [SecurityException]. */
    private fun assertSecurityException(block: () -> Unit) {
        try {
            block()
            fail("Expected SecurityException")
        } catch (e: SecurityException) {
            // Expected.
        }
    }

    @Test
    fun resolveSafe_rejectsParentEscape() {
        assertSecurityException { tools.resolveSafe("../escape.txt") }
        assertSecurityException { tools.resolveSafe("sub/../../escape.txt") }
    }

    @Test
    fun resolveSafe_rejectsAbsolutePathsOutsideRoot() {
        assertSecurityException { tools.resolveSafe("/etc/passwd") }
        assertSecurityException { tools.resolveSafe("/tmp/escape.txt") }
    }

    @Test
    fun resolveSafe_allowsPathsInsideRoot() {
        // Must not throw; writing through it proves the resolution is usable.
        tools.writeFile("sub/inner.txt", "ok")
        assertTrue(tools.readFile("sub/inner.txt").contains("ok"))
    }

    @Test
    fun writeFile_thenReadFile_roundTrip() {
        tools.writeFile("hello.txt", "line one\nline two\nline three")
        val read = tools.readFile("hello.txt")
        assertTrue(read.contains("1 | line one"))
        assertTrue(read.contains("2 | line two"))
        assertTrue(read.contains("3 | line three"))
    }

    @Test
    fun readFile_supportsLineRanges() {
        tools.writeFile("ranged.txt", "a\nb\nc\nd\ne")
        val read = tools.readFile("ranged.txt", startLine = 2, maxLines = 2)
        assertTrue(read.contains("2 | b"))
        assertTrue(read.contains("3 | c"))
        assertFalse(read.contains("4 | d"))
        assertTrue(read.contains("more lines"))
    }

    @Test
    fun readFile_missingFile_returnsError() {
        assertTrue(tools.readFile("nope.txt").startsWith("Error: file not found"))
    }

    @Test
    fun applyPatch_replacesUniqueSnippet() {
        tools.writeFile("patch.txt", "alpha\nbeta\ngamma\n")
        val result = tools.applyPatch("patch.txt", "beta", "BETA")
        assertEquals("Patched patch.txt", result)
        assertTrue(tools.readFile("patch.txt").contains("BETA"))
        assertFalse(tools.readFile("patch.txt").contains("\nbeta\n"))
    }

    @Test
    fun applyPatch_missingSnippet_returnsError() {
        tools.writeFile("patch.txt", "alpha\nbeta\n")
        val result = tools.applyPatch("patch.txt", "does not exist", "x")
        assertTrue(result.startsWith("Error: target snippet not found"))
        // File left untouched.
        assertTrue(tools.readFile("patch.txt").contains("beta"))
    }

    @Test
    fun applyPatch_nonUniqueSnippet_returnsError() {
        tools.writeFile("patch.txt", "dup\ndup\n")
        val result = tools.applyPatch("patch.txt", "dup", "x")
        assertTrue(result.contains("matched 2 times"))
        assertTrue(tools.readFile("patch.txt").contains("dup"))
    }

    @Test
    fun listFiles_listsWrittenFiles() {
        tools.writeFile("a.txt", "a")
        tools.writeFile("sub/b.txt", "b")
        tools.writeFile("sub/c.txt", "c")
        val listed = tools.listFiles()
        assertEquals(listOf("a.txt", "sub/b.txt", "sub/c.txt"), listed)
        assertEquals(listOf("sub/b.txt", "sub/c.txt"), tools.listFiles("sub"))
    }

    @Test
    fun getChanges_newFileDiffContainsAddedLines() {
        // No trailing newline: "hello\nworld\n".lines() would yield a third
        // empty line, making addedLines 3 instead of 2.
        tools.writeFile("new.txt", "hello\nworld")
        val changes = tools.getChanges()
        assertEquals(1, changes.size)
        val change = changes.first()
        assertEquals("new.txt", change.relativePath)
        assertTrue(change.isNewFile)
        assertEquals(2, change.addedLines)
        assertEquals(0, change.removedLines)
        assertTrue(change.unifiedDiff.contains("--- a/new.txt"))
        assertTrue(change.unifiedDiff.contains("+++ b/new.txt"))
        assertTrue(change.unifiedDiff.contains("+hello"))
        assertTrue(change.unifiedDiff.contains("+world"))
    }

    @Test
    fun getChanges_editedFileDiffContainsAddedAndRemovedLines() {
        // Pre-existing file (written directly, not via the tools) so the
        // snapshot captures the original text before the first tool edit.
        File(tempDir, "edit.txt").writeText("keep\nold line\nkeep2\n")
        tools.writeFile("edit.txt", "keep\nnew line\nkeep2\n")
        val changes = tools.getChanges()
        assertEquals(1, changes.size)
        val change = changes.first()
        assertFalse(change.isNewFile)
        assertEquals(1, change.addedLines)
        assertEquals(1, change.removedLines)
        assertTrue(change.unifiedDiff.contains("-old line"))
        assertTrue(change.unifiedDiff.contains("+new line"))
        assertTrue(change.unifiedDiff.contains(" keep"))
    }

    @Test
    fun getChanges_unchangedFileProducesNoEntry() {
        tools.writeFile("same.txt", "x\n")
        // Reverting the only edit leaves no change behind.
        tools.revertFile("same.txt")
        assertTrue(tools.getChanges().isEmpty())
    }

    @Test
    fun revertFile_restoresOriginalContent() {
        // Pre-existing file so the snapshot holds "original", not null.
        File(tempDir, "revert.txt").writeText("original\n")
        tools.writeFile("revert.txt", "changed\n")
        assertTrue(tools.revertFile("revert.txt"))
        assertTrue(tools.readFile("revert.txt").contains("original"))
        assertFalse(tools.readFile("revert.txt").contains("changed"))
        // Second revert: nothing left to revert.
        assertFalse(tools.revertFile("revert.txt"))
    }

    @Test
    fun revertFile_deletesFileThatDidNotExistBefore() {
        tools.writeFile("brand-new.txt", "temp\n")
        assertTrue(tools.revertFile("brand-new.txt"))
        assertTrue(tools.readFile("brand-new.txt").startsWith("Error: file not found"))
    }

    @Test
    fun revertAll_restoresEveryEditedFile() {
        File(tempDir, "one.txt").writeText("one\n")
        File(tempDir, "two.txt").writeText("two\n")
        tools.writeFile("one.txt", "ONE\n")
        tools.writeFile("two.txt", "TWO\n")
        tools.revertAll()
        assertTrue(tools.readFile("one.txt").contains("one"))
        assertTrue(tools.readFile("two.txt").contains("two"))
        assertTrue(tools.getChanges().isEmpty())
    }

    @Test
    fun searchText_findsMatchesWithLineNumbers() {
        tools.writeFile("search.txt", "first line\nneedle here\nlast line\n")
        tools.writeFile("other.txt", "no match\n")
        val result = tools.searchText("needle")
        assertTrue(result.contains("search.txt:2: needle here"))
        assertFalse(result.contains("other.txt"))
        assertEquals("No matches for 'zzz'", tools.searchText("zzz"))
    }

    // --- Post-edit loop (ProotShellEngine wired) ---

    /**
     * Fake runtime: records every post-edit shell command and answers
     * 127 (binary not installed), so the report degrades to a one-line
     * "skipped" note instead of failing the edit. The real
     * [ProotShellEngine] constructor does no I/O, so this runs on the
     * plain JVM.
     */
    private class RecordingProotEngine : ProotShellEngine(File("rootfs"), File("nativeLib")) {
        val commands = mutableListOf<String>()

        override suspend fun shellExec(command: String, timeoutMs: Long): ShellResult {
            commands += command
            return ShellResult(exitCode = 127, stdout = "", stderr = "")
        }
    }

    @Test
    fun writeFile_withProot_runsPostEditAndAppendsReport() {
        val engine = RecordingProotEngine()
        val wired = WorkspaceFileTools(tempDir, proot = engine)
        val result = wired.writeFile("script.py", "x = 1\n")
        // Edit itself succeeded and the file holds the new content.
        assertTrue(wired.readFile("script.py").contains("x = 1"))
        // Post-edit ran (ruff format + pyright probes) and its report
        // is appended; a 127 degrades to "skipped", never an error.
        assertTrue(engine.commands.isNotEmpty())
        assertTrue(result.contains("[Post-Edit Quality Loop]"))
        assertTrue(result.contains("Formatter: skipped (ruff not installed in rootfs)"))
        assertTrue(result.contains("LSP: skipped (pyright not installed in rootfs)"))
    }

    @Test
    fun applyPatch_withProot_runsPostEdit() {
        val engine = RecordingProotEngine()
        val wired = WorkspaceFileTools(tempDir, proot = engine)
        wired.writeFile("app.py", "a = 1\n")
        engine.commands.clear()
        val result = wired.applyPatch("app.py", "a = 1", "a = 2")
        assertTrue(result.startsWith("Patched app.py"))
        assertTrue(result.contains("[Post-Edit Quality Loop]"))
        assertTrue(engine.commands.isNotEmpty())
        assertTrue(wired.readFile("app.py").contains("a = 2"))
    }

    @Test
    fun writeFile_withoutProot_hasNoPostEditSuffix() {
        // Default construction (no runtime): the result is exactly the
        // historic string — existing callers and tests are unaffected.
        val result = tools.writeFile("plain.txt", "hello\n")
        assertEquals("Wrote 6 chars to plain.txt", result)
    }
}
