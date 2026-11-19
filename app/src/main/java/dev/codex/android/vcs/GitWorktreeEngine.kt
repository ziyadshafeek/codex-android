package dev.codex.android.vcs

import dev.codex.android.ci.GitHubActionsEngine
import dev.codex.android.linux.ProotShellEngine
import dev.codex.android.linux.ShellResult
import dev.codex.android.security.SecretScrubber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Real Git worktree orchestrator running inside the embedded Linux rootfs (Milestone M5).
 *
 * - Creates one isolated `git worktree add` checkout per parallel agent under
 *   `<repoDir.parent>/.codex-worktrees/<sanitized-branch>`.
 * - Keys worktrees strictly by canonical directory path (never collapsing checkouts
 *   by remote URL — fixes OpenCode #29869 / #50821).
 * - Uses an ephemeral `GIT_ASKPASS` helper script backed by the user's BYOK GitHub PAT
 *   supplied via [githubPatProvider] (from encrypted KeyStore); the script is deleted
 *   immediately in `finally`, the PAT is NEVER written to `.git/config`, and all command
 *   output passes through [SecretScrubber.scrub].
 *
 * Verified against the real repo (HEAD 1a1e8c7): `GitHubActionsEngine.createPr(owner,
 * repo, title, head, base, body: String? = null): PullRequest` with
 * `PullRequest(.number, .htmlUrl)` — the call in [openPr] matches exactly, no
 * adaptation was needed. Worktree operations run through [ProotShellEngine] (H2,
 * `dev.codex.android.linux`); this engine must not be constructed before H2 lands.
 */
class GitWorktreeEngine(
    private val proot: ProotShellEngine,
    val repoDir: File,
    private val githubPatProvider: () -> String? = { null },
    private val githubActionsEngine: GitHubActionsEngine? = null,
) {
    private val repoCanonical: File = repoDir.canonicalFile

    /** Directory containing sibling isolated worktrees for parallel agents. */
    fun worktreesBaseDir(): File = File(
        repoCanonical.parentFile ?: repoCanonical,
        ".codex-worktrees/${repoCanonical.name}",
    )

    /** Sanitises a Git branch name into a safe directory segment. */
    fun sanitizeBranchForDir(branch: String): String {
        val clean = branch.trim()
        require(clean.isNotEmpty() && !clean.startsWith("-") && !clean.contains("..")) {
            "Invalid git branch name: '$branch'"
        }
        return clean.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    /** Shell-quotes a single argument safely for POSIX `/bin/sh`. */
    internal fun shellQuote(arg: String): String =
        "'" + arg.replace("'", "'\"'\"'") + "'"

    /** Pure command builder for `git worktree add`. */
    internal fun buildAddWorktreeCommand(branch: String, worktreePath: File): String {
        val repoQ = shellQuote(repoCanonical.path)
        val wtQ = shellQuote(worktreePath.canonicalPath)
        val brQ = shellQuote(branch)
        return "git -C $repoQ worktree add -B $brQ $wtQ"
    }

    /** Pure command builder for computing a branch diff summary before handoff. */
    internal fun buildHandoffSummaryCommand(worktreeDir: File): String {
        val wtQ = shellQuote(worktreeDir.canonicalPath)
        return "git -C $wtQ status --short && echo '---STAT---' && git -C $wtQ diff --stat HEAD"
    }

    /** Pure command builder for merging a worktree's branch into the main checkout. */
    internal fun buildMergeCommand(branch: String): String {
        val repoQ = shellQuote(repoCanonical.path)
        val brQ = shellQuote(branch)
        return "git -C $repoQ merge --no-ff $brQ -m ${shellQuote("Merge worktree branch $branch")}"
    }

    /** Pure command builder for pushing a branch using ephemeral `GIT_ASKPASS`. */
    internal fun buildPushCommand(worktreeDir: File, branch: String): String {
        val wtQ = shellQuote(worktreeDir.canonicalPath)
        val brQ = shellQuote(branch)
        return "git -C $wtQ push -u origin $brQ"
    }

    /**
     * Parses `owner` and `repo` from a GitHub HTTPS or SSH remote URL.
     * Returns null when the remote is not a recognizable GitHub URL.
     */
    internal fun parseGitHubOwnerRepo(remoteUrl: String): Pair<String, String>? {
        val trimmed = remoteUrl.trim().removeSuffix(".git").removeSuffix("/")
        val httpsMatch = Regex("^https?://(?:[^@]+@)?github\\.com/([^/]+)/([^/]+)$").find(trimmed)
        if (httpsMatch != null) {
            return httpsMatch.groupValues[1] to httpsMatch.groupValues[2]
        }
        val sshMatch = Regex("^git@github\\.com:([^/]+)/([^/]+)$").find(trimmed)
        if (sshMatch != null) {
            return sshMatch.groupValues[1] to sshMatch.groupValues[2]
        }
        return null
    }

    /**
     * Builds the ephemeral `GIT_ASKPASS` script text.
     * The script reads the token strictly from the process environment variable
     * `CODEX_EPHEMERAL_GIT_TOKEN` (never hardcoded into the script file or `.git/config`!).
     */
    internal fun buildEphemeralAskpassScript(): String = """
        #!/bin/sh
        case "${'$'}1" in
          *Username*|*username*) printf '%s\n' 'x-access-token' ;;
          *) printf '%s\n' "${'$'}CODEX_EPHEMERAL_GIT_TOKEN" ;;
        esac
    """.trimIndent() + "\n"

    /**
     * Creates an isolated Git worktree for [branch] and returns its canonical directory.
     */
    suspend fun addWorktree(branch: String): File = withContext(Dispatchers.IO) {
        val dirName = sanitizeBranchForDir(branch)
        val targetDir = File(worktreesBaseDir(), dirName)
        targetDir.parentFile?.mkdirs()

        val cmd = buildAddWorktreeCommand(branch.trim(), targetDir)
        val result = proot.shellExec(cmd)
        if (result.exitCode != 0) {
            throw IllegalStateException(
                "git worktree add failed (exit ${result.exitCode}): ${SecretScrubber.scrub(result.stderr)}",
            )
        }
        targetDir.canonicalFile
    }

    /**
     * Inspects [worktreeDir] and returns a clean merge-readiness summary
     * (branch name, modified files, diffstat) ready for human review.
     */
    suspend fun handoff(worktreeDir: File): String = withContext(Dispatchers.IO) {
        val canonical = worktreeDir.canonicalFile
        val branch = resolveCurrentBranch(canonical)
        val summaryRes = proot.shellExec(buildHandoffSummaryCommand(canonical))
        val scrubbedOut = SecretScrubber.scrub(summaryRes.stdout.trim())
        val scrubbedErr = SecretScrubber.scrub(summaryRes.stderr.trim())
        buildString {
            appendLine("Worktree Handoff Summary")
            appendLine("Path: ${canonical.path}")
            appendLine("Branch: $branch")
            if (summaryRes.exitCode != 0) {
                appendLine("Warning (exit ${summaryRes.exitCode}): $scrubbedErr")
            }
            if (scrubbedOut.isBlank()) {
                appendLine("Working tree clean.")
            } else {
                appendLine(scrubbedOut)
            }
        }.trimEnd()
    }

    /**
     * Merges [worktreeDir]'s branch back into [repoDir] and returns the scrubbed merge output.
     */
    suspend fun merge(worktreeDir: File): String = withContext(Dispatchers.IO) {
        val canonical = worktreeDir.canonicalFile
        val branch = resolveCurrentBranch(canonical)
        val result = proot.shellExec(buildMergeCommand(branch))
        val output = SecretScrubber.scrub((result.stdout + "\n" + result.stderr).trim())
        if (result.exitCode != 0) {
            throw IllegalStateException("git merge failed (exit ${result.exitCode}): $output")
        }
        output.ifBlank { "Merged branch '$branch' into ${repoCanonical.name}." }
    }

    /**
     * Pushes [worktreeDir]'s branch using an ephemeral `GIT_ASKPASS` helper (never
     * logging or persisting the PAT), then opens a Pull Request via [GitHubActionsEngine].
     */
    suspend fun openPr(worktreeDir: File, title: String): String = withContext(Dispatchers.IO) {
        require(title.isNotBlank()) { "PR title must not be blank" }
        val canonical = worktreeDir.canonicalFile
        val branch = resolveCurrentBranch(canonical)
        val remoteRes = proot.shellExec("git -C ${shellQuote(canonical.path)} remote get-url origin")
        val remoteUrl = remoteRes.stdout.trim()
        val (owner, repo) = parseGitHubOwnerRepo(remoteUrl)
            ?: throw IllegalStateException("Could not parse GitHub owner/repo from origin remote.")

        val pushResult = runWithEphemeralAskpass(buildPushCommand(canonical, branch))
        if (pushResult.exitCode != 0) {
            throw IllegalStateException(
                "git push failed (exit ${pushResult.exitCode}): ${SecretScrubber.scrub(pushResult.stderr)}",
            )
        }

        val actions = githubActionsEngine ?: GitHubActionsEngine(tokenProvider = githubPatProvider)
        val baseBranch = resolveDefaultBaseBranch()
        // Verified: real signature is createPr(owner, repo, title, head, base,
        // body: String? = null): PullRequest — see ci/GitHubActionsEngine.kt.
        val pr = actions.createPr(
            owner = owner,
            repo = repo,
            title = title.trim(),
            head = branch,
            base = baseBranch,
            body = "Opened from Codex for Android worktree (`$branch`).",
        )
        "Opened PR #${pr.number}: ${pr.htmlUrl}"
    }

    /**
     * Executes [command] with an ephemeral `0700` `GIT_ASKPASS` script and passes the
     * BYOK PAT strictly via process environment. Deletes the script in `finally` and
     * scrubs any accidental token leak from stdout/stderr.
     */
    internal suspend fun runWithEphemeralAskpass(command: String): ShellResult {
        val pat = githubPatProvider()?.trim().orEmpty()
        if (pat.isEmpty()) {
            throw IllegalStateException("GitHub PAT missing in KeyStore (BYOK) — add one in Settings.")
        }
        val askpassDir = File(proot.rootfsDir, "tmp").apply { mkdirs() }
        val askpassFile = File.createTempFile("codex-askpass-", ".sh", askpassDir)
        try {
            askpassFile.writeText(buildEphemeralAskpassScript())
            askpassFile.setReadable(false, false)
            askpassFile.setReadable(true, true)
            askpassFile.setWritable(false, false)
            askpassFile.setWritable(true, true)
            askpassFile.setExecutable(false, false)
            askpassFile.setExecutable(true, true)

            val env = mapOf(
                "GIT_ASKPASS" to "/tmp/${askpassFile.name}",
                "GIT_TERMINAL_PROMPT" to "0",
                "CODEX_EPHEMERAL_GIT_TOKEN" to pat,
            )
            val raw = proot.shellExecWithEnv(command = command, extraEnv = env)
            return raw.copy(
                stdout = scrubPat(raw.stdout, pat),
                stderr = scrubPat(raw.stderr, pat),
            )
        } finally {
            runCatching { askpassFile.delete() }
        }
    }

    private fun scrubPat(text: String, pat: String): String {
        val withoutPat = if (pat.isNotBlank()) text.replace(pat, "[REDACTED]") else text
        return SecretScrubber.scrub(withoutPat)
    }

    private suspend fun resolveCurrentBranch(worktreeDir: File): String {
        val res = proot.shellExec("git -C ${shellQuote(worktreeDir.path)} rev-parse --abbrev-ref HEAD")
        val branch = res.stdout.trim()
        if (res.exitCode != 0 || branch.isBlank()) {
            throw IllegalStateException("Could not determine current branch for ${worktreeDir.path}")
        }
        return branch
    }

    private suspend fun resolveDefaultBaseBranch(): String {
        val res = proot.shellExec(
            "git -C ${shellQuote(repoCanonical.path)} symbolic-ref --short refs/remotes/origin/HEAD",
        )
        return res.stdout.trim().removePrefix("origin/").ifBlank { "main" }
    }
}
