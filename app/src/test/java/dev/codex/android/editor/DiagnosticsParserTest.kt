package dev.codex.android.editor

import dev.codex.android.linux.ProotShellEngine
import dev.codex.android.linux.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM unit tests for [PostEditToolchain.parseDiagnostics] and the
 * post-edit report contract: after an edit, diagnostics must name
 * `file:line:message` so the agent can fix them (a loop, not a silent pass).
 *
 * Format coverage, verified against the tools' documented text output:
 * - `tsc --pretty false`: `path(line,col): error TSxxxx: message`
 * - `pyright` text: `path:line:col - error|warning|information: message`
 * - `gopls check`: `path:line:col: [severity:] message`
 * - `rust-analyzer diagnostics`: NOT verified against a real binary — the
 *   parser only understands the `path:line:col: severity: message` shape.
 *   If rust-analyzer emits multi-line rustc-style output (`--> file:line:col`
 *   header plus `|` context lines), those diagnostics are silently dropped
 *   (see [rustcStyleMultilineOutput_isNotParsed]); that is a known gap.
 */
class DiagnosticsParserTest {

    @Test
    fun parseDiagnostics_tscErrorAndWarning() {
        val out = """
            src/index.ts(12,5): error TS2322: Type 'number' is not assignable to type 'string'.
            src/util.ts(3,1): warning TS6133: 'helper' is declared but its value is never read.
            Found 2 errors in 2 files.
        """.trimIndent()
        val items = PostEditToolchain.parseDiagnostics("npx", out)
        assertEquals(2, items.size)
        assertEquals("src/index.ts", items[0].filePath)
        assertEquals(12, items[0].line)
        assertEquals(5, items[0].column)
        assertEquals(DiagnosticSeverity.ERROR, items[0].severity)
        assertTrue(items[0].message.contains("TS2322"))
        assertEquals(DiagnosticSeverity.WARNING, items[1].severity)
        assertEquals("npx", items[1].source)
    }

    @Test
    fun parseDiagnostics_tscLocationlessErrorIsDropped() {
        // tsc prints project-level errors with no file:line at all.
        val out = "error TS2688: Cannot find type definition file for 'node'."
        assertTrue(PostEditToolchain.parseDiagnostics("npx", out).isEmpty())
    }

    @Test
    fun parseDiagnostics_pyrightSeverities() {
        val out = """
            app/service.py:7:14 - error: "foo" is not defined
            app/service.py:9:1 - warning: "bar" is not accessed
            app/service.py:11:5 - information: Type of "x" is "int"
            1 error, 1 warning, 1 information
        """.trimIndent()
        val items = PostEditToolchain.parseDiagnostics("pyright", out)
        assertEquals(3, items.size)
        assertEquals(DiagnosticSeverity.ERROR, items[0].severity)
        assertEquals(DiagnosticSeverity.WARNING, items[1].severity)
        assertEquals(DiagnosticSeverity.INFO, items[2].severity)
        assertEquals(7, items[0].line)
        assertEquals(14, items[0].column)
        assertEquals("\"foo\" is not defined", items[0].message)
    }

    @Test
    fun parseDiagnostics_goplsColonFormat() {
        val out = """
            cmd/main.go:42:9: undefined: x
            cmd/main.go:10:2: warning: unused variable y
        """.trimIndent()
        val items = PostEditToolchain.parseDiagnostics("gopls", out)
        assertEquals(2, items.size)
        assertEquals("cmd/main.go", items[0].filePath)
        assertEquals(42, items[0].line)
        assertEquals(9, items[0].column)
        // No severity token defaults to ERROR.
        assertEquals(DiagnosticSeverity.ERROR, items[0].severity)
        assertEquals("undefined: x", items[0].message)
        assertEquals(DiagnosticSeverity.WARNING, items[1].severity)
    }

    @Test
    fun parseDiagnostics_rustAnalyzerColonFormat() {
        // Assumed shape only — see the class KDoc: the real
        // `rust-analyzer diagnostics` output is unverified.
        val out = "src/main.rs:5:10: error: mismatched types"
        val items = PostEditToolchain.parseDiagnostics("rust-analyzer", out)
        assertEquals(1, items.size)
        assertEquals("src/main.rs", items[0].filePath)
        assertEquals(5, items[0].line)
        assertEquals(10, items[0].column)
        assertEquals(DiagnosticSeverity.ERROR, items[0].severity)
    }

