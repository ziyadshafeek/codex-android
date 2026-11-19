package dev.codex.android.artifacts

import dev.codex.android.core.CodexJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Lifecycle status of a verifiable artifact rendered in the workbench. */
@Serializable
enum class ArtifactStatus {
    DRAFT,
    IN_PROGRESS,
    NEEDS_REVIEW,
    APPROVED,
    APPLIED,
}

/** Completion status for a single item inside [TaskChecklist]. */
@Serializable
enum class ChecklistItemStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
    SKIPPED,
}

/** One checkable task inside [TaskChecklist]. */
@Serializable
data class ChecklistItem(
    val id: String,
    val text: String,
    val done: Boolean = false,
    val status: ChecklistItemStatus = if (done) ChecklistItemStatus.DONE else ChecklistItemStatus.PENDING,
)

/** One step inside an [ImplementationPlan]. */
@Serializable
data class PlanStepItem(
    val id: String,
    val title: String,
    val detail: String = "",
    val completed: Boolean = false,
)

/** One verification / explanation step inside a [Walkthrough]. */
@Serializable
data class WalkthroughStep(
    val id: String,
    val title: String,
    val notes: String,
    val evidence: String = "",
)

/** One file diff entry inside a [Diff] artifact. */
@Serializable
data class DiffHunkItem(
    val id: String,
    val filePath: String,
    val unifiedDiff: String,
    val addedLines: Int = 0,
    val removedLines: Int = 0,
)

/** Common contract for verifiable artifacts rendered in the workbench. */
sealed interface Artifact {
    val id: String
    val title: String
    val status: ArtifactStatus
}

/** Verifiable task checklist artifact. */
@Serializable
data class TaskChecklist(
    override val id: String,
    override val title: String,
    val items: List<ChecklistItem>,
    override val status: ArtifactStatus = ArtifactStatus.IN_PROGRESS,
) : Artifact

/** Verifiable implementation plan artifact (Plan mode output). */
@Serializable
data class ImplementationPlan(
    override val id: String,
    override val title: String,
    val steps: List<PlanStepItem>,
    override val status: ArtifactStatus = ArtifactStatus.NEEDS_REVIEW,
    val summary: String = "",
) : Artifact

/** Verifiable post-change walkthrough artifact with evidence per step. */
@Serializable
data class Walkthrough(
    override val id: String,
    override val title: String,
    val steps: List<WalkthroughStep>,
    override val status: ArtifactStatus = ArtifactStatus.NEEDS_REVIEW,
    val summary: String = "",
) : Artifact

/** Verifiable code diff artifact (tap any hunk to leave an inline comment). */
@Serializable
data class Diff(
    override val id: String,
    override val title: String,
    val items: List<DiffHunkItem>,
    override val status: ArtifactStatus = ArtifactStatus.NEEDS_REVIEW,
) : Artifact

/**
 * Pure Markdown renderer for verifiable artifacts.
 * Deterministic and side-effect-free for both UI preview and transcript export.
 */
