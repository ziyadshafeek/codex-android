package dev.codex.android.tools

import dev.codex.android.ci.GitHubActionsEngine
import dev.codex.android.linux.ShellResult
import dev.codex.android.security.SecretScrubber
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Interactive coding tools. Never registered for unattended automation. */
internal fun ToolRegistry.registerDeveloperTools(
    workspace: WorkspaceFileTools,
    shell: suspend (String) -> ShellResult,
    github: GitHubActionsEngine,
    repository: () -> String?,
) {
    register(developerTool("workspace_list", "List the source files in the phone workspace.", false) {
        val files = workspace.listFiles().filter { runCatching { sourcePath(it) }.isSuccess }
        success(files.joinToString("\n").ifBlank { "Workspace is empty." })
    })
    register(developerTool("workspace_read", "Read a source file relative to the phone workspace.", false,
        "path" to "Relative file path") { args ->
        val path = sourcePath(args.text("path"))
        val file = workspace.regularSource(path)
        require(file.isFile && file.length() <= 150_000) { "File is missing or exceeds the read limit: $path" }
        success(workspace.readFile(path).take(20_000))
    })
    register(developerTool("workspace_write", "Create or replace a source file in the workspace. Use this for custom apps, not the template store.", true,
        "path" to "Relative file path", "content" to "Complete UTF-8 text to save") { args ->
        val content = args.text("content")
        val path = sourcePath(args.text("path"))
        val file = workspace.regularSource(path)
        require(content.length <= 150_000 && file.length() <= 150_000) { "File exceeds the tool's size limit" }
        require(SecretScrubber.scrub(content) == content) { "Do not put credentials in workspace source" }
        success(workspace.writeFile(path, content))
    })
    register(developerTool("shell_exec", "Run one approved command in the on-device Linux workspace; no Android Accessibility needed. Use GitHub Actions for Gradle builds.", true,
        "command" to "Exact shell command (working directory /workspace)") { args ->
        val command = args.text("command")
        require(validShellCommand(command)) { "Invalid or oversized command, or command contains a credential" }
        val result = shell(command)
        success("Exit code: ${result.exitCode}\n${result.stdout.takeLast(6000)}\n${result.stderr.takeLast(3000)}")
    })
    val destination = repository() ?: "the GitHub repository configured in Settings"
    register(developerTool("github_publish", "Publish only the listed workspace source files to $destination on a codex/<name> branch. Creates the branch if needed. Does not start a manual workflow.", true,
        "branch" to "codex/<name>", "paths" to "Newline-separated relative files to publish (up to 20)",
        "message" to "Commit message") { args ->
        val (owner, repo) = githubRepository(repository)
        val branch = args.text("branch")
        val paths = args.text("paths").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
        require(paths.size in 1..20) { "List between one and 20 source files" }
        val message = args.text("message").trim()
        require(message.length in 1..160 && SecretScrubber.scrub(message) == message) { "Invalid commit message" }
        val files = paths.associate { input ->
            val path = sourcePath(input)
            val file = workspace.regularSource(path)
            val canonicalPath = file.relativeTo(workspace.rootDir).invariantSeparatorsPath
            require(canonicalPath == path && file.isFile && file.length() <= 150_000) { "Missing, linked, or oversized source file: $path" }
            val content = file.readText()
            require('\u0000' !in content && SecretScrubber.scrub(content) == content) { "Refusing binary or credential-like content: $path" }
            path to content
        }
        val commit = github.publishFiles(owner, repo, branch, message, files)
        success("Published ${files.size} file(s) at https://github.com/$owner/$repo/commit/$commit. To build, dispatch a configured workflow or open a PR.")
    })
    register(developerTool("github_build", "Request a GitHub Actions build of a published branch in $destination. Workflow must support workflow_dispatch. Acceptance does not mean an APK exists.", true,
        "branch" to "Git branch already pushed", "workflow" to "Workflow filename, such as build.yml") { args ->
        val (owner, repo) = githubRepository(repository)
        val branch = gitBranch(args.text("branch"))
        val workflow = args.text("workflow")
        require(workflow.matches(Regex("[A-Za-z0-9_-]+\\.ya?ml"))) { "Supply a workflow filename like build.yml" }
        github.triggerWorkflow(owner, repo, workflow, branch)
        success("GitHub accepted the build request for $branch. Use github_build_status to check the outcome. https://github.com/$owner/$repo/actions/workflows/$workflow")
    })
    register(developerTool("github_build_status", "List recent GitHub Actions runs for a branch, with status and links to any APK artifacts. Use a run's link to see logs or download artifacts.", false,
        "branch" to "Git branch to look up") { args ->
        val (owner, repo) = githubRepository(repository)
        val branch = gitBranch(args.text("branch"))
        val runs = github.listRuns(owner, repo, branch, perPage = 4)
        val lines = mutableListOf<String>()
        for (run in runs) {
            val url = "https://github.com/$owner/$repo/actions/runs/${run.id}"
            lines += "${run.status}/${run.conclusion ?: "pending"} ${run.headSha.orEmpty().take(10)} $url"
            if (run.isSuccess) {
                for (artifact in github.listArtifacts(owner, repo, run.id).filterNot { it.expired }) {
                    lines += "${artifact.name}: $url/artifacts/${artifact.id}"
                }
            }
        }
        success(lines.joinToString("\n").ifBlank { "No runs found for $branch. A new dispatch may take a moment to appear." })
    })
}

