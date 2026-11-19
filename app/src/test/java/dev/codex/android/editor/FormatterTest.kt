package dev.codex.android.editor

import dev.codex.android.linux.ProotShellEngine
import dev.codex.android.linux.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** JVM unit tests for [PostEditToolchain] formatting, pin generation, and LSP output parsing. */
class FormatterTest {

    @Test
    fun buildLinePin_formatsSingleAndRangePins() {
        assertEquals("@src/Main.kt:L10-L30", PostEditToolchain.buildLinePin("src/Main.kt", 10, 30))
        assertEquals("@src/Main.kt:L10", PostEditToolchain.buildLinePin("./src/Main.kt", 10, 10))
    }

    @Test
    fun parseDiagnostics_parsesTypeScriptPyrightAndGoplsOutputs() {
        val tscOut = "src/index.ts(12,5): error TS2322: Type 'number' is not assignable to type 'string'."
        val tsc = PostEditToolchain.parseDiagnostics("tsserver", tscOut)
        assertEquals(1, tsc.size)
        assertEquals("src/index.ts", tsc[0].filePath)
        assertEquals(12, tsc[0].line)
        assertEquals(5, tsc[0].column)
        assertEquals(DiagnosticSeverity.ERROR, tsc[0].severity)
        assertTrue(tsc[0].message.contains("TS2322"))

        val goplsOut = "cmd/main.go:42:9: warning: unused variable x"
        val gopls = PostEditToolchain.parseDiagnostics("gopls", goplsOut)
        assertEquals(1, gopls.size)
        assertEquals("cmd/main.go", gopls[0].filePath)
        assertEquals(42, gopls[0].line)
        assertEquals(DiagnosticSeverity.WARNING, gopls[0].severity)

        val pyrightOut = "app/service.py:7:14 - error: \"foo\" is not defined"
        val py = PostEditToolchain.parseDiagnostics("pyright", pyrightOut)
        assertEquals(1, py.size)
        assertEquals(7, py[0].line)
        assertEquals(14, py[0].column)
        assertEquals(DiagnosticSeverity.ERROR, py[0].severity)
    }

    @Test
    fun runPostEdit_missingBinarySkipsGracefullyNeverFails() = runBlocking {
        val tempDir = Files.createTempDirectory("codex-fmt-test").toFile()
        try {
            val file = File(tempDir, "Main.kt").apply { writeText("fun main() {}\n") }
            val fakeProot = object : ProotShellEngine(tempDir, tempDir) {
                override suspend fun shellExec(command: String, timeoutMs: Long): ShellResult =
                    ShellResult(exitCode = 127, stdout = "", stderr = "not found")
            }
            val report = PostEditToolchain.runPostEdit(fakeProot, file, "/workspace/Main.kt")
            assertTrue(report.formatterNote.contains("skipped"))
            assertTrue(report.toToolResultSuffix().contains("Post-Edit Quality Loop"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
