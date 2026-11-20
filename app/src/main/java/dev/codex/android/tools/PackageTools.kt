package dev.codex.android.tools

import dev.codex.android.artifacts.downloadIndex
import dev.codex.android.artifacts.resolveWorkspaceDownload
import dev.codex.android.attachments.FileLibrary
import dev.codex.android.linux.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Delete control for the Linux workspace, the phone workspace, staged
 * attachments and workspace downloads.
 *
 * Nothing in this file deletes silently:
 * - The agent tools ([UninstallPackageTool], [ClearWorkspaceTool]) refuse
 *   to run unless their arguments carry `confirmed: true` — the agent must
 *   have asked the user first (they are also `sensitive = true`, so the
 *   policy gate asks the user before the tool even runs).
 * - The Storage screen puts its own confirmation dialog in front of every
 *   call into [WorkspaceCleaner] / [LinuxPackageService] /
 *   [dev.codex.android.linux.RootfsManager.wipeRootfs].
 * - Package names are validated against [LinuxPackageManager.isValidPackageName]
 *   before they are ever interpolated into an `apk` command, so a crafted
 *   name cannot smuggle shell metacharacters (`;`, `&&`, `$()`, …) in.
 *
 * Pure helpers ([LinuxPackageManager], [WorkspaceCleaner]) are JVM-testable
 * with a fake shell / temp dirs; see `PackageToolsTest`.
 */

/** One Alpine package as reported by `apk list --installed`. */
data class InstalledPackage(val name: String, val version: String)

/** Pure parsing/validation for `apk` output. No I/O, no shell. */
object LinuxPackageManager {

    /**
     * Alpine package names: lowercase letters, digits, `.`, `_`, `+`, `-`,
     * starting with a letter or digit. Anything else (spaces, `/`, `;`,
     * `$`, quotes, `..`) is rejected so the name is safe to interpolate
     * into `apk del <name>`.
     */
    private val PACKAGE_NAME_REGEX = Regex("^[a-z0-9][a-z0-9._+-]*$")

    fun isValidPackageName(name: String): Boolean =
        name.length in 1..128 && PACKAGE_NAME_REGEX.matches(name)

    /**
     * Parses `apk list --installed` output. Each line starts with
     * `<name>-<version>` (e.g. `curl-8.5.0-r0 x86_64 {curl} (MIT)
     * [installed]`). The split is the FIRST `-` whose remainder looks
     * like a version (`<digits>[...]` with an optional `-r<number>`
     * suffix) — a plain "last dash" split misreads versions (`8.5.0-r0`
     * would split as name `curl-8.5`, version `0-r0`), and a plain
     * "first dash followed by a digit" misreads digit-bearing names
     * (`sdl2-2.0.20-r0` would split as name `sdl`). Blank lines and
     * unparseable lines are skipped, never faked.
     */
    fun parseInstalledPackages(apkListOutput: String): List<InstalledPackage> {
        val result = mutableListOf<InstalledPackage>()
        for (rawLine in apkListOutput.lines()) {
            val firstToken = rawLine.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            if (firstToken.isEmpty()) continue
            parseNameVersion(firstToken)?.let { result += it }
        }
        return result.sortedBy { it.name }
    }

    private val VERSION_REGEX = Regex("^[0-9][0-9A-Za-z._+]*(-r[0-9]+)?$")

    /** Splits one `<name>-<version>` token, or null when it is not one. */
    internal fun parseNameVersion(token: String): InstalledPackage? {
        var dash = token.indexOf('-')
        while (dash in 1 until token.length) {
            val name = token.substring(0, dash)
            val version = token.substring(dash + 1)
            if (isValidPackageName(name) && VERSION_REGEX.matches(version)) {
                return InstalledPackage(name, version)
            }
            dash = token.indexOf('-', dash + 1)
        }
        return null
    }

    /**
     * `apk info -e <package>` exits 0 exactly when the package is
     * installed, non-zero when it is not. The exit code — not the output
     * text — is the verdict.
     */
    fun isInstalled(apkInfoResult: ShellResult): Boolean = apkInfoResult.exitCode == 0
}

/**
 * Lists / uninstalls Alpine packages through the app's existing shell
 * path ([shell] is `ProotShellEngine.shellExec`, or the ChatViewModel shell
 * lambda that also ensures the rootfs first). The shell itself is never
 * modified here.
 */
