package dev.codex.android.tools

import dev.codex.android.api.ToolCall
import dev.codex.android.core.CodexError
import dev.codex.android.core.CodexJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A validated tool call: the resolved [Tool] plus its parsed arguments.
 */
data class ParsedToolCall(val tool: Tool, val args: JsonObject)

/**
 * Parses and validates a model's [ToolCall] against the [registry].
 *
 * Only ever throws [CodexError.MalformedToolCall] on failure — never
 * returns null and never throws any other exception. A call is malformed
 * when:
 * - the tool name is unknown to the registry;
 * - `call.function.arguments` is not valid JSON or not a JSON object;
 * - a parameter listed in the schema's "required" array is missing;
 * - a present argument has the wrong JSON type for its schema-declared
 *   type (top-level parameters only).
 *
 * Arguments are parsed with the shared [CodexJson] instance from
 * `dev.codex.android.core` (defined in core/Json.kt).
 */
fun parseToolCall(call: ToolCall, registry: ToolRegistry): ParsedToolCall {
    try {
        return parseToolCallInternal(call, registry)
    } catch (e: CodexError.MalformedToolCall) {
        throw e
    } catch (e: Exception) {
        throw CodexError.MalformedToolCall(
            "Invalid tool call for '${call.function.name}': ${e.message}",
        )
    }
}

private fun parseToolCallInternal(call: ToolCall, registry: ToolRegistry): ParsedToolCall {
    val toolName = call.function.name
    val tool = registry.get(toolName)
        ?: throw CodexError.MalformedToolCall("Unknown tool: $toolName")

    val args = parseArguments(call.function.arguments, toolName)
    validateRequired(tool, args)
    validateTypes(tool, args)

    return ParsedToolCall(tool = tool, args = args)
}

private fun parseArguments(rawArguments: String, toolName: String): JsonObject {
    val element: JsonElement = try {
        CodexJson.parseToJsonElement(rawArguments)
    } catch (e: Exception) {
        throw CodexError.MalformedToolCall(
            "Arguments for tool '$toolName' are not valid JSON: ${e.message}",
        )
    }
    return element as? JsonObject
        ?: throw CodexError.MalformedToolCall(
            "Arguments for tool '$toolName' must be a JSON object",
        )
}

private fun validateRequired(tool: Tool, args: JsonObject) {
    val required = tool.parametersSchema["required"] as? JsonArray ?: return
    for (entry in required) {
        val name = entry.jsonPrimitive.content
        if (name !in args) {
            throw CodexError.MalformedToolCall(
                "Missing required parameter '$name' for tool '${tool.name}'",
            )
        }
    }
}

private fun validateTypes(tool: Tool, args: JsonObject) {
    val properties = tool.parametersSchema["properties"] as? JsonObject ?: return
    for ((name, value) in args) {
        val paramSchema = properties[name] as? JsonObject ?: continue
        val declaredType = (paramSchema["type"] as? JsonPrimitive)?.contentOrNull
            ?: continue
        if (!matchesType(value, declaredType)) {
            throw CodexError.MalformedToolCall(
                "Parameter '$name' for tool '${tool.name}' must be of type " +
                    "'$declaredType'",
            )
        }
    }
}

/** Top-level JSON type check for one argument value. Unknown schema types pass. */
private fun matchesType(value: JsonElement, declaredType: String): Boolean {
    if (value is JsonNull) return false
    return when (declaredType) {
        "string" -> value is JsonPrimitive && value.isString
        "number" -> {
            val primitive = value as? JsonPrimitive ?: return false
            !primitive.isString && primitive.doubleOrNull != null
        }
        "integer" -> {
            val primitive = value as? JsonPrimitive ?: return false
            if (primitive.isString) return false
            if (primitive.longOrNull != null) return true
            val asDouble = primitive.doubleOrNull ?: return false
            asDouble % 1.0 == 0.0
        }
        "boolean" -> {
            val primitive = value as? JsonPrimitive ?: return false
            primitive.booleanOrNull != null
        }
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        else -> true
    }
}
