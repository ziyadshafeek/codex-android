package dev.codex.android.tools

import android.content.Context
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.SessionSearchHit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Storage seam for past-session search. Production uses
 * [DatabaseSessionSearchStore] (the `messages` + `threads` tables in the
 * single `codex.db`); JVM tests use an in-memory fake — there is no
 * `CodexDatabase.createInMemoryForTest()` and no Robolectric, so the
 * database itself is never constructed in a unit test (same pattern as
 * [dev.codex.android.kb.KnowledgeStore]).
 */
interface SessionSearchStore {
    suspend fun searchMessages(query: String, threadId: String?, limit: Int): List<SessionSearchHit>
}

/** Production [SessionSearchStore] backed by `codex.db`. */
class DatabaseSessionSearchStore(
    private val database: CodexDatabase,
) : SessionSearchStore {
    override suspend fun searchMessages(
        query: String,
        threadId: String?,
        limit: Int,
    ): List<SessionSearchHit> = database.searchMessages(query, threadId, limit)
}

/**
 * Searches past sessions/threads (`name = "session-search"`).
 *
 * Case-insensitive substring search over message content (and thread
 * titles) in the app's own database, via a SQL `LIKE` query with a
 * `LIMIT` — history is never loaded whole into memory. Only message
 * content, role, timestamp, thread id and thread title are returned:
 * `metadata_json` and every other table are never read, so no field
 * that could hold a credential can leak through a search. Output is
 * capped at [MAX_OUTPUT_CHARS] with a truncation note.
 */
class SessionSearchTool(
    private val store: SessionSearchStore,
) : Tool {

    constructor(context: Context) : this(
        DatabaseSessionSearchStore(CodexDatabase.getInstance(context)),
    )

    override val name: String = "session-search"
    override val description: String =
        "Searches past sessions/threads for a text query (case-insensitive substring over message " +
            "content and thread titles). Returns matching messages with their thread, role, timestamp, " +
            "and a snippet around the match. Optionally restrict to one thread with thread_id."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "Text to search for in past messages and thread titles")
            }
            putJsonObject("thread_id") {
                put("type", "string")
                put("description", "Optional thread id to restrict the search to one thread")
            }
            putJsonObject("max_results") {
                put("type", "integer")
                put("description", "Maximum hits to return (default 20, max 100)")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("query"))
        }
    }
    // Reads the user's chat history across threads: the approval gate asks first.
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: query")
        val threadId = args["thread_id"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
        val maxResults = args["max_results"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?.coerceIn(1, MAX_RESULTS) ?: DEFAULT_MAX_RESULTS

        val hits = try {
            store.searchMessages(query, threadId, maxResults)
        } catch (e: Exception) {
            return ToolResult(ok = false, output = "", error = "Session search failed: ${e.message ?: e.javaClass.simpleName}")
        }
        if (hits.isEmpty()) {
            return ToolResult(
                ok = true,
                output = "No matches for \"$query\"" +
                    (if (threadId != null) " in thread $threadId" else "") + ".",
            )
        }
        val output = buildString {
            append("Found ${hits.size} match${if (hits.size == 1) "" else "es"} for \"$query\":\n")
            for ((index, hit) in hits.withIndex()) {
                append("\n${index + 1}. [${hit.threadId} — \"${hit.threadTitle}\"] ")
                append("${formatTimestamp(hit.createdAt)} (${hit.role})\n")
                append("   ${buildSnippet(hit.content, query)}\n")
            }
        }
        val capped = if (output.length > MAX_OUTPUT_CHARS) {
            output.take(MAX_OUTPUT_CHARS) +
                "\n[output truncated at $MAX_OUTPUT_CHARS chars; narrow the query or use thread_id / max_results]"
        } else {
            output
        }
        return ToolResult(ok = true, output = capped)
    }

    companion object {
        const val DEFAULT_MAX_RESULTS: Int = 20
        const val MAX_RESULTS: Int = 100
        const val MAX_OUTPUT_CHARS: Int = 20_000
        private const val SNIPPET_BEFORE_CHARS: Int = 80
        private const val SNIPPET_AFTER_CHARS: Int = 120

        private val timestampFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

        internal fun formatTimestamp(epochMillis: Long): String =
            timestampFormatter.format(Instant.ofEpochMilli(epochMillis))

        /**
         * Snippet of [content] around the first case-insensitive occurrence
         * of [query] (80 chars before, 120 after, with ellipsis markers
         * when cut). When the match is only in the thread title, the
         * snippet is the start of the message.
         */
        internal fun buildSnippet(content: String, query: String): String {
            val matchAt = content.indexOf(query, ignoreCase = true)
            if (matchAt < 0) {
                val head = content.take(SNIPPET_BEFORE_CHARS + SNIPPET_AFTER_CHARS)
                return head + if (content.length > head.length) "…" else ""
            }
            val start = maxOf(0, matchAt - SNIPPET_BEFORE_CHARS)
            val end = minOf(content.length, matchAt + query.length + SNIPPET_AFTER_CHARS)
            val prefix = if (start > 0) "…" else ""
            val suffix = if (end < content.length) "…" else ""
            return prefix + content.substring(start, end).replace("\n", " ") + suffix
        }
    }
}
