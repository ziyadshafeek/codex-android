package dev.codex.android.linux

/**
 * Staging manifest for the builder toolchains (skills: webapp-testing,
 * web-artifacts-builder, firebase).
 *
 * This is a MANIFEST, not a bootstrap change: it adds no artifact to
 * `RootfsManager.PINNED_ARTIFACTS` and touches no rootfs bootstrap
 * logic. Everything here installs at runtime inside the already-booted
 * Alpine rootfs, through the two mechanisms that already exist:
 * - `RootfsManager.ensureToolchain(name)` — Alpine `apk add` for the
 *   pinned `node` / `python` toolchains (see `TOOLCHAIN_PACKAGES`);
 * - `ProotShellEngine.shellExec` — `apk add` for extra packages and
 *   `npm install -g` for npm CLIs.
 *
 * Staging outcomes, honestly:
 * - **node**: `ensureToolchain("node")` installs Alpine `nodejs` + `npm`.
 *   This is the base for everything below; it downloads from the Alpine
 *   mirrors at staging time and needs network on the phone.
 * - **playwright**: node + `apk add chromium` (system Chromium, with
 *   its Alpine dependencies) + `npm install -g playwright`. Playwright
 *   is pointed at the system Chromium via
 *   `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium-browser`
 *   instead of `playwright install`, because that command downloads
 *   browsers with apt-style deps that do not work on Alpine. The
 *   `playwright` npm package itself installs WITHOUT browsers, so no
 *   browser download ever runs.
 * - **firebase-tools**: node + `npm install -g firebase-tools`. Large
 *   download (npm, at staging time); login is interactive
 *   (`firebase login --no-localhost`) and is the user's step, never
 *   automated here.
 * - **python**: `ensureToolchain("python")` installs `python3` +
 *   `py3-pip`; listed because the Playwright Python route and the
 *   `mcp-builder` Python scaffold both need it.
 *
 * All staging runs through caller-supplied seams
 * (`ensureToolchain`, `shell`) so JVM tests can drive it with fakes and
 * so the coordinator wires the real `RootfsManager` /
 * `ProotShellEngine` at integration time. A stage that cannot install
 * reports failure with the exact step that failed; nothing here fakes
 * a successful install.
 */
data class ToolchainStage(
    val id: String,
    val description: String,
    /** Toolchain names for `RootfsManager.ensureToolchain`, in order. */
    val requiresToolchain: List<String> = emptyList(),
    /** Extra Alpine packages, installed with one `apk add --no-cache`. */
    val apkPackages: List<String> = emptyList(),
    /** npm CLIs, installed with one `npm install -g`. */
    val npmGlobalPackages: List<String> = emptyList(),
    /** Environment the staged tools need (documented, set by the caller). */
    val env: Map<String, String> = emptyMap(),
)

data class StagingResult(
    val ok: Boolean,
    val stageId: String,
    val detail: String,
)

object BuilderToolchainStaging {

    val NODE: ToolchainStage = ToolchainStage(
        id = "node",
        description = "Node.js + npm (Alpine nodejs + npm packages). Base for Playwright, web builds and firebase-tools.",
        requiresToolchain = listOf("node"),
    )

    val PYTHON: ToolchainStage = ToolchainStage(
        id = "python",
        description = "Python 3 + pip (Alpine python3 + py3-pip). For Python MCP servers and Playwright-Python.",
        requiresToolchain = listOf("python"),
    )

    val PLAYWRIGHT: ToolchainStage = ToolchainStage(
        id = "playwright",
        description = "Playwright (Node) driving the system Chromium. No browser download: uses apk chromium via PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH.",
        requiresToolchain = listOf("node"),
        apkPackages = listOf("chromium"),
        npmGlobalPackages = listOf("playwright"),
        env = mapOf("PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH" to "/usr/bin/chromium-browser"),
    )

    val FIREBASE_TOOLS: ToolchainStage = ToolchainStage(
        id = "firebase-tools",
        description = "Firebase CLI (npm firebase-tools). Login is the user's interactive step (firebase login --no-localhost).",
        requiresToolchain = listOf("node"),
        npmGlobalPackages = listOf("firebase-tools"),
    )

    /** Every stage this manifest knows, in dependency order. */
    val STAGES: List<ToolchainStage> = listOf(NODE, PYTHON, PLAYWRIGHT, FIREBASE_TOOLS)

    fun find(id: String): ToolchainStage? = STAGES.firstOrNull { it.id == id }

    /**
     * Installs [stage] inside the Linux workspace. Returns
     * `ok = false` with the exact failing step when a toolchain, apk or
     * npm step fails — never a faked success. Idempotent: re-running a
     * stage that is already installed just re-runs the same installs.
     */
    suspend fun stage(
        stage: ToolchainStage,
        ensureToolchain: suspend (String) -> Boolean,
        shell: suspend (String) -> ShellResult,
    ): StagingResult {
        for (toolchain in stage.requiresToolchain) {
            val installed = runCatching { ensureToolchain(toolchain) }.getOrDefault(false)
            if (!installed) {
                return StagingResult(
                    ok = false,
                    stageId = stage.id,
                    detail = "Toolchain '$toolchain' could not be installed in the Linux workspace " +
                        "(ensureToolchain returned false). ${stage.description}",
                )
            }
        }
        if (stage.apkPackages.isNotEmpty()) {
            val cmd = "apk add --no-cache ${stage.apkPackages.joinToString(" ")}"
            val result = runCatching { shell(cmd) }.getOrNull()
            if (result == null || result.exitCode != 0) {
                return StagingResult(
                    ok = false,
                    stageId = stage.id,
                    detail = "apk install failed for ${stage.apkPackages.joinToString(", ")} " +
                        "(command: $cmd, exit: ${result?.exitCode ?: "not run"}). " +
                        "Stderr: ${result?.stderr?.takeLast(500).orEmpty()}",
                )
            }
        }
        if (stage.npmGlobalPackages.isNotEmpty()) {
            val cmd = "npm install -g ${stage.npmGlobalPackages.joinToString(" ")}"
            val result = runCatching { shell(cmd) }.getOrNull()
            if (result == null || result.exitCode != 0) {
                return StagingResult(
                    ok = false,
                    stageId = stage.id,
                    detail = "npm install failed for ${stage.npmGlobalPackages.joinToString(", ")} " +
                        "(command: $cmd, exit: ${result?.exitCode ?: "not run"}). " +
                        "Stderr: ${result?.stderr?.takeLast(500).orEmpty()}",
                )
            }
        }
        val envNote = if (stage.env.isEmpty()) "" else
            " Environment needed: ${stage.env.entries.joinToString(", ") { "${it.key}=${it.value}" }}."
        return StagingResult(
            ok = true,
            stageId = stage.id,
            detail = "Staged '${stage.id}': ${stage.description}$envNote",
        )
    }
}
