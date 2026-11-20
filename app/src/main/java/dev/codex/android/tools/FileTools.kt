package dev.codex.android.tools

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---------------------------------------------------------------------------
// Shared argument helpers
// ---------------------------------------------------------------------------

private fun JsonObject.stringArg(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.requiredArg(name: String): String? =
    stringArg(name)?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.boolArg(name: String, default: Boolean): Boolean =
    this[name]?.jsonPrimitive?.booleanOrNull ?: default

private fun JsonObject.intArg(name: String, default: Int): Int =
    this[name]?.jsonPrimitive?.intOrNull ?: default

private fun fail(message: String): ToolResult = ToolResult(ok = false, output = "", error = message)

private fun schema(
    vararg properties: Triple<String, String, String>,
    required: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        for ((name, type, description) in properties) {
            putJsonObject(name) {
                put("type", type)
                put("description", description)
            }
        }
    }
    putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
}

/** One content hit: `path:line: text`. */
internal data class ContentHit(val path: String, val line: Int, val text: String)

/**
 * Searches workspace files for lines matching [predicate].
 *
 * [scope] is blank for the whole workspace, a relative file path for one
 * file, or a relative directory for the subtree under it. Returns the hits
 * found (at most [maxMatches]) plus the total hit count, so callers can
 * report truncation. Files larger than 512 KB are skipped, matching the
 * [WorkspaceFileTools.searchText] precedent. Throws [IllegalArgumentException]
 * for a missing scope path and [SecurityException] for a path escape.
 */
internal fun searchContent(
    files: WorkspaceFileTools,
    scope: String,
    predicate: (String) -> Boolean,
    maxMatches: Int,
): Pair<List<ContentHit>, Int> {
    val targets: List<String> = when {
        scope.isBlank() -> files.listFiles(maxResults = 1000)
        else -> {
            val target = files.resolveSafe(scope)
            when {
                target.isFile -> listOf(
                    target.canonicalFile.relativeTo(files.rootDir).path.replace(File.separatorChar, '/'),
                )
                target.isDirectory -> files.listFiles(scope, maxResults = 1000)
                else -> throw IllegalArgumentException("Path not found: $scope")
            }
        }
    }
    val hits = mutableListOf<ContentHit>()
    var total = 0
    for (path in targets) {
        val file = runCatching { files.resolveSafe(path) }.getOrNull() ?: continue
        if (!file.isFile || file.length() > 512_000) continue
        runCatching {
            file.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (predicate(line)) {
                        total++
                        if (hits.size < maxMatches) {
                            hits += ContentHit(path, index + 1, line.trim().take(200))
                        }
                    }
                }
            }
        }
    }
    return hits to total
}

// ---------------------------------------------------------------------------
// Glob matching (pure, no dependency)
// ---------------------------------------------------------------------------

/**
 * Glob matcher supporting `*` (any run inside one path segment),
 * `?` (one character inside one segment), `**` (any run across segments,
 * including zero segments when written as a double star followed by `/`),
 * and character classes (`[abc]`, `[a-z]`, `[!abc]`).
 */
object GlobMatcher {

    /** True when workspace-relative [path] matches [pattern]. */
    fun matches(pattern: String, path: String): Boolean {
        val cleanPattern = pattern.trim().removePrefix("./").removePrefix("/")
        val cleanPath = path.trim().removePrefix("./").removePrefix("/")
        return Regex(globToRegex(cleanPattern)).matches(cleanPath)
    }

