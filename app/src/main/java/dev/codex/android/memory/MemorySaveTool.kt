package dev.codex.android.memory

import dev.codex.android.tools.Tool
import dev.codex.android.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Saves a note to the user's durable memory, on their explicit ask.
 *
 * The agent previously had no way to save a memory from chat: the only
 * write paths were the literal composer commands (`/remember ...`,
 * `remember that ...`) and the Notes tab, so "save that to memory" fell
 * through to the model, which truthfully said it had no memory-saving
 * tool. This tool closes that gap.
 *
 * Opt-in is preserved: the description restricts the tool to explicit
 * user requests ("remember this", "save that to memory"), and the agent
 * must never call it unprompted. The save itself goes through
 * [PersonalMemory.remember], so credentials are still refused and notes
 * are still capped at 400 characters. Registered chat-only (never in
 * withBuiltins, automations or the daemon), so no background run can
 * write a note while the user is not there asking for it.
 */
class MemorySaveTool(
    private val remember: suspend (key: String?, note: String) -> String,
) : Tool {
    override val name = "memory_save"
    override val description = "Saves a note to the user's memory so it is remembered in later chats. " +
        "Call whenever the user shares a durable fact, preference, decision or commitment — you do not " +
        "need to be asked — and also when they explicitly ask you to remember or save something " +
        "('remember this', 'save that to memory'). Never save credentials, keys or passwords, never save " +
        "something said only in passing, and never save what the user asks you to forget. " +
        "Write the note in the user's own words; keep it to one fact or preference."
    override val sensitive = false
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("note") {
                put("type", "string")
                put("description", "The fact or preference to remember, in the user's own words (1–400 characters)")
                put("maxLength", MAX_NOTE_LENGTH)
            }
            putJsonObject("key") {
                put("type", "string")
                put("description", "Optional short name for the note (letters, numbers, dots, underscores, hyphens, up to 48 characters). Omit it to let the app derive one from the note.")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("note")) }
        put("additionalProperties", false)
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val note = args["note"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (note.isBlank()) return ToolResult(false, "", "Provide the note to remember.")
        if (note.length > MAX_NOTE_LENGTH) {
            return ToolResult(false, "", "Keep the note to $MAX_NOTE_LENGTH characters or fewer.")
        }
        val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val reply = remember(key, note)
        return if (reply.startsWith("Remembered:")) ToolResult(true, reply)
        else ToolResult(false, "", reply)
    }
}
