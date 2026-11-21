package dev.codex.android.tools

import dev.codex.android.mcp.McpHub
import dev.codex.android.mcp.McpResourceContent
import dev.codex.android.security.SecretScrubber
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Lists and reads MCP resources (`name = "mcp-resource"`).
 *
 * With `uri` omitted, lists the resources a server exposes via
 * `resources/list` (uri + name + mimeType). With `uri`, reads one
 * resource via `resources/read` and returns its text, capped at
 * [MAX_READ_CHARS] with a truncation note. Binary resources (a `blob`
 * item from the server) are never dumped as text: the tool reports them
 * as binary instead. Every result is scrubbed for secrets before it
 * reaches the agent, like all other MCP results.
 */
class McpResourceTool(
    private val hub: McpHub,
) : Tool {

    override val name: String = "mcp-resource"
    override val description: String =
        "Lists the resources an MCP server exposes (resources/list) or reads one resource by URI " +
            "(resources/read). Omit uri to list; provide uri to read its text content. " +
            "Binary resources are reported, never dumped as text."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("server_id") {
                put("type", "string")
                put("description", "MCP server id (see mcp-events output or the MCP server list)")
            }
            putJsonObject("uri") {
                put("type", "string")
                put("description", "Optional resource URI to read; omit to list the server's resources")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("server_id"))
        }
    }
    // Contacts an untrusted MCP server: the approval gate asks first.
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val serverId = args["server_id"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: server_id")
        val uri = args["uri"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
        return try {
            if (uri == null) listResources(serverId) else readResource(serverId, uri)
        } catch (e: Exception) {
            ToolResult(
                ok = false,
                output = "",
                error = SecretScrubber.scrub(e.message ?: "MCP resource operation failed"),
            )
        }
    }

    private suspend fun listResources(serverId: String): ToolResult {
        val resources = hub.listResources(serverId)
        if (resources.isEmpty()) {
            return ToolResult(ok = true, output = "Server $serverId exposes no resources.")
        }
        val output = buildString {
            append("Resources on server $serverId (${resources.size}):\n")
            for (resource in resources) {
                append("- ${resource.uri} — ${resource.name}")
                resource.mimeType?.let { append(" ($it)") }
                append("\n")
            }
        }
        return ToolResult(ok = true, output = output.trimEnd())
    }

    private suspend fun readResource(serverId: String, uri: String): ToolResult {
        val content: McpResourceContent = hub.readResource(serverId, uri)
        if (content.isBinary) {
            return ToolResult(
                ok = false,
                output = "",
                error = "Resource $uri is binary" +
                    (content.mimeType?.let { " ($it)" } ?: "") +
                    " — text preview is not available.",
            )
        }
        val text = content.text
            ?: return ToolResult(
                ok = false,
                output = "",
                error = "Resource $uri has no readable text content.",
            )
        val capped = if (text.length > MAX_READ_CHARS) {
            text.take(MAX_READ_CHARS) +
                "\n[resource truncated at $MAX_READ_CHARS chars]"
        } else {
            text
        }
        val header = "Resource $uri" + (content.mimeType?.let { " ($it)" } ?: "") + ":\n"
        return ToolResult(ok = true, output = header + capped)
    }

    companion object {
        const val MAX_READ_CHARS: Int = 50_000
    }
}
