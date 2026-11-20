package dev.codex.android.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One server-initiated MCP notification captured by the client.
 *
 * HONEST LIMIT (see [McpClient] KDoc): this client speaks Streamable HTTP
 * with one-shot POST requests and holds no persistent SSE stream, so a
 * server cannot push a notification to it at an arbitrary moment. The
 * only notifications ever recorded here are the ones a server chose to
 * interleave as `data:` lines in the SSE body of a response to a request
 * the client already had in flight — [McpClient] records those while
 * parsing the response envelope. Anything else is detected by polling:
 * [McpServerChange] diffs a fresh `tools/list` / `resources/list`
 * against the last snapshot. Never present this as a live push feed.
 *
 * @property serverId the hub/manager server id the notification came from.
 * @property method e.g. `notifications/tools/list_changed`.
 * @property receivedAtMillis wall-clock time the client saw the line.
 * @property summary the notification's `params` as compact JSON, capped
 *   at [MAX_SUMMARY_CHARS] so a chatty server cannot flood the context.
 */
data class McpEvent(
    val serverId: String,
    val method: String,
    val receivedAtMillis: Long,
    val summary: String,
) {
    companion object {
        const val MAX_SUMMARY_CHARS: Int = 500

        private val lenientJson = Json { ignoreUnknownKeys = true }

        /**
         * Parses one raw JSON-RPC message line as a server notification.
         * Returns null for anything that is not a notification: a response
         * (has an `id`), a request, malformed JSON, or a message with no
         * `method`. Pure function — no network, no state — so it is
         * directly unit-testable.
         */
        fun parseNotification(
            serverId: String,
            rawJson: String,
            nowMillis: Long = System.currentTimeMillis(),
        ): McpEvent? {
            val element = runCatching { lenientJson.parseToJsonElement(rawJson) }.getOrNull()
                ?: return null
            val obj = element as? JsonObject ?: return null
            // A response carries "id"; a notification never does.
            if (obj.containsKey("id")) return null
            val method = obj["method"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return null
            val params = obj["params"]?.toString().orEmpty()
            val summary = if (params.isEmpty()) "{}" else params.take(MAX_SUMMARY_CHARS) +
                if (params.length > MAX_SUMMARY_CHARS) "…[truncated]" else ""
            return McpEvent(serverId, method, nowMillis, summary)
        }
    }
}

/** What changed on one server since the last snapshot (poll-based). */
data class McpServerChange(
    val serverId: String,
    val serverName: String,
    /** True on the first check for a server: snapshot recorded, nothing diffed. */
    val baseline: Boolean,
    val addedTools: List<String> = emptyList(),
    val removedTools: List<String> = emptyList(),
    val addedResources: List<String> = emptyList(),
    val removedResources: List<String> = emptyList(),
    /** Notifications captured opportunistically since the last check. */
    val notifications: List<McpEvent> = emptyList(),
    /** Non-null when this server's re-list failed; diff fields are then empty. */
    val error: String? = null,
)

/** Pure set diff: (added, removed) of [current] relative to [previous]. */
internal fun diffNameSets(previous: Set<String>, current: Set<String>): Pair<List<String>, List<String>> {
    val added = (current - previous).sorted()
    val removed = (previous - current).sorted()
    return added to removed
}