    internal fun globToRegex(pattern: String): String {
        val out = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            when {
                pattern.startsWith("**/", i) -> {
                    out.append("(?:.*/)?")
                    i += 3
                }
                pattern.startsWith("/**", i) && i + 3 == pattern.length -> {
                    out.append("(?:/.*)?")
                    i += 3
                }
                pattern.startsWith("**", i) -> {
                    out.append(".*")
                    i += 2
                }
                pattern[i] == '*' -> {
                    out.append("[^/]*")
                    i++
                }
                pattern[i] == '?' -> {
                    out.append("[^/]")
                    i++
                }
                pattern[i] == '[' -> {
                    val close = findClassClose(pattern, i)
                    if (close < 0) {
                        out.append("\\[")
                        i++
                    } else {
                        out.append(pattern.substring(i, close + 1).let { raw ->
                            // `[!...]` / `[^...]` negate; a leading `]` is a literal.
                            val body = raw.removePrefix("[").removeSuffix("]")
                            val negated = body.startsWith("!") || body.startsWith("^")
                            val inner = if (negated) body.substring(1) else body
                            val escaped = inner.replace("\\", "\\\\").replace("]", "\\]")
                            "[" + (if (negated) "^" else "") + escaped + "]"
                        })
                        i = close + 1
                    }
                }
                pattern[i] in ".+()|^$\\" -> {
                    out.append('\\').append(pattern[i])
                    i++
                }
                else -> {
                    out.append(pattern[i])
                    i++
                }
            }
        }
        out.append('$')
        return out.toString()
    }

    /** Index of the `]` closing the class opened at [open], or -1. */
    private fun findClassClose(pattern: String, open: Int): Int {
        var j = open + 1
        if (j < pattern.length && (pattern[j] == '!' || pattern[j] == '^')) j++
        if (j < pattern.length && pattern[j] == ']') j++ // leading ] is a literal
        while (j < pattern.length) {
            if (pattern[j] == ']') return j
            j++
        }
        return -1
    }
}

// ---------------------------------------------------------------------------
// Unified-diff parsing + application (pure except for the final writes)
// ---------------------------------------------------------------------------

internal data class DiffLine(val kind: Char, val text: String)

internal data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>,
)

internal data class ParsedPatchFile(
    val oldPath: String?,
    val newPath: String?,
    val hunks: List<DiffHunk>,
)

/**
 * Strict unified-diff applier.
 *
 * Parses `---`/`+++` headers and `@@` hunks, verifies every hunk's context
 * and removed lines against the file's current content, and only then writes
 * — a single mismatching or malformed hunk fails the whole patch with
 * nothing written. New files (`--- /dev/null`) are supported; deletions
 * (`+++ /dev/null`) are rejected cleanly. No partial application, within one
 * file or across files.
 */
object UnifiedDiffApplier {

    const val MAX_PATCH_CHARS: Int = 500_000

    private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")
    private val META_PREFIXES = listOf(
        "diff --git ", "index ", "new file mode ", "deleted file mode ",
        "old mode ", "new mode ", "similarity index ", "rename from ", "rename to ",
        "copy from ", "copy to ",
    )

