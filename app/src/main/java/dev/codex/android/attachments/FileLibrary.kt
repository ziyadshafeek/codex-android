package dev.codex.android.attachments

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.codex.android.artifacts.resolveWorkspaceDownload
import dev.codex.android.tools.WorkspaceFileTools
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val MIB = 1024L * 1024
internal data class ManagedFile(val group: String, val id: String, val name: String, val bytes: Long, val modified: Long) {
    val key get() = "$group/$id/$name"
    val vmPath get() = "/workspace/.attachments/$key"
    val saved get() = group == "library"
}
internal data class FileStorage(val managed: Long, val workspace: Long, val linux: Long, val free: Long,
    val ramAvailable: Long, val ramTotal: Long)

/** Library is outside the Linux workspace. Only a staged copy is exposed to Linux. */
internal class FileLibrary(private val context: Context, private val workspace: WorkspaceFileTools) {
    private val stage = File(workspace.rootDir, ".attachments")
    private val library = File(context.filesDir.canonicalFile, "file-library")
    private val preferences = context.getSharedPreferences("file-library-settings", Context.MODE_PRIVATE)
    var limitMiB: Int
        get() = preferences.getInt("quota", 512)
        set(value) { preferences.edit().putInt("quota", value.coerceIn(0, 4096)).apply() }

    private fun groupDir(group: String): File {
        if (group == "library") return library.also { require(it.canonicalFile == it) }
        require(group == "drafts" || thread.matches(group)) { "Invalid file group." }
        require(stage.canonicalFile == stage) { "Attachment folder cannot be a link." }
        return File(stage, group).also { require(it.canonicalFile == it) { "Attachment group cannot be a link." } }
    }

    private fun folder(entry: ManagedFile): File {
        require(uuid.matches(entry.id)) { "Invalid file ID." }
        return File(groupDir(entry.group), entry.id).also { require(it.canonicalFile == it) { "File folder cannot be a link." } }
    }

    fun resolve(entry: ManagedFile): File = File(folder(entry), entry.name).also {
        require(entry.name == it.name && it.canonicalFile == it && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS)) {
            "The original file was deleted, moved, or is a link."
        }
    }

    suspend fun import(uri: Uri): ManagedFile = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Choose a file from Android Files." }
        val resolver = context.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: "Attachment"
        copy("drafts", name) { resolver.openInputStream(uri) ?: error("Could not open the selected file.") }
    }

    suspend fun fromLibrary(entry: ManagedFile): ManagedFile = withContext(Dispatchers.IO) {
        require(entry.saved)
        copy("drafts", entry.name) { Files.newInputStream(resolve(entry).toPath(), NOFOLLOW_LINKS) }
    }

    suspend fun fromWorkspace(path: String, group: String): ManagedFile = withContext(Dispatchers.IO) {
        val source = resolveWorkspaceDownload(workspace, path)
        copy(group, source.name) { Files.newInputStream(source.toPath(), NOFOLLOW_LINKS) }
    }

    suspend fun promote(entry: ManagedFile, threadId: String): ManagedFile = withContext(Dispatchers.IO) {
        require(entry.group == "drafts" && thread.matches(threadId))
        synchronized(lock) {
            val target = entry.copy(group = threadId)
            val source = resolve(entry)
            val dir = folder(target)
            check(dir.parentFile?.mkdirs() == true || dir.parentFile?.isDirectory == true) { "Could not create chat storage." }
            check(source.parentFile?.renameTo(dir) == true) { "Could not stage ${entry.name}." }
            target
        }
    }

    suspend fun save(entry: ManagedFile): ManagedFile = withContext(Dispatchers.IO) {
        copy("library", entry.name) { Files.newInputStream(resolve(entry).toPath(), NOFOLLOW_LINKS) }
    }

    suspend fun delete(entry: ManagedFile) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val file = resolve(entry)
            check(file.delete()) { "Could not delete ${entry.name}." }
            file.parentFile?.delete()
        }
    }

    fun list(group: String): List<ManagedFile> = synchronized(lock) {
        val dir = groupDir(group)
        dir.listFiles().orEmpty().flatMap { folder ->
            if (!uuid.matches(folder.name) || !Files.isDirectory(folder.toPath(), NOFOLLOW_LINKS)) emptyList()
            else folder.listFiles().orEmpty().filter { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) && it.name != ".partial" }
                .map { ManagedFile(group, folder.name, it.name, it.length(), it.lastModified()) }
        }.sortedByDescending { it.modified }
    }

    fun staged(): List<ManagedFile> = synchronized(lock) {
        if (stage.canonicalFile != stage) return@synchronized emptyList()
        stage.listFiles().orEmpty().filter { it.name == "drafts" || thread.matches(it.name) }
            .filter { Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }.flatMap { list(it.name) }.sortedByDescending { it.modified }
    }

    suspend fun storage(): FileStorage = withContext(Dispatchers.IO) {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        FileStorage(synchronized(lock) { bytes(library) + bytes(stage) }, bytes(workspace.rootDir, stage),
            bytes(File(context.filesDir, "linux")), context.filesDir.usableSpace, info.availMem, info.totalMem)
    }

    private suspend fun copy(group: String, original: String, open: () -> InputStream): ManagedFile = withContext(Dispatchers.IO) {
        val job = coroutineContext
        synchronized(lock) {
            val name = safeName(original.substringAfterLast('/').substringAfterLast('\\')).trimStart('.')
                .take(80).ifBlank { "Attachment" }
            val item = ManagedFile(group, UUID.randomUUID().toString(), name, 0, 0)
            val dir = folder(item)
            val remaining = limitMiB * MIB - bytes(library) - bytes(stage)
            require(remaining > 0) { "Managed storage is full. Delete files or increase the limit in File library." }
            check(dir.mkdirs()) { "Could not create file storage." }
            val tmp = File(dir, ".partial")
            try {
                var size = 0L
                open().use { source -> tmp.outputStream().buffered().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        job.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= remaining) { "This file exceeds the remaining managed storage limit." }
                        out.write(buffer, 0, count)
                    }
                } }
                val target = File(dir, name)
                check(tmp.renameTo(target)) { "Could not finish saving $name." }
                item.copy(bytes = size, modified = target.lastModified())
            } finally {
                tmp.delete()
                dir.delete() // only succeeds if empty
            }
        }
    }

    private fun bytes(root: File, exclude: File? = null): Long {
        if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) return 0
        var sum = 0L
        Files.walkFileTree(root.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun preVisitDirectory(dir: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes) =
                if (dir.toFile() == exclude) java.nio.file.FileVisitResult.SKIP_SUBTREE else java.nio.file.FileVisitResult.CONTINUE
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if (attrs.isRegularFile) sum += attrs.size()
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: java.nio.file.Path, exc: java.io.IOException) = java.nio.file.FileVisitResult.CONTINUE
        })
        return sum
    }

    private companion object {
        val lock = Any()
        val thread = Regex("thr_[0-9a-f]{8}-[0-9a-f]{3}")
        val uuid = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    }
}
