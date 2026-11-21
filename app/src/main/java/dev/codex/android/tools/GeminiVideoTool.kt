package dev.codex.android.tools

import android.content.Context
import dev.codex.android.api.GeminiVideoClient
import dev.codex.android.api.GeminiVideoRequest
import dev.codex.android.api.ShellOutcome
import dev.codex.android.api.ShellRunner
import dev.codex.android.data.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import java.io.File

/**
 * Video generation / editing with Gemini Omni Flash (`name = "gemini_video"`).
 *
 * Delegates to [GeminiVideoClient]: validates the request against the
 * Omni Flash limits, prepares source clips with ffmpeg when a
 * [shellRunner] is wired (the coordinator passes the Linux workspace
 * shell — without one, source clips are sent as-is and the result says
 * so), uploads media through the Files API, creates an Interactions API
 * interaction, polls until it completes, and saves the returned .mp4.
 *
 * BYOK only: the Gemini API key comes from the constructor (the
 * coordinator passes the user's own Settings key) — never baked in.
 * The tool never invents a video: on any failure it returns the real
 * error, and the output path it reports is a file it actually wrote.
 */
class GeminiVideoTool(
    private val apiKey: String,
    private val outputDir: File,
    private val httpClient: OkHttpClient = GeminiVideoClient.defaultHttpClient(),
    private val shellRunner: ShellRunner? = null,
    private val clientFactory: (String, File, OkHttpClient, ShellRunner?) -> GeminiVideoClient =
        { key, dir, http, shell -> GeminiVideoClient(key, dir, http, shell) },
) : Tool {

    /**
     * Convenience constructor wired to the app's settings: the key comes
     * from [KeyStore], videos land in `<filesDir>/videos/`. Pass a
     * [shellRunner] to enable ffmpeg prep (the coordinator adapts the
     * Linux workspace shell to [ShellOutcome]).
     */
    constructor(
        context: Context,
        httpClient: OkHttpClient = GeminiVideoClient.defaultHttpClient(),
        shellRunner: ShellRunner? = null,
    ) : this(
        apiKey = KeyStore(context.applicationContext).getApiKey() ?: "",
        outputDir = File(context.applicationContext.filesDir, "videos"),
        httpClient = httpClient,
        shellRunner = shellRunner,
    )

    override val name: String = "gemini_video"
    override val description: String =
        "Generates or edits a short video with Gemini Omni Flash (gemini-omni-1.2-flash) using the user's own Gemini API key. " +
            "Text-to-video (3-15s), first-frame and first+last-frame transitions (3-10s for two frames), extensions (3-10s per turn, up to 30s total), " +
            "reference-guided generation, and video editing. Prepares source clips with ffmpeg when a Linux shell is wired. " +
            "Returns the saved .mp4 path. Never fakes a video file."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("prompt") {
                put("type", "string")
                put("description", "What the video should show, or how to edit/extend it")
            }
            putJsonObject("duration_seconds") {
                put("type", "integer")
                put("description", "Clip length in seconds: 3-15 (3-10 for two-frame transitions and extensions; default 10)")
            }
            putJsonObject("resolution") {
                put("type", "string")
                put("description", "360p, 720p (default), 1080p or 4k")
            }
            putJsonObject("aspect_ratio") {
                put("type", "string")
                put("description", "16:9 (default) or 9:16")
            }
            putJsonObject("first_frame") {
                put("type", "string")
                put("description", "Path to the starting-frame image (png/jpg/webp)")
            }
            putJsonObject("last_frame") {
                put("type", "string")
                put("description", "Path to the final-frame image; requires first_frame")
            }
            putJsonObject("reference_images") {
                put("type", "array")
                put("description", "Up to 6 reference image paths (JSON array of strings)")
            }
            putJsonObject("reference_videos") {
                put("type", "array")
                put("description", "Reference video paths for new videos only, up to 3 (1 for 11-15s clips)")
            }
            putJsonObject("input_video") {
                put("type", "string")
                put("description", "Source video path to edit or extend (must be 10s or shorter)")
            }
            putJsonObject("previous_interaction_id") {
                put("type", "string")
                put("description", "Interaction id (v1_...) of a previous generation to edit or extend turn-by-turn")
            }
            putJsonObject("strip_audio") {
                put("type", "boolean")
                put("description", "true to strip the source audio so all sound is regenerated (default false: keep original audio)")
            }
            putJsonObject("output_path") {
                put("type", "string")
                put("description", "Optional output path for the .mp4 (absolute, or relative to the videos directory)")
            }
            putJsonObject("timeout_ms") {
                put("type", "integer")
                put("description", "How long to poll for completion (default 600000, 10 minutes)")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("prompt")) }
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@withContext ToolResult(ok = false, output = "", error = "Missing required argument: prompt")
        val request = GeminiVideoRequest(
            prompt = prompt,
            durationSeconds = (args["duration_seconds"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 10,
            resolution = args["resolution"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: "720p",
            aspectRatio = args["aspect_ratio"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: "16:9",
            firstFramePath = args["first_frame"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            lastFramePath = args["last_frame"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            referenceImagePaths = stringListArg(args, "reference_images"),
            referenceVideoPaths = stringListArg(args, "reference_videos"),
            inputVideoPath = args["input_video"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            previousInteractionId = args["previous_interaction_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            stripAudio = (args["strip_audio"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            outputPath = args["output_path"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            timeoutMs = (args["timeout_ms"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: GeminiVideoClient.DEFAULT_TIMEOUT_MS,
        )
        val client = clientFactory(apiKey, outputDir, httpClient, shellRunner)
        val outcome = client.generate(request)
        if (outcome.ok) {
            ToolResult(
                ok = true,
                output = buildString {
                    append("Saved video to ${outcome.outputPath} (${outcome.bytes} bytes, interaction ${outcome.interactionId})")
                    outcome.note?.takeIf { it.isNotBlank() }?.let { append("\n$it") }
                },
            )
        } else {
            ToolResult(ok = false, output = "", error = outcome.error ?: "Video generation failed")
        }
    }

    companion object {
        /** Accepts a JSON array of strings, or a comma-separated string. */
        internal fun stringListArg(args: JsonObject, name: String): List<String> {
            val element = args[name] ?: return emptyList()
            if (element is JsonArray) {
                return element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
            }
            val raw = (element as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (raw.isBlank()) return emptyList()
            return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}
