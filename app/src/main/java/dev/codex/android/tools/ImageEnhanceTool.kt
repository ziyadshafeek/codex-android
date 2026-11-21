package dev.codex.android.tools

import dev.codex.android.linux.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
 * Image enhancement (`name = "image_enhance"`) via Pillow (PIL) in the
 * on-device Linux workspace.
 *
 * The tool writes a small generated Python script into the workspace,
 * runs it with the Linux shell (`python3 <script> <input> <output>`),
 * and returns the path of the enhanced image. Enhancement is real
 * Pillow work — resize, sharpen, contrast, brightness, format
 * conversion, EXIF auto-orientation — never a renamed copy of the
 * input. If Pillow is missing and cannot be installed, the tool says
 * so plainly instead of faking an enhancement.
 *
 * Pillow staging: the Alpine package is `py3-pillow`, registered as
 * the `"pillow"` toolchain in `RootfsManager.TOOLCHAIN_PACKAGES`, so
 * `ensureToolchain("pillow")` installs it. The default
 * [ensurePillow] probes for PIL and falls back to
 * `apk add --no-cache py3-pillow` inside the rootfs. Production
 * wiring may pass its own installer instead; JVM tests inject a fake.
 *
 * [shell] is the same `suspend (String) -> ShellResult` the
 * `shell_exec` developer tool uses (working directory `/workspace`,
 * workspace root bound at `/workspace` in the guest).
 */
