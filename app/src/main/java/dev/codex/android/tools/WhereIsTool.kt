package dev.codex.android.tools

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Answers "where is the file when install is done?" with real paths, never
 * invented ones.
 *
 * Three places files can live on this phone:
 * - Installed Linux packages live INSIDE the Alpine rootfs, which is the
 *   app's private storage at `<filesDir>/linux/rootfs` (see
 *   `RootfsManager.rootfsDir()`). From inside the shell that directory IS
 *   `/`, so a binary at `usr/bin/pdftoppm` under the rootfs is
 *   `/usr/bin/pdftoppm` in the shell.
 * - Workspace files live under the workspace root, exposed inside the
 *   shell as `/workspace`.
 * - Attachments are staged at `<workspaceRoot>/.attachments/<key>`, exposed
 *   inside the shell as `/workspace/.attachments/<key>` (see
 *   `FileLibrary.ManagedFile.vmPath`).
 *
 * Known binaries below are only the ones this codebase actually installs
 * (see `RootfsManager.TOOLCHAIN_PACKAGES` and the `ocr_image` /
 * `gemini_video` / `image_enhance` tools). Anything else falls through to
 * a cheap existence check at `usr/bin/<name>` and `usr/local/bin/<name>`
 * under the rootfs, and if that finds nothing the tool says so plainly
 * instead of fabricating a path.
 */
