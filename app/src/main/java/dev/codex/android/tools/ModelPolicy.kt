package dev.codex.android.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Whether a model is known to have a capability.
 *
 * UNKNOWN means the capability could not be verified against the
 * provider's own documentation at the time this policy was written —
 * never a guess. The policy would rather say "unknown" than assert a
 * capability the provider may not expose through its OpenAI-compatible
 * endpoint.
 */
enum class ModelCapability { YES, NO, UNKNOWN }

/**
 * One known model in the policy.
 *
 * @property contextWindow verified context window in tokens, or null when unknown.
 * @property maxOutputTokens verified maximum output tokens, or null when unknown.
 */
data class ModelPolicyEntry(
    val id: String,
    val provider: String,
    val vision: ModelCapability,
    val imageGeneration: ModelCapability,
    val contextWindow: Int?,
    val maxOutputTokens: Int?,
    val notes: String,
)

/**
 * Data-driven model policy for the models the user actually uses.
 *
 * Verified against provider documentation on 2026-11-21 (see notes):
 * - gpt-6-luna: OpenAI GPT-6 family — text + image input, 1,050,000-token
 *   context, 128,000 max output.
 * - gemini-3-8-flash: specs are Google's Gemini 3 Flash family (1M input,
 *   65,536 max output, multimodal incl. images); the `gemini-3-8-flash`
 *   id itself is a Coarena-served alias whose exact serving config is
 *   unverified — the notes say so.
 * - deepseek-v4-1-flash-1017: specs are DeepSeek-V4.1-Flash (NVIDIA NIM
 *   model card) — multimodal text + image input, 1,048,576 combined
 *   context; the Coarena checkpoint id's exact serving config is
 *   unverified — the notes say so. Max output is unknown (NVIDIA only
 *   recommends `max_tokens` of at least 256K, which is not a maximum).
 * - nvidia/nemotron-3-ultra-550b-a55b: NVIDIA NIM model card — input type
 *   Text only (no vision, no image generation), context up to 1M tokens.
 *   Max output is unknown (not published on the model card).
 * - gemini-4-hydrogen: Google DeepMind model card (12 Nov 2026) — inputs
 *   text, images, audio, video; unified 1,048,576-token context; up to
 *   65,536 default synchronous output tokens.
 *
 * Image generation is UNKNOWN for every model here: none of these is an
 * image model, and whether a given provider's OpenAI-compatible endpoint
 * exposes `/images/generations` for them was not verifiable — the
 * `image-generate` tool reports that live instead of this policy guessing.
 */
object ModelPolicy {

    val MODELS: List<ModelPolicyEntry> = listOf(
        ModelPolicyEntry(
            id = "gpt-6-luna",
            provider = "Coarena (OpenAI GPT-6 family)",
            vision = ModelCapability.YES,
            imageGeneration = ModelCapability.UNKNOWN,
            contextWindow = 1_050_000,
            maxOutputTokens = 128_000,
            notes = "Smallest, cheapest GPT-6 model; text + image input, text output. " +
                "The app's configured default chat model (Coarena free). " +
                "Image generation via this provider's endpoint is unverified.",
        ),
        ModelPolicyEntry(
            id = "gemini-3-8-flash",
            provider = "Coarena (Google Gemini 3 Flash family)",
            vision = ModelCapability.YES,
            imageGeneration = ModelCapability.UNKNOWN,
            contextWindow = 1_048_576,
            maxOutputTokens = 65_536,
            notes = "Context/output figures are Google's Gemini 3 Flash family specs " +
                "(multimodal: text, image, audio, video in). The 'gemini-3-8-flash' id " +
                "is a Coarena-served alias; its exact serving config is unverified. " +
                "Image generation via this provider's endpoint is unverified.",
        ),
        ModelPolicyEntry(
            id = "deepseek-v4-1-flash-1017",
            provider = "Coarena (DeepSeek V4.1 Flash family)",
            vision = ModelCapability.YES,
            imageGeneration = ModelCapability.UNKNOWN,
            contextWindow = 1_048_576,
            maxOutputTokens = null,
            notes = "Multimodal (text + image input) per the DeepSeek-V4.1-Flash model card; " +
                "max output tokens unknown. The 'deepseek-v4-1-flash-1017' id is a " +
                "Coarena-served checkpoint; its exact serving config is unverified. " +
                "Image generation via this provider's endpoint is unverified.",
        ),
        ModelPolicyEntry(
            id = "nvidia/nemotron-3-ultra-550b-a55b",
            provider = "NVIDIA NIM",
            vision = ModelCapability.NO,
            imageGeneration = ModelCapability.NO,
            contextWindow = 1_048_576,
            maxOutputTokens = null,
            notes = "Frontier-scale reasoning/chat model (550B total / 55B active params), " +
                "built for agentic reasoning and code; NVIDIA model card lists input type " +
                "as Text only, so no vision and no image generation. Max output tokens unknown.",
        ),
        ModelPolicyEntry(
            id = "gemini-4-hydrogen",
            provider = "AI Studio (Google Gemini 4 family)",
            vision = ModelCapability.YES,
            imageGeneration = ModelCapability.UNKNOWN,
            contextWindow = 1_048_576,
            maxOutputTokens = 65_536,
            notes = "Speed-focused Gemini 4 model for everyday coding and agentic workflows " +
                "(DeepMind model card, 12 Nov 2026); inputs text, images, audio, video; " +
                "unified 1M context, up to 64K default synchronous output. " +
                "Image generation via this provider's endpoint is unverified.",
        ),
    )