    @Test
    fun rustcStyleMultilineOutput_isNotParsed() {
        // rustc-style output: the `-->` header carries the location but
        // matches no parser regex, and the `|` context lines carry none.
        // Documents the gap: such diagnostics are silently dropped.
        val out = """
            error[E0308]: mismatched types
              --> src/main.rs:5:10
               |
            5  |     let x: i32 = "s";
               |                  ^^^ expected `i32`, found `&str`
        """.trimIndent()
        assertTrue(PostEditToolchain.parseDiagnostics("rust-analyzer", out).isEmpty())
    }

    @Test
    fun lspDiagnosticCommandFor_selectsPerExtension() {
        assertEquals("npx", PostEditToolchain.lspDiagnosticCommandFor("/workspace/a.ts")?.first)
        assertTrue(PostEditToolchain.lspDiagnosticCommandFor("/workspace/a.ts")!!.second.contains("tsc --noEmit"))
        assertEquals("pyright", PostEditToolchain.lspDiagnosticCommandFor("/workspace/a.py")?.first)
        assertEquals("gopls", PostEditToolchain.lspDiagnosticCommandFor("/workspace/a.go")?.first)
        assertEquals("rust-analyzer", PostEditToolchain.lspDiagnosticCommandFor("/workspace/a.rs")?.first)
        assertNull(PostEditToolchain.lspDiagnosticCommandFor("/workspace/a.txt"))
        assertNull(PostEditToolchain.lspDiagnosticCommandFor("/workspace/a.kt"))
    }

    @Test
    fun reportSuffix_namesFileLineAndMessageForErrors() {
        val report = PostEditReport(
            formatterNote = "formatted with prettier",
            diagnostics = listOf(
                DiagnosticItem("src/index.ts", 12, 5, DiagnosticSeverity.ERROR, "TS2322: bad type", "npx"),
            ),
            lspNote = "npx: 1 diagnostic(s)",
        )
        val suffix = report.toToolResultSuffix()
        assertTrue(suffix.contains("[Post-Edit Quality Loop]"))
        assertTrue(suffix.contains("[ERROR] src/index.ts:12:5 (npx): TS2322: bad type"))
    }

    @Test
    fun reportSuffix_capsListedDiagnosticsAtTwenty() {
        val diagnostics = (1..25).map {
            DiagnosticItem("f.ts", it, 1, DiagnosticSeverity.ERROR, "err $it", "npx")
        }
        val suffix = PostEditReport("ok", diagnostics, "npx: 25 diagnostic(s)").toToolResultSuffix()
        assertTrue(suffix.contains("LSP Diagnostics (25):"))
        assertTrue(suffix.contains("... (5 more diagnostics)"))
        assertFalse(suffix.contains("err 25"))
    }

    @Test
    fun runPostEdit_tscErrorsSurfaceInReport() = runBlocking {
        val tempDir = Files.createTempDirectory("codex-diag-test").toFile()
        try {
            val file = File(tempDir, "index.ts").apply { writeText("const x: string = 1;\n") }
            val tscOut = "src/index.ts(1,7): error TS2322: Type 'number' is not assignable to type 'string'."
            val fakeProot = object : ProotShellEngine(tempDir, tempDir) {
                override suspend fun shellExec(command: String, timeoutMs: Long): ShellResult =
                    if ("tsc" in command) ShellResult(exitCode = 2, stdout = tscOut, stderr = "")
                    else ShellResult(exitCode = 0, stdout = "", stderr = "")
            }
            val report = PostEditToolchain.runPostEdit(fakeProot, file, "/workspace/index.ts")
            assertEquals(1, report.diagnostics.size)
            assertEquals("src/index.ts", report.diagnostics[0].filePath)
            assertEquals(1, report.diagnostics[0].line)
            assertTrue(report.toToolResultSuffix().contains("[ERROR] src/index.ts:1:7 (npx): TS2322:"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
