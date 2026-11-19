package dev.codex.android.tools

import dev.codex.android.core.CodexJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP-style listing of the tools in this registry.
 *
 * [manifestJson] is the clean registry surface the agent and skills share:
 * any caller can list the tools and their input schemas without knowing the
 * [Tool] implementations. Tools carry no keys, tokens, or secrets, so none
 * can leak through the manifest.
 *
 * Shape:
 * ```
 * {
 *   "tools": [
 *     { "name": "...", "description": "...", "inputSchema": { ... } },
 *     ...
 *   ]
 * }
 * ```
 * Tools appear in registration order.
 */
fun ToolRegistry.manifestJson(): String {
    val manifest = buildJsonObject {
        put(
            "tools",
            buildJsonArray {
                for (tool in all()) {
                    add(
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("inputSchema", tool.parametersSchema)
                        },
                    )
                }
            },
        )
    }
    return CodexJson.encodeToString(JsonObject.serializer(), manifest)
}
