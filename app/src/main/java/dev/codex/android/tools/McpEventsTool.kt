package dev.codex.android.tools

import dev.codex.android.mcp.McpEvent
import dev.codex.android.mcp.McpHub
import dev.codex.android.mcp.McpServerChange
import dev.codex.android.security.SecretScrubber
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Surfaces MCP server events (`name = "mcp-events"`).
 *
 * HONEST LIMIT — this is poll-based change detection, NOT a live push
 * feed, and the description says so. The client speaks Streamable HTTP
 * with one-shot POST requests and holds no persistent SSE stream, so a
 * server cannot push a notification at an arbitrary moment (see
 * [McpEvent] KDoc). What this tool does on each call:
 * 1. Drains the notifications the client captured opportunistically from
 *    SSE response bodies during other MCP calls in flight, and
 * 2. re-lists each server's tools (and resources) and diffs them against
 *    the snapshot from the previous call, reporting what was added or
 *    removed. The first call for a server records the baseline.
 * Re-listing a server whose `notifications/tools/list_changed` fired is
 * exactly how that change is picked up — call this tool again after a
 * notification to refresh the tool list.
 */
class McpEventsTool(
    private val hub: McpHub,
) : Tool {

    override val name: String = "mcp-events"
    override val description: String =
        "Lists recent MCP server events. POLL-BASED, not live push: the client uses one-shot " +
            "Streamable HTTP requests with no persistent stream, so server notifications are only " +
            "captured opportunistically when they ride along with a response, and changes are " +
            "otherwise detected by re-listing each server's tools/resources and diffing against " +
            "the last snapshot. Call again after a tools/list_changed notification to refresh. " +
            "Optional server_id restricts to one server; omit for all servers."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("server_id") {
                put("type", "string")
                put("description", "Optional MCP server id to restrict the check to one server")
            }
        }
    }
    // Contacts untrusted MCP servers (re-list): the approval gate asks first.
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val serverId = args["server_id"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
        return try {
            val changes = hub.checkForChanges(serverId)
            if (changes.isEmpty()) {
                return ToolResult(ok = true, output = "No MCP servers registered.")
            }
            ToolResult(ok = true, output = formatChanges(changes))
        } catch (e: Exception) {
            ToolResult(
                ok = false,
                output = "",
                error = SecretScrubber.scrub(e.message ?: "MCP events check failed"),
            )
        }
    }

    companion object {
        private const val POLL_NOTE =
            "Note: poll-based, not live push — the MCP client uses one-shot Streamable HTTP " +
                "requests with no persistent stream, so notifications are only captured when " +
                "they ride along with a response; other changes are found by re-listing and diffing."

        private val timestampFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

        internal fun formatChanges(changes: List<McpServerChange>): String {
            val out = StringBuilder(POLL_NOTE)
            for (change in changes) {
                out.append("\n\nServer ${change.serverId} (\"${change.serverName}\"):")
                if (change.error != null) {
                    out.append("\n  Check failed: ${change.error}")
                    continue
                }
                if (change.baseline) {
                    out.append("\n  Baseline recorded (no previous snapshot to diff against).")
                }
                if (change.notifications.isEmpty()) {
                    out.append("\n  No notifications captured since the last check.")
                } else {
                    out.append("\n  Notifications since the last check:")
                    for (event in change.notifications) {
                        out.append("\n  - [${timestampFormatter.format(Instant.ofEpochMilli(event.receivedAtMillis))}] ${event.method} ${event.summary}")
                    }
                }
                val diffLines = mutableListOf<String>()
                if (change.addedTools.isNotEmpty()) diffLines += "tools added: ${change.addedTools.joinToString(", ")}"
                if (change.removedTools.isNotEmpty()) diffLines += "tools removed: ${change.removedTools.joinToString(", ")}"
                if (change.addedResources.isNotEmpty()) diffLines += "resources added: ${change.addedResources.joinToString(", ")}"
                if (change.removedResources.isNotEmpty()) diffLines += "resources removed: ${change.removedResources.joinToString(", ")}"
                if (diffLines.isEmpty()) {
                    out.append("\n  No tool/resource changes since the last check.")
                } else {
                    out.append("\n  Changes since the last check:")
                    for (line in diffLines) out.append("\n  - $line")
                }
            }
            return out.toString()
        }
    }
}
