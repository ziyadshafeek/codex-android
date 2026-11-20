package dev.codex.android.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.codex.android.api.ChatMessage
import dev.codex.android.api.OpenAiClient
import dev.codex.android.api.OkHttpOpenAiClient
import dev.codex.android.core.CodexError
import dev.codex.android.data.KeyStore
import kotlinx.coroutines.CancellationException
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Standalone image reader (`name = "read_image"`).
 *
 * The agent calls it with a file path and it genuinely sends the image
 * to the user's own vision-capable model and returns the model's
 * description / analysis / OCR text. The image travels the exact same
 * `image_url` path [OkHttpOpenAiClient] uses for inline chat images
 * (see `ChatMessagesWire`): one user message whose content is the
 * prompt plus a `data:image/jpeg;base64,...` image part. Nothing is
 * ever described from a filename or guessed — if the model cannot see
 * the image, the tool fails with a clear error instead of inventing
 * a description.
 *
 * Images are downscaled (longest side [MAX_SIDE_PX]) and re-encoded
 * as JPEG ([JPEG_QUALITY]) before sending, so a phone photo does not
 * blow up the request. Files over [MAX_IMAGE_BYTES] are refused
 * outright with a clear error.
 *
 * BYOK only: the model, base URL, and key come from the user's own
 * settings ([KeyStore]); nothing is baked in. The [OpenAiClient] is
 * injected so JVM tests can substitute a fake; the [Context]
 * constructor wires the real client from settings.
 */
