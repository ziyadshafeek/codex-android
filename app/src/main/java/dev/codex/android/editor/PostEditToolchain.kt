package dev.codex.android.editor

import dev.codex.android.linux.ProotShellEngine
import java.io.File

/** Severity for one parsed LSP or linter diagnostic. */
enum class DiagnosticSeverity { ERROR, WARNING, INFO }

/** One structured diagnostic returned by local LSP/typecheckers. */
data class DiagnosticItem(
    val filePath: String,
    val line: Int,
    val column: Int,
    val severity: DiagnosticSeverity,
    val message: String,
    val source: String,
)

/** Combined post-edit report appended to tool results. */
data class PostEditReport(
    val formatterNote: String,
    val diagnostics: List<DiagnosticItem>,
    val lspNote: String,
) {
    /** Formats a compact summary suitable for appending to an edit/write tool result. */
    fun toToolResultSuffix(): String = buildString {
        appendLine()
        appendLine("[Post-Edit Quality Loop]")
        appendLine("Formatter: $formatterNote")
        if (diagnostics.isNotEmpty()) {
            appendLine("LSP Diagnostics (${diagnostics.size}):")
            diagnostics.take(20).forEach { d ->
                appendLine("  [${d.severity}] ${d.filePath}:${d.line}:${d.column} (${d.source}): ${d.message}")
            }
            if (diagnostics.size > 20) {
                appendLine("  ... (${diagnostics.size - 20} more diagnostics)")
            }
        } else {
            appendLine("LSP: $lspNote")
        }
    }.trimEnd()
}

/**
 * Pure, JVM-testable post-edit formatter & LSP diagnostic runner/parser (Milestone M5).
 *
 * All formatter and LSP calls are strictly best-effort:
 * - Missing binary (exit 127 or `which` absent) -> skip with a one-line note, NEVER fail the edit.
 * - Supports `prettier`, `ruff`, `gofmt`, `ktfmt` and diagnostics from
 *   `tsserver`/`tsc`, `pyright`, `gopls`, `rust-analyzer`.
 * - Unverified against a real rootfs (see NOTES.md): `ktfmt <file>` in-place
 *   behaviour, `gopls check <file>`, and `rust-analyzer diagnostics .` — a
 *   wrong invocation degrades to a one-line note, never a failed edit.
 *
 * CALL SITE (integrator — do NOT call from here): `runPostEdit` is invoked by
 * the integrator after every `write_file`/`apply_patch` in `WorkspaceFileTools`
 * (`tools/WorkspaceFileTools.kt`, which this file deliberately does not modify).
 */
object PostEditToolchain {

    /** Builds the composer `@file:L10-L30` (or `@file:L10`) pin token on long-press. */
    fun buildLinePin(relativePath: String, startLine: Int, endLine: Int = startLine): String {
        val clean = relativePath.trim().removePrefix("./").removePrefix("/")
        val s = startLine.coerceAtLeast(1)
        val e = endLine.coerceAtLeast(s)
        return if (s == e) "@$clean:L$s" else "@$clean:L$s-L$e"
    }

    /** Selects the best local formatter `(binaryName, command)` for [filePath], or null. */
    fun formatterCommandFor(filePath: String): Pair<String, String>? {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        val quoted = shellQuote(filePath)
        return when (ext) {
            "js", "jsx", "ts", "tsx", "json", "css", "html", "md" ->
                "prettier" to "prettier --write $quoted"
            "py" ->
                "ruff" to "ruff format $quoted"
            "go" ->
                "gofmt" to "gofmt -w $quoted"
            "kt", "kts" ->
                "ktfmt" to "ktfmt $quoted"
            "rs" ->
                "rustfmt" to "rustfmt $quoted"
            else -> null
        }
    }

    /** Selects the best local LSP/typecheck diagnostic command `(binaryName, command)` for [filePath]. */
    fun lspDiagnosticCommandFor(filePath: String): Pair<String, String>? {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        val quoted = shellQuote(filePath)
        return when (ext) {
            // Gate binary is `npx` (not `tsserver`): the executed command is
            // `npx tsc`, and `command -v tsserver` would wrongly skip a
            // working npx-provided tsc. The diagnostic source label in
            // reports therefore reads "npx" for TypeScript.
            "ts", "tsx", "js", "jsx" ->
                "npx" to "npx --no-install tsc --noEmit --pretty false $quoted"
            // Plain text output (no --outputjson): parseDiagnostics() only
            // understands pyright's text format
            // (`path:line:col - error: message`), not its JSON output.
            "py" ->
                "pyright" to "pyright $quoted"
            "go" ->
                "gopls" to "gopls check $quoted"
            "rs" ->
                "rust-analyzer" to "rust-analyzer diagnostics ."
            else -> null
        }
    }

