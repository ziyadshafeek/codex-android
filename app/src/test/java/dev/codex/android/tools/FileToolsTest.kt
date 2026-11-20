package dev.codex.android.tools

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for the agent-facing file tools ([EditTool], [ApplyPatchTool],
 * [GlobTool], [GrepTool], [SearchTool], [FileDiffTool]) plus the pure helpers
 * [GlobMatcher] and [UnifiedDiffApplier]. Temp directory workspace, plain
 * JUnit 4, no Robolectric/Mockito — same style as [WorkspaceFileToolsTest].
 */
class FileToolsTest {

    private lateinit var tempDir: File
    private lateinit var files: WorkspaceFileTools

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("codex-file-tools-test").toFile()
        files = WorkspaceFileTools(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        for ((key, value) in pairs) {
            when (value) {
                is String -> put(key, value)
                is Boolean -> put(key, value)
                is Int -> put(key, value)
                else -> throw IllegalArgumentException("Unsupported arg type: $value")
            }
        }
    }

    // --- edit ---

    @Test
    fun edit_replacesUniqueSnippet() = runBlocking {
        files.writeFile("a.txt", "alpha\nbeta\ngamma\n")
        val tool = EditTool(files)
        assertEquals("edit", tool.name)
        assertFalse(tool.sensitive)
        val result = tool.execute(args("path" to "a.txt", "old_text" to "beta", "new_text" to "BETA"))
        assertTrue(result.ok)
        assertTrue(result.output.startsWith("Patched a.txt"))
        assertTrue(files.readFile("a.txt").contains("BETA"))
    }

