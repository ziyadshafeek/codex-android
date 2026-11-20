package dev.codex.android.artifacts

import dev.codex.android.tools.WorkspaceFileTools
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

internal const val WORKSPACE_DOWNLOAD_SCAN_LIMIT = 1_500
private val ignoredFolders = setOf("node_modules", "vendor", "__pycache__", "obj", "intermediates", "kotlin", "caches")
private val credentialNames = setOf("credentials", "credentials.json", "secrets.json", "local.properties")
private val credentialExtensions = setOf("pem", "key", "p12", "pfx", "jks", "keystore")

internal fun isDownloadablePath(path: String): Boolean {
    val segments = path.split('/')
    if (segments.any { it.isBlank() || it.startsWith('.') || it in ignoredFolders || it.any(Char::isISOControl) || '\\' in it }) return false
    val name = segments.last().lowercase(java.util.Locale.ROOT)
    return name !in credentialNames && name.substringAfterLast('.', "") !in credentialExtensions
}

internal fun resolveWorkspaceDownload(workspace: WorkspaceFileTools, path: String): File {
    require(isDownloadablePath(path)) { "This path is not available for download." }
    val root = workspace.rootDir.toPath()
    val entry = root.resolve(path).normalize()
    require(entry.startsWith(root) && entry != root) { "This path is outside the workspace." }
    val resolved = workspace.resolveSafe(path)
    require(entry.toFile() == resolved && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
        "The saved file was moved, deleted, or is a link."
    }
    return resolved
}

internal data class WorkspaceStamp(val size: Long, val modified: FileTime)

internal fun downloadIndex(workspace: WorkspaceFileTools): Map<String, WorkspaceStamp> {
    val root = workspace.rootDir.toPath()
    val found = linkedMapOf<String, WorkspaceStamp>()
    var visited = 0
    Files.walkFileTree(root, emptySet(), 15, object : SimpleFileVisitor<Path>() {
        private fun relative(path: Path): String = root.relativize(path).joinToString("/") { it.toString() }
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = when {
            ++visited > WORKSPACE_DOWNLOAD_SCAN_LIMIT -> FileVisitResult.TERMINATE
            dir == root || isDownloadablePath(relative(dir)) -> FileVisitResult.CONTINUE
            else -> FileVisitResult.SKIP_SUBTREE
        }
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (++visited > WORKSPACE_DOWNLOAD_SCAN_LIMIT) return FileVisitResult.TERMINATE
            val path = relative(file)
            if (attrs.isRegularFile && isDownloadablePath(path)) found[path] = WorkspaceStamp(attrs.size(), attrs.lastModifiedTime())
            return FileVisitResult.CONTINUE
        }
        override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
    })
    return found
}

internal fun changedWorkspaceOutputs(before: Map<String, WorkspaceStamp>, after: Map<String, WorkspaceStamp>): List<String> =
    after.entries.filter { (path, stamp) -> before[path] != stamp }
        .sortedWith(compareBy<Map.Entry<String, WorkspaceStamp>> { it.key in before }.thenByDescending { it.value.modified })
        .take(12).map { it.key }

/** Only handle clear export requests locally, and never infer a file from multiple candidates. */
internal fun requestedWorkspaceDownload(goal: String, recentPath: String?, lastAssistant: String?): String? {
    if (!Regex("""(?i)\b(?:download|export|(?:save|copy)\b.{0,100}\bdownloads)\b""").containsMatchIn(goal)) return null
    if (Regex("""(?i)(?:https?://|\b(?:don't|do not|never|and then)\b)""").containsMatchIn(goal)) return null
    val pathPattern = Regex("""(?<![\w./-])[\w-]+(?:/[\w.-]+)*\.[\w-]{1,20}\b""")
    val explicit = pathPattern.findAll(goal).map { it.value }.distinct().toList()
    if (explicit.isNotEmpty()) return explicit.singleOrNull()?.takeIf(::isDownloadablePath)
    if (!Regex("""(?i)\b(it|that file|this file|the file)\b""").containsMatchIn(goal)) return null
    val referenced = lastAssistant?.let { text ->
        Regex("`([^`\\r\\n]+)`").findAll(text).map { it.groupValues[1] }
            .filter { pathPattern.matches(it) && isDownloadablePath(it) }.distinct().toList()
    }.orEmpty()
    return when {
        referenced.size == 1 -> referenced.single()
        referenced.size > 1 -> null
        else -> recentPath
    }
}