    /**
     * Runs formatter + LSP diagnostics via [proot] on a best-effort basis.
     * Never throws and never fails the underlying edit.
     */
    suspend fun runPostEdit(
        proot: ProotShellEngine?,
        file: File,
        guestPath: String = "/workspace/${file.name}",
    ): PostEditReport {
        if (proot == null) {
            return PostEditReport(
                formatterNote = "skipped (Linux runtime not active)",
                diagnostics = emptyList(),
                lspNote = "skipped (Linux runtime not active)",
            )
        }

        val formatterNote = runCatching {
            val spec = formatterCommandFor(guestPath)
                ?: return@runCatching "skipped (no formatter configured for .${file.extension})"
            val (bin, cmd) = spec
            // if/then/else (NOT `... && $cmd || exit 127`): a real formatter
            // failure (exit != 0) must surface with its message, not be
            // misreported as "binary not installed". Only a missing binary
            // yields 127.
            val res = proot.shellExec(
                "if command -v $bin >/dev/null 2>&1; then $cmd; else exit 127; fi",
                timeoutMs = 15_000L,
            )
            when (res.exitCode) {
                0 -> "formatted with $bin"
                127 -> "skipped ($bin not installed in rootfs)"
                else -> "$bin exited ${res.exitCode}: ${(res.stderr.ifBlank { res.stdout }).lines().firstOrNull().orEmpty().take(120)}"
            }
        }.getOrElse { "skipped (${it.message})" }

        var diagnostics: List<DiagnosticItem> = emptyList()
        val lspNote = runCatching {
            val spec = lspDiagnosticCommandFor(guestPath)
                ?: return@runCatching "skipped (no LSP configured for .${file.extension})"
            val (bin, cmd) = spec
            // Same if/then/else shape as the formatter branch: a failing
            // typecheck must not be misreported as a missing binary.
            val res = proot.shellExec(
                "if command -v $bin >/dev/null 2>&1; then $cmd; else exit 127; fi",
                timeoutMs = 20_000L,
            )
            if (res.exitCode == 127) {
                "skipped ($bin not installed in rootfs)"
            } else {
                val combined = (res.stdout + "\n" + res.stderr).trim()
                diagnostics = parseDiagnostics(bin, combined)
                if (diagnostics.isEmpty()) "$bin: 0 diagnostics" else "$bin: ${diagnostics.size} diagnostic(s)"
            }
        }.getOrElse { "skipped (${it.message})" }

        return PostEditReport(
            formatterNote = formatterNote,
            diagnostics = diagnostics,
            lspNote = lspNote,
        )
    }

    /**
     * Pure parser normalizing diagnostic output from `gopls`, `tsc`/`tsserver`,
     * `pyright`, `rust-analyzer`, and `ruff`/`ktfmt` into [DiagnosticItem]s.
     */
    fun parseDiagnostics(tool: String, rawOutput: String): List<DiagnosticItem> {
        if (rawOutput.isBlank()) return emptyList()
        val items = mutableListOf<DiagnosticItem>()

        // TypeScript: path(line,col): error TS2322: message
        val tscRegex = Regex("""^(.+?)\((\d+),(\d+)\):\s+(error|warning)\s+([A-Z0-9]+):\s+(.+)$""")
        // gopls / rust-analyzer / standard colon format: path:line:col: message
        val colonRegex = Regex("""^([^:\n]+):(\d+):(\d+):\s*(?:(error|warning|note|info):?\s*)?(.+)$""", RegexOption.IGNORE_CASE)
        // pyright text fallback: path:line:col - error: message
        val pyrightTextRegex = Regex("""^\s*([^:\n]+):(\d+):(\d+)\s*-\s*(error|warning|information):\s*(.+)$""", RegexOption.IGNORE_CASE)

        for (rawLine in rawOutput.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            // `continue` directly in the loop body: `continue` inside an
            // inline `let` lambda needs the experimental "break continue in
            // inline lambdas" feature, which this project's Kotlin 1.9.25
            // setup rejects (CI run 44629934149).
            val tscMatch = tscRegex.matchEntire(line)
            if (tscMatch != null) {
                val m = tscMatch
                items += DiagnosticItem(
                    filePath = m.groupValues[1],
                    line = m.groupValues[2].toIntOrNull() ?: 1,
                    column = m.groupValues[3].toIntOrNull() ?: 1,
                    severity = parseSeverity(m.groupValues[4]),
                    message = "${m.groupValues[5]}: ${m.groupValues[6]}",
                    source = tool,
                )
                continue
            }

            val pyrightMatch = pyrightTextRegex.matchEntire(line)
            if (pyrightMatch != null) {
                val m = pyrightMatch
                items += DiagnosticItem(
                    filePath = m.groupValues[1],
                    line = m.groupValues[2].toIntOrNull() ?: 1,
                    column = m.groupValues[3].toIntOrNull() ?: 1,
                    severity = parseSeverity(m.groupValues[4]),
                    message = m.groupValues[5],
                    source = tool,
                )
                continue
            }

            colonRegex.matchEntire(line)?.let { m ->
                val sev = m.groupValues[4].ifBlank { "error" }
                items += DiagnosticItem(
                    filePath = m.groupValues[1],
                    line = m.groupValues[2].toIntOrNull() ?: 1,
                    column = m.groupValues[3].toIntOrNull() ?: 1,
                    severity = parseSeverity(sev),
                    message = m.groupValues[5].trim(),
                    source = tool,
                )
            }
        }
        return items
    }

    private fun parseSeverity(raw: String): DiagnosticSeverity = when (raw.lowercase()) {
        "warning", "warn" -> DiagnosticSeverity.WARNING
        "info", "information", "note" -> DiagnosticSeverity.INFO
        else -> DiagnosticSeverity.ERROR
    }

    private fun shellQuote(arg: String): String =
        "'" + arg.replace("'", "'\"'\"'") + "'"
}