class ReadImageTool(
    private val client: OpenAiClient,
    private val model: String,
    private val workspaceDir: File? = null,
    private val prepareImage: (File) -> ByteArray = { file -> ImagePreparer.prepare(file) },
    private val configured: Boolean = true,
) : Tool {

    /**
     * Production constructor: model client from the user's own settings,
     * workspace root under the app's files directory.
     */
    constructor(context: Context, workspaceDir: File? = null) : this(
        client = clientFromSettings(context),
        model = modelFromSettings(context),
        workspaceDir = workspaceDir ?: File(context.applicationContext.filesDir, "workspace"),
        configured = isConfigured(context),
    )

    override val name: String = "read_image"
    override val description: String =
        "Reads an image file and returns the vision model's description of it: what it shows, " +
            "layout and colours, and any visible text read out word for word. " +
            "Pass a workspace-relative path or an absolute file path, and optionally a prompt " +
            "saying what to describe or extract. The image is genuinely sent to the user's own " +
            "vision-capable model; if the model does not support vision, the error says so."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Image file path: workspace-relative, or an absolute file path")
            }
            putJsonObject("prompt") {
                put("type", "string")
                put("description", "What to describe or extract (default: describe the image in detail, including any visible text)")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val rawPath = args["path"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return@withContext ToolResult(ok = false, output = "", error = "Missing required argument: path")
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_PROMPT

        val file = try {
            resolveImageFile(rawPath)
        } catch (e: SecurityException) {
            return@withContext ToolResult(ok = false, output = "", error = e.message ?: "Refused path: $rawPath")
        }
        if (!file.isFile) {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = "Image file not found: $rawPath (looked for ${file.absolutePath})",
            )
        }
        if (file.length() > MAX_IMAGE_BYTES) {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = "Image file is too large: ${file.length()} bytes (limit ${MAX_IMAGE_BYTES} bytes / 10 MB). " +
                    "Shrink or compress it first, e.g. with the image-enhancer skill.",
            )
        }

        if (!configured) {
            return@withContext ToolResult(ok = false, output = "", error = NO_PROVIDER_ERROR)
        }

        val jpegBytes = try {
            prepareImage(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = "Could not read $rawPath as an image: ${e.message ?: e.javaClass.simpleName}",
            )
        }
        if (jpegBytes.isEmpty()) {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = "Could not read $rawPath as an image: empty image data",
            )
        }

        val dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpegBytes)
        val text = try {
            val result = client.chat(
                messages = listOf(
                    ChatMessage(role = "user", content = prompt, imageUrls = listOf(dataUrl)),
                ),
                tools = emptyList(),
                model = model,
            )
            result.choices.firstOrNull()?.text?.trim().orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CodexError) {
            if (isVisionUnsupported(e)) {
                return@withContext ToolResult(ok = false, output = "", error = VISION_UNSUPPORTED_ERROR)
            }
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = "Image read failed: ${e.message ?: e.javaClass.simpleName}",
            )
        } catch (e: Exception) {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = "Image read failed: ${e.message ?: e.javaClass.simpleName}",
            )
        }
        if (text.isEmpty()) {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = "The model returned no description for $rawPath",
            )
        }
        ToolResult(ok = true, output = text)
    }

    /**
     * Resolves [rawPath] to a file: absolute paths are used as given
     * (attachment files live outside the workspace), relative paths are
     * resolved under [workspaceDir] and must stay inside it.
     */
    internal fun resolveImageFile(rawPath: String): File {
        val given = File(rawPath)
        if (given.isAbsolute) return given
        val root = workspaceDir ?: return given
        val rootCanonical = root.canonicalFile
        val target = File(rootCanonical, rawPath).canonicalFile
        val prefix = rootCanonical.path + File.separator
        if (target != rootCanonical && !target.path.startsWith(prefix)) {
            throw SecurityException("Path escapes workspace: $rawPath")
        }
        return target
    }

    companion object {
        const val DEFAULT_PROMPT: String =
            "Describe this image in detail, including any visible text."
        const val MAX_IMAGE_BYTES: Long = 10L * 1024 * 1024
        const val MAX_SIDE_PX: Int = 1568
        const val JPEG_QUALITY: Int = 85
        const val NO_PROVIDER_ERROR: String =
            "No model configured: add your API base URL and key in Settings first. " +
                "Image reading needs your own (BYOK) vision-capable model."
        const val VISION_UNSUPPORTED_ERROR: String =
            "The configured model does not support vision: the provider rejected the image. " +
                "Switch to a vision-capable model in Settings, or read the file's text with " +
                "attachment_read instead. Nothing was described — no description was invented."

        private fun clientFromSettings(context: Context): OkHttpOpenAiClient {
            val store = KeyStore(context.applicationContext)
            return OkHttpOpenAiClient(store.getBaseUrl(), store.getApiKey() ?: "", store.getModel())
        }

        private fun modelFromSettings(context: Context): String =
            KeyStore(context.applicationContext).getModel()

        private fun isConfigured(context: Context): Boolean {
            val store = KeyStore(context.applicationContext)
            return store.getBaseUrl().isNotBlank() && !store.getApiKey().isNullOrBlank()
        }

        /**
         * True when a [CodexError] means the provider refused the image
         * itself — the model has no vision — rather than a transient
         * failure. [OkHttpOpenAiClient] reports a 400/413/415/422 on an
         * image message as "The provider rejected the image or PDF";
         * other providers phrase it as vision / image support.
         */
        internal fun isVisionUnsupported(error: CodexError): Boolean {
            val message = (error.message ?: "").lowercase()
            return "rejected the image" in message ||
                "does not support vision" in message ||
                "does not support image" in message ||
                "vision" in message && "support" in message ||
                "image_url" in message
        }
    }
}

/**
 * Downscales an image file to [ReadImageTool.MAX_SIDE_PX] on its longest
 * side and re-encodes it as JPEG ([ReadImageTool.JPEG_QUALITY]).
 * Separated from [ReadImageTool] so JVM tests can substitute a fake
 * preparer — `android.graphics.Bitmap` is a stub on the JVM.
 */
internal object ImagePreparer {

    fun prepare(
        file: File,
        maxSidePx: Int = ReadImageTool.MAX_SIDE_PX,
        jpegQuality: Int = ReadImageTool.JPEG_QUALITY,
    ): ByteArray {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IOException("Not a readable image file: ${file.name}")
        try {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) throw IOException("Image has no pixels: ${file.name}")
            val longest = max(width, height)
            val scaled = if (longest > maxSidePx) {
                val scale = maxSidePx.toDouble() / longest
                Bitmap.createScaledBitmap(
                    bitmap,
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            try {
                val out = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)) {
                    throw IOException("Failed to encode ${file.name} as JPEG")
                }
                return out.toByteArray()
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
