package dev.codex.android.api

import dev.codex.android.core.CodexJson
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
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
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Outcome of one ffmpeg invocation inside the Linux workspace. */
data class ShellOutcome(val exitCode: Int, val stdout: String, val stderr: String)

/** Runs one shell command in the on-device Linux workspace; null means no shell is wired. */
typealias ShellRunner = suspend (String) -> ShellOutcome

/** Result of [GeminiVideoClient.generate]. Never fakes a video file. */
data class GeminiVideoOutcome(
    val ok: Boolean,
    val outputPath: String? = null,
    val bytes: Long = 0,
    val interactionId: String? = null,
    val note: String? = null,
    val error: String? = null,
)

/** One video-generation request. See [GeminiVideoClient.validate] for the limits. */
data class GeminiVideoRequest(
    val prompt: String,
    val durationSeconds: Int = 10,
    val resolution: String = "720p",
    val aspectRatio: String = "16:9",
    val firstFramePath: String? = null,
    val lastFramePath: String? = null,
    val referenceImagePaths: List<String> = emptyList(),
    val referenceVideoPaths: List<String> = emptyList(),
    val inputVideoPath: String? = null,
    val previousInteractionId: String? = null,
    val stripAudio: Boolean = false,
    val outputPath: String? = null,
    val timeoutMs: Long = GeminiVideoClient.DEFAULT_TIMEOUT_MS,
)

/** ffprobe summary of one local video file. */
data class VideoProbe(
    val durationSeconds: Double?,
    val width: Int?,
    val height: Int?,
    val hasAudio: Boolean,
)

/**
 * Video generation / editing with Gemini Omni Flash (`gemini-omni-1.2-flash`).
 *
 * Flow: validate args → prepare source clips with ffmpeg (trim to the
 * 10 s edit/extend limit, scale to 720p, strip audio when asked) →
 * upload media through the Files API and poll until ACTIVE → create an
 * Interactions API interaction → poll until completed → save the returned
 * video bytes. BYOK only: the user's own Gemini API key, sent as the
 * `x-goog-api-key` header; no key is ever baked in.
 *
 * Two honest limits. First, the Interactions REST field names used here
 * (`generation_config.video_config`, input parts typed `text` / `image` /
 * `video` with `uri` + `mime_type`) follow the shapes the Gemini skills
 * research describes — the `steps` / `model_output` response shape is
 * documented there — but no live video call has been made from this app
 * yet, so the first real BYOK call is the verification. Second, ffmpeg
 * only runs when the coordinator wires a [ShellRunner] (the Linux
 * workspace shell); without one, source clips are sent as-is and the
 * result says so.
 */
