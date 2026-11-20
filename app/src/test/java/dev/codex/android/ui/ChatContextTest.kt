package dev.codex.android.ui

import dev.codex.android.data.TimelineMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContextTest {
    @Test fun compactedHistoryUsesSummaryAndOnlyTheNewerMessages() {
        fun row(id: Long, role: String, text: String, kind: String = "text") =
            TimelineMessage(id = id, threadId = "t1", role = role, kind = kind, content = text)
        val summary = row(3, "system", "Feature A is working.", "summary")
        val history = listOf(row(1, "user", "Old details"), summary, row(4, "assistant", "New details"), row(5, "user", "Continue"))
        val context = contextMessages(history, summary, "Continue")
        assertEquals(2, context.size)
        assertTrue(context.first().content.orEmpty().endsWith("Feature A is working."))
        assertEquals("New details", context.last().content)
    }
}