class LinuxPackageService(
    private val shell: suspend (String) -> ShellResult,
) {
    /** Runs `apk list --installed`; failure carries the shell's stderr. */
    suspend fun listInstalled(): Result<List<InstalledPackage>> {
        val result = shell("apk list --installed")
        if (result.exitCode != 0) {
            val detail = result.stderr.trim().ifEmpty { "apk list failed (exit ${result.exitCode})" }
            return Result.failure(IllegalStateException("Could not list installed packages: $detail"))
        }
        return Result.success(LinuxPackageManager.parseInstalledPackages(result.stdout))
    }

    /**
     * Runs `apk del <name>`, then verifies with `apk info -e <name>`.
     * Success is reported only when the verify step says the package is
     * really gone; if `apk del` succeeds but the package is still listed,
     * that is a failure, not a success.
     */
    suspend fun uninstall(name: String): Result<String> {
        if (!LinuxPackageManager.isValidPackageName(name)) {
            return Result.failure(IllegalArgumentException("Not a valid package name: \"$name\""))
        }
        val del = shell("apk del $name")
        if (del.exitCode != 0) {
            val detail = del.stderr.trim().ifEmpty { "apk del failed (exit ${del.exitCode})" }
            return Result.failure(IllegalStateException("Could not uninstall $name: $detail"))
        }
        val verify = shell("apk info -e $name")
        if (LinuxPackageManager.isInstalled(verify)) {
            return Result.failure(
                IllegalStateException("apk del reported success but $name is still installed."),
            )
        }
        return Result.success("Uninstalled $name. Verified: it is no longer installed.")
    }
}

/** Pure file-deletion helpers for the workspace, attachments and downloads. */
object WorkspaceCleaner {

    /** Counts regular files under [root] (symlinks not followed). */
    fun countFiles(root: File): Int {
        if (!root.isDirectory) return 0
        var count = 0
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) count++
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
        })
        return count
    }

    /** Total bytes of regular files under [root] (symlinks not followed). */
    fun totalBytes(root: File): Long {
        if (!root.isDirectory) return 0L
        var sum = 0L
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) sum += attrs.size()
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
        })
        return sum
    }

    /**
     * Deletes everything under [root] except top-level directories named in
     * [keepTopLevel] (their whole subtree is kept). The root directory
     * itself is always kept. Symlinks are deleted as links, never followed.
     * Returns the number of regular files removed.
     */
    fun clearDirectoryContents(root: File, keepTopLevel: Set<String> = emptySet()): Int {
        if (!root.isDirectory) return 0
        var removed = 0
        val rootPath = root.toPath()
        Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != rootPath && dir.parent == rootPath && dir.fileName.toString() in keepTopLevel) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Delete the link itself when it is a symlink; never follow it.
                if (Files.deleteIfExists(file)) removed++
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (dir != rootPath) Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
        })
        return removed
    }

    /**
     * Deletes ALL workspace files, keeping the workspace root directory
     * itself. The `.attachments` subtree is kept: staged attachments are
     * managed files owned by [FileLibrary] and have their own
     * [clearAllAttachments] path — clearing the workspace must not silently
     * wipe them as a side effect. Generated store source
     * (`workspace/generated/<appId>`) IS deleted here; deleting a store app
     * properly (listing + source) is
     * [dev.codex.android.store.deleteGeneratedAppSource] /
     * [dev.codex.android.store.StoreCatalog.deleteListingAndSource].
     */
    fun clearWorkspaceContents(workspace: WorkspaceFileTools): Int =
        clearDirectoryContents(workspace.rootDir, keepTopLevel = setOf(".attachments"))

    /**
     * Deletes every downloadable workspace file (the same set the
     * workspace-downloads UI lists, via [downloadIndex]) using the existing
     * download-delete path ([resolveWorkspaceDownload] + delete). Returns
     * the number of files removed. Credential-named / hidden files are
     * never in the index, so they are never touched here.
     */
    fun clearDownloads(workspace: WorkspaceFileTools): Int {
        var removed = 0
        for (path in downloadIndex(workspace).keys) {
            val file = runCatching { resolveWorkspaceDownload(workspace, path) }.getOrNull() ?: continue
            if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                Files.deleteIfExists(file.toPath())
            ) removed++
        }
        return removed
    }

    /**
     * Deletes every staged attachment (drafts + per-thread) and every saved
     * library file, reusing [FileLibrary.delete] per entry — the same call
     * the File Library screen's per-file Delete button uses. Returns the
     * number of entries removed. Must be called off the main thread.
     */
    internal suspend fun clearAllAttachments(library: FileLibrary): Int = withContext(Dispatchers.IO) {
        val entries = library.staged() + library.list("library")
        var removed = 0
        for (entry in entries) {
            if (runCatching { library.delete(entry) }.isSuccess) removed++
        }
        removed
    }
}

/**
 * Agent tool: uninstall one Alpine package from the Linux workspace.
 * Sensitive: the policy gate asks the user before it runs, AND the tool
 * itself refuses unless the arguments carry `confirmed: true` — the model
 * must have asked the user in chat first. Never deletes without both.
 */