    /** Exact match, then case-insensitive match; null when not in the policy. */
    fun lookup(id: String): ModelPolicyEntry? {
        val clean = id.trim()
        if (clean.isEmpty()) return null
        return MODELS.firstOrNull { it.id == clean }
            ?: MODELS.firstOrNull { it.id.equals(clean, ignoreCase = true) }
    }

    /** Kinds of task the policy can route. Bare "image" means image *generation* ("vision" is the image-input task). */
    enum class TaskKind { IMAGE_GENERATION, VISION, LONG_CONTEXT, CODE, GENERAL }

    /** Classifies free-form task text. Image generation is checked before image input. */
    fun classify(task: String): TaskKind {
        val t = task.trim().lowercase()
        return when {
            t.isEmpty() -> TaskKind.GENERAL
            t == "image" || "image generat" in t || "generat an image" in t ||
                "generat image" in t || "generate an image" in t ||
                ("generat" in t && ("image" in t || "picture" in t || "photo" in t ||
                    "art" in t || "logo" in t || "poster" in t)) ||
                "draw" in t || "paint" in t ||
                "create an image" in t || "make an image" in t ->
                TaskKind.IMAGE_GENERATION
            "vision" in t || "image input" in t || "picture" in t || "photo" in t ||
                "screenshot" in t || "diagram" in t || "chart" in t || "ocr" in t ||
                "read this image" in t || "describe the image" in t || "what is in this image" in t ->
                TaskKind.VISION
            "long" in t && "context" in t || "codebase" in t || "whole repo" in t ||
                "large document" in t || "long document" in t ->
                TaskKind.LONG_CONTEXT
            "code" in t || "coding" in t || "debug" in t || "program" in t || "refactor" in t ->
                TaskKind.CODE
            else -> TaskKind.GENERAL
        }
    }

    /**
     * Best model for a task, or null when no model in the policy is known
     * to support it. Task matching is keyword-based and forgiving (see
     * [classify]): "vision" / "picture" / "screenshot" → image input,
     * bare "image" / "draw" → image generation, "long context" /
     * "codebase", "code" / "coding", anything else → general.
     */
    fun recommend(task: String): ModelPolicyEntry? {
        return when (classify(task)) {
            TaskKind.GENERAL -> MODELS.firstOrNull { it.id == "gpt-6-luna" } ?: MODELS.firstOrNull()
            TaskKind.IMAGE_GENERATION ->
                MODELS.firstOrNull { it.imageGeneration == ModelCapability.YES }
            TaskKind.VISION ->
                MODELS.filter { it.vision == ModelCapability.YES }
                    .maxByOrNull { it.contextWindow ?: 0 }
            TaskKind.LONG_CONTEXT ->
                MODELS.maxByOrNull { it.contextWindow ?: 0 }
            TaskKind.CODE ->
                MODELS.firstOrNull { it.id == "nvidia/nemotron-3-ultra-550b-a55b" }
        }
    }

    /** One-line human reason for a recommendation, or why none is possible. */
    fun reasonFor(task: String, model: ModelPolicyEntry?): String {
        if (model == null) {
            return when (classify(task)) {
                TaskKind.IMAGE_GENERATION ->
                    "No model in the policy is known to support image generation. " +
                        "Use the image-generate tool against your provider — it reports live " +
                        "whether the provider exposes an image endpoint instead of guessing."
                else -> "No model in the policy is known to support this task."
            }
        }
        return when (classify(task)) {
            TaskKind.IMAGE_GENERATION ->
                "${model.id} is listed as supporting image generation."
            TaskKind.VISION ->
                "${model.id} accepts image input and has the largest verified context window " +
                    "(${model.contextWindow?.let { "$it tokens" } ?: "unknown"}) among the vision-capable models in the policy."
            TaskKind.LONG_CONTEXT ->
                "${model.id} has the largest verified context window in the policy " +
                    "(${model.contextWindow?.let { "$it tokens" } ?: "unknown"})."
            TaskKind.CODE ->
                "${model.id} is the policy's frontier-scale reasoning model (550B total / 55B active), " +
                    "built by NVIDIA for agentic reasoning and code, with a 1M-token context window."
            TaskKind.GENERAL -> "${model.id} is the app's configured default chat model: fast, low-cost, " +
                "with a 1,050,000-token context window."
        }
    }

