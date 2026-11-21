package dev.codex.android.tools

import dev.codex.android.linux.BuilderToolchainStaging
import dev.codex.android.linux.ShellResult
import dev.codex.android.skills.ActiveSkillScope
import dev.codex.android.skills.Skill
import dev.codex.android.skills.SkillTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Builder tools for the Codex agent (skills: mcp-builder, skill-creator,
 * webapp-testing, web-artifacts-builder, firebase, mobile-ads).
 *
 * Registered by the coordinator next to [registerDeveloperTools] — this
 * file only defines the tools; it does not wire itself into any
 * registry, and it does not touch [ToolRegistry.withBuiltins],
 * [dev.codex.android.skills.SkillStore], or ChatViewModel.
 *
 * Tools:
 * - `mcp_scaffold` — scaffold a minimal stdio MCP server (Python /
 *   TypeScript, no dependencies) in the workspace and register it with
 *   the app's MCP config when a hub seam is wired.
 * - `skill_eval` — run a skill's test cases through the real `skill`
 *   tool path and report pass/fail per case.
 * - `webapp_test` — run a Playwright test script against a URL (or a
 *   local web artifact) in the Linux workspace; falls back to the
 *   VerificationBrowser seam when Playwright cannot be staged, and says
 *   so plainly.
 * - `webapp_build` — scaffold a React + Tailwind app and run
 *   `npm install` + `npm run build` in the Linux workspace.
 *
 * Seams (all supplied by the coordinator; nullable means "not wired"):
 * - [ensureToolchain]: `RootfsManager.ensureToolchain` — stages
 *   `node` / `python` / Playwright / firebase-tools inside the rootfs.
 *   When null, the tools fail honestly instead of faking a build.
 * - [registerStdioServer]: `McpHub.addStdioServer(name, command, args)`
 *   returning the server id. When null, `mcp_scaffold` writes the files
 *   and reports the manual registration step.
 * - [browserCheck]: VerificationBrowser-backed check of a URL against
 *   [WebCheck]s, returning a [WebAppTestResult]. Used only when
 *   Playwright is unavailable.
 *
 * Reference skills that informed these tools (Apache 2.0, adapted with
 * fresh instructions written for this app, not copied): anthropics
 * mcp-builder / skill-creator / webapp-testing / web-artifacts-builder,
 * composio mcp-builder / skill-creator / webapp-testing, google
 * firebase-basics. See `skills/BuilderSkills.kt`.
 */