internal fun validShellCommand(command: String): Boolean = command.isNotBlank() && command.length <= 2000 &&
    '\u0000' !in command && SecretScrubber.scrub(command) == command

internal fun validGitHubRepository(value: String): Boolean =
    value.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}")) &&
        value.substringAfter('/') !in setOf(".", "..")

private fun githubRepository(provider: () -> String?): List<String> {
    val repo = provider().orEmpty()
    require(validGitHubRepository(repo)) { "Set the target GitHub repository in Codex Settings first." }
    return repo.split('/')
}

private fun gitBranch(branch: String): String {
    require(branch.length in 1..120 && branch.split('/').all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) } &&
        ".." !in branch && !branch.endsWith('.')) { "Invalid Git branch" }
    return branch
}

private fun sourcePath(path: String): String {
    require(path.length in 1..240) { "Use a regular relative source path (no hidden files, keys or private configuration)" }
    val parts = path.split('/')
    // Workflow files are CI config, not secrets: allow exactly
    // `.github/workflows/<name>.yml|yaml` so the agent can publish a build
    // workflow. Every other hidden path stays blocked.
    val isWorkflowFile = parts.size == 3 && parts[0] == ".github" && parts[1] == "workflows" &&
        parts[2].matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*\\.(yml|yaml)"))
    if (!isWorkflowFile) {
        require(parts.all { part ->
            part.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")) &&
                part.lowercase() !in setOf("local.properties", "gradle.properties", "key.properties", "secrets.properties") &&
                !part.lowercase().endsWith(".keystore") && !part.lowercase().endsWith(".jks")
        }) { "Use a regular relative source path (no hidden files, keys or private configuration)" }
    }
    return path
}

private fun WorkspaceFileTools.regularSource(path: String): File {
    var candidate = rootDir
    for (name in path.split('/')) {
        candidate = File(candidate, name)
        require(!Files.isSymbolicLink(candidate.toPath())) { "Linked source paths are not allowed: $path" }
    }
    return resolveSafe(path)
}

private fun JsonObject.text(name: String): String = (this[name] as? JsonPrimitive)
    ?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("Missing text argument: $name")

private fun success(text: String) = ToolResult(true, SecretScrubber.scrub(text))

private fun developerTool(
    id: String, help: String, approval: Boolean, vararg fields: Pair<String, String>,
    run: suspend (JsonObject) -> ToolResult,
): Tool = object : Tool {
    override val name = id
    override val description = help
    override val sensitive = approval
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            fields.forEach { (key, doc) -> putJsonObject(key) { put("type", "string"); put("description", doc) } }
        }
        putJsonArray("required") { fields.forEach { add(JsonPrimitive(it.first)) } }
    }
    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            run(args)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(false, "", SecretScrubber.scrub(e.message ?: "Tool failed"))
        }
    }
}