    @Test
    fun edit_missingSnippetFailsAndLeavesFileUntouched() = runBlocking {
        files.writeFile("a.txt", "alpha\nbeta\n")
        val result = EditTool(files).execute(args("path" to "a.txt", "old_text" to "zzz", "new_text" to "x"))
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("not found"))
        assertTrue(files.readFile("a.txt").contains("beta"))
    }

    @Test
    fun edit_ambiguousSnippetFails() = runBlocking {
        files.writeFile("a.txt", "dup\ndup\n")
        val result = EditTool(files).execute(args("path" to "a.txt", "old_text" to "dup", "new_text" to "x"))
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("2 times"))
    }

    @Test
    fun edit_rejectsEmptyOldTextAndMissingFile() = runBlocking {
        files.writeFile("a.txt", "alpha\n")
        val tool = EditTool(files)
        assertFalse(tool.execute(args("path" to "a.txt", "old_text" to "", "new_text" to "x")).ok)
        val missing = tool.execute(args("path" to "nope.txt", "old_text" to "a", "new_text" to "x"))
        assertFalse(missing.ok)
        assertTrue(missing.error!!.contains("not found"))
        assertFalse(tool.execute(args("path" to "a.txt", "old_text" to "alpha")).ok) // no new_text
    }

    @Test
    fun edit_rejectsPathEscape() = runBlocking {
        val tool = EditTool(files)
        assertFalse(tool.execute(args("path" to "../escape.txt", "old_text" to "a", "new_text" to "x")).ok)
        assertFalse(tool.execute(args("path" to "/etc/passwd", "old_text" to "a", "new_text" to "x")).ok)
        assertFalse(File(tempDir.parentFile, "escape.txt").exists())
    }

    @Test
    fun edit_secondaryConstructorTakesWorkspaceRoot() = runBlocking {
        val tool = EditTool(tempDir)
        files.writeFile("b.txt", "hello\n")
        assertTrue(tool.execute(args("path" to "b.txt", "old_text" to "hello", "new_text" to "bye")).ok)
    }

    // --- apply_patch ---

    private val twoHunkPatch = """
        --- a/app.txt
        +++ b/app.txt
        @@ -1,3 +1,3 @@
         one
        -two
        +TWO
         three
        @@ -5,3 +5,3 @@
         five
        -six
        +SIX
         seven
    """.trimIndent()

    @Test
    fun applyPatch_twoHunkDiffAppliesBoth() = runBlocking {
        files.writeFile("app.txt", "one\ntwo\nthree\nfour\nfive\nsix\nseven\n")
        val tool = ApplyPatchTool(files)
        assertEquals("apply_patch", tool.name)
        assertFalse(tool.sensitive)
        val result = tool.execute(args("patch" to twoHunkPatch))
        assertTrue(result.ok)
        val text = files.readFile("app.txt")
        assertTrue(text.contains("TWO"))
        assertTrue(text.contains("SIX"))
        assertTrue(text.contains("three"))
        assertFalse(text.contains("\ntwo\n"))
    }

    @Test
    fun applyPatch_mismatchedContextFailsWithNothingWritten() = runBlocking {
        files.writeFile("app.txt", "one\nTWO-ALREADY\nthree\nfour\nfive\nsix\nseven\n")
        val before = File(tempDir, "app.txt").readText()
        val result = ApplyPatchTool(files).execute(args("patch" to twoHunkPatch))
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("does not match"))
        assertEquals(before, File(tempDir, "app.txt").readText())
    }

    @Test
    fun applyPatch_malformedDiffFailsCleanly() = runBlocking {
        files.writeFile("app.txt", "one\ntwo\n")
        val tool = ApplyPatchTool(files)
        // Hunk header declares 3 old lines but only 1 is supplied.
        val truncated = "--- a/app.txt\n+++ b/app.txt\n@@ -1,3 +1,3 @@\n one\n"
        val r1 = tool.execute(args("patch" to truncated))
        assertFalse(r1.ok)
        assertTrue(r1.error!!.contains("Malformed"))
        // No file header and no path argument.
        val r2 = tool.execute(args("patch" to "@@ -1,1 +1,1 @@\n-a\n+b\n"))
        assertFalse(r2.ok)
        // Junk that is neither a header nor a hunk.
        val r3 = tool.execute(args("patch" to "hello world, this is not a diff"))
        assertFalse(r3.ok)
        assertEquals("one\ntwo\n", File(tempDir, "app.txt").readText())
    }

    @Test
    fun applyPatch_headerlessDiffUsesPathArg() = runBlocking {
        files.writeFile("only.txt", "a\nb\nc\n")
        val patch = "@@ -1,3 +1,3 @@\n a\n-b\n+B\n c\n"
        val result = ApplyPatchTool(files).execute(args("patch" to patch, "path" to "only.txt"))
        assertTrue(result.ok)
        assertTrue(files.readFile("only.txt").contains("B"))
    }

    @Test
    fun applyPatch_createsNewFile() = runBlocking {
        val patch = "--- /dev/null\n+++ b/new.txt\n@@ -0,0 +1,2 @@\n+hello\n+world\n"
        val result = ApplyPatchTool(files).execute(args("patch" to patch))
        assertTrue(result.ok)
        assertEquals("hello\nworld\n", File(tempDir, "new.txt").readText())
    }

    @Test
    fun applyPatch_rejectsPathEscapeAndDeletion() = runBlocking {
        val tool = ApplyPatchTool(files)
        val escape = "--- a/../escape.txt\n+++ b/../escape.txt\n@@ -1,1 +1,1 @@\n-a\n+b\n"
        assertFalse(tool.execute(args("patch" to escape)).ok)
        assertFalse(File(tempDir.parentFile, "escape.txt").exists())
        val absolute = "--- a//etc/passwd\n+++ b//etc/passwd\n@@ -1,1 +1,1 @@\n-a\n+b\n"
        assertFalse(tool.execute(args("patch" to absolute)).ok)
        val deletion = "--- a/app.txt\n+++ /dev/null\n@@ -1,1 +0,0 @@\n-a\n"
        files.writeFile("app.txt", "a\n")
        assertFalse(tool.execute(args("patch" to deletion)).ok)
        assertEquals("a\n", File(tempDir, "app.txt").readText())
    }

    @Test
    fun applyPatch_secondHunkMismatchLeavesFirstHunkUnapplied() = runBlocking {
        // The first hunk matches but the second does not: nothing may change.
        files.writeFile("app.txt", "one\ntwo\nthree\nfour\nfive\nSIX-ALREADY\nseven\n")
        val before = File(tempDir, "app.txt").readText()
        val result = ApplyPatchTool(files).execute(args("patch" to twoHunkPatch))
        assertFalse(result.ok)
        assertEquals(before, File(tempDir, "app.txt").readText())
    }

    @Test
    fun unifiedDiffApplier_parseAndApplyDirectly() {
        val parsed = UnifiedDiffApplier.parse(twoHunkPatch, null)
        assertEquals(1, parsed.size)
        assertEquals(2, parsed.first().hunks.size)
        val original = listOf("one", "two", "three", "four", "five", "six", "seven")
        val applied = UnifiedDiffApplier.applyHunks("app.txt", original, parsed.first().hunks)
        assertEquals(listOf("one", "TWO", "three", "four", "five", "SIX", "seven"), applied)
    }

    // --- glob ---

    @Test
    fun glob_matchesStarStarAndStar() = runBlocking {
        files.writeFile("src/main/App.kt", "x")
        files.writeFile("src/main/util/Help.kt", "x")
        files.writeFile("src/test/AppTest.kt", "x")
        files.writeFile("README.md", "x")
        val tool = GlobTool(files)
        assertEquals("glob", tool.name)
        assertFalse(tool.sensitive)
        val allKt = tool.execute(args("pattern" to "**/*.kt"))
        assertTrue(allKt.ok)
        assertTrue(allKt.output.contains("src/main/App.kt"))
        assertTrue(allKt.output.contains("src/main/util/Help.kt"))
        assertTrue(allKt.output.contains("src/test/AppTest.kt"))
        assertFalse(allKt.output.contains("README.md"))
        val topOnly = tool.execute(args("pattern" to "*.md"))
        assertTrue(topOnly.output.contains("README.md"))
        val scoped = tool.execute(args("pattern" to "**/*.kt", "directory" to "src/main"))
        assertTrue(scoped.output.contains("src/main/App.kt"))
        assertTrue(scoped.output.contains("src/main/util/Help.kt"))
        assertFalse(scoped.output.contains("src/test/AppTest.kt"))
        val none = tool.execute(args("pattern" to "**/*.rs"))
        assertTrue(none.ok)
        assertTrue(none.output.contains("No files match"))
    }

    @Test
    fun glob_supportsQuestionMarkAndCharacterClasses() {
        assertTrue(GlobMatcher.matches("src/?.kt", "src/a.kt"))
        assertFalse(GlobMatcher.matches("src/?.kt", "src/ab.kt"))
        assertTrue(GlobMatcher.matches("src/[a-c].kt", "src/b.kt"))
        assertFalse(GlobMatcher.matches("src/[a-c].kt", "src/d.kt"))
        assertTrue(GlobMatcher.matches("src/[!a-c].kt", "src/d.kt"))
        assertFalse(GlobMatcher.matches("src/[!a-c].kt", "src/b.kt"))
        assertFalse(GlobMatcher.matches("*.kt", "src/a.kt")) // * does not cross /
        assertTrue(GlobMatcher.matches("src/*.kt", "src/a.kt"))
    }

    @Test
    fun glob_rejectsEscapedDirectory() = runBlocking {
        val result = GlobTool(files).execute(args("pattern" to "**/*.kt", "directory" to "../.."))
        assertFalse(result.ok)
    }

    @Test
    fun glob_capsResultsAt200() = runBlocking {
        for (n in 0 until 250) files.writeFile("many/f%03d.txt".format(n), "x")
        val result = GlobTool(files).execute(args("pattern" to "many/*.txt"))
        assertTrue(result.ok)
        val lines = result.output.lines()
        assertEquals(201, lines.size) // 200 files + truncation note
        assertTrue(lines.last().contains("50 more matches"))
    }

    // --- grep ---

    @Test
    fun grep_literalCaseSensitiveAndInsensitive() = runBlocking {
        files.writeFile("a.txt", "Needle here\nnothing\nNEEDLE again\n")
        files.writeFile("b.txt", "needle lower\n")
        val tool = GrepTool(files)
        assertEquals("grep", tool.name)
        assertFalse(tool.sensitive)
        val sensitive = tool.execute(args("pattern" to "Needle"))
        assertTrue(sensitive.output.contains("a.txt:1: Needle here"))
        assertFalse(sensitive.output.contains("NEEDLE again"))
        assertFalse(sensitive.output.contains("b.txt"))
        val insensitive = tool.execute(args("pattern" to "needle", "case_sensitive" to false))
        assertTrue(insensitive.output.contains("a.txt:1:"))
        assertTrue(insensitive.output.contains("a.txt:3:"))
        assertTrue(insensitive.output.contains("b.txt:1:"))
        // Literal mode: regex metacharacters are not special.
        files.writeFile("c.txt", "a.b literal\naxb other\n")
        val literal = tool.execute(args("pattern" to "a.b"))
        assertTrue(literal.output.contains("c.txt:1:"))
        assertFalse(literal.output.contains("c.txt:2:"))
    }

    @Test
    fun grep_regexModeAndInvalidRegex() = runBlocking {
        files.writeFile("a.txt", "error 404 here\nerror 500 there\nplain\n")
        val tool = GrepTool(files)
        val regex = tool.execute(args("pattern" to "error [0-9]+", "regex" to true))
        assertTrue(regex.ok)
        assertTrue(regex.output.contains("a.txt:1:"))
        assertTrue(regex.output.contains("a.txt:2:"))
        val bad = tool.execute(args("pattern" to "error (", "regex" to true))
        assertFalse(bad.ok)
        assertTrue(bad.error!!.contains("Invalid regex"))
    }

    @Test
    fun grep_scopedToFileOrDirectoryAndCaps() = runBlocking {
        files.writeFile("src/a.txt", "hit\n")
        files.writeFile("src/b.txt", "hit\n")
        files.writeFile("other/c.txt", "hit\n")
        val tool = GrepTool(files)
        val dir = tool.execute(args("pattern" to "hit", "path" to "src"))
        assertTrue(dir.output.contains("src/a.txt"))
        assertFalse(dir.output.contains("other/c.txt"))
        val oneFile = tool.execute(args("pattern" to "hit", "path" to "src/a.txt"))
        assertTrue(oneFile.output.contains("src/a.txt"))
        assertFalse(oneFile.output.contains("src/b.txt"))
        val missing = tool.execute(args("pattern" to "hit", "path" to "nope"))
        assertFalse(missing.ok)
        val capped = tool.execute(args("pattern" to "hit", "max_matches" to 2))
        assertTrue(capped.ok)
        assertTrue(capped.output.contains("more matches"))
        assertEquals("No matches for 'zzz'", tool.execute(args("pattern" to "zzz")).output)
    }

    @Test
    fun grep_rejectsPathEscape() = runBlocking {
        val result = GrepTool(files).execute(args("pattern" to "x", "path" to "../../etc"))
        assertFalse(result.ok)
    }

    // --- search ---

    @Test
    fun search_combinesFilenameAndContentHits() = runBlocking {
        files.writeFile("notes/todo.md", "buy milk\n")
        files.writeFile("notes/other.txt", "remember the milk run\n")
        files.writeFile("src/Main.kt", "no hit\n")
        val tool = SearchTool(files)
        assertEquals("search", tool.name)
        assertFalse(tool.sensitive)
        val result = tool.execute(args("query" to "milk"))
        assertTrue(result.ok)
        assertTrue(result.output.contains("Files:"))
        assertTrue(result.output.contains("Content:"))
        // Content hits in both files; filename hit only where the name matches.
        assertTrue(result.output.contains("notes/todo.md:1: buy milk"))
        assertTrue(result.output.contains("notes/other.txt:1: remember the milk run"))
        val byName = tool.execute(args("query" to "todo"))
        assertTrue(byName.output.contains("notes/todo.md"))
        assertTrue(tool.execute(args("query" to "qqq-no-such-thing")).output.contains("No matches"))
    }

    // --- file-diff ---

    @Test
    fun fileDiff_showsDiffForOneFileAndAllFiles() = runBlocking {
        File(tempDir, "orig.txt").writeText("keep\nold line\n")
        files.writeFile("orig.txt", "keep\nnew line\n")
        files.writeFile("brand.txt", "fresh\n")
        val tool = FileDiffTool(files)
        assertEquals("file-diff", tool.name)
        assertFalse(tool.sensitive)
        val one = tool.execute(args("path" to "orig.txt"))
        assertTrue(one.ok)
        assertTrue(one.output.contains("-old line"))
        assertTrue(one.output.contains("+new line"))
        assertFalse(one.output.contains("brand.txt"))
        val all = tool.execute(buildJsonObject {})
        assertTrue(all.ok)
        assertTrue(all.output.contains("orig.txt"))
        assertTrue(all.output.contains("brand.txt"))
        assertTrue(all.output.contains("+fresh"))
    }

    @Test
    fun fileDiff_noChangesAndMissingFile() = runBlocking {
        val tool = FileDiffTool(files)
        assertEquals("No changes.", tool.execute(buildJsonObject {}).output)
        // Pre-existing file rewritten with identical content: no change entry,
        // but the file exists, so the tool reports "no changes" rather than an error.
        File(tempDir, "same.txt").writeText("x\n")
        files.writeFile("same.txt", "x\n")
        assertEquals("No changes for same.txt.", tool.execute(args("path" to "same.txt")).output)
        assertFalse(tool.execute(args("path" to "ghost.txt")).ok)
    }

    @Test
    fun fileDiff_truncatesLongOutput() = runBlocking {
        val big = (0 until 1000).joinToString("\n") { "line number $it with some padding text" }
        files.writeFile("big.txt", big)
        val result = FileDiffTool(files).execute(buildJsonObject {})
        assertTrue(result.ok)
        assertTrue(result.output.length <= FileDiffTool.MAX_OUTPUT_CHARS + 100)
        assertTrue(result.output.contains("truncated"))
    }
}