class GeminiVideoClient(
    private val apiKey: String,
    private val outputDir: File,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val shellRunner: ShellRunner? = null,
    private val restBase: String = REST_BASE,
    private val interactionPollIntervalMs: Long = 10_000L,
    private val filePollIntervalMs: Long = 2_000L,
) {

    suspend fun generate(request: GeminiVideoRequest): GeminiVideoOutcome {
        if (apiKey.isBlank()) {
            return GeminiVideoOutcome(
                ok = false,
                error = "No Gemini API key configured: add your own Gemini API key in Settings first. This tool never calls anything without your own (BYOK) key.",
            )
        }
        validate(request)?.let { problem ->
            return GeminiVideoOutcome(ok = false, error = problem)
        }
        val notes = mutableListOf<String>()
        return try {
            // 1. Prepare clips with ffmpeg when a shell is wired.
            val preparedInput: String? = if (request.inputVideoPath != null) {
                prepareSourceVideo(request.inputVideoPath, request.stripAudio, notes)
                    ?: return GeminiVideoOutcome(ok = false, error = "Could not prepare source video ${request.inputVideoPath} with ffmpeg.")
            } else {
                null
            }
            val preparedRefs = mutableListOf<String>()
            for (path in request.referenceVideoPaths) {
                preparedRefs += prepareReferenceVideo(path, notes)
                    ?: return GeminiVideoOutcome(ok = false, error = "Could not prepare reference video $path with ffmpeg.")
            }
            // 2. Upload media through the Files API.
            val mediaParts = mutableListOf<JsonObject>()
            suspend fun upload(path: String, kind: String, role: String) {
                val uploaded = uploadFile(File(path))
                mediaParts += buildJsonObject {
                    put("type", kind)
                    put("uri", uploaded.first)
                    put("mime_type", uploaded.second)
                }
                notes += "$role uploaded as ${uploaded.first}."
            }
            if (request.firstFramePath != null) upload(request.firstFramePath, "image", "First frame")
            if (request.lastFramePath != null) upload(request.lastFramePath, "image", "Last frame")
            request.referenceImagePaths.forEachIndexed { index, path ->
                upload(path, "image", "Reference image ${index + 1}")
            }
            preparedRefs.forEachIndexed { index, path ->
                upload(path, "video", "Reference video ${index + 1}")
            }
            if (preparedInput != null) upload(preparedInput, "video", "Source video")
            // 3. Create the interaction and poll until it finishes.
            val interactionId = createInteraction(request, mediaParts)
            val final = pollInteraction(interactionId, request.timeoutMs)
                ?: return GeminiVideoOutcome(
                    ok = false,
                    interactionId = interactionId,
                    error = "Video generation did not finish within ${request.timeoutMs / 1000}s (interaction $interactionId). " +
                        "The interaction may still be running server-side; retry with its id as previous_interaction_id.",
                )
            // 4. Save the returned video bytes.
            val saved = saveOutputVideo(final, request)
                ?: return GeminiVideoOutcome(
                    ok = false,
                    interactionId = interactionId,
                    error = "The interaction finished but returned no video (interaction $interactionId). " +
                        "An empty video output can mean the request is not available in this region — video upload/edit is restricted in the EEA, Switzerland, the UK and some US states.",
                )
            GeminiVideoOutcome(
                ok = true,
                outputPath = saved.first,
                bytes = saved.second,
                interactionId = interactionId,
                note = notes.joinToString(" ").takeIf { it.isNotBlank() },
            )
        } catch (e: IOException) {
            GeminiVideoOutcome(ok = false, error = "Video request failed: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            GeminiVideoOutcome(ok = false, error = "Video request failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Trims / scales / strips-audio a source video for editing or
     * extension (10 s max). Returns the path to use (the original when
     * it already fits, or the prepared copy). Returns null on failure.
     * When no shell is wired the original path is returned untouched
     * and the note says so.
     */
    private suspend fun prepareSourceVideo(
        path: String,
        stripAudio: Boolean,
        notes: MutableList<String>,
    ): String? {
        val shell = shellRunner ?: run {
            notes += "ffmpeg is not wired in this build: source video sent as-is — keep it 10s or shorter at 720p or below, or the API will reject it."
            return path
        }
        val probe = inspectVideo(path, shell)
        val needsTrim = (probe?.durationSeconds ?: 0.0) > SOURCE_MAX_SECONDS + 0.001
        val needsScale = (probe?.width ?: 0) > MAX_WIDTH || (probe?.height ?: 0) > MAX_HEIGHT
        if (!needsTrim && !needsScale && !stripAudio) return path
        val out = File(outputDir, "prep-${System.currentTimeMillis()}.mp4")
        val result = shell(buildPrepCommand(path, out.absolutePath, startSeconds = 0.0, durationSeconds = SOURCE_MAX_SECONDS.toInt(), stripAudio = stripAudio, scaleToFit = needsScale))
        if (result.exitCode != 0 || !out.isFile) {
            notes += "ffmpeg prep failed: ${result.stderr.take(300)}"
            return null
        }
        notes += "Source video prepared with ffmpeg (${out.name})."
        return out.absolutePath
    }

    /** Reference clips are ideally ~3 s; trim longer ones when a shell is wired. */
    private suspend fun prepareReferenceVideo(path: String, notes: MutableList<String>): String? {
        val shell = shellRunner ?: return path
        val probe = inspectVideo(path, shell)
        if ((probe?.durationSeconds ?: 0.0) <= REFERENCE_MAX_SECONDS + 0.001) return path
        val out = File(outputDir, "ref-${System.currentTimeMillis()}.mp4")
        val result = shell(buildPrepCommand(path, out.absolutePath, startSeconds = 0.0, durationSeconds = REFERENCE_MAX_SECONDS.toInt(), stripAudio = false, scaleToFit = false))
        if (result.exitCode != 0 || !out.isFile) {
            notes += "ffmpeg prep of reference video failed: ${result.stderr.take(300)}"
            return null
        }
        notes += "Reference video trimmed to 3s with ffmpeg (${out.name})."
        return out.absolutePath
    }

    private suspend fun inspectVideo(path: String, shell: ShellRunner): VideoProbe? {
        val result = shell(buildInspectCommand(path))
        if (result.exitCode != 0) return null
        return parseFfprobeJson(result.stdout)
    }

    /**
     * Uploads one file to the Files API and polls until it is ACTIVE.
     * Returns (uri, mimeType). Throws [IOException] on failure.
     */
    private suspend fun uploadFile(file: File): Pair<String, String> {
        val mime = mimeTypeFor(file.name)
            ?: throw IOException("Cannot upload ${file.name}: unknown media type (use png/jpg/webp for images, mp4/mov/webm for video).")
        if (!file.isFile) throw IOException("Media file not found: ${file.absolutePath}")
        val metadata = buildJsonObject {
            putJsonObject("file") { put("display_name", file.name) }
        }
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            // NOTE: no Content-Type in the part headers — OkHttp rejects
            // it there ("Unexpected header: Content-Type"). Each part's
            // media type comes from its RequestBody instead.
            .addPart(
                CodexJson.encodeToString(JsonObject.serializer(), metadata).toRequestBody(JSON_MEDIA_TYPE),
            )
            .addPart(
                okhttp3.Headers.headersOf(
                    "Content-Disposition",
                    "attachment; filename=\"${file.name}\"",
                ),
                file.asRequestBody(mime.toMediaType()),
            )
            .build()
        val request = Request.Builder()
            .url("$restBase/upload/v1beta/files")
            .header("x-goog-api-key", apiKey)
            .post(body)
            .build()
        val uploadedName: String = httpClient.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(mapHttpError(resp.code, raw))
            val root = CodexJson.parseToJsonElement(raw) as? JsonObject
                ?: throw IOException("Files API returned an unrecognised response.")
            val fileObj = root["file"] as? JsonObject
                ?: throw IOException("Files API returned no file object.")
            (fileObj["name"] as? JsonPrimitive)?.contentOrNull
                ?: throw IOException("Files API returned a file with no name.")
        }
        val deadline = System.currentTimeMillis() + FILE_ACTIVE_TIMEOUT_MS
        while (true) {
            val state = fileState(uploadedName)
            if (state == "ACTIVE") return uploadedName to mime
            if (state == "FAILED") throw IOException("Files API failed to process ${file.name} (state FAILED).")
            if (System.currentTimeMillis() > deadline) {
                throw IOException("Files API did not activate ${file.name} within ${FILE_ACTIVE_TIMEOUT_MS / 1000}s.")
            }
            delay(filePollIntervalMs)
        }
    }

    private fun fileState(name: String): String {
        val request = Request.Builder()
            .url("$restBase/v1beta/$name")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        return httpClient.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(mapHttpError(resp.code, raw))
            val root = CodexJson.parseToJsonElement(raw) as? JsonObject ?: return "FAILED"
            (root["state"] as? JsonPrimitive)?.contentOrNull ?: "FAILED"
        }
    }

    /** POST /v1beta/interactions; returns the interaction id. Throws [IOException] on failure. */
    private fun createInteraction(request: GeminiVideoRequest, mediaParts: List<JsonObject>): String {
        val promptWithRoles = buildPromptWithRoles(request)
        val bodyJson = buildJsonObject {
            put("model", VIDEO_MODEL)
            if (request.previousInteractionId != null) {
                put("previous_interaction_id", request.previousInteractionId)
            }
            if (mediaParts.isEmpty()) {
                put("input", promptWithRoles)
            } else {
                putJsonArray("input") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", promptWithRoles)
                    })
                    mediaParts.forEach { add(it) }
                }
            }
            putJsonObject("generation_config") {
                putJsonObject("video_config") {
                    put("duration", "${request.durationSeconds}s")
                    put("aspect_ratio", request.aspectRatio)
                    put("resolution", request.resolution)
                }
            }
        }
        val httpRequest = Request.Builder()
            .url("$restBase/v1beta/interactions")
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(CodexJson.encodeToString(JsonObject.serializer(), bodyJson).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return httpClient.newCall(httpRequest).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(mapHttpError(resp.code, raw))
            val root = CodexJson.parseToJsonElement(raw) as? JsonObject
                ?: throw IOException("Interactions API returned an unrecognised response.")
            (root["id"] as? JsonPrimitive)?.contentOrNull
                ?: throw IOException("Interactions API returned no interaction id.")
        }
    }

    /**
     * Polls GET /v1beta/interactions/{id} until `completed`, `failed`
     * or `cancelled`. Returns the final JSON on completion, or null on
     * timeout. Throws [IOException] on failed/cancelled or HTTP errors.
     */
    private suspend fun pollInteraction(interactionId: String, timeoutMs: Long): JsonObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val httpRequest = Request.Builder()
                .url("$restBase/v1beta/interactions/$interactionId")
                .header("x-goog-api-key", apiKey)
                .get()
                .build()
            val root = httpClient.newCall(httpRequest).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException(mapHttpError(resp.code, raw))
                CodexJson.parseToJsonElement(raw) as? JsonObject
                    ?: throw IOException("Interactions API returned an unrecognised response.")
            }
            val status = (root["status"] as? JsonPrimitive)?.contentOrNull
            when (status) {
                "completed" -> return root
                "failed", "cancelled" -> {
                    val detail = (root["error"] as? JsonObject)?.let { err ->
                        (err["message"] as? JsonPrimitive)?.contentOrNull
                    } ?: (root["error"] as? JsonPrimitive)?.contentOrNull
                    throw IOException(
                        "Video generation $status (interaction $interactionId)" +
                            (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."),
                    )
                }
                else -> {
                    if (System.currentTimeMillis() > deadline) return null
                    delay(interactionPollIntervalMs)
                }
            }
        }
    }

    /**
     * Finds the video in a completed interaction (`steps` → `model_output`
     * → content part of type `video`) and saves it. Returns
     * (path, byte count), or null when no video part exists.
     */
    private fun saveOutputVideo(final: JsonObject, request: GeminiVideoRequest): Pair<String, Long>? {
        val bytes = extractVideoBytes(final) ?: return null
        val target = resolveOutputFile(request.outputPath)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return target.absolutePath to bytes.size.toLong()
    }

    private fun extractVideoBytes(final: JsonObject): ByteArray? {
        val steps = final["steps"] as? JsonArray ?: return null
        for (stepEl in steps) {
            val step = stepEl as? JsonObject ?: continue
            if ((step["type"] as? JsonPrimitive)?.contentOrNull != "model_output") continue
            val content = step["content"] as? JsonArray ?: continue
            for (partEl in content) {
                val part = partEl as? JsonObject ?: continue
                if ((part["type"] as? JsonPrimitive)?.contentOrNull != "video") continue
                (part["data"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { base64 ->
                    return runCatching { Base64.getDecoder().decode(base64.replace("\\s".toRegex(), "")) }.getOrNull()
                }
                (part["uri"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { uri ->
                    return downloadVideoUri(uri)
                }
            }
        }
        return null
    }

    private fun downloadVideoUri(uri: String): ByteArray? {
        val url = when {
            uri.startsWith("http://") || uri.startsWith("https://") -> uri
            uri.startsWith("files/") -> "$restBase/v1beta/$uri:download?alt=media"
            else -> return null
        }
        val builder = Request.Builder().url(url).get()
        if (uri.startsWith("https://generativelanguage.googleapis.com") || uri.startsWith("files/")) {
            builder.header("x-goog-api-key", apiKey)
        }
        return try {
            httpClient.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.bytes()?.takeIf { it.isNotEmpty() }
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun resolveOutputFile(requested: String?): File {
        if (requested.isNullOrBlank()) {
            return File(outputDir, "video-${System.currentTimeMillis()}.mp4")
        }
        val raw = File(requested)
        val target = if (raw.isAbsolute) raw else File(outputDir, requested)
        return if (requested.endsWith("/")) File(target, "video-${System.currentTimeMillis()}.mp4") else target
    }

    /** Role sentences so the model binds each uploaded file to its job, in upload order. */
    private fun buildPromptWithRoles(request: GeminiVideoRequest): String {
        val roles = mutableListOf<String>()
        if (request.firstFramePath != null && request.lastFramePath != null) {
            roles += "Use the first image as the starting frame and the second image as the final frame, smoothly transitioning between them."
        } else if (request.firstFramePath != null) {
            roles += "Use this image as the starting frame."
        }
        if (request.referenceImagePaths.isNotEmpty()) {
            roles += "Use the given reference image(s) as character, prop, and environment references rather than literal starting frames."
        }
        if (request.referenceVideoPaths.isNotEmpty()) {
            roles += "Use the reference video(s) for character appearance, object details, and camera and motion style."
        }
        if (request.inputVideoPath != null) {
            roles += "Edit the given source video. Keep everything else the same."
        }
        return (roles + request.prompt).joinToString(" ")
    }

    companion object {
        const val REST_BASE: String = "https://generativelanguage.googleapis.com"
        const val VIDEO_MODEL: String = "gemini-omni-1.2-flash"
        const val DEFAULT_TIMEOUT_MS: Long = 600_000L
        const val FILE_ACTIVE_TIMEOUT_MS: Long = 60_000L

        /** Edit/extend source videos must be 10 s or shorter. */
        const val SOURCE_MAX_SECONDS: Int = 10

        /** Reference clips are ideally ~3 s. */
        const val REFERENCE_MAX_SECONDS: Int = 3

        const val MAX_WIDTH: Int = 1280
        const val MAX_HEIGHT: Int = 1280

        val RESOLUTIONS: Set<String> = setOf("360p", "720p", "1080p", "4k")
        val ASPECT_RATIOS: Set<String> = setOf("16:9", "9:16")

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * Arg validation, grounded in the Omni Flash limits. Returns the
         * problem, or null when the request is well-formed. Pure — JVM
         * tested. (A source video longer than 10 s is only caught here
         * when ffmpeg is wired; otherwise the API rejects it and the
         * error is reported verbatim.)
         */
        fun validate(request: GeminiVideoRequest): String? {
            if (request.prompt.isBlank()) return "Missing prompt: describe the video to generate or how to edit it."
            if (request.durationSeconds !in 3..15) {
                return "Invalid duration ${request.durationSeconds}s: video length must be 3 to 15 seconds."
            }
            if (request.lastFramePath != null && request.firstFramePath == null) {
                return "A last frame needs a first frame: pass both --first-frame and --last-frame together."
            }
            if (request.firstFramePath != null && request.lastFramePath != null && request.durationSeconds > 10) {
                return "First-and-last-frame transitions are capped at 10s; use 10s or less, or a single first frame for 11-15s."
            }
            if (request.resolution !in RESOLUTIONS) {
                return "Invalid resolution \"${request.resolution}\": expected one of 360p, 720p, 1080p, 4k."
            }
            if (request.aspectRatio !in ASPECT_RATIOS) {
                return "Invalid aspect ratio \"${request.aspectRatio}\": expected 16:9 or 9:16."
            }
            if (request.referenceImagePaths.size > 6) {
                return "Too many reference images (${request.referenceImagePaths.size}): at most 6."
            }
            if (request.referenceVideoPaths.size > 3) {
                return "Too many reference videos (${request.referenceVideoPaths.size}): at most 3."
            }
            if (request.referenceVideoPaths.size > 1 && request.durationSeconds > 10) {
                return "Clips of 11-15s take at most 1 reference video; use 1, or shorten the clip to 10s or less."
            }
            if (request.referenceVideoPaths.isNotEmpty() &&
                (request.inputVideoPath != null || request.previousInteractionId != null)
            ) {
                return "Reference videos only guide a new video — they cannot be used for video editing or extension. Use reference images instead."
            }
            if (request.timeoutMs < 1_000L) return "Timeout must be at least 1000ms."
            return null
        }

        /** ffmpeg trim/scale/strip-audio for one clip. Pure — JVM tested. */
        fun buildPrepCommand(
            inputPath: String,
            outputPath: String,
            startSeconds: Double = 0.0,
            durationSeconds: Int = SOURCE_MAX_SECONDS,
            stripAudio: Boolean = false,
            scaleToFit: Boolean = true,
        ): String {
            val parts = mutableListOf("ffmpeg", "-y")
            if (startSeconds > 0) parts += listOf("-ss", startSeconds.toString())
            parts += listOf("-i", quoteShellArg(inputPath))
            parts += listOf("-t", durationSeconds.toString())
            if (scaleToFit) parts += listOf("-vf", quoteShellArg("scale='min(1280,iw)':'min(720,ih)':force_original_aspect_ratio=decrease"))
            parts += listOf("-r", "24")
            if (stripAudio) parts += "-an"
            parts += listOf("-c:v", "libx264", "-pix_fmt", "yuv420p", quoteShellArg(outputPath))
            return parts.joinToString(" ")
        }

        /** ffprobe JSON summary for one clip. Pure — JVM tested. */
        fun buildInspectCommand(inputPath: String): String =
            "ffprobe -v quiet -print_format json -show_format -show_streams ${quoteShellArg(inputPath)}"

        /** Parses ffprobe `-print_format json` output. Returns null on unparseable input. */
        fun parseFfprobeJson(raw: String): VideoProbe? {
            val root = runCatching { CodexJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: return null
            val format = root["format"] as? JsonObject
            val duration = (format?.get("duration") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            var width: Int? = null
            var height: Int? = null
            var hasAudio = false
            val streams = root["streams"] as? JsonArray ?: return VideoProbe(duration, null, null, false)
            for (streamEl in streams) {
                val stream = streamEl as? JsonObject ?: continue
                when ((stream["codec_type"] as? JsonPrimitive)?.contentOrNull) {
                    "video" -> {
                        width = (stream["width"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: width
                        height = (stream["height"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: height
                    }
                    "audio" -> hasAudio = true
                }
            }
            return VideoProbe(duration, width, height, hasAudio)
        }

        /** Single-quote shell quoting (safe for paths with spaces). */
        fun quoteShellArg(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        fun mimeTypeFor(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            else -> null
        }

        /** Maps a failed Gemini call to a plain, honest error. Never fakes a video. */
        internal fun mapHttpError(code: Int, bodySnippet: String): String {
            val detail = bodySnippet.trim().take(300).takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
            return when (code) {
                401, 403 -> "Video request rejected (HTTP $code): check your Gemini API key$detail"
                400 -> "Video request rejected (HTTP 400): the Gemini API did not accept the request$detail"
                404 -> "Video endpoint not found (HTTP 404): the Interactions API path may have changed$detail"
                429 -> "Video request rate-limited (HTTP 429). Try again later$detail"
                else -> "Video request failed (HTTP $code)$detail"
            }
        }
    }
}
