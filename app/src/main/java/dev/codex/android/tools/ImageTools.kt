package dev.codex.android.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import dev.codex.android.api.OkHttpOpenAiClient
import dev.codex.android.core.CodexJson
import dev.codex.android.data.KeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/** Longest side, in pixels, an image is downscaled to before a vision call. */
const val MAX_IMAGE_SIDE_PX = 1568

/** Largest image file the tool will read (10 MB). */
const val MAX_IMAGE_BYTES = 10L * 1024L * 1024L

/** Default question asked about an image when the caller supplies no prompt. */
const val DEFAULT_IMAGE_PROMPT = "Describe this image in detail."

/**
 * The size an image of [width] x [height] is downscaled to so its longest
 * side is at most [maxSide] pixels, keeping the aspect ratio. Images that
 * already fit are returned unchanged — never upscaled. Pure function, so it
 * is unit-testable on the JVM with no Android framework.
 */
fun scaledDimensions(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "Image dimensions must be positive" }
    require(maxSide > 0) { "maxSide must be positive" }
    val longest = max(width, height)
    if (longest <= maxSide) return width to height
    // Integer math: exact, so the longest side lands on exactly maxSide
    // (floating-point scaling can truncate it one pixel short).
    val scaledWidth = ((width.toLong() * maxSide) / longest).toInt().coerceAtLeast(1)
    val scaledHeight = ((height.toLong() * maxSide) / longest).toInt().coerceAtLeast(1)
    return scaledWidth to scaledHeight
}

/**
 * The exact JSON body [OkHttpOpenAiClient.describeImage] sends: one
 * non-streaming chat-completions call whose user message carries the prompt
 * as text plus the image as a `data:` URL. Pure function, so it is
 * unit-testable on the JVM with no Android framework and no network.
 */
fun buildVisionRequestJson(model: String, prompt: String, base64Image: String, mimeType: String): String {
    val root = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", prompt)
                    }
                    addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", "data:$mimeType;base64,$base64Image")
                        }
                    }
                }
            }
        }
        put("stream", false)
        put("temperature", 0.2)
    }
    return CodexJson.encodeToString(JsonObject.serializer(), root)
}

/**
 * Reads an image file from the app's private storage and asks the configured
 * model to describe what it shows (`name = "read_image"`).
 *
 * The file must exist and be at most 10 MB. It is decoded, downscaled so its
 * longest side is at most [MAX_IMAGE_SIDE_PX] pixels, JPEG-compressed at
 * quality 85, and sent to the model as a base64 `data:` URL in a single
 * vision call. The endpoint, key, and model come from [KeyStore] at call
 * time (BYOK — nothing is baked in). The only network use is that one
 * vision call.
 *
 * Failures are reported as `ToolResult(ok = false, ...)` — [execute] never
 * throws (except coroutine cancellation, which propagates as usual). When
 * the model does not support image input, the error says so plainly instead
 * of returning an invented description.
 *
 * [context] is nullable with a null default so JVM unit tests can construct
 * the tool: an Android [Context] cannot be instantiated on the JVM (its
 * stub constructor throws), and the file-validation error paths under test
 * return before any settings are read. With a null context the tool reports
 * itself as not configured, exactly as it would with no key stored. The
 * normal call site always passes a real context.
 */
class ReadImageTool(private val context: Context? = null) : Tool {

    override val name: String = "read_image"

    override val description: String =
        "Reads an image file from the app's private storage and describes what it shows " +
            "using the configured vision-capable model."

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Absolute path to the image file in the app's private storage")
            }
            putJsonObject("prompt") {
                put("type", "string")
                put("description", "What to ask about the image; a detailed description is used when omitted")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("path"))
        }
    }

    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        return try {
            withContext(Dispatchers.IO) { executeInternal(args) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ToolResult(
                ok = false,
                output = "",
                error = e.message?.takeIf { it.isNotBlank() } ?: "Image could not be read",
            )
        }
    }

    private suspend fun executeInternal(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: path")
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_IMAGE_PROMPT

        val file = File(path)
        if (!file.exists()) {
            return ToolResult(ok = false, output = "", error = "Image file not found: $path")
        }
        if (!file.isFile) {
            return ToolResult(ok = false, output = "", error = "Path is not a file: $path")
        }
        if (file.length() > MAX_IMAGE_BYTES) {
            return ToolResult(
                ok = false,
                output = "",
                error = "Image file is larger than 10 MB: $path",
            )
        }

        val ctx = context
            ?: return ToolResult(
                ok = false,
                output = "",
                error = "Add your API endpoint and key in Settings first",
            )
        val keyStore = KeyStore(ctx)
        val baseUrl = keyStore.getBaseUrl()
        val apiKey = keyStore.getApiKey()
        val model = keyStore.getModel()
        if (apiKey.isNullOrBlank() || baseUrl.isBlank()) {
            return ToolResult(
                ok = false,
                output = "",
                error = "Add your API endpoint and key in Settings first",
            )
        }

        val bitmap = decodeDownsampled(file)
            ?: return ToolResult(ok = false, output = "", error = "Could not decode image file: $path")
        try {
            val (targetWidth, targetHeight) = scaledDimensions(bitmap.width, bitmap.height, MAX_IMAGE_SIDE_PX)
            val scaled = if (targetWidth == bitmap.width && targetHeight == bitmap.height) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            }
            try {
                val out = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)) {
                    return ToolResult(ok = false, output = "", error = "Could not encode image file: $path")
                }
                val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                val client = OkHttpOpenAiClient(baseUrl, apiKey, model)
                val description = try {
                    client.describeImage(prompt, base64, "image/jpeg", model)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return ToolResult(ok = false, output = "", error = visionErrorMessage(e))
                }
                return ToolResult(ok = true, output = description)
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Decodes [file], downsampling in powers of two first so a very large
     * image cannot exhaust memory before the exact [MAX_IMAGE_SIDE_PX]
     * downscale in [executeInternal]. Returns null when the file is not a
     * decodable image.
     */
    private fun decodeDownsampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        var sampleSize = 1
        while (max(width, height) / sampleSize > MAX_IMAGE_SIDE_PX * 2) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    /**
     * Maps a vision-call failure to a user-facing error. A model that does
     * not support image input reports it plainly; every other failure keeps
     * its own message.
     */
    private fun visionErrorMessage(error: Throwable): String {
        val detail = error.message.orEmpty()
        val lower = detail.lowercase()
        return if (lower.contains("image") || lower.contains("vision")) {
            "This model does not support image input"
        } else {
            detail.ifBlank { "Image description failed" }
        }
    }
}