class ImageEnhanceTool(
    private val workspace: WorkspaceFileTools,
    private val shell: suspend (String) -> ShellResult,
    private val ensurePillow: suspend () -> Boolean = { ensurePillowViaShell(shell) },
) : Tool {

    override val name: String = "image_enhance"
    override val description: String =
        "Enhances an image file in the workspace with Pillow (resize, sharpen, contrast, " +
            "brightness, format conversion, auto-orient) and saves the result, returning its path. " +
            "Use before OCR or reading when an image is blurry, dim, sideways, or too large. " +
            "The original file is never modified. If Pillow is not installed in the Linux " +
            "workspace, the error says so instead of faking an enhancement."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Workspace-relative path of the image to enhance")
            }
            putJsonObject("operations") {
                put("type", "string")
                put("description", "Operations to apply, e.g. \"auto-orient,sharpen:1.5,contrast:1.3\" or a JSON array/object of named operations with values. Supported: resize (\"800x600\" or max side \"1024\"), sharpen, contrast, brightness, convert (png/jpg/webp), auto-orient. Default: auto-orient,sharpen.")
            }
            putJsonObject("output_path") {
                put("type", "string")
                put("description", "Optional workspace-relative output path. Default: same folder as the input, named <name>-enhanced.<ext>.")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val rawPath = args["path"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return@withContext ToolResult(ok = false, output = "", error = "Missing required argument: path")
        val ops = try {
            parseOperations(args["operations"])
        } catch (e: IllegalArgumentException) {
            return@withContext ToolResult(ok = false, output = "", error = e.message ?: "Invalid operations")
        }

        val inputFile = try {
            workspace.resolveSafe(rawPath)
        } catch (e: Exception) {
            return@withContext ToolResult(ok = false, output = "", error = e.message ?: "Refused path: $rawPath")
        }
        if (!inputFile.isFile) {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = "Image file not found in workspace: $rawPath",
            )
        }

        val convertFormat = ops.firstOrNull { it.name == "convert" }?.value
        val outputFile = try {
            resolveOutputFile(inputFile, convertFormat, args["output_path"]?.jsonPrimitive?.contentOrNull?.trim())
        } catch (e: Exception) {
            return@withContext ToolResult(ok = false, output = "", error = e.message ?: "Invalid output_path")
        }

        val pillowReady = try {
            ensurePillow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = "Could not check for Pillow: ${e.message ?: e.javaClass.simpleName}",
            )
        }
        if (!pillowReady) {
            return@withContext ToolResult(ok = false, output = "", error = PILLOW_MISSING_ERROR)
        }

        val scriptFile = File(
            workspace.rootDir,
            ".codex/enhance-${System.currentTimeMillis()}.py",
        )
        try {
            scriptFile.parentFile?.mkdirs()
            scriptFile.writeText(buildEnhanceScript(ops))
            val command = "python3 ${shellQuote(guestPath(scriptFile))} " +
                "${shellQuote(guestPath(inputFile))} ${shellQuote(guestPath(outputFile))}"
            val result = try {
                shell(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext ToolResult(
                    ok = false,
                    output = "",
                    error = "Image enhancement failed to run: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            if (result.timedOut) {
                return@withContext ToolResult(
                    ok = false,
                    output = "",
                    error = "Image enhancement timed out for $rawPath",
                )
            }
            if (result.exitCode != 0) {
                return@withContext ToolResult(
                    ok = false,
                    output = "",
                    error = "Image enhancement failed (exit ${result.exitCode}): " +
                        result.stderr.trim().takeLast(600).ifEmpty { result.stdout.trim().takeLast(600) },
                )
            }
            if (!outputFile.isFile) {
                return@withContext ToolResult(
                    ok = false,
                    output = "",
                    error = "Enhancement ran but produced no output file at ${relativePath(outputFile)}",
                )
            }
            ToolResult(
                ok = true,
                output = "Enhanced image saved to ${relativePath(outputFile)} " +
                    "(${outputFile.length()} bytes). Operations applied: ${ops.joinToString(", ") { it.describe() }}. " +
                    "Original ${relativePath(inputFile)} left unchanged.",
            )
        } finally {
            runCatching { scriptFile.delete() }
        }
    }

    /** Workspace-relative path of [file], for messages and guest paths. */
    private fun relativePath(file: File): String =
        file.canonicalFile.relativeTo(workspace.rootDir.canonicalFile).invariantSeparatorsPath

    /** Guest path of a workspace [file]: the workspace root is bound at `/workspace`. */
    private fun guestPath(file: File): String = "/workspace/${relativePath(file)}"

    /**
     * Output file for [inputFile]: the caller's `output_path` when given
     * (a trailing `/` means "same default name inside this folder"),
     * otherwise `<name>-enhanced.<ext>` next to the input, with the
     * extension from the `convert` operation when one is given.
     */
    internal fun resolveOutputFile(inputFile: File, convertFormat: String?, requested: String?): File {
        val ext = convertFormat?.lowercase()?.let { if (it == "jpeg") "jpg" else it }
            ?: inputFile.extension.lowercase().ifEmpty { "png" }
        val defaultName = inputFile.nameWithoutExtension + "-enhanced." + ext
        if (requested.isNullOrBlank()) return File(inputFile.parentFile, defaultName)
        val target = workspace.resolveSafe(requested)
        return if (requested.endsWith("/")) File(target, defaultName) else target
    }

    companion object {
        const val PILLOW_MISSING_ERROR: String =
            "Pillow (PIL) is not installed in the Linux workspace and could not be installed " +
                "automatically (Alpine package py3-pillow). Install it with the \"pillow\" " +
                "toolchain — RootfsManager.ensureToolchain(\"pillow\") — or run " +
                "`apk add --no-cache py3-pillow` in the Linux terminal, then try again. " +
                "No enhancement was faked."

        /** The operations applied when the caller passes none. */
        val DEFAULT_OPERATIONS: List<EnhanceOp> = listOf(
            EnhanceOp("auto-orient", null),
            EnhanceOp("sharpen", "1.5"),
        )

        /** Supported operation names, in the order the skill teaches them. */
        val SUPPORTED_OPERATIONS: List<String> = listOf(
            "resize", "sharpen", "contrast", "brightness", "convert", "auto-orient",
        )

        /**
         * Parses the `operations` argument in any of its accepted shapes:
         * a comma-separated string (`"sharpen:1.5,contrast:1.3"`), a JSON
         * array of such strings, or a JSON object mapping names to values
         * (`{"sharpen": 1.5, "auto-orient": true}`). Absent / blank means
         * [DEFAULT_OPERATIONS]. Throws [IllegalArgumentException] on an
         * unknown operation or a malformed value.
         */
        fun parseOperations(raw: JsonElement?): List<EnhanceOp> {
            if (raw == null || raw is JsonNull) return DEFAULT_OPERATIONS
            val parts: List<String> = when (raw) {
                is JsonPrimitive -> {
                    if (!raw.isString) throw IllegalArgumentException(
                        "operations must be a string, array, or object naming: ${SUPPORTED_OPERATIONS.joinToString(", ")}",
                    )
                    val text = raw.content.trim()
                    if (text.isEmpty()) return DEFAULT_OPERATIONS
                    // A JSON-looking string is parsed as JSON, so
                    // "[\"sharpen\"]" and "{\"sharpen\": 1.5}" work too.
                    if (text.startsWith("[") || text.startsWith("{")) {
                        val parsed = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(text)
                        }.getOrNull() ?: throw IllegalArgumentException("operations is not valid JSON: $text")
                        return parseOperations(parsed)
                    }
                    text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                }
                is JsonArray -> raw.map {
                    (it as? JsonPrimitive)?.contentOrNull
                        ?: throw IllegalArgumentException("operations array entries must be strings like \"sharpen:1.5\"")
                }
                is JsonObject -> raw.entries.map { (key, value) ->
                    when (value) {
                        is JsonNull -> key
                        is JsonPrimitive -> {
                            val v = value.contentOrNull
                            if (v.isNullOrBlank()) key else "$key:$v"
                        }
                        else -> throw IllegalArgumentException("operations object values must be strings, numbers, or booleans")
                    }
                }
                else -> throw IllegalArgumentException(
                    "operations must be a string, array, or object naming: ${SUPPORTED_OPERATIONS.joinToString(", ")}",
                )
            }
            if (parts.isEmpty()) return DEFAULT_OPERATIONS
            return parts.map { parseOneOperation(it) }
        }

        private fun parseOneOperation(text: String): EnhanceOp {
            val name = text.substringBefore(':').trim().lowercase()
            val value = text.substringAfter(':', "").trim().takeIf { it.isNotEmpty() }
            if (name !in SUPPORTED_OPERATIONS) {
                throw IllegalArgumentException(
                    "Unknown image operation \"$name\". Supported: ${SUPPORTED_OPERATIONS.joinToString(", ")}.",
                )
            }
            when (name) {
                "resize" -> {
                    val v = value ?: throw IllegalArgumentException(
                        "resize needs a value: \"800x600\" for exact size or \"1024\" for a max side.",
                    )
                    if (!v.matches(Regex("""\d+x\d+""")) && v.toIntOrNull() == null) {
                        throw IllegalArgumentException(
                            "Invalid resize value \"$v\": expected \"800x600\" or a max side like \"1024\".",
                        )
                    }
                }
                "sharpen", "contrast", "brightness" -> {
                    val v = value ?: defaultFactor(name)
                    val factor = v.toDoubleOrNull()
                        ?: throw IllegalArgumentException("Invalid $name value \"$v\": expected a number.")
                    if (factor <= 0.0) {
                        throw IllegalArgumentException("Invalid $name value \"$v\": expected a number above 0.")
                    }
                }
                "convert" -> {
                    val v = (value ?: throw IllegalArgumentException(
                        "convert needs a format: png, jpg, or webp.",
                    )).lowercase()
                    if (v !in setOf("png", "jpg", "jpeg", "webp")) {
                        throw IllegalArgumentException(
                            "Invalid convert format \"$v\": expected png, jpg, or webp.",
                        )
                    }
                }
                "auto-orient" -> {
                    if (value != null && value.lowercase() !in setOf("true", "false")) {
                        throw IllegalArgumentException("Invalid auto-orient value \"$value\": expected true or false.")
                    }
                }
            }
            return EnhanceOp(name, value ?: defaultFactorOrNull(name))
        }

        private fun defaultFactor(name: String): String = when (name) {
            "sharpen" -> "1.5"
            "contrast" -> "1.2"
            "brightness" -> "1.1"
            else -> ""
        }

        private fun defaultFactorOrNull(name: String): String? = when (name) {
            "sharpen", "contrast", "brightness" -> defaultFactor(name)
            else -> null
        }

        /**
         * Builds the Pillow script run in the guest. Operations run in the
         * order given. The script takes input and output paths as
         * `sys.argv[1]` / `sys.argv[2]` and prints `OK <path> <WxH>` on
         * success; any Pillow failure exits non-zero with a traceback.
         */
        fun buildEnhanceScript(ops: List<EnhanceOp>): String = buildString {
            appendLine("import sys")
            appendLine("from PIL import Image, ImageEnhance, ImageOps")
            appendLine("src = sys.argv[1]")
            appendLine("dst = sys.argv[2]")
            appendLine("img = Image.open(src)")
            for (op in ops) {
                when (op.name) {
                    "auto-orient" -> {
                        if (op.value?.lowercase() != "false") appendLine("img = ImageOps.exif_transpose(img)")
                    }
                    "resize" -> {
                        val v = op.value ?: "1024"
                        val dims = v.split("x")
                        if (dims.size == 2) {
                            appendLine("img = img.resize((${dims[0]}, ${dims[1]}))")
                        } else {
                            appendLine("img.thumbnail(($v, $v))")
                        }
                    }
                    "sharpen" -> appendLine("img = ImageEnhance.Sharpness(img).enhance(${op.value ?: "1.5"})")
                    "contrast" -> appendLine("img = ImageEnhance.Contrast(img).enhance(${op.value ?: "1.2"})")
                    "brightness" -> appendLine("img = ImageEnhance.Brightness(img).enhance(${op.value ?: "1.1"})")
                    "convert" -> appendLine("# output format follows the output file extension")
                }
            }
            appendLine("if dst.lower().endswith(('.jpg', '.jpeg')) and img.mode in ('RGBA', 'LA', 'P'):")
            appendLine("    img = img.convert('RGB')")
            appendLine("img.save(dst)")
            appendLine("print('OK ' + dst + ' ' + str(img.size[0]) + 'x' + str(img.size[1]))")
        }

        /** Single-quote escaping for one shell word. */
        internal fun shellQuote(word: String): String = "'" + word.replace("'", "'\\''") + "'"

        /**
         * Default Pillow check: probe `python3 -c "import PIL"`; when it
         * fails, install the Alpine package and probe again. Returns true
         * only when PIL imports afterwards.
         */
        internal suspend fun ensurePillowViaShell(shell: suspend (String) -> ShellResult): Boolean {
            if (shell("python3 -c \"import PIL\"").exitCode == 0) return true
            if (shell("apk add --no-cache py3-pillow").exitCode != 0) return false
            return shell("python3 -c \"import PIL\"").exitCode == 0
        }
    }
}

/** One parsed enhancement operation: a supported name plus its value, if any. */
data class EnhanceOp(val name: String, val value: String?) {
    fun describe(): String = if (value == null) name else "$name=$value"
}