    internal fun parse(patchText: String, defaultPath: String?): List<ParsedPatchFile> {
        if (patchText.isBlank()) throw IllegalArgumentException("Empty patch")
        if (patchText.length > MAX_PATCH_CHARS) {
            throw IllegalArgumentException("Patch exceeds ${MAX_PATCH_CHARS} characters")
        }
        val lines = patchText.split('\n').map { it.removeSuffix("\r") }
        var i = 0
        val files = mutableListOf<ParsedPatchFile>()
        // A headerless patch is allowed only with an explicit default path:
        // the whole text must then be hunks for that one file.
        if (defaultPath != null && lines.none { it.startsWith("--- ") }) {
            return listOf(ParsedPatchFile(null, defaultPath, parseHunks(lines, 0).first))
        }
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.isBlank() || META_PREFIXES.any { line.startsWith(it) } -> i++
                line.startsWith("--- ") -> {
                    val oldPath = stripDiffPrefix(line.removePrefix("--- ").trim())
                    i++
                    if (i >= lines.size || !lines[i].startsWith("+++ ")) {
                        throw IllegalArgumentException("Malformed patch: '--- $oldPath' is not followed by a '+++' header")
                    }
                    val newPath = stripDiffPrefix(lines[i].removePrefix("+++ ").trim())
                    i++
                    val (hunks, next) = parseHunks(lines, i)
                    if (hunks.isEmpty()) {
                        throw IllegalArgumentException("Malformed patch: no hunks for file ${newPath ?: oldPath}")
                    }
                    files += ParsedPatchFile(oldPath, newPath, hunks)
                    i = next
                }
                else -> throw IllegalArgumentException("Malformed patch: unexpected line outside a hunk: '${line.take(80)}'")
            }
        }
        if (files.isEmpty()) throw IllegalArgumentException("Malformed patch: no file headers or hunks found")
        return files
    }

    /** Parses hunks starting at [start]; returns the hunks and the next index. */
    private fun parseHunks(lines: List<String>, start: Int): Pair<List<DiffHunk>, Int> {
        val hunks = mutableListOf<DiffHunk>()
        var i = start
        while (i < lines.size) {
            val header = HUNK_HEADER.find(lines[i]) ?: break
            val oldStart = header.groupValues[1].toInt()
            val oldCount = header.groupValues[2].ifEmpty { "1" }.toInt()
            val newStart = header.groupValues[3].toInt()
            val newCount = header.groupValues[4].ifEmpty { "1" }.toInt()
            i++
            val body = mutableListOf<DiffLine>()
            var seenOld = 0
            var seenNew = 0
            while (i < lines.size && (seenOld < oldCount || seenNew < newCount)) {
                val raw = lines[i]
                when {
                    raw.startsWith("\\ ") || raw == "\\" -> i++ // "\ No newline at end of file": ignore
                    raw.startsWith(" ") -> {
                        body += DiffLine(' ', raw.substring(1)); seenOld++; seenNew++; i++
                    }
                    raw.startsWith("-") -> {
                        body += DiffLine('-', raw.substring(1)); seenOld++; i++
                    }
                    raw.startsWith("+") -> {
                        body += DiffLine('+', raw.substring(1)); seenNew++; i++
                    }
                    raw.isEmpty() && seenOld < oldCount -> {
                        // A blank context line with its leading space stripped
                        // by the caller: treat as an empty context line.
                        body += DiffLine(' ', ""); seenOld++; seenNew++; i++
                    }
                    else -> throw IllegalArgumentException(
                        "Malformed patch: unexpected hunk line '${raw.take(80)}'",
                    )
                }
            }
            if (seenOld != oldCount || seenNew != newCount) {
                throw IllegalArgumentException(
                    "Malformed patch: hunk @@ -$oldStart,$oldCount +$newStart,$newCount @@ " +
                        "has $seenOld old / $seenNew new lines, expected $oldCount / $newCount",
                )
            }
            hunks += DiffHunk(oldStart, oldCount, newStart, newCount, body)
        }
        return hunks to i
    }

    private fun stripDiffPrefix(headerPath: String): String {
        val path = headerPath.trim()
        if (path == "/dev/null") return path
        // Strip a leading a/ or b/ (also tolerate a timestamp suffix).
        val firstToken = path.split('\t').first().trim()
        return firstToken.removePrefix("a/").removePrefix("b/")
    }

    /**
     * Validates every parsed file against current disk content, then writes
     * all of them via [files] (so snapshots + post-edit hooks run). Returns
     * a one-line-per-file summary. Throws [IllegalArgumentException] or
     * [SecurityException] on any failure, before writing anything.
     */
    internal fun apply(files: WorkspaceFileTools, parsed: List<ParsedPatchFile>): String {
        // Phase 1 — validate everything, computing the new text per file.
        val planned = parsed.map { filePatch -> planFile(files, filePatch) }
        // Phase 2 — write everything (only reached when all files validated).
        val summaries = mutableListOf<String>()
        for ((path, newText) in planned) {
            summaries += files.writeFile(path, newText)
        }
        return summaries.joinToString("\n")
    }

    private fun planFile(files: WorkspaceFileTools, patch: ParsedPatchFile): Pair<String, String> {
        val newPath = patch.newPath
            ?: throw IllegalArgumentException("Malformed patch: missing '+++' file header")
        if (newPath == "/dev/null") {
            throw IllegalArgumentException("File deletion via patch is not supported: ${patch.oldPath}")
        }
        val isNewFile = patch.oldPath == "/dev/null"
        val file = files.resolveSafe(newPath)
        val currentText = when {
            isNewFile -> {
                if (file.exists()) {
                    throw IllegalArgumentException("Patch creates $newPath but it already exists")
                }
                ""
            }
            !file.exists() || !file.isFile -> throw IllegalArgumentException("File not found: $newPath")
            else -> file.readText()
        }
        val original = splitLines(currentText)
        val originalEndsWithNewline = currentText.isEmpty() || currentText.endsWith("\n")
        val result = applyHunks(newPath, original, patch.hunks)
        val newText = when {
            result.isEmpty() -> ""
            originalEndsWithNewline -> result.joinToString("\n") + "\n"
            else -> result.joinToString("\n")
        }
        return newPath to newText
    }

    /** Applies [hunks] to [original], verifying context/removals exactly. */
    internal fun applyHunks(path: String, original: List<String>, hunks: List<DiffHunk>): List<String> {
        val out = mutableListOf<String>()
        var pos = 0
        for (hunk in hunks) {
            // oldStart is 1-based; a 0-count hunk anchors *before* oldStart+1.
            val startIdx = if (hunk.oldCount == 0) hunk.oldStart else hunk.oldStart - 1
            if (startIdx < pos || startIdx > original.size) {
                throw IllegalArgumentException(
                    "Hunk @@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@ " +
                        "in $path starts at line ${hunk.oldStart}, outside the file or before a previous hunk",
                )
            }
            while (pos < startIdx) {
                out += original[pos]
                pos++
            }
            for (diffLine in hunk.lines) {
                when (diffLine.kind) {
                    ' ' -> {
                        val actual = original.getOrNull(pos)
                            ?: throw IllegalArgumentException(
                                "Hunk in $path does not match: context line '${diffLine.text}' " +
                                    "expected at line ${pos + 1} but the file ends there",
                            )
                        if (actual != diffLine.text) {
                            throw IllegalArgumentException(
                                "Hunk in $path does not match: expected context '${diffLine.text}' " +
                                    "at line ${pos + 1} but found '$actual'",
                            )
                        }
                        out += actual
                        pos++
                    }
                    '-' -> {
                        val actual = original.getOrNull(pos)
                            ?: throw IllegalArgumentException(
                                "Hunk in $path does not match: line '${diffLine.text}' " +
                                    "expected for removal at line ${pos + 1} but the file ends there",
                            )
                        if (actual != diffLine.text) {
                            throw IllegalArgumentException(
                                "Hunk in $path does not match: expected to remove '${diffLine.text}' " +
                                    "at line ${pos + 1} but found '$actual'",
                            )
                        }
                        pos++
                    }
                    '+' -> out += diffLine.text
                }
            }
        }
        while (pos < original.size) {
            out += original[pos]
            pos++
        }
        return out
    }

    internal fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = text.split('\n').map { it.removeSuffix("\r") }
        return if (text.endsWith("\n") || text.endsWith("\r")) parts.dropLast(1) else parts
    }
}

