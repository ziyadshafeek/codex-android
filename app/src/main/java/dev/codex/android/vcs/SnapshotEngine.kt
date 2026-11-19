package dev.codex.android.vcs

import dev.codex.android.core.CodexJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Metadata for one turn snapshot stored outside the workspace. */
@Serializable
data class SnapshotMeta(
    val id: String,
    val label: String,
    val createdAtMillis: Long,
    val changedPaths: List<String>,
)

/** Per-file before/after state captured in a single surgical snapshot. */
@Serializable
data class FileDeltaRecord(
    val relativePath: String,
    val existedBefore: Boolean,
    val beforeHash: String?,
    val beforeMtime: Long,
    val existedAfter: Boolean,
    val afterHash: String?,
    val afterMtime: Long,
)

/** Full snapshot manifest returned by [SnapshotEngine.snapshot]. */
@Serializable
data class Snapshot(
    val meta: SnapshotMeta,
    val deltas: List<FileDeltaRecord>,
)

@Serializable
private data class TrackedFileEntry(
    val relativePath: String,
    val sha256: String,
    val mtime: Long,
)

@Serializable
private data class SnapshotIndex(
    val cursor: Int = -1,
    val snapshots: List<Snapshot> = emptyList(),
    val baseline: List<TrackedFileEntry> = emptyList(),
)

/**
 * Shadow-Git style snapshot engine (Milestone M5) that works identically on Git
 * and non-Git workspaces (fixes OpenCode #38672 and #15391 by design):
 *
 * - Stores per-file before/after blobs strictly OUTSIDE the workspace under
 *   `<snapshotRoot>/<canonical-workspace-hash>/<snapshot-id>/` (defaulting to
 *   `filesDir/snapshots/<workspace-key>/` when passed `filesDir`).
 * - Tracks exact changed paths per turn and performs surgical `/undo` & `/redo`:
 *   untouched files are NEVER written or touched, preserving their original mtimes.
 * - Restored files have their exact original `lastModified` timestamps restored.
 * - Construction does NO disk I/O (zero-Main-thread-I/O rule): directory
 *   creation and the baseline scan happen only in [initialize], which the caller
 *   must invoke once, off the Main thread (e.g. `scope.launch(Dispatchers.IO)` in
 *   the DI root, immediately after construction and before any agent turn edits
 *   files). Every public operation also ensures a baseline exists as a defensive
 *   fallback, but that fallback baselines whatever the workspace looks like at
 *   first use — so a caller that skips [initialize] will see its first turn
 *   report no changes. Call [initialize] explicitly.
 */