fun renderArtifactMarkdown(artifact: Artifact): String = buildString {
    append("# ").append(artifact.title).append('\n')
    append("_Status: ").append(artifact.status.name).append(" | ID: ").append(artifact.id).append("_\n\n")
    when (artifact) {
        is TaskChecklist -> {
            if (artifact.items.isEmpty()) {
                append("_No tasks recorded._\n")
            } else {
                for (item in artifact.items) {
                    val mark = when {
                        item.done || item.status == ChecklistItemStatus.DONE -> "x"
                        item.status == ChecklistItemStatus.IN_PROGRESS -> "~"
                        item.status == ChecklistItemStatus.SKIPPED -> "-"
                        else -> " "
                    }
                    append("- [").append(mark).append("] ").append(item.text)
                    append(" (`").append(item.id).append("`)\n")
                }
            }
        }
        is ImplementationPlan -> {
            if (artifact.summary.isNotBlank()) {
                append(artifact.summary.trim()).append("\n\n")
            }
            if (artifact.steps.isEmpty()) {
                append("_No steps defined._\n")
            } else {
                artifact.steps.forEachIndexed { index, step ->
                    val prefix = if (step.completed) "[x]" else "[ ]"
                    append("${index + 1}. $prefix **${step.title}** (`${step.id}`)\n")
                    if (step.detail.isNotBlank()) {
                        append("   ").append(step.detail.trim().replace("\n", "\n   ")).append('\n')
                    }
                }
            }
        }
        is Walkthrough -> {
            if (artifact.summary.isNotBlank()) {
                append(artifact.summary.trim()).append("\n\n")
            }
            if (artifact.steps.isEmpty()) {
                append("_No walkthrough steps recorded._\n")
            } else {
                artifact.steps.forEachIndexed { index, step ->
                    append("## ${index + 1}. ${step.title} (`${step.id}`)\n\n")
                    append(step.notes.trim()).append("\n\n")
                    if (step.evidence.isNotBlank()) {
                        append("```text\n")
                        append(step.evidence.trimEnd()).append("\n```\n\n")
                    }
                }
            }
        }
        is Diff -> {
            val totalAdded = artifact.items.sumOf { it.addedLines }
            val totalRemoved = artifact.items.sumOf { it.removedLines }
            append("Files changed: ${artifact.items.size} (+$totalAdded / -$totalRemoved)\n\n")
            if (artifact.items.isEmpty()) {
                append("_No diff hunks._\n")
            } else {
                for (item in artifact.items) {
                    append("### ").append(item.filePath)
                    append(" (+").append(item.addedLines).append(" / -").append(item.removedLines).append(")")
                    append(" `").append(item.id).append("`\n\n")
                    append("```diff\n")
                    append(item.unifiedDiff.trimEnd()).append("\n```\n\n")
                }
            }
        }
    }
}.trimEnd() + "\n"

/**
 * Inline comment pinned to a specific artifact + target item/step/hunk id.
 * Comments steer the agent live via [InlineCommentStore.pendingComments].
 */
@Serializable
data class InlineComment(
    val artifactId: String,
    val targetId: String,
    val text: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val id: String = "cmt_${UUID.randomUUID().toString().replace("-", "").take(10)}",
    val resolved: Boolean = false,
)

/**
 * Thread-scoped inline comment store backed by an in-memory map + one JSON
 * file per thread (mirroring the TodoStore persistence pattern).
 *
 * Call [configure] once at app startup (e.g., `File(context.filesDir, "inline_comments")`)
 * to enable disk persistence; without configuration, operates purely in memory.
 */
object InlineCommentStore {

    private val listSerializer = ListSerializer(InlineComment.serializer())
    private val cache = ConcurrentHashMap<String, List<InlineComment>>()
    private val lock = Any()

    @Volatile
    private var storageDir: File? = null

    /** Configures the directory for per-thread JSON persistence. */
    fun configure(dir: File) {
        synchronized(lock) {
            dir.mkdirs()
            storageDir = dir
            cache.clear()
        }
    }

    /** Adds a comment for [threadId], persisting immediately when configured. */
    fun addComment(threadId: String, comment: InlineComment): InlineComment {
        require(threadId.isNotBlank()) { "threadId must not be blank" }
        require(comment.text.isNotBlank()) { "comment text must not be blank" }
        synchronized(lock) {
            val current = loadLocked(threadId)
            val updated = current + comment
            cache[threadId] = updated
            saveLocked(threadId, updated)
            return comment
        }
    }

    /** Convenience overload creating and persisting an [InlineComment]. */
    fun addComment(
        threadId: String,
        artifactId: String,
        targetId: String,
        text: String,
        createdAtMillis: Long = System.currentTimeMillis(),
    ): InlineComment = addComment(
        threadId = threadId,
        comment = InlineComment(
            artifactId = artifactId,
            targetId = targetId,
            text = text.trim(),
            createdAtMillis = createdAtMillis,
        ),
    )