/**
 * Registers the seven agent-facing file tools, all backed by [files].
 *
 * The coordinator should call this with the SAME [WorkspaceFileTools]
 * instance the rest of the app uses (e.g. `ChatViewModel.workspaceFileTools`):
 * snapshots (and therefore [FileDiffTool] / revert) live per instance, so a
 * second instance over the same directory would see a different change set.
 */
fun ToolRegistry.registerFileTools(files: WorkspaceFileTools) {
    register(EditTool(files))
    register(ApplyPatchTool(files))
    register(GlobTool(files))
    register(GrepTool(files))
    register(SearchTool(files))
    register(FileDiffTool(files))
    register(WriteFileTool(files))
}

// ---------------------------------------------------------------------------
// 1. edit — search-and-replace in a file
// ---------------------------------------------------------------------------

/**
 * Search-and-replace edit (`name = "edit"`).
 *
 * Wraps [WorkspaceFileTools.applyPatch]: the snippet must match exactly once.
 * Not sensitive: edits stay inside the workspace sandbox and the agent's own
 * policy gate handles approval — same treatment as the task brief specifies.
 * (Note for the coordinator: the developer-only `workspace_write` in
 * DeveloperTools.kt is approval-gated (`sensitive = true`); if the policy
 * gate is ever meant to ask before edits, flip this flag.)
 */