class SnapshotEngine(
    val workspaceDir: File,
    snapshotsBaseDir: File? = null,
) {
    // Lazy: resolving the canonical path and creating directories is disk I/O,
    // so it must not run in the constructor. First access happens inside the
    // Dispatchers.IO public operations (or on the caller's thread for the
    // test-only storeDir read — never Main in production).
    private val workspaceCanonical: File by lazy { workspaceDir.apply { mkdirs() }.canonicalFile }

    /**
     * Storage root outside [workspaceDir]:
     * - In production, callers pass `File(context.filesDir, "snapshots")` (or `context.filesDir`
     *   via the secondary constructor) so data lives under `filesDir/snapshots/<workspace>/<id>/`.
     * - In single-arg construction `SnapshotEngine(workspaceDir)`, creates an isolated sibling
     *   directory outside [workspaceDir] (never inside the workspace).
     */
    val storeDir: File by lazy {
        val workspaceKey = sha256Hex(workspaceCanonical.path.toByteArray(Charsets.UTF_8)).take(16)
        val base = snapshotsBaseDir ?: File(
            workspaceCanonical.parentFile ?: File(System.getProperty("java.io.tmpdir", ".")),
            ".codex_snapshots",
        )
        File(base, workspaceKey).apply { mkdirs() }
    }

    private val mutex = Mutex()
    private val indexFile: File by lazy { File(storeDir, "index.json") }
    private val objectsDir: File by lazy { File(storeDir, "objects").apply { mkdirs() } }

    // Set once the baseline has been captured (in-memory flag + the on-disk
    // index.json existence check in initializeBaselineIfNeededLocked()).
    @Volatile
    private var baselineInitialized = false

    /** Android-friendly overload storing snapshots under `filesDir/snapshots/<workspace>/`. */
    constructor(workspaceDir: File, contextFilesDir: File, useFilesDirLayout: Boolean) : this(
        workspaceDir = workspaceDir,
        snapshotsBaseDir = if (useFilesDirLayout) File(contextFilesDir, "snapshots") else contextFilesDir,
    )

    /**
     * Captures the baseline (workspace scan + object store + index write) on
     * [Dispatchers.IO]. MUST be called once, off Main, immediately after
     * construction and before any agent turn edits files — the baseline is the
     * "before" state the first snapshot diffs against. Idempotent: a second
     * call (or an existing `index.json` from a previous process) is a no-op.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock { initializeBaselineIfNeededLocked() }
    }

    /**
     * Defensive fallback: every public operation ensures a baseline exists.
     * Must only be called with [mutex] held, on Dispatchers.IO. This is NOT
     * the normal path — see [initialize].
     */
    private fun ensureInitializedLocked() {
        if (!baselineInitialized) initializeBaselineIfNeededLocked()
    }

    /**
     * Captures all files modified, added, or deleted since the previous snapshot
     * (or baseline). Stores per-file before/after content under `<storeDir>/<id>/`.
     */
    suspend fun snapshot(label: String): Snapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureInitializedLocked()
            val index = loadIndexLocked()
            val prevMap = index.baseline.associateBy { it.relativePath }
            val currentFiles = scanWorkspaceFiles()
            val currentMap = currentFiles.associateBy { it.relativePath }

            val snapshotId = "snap_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val snapDir = File(storeDir, snapshotId).apply { mkdirs() }
            val beforeDir = File(snapDir, "before").apply { mkdirs() }
            val afterDir = File(snapDir, "after").apply { mkdirs() }

            val allPaths = (prevMap.keys + currentMap.keys).sorted()
            val deltas = mutableListOf<FileDeltaRecord>()

            for (rel in allPaths) {
                val before = prevMap[rel]
                val after = currentMap[rel]
                val changed = when {
                    before == null && after != null -> true
                    before != null && after == null -> true
                    before != null && after != null -> before.sha256 != after.sha256
                    else -> false
                }
                if (!changed) continue

                if (before != null) {
                    val objFile = objectFile(before.sha256)
                    if (objFile.isFile) {
                        val copy = File(beforeDir, encodePathForFlatFile(rel))
                        objFile.copyTo(copy, overwrite = true)
                    }
                }
                if (after != null) {
                    val liveFile = File(workspaceCanonical, rel)
                    val bytes = liveFile.readBytes()
                    storeObject(after.sha256, bytes)
                    val copy = File(afterDir, encodePathForFlatFile(rel))
                    copy.writeBytes(bytes)
                }

                deltas += FileDeltaRecord(
                    relativePath = rel,
                    existedBefore = before != null,
                    beforeHash = before?.sha256,
                    beforeMtime = before?.mtime ?: 0L,
                    existedAfter = after != null,
                    afterHash = after?.sha256,
                    afterMtime = after?.mtime ?: 0L,
                )
            }

            val snapshot = Snapshot(
                meta = SnapshotMeta(
                    id = snapshotId,
                    label = label.ifBlank { "Turn snapshot" },
                    createdAtMillis = System.currentTimeMillis(),
                    changedPaths = deltas.map { it.relativePath },
                ),
                deltas = deltas,
            )

            // Truncate any redo tail above the current cursor (standard undo/redo branch semantics).
            val kept = if (index.cursor >= 0 && index.cursor < index.snapshots.size) {
                index.snapshots.take(index.cursor + 1)
            } else {
                emptyList()
            }
            val nextSnapshots = kept + snapshot
            saveIndexLocked(
                SnapshotIndex(
                    cursor = nextSnapshots.lastIndex,
                    snapshots = nextSnapshots,
                    baseline = currentFiles,
                ),
            )
            snapshot
        }
    }

    /**
     * Surgically reverts ONLY the files changed in the current turn snapshot,
     * restoring each file's exact prior content and `lastModified` timestamp.
     * Untouched files in the workspace are never written.
     */
    suspend fun undo(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureInitializedLocked()
            val index = loadIndexLocked()
            if (index.cursor < 0 || index.cursor >= index.snapshots.size) return@withLock false

            val snap = index.snapshots[index.cursor]
            val snapBeforeDir = File(File(storeDir, snap.meta.id), "before")
            for (delta in snap.deltas) {
                val target = resolveInsideWorkspace(delta.relativePath)
                if (!delta.existedBefore) {
                    if (target.exists()) target.delete()
                } else {
                    val bytes = readBlob(snapBeforeDir, delta.relativePath, delta.beforeHash)
                        ?: continue
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                    if (delta.beforeMtime > 0L) {
                        target.setLastModified(delta.beforeMtime)
                    }
                }
            }

            saveIndexLocked(
                index.copy(
                    cursor = index.cursor - 1,
                    baseline = scanWorkspaceFiles(),
                ),
            )
            true
        }
    }

    /**
     * Surgically re-applies ONLY the files changed in the next undone turn snapshot,
     * restoring each file's exact post-turn content and `lastModified` timestamp.
     */
    suspend fun redo(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureInitializedLocked()
            val index = loadIndexLocked()
            val nextCursor = index.cursor + 1
            if (nextCursor < 0 || nextCursor >= index.snapshots.size) return@withLock false

            val snap = index.snapshots[nextCursor]
            val snapAfterDir = File(File(storeDir, snap.meta.id), "after")
            for (delta in snap.deltas) {
                val target = resolveInsideWorkspace(delta.relativePath)
                if (!delta.existedAfter) {
                    if (target.exists()) target.delete()
                } else {
                    val bytes = readBlob(snapAfterDir, delta.relativePath, delta.afterHash)
                        ?: continue
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                    if (delta.afterMtime > 0L) {
                        target.setLastModified(delta.afterMtime)
                    }
                }
            }

            saveIndexLocked(
                index.copy(
                    cursor = nextCursor,
                    baseline = scanWorkspaceFiles(),
                ),
            )
            true
        }
    }

    /** Returns metadata for all captured snapshots in chronological order. */
    suspend fun history(): List<SnapshotMeta> = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureInitializedLocked()
            loadIndexLocked().snapshots.map { it.meta }
        }
    }

    private fun initializeBaselineIfNeededLocked() {
        if (baselineInitialized) return
        if (indexFile.isFile) {
            // Baseline already captured by a previous process/initialization.
            baselineInitialized = true
            return
        }
        val initialFiles = scanWorkspaceFiles()
        for (entry in initialFiles) {
            val file = File(workspaceCanonical, entry.relativePath)
            if (file.isFile) storeObject(entry.sha256, file.readBytes())
        }
        saveIndexLocked(SnapshotIndex(cursor = -1, snapshots = emptyList(), baseline = initialFiles))
        baselineInitialized = true
    }

    private fun scanWorkspaceFiles(): List<TrackedFileEntry> {
        if (!workspaceCanonical.isDirectory) return emptyList()
        val entries = mutableListOf<TrackedFileEntry>()
        workspaceCanonical.walkTopDown()
            .onEnter { dir ->
                dir == workspaceCanonical || (dir.name != ".git" && dir.name != "build" && dir.name != "node_modules")
            }
            .filter { it.isFile }
            .forEach { file ->
                val rel = file.canonicalFile.relativeTo(workspaceCanonical).path.replace(File.separatorChar, '/')
                val bytes = file.readBytes()
                entries += TrackedFileEntry(
                    relativePath = rel,
                    sha256 = sha256Hex(bytes),
                    mtime = file.lastModified(),
                )
            }
        return entries.sortedBy { it.relativePath }
    }

    private fun resolveInsideWorkspace(relativePath: String): File {
        val target = File(workspaceCanonical, relativePath).canonicalFile
        val prefix = workspaceCanonical.path + File.separator
        if (target != workspaceCanonical && !target.path.startsWith(prefix)) {
            throw SecurityException("Snapshot path escapes workspace: $relativePath")
        }
        return target
    }

    private fun storeObject(hash: String, bytes: ByteArray) {
        val file = objectFile(hash)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
    }

    private fun readBlob(snapSubDir: File, relativePath: String, hash: String?): ByteArray? {
        val flat = File(snapSubDir, encodePathForFlatFile(relativePath))
        if (flat.isFile) return flat.readBytes()
        if (hash != null) {
            val obj = objectFile(hash)
            if (obj.isFile) return obj.readBytes()
        }
        return null
    }

    private fun objectFile(hash: String): File = File(objectsDir, hash)

    private fun encodePathForFlatFile(relPath: String): String =
        sha256Hex(relPath.toByteArray(Charsets.UTF_8)) + ".blob"

    private fun loadIndexLocked(): SnapshotIndex {
        if (!indexFile.isFile) return SnapshotIndex()
        return runCatching {
            CodexJson.decodeFromString(SnapshotIndex.serializer(), indexFile.readText())
        }.getOrDefault(SnapshotIndex())
    }

    private fun saveIndexLocked(index: SnapshotIndex) {
        indexFile.parentFile?.mkdirs()
        val tmp = File(indexFile.parentFile, "${indexFile.name}.tmp")
        tmp.writeText(CodexJson.encodeToString(SnapshotIndex.serializer(), index))
        if (!tmp.renameTo(indexFile)) {
            indexFile.writeText(CodexJson.encodeToString(SnapshotIndex.serializer(), index))
            tmp.delete()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