class WhereIsTool(
    private val filesDir: File,
    private val workspaceRoot: File,
) : Tool {
    override val name: String = "where_is"
    override val description: String =
        "Finds where an installed package, binary, workspace file, or attachment really lives: " +
            "the on-device path and the in-shell path. Use when the user asks where a file went " +
            "after an install, or where any file is. Never invents a path: it reports when a " +
            "file is not present at the expected location."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") {
                put("type", "string")
                put(
                    "description",
                    "Package name (e.g. \"poppler\"), binary name (e.g. \"pdftoppm\"), " +
                        "workspace path (e.g. \"notes/app.kt\" or \"/workspace/notes/app.kt\"), " +
                        "in-shell path (e.g. \"/usr/bin/pdftoppm\"), or attachment key " +
                        "(e.g. \"drafts/<id>/photo.jpg\" or \"/workspace/.attachments/drafts/<id>/photo.jpg\").",
                )
            }
        }
        putJsonArray("required") { add(JsonPrimitive("name")) }
    }
    override val sensitive: Boolean = false

    /** One known binary: which Alpine package ships it and where it sits under the rootfs. */
    internal data class KnownBinary(val packageName: String, val guestPath: String)

    internal enum class WhereIsKind { ROOTFS, WORKSPACE_FILE, ATTACHMENT, LIBRARY, NOT_FOUND }

    /** One located file: its path as the shell sees it and as Android sees it. */
    internal data class LocatedFile(
        val shellPath: String,
        val devicePath: String,
        val packageName: String?,
        val exists: Boolean,
    )

    internal data class WhereIsResult(
        val kind: WhereIsKind,
        val files: List<LocatedFile>,
        val note: String,
    )

    internal companion object {
        /**
         * Binaries this codebase actually installs, keyed by every name the
         * user might ask for (package name, toolchain name, or binary name).
         * Only binaries verified in RootfsManager.TOOLCHAIN_PACKAGES or
         * referenced by a tool are listed here; anything else is resolved
         * by the generic usr/bin existence check in [resolve].
         */
        val KNOWN: Map<String, List<KnownBinary>> = mapOf(
            // OCR toolchain: tesseract-ocr + poppler (RootfsManager "ocr").
            "poppler" to listOf(KnownBinary("poppler", "usr/bin/pdftoppm")),
            "pdftoppm" to listOf(KnownBinary("poppler", "usr/bin/pdftoppm")),
            "tesseract-ocr" to listOf(KnownBinary("tesseract-ocr", "usr/bin/tesseract")),
            "tesseract" to listOf(KnownBinary("tesseract-ocr", "usr/bin/tesseract")),
            "ocr" to listOf(
                KnownBinary("tesseract-ocr", "usr/bin/tesseract"),
                KnownBinary("poppler", "usr/bin/pdftoppm"),
            ),
            // Video toolchain: one Alpine package ships both (RootfsManager "ffmpeg").
            "ffmpeg" to listOf(
                KnownBinary("ffmpeg", "usr/bin/ffmpeg"),
                KnownBinary("ffmpeg", "usr/bin/ffprobe"),
            ),
            "ffprobe" to listOf(KnownBinary("ffmpeg", "usr/bin/ffprobe")),
            // Python toolchain: python3 + py3-pip (RootfsManager "python").
            "python" to listOf(KnownBinary("python3", "usr/bin/python3")),
            "python3" to listOf(KnownBinary("python3", "usr/bin/python3")),
            "pip" to listOf(KnownBinary("py3-pip", "usr/bin/pip3")),
            "pip3" to listOf(KnownBinary("py3-pip", "usr/bin/pip3")),
            // Node toolchain: nodejs + npm (RootfsManager "node").
            "node" to listOf(KnownBinary("nodejs", "usr/bin/node")),
            "nodejs" to listOf(KnownBinary("nodejs", "usr/bin/node")),
            "npm" to listOf(KnownBinary("npm", "usr/bin/npm")),
            "git" to listOf(KnownBinary("git", "usr/bin/git")),
            "rg" to listOf(KnownBinary("ripgrep", "usr/bin/rg")),
            "ripgrep" to listOf(KnownBinary("ripgrep", "usr/bin/rg")),
            "go" to listOf(KnownBinary("go", "usr/bin/go")),
            "rust" to listOf(
                KnownBinary("rust", "usr/bin/rustc"),
                KnownBinary("cargo", "usr/bin/cargo"),
            ),
            "cargo" to listOf(KnownBinary("cargo", "usr/bin/cargo")),
            "rustc" to listOf(KnownBinary("rust", "usr/bin/rustc")),
            "clang" to listOf(KnownBinary("clang", "usr/bin/clang")),
            "make" to listOf(KnownBinary("make", "usr/bin/make")),
            // Web-app testing: `apk add chromium` (BuilderToolchainStaging).
            "chromium" to listOf(KnownBinary("chromium", "usr/bin/chromium")),
            // Gemini CLI prerequisites (RootfsManager "gemini-cli").
            "curl" to listOf(KnownBinary("curl", "usr/bin/curl")),
            "bash" to listOf(KnownBinary("bash", "usr/bin/bash")),
        )

        /**
         * Installed things that are libraries, not binaries, so there is no
         * single file path to give. The note tells the user how to check.
         */
        val LIBRARIES: Map<String, String> = mapOf(
            "pillow" to "Pillow is a Python library (Alpine package py3-pillow), not a standalone binary: " +
                "there is no single file path for it. It lives inside the Linux rootfs under the " +
                "Python site-packages directory. Check it from the shell with: python3 -c \"import PIL; print(PIL.__file__)\"",
            "py3-pillow" to "Pillow is a Python library (Alpine package py3-pillow), not a standalone binary: " +
                "there is no single file path for it. It lives inside the Linux rootfs under the " +
                "Python site-packages directory. Check it from the shell with: python3 -c \"import PIL; print(PIL.__file__)\"",
        )
    }

    /** The Alpine rootfs directory: `<filesDir>/linux/rootfs` (RootfsManager.rootfsDir()). */
    internal fun rootfsDir(): File = File(filesDir, "linux/rootfs")

    /** Resolves [rawName] to real paths. Pure except for cheap File.exists() checks. */
    internal fun resolve(rawName: String): WhereIsResult {
        val name = rawName.trim()
        val rootfs = rootfsDir()

        // Attachments: "/workspace/.attachments/<key>", ".attachments/<key>",
        // or a bare ManagedFile key "<group>/<id>/<name>".
        val attachmentKey = when {
            name.startsWith("/workspace/.attachments/") -> name.removePrefix("/workspace/.attachments/")
            name.startsWith(".attachments/") -> name.removePrefix(".attachments/")
            name.startsWith("workspace/.attachments/") -> name.removePrefix("workspace/.attachments/")
            isAttachmentKey(name) -> name
            else -> null
        }
        if (attachmentKey != null) {
            val device = File(workspaceRoot, ".attachments/$attachmentKey")
            return WhereIsResult(
                kind = WhereIsKind.ATTACHMENT,
                files = listOf(
                    LocatedFile(
                        shellPath = "/workspace/.attachments/$attachmentKey",
                        devicePath = device.path,
                        packageName = null,
                        exists = device.isFile,
                    ),
                ),
                note = "Attachments are staged copies under the workspace; the original library copy is separate.",
            )
        }

        // Workspace files: "/workspace/<rel>", "workspace/<rel>", or a
        // workspace-relative path containing "/" (e.g. "notes/app.kt").
        val workspaceRel = when {
            name.startsWith("/workspace/") -> name.removePrefix("/workspace/")
            name.startsWith("workspace/") -> name.removePrefix("workspace/")
            name == "/workspace" || name == "workspace" -> ""
            else -> null
        }
        if (workspaceRel != null) {
            return workspaceResult(workspaceRel)
        }
        if (name.contains("/") && !name.startsWith("/")) {
            // "usr/bin/pdftoppm" is a path inside the shell's root; anything
            // else with a "/" is a workspace-relative path.
            if (name.startsWith("usr/") || name.startsWith("etc/") || name.startsWith("bin/") ||
                name.startsWith("lib/") || name.startsWith("opt/") || name.startsWith("root/") ||
                name.startsWith("tmp/")
            ) {
                return rootfsResult(listOf(KnownBinary(packageName = "", guestPath = name)), rootfs)
            }
            return workspaceResult(name)
        }

        // Absolute in-shell path ("/usr/bin/pdftoppm", "/etc/hosts", ...):
        // the shell's "/" IS the rootfs directory.
        if (name.startsWith("/")) {
            val guestPath = name.removePrefix("/")
            return rootfsResult(listOf(KnownBinary(packageName = "", guestPath = guestPath)), rootfs)
        }

        // Known package / toolchain / binary name.
        val key = name.lowercase()
        KNOWN[key]?.let { binaries ->
            return rootfsResult(binaries, rootfs)
        }
        LIBRARIES[key]?.let { note ->
            return WhereIsResult(kind = WhereIsKind.LIBRARY, files = emptyList(), note = note)
        }

        // Generic rule: an `apk add` binary lands in /usr/bin (or
        // /usr/local/bin for a manually installed one). Only report it when
        // it is actually there; otherwise say plainly it is not installed.
        for (candidate in listOf("usr/bin/$name", "usr/local/bin/$name")) {
            val file = File(rootfs, candidate)
            if (file.isFile) {
                return rootfsResult(
                    listOf(KnownBinary(packageName = "", guestPath = candidate)),
                    rootfs,
                )
            }
        }
        return WhereIsResult(
            kind = WhereIsKind.NOT_FOUND,
            files = emptyList(),
            note = "\"$name\" is not a known installed package or binary, and no file " +
                "named \"$name\" exists at /usr/bin/$name or /usr/local/bin/$name inside the Linux workspace. " +
                "It is not installed. If it was installed with `apk add`, it would live under the Linux rootfs at " +
                "${File(rootfs, "usr/bin/$name").path} (in the shell: /usr/bin/$name). " +
                "Confirm from the shell with: command -v $name",
        )
    }

    private fun isAttachmentKey(name: String): Boolean {
        val parts = name.split("/")
        if (parts.size != 3) return false
        val group = parts[0]
        if (group != "drafts" && group != "library" && !group.startsWith("thr_")) return false
        // Middle segment is a UUID folder (FileLibrary ManagedFile ids).
        return parts[1].length == 36 && parts[1].count { it == '-' } == 4
    }

    private fun workspaceResult(relativePath: String): WhereIsResult {
        if (relativePath.contains("..")) {
            return WhereIsResult(
                kind = WhereIsKind.NOT_FOUND,
                files = emptyList(),
                note = "Paths outside the workspace (\"..\") are not allowed.",
            )
        }
        val device = if (relativePath.isEmpty()) workspaceRoot else File(workspaceRoot, relativePath)
        val shellPath = if (relativePath.isEmpty()) "/workspace" else "/workspace/$relativePath"
        return WhereIsResult(
            kind = WhereIsKind.WORKSPACE_FILE,
            files = listOf(
                LocatedFile(
                    shellPath = shellPath,
                    devicePath = device.path,
                    packageName = null,
                    exists = device.exists(),
                ),
            ),
            note = "Workspace files live under the workspace root, exposed inside the shell as /workspace.",
        )
    }

    private fun rootfsResult(binaries: List<KnownBinary>, rootfs: File): WhereIsResult {
        val files = binaries.map { binary ->
            val device = File(rootfs, binary.guestPath)
            LocatedFile(
                shellPath = "/${binary.guestPath}",
                devicePath = device.path,
                packageName = binary.packageName.ifEmpty { null },
                exists = device.isFile,
            )
        }
        return WhereIsResult(
            kind = WhereIsKind.ROOTFS,
            files = files,
            note = "Installed packages live inside the Linux rootfs at ${rootfs.path} " +
                "(in the shell that directory is /).",
        )
    }

    internal fun formatResult(query: String, result: WhereIsResult): String = buildString {
        when (result.kind) {
            WhereIsKind.NOT_FOUND -> appendLine(result.note)
            WhereIsKind.LIBRARY -> appendLine(result.note)
            else -> {
                for (file in result.files) {
                    if (file.packageName != null) appendLine("Package: ${file.packageName}")
                    appendLine("In the shell: ${file.shellPath}")
                    appendLine("On this phone: ${file.devicePath}")
                    appendLine(
                        if (file.exists) "Installed: yes, the file is there."
                        else "Installed: no, not present at that location.",
                    )
                }
                appendLine(result.note)
            }
        }
    }.trimEnd()

    override suspend fun execute(args: JsonObject): ToolResult {
        val name = args["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: name")
        return ToolResult(ok = true, output = formatResult(name, resolve(name)))
    }
}
