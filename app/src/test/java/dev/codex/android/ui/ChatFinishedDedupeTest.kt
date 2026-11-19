package dev.codex.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [shouldAppendFinished]: the Finished dedupe that
 * stops AgentLoop's final answer appearing twice (once as AssistantText,
 * once as Finished).
 */
class ChatFinishedDedupeTest {

    private fun assistant(text: String) = ChatUiMessage(role = "assistant", text = text)

    @Test
    fun duplicateAnswer_isNotAppended() {
        assertFalse(
            shouldAppendFinished(listOf(assistant("Hello")), "Hello"),
        )
    }

    @Test
    fun differentAnswer_isAppended() {
        assertTrue(
            shouldAppendFinished(listOf(assistant("Working on it")), "Done."),
        )
    }

    @Test
    fun noPriorAssistant_isAppended() {
        assertTrue(
            shouldAppendFinished(listOf(ChatUiMessage(role = "system", text = "Running get_time…")), "Done."),
        )
        assertTrue(shouldAppendFinished(emptyList(), "Done."))
    }

    @Test
    fun errorAssistant_doesNotCountAsDuplicate() {
        val messages = listOf(
            ChatUiMessage(role = "assistant", text = "Done.", isError = true),
        )
        assertTrue(shouldAppendFinished(messages, "Done."))
    }
}
