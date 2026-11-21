package dev.codex.android.instructions

import java.io.File

/**
 * One repo instruction file found under a workspace/repo root.
 *
 * @property relativePath path relative to the workspace root, '/' separated
 *   (e.g. `AGENTS.md`, `my-repo/AGENTS.md`).
 * @property content the file's full text (possibly truncated at the cap —
 *   see [RepoInstructions.truncated]).
 */
data class RepoInstructionFile(
    val relativePath: String,
    val content: String,
)

/**
 * Repo instruction files auto-discovered for a workspace/repo (OpenCode
 * gap: the `/init` command writes `AGENTS.md` and claims "Codex will read
 * AGENTS.md at the start of every run in this workspace", but nothing in
 * the app ever read it — see `commands/CommandHandlers.kt`).
 *
 * Discovery rules:
 * - Candidates, in priority order: `AGENTS.md`, then `CLAUDE.md`. No
 *   `.codex/AGENTS.md` / `.agent/AGENTS.md` convention exists anywhere in
 *   this codebase, so none is added.
 * - Search starts at [repoRoot] and walks up parent directories to
 *   [workspaceRoot] (inclusive), so a repo cloned inside the workspace
 *   picks up both its own instructions and the workspace's. Nothing above
 *   the workspace root is ever read.
 * - Symlinks are never followed outside the workspace or the repo
 *   root: a candidate whose canonical path escapes both is skipped.
 * - Missing or unreadable files are skipped silently — an empty result,
 *   never an error.
 * - Combined content is capped at [MAX_TOTAL_CHARS]; the tail is cut and
 *   [truncated] is set, with a note in [toAgentContext] telling the agent
 *   to read the files directly for the full text.
 *
 * Pure JVM: no Android APIs, no coroutines — safe to call from a
 * background thread at run start and from unit tests.
 *
 * CALL SITES (integrator — do NOT call from here):
 * - Run start: `ui/ChatViewModel.kt`, `runAgentTurn`, where
 *   `sessionInstructions` is built for `AgentLoop` — prepend
 *   `RepoInstructions.load(workspaceFileTools.rootDir).toAgentContext()`.
 * - After a checkout: `vcs/GitWorktreeEngine.kt`, `addWorktree` — load
 *   with `RepoInstructions.load(targetDir, repoDir)` once the worktree
 *   exists, so the caller can surface the instructions for the new
 *   checkout (there is currently no `git clone` path in the app; the
 *   worktree is the only repo-checkout entry point).
 */
data class RepoInstructions(
    /** Loaded files, highest priority first (repo-local before workspace-level). */
    val files: List<RepoInstructionFile>,
    /** True when the combined content was cut at [MAX_TOTAL_CHARS]. */
    val truncated: Boolean,
    /** Total characters across all found files, before truncation. */
    val totalChars: Int,
) {
    fun isEmpty(): Boolean = files.isEmpty()

    /**
     * Block to inject into the agent's system prompt / session
     * instructions. Empty string when no instruction file was found, so
     * the caller can append it unconditionally.
     */
    fun toAgentContext(): String {
        if (files.isEmpty()) return ""
        return buildString {
            appendLine("[Repository Instructions]")
            appendLine("The following repo instruction files apply to this workspace, highest priority first:")
            for (file in files) appendLine("- ${file.relativePath} (${file.content.length} chars)")
            if (truncated) {
                appendLine(
                    "Note: combined instructions were truncated to $MAX_TOTAL_CHARS characters; " +
                        "read the files directly (workspace_read) for the full text.",
                )
            }
            appendLine()
            for (file in files) {
                appendLine("--- ${file.relativePath} ---")
                appendLine(file.content)
            }
            append("[End Repository Instructions]")
        }
    }

    companion object {
        /** Cap on combined instruction characters injected into a run. */
        const val MAX_TOTAL_CHARS = 20_000

        /** Guard against pathological parent chains; real workspaces are far shallower. */
        private const val MAX_WALK_UP_LEVELS = 16

        /** Candidate file names, in priority order, per directory level. */
        private val CANDIDATE_NAMES = listOf("AGENTS.md", "CLAUDE.md")

        /**
         * Loads instruction files for [repoRoot], walking up to
         * [workspaceRoot] (inclusive). [repoRoot] defaults to
         * [workspaceRoot] for a flat workspace. Returns an empty
         * [RepoInstructions] (never throws) when nothing is found.
         */
        fun load(repoRoot: File, workspaceRoot: File = repoRoot): RepoInstructions {
            val workspaceCanonical = runCatching { workspaceRoot.canonicalFile }.getOrNull()
                ?: return RepoInstructions(emptyList(), truncated = false, totalChars = 0)
            val start = runCatching { repoRoot.canonicalFile }.getOrNull()
                ?: return RepoInstructions(emptyList(), truncated = false, totalChars = 0)

            // Keyed by canonical path so the same file is never loaded
            // twice (e.g. repoRoot == workspaceRoot, or overlapping levels).
            val found = LinkedHashMap<String, RepoInstructionFile>()
            var dir: File? = start
            var levels = 0
            while (dir != null && levels <= MAX_WALK_UP_LEVELS) {
                val insideWorkspace = dir == workspaceCanonical ||
                    dir.path.startsWith(workspaceCanonical.path + File.separator)
                // The repo root itself is always searched, even if it sits
                // outside the workspace; parents are searched only while
                // they stay inside the workspace.
                if (dir != start && !insideWorkspace) break
                for (name in CANDIDATE_NAMES) {
                    val candidate = File(dir, name)
                    if (!candidate.isFile) continue
                    val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: continue
                    // Never follow a symlink outside the workspace or the
                    // repo root itself: a repo that sits outside the
                    // workspace still contributes its own files, but a
                    // link pointing elsewhere is skipped.
                    val underRoot = { root: File ->
                        canonical == root || canonical.path.startsWith(root.path + File.separator)
                    }
                    if (!underRoot(workspaceCanonical) && !underRoot(start)) continue
                    if (found.containsKey(canonical.path)) continue
                    val content = runCatching { candidate.readText() }.getOrNull() ?: continue
                    val relative = runCatching {
                        canonical.relativeTo(workspaceCanonical).path.replace(File.separatorChar, '/')
                    }.getOrElse {
                        // Repo outside the workspace: relativize against the repo root.
                        runCatching {
                            canonical.relativeTo(start).path.replace(File.separatorChar, '/')
                        }.getOrElse { canonical.path }
                    }
                    found[canonical.path] = RepoInstructionFile(relative, content)
                }
                if (dir == workspaceCanonical) break
                dir = dir.parentFile
                levels++
            }

            val totalChars = found.values.sumOf { it.content.length }
            var remaining = MAX_TOTAL_CHARS
            var truncated = false
            val kept = mutableListOf<RepoInstructionFile>()
            for (file in found.values) {
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                if (file.content.length <= remaining) {
                    kept += file
                    remaining -= file.content.length
                } else {
                    kept += file.copy(content = file.content.take(remaining))
                    remaining = 0
                    truncated = true
                }
            }
            return RepoInstructions(kept, truncated, totalChars)
        }
    }
}