class EditTool(private val files: WorkspaceFileTools) : Tool {
    constructor(workspaceRoot: File) : this(WorkspaceFileTools(workspaceRoot))

    override val name: String = "edit"
    override val description: String =
        "Replaces one exact snippet in a workspace file (search-and-replace). " +
            "The old text must match exactly once; use more surrounding context to disambiguate."
    override val parametersSchema: JsonObject = schema(
        Triple("path", "string", "Workspace-relative path of the file to edit"),
        Triple("old_text", "string", "Exact text to replace (must occur exactly once)"),
        Triple("new_text", "string", "Replacement text (may be empty to delete the snippet)"),
        required = listOf("path", "old_text", "new_text"),
    )
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val path = args.requiredArg("path") ?: return@withContext fail("Missing required argument: path")
        val oldText = args["old_text"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext fail("Missing required argument: old_text")
        if (oldText.isEmpty()) return@withContext fail("old_text must not be empty")
        val newText = args["new_text"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext fail("Missing required argument: new_text")
        try {
            val result = files.applyPatch(path, oldText, newText)
            if (result.startsWith("Error:")) fail(result) else ToolResult(ok = true, output = result)
        } catch (e: SecurityException) {
            fail(e.message ?: "Path escapes workspace root: $path")
        } catch (e: Exception) {
            fail(e.message ?: "Edit failed for $path")
        }
    }
}

// ---------------------------------------------------------------------------
// 2. apply_patch — apply a unified diff
// ---------------------------------------------------------------------------

/**
 * Unified-diff applier (`name = "apply_patch"`).
 *
 * Applies a strict unified diff (`---`/`+++` headers, `@@` hunks) to the
 * workspace. Every hunk's context and removed lines must match the file
 * exactly; any mismatch, malformed diff, or path escape fails the whole
 * patch with nothing written. Writes go through [WorkspaceFileTools] so
 * snapshots and post-edit hooks run. Not sensitive: same basis as [EditTool].
 */
class ApplyPatchTool(private val files: WorkspaceFileTools) : Tool {
    constructor(workspaceRoot: File) : this(WorkspaceFileTools(workspaceRoot))

    override val name: String = "apply_patch"
    override val description: String =
        "Applies a unified diff to workspace files. Each hunk's context and removed " +
            "lines must match the file exactly; any mismatch or malformed diff fails " +
            "the whole patch with nothing written. Use the optional path only when " +
            "the diff has no ---/+++ file headers."
    override val parametersSchema: JsonObject = schema(
        Triple("patch", "string", "Full unified diff text (---/+++ headers, @@ hunks)"),
        Triple("path", "string", "Optional: target file when the diff has no file headers"),
        required = listOf("patch"),
    )
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val patch = args["patch"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext fail("Missing required argument: patch")
        if (patch.isBlank()) return@withContext fail("patch must not be empty")
        val path = args.requiredArg("path")
        try {
            val parsed = UnifiedDiffApplier.parse(patch, path)
            val result = UnifiedDiffApplier.apply(files, parsed)
            ToolResult(ok = true, output = result)
        } catch (e: SecurityException) {
            fail(e.message ?: "Patch targets a path outside the workspace")
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "Invalid patch")
        } catch (e: Exception) {
            fail(e.message ?: "Failed to apply patch")
        }
    }
}

