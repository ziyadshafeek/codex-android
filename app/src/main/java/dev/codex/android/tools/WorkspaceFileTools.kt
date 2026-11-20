package dev.codex.android.tools

import dev.codex.android.editor.PostEditToolchain
import dev.codex.android.linux.ProotShellEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

data class FileChangeSummary(
    val relativePath: String,
    val addedLines: Int,
    val removedLines: Int,
    val unifiedDiff: String,
    val isNewFile: Boolean,
)

/**
 * File tools for one workspace root. [proot] (nullable) is the Linux
 * runtime the post-edit formatter + diagnostics loop runs through
 * after every successful write; with no runtime wired it stays null
 * and edits behave exactly as before (no post-edit step).
 */
class WorkspaceFileTools(
    workspaceDir: File,
    private val proot: ProotShellEngine? = null,
) {

    private val rootCanonical: File = workspaceDir.apply { mkdirs() }.canonicalFile

    /** Canonical workspace root, so the editor can open real files under it. */
    val rootDir: File get() = rootCanonical

    // Maps relative path -> original file text before first edit (null = file did not exist).
    // Plain HashMap, NOT ConcurrentHashMap: ConcurrentHashMap forbids null values,
    // and null is the legitimate "did not exist" sentinel here (putting null throws
    // NullPointerException). Access is guarded by @Synchronized on the methods below.
    private val originalSnapshots = HashMap<String, String?>()

    /**
     * Strictly resolves [relativePath] inside [rootCanonical], rejecting ".." escapes
     * and absolute paths outside the workspace root.
     * Internal (not private) so JVM unit tests in the same module can call it directly.
     */
    internal fun resolveSafe(relativePath: String): File {
        val trimmed = relativePath.trim()
        // Absolute paths are rejected outright: the verbatim drop-in stripped a
        // leading "/" and silently remapped "/etc/passwd" to <root>/etc/passwd.
        if (trimmed.startsWith("/") || trimmed.startsWith("\\") ||
            File(trimmed).isAbsolute || trimmed.matches(Regex("^[A-Za-z]:.*"))
        ) {
            throw SecurityException("Absolute paths are not allowed inside the workspace: $relativePath")
        }
        val cleaned = trimmed.removePrefix("/")
        val target = File(rootCanonical, cleaned).canonicalFile
        val rootPrefix = rootCanonical.path + File.separator
        if (target != rootCanonical && !target.path.startsWith(rootPrefix)) {
            throw SecurityException("Path escapes workspace root: $relativePath")
        }
        return target
    }

    private fun relPath(file: File): String =
        file.canonicalFile.relativeTo(rootCanonical).path.replace(File.separatorChar, '/')

    @Synchronized
    private fun snapshotIfNeeded(file: File) {
        val key = relPath(file)
        if (!originalSnapshots.containsKey(key)) {
            originalSnapshots[key] = if (file.exists() && file.isFile) file.readText() else null
        }
    }

    fun readFile(relativePath: String, startLine: Int = 1, maxLines: Int = 200): String {
        val file = resolveSafe(relativePath)
        if (!file.exists() || !file.isFile) return "Error: file not found: $relativePath"
        val lines = file.readLines()
        val from = (startLine - 1).coerceIn(0, lines.size)
        val to = (from + maxLines.coerceAtLeast(1)).coerceAtMost(lines.size)
        val sb = StringBuilder()
        for (i in from until to) {
            sb.append(i + 1).append(" | ").append(lines[i]).append('\n')
        }
        if (to < lines.size) {
            sb.append("... (${lines.size - to} more lines)\n")
        }
        return sb.toString()
    }

    fun writeFile(relativePath: String, content: String): String {
        val file = resolveSafe(relativePath)
        snapshotIfNeeded(file)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "Wrote ${content.length} chars to ${relPath(file)}" + runPostEdit(file)
    }

    /**
     * Post-edit formatter + diagnostics for a just-written [file], on a
     * best-effort basis. Returns the report suffix for the tool result
     * ("" when no runtime is wired). Never throws and never fails the
     * edit: a missing runtime, a failed formatter, or a failed
     * diagnostics run degrades to a one-line note inside the report.
     */
    private fun runPostEdit(file: File): String {
        val engine = proot ?: return ""
        return runCatching {
            runBlocking(Dispatchers.IO) {
                PostEditToolchain.runPostEdit(engine, file, "/workspace/${relPath(file)}")
            }.toToolResultSuffix()
        }.getOrDefault("")
    }

    /**
     * Deterministic search-and-replace patch (much more reliable for LLMs than raw line numbers).
     */
    fun applyPatch(relativePath: String, oldSnippet: String, newSnippet: String): String {
        if (oldSnippet.isEmpty()) {
            return "Error: target snippet must not be empty"
        }
        val file = resolveSafe(relativePath)
        if (!file.exists() || !file.isFile) return "Error: file not found: $relativePath"
        val current = file.readText()
        // Count occurrences with indexOf, not windowed(): windowed()
        // allocates one String per character position of the whole file
        // just to count. Overlapping matches still count (step +1).
        var occurrences = 0
        var fromIndex = 0
        while (true) {
            val found = current.indexOf(oldSnippet, fromIndex)
            if (found < 0) break
            occurrences++
            fromIndex = found + 1
        }
        if (occurrences == 0) {
            return "Error: target snippet not found in $relativePath"
        }
        if (occurrences > 1) {
            return "Error: target snippet matched $occurrences times in $relativePath; provide more context"
        }
        snapshotIfNeeded(file)
        file.writeText(current.replaceFirst(oldSnippet, newSnippet))
        return "Patched ${relPath(file)}" + runPostEdit(file)
    }

    fun listFiles(relativeDir: String = "", maxResults: Int = 200): List<String> {
        val dir = if (relativeDir.isBlank()) rootCanonical else resolveSafe(relativeDir)
        if (!dir.isDirectory || maxResults <= 0) return emptyList()
        val results = mutableListOf<String>()
        Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(path: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (path.fileName.toString() in setOf(".git", "build", "node_modules")) FileVisitResult.SKIP_SUBTREE
                else FileVisitResult.CONTINUE

            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) results += relPath(path.toFile())
                return if (results.size >= maxResults) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }
        })
        return results.sorted()
    }

    fun searchText(query: String, maxMatches: Int = 50): String {
        if (query.isBlank()) return "Error: empty query"
        val regex = runCatching { Regex(query, RegexOption.IGNORE_CASE) }
            .getOrElse { Regex(Regex.escape(query), RegexOption.IGNORE_CASE) }
        val matches = mutableListOf<String>()
        for (path in listFiles(maxResults = 1000)) {
            val file = resolveSafe(path)
            if (file.length() > 512_000) continue // skip large/binary files
            file.useLines { seq ->
                seq.forEachIndexed { idx, line ->
                    if (matches.size < maxMatches && regex.containsMatchIn(line)) {
                        matches += "$path:${idx + 1}: ${line.trim().take(160)}"
                    }
                }
            }
            if (matches.size >= maxMatches) break
        }
        return if (matches.isEmpty()) "No matches for '$query'" else matches.joinToString("\n")
    }

    /** Produces structured diffs for the Changes / Diff Review screen. */
    @Synchronized
    fun getChanges(): List<FileChangeSummary> {
        return originalSnapshots.entries.sortedBy { it.key }.mapNotNull { (path, before) ->
            val file = resolveSafe(path)
            // A file created and then deleted again within the session is no change.
            if (before == null && !file.exists()) return@mapNotNull null
            val after = if (file.exists()) file.readText() else ""
            if (before == after) return@mapNotNull null
            val beforeLines = before?.lines().orEmpty()
            val afterLines = if (file.exists()) after.lines() else emptyList()
            val diff = buildSimpleUnifiedDiff(path, beforeLines, afterLines)
            FileChangeSummary(
                relativePath = path,
                addedLines = diff.added,
                removedLines = diff.removed,
                unifiedDiff = diff.text,
                isNewFile = before == null,
            )
        }
    }

    @Synchronized
    fun revertFile(relativePath: String): Boolean {
        val key = relPath(resolveSafe(relativePath))
        if (!originalSnapshots.containsKey(key)) return false
        val original = originalSnapshots.remove(key)
        val file = resolveSafe(key)
        if (original == null) {
            file.delete()
        } else {
            file.parentFile?.mkdirs()
            file.writeText(original)
        }
        return true
    }

    @Synchronized
    fun revertAll() {
        originalSnapshots.keys.toList().forEach { revertFile(it) }
    }

    private data class DiffOutput(val text: String, val added: Int, val removed: Int)

    private fun buildSimpleUnifiedDiff(
        path: String,
        oldLines: List<String>,
        newLines: List<String>,
    ): DiffOutput {
        // LCS-based unified diff (fast for source files up to ~2k lines).
        val m = oldLines.size
        val n = newLines.size
        val sb = StringBuilder()
        sb.append("--- a/").append(path).append('\n')
        sb.append("+++ b/").append(path).append('\n')

        if (m * n > 2_000_000) {
            // Fallback summary for huge files to prevent OOM on low-end phones.
            sb.append("@@ -1,$m +1,$n @@ (file too large for inline LCS diff)\n")
            return DiffOutput(sb.toString(), n, m)
        }

        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in m - 1 downTo 0) {
            for (j in n - 1 downTo 0) {
                dp[i][j] = if (oldLines[i] == newLines[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }
        var i = 0
        var j = 0
        var added = 0
        var removed = 0
        while (i < m || j < n) {
            when {
                i < m && j < n && oldLines[i] == newLines[j] -> {
                    sb.append(' ').append(oldLines[i]).append('\n')
                    i++; j++
                }
                j < n && (i == m || dp[i][j + 1] >= dp[i + 1][j]) -> {
                    sb.append('+').append(newLines[j]).append('\n')
                    added++; j++
                }
                else -> {
                    sb.append('-').append(oldLines[i]).append('\n')
                    removed++; i++
                }
            }
        }
        return DiffOutput(sb.toString(), added, removed)
    }
}
