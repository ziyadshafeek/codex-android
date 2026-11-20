package dev.codex.android.tools

import dev.codex.android.data.SessionSearchHit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [SessionSearchTool] against an in-memory
 * [SessionSearchStore] fake (case-insensitive substring semantics, the
 * same contract the SQL LIKE query in `CodexDatabase.searchMessages`
 * provides). The real database is never constructed in a unit test:
 * there is no `CodexDatabase.createInMemoryForTest()` and no Robolectric.
 */
class SessionSearchToolTest {

    /** In-memory fake: substring match over content + thread title, thread filter, limit. */
    private class FakeSessionSearchStore(
        private val hits: List<SessionSearchHit>,
    ) : SessionSearchStore {
        override suspend fun searchMessages(
            query: String,
            threadId: String?,
            limit: Int,
        ): List<SessionSearchHit> {
            if (query.isBlank()) return emptyList()
            return hits
                .filter { hit ->
                    (threadId == null || hit.threadId == threadId) &&
                        (hit.content.contains(query, ignoreCase = true) ||
                            hit.threadTitle.contains(query, ignoreCase = true))
                }
                .sortedByDescending { it.createdAt }
                .take(limit.coerceAtLeast(1))
        }
    }

    private fun hit(
        threadId: String,
        title: String,
        role: String,
        content: String,
        createdAt: Long = 1_000L,
    ) = SessionSearchHit(
        threadId = threadId,
        threadTitle = title,
        messageId = createdAt,
        role = role,
        content = content,
        createdAt = createdAt,
    )

    private fun toolWith(vararg hits: SessionSearchHit) =
        SessionSearchTool(FakeSessionSearchStore(hits.toList()))

    @Test
    fun search_findsCaseInsensitiveMatchWithSnippetRoleAndThread() = runBlocking {
        val tool = toolWith(
            hit("thr_1", "My project", "user", "Please build me a counter app with a reset button.", 2_000L),
            hit("thr_1", "My project", "assistant", "Done — created the files.", 3_000L),
        )
        val result = tool.execute(buildJsonObject { put("query", "COUNTER") })

        assertTrue(result.ok)
        assertTrue(result.output.contains("Found 1 match"))
        assertTrue(result.output.contains("thr_1"))
        assertTrue(result.output.contains("My project"))
        assertTrue(result.output.contains("(user)"))
        assertTrue(result.output.contains("counter app"))
        // No hit from the assistant message, which does not contain the term.
        assertFalse(result.output.contains("created the files"))
    }

    @Test
    fun search_titleOnlyMatch_returnsMessageWithHeadSnippet() = runBlocking {
        val tool = toolWith(
            hit("thr_2", "Deployment checklist", "assistant", "Everything is green.", 2_000L),
        )
        val result = tool.execute(buildJsonObject { put("query", "checklist") })

        assertTrue(result.ok)
        assertTrue(result.output.contains("Found 1 match"))
        assertTrue(result.output.contains("Everything is green."))
    }

    @Test
    fun search_noHit_reportsNoMatches() = runBlocking {
        val tool = toolWith(
            hit("thr_1", "Counter app", "user", "Build a counter app.", 2_000L),
        )
        val result = tool.execute(buildJsonObject { put("query", "zebra") })

        assertTrue(result.ok)
        assertEquals("No matches for \"zebra\".", result.output)
    }

    @Test
    fun search_threadFilter_restrictsToOneThread() = runBlocking {
        val tool = toolWith(
            hit("thr_1", "Counter app", "user", "Build a counter app.", 2_000L),
            hit("thr_2", "Weather", "user", "What is a counter app?", 3_000L),
        )
        val result = tool.execute(buildJsonObject {
            put("query", "counter")
            put("thread_id", "thr_2")
        })

        assertTrue(result.ok)
        assertTrue(result.output.contains("Found 1 match"))
        assertTrue(result.output.contains("thr_2"))
        assertFalse(result.output.contains("thr_1"))
    }

    @Test
    fun search_maxResults_capsHits() = runBlocking {
        val tool = toolWith(
            hit("thr_1", "T", "user", "counter one", 1_000L),
            hit("thr_1", "T", "user", "counter two", 2_000L),
            hit("thr_1", "T", "user", "counter three", 3_000L),
        )
        val result = tool.execute(buildJsonObject {
            put("query", "counter")
            put("max_results", 2)
        })

        assertTrue(result.ok)
        assertTrue(result.output.contains("Found 2 matches"))
        assertTrue(result.output.contains("counter three"))
        assertTrue(result.output.contains("counter two"))
        assertFalse(result.output.contains("counter one"))
    }

    @Test
    fun search_blankQuery_isAnError() = runBlocking {
        val tool = toolWith()
        val result = tool.execute(buildJsonObject { put("query", "   ") })

        assertFalse(result.ok)
        assertTrue(result.error?.contains("query") == true)
    }

    @Test
    fun search_missingQuery_isAnError() = runBlocking {
        val tool = toolWith()
        val result = tool.execute(buildJsonObject {})

        assertFalse(result.ok)
        assertTrue(result.error?.contains("query") == true)
    }

    @Test
    fun search_outputIsCappedWithTruncationNote() = runBlocking {
        // One huge message alone cannot trip the cap: snippets are ~200
        // chars around the match. The cap trips when MANY hits accumulate.
        val content = "a".repeat(100) + "needle" + "b".repeat(100)
        val hits = (1..100).map { i ->
            hit("thr_$i", "T", "user", content, createdAt = i.toLong())
        }
        val tool = toolWith(*hits.toTypedArray())
        val result = tool.execute(buildJsonObject {
            put("query", "needle")
            put("max_results", 100)
        })

        assertTrue(result.ok)
        assertTrue(result.output.length <= SessionSearchTool.MAX_OUTPUT_CHARS + 120)
        assertTrue(result.output.contains("output truncated at ${SessionSearchTool.MAX_OUTPUT_CHARS} chars"))
    }

    @Test
    fun snippet_centersOnMatchWithEllipsis() {
        val content = "a".repeat(200) + "NEEDLE" + "b".repeat(200)
        val snippet = SessionSearchTool.buildSnippet(content, "needle")
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.contains("NEEDLE"))
        assertTrue(snippet.length < content.length)
    }

    @Test
    fun snippet_noMatch_returnsHead() {
        val snippet = SessionSearchTool.buildSnippet("short message", "zzz")
        assertEquals("short message", snippet)
    }

    @Test
    fun tool_isSensitiveAndNamed() {
        val tool = toolWith()
        assertEquals("session-search", tool.name)
        assertTrue(tool.sensitive)
        assertTrue(tool.description.contains("past sessions"))
    }
}