// ---------------------------------------------------------------------------
// 3. glob — list files matching a glob pattern
// ---------------------------------------------------------------------------

/**
 * Glob file listing (`name = "glob"`).
 *
 * Lists workspace files matching a glob pattern (`*`, `?`, `**`,
 * character classes). Read-only.
 */
class GlobTool(private val files: WorkspaceFileTools) : Tool {
    constructor(workspaceRoot: File) : this(WorkspaceFileTools(workspaceRoot))

    override val name: String = "glob"
    override val description: String =
        "Lists workspace files matching a glob pattern (supports *, ?, ** and " +
            "character classes, e.g. src/**/*.kt). Results are capped at 200."
    override val parametersSchema: JsonObject = schema(
        Triple("pattern", "string", "Glob pattern, e.g. src/**/*.kt"),
        Triple("directory", "string", "Optional workspace-relative directory to search under (default: workspace root)"),
        required = listOf("pattern"),
    )
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val pattern = args.requiredArg("pattern") ?: return@withContext fail("Missing required argument: pattern")
        val directory = args.requiredArg("directory").orEmpty()
        try {
            val all = files.listFiles(directory, maxResults = 1000)
            val relativeToDir = directory.trim().removeSuffix("/").let { if (it.isEmpty()) "" else "$it/" }
            val matched = all
                .map { it.removePrefix(relativeToDir) to it }
                .filter { (rel, _) -> GlobMatcher.matches(pattern, rel) }
                .map { (_, full) -> full }
            val shown = matched.take(MAX_RESULTS)
            val out = buildString {
                shown.forEach { appendLine(it) }
                if (matched.size > shown.size) {
                    append("... (${matched.size - shown.size} more matches, showing first $MAX_RESULTS)")
                }
            }
            ToolResult(ok = true, output = out.trimEnd().ifEmpty { "No files match '$pattern'" })
        } catch (e: SecurityException) {
            fail(e.message ?: "Directory escapes workspace root: $directory")
        } catch (e: Exception) {
            fail(e.message ?: "Glob failed for '$pattern'")
        }
    }

    companion object {
        const val MAX_RESULTS: Int = 200
    }
}

// ---------------------------------------------------------------------------
// 4. grep — search file contents
// ---------------------------------------------------------------------------

/**
 * Content search (`name = "grep"`).
 *
 * Searches file contents for a literal string or a regex, in one file, one
 * directory, or the whole workspace. Returns `file:line: text` hits, capped.
 * Read-only.
 */
class GrepTool(private val files: WorkspaceFileTools) : Tool {
    constructor(workspaceRoot: File) : this(WorkspaceFileTools(workspaceRoot))

    override val name: String = "grep"
    override val description: String =
        "Searches workspace file contents for a pattern (literal by default, " +
            "or regex with regex=true). Returns file:line matches with the matching line text."
    override val parametersSchema: JsonObject = schema(
        Triple("pattern", "string", "Text or regex pattern to search for"),
        Triple("path", "string", "Optional file or directory to search in (default: whole workspace)"),
        Triple("regex", "boolean", "Treat pattern as a regular expression (default false)"),
        Triple("case_sensitive", "boolean", "Case-sensitive matching (default true)"),
        Triple("max_matches", "integer", "Maximum matches to return (default 50, max 500)"),
        required = listOf("pattern"),
    )
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val pattern = args["pattern"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext fail("Missing required argument: pattern")
        if (pattern.isEmpty()) return@withContext fail("pattern must not be empty")
        val path = args.requiredArg("path").orEmpty()
        val useRegex = args.boolArg("regex", false)
        val caseSensitive = args.boolArg("case_sensitive", true)
        val maxMatches = args.intArg("max_matches", 50).coerceIn(1, 500)
        val predicate: (String) -> Boolean = if (useRegex) {
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            val regex = try {
                Regex(pattern, options)
            } catch (e: Exception) {
                return@withContext fail("Invalid regex '$pattern': ${e.message}")
            }
            { line -> regex.containsMatchIn(line) }
        } else if (caseSensitive) {
            { line -> line.contains(pattern) }
        } else {
            { line -> line.contains(pattern, ignoreCase = true) }
        }
        try {
            val (hits, total) = searchContent(files, path, predicate, maxMatches)
            if (hits.isEmpty()) {
                return@withContext ToolResult(ok = true, output = "No matches for '$pattern'")
            }
            val out = buildString {
                hits.forEach { appendLine("${it.path}:${it.line}: ${it.text}") }
                if (total > hits.size) {
                    append("... (${total - hits.size} more matches, showing first ${hits.size} of $total)")
                }
            }
            ToolResult(ok = true, output = out.trimEnd())
        } catch (e: SecurityException) {
            fail(e.message ?: "Path escapes workspace root: $path")
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "Invalid search path: $path")
        } catch (e: Exception) {
            fail(e.message ?: "Grep failed for '$pattern'")
        }
    }
}