    /** Returns all unresolved comments for [threadId] in chronological order. */
    fun pendingComments(threadId: String): List<InlineComment> {
        synchronized(lock) {
            return loadLocked(threadId)
                .filter { !it.resolved }
                .sortedBy { it.createdAtMillis }
        }
    }

    /** Returns all comments (resolved and unresolved) for [threadId]. */
    fun allComments(threadId: String): List<InlineComment> {
        synchronized(lock) {
            return loadLocked(threadId).sortedBy { it.createdAtMillis }
        }
    }

    /** Marks a single comment as resolved. Returns true if found and updated. */
    fun resolveComment(threadId: String, commentId: String): Boolean {
        synchronized(lock) {
            val current = loadLocked(threadId)
            var found = false
            val updated = current.map { comment ->
                if (comment.id == commentId && !comment.resolved) {
                    found = true
                    comment.copy(resolved = true)
                } else {
                    comment
                }
            }
            if (found) {
                cache[threadId] = updated
                saveLocked(threadId, updated)
            }
            return found
        }
    }

    /** Marks all pending comments on [threadId] resolved; returns count resolved. */
    fun resolveAll(threadId: String): Int {
        synchronized(lock) {
            val current = loadLocked(threadId)
            val pendingCount = current.count { !it.resolved }
            if (pendingCount == 0) return 0
            val updated = current.map { if (it.resolved) it else it.copy(resolved = true) }
            cache[threadId] = updated
            saveLocked(threadId, updated)
            return pendingCount
        }
    }

    /**
     * Atomically retrieves all pending comments for [threadId] and marks them
     * resolved so they steer the agent exactly once.
     */
    fun consumePendingForSteering(threadId: String): List<InlineComment> {
        synchronized(lock) {
            val current = loadLocked(threadId)
            val pending = current.filter { !it.resolved }.sortedBy { it.createdAtMillis }
            if (pending.isEmpty()) return emptyList()
            val updated = current.map { if (it.resolved) it else it.copy(resolved = true) }
            cache[threadId] = updated
            saveLocked(threadId, updated)
            return pending
        }
    }

    /** Renders pending inline comments into a concise steering instruction for the agent. */
    fun formatForSteering(comments: List<InlineComment>): String {
        if (comments.isEmpty()) return ""
        return buildString {
            append("Apply the user's inline artifact review comments:\n")
            comments.forEachIndexed { idx, comment ->
                append("${idx + 1}. [artifact=${comment.artifactId} target=${comment.targetId}] ${comment.text}\n")
            }
        }.trimEnd()
    }

    /** Clears one thread or all threads (also removes persisted JSON files when configured). */
    fun clear(threadId: String? = null) {
        synchronized(lock) {
            if (threadId == null) {
                cache.clear()
                storageDir?.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                    ?.forEach { runCatching { it.delete() } }
            } else {
                cache.remove(threadId)
                fileFor(threadId)?.let { runCatching { if (it.exists()) it.delete() } }
            }
        }
    }

    /** Drops the in-memory cache without deleting files (useful for verifying disk reload). */
    internal fun evictMemoryCache() {
        synchronized(lock) {
            cache.clear()
        }
    }

    private fun loadLocked(threadId: String): List<InlineComment> {
        cache[threadId]?.let { return it }
        val file = fileFor(threadId) ?: return emptyList()
        if (!file.isFile) return emptyList()
        val parsed = runCatching {
            val raw = file.readText()
            if (raw.isBlank()) emptyList() else CodexJson.decodeFromString(listSerializer, raw)
        }.getOrDefault(emptyList())
        cache[threadId] = parsed
        return parsed
    }

    private fun saveLocked(threadId: String, comments: List<InlineComment>) {
        val file = fileFor(threadId) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(CodexJson.encodeToString(listSerializer, comments))
            if (!tmp.renameTo(file)) {
                file.writeText(CodexJson.encodeToString(listSerializer, comments))
                tmp.delete()
            }
        }
    }

    private fun fileFor(threadId: String): File? {
        val dir = storageDir ?: return null
        val safeName = threadId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "default" }
        return File(dir, "$safeName.json")
    }
}