internal fun ToolRegistry.registerBuilderTools(
    workspace: WorkspaceFileTools,
    shell: suspend (String) -> ShellResult,
    skillsProvider: () -> List<Skill>,
    ensureToolchain: (suspend (String) -> Boolean)? = null,
    registerStdioServer: (suspend (name: String, command: String, args: List<String>) -> String)? = null,
    browserCheck: (suspend (url: String, checks: List<WebCheck>) -> WebAppTestResult)? = null,
) {
    register(
        builderTool(
            name = "mcp_scaffold",
            description = "Scaffold a minimal MCP server (Python or TypeScript, stdio JSON-RPC, no dependencies) in the workspace under mcp-servers/<name>/, and register it as a stdio MCP server when a hub is wired.",
            sensitive = true,
            parametersSchema = objectSchema(
                "name" to stringParam("Server name, e.g. notes-server."),
                "language" to stringParam("python or typescript (default python)."),
                "register" to booleanParam("Register the server in the app MCP config when possible (default true)."),
                required = listOf("name"),
            ),
        ) { args ->
            val name = args.text("name")?.trim()
                ?: return@builderTool ToolResult(false, "", "Missing required argument: name.")
            val language = args.text("language")?.trim()?.lowercase().ifNullOrBlank("python")
            McpScaffold.validate(name, language)?.let { error ->
                return@builderTool ToolResult(false, "", error)
            }
            val dir = "mcp-servers/$name"
            if (File(workspace.rootDir, dir).exists()) {
                return@builderTool ToolResult(
                    false,
                    "",
                    "Directory $dir already exists. Refusing to overwrite an existing server — " +
                        "pick a different name or delete it first.",
                )
            }
            val written = withContext(Dispatchers.IO) {
                McpScaffold.scaffoldFiles(name, language).map { file ->
                    workspace.writeFile("$dir/${file.relativePath}", file.content)
                }
            }
            val (command, cmdArgs) = McpScaffold.serverCommand(language, "/workspace/$dir")
            val register = args.boolean("register", default = true)
            val registrationNote = when {
                !register -> "Not registered in the MCP config (register=false)."
                registerStdioServer != null -> {
                    val id = runCatching { registerStdioServer(name, command, cmdArgs) }.getOrNull()
                    if (id != null) {
                        "Registered as stdio MCP server (id $id). Note: live stdio calls inside the app " +
                            "need the stdio transport, which is not built yet — until then run it standalone: " +
                            "$command ${cmdArgs.joinToString(" ")}."
                    } else {
                        "Registration failed: the MCP hub refused the server. The files are still written; " +
                            "add it manually as a stdio server with command '$command' and args '${cmdArgs.joinToString(" ")}'."
                    }
                }
                else -> "Not registered in the app MCP config (no MCP hub wired). Manual step: add a stdio " +
                    "server with command '$command' and args '${cmdArgs.joinToString(" ")}'."
            }
            ToolResult(
                ok = true,
                output = "Scaffolded MCP server '$name' ($language) in $dir:\n" +
                    written.joinToString("\n") + "\n$registrationNote",
            )
        },
    )
    register(
        builderTool(
            name = "skill_eval",
            description = "Run a skill's test cases through the real skill tool path and report pass/fail per case with a summary. Each case is {name, input, expectedTools?, expectedOutputSubstring?, forbiddenTools?}. Static eval: it checks the skill definition (instructions + tool list), not a live model turn.",
            sensitive = false,
            parametersSchema = objectSchema(
                "skill_id" to stringParam("Id of the skill to evaluate, e.g. mcp-builder."),
                "cases" to stringParam("JSON array of test cases: {name, input, expectedTools?, expectedOutputSubstring?, forbiddenTools?}."),
                required = listOf("skill_id", "cases"),
            ),
        ) { args ->
            val skillId = args.text("skill_id")?.trim()
                ?: return@builderTool ToolResult(false, "", "Missing required argument: skill_id.")
            val casesJson = args.text("cases")
                ?: return@builderTool ToolResult(false, "", "Missing required argument: cases.")
            val cases = try {
                SkillEval.parseCases(casesJson)
            } catch (e: IllegalArgumentException) {
                return@builderTool ToolResult(false, "", e.message ?: "Invalid cases JSON.")
            }
            val skillTool = SkillTool(skillsProvider, ActiveSkillScope())
            val report = SkillEval.run(skillTool, skillId, cases)
            if (report.skillError != null) {
                return@builderTool ToolResult(false, "", report.skillError)
            }
            ToolResult(ok = true, output = report.format())
        },
    )
    register(
        builderTool(
            name = "webapp_test",
            description = "Test a web page with Playwright in the Linux workspace: give a URL and optional checks [{name, selector, expectText?}], or a raw Playwright script. Returns pass/fail per check plus the failing assertion. When Playwright is not staged it falls back to the VerificationBrowser check and says so plainly.",
            sensitive = true,
            parametersSchema = objectSchema(
                "url" to stringParam("http(s):// or file:// URL of the page to test."),
                "checks" to stringParam("Optional JSON array of checks: {name, selector, expectText?}. When empty, only the page load + console errors are checked."),
                "script" to stringParam("Optional raw Node Playwright script to run instead of the generated one. It must print a WEBAPP_TEST_RESULT {...} line."),
                required = listOf("url"),
            ),
        ) { args ->
            val url = args.text("url")?.trim()
                ?: return@builderTool ToolResult(false, "", "Missing required argument: url.")
            if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")) {
                return@builderTool ToolResult(
                    false,
                    "",
                    "url must start with http://, https:// or file:// — got '$url'.",
                )
            }
            val checks = try {
                WebAppTesting.parseChecks(args.text("checks").orEmpty())
            } catch (e: IllegalArgumentException) {
                return@builderTool ToolResult(false, "", e.message ?: "Invalid checks JSON.")
            }
            val scriptPath = "webapp-tests/${WebAppTesting.scriptFileName(url)}"
            val script = args.text("script")?.takeIf { it.isNotBlank() }
                ?: WebAppTesting.buildPlaywrightScript(url, checks)
            withContext(Dispatchers.IO) { workspace.writeFile(scriptPath, script) }
            val runCommand =
                "PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium-browser " +
                    "node /workspace/$scriptPath ${shellQuote(url)}"

            suspend fun runPlaywright(): WebAppTestResult {
                val result = shell(runCommand)
                return WebAppTesting.parsePlaywrightOutput(
                    result.exitCode,
                    result.stdout + "\n" + result.stderr,
                )
            }

            var result = runPlaywright()
            if (!result.ok && WebAppTesting.isPlaywrightMissing(result.failures.joinToString("\n"))) {
                // Playwright is not installed: try staging it once, then retry.
                val staged = if (ensureToolchain != null) {
                    BuilderToolchainStaging.stage(BuilderToolchainStaging.PLAYWRIGHT, ensureToolchain, shell)
                } else {
                    null
                }
                if (staged?.ok == true) {
                    result = runPlaywright()
                }
                if (!result.ok && WebAppTesting.isPlaywrightMissing(result.failures.joinToString("\n"))) {
                    if (browserCheck != null) {
                        val fallback = browserCheck(url, checks)
                        val note = "Playwright is not staged in the Linux workspace" +
                            (if (staged == null) " (no staging seam wired)" else " (staging failed: ${staged.detail})") +
                            ", so this result comes from the VerificationBrowser fallback, NOT Playwright.\n"
                        return@builderTool ToolResult(
                            ok = fallback.ok,
                            output = note + "VerificationBrowser: ${fallback.summary}" +
                                (fallback.failures.takeIf { it.isNotEmpty() }?.let { "\nFailures: " + it.joinToString("; ") } ?: ""),
                        )
                    }
                    return@builderTool ToolResult(
                        false,
                        "",
                        "Playwright is not staged in the Linux workspace and no VerificationBrowser " +
                            "fallback is wired. " +
                            (staged?.detail ?: "No staging seam is wired, so nothing could be installed.") +
                            " Stage it first (node toolchain + chromium + npm playwright) and try again.",
                    )
                }
            }
            ToolResult(
                ok = result.ok,
                output = result.summary +
                    (result.failures.takeIf { it.isNotEmpty() }?.let { "\nFailures:\n" + it.joinToString("\n") } ?: ""),
            )
        },
    )
    register(
        builderTool(
            name = "webapp_build",
            description = "Scaffold a real React 18 + Vite + Tailwind CSS web app from scratch in the workspace (not a store template) and run npm install + npm run build in the Linux workspace. Returns the dist/ output path or the build errors. Fails honestly when Node.js is not staged.",
            sensitive = true,
            parametersSchema = objectSchema(
                "name" to stringParam("App name, e.g. invoice-tracker."),
                "path" to stringParam("Optional workspace path, default web-artifacts/<name>."),
                required = listOf("name"),
            ),
        ) { args ->
            val name = args.text("name")?.trim()
                ?: return@builderTool ToolResult(false, "", "Missing required argument: name.")
            WebAppBuild.validate(name)?.let { error ->
                return@builderTool ToolResult(false, "", error)
            }
            val path = args.text("path")?.trim().ifNullOrBlank("web-artifacts/$name")
            if (File(workspace.rootDir, path).exists()) {
                return@builderTool ToolResult(
                    false,
                    "",
                    "Directory $path already exists. Refusing to overwrite an existing app — " +
                        "pick a different name or path, or delete it first.",
                )
            }
            withContext(Dispatchers.IO) {
                WebAppBuild.scaffoldFiles(name).forEach { file ->
                    workspace.writeFile("$path/${file.relativePath}", file.content)
                }
            }
            val guestDir = "/workspace/$path"
            if (ensureToolchain == null) {
                return@builderTool ToolResult(
                    false,
                    "",
                    "Scaffold written to $path, but the build did NOT run: no Node.js staging seam is " +
                        "wired, so npm is unavailable. Wire RootfsManager.ensureToolchain and run " +
                        "'npm install && npm run build' in $guestDir.",
                )
            }
            if (!ensureToolchain("node")) {
                return@builderTool ToolResult(
                    false,
                    "",
                    "Scaffold written to $path, but the build did NOT run: the Node.js toolchain " +
                        "could not be installed in the Linux workspace (ensureToolchain(\"node\") failed).",
                )
            }
            val install = shell(WebAppBuild.installCommand(guestDir))
            if (install.exitCode != 0) {
                return@builderTool ToolResult(
                    false,
                    "",
                    "Scaffold written to $path, but 'npm install' failed (exit ${install.exitCode}). " +
                        "Stderr tail: ${install.stderr.takeLast(800).ifBlank { "(empty)" }}",
                )
            }
            val build = shell(WebAppBuild.buildCommand(guestDir))
            val parsed = WebAppBuild.parseBuildOutput(build.stdout + "\n" + build.stderr, build.exitCode, guestDir)
            if (!parsed.ok) {
                return@builderTool ToolResult(
                    false,
                    "",
                    "Build failed for $path.\nErrors:\n${parsed.errors.joinToString("\n")}\n" +
                        "Output tail:\n${parsed.rawTail}",
                )
            }
            ToolResult(
                ok = true,
                output = "Built '$name' successfully. Production output: ${parsed.distPath}\n" +
                    "Scaffold + build output live in $path. Serve dist/ or publish it with github_publish.",
            )
        },
    )
}

private fun builderTool(
    name: String,
    description: String,
    sensitive: Boolean,
    parametersSchema: JsonObject,
    exec: suspend (JsonObject) -> ToolResult,
): Tool = object : Tool {
    override val name: String = name
    override val description: String = description
    override val parametersSchema: JsonObject = parametersSchema
    override val sensitive: Boolean = sensitive
    override suspend fun execute(args: JsonObject): ToolResult =
        runCatching { exec(args) }.getOrElse { e ->
            ToolResult(false, "", "Tool '$name' failed: ${e.message ?: e.javaClass.simpleName}")
        }
}

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.boolean(name: String, default: Boolean): Boolean =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: default

private fun String?.ifNullOrBlank(default: String): String =
    if (this.isNullOrBlank()) default else this

private fun stringParam(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun booleanParam(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

private fun objectSchema(vararg props: Pair<String, JsonObject>, required: List<String>): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for ((key, schema) in props) put(key, schema)
        }
        putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    }
