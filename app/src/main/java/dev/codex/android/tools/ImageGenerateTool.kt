package dev.codex.android.tools

import android.content.Context
import dev.codex.android.api.OkHttpOpenAiClient
import dev.codex.android.core.CodexJson
import dev.codex.android.data.KeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * BYOK image generation (`name = "image-generate"`).
 *
 * POSTs `{baseUrl}/images/generations` with `{prompt, size, n: 1}` (plus
 * `model` when the caller supplies one) and a Bearer API key — the same
 * base URL + API key plumbing [OkHttpOpenAiClient] uses, read from the
 * user's own settings ([KeyStore.getBaseUrl] / [KeyStore.getApiKey]).
 * No provider, endpoint, or key is ever baked in.
 *
 * Accepts both response shapes: `data[0].b64_json` (decoded and saved)
 * and `data[0].url` (downloaded and saved). The image bytes are saved
 * under [outputDir] (or a caller-supplied path) and the tool returns the
 * saved file path and byte size.
 *
 * Graceful degradation, never faked: when the provider has no image
 * endpoint (404/405/unsupported-model) the error says so plainly and
 * names the provider's response; when no key/base URL is configured
 * nothing is called at all.
 */
class ImageGenerateTool(
    private val baseUrl: String,
    private val apiKey: String,
    private val outputDir: File,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) : Tool {

    /**
     * Convenience constructor wired to the app's settings: base URL and
     * key come from [KeyStore], files land in `<filesDir>/images/`.
     */
    constructor(
        context: Context,
        httpClient: OkHttpClient = defaultHttpClient(),
    ) : this(
        baseUrl = KeyStore(context.applicationContext).getBaseUrl(),
        apiKey = KeyStore(context.applicationContext).getApiKey() ?: "",
        outputDir = File(context.applicationContext.filesDir, "images"),
        httpClient = httpClient,
    )

    override val name: String = "image-generate"
    override val description: String =
        "Generates an image from a text prompt using the user's own configured " +
            "BYOK image endpoint (POST {baseUrl}/images/generations) and saves it to a file. " +
            "Returns the saved file path and size. If the provider has no image endpoint, " +
            "the error says so instead of faking an image."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("prompt") {
                put("type", "string")
                put("description", "Text prompt describing the image to generate")
            }
            putJsonObject("size") {
                put("type", "string")
                put("description", "Image size, e.g. \"1024x1024\" (default \"1024x1024\")")
            }
            putJsonObject("model") {
                put("type", "string")
                put("description", "Optional image model id, for providers that require one")
            }
            putJsonObject("output_path") {
                put("type", "string")
                put("description", "Optional output path: absolute, or relative to the images directory")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("prompt"))
        }
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val cleanBase = baseUrl.trim()
        val cleanKey = apiKey.trim()
        if (cleanBase.isEmpty() || cleanKey.isEmpty()) {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = NO_PROVIDER_ERROR,
            )
        }

        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return@withContext ToolResult(ok = false, output = "", error = "Missing required argument: prompt")
        val size = args["size"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_SIZE
        if (!SIZE_REGEX.matches(size)) {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = "Invalid size \"$size\": expected e.g. \"1024x1024\"",
            )
        }
        val model = args["model"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
        val outputPath = args["output_path"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }

        val url = "${OkHttpOpenAiClient.normaliseBaseUrl(cleanBase)}/images/generations"
        val requestJson = buildJsonObject {
            put("prompt", prompt)
            put("size", size)
            put("n", 1)
            if (model != null) put("model", model)
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $cleanKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(CodexJson.encodeToString(JsonObject.serializer(), requestJson).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val responseBody = try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val snippet = resp.body?.string().orEmpty().take(ERROR_SNIPPET_CHARS)
                    return@withContext ToolResult(ok = false, output = "", error = mapHttpError(resp.code, snippet))
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = "Image generation request failed: ${e.message ?: e.javaClass.simpleName}",
            )
        }

        val parsed = parseImageResponse(responseBody)
            ?: return@withContext ToolResult(
                ok = false,
                output = "",
                error = "Image endpoint returned an unrecognised response (no usable data[0].b64_json or data[0].url)",
            )

        val (imageBytes, extension) = when (parsed) {
            is ImagePayload.B64 -> {
                val bytes = try {
                    Base64.getDecoder().decode(parsed.b64Json.replace("\\s".toRegex(), ""))
                } catch (e: IllegalArgumentException) {
                    return@withContext ToolResult(
                        ok = false,
                        output = "",
                        error = "Image endpoint returned invalid base64 image data",
                    )
                }
                bytes to sniffExtension(bytes)
            }
            is ImagePayload.Url -> {
                val downloaded = downloadImage(parsed.url, cleanBase, cleanKey)
                    ?: return@withContext ToolResult(
                        ok = false,
                        output = "",
                        error = "Failed to download the generated image from ${parsed.url}",
                    )
                downloaded
            }
        }

        if (imageBytes.isEmpty()) {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = "Image endpoint returned empty image data",
            )
        }

        val target = resolveOutputFile(outputPath, extension)
        try {
            target.parentFile?.mkdirs()
            target.writeBytes(imageBytes)
        } catch (e: IOException) {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = "Could not save image to ${target.absolutePath}: ${e.message}",
            )
        }
        ToolResult(
            ok = true,
            output = "Saved image to ${target.absolutePath} (${imageBytes.size} bytes, $extension)",
        )
    }

    /** Downloads the image at [imageUrl]; returns bytes + extension, or null on failure. */
    private fun downloadImage(imageUrl: String, base: String, key: String): Pair<ByteArray, String>? {
        val baseHost = runCatching { java.net.URI(base).host?.lowercase() }.getOrNull()
        val imageHost = runCatching { java.net.URI(imageUrl).host?.lowercase() }.getOrNull()
        val requestBuilder = Request.Builder().url(imageUrl).get()
            .header("Accept", "image/*")
        // Only send the key back to the provider itself; a third-party CDN
        // URL must not receive the user's API key.
        if (baseHost != null && baseHost == imageHost) {
            requestBuilder.header("Authorization", "Bearer $key")
        }
        return try {
            httpClient.newCall(requestBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                val ext = extensionFromUrlOrContentType(imageUrl, resp.header("Content-Type"))
                    .takeIf { it != "png" } ?: sniffExtension(bytes)
                bytes to ext
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun resolveOutputFile(requested: String?, extension: String): File {
        if (requested.isNullOrBlank()) {
            return File(outputDir, "image-${System.currentTimeMillis()}.$extension")
        }
        val raw = File(requested)
        val target = if (raw.isAbsolute) raw else File(outputDir, requested)
        return if (requested.endsWith("/") || target.isDirectory) {
            File(target, "image-${System.currentTimeMillis()}.$extension")
        } else {
            target
        }
    }

    internal sealed interface ImagePayload {
        data class B64(val b64Json: String) : ImagePayload
        data class Url(val url: String) : ImagePayload
    }

    companion object {
        const val NO_PROVIDER_ERROR: String =
            "No image provider configured: add your API base URL and key in Settings first. " +
                "This tool never calls anything without your own (BYOK) credentials."
        const val DEFAULT_SIZE: String = "1024x1024"
        private const val ERROR_SNIPPET_CHARS: Int = 500
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val SIZE_REGEX = Regex("""^\d+x\d+$""")

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Maps a failed generation call to a plain, honest error. Never fakes an image. */
        internal fun mapHttpError(code: Int, bodySnippet: String): String {
            val detail = bodySnippet.trim().takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
            return when (code) {
                404, 405 -> "This provider does not expose an image generation endpoint " +
                    "(HTTP $code from /images/generations$detail). " +
                    "Use a provider that offers image generation, or a different model."
                401, 403 -> "Image generation rejected (HTTP $code): check your API key$detail"
                400 -> "Image generation rejected (HTTP 400): the provider did not accept the request$detail"
                429 -> "Image generation rate-limited (HTTP 429). Try again later$detail"
                else -> "Image generation failed (HTTP $code)$detail"
            }
        }

        /** Parses `{"data":[{"b64_json":...}]}` or `{"data":[{"url":...}]}`. */
        internal fun parseImageResponse(rawJson: String): ImagePayload? {
            if (rawJson.isBlank()) return null
            val root = runCatching { CodexJson.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()
                ?: return null
            val first = root["data"]?.jsonArray?.firstOrNull() as? JsonObject ?: return null
            val b64 = (first["b64_json"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            if (b64 != null) return ImagePayload.B64(b64)
            val url = (first["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            if (url != null) return ImagePayload.Url(url)
            return null
        }

        /** Sniffs the image format from magic bytes; defaults to png. */
        internal fun sniffExtension(bytes: ByteArray): String {
            if (bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            ) return "png"
            if (bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
            ) return "jpg"
            if (bytes.size >= 12 &&
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
            ) return "webp"
            if (bytes.size >= 6 &&
                bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
            ) return "gif"
            return "png"
        }

        private fun extensionFromUrlOrContentType(url: String, contentType: String?): String {
            val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            when (mediaType) {
                "image/jpeg" -> return "jpg"
                "image/png" -> return "png"
                "image/webp" -> return "webp"
                "image/gif" -> return "gif"
            }
            val pathExt = url.substringBefore('?').substringAfterLast('.', "").lowercase()
            if (pathExt in setOf("png", "jpg", "jpeg", "webp", "gif")) {
                return if (pathExt == "jpeg") "jpg" else pathExt
            }
            return "png"
        }
    }
}