class UninstallPackageTool(
    private val service: LinuxPackageService,
) : Tool {
    override val name: String = "uninstall_package"
    override val description: String =
        "Uninstalls one Alpine Linux package from the Linux workspace (apk del) and verifies it is gone. " +
            "Ask the user to confirm first and pass confirmed=true only after they say yes."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("package") {
                put("type", "string")
                put("description", "Exact Alpine package name to uninstall, e.g. \"curl\"")
            }
            putJsonObject("confirmed") {
                put("type", "boolean")
                put("description", "Must be true. The user must have explicitly confirmed this uninstall in chat.")
            }
        }
        putJsonArray("required") {
            add(kotlinx.serialization.json.JsonPrimitive("package"))
            add(kotlinx.serialization.json.JsonPrimitive("confirmed"))
        }
    }
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val confirmed = args["confirmed"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        if (!confirmed) {
            return ToolResult(
                ok = false,
                output = "",
                error = "Refused: uninstall_package needs confirmed=true. Ask the user to confirm the uninstall first, then call again with confirmed=true.",
            )
        }
        val name = args["package"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (name.isEmpty()) return ToolResult(ok = false, output = "", error = "Missing required argument: package")
        return service.uninstall(name).fold(
            onSuccess = { ToolResult(ok = true, output = it) },
            onFailure = { ToolResult(ok = false, output = "", error = it.message ?: "Uninstall failed") },
        )
    }
}

/**
 * Agent tool: clear workspace files, staged attachments, or workspace
 * downloads — one scope per call. Sensitive, and refuses unless the
 * arguments carry `confirmed: true` (user must have confirmed in chat).
 * The workspace scope keeps the workspace root directory and the
 * `.attachments` subtree (attachments have their own scope).
 *
 * Internal (not public) because it takes the module-internal
 * [FileLibrary]; registered through [registerPackageTools].
 */
internal class ClearWorkspaceTool(
    private val workspace: WorkspaceFileTools,
    /** Null when no attachment store is wired: the attachments scope then fails honestly instead of faking it. */
    private val fileLibrary: FileLibrary?,
) : Tool {
    override val name: String = "clear_workspace"
    override val description: String =
        "Deletes files in one scope: \"workspace\" (all workspace files, keeps the workspace folder and staged attachments), " +
            "\"attachments\" (all staged attachments and saved library files), or \"downloads\" (all downloadable workspace files). " +
            "Ask the user to confirm first and pass confirmed=true only after they say yes."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("scope") {
                put("type", "string")
                put("description", "What to delete: \"workspace\", \"attachments\", or \"downloads\"")
                putJsonArray("enum") {
                    add(kotlinx.serialization.json.JsonPrimitive("workspace"))
                    add(kotlinx.serialization.json.JsonPrimitive("attachments"))
                    add(kotlinx.serialization.json.JsonPrimitive("downloads"))
                }
            }
            putJsonObject("confirmed") {
                put("type", "boolean")
                put("description", "Must be true. The user must have explicitly confirmed this deletion in chat.")
            }
        }
        putJsonArray("required") {
            add(kotlinx.serialization.json.JsonPrimitive("scope"))
            add(kotlinx.serialization.json.JsonPrimitive("confirmed"))
        }
    }
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val confirmed = args["confirmed"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        if (!confirmed) {
            return ToolResult(
                ok = false,
                output = "",
                error = "Refused: clear_workspace needs confirmed=true. Ask the user to confirm the deletion first, then call again with confirmed=true.",
            )
        }
        val scope = args["scope"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return try {
            when (scope) {
                "workspace" -> {
                    val removed = withContext(Dispatchers.IO) {
                        WorkspaceCleaner.clearWorkspaceContents(workspace)
                    }
                    ToolResult(ok = true, output = "Cleared the workspace: removed $removed file(s). The workspace folder itself was kept.")
                }
                "attachments" -> {
                    val library = fileLibrary
                        ?: return ToolResult(ok = false, output = "", error = "Attachments are not available here (no file library wired).")
                    val removed = WorkspaceCleaner.clearAllAttachments(library)
                    ToolResult(ok = true, output = "Cleared attachments: removed $removed file(s) (staged + saved library).")
                }
                "downloads" -> {
                    val removed = withContext(Dispatchers.IO) {
                        WorkspaceCleaner.clearDownloads(workspace)
                    }
                    ToolResult(ok = true, output = "Cleared downloads: removed $removed file(s).")
                }
                else -> ToolResult(ok = false, output = "", error = "Unknown scope \"$scope\" — use workspace, attachments, or downloads")
            }
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "Clear failed")
        }
    }
}

/**
 * Registers the delete-control tools. Wired by the coordinator next to
 * [registerDeveloperTools] (same pattern as [registerBuilderTools]):
 * this file only defines the tools, it does not wire itself into any
 * registry and does not touch [ToolRegistry.withBuiltins].
 *
 * Coordinator line (ChatViewModel.policyGatedRegistry, after the
 * `registerDeveloperTools(...)` call):
 * ```
 * interactive.registerPackageTools(workspaceFileTools, fileLibrary, shell = { command -> ... })
 * ```
 * — but see the report: the `shell` lambda there is the local `shell`
 * parameter of `registerDeveloperTools`, so the exact line depends on the
 * coordinator's chosen seam; the function below takes the same
 * `suspend (String) -> ShellResult` the developer tools already use.
 */
internal fun ToolRegistry.registerPackageTools(
    workspace: WorkspaceFileTools,
    fileLibrary: FileLibrary,
    shell: suspend (String) -> ShellResult,
) {
    register(UninstallPackageTool(LinuxPackageService(shell)))
    register(ClearWorkspaceTool(workspace, fileLibrary))
}
