package dev.codex.android.memory

import dev.codex.android.tools.Tool
import dev.codex.android.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Forgets a saved memory note by its name.
 *
 * The agent could previously only ADD notes from chat (`memory_save`);
 * it had no way to remove or replace one, so a corrected fact left the
 * stale note standing next to the new one and both surfaced on recall.
 * This tool closes the manage side: the agent forgets a note when the
 * user asks ("forget that", "don't remember this") and when the user
 * corrects a saved fact (forget the old note, then save the new one
 * with `memory_save`). If the exact name is unknown, the agent searches
 * memory first. Registered chat-only, next to `memory_save`.
 */
class MemoryForgetTool(
    private val forget: suspend (key: String) -> Boolean,
) : Tool {
    override val name = "memory_forget"
    override val description = "Forgets a saved memory note by its name. " +
        "Call when the user asks you to forget something ('forget that', 'don't remember this'), " +
        "or when they correct a saved fact — forget the old note, then save the new one with memory_save. " +
        "If you do not know the note's exact name, search memory first to find it."
    override val sensitive = false
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("key") {
                put("type", "string")
                put("description", "The exact name of the saved note to forget, as shown by a memory search or /memory")
            }
        }
        putJsonArray("required") { add("key") }
        put("additionalProperties", false)
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (key.isBlank()) return ToolResult(false, "", "Provide the name of the note to forget.")
        return if (forget(key)) ToolResult(true, "Forgot the saved note.")
        else ToolResult(false, "", "Could not remove that note. Check its name with /memory.")
    }
}
