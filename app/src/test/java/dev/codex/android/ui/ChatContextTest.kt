package dev.codex.android.ui

import dev.codex.android.data.TimelineMessage
import dev.codex.android.context.buildConversationContext
import dev.codex.android.context.autoSummaryEnd
import dev.codex.android.context.transcriptPieces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContextTest {
    @Test fun compactedHistoryUsesSummaryAndOnlyTheNewerMessages() {
        fun row(id: Long, role: String, text: String, kind: String = "text") =
            TimelineMessage(id = id, threadId = "t1", role = role, kind = kind, content = text)
        val summary = row(3, "system", "Feature A is working.", "summary")
        val history = listOf(row(1, "user", "Old details"), summary, row(4, "assistant", "New details"), row(5, "user", "Continue"))
        val context = buildConversationContext(history, summary, "Continue", 2000)
        assertEquals(2, context.size)
        assertTrue(context.first().content.orEmpty().endsWith("Feature A is working."))
        assertEquals("New details", context.last().content)
    }
    @Test fun automaticCompactionKeepsLatestMessagesVerbatim() {
        val rows = (1L..9L).map { TimelineMessage(it, "t1", "user", "text", "message " + "x".repeat(480)) }
        assertEquals(6L, autoSummaryEnd(rows, null, budget = 1365, mayHaveOlder = false))
        val summary = TimelineMessage(10, "t1", "system", "summary", "summary text", """{"through":3}""")
        val context = buildConversationContext(rows, summary, "follow-up", 1365)
        assertEquals(listOf("Summary of earlier conversation (context only):\nsummary text") + rows.drop(3).map { it.content }, context.map { it.content })
    }
    @Test fun largeTranscriptPiecesDoNotDropToolOutput() {
        val input = "[Tool result] " + "x😀".repeat(70)
        val pieces = transcriptPieces(input, 15)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.none { it.last().isHighSurrogate() })
        assertEquals(input, pieces.joinToString(""))
    }
}