// ---------------------------------------------------------------------------
// 5. search — filename AND content search in one call (OpenCode semantic)
// ---------------------------------------------------------------------------

/**
 * Broad workspace search (`name = "search"`).
 *
 * Combines filename matching and content matching in one call: filename hits
 * (basename or path contains the query, case-insensitive) plus content hits
 * (literal, case-insensitive). Read-only.
 */
class SearchTool(private val files: WorkspaceFileTools) : Tool {
    constructor(workspaceRoot: File) : this(WorkspaceFileTools(workspaceRoot))

    override val name: String = "search"
    override val description: String =
        "Searches the workspace by filename and by file contents in one call. " +
            "Returns filename hits and content hits under separate headings."
    override val parametersSchema: JsonObject = schema(
        Triple("query", "string", "Text to search for in filenames and file contents"),
        Triple("max_results", "integer", "Maximum results per section (default 50, max 500)"),
        required = listOf("query"),
    )
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val query = args.requiredArg("query") ?: return@withContext fail("Missing required argument: query")
        val maxResults = args.intArg("max_results", 50).coerceIn(1, 500)
        try {
            val allFiles = files.listFiles(maxResults = 1000)
            val fileHits = allFiles.filter {
                it.substringAfterLast('/').contains(query, ignoreCase = true) ||
                    it.contains(query, ignoreCase = true)
            }
            val (contentHits, contentTotal) = searchContent(
                files, "", { line -> line.contains(query, ignoreCase = true) }, maxResults,
            )
            if (fileHits.isEmpty() && contentHits.isEmpty()) {
                return@withContext ToolResult(ok = true, output = "No matches for '$query'")
            }
            val out = buildString {
                appendLine("Files:")
                if (fileHits.isEmpty()) {
                    appendLine("  (none)")
                } else {
                    fileHits.take(maxResults).forEach { appendLine("  $it") }
                    if (fileHits.size > maxResults) {
                        appendLine("  ... (${fileHits.size - maxResults} more files, showing first $maxResults)")
                    }
                }
                appendLine("Content:")
                if (contentHits.isEmpty()) {
                    appendLine("  (none)")
                } else {
                    contentHits.forEach { appendLine("  ${it.path}:${it.line}: ${it.text}") }
                    if (contentTotal > contentHits.size) {
                        appendLine("  ... (${contentTotal - contentHits.size} more matches, showing first ${contentHits.size} of $contentTotal)")
                    }
                }
            }
            ToolResult(ok = true, output = out.trimEnd())
        } catch (e: SecurityException) {
            fail(e.message ?: "Search path escapes workspace root")
        } catch (e: Exception) {
            fail(e.message ?: "Search failed for '$query'")
        }
    }
}

// ---------------------------------------------------------------------------
// 6. file-diff — unified diff of changed files
// ---------------------------------------------------------------------------

