package dev.codex.android.api

import dev.codex.android.core.CodexJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One incremental update from a streamed chat-completions response.
 *
 * @property textDelta new assistant text in this chunk (null when none)
 * @property toolCallDeltas partial tool-call fragments, keyed by their
 *   index in the response (arguments arrive in pieces and are concatenated)
 * @property finishReason set on the final chunk ("stop", "tool_calls", ...)
 * @property done true when the stream sent "[DONE]"
 */
data class StreamDelta(
    val textDelta: String? = null,
    val toolCallDeltas: Map<Int, ToolCallDelta> = emptyMap(),
    val finishReason: String? = null,
    val done: Boolean = false,
)

/** One fragment of a streamed tool call (arguments accumulate across chunks). */
data class ToolCallDelta(
    val id: String? = null,
    val name: String? = null,
    val argumentsDelta: String? = null,
)

/**
 * Minimal Server-Sent Events parser for OpenAI-compatible streaming.
 *
 * Reads `data: {...}` lines; "[DONE]" ends the stream. Only the fields the
 * agent loop needs are extracted (delta content, delta tool calls,
 * finish_reason). Malformed lines are skipped, never thrown — a bad chunk
 * must not kill the whole stream.
 */
object SseParser {

    /** Parses one SSE `data:` payload (without the "data:" prefix) into a [StreamDelta]. */
    fun parseDataLine(payload: String): StreamDelta {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return StreamDelta()
        if (trimmed == "[DONE]") return StreamDelta(done = true)
        val element = try {
            CodexJson.parseToJsonElement(trimmed)
        } catch (e: Exception) {
            return StreamDelta()
        }
        val root = element as? JsonObject ?: return StreamDelta()
        val choices = root["choices"]?.jsonArray ?: return StreamDelta()
        if (choices.isEmpty()) return StreamDelta()
        val choice = choices[0].jsonObject
        val finishReason = (choice["finish_reason"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        val delta = choice["delta"]?.jsonObject
        if (delta == null) return StreamDelta(finishReason = finishReason)
        val textDelta = (delta["content"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        val toolCallDeltas = mutableMapOf<Int, ToolCallDelta>()
        val rawToolCalls = delta["tool_calls"]?.jsonArray
        if (rawToolCalls != null) {
            for (raw in rawToolCalls) {
                val obj = raw as? JsonObject ?: continue
                val index = (obj["index"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 0
                val id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                val functionObj = obj["function"]?.jsonObject
                val name = (functionObj?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                val argsDelta = (functionObj?.get("arguments") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                toolCallDeltas[index] = ToolCallDelta(id = id, name = name, argumentsDelta = argsDelta)
            }
        }
        return StreamDelta(
            textDelta = textDelta,
            toolCallDeltas = toolCallDeltas,
            finishReason = finishReason,
        )
    }
}