    fun formatEntry(entry: ModelPolicyEntry): String = buildString {
        appendLine("Model: ${entry.id}")
        appendLine("Provider: ${entry.provider}")
        appendLine("Vision (image input): ${capabilityText(entry.vision)}")
        appendLine("Image generation: ${capabilityText(entry.imageGeneration)}")
        appendLine("Context window: ${entry.contextWindow?.let { "$it tokens" } ?: "unknown"}")
        appendLine("Max output tokens: ${entry.maxOutputTokens?.let { "$it tokens" } ?: "unknown"}")
        append("Notes: ${entry.notes}")
    }

    fun capabilityText(capability: ModelCapability): String = when (capability) {
        ModelCapability.YES -> "yes"
        ModelCapability.NO -> "no"
        ModelCapability.UNKNOWN -> "unknown"
    }
}

/**
 * Model policy tool (`name = "model-policy"`).
 *
 * Answers, from [ModelPolicy], which model fits a task and what a given
 * model can do. Capabilities that could not be verified are reported as
 * "unknown", never asserted. An unknown model id is a plain statement
 * ("not in the policy"), not an error.
 */
object ModelPolicyTool : Tool {

    override val name: String = "model-policy"
    override val description: String =
        "Recommends the best model from the app's model policy for a task " +
            "(vision, image generation, long-context, code, general) and reports a model's " +
            "known capabilities (vision, image generation, context window, max output). " +
            "Unverified capabilities are reported as unknown, never guessed."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("task") {
                put("type", "string")
                put("description", "Task to pick a model for, e.g. \"vision\", \"image\", \"long-context\", \"code\"")
            }
            putJsonObject("model") {
                put("type", "string")
                put("description", "Model id to look up, e.g. \"gpt-6-luna\"")
            }
        }
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val task = args["task"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val modelId = args["model"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        if (modelId.isNotEmpty()) {
            val entry = ModelPolicy.lookup(modelId)
            if (entry == null) {
                return ToolResult(
                    ok = true,
                    output = "Model '$modelId' is not in the policy. Known models: " +
                        ModelPolicy.MODELS.joinToString(", ") { it.id } + ".",
                )
            }
            val base = ModelPolicy.formatEntry(entry)
            if (task.isEmpty()) return ToolResult(ok = true, output = base)
            val verdict = when (ModelPolicy.classify(task)) {
                ModelPolicy.TaskKind.IMAGE_GENERATION ->
                    "For image generation: ${ModelPolicy.capabilityText(entry.imageGeneration)}."
                ModelPolicy.TaskKind.VISION ->
                    "For vision: ${ModelPolicy.capabilityText(entry.vision)}."
                ModelPolicy.TaskKind.LONG_CONTEXT ->
                    "For long context: context window is " +
                        (entry.contextWindow?.let { "$it tokens" } ?: "unknown") + "."
                ModelPolicy.TaskKind.CODE ->
                    "For code: ${entry.id} is ${if (entry.id.contains("nemotron")) "the policy's recommended coding model" else "a general chat model, not the policy's recommended coding model (nvidia/nemotron-3-ultra-550b-a55b)"}."
                ModelPolicy.TaskKind.GENERAL -> null
            }
            val output = if (verdict != null) "$base\n$verdict" else base
            return ToolResult(ok = true, output = output)
        }

        if (task.isNotEmpty()) {
            val recommended = ModelPolicy.recommend(task)
            if (recommended == null) {
                return ToolResult(
                    ok = true,
                    output = "No recommendation for task '$task': " +
                        ModelPolicy.reasonFor(task, null),
                )
            }
            return ToolResult(
                ok = true,
                output = "Recommended model for '$task': ${recommended.id}\n" +
                    "Why: ${ModelPolicy.reasonFor(task, recommended)}\n" +
                    ModelPolicy.formatEntry(recommended),
            )
        }

        val listing = ModelPolicy.MODELS.joinToString("\n\n") { entry ->
            "- ${entry.id} (${entry.provider}): vision ${ModelPolicy.capabilityText(entry.vision)}, " +
                "image generation ${ModelPolicy.capabilityText(entry.imageGeneration)}, " +
                "context ${entry.contextWindow?.let { "$it tokens" } ?: "unknown"}"
        }
        return ToolResult(
            ok = true,
            output = "Models in the policy:\n\n$listing\n\nAsk with a task (e.g. \"vision\", \"code\") for a recommendation, or with a model id for full details.",
        )
    }
}