/**
 * Changed-file diff (`name = "file-diff"`).
 *
 * Shows the unified diff for one changed file, or for all files changed
 * since the session started (via [WorkspaceFileTools.getChanges], which is
 * the public accessor for the diffs — no change to WorkspaceFileTools was
 * needed). Output is capped at 20,000 characters. Read-only.
 */
class FileDiffTool(private val files: WorkspaceFileTools) : Tool {
    constructor(workspaceRoot: File) : this(WorkspaceFileTools(workspaceRoot))

    override val name: String = "file-diff"
    override val description: String =
        "Shows the unified diff for a changed workspace file, or for all files " +
            "changed since the session started when no path is given."
    override val parametersSchema: JsonObject = schema(
        Triple("path", "string", "Optional workspace-relative path of one file; omit for all changed files"),
    )
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val path = args.requiredArg("path")
        try {
            val changes = files.getChanges()
            val selected = if (path == null) changes else changes.filter { it.relativePath == path }
            if (selected.isEmpty()) {
                if (path == null) {
                    return@withContext ToolResult(ok = true, output = "No changes.")
                }
                val file = runCatching { files.resolveSafe(path) }.getOrNull()
                return@withContext if (file != null && file.isFile) {
                    ToolResult(ok = true, output = "No changes for $path.")
                } else {
                    fail("File not found: $path")
                }
            }
            val full = selected.joinToString("\n") { it.unifiedDiff.trimEnd() }
            val output = if (full.length > MAX_OUTPUT_CHARS) {
                full.take(MAX_OUTPUT_CHARS) +
                    "\n... (truncated, ${full.length - MAX_OUTPUT_CHARS} more characters)"
            } else {
                full
            }
            ToolResult(ok = true, output = output)
        } catch (e: SecurityException) {
            fail(e.message ?: "Path escapes workspace root: $path")
        } catch (e: Exception) {
            fail(e.message ?: "Diff failed")
        }
    }

    companion object {
        const val MAX_OUTPUT_CHARS: Int = 20_000
    }
}

// ---------------------------------------------------------------------------
// 7. write_file — create or replace a whole file
// ---------------------------------------------------------------------------

/**
 * Whole-file write (`name = "write_file"`).
 *
 * Creates or replaces a workspace file with the given content. This is
 * the `write_file` half of [ToolRouter]'s EDIT_WRITE strategy: the
 * router names it, so it must exist — previously the name was a latent
 * trap (named, never implemented). Writes go through
 * [WorkspaceFileTools.writeFile] so snapshots and post-edit hooks run.
 * Content is capped at 150,000 characters, matching the developer-only
 * `workspace_write` tool. Not sensitive: same basis as [EditTool] —
 * writes stay inside the workspace sandbox and the policy gate handles
 * approval. Prefer `edit` for small changes to an existing file.
 */
class WriteFileTool(private val files: WorkspaceFileTools) : Tool {
    constructor(workspaceRoot: File) : this(WorkspaceFileTools(workspaceRoot))

    override val name: String = "write_file"
    override val description: String =
        "Creates or replaces a whole workspace file with the given content. " +
            "Use edit for small changes to an existing file; use this for new files or full rewrites."
    override val parametersSchema: JsonObject = schema(
        Triple("path", "string", "Workspace-relative path of the file to write"),
        Triple("content", "string", "Complete UTF-8 text to save (max 150,000 characters)"),
        required = listOf("path", "content"),
    )
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val path = args.requiredArg("path") ?: return@withContext fail("Missing required argument: path")
        val content = args["content"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext fail("Missing required argument: content")
        if (content.length > MAX_CONTENT_CHARS) {
            return@withContext fail("Content exceeds the 150,000 character limit")
        }
        try {
            val result = files.writeFile(path, content)
            ToolResult(ok = true, output = result)
        } catch (e: SecurityException) {
            fail(e.message ?: "Path escapes workspace root: $path")
        } catch (e: Exception) {
            fail(e.message ?: "Write failed for $path")
        }
    }

    companion object {
        const val MAX_CONTENT_CHARS: Int = 150_000
    }
}
