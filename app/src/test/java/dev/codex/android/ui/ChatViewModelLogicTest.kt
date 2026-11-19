package dev.codex.android.ui

import dev.codex.android.agent.AgentEvent
import dev.codex.android.agent.Plan
import dev.codex.android.agent.channelEventCollector
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the BUG 1 / BUG 3 fixes in ChatViewModel + AgentLoop.
 *
 * These target the pure, Android-free seams: [mergeRestored] (the restore
 * merge) and [channelEventCollector] (the Channel bridge the loop emits
 * into). The ViewModel itself needs a real Application + KeyStore +
 * AgentRuntime, so the double-tap race (BUG 2) is covered by the
 * instrumented [dev.codex.android.ChatViewModelTest] instead.
 */
class ChatViewModelLogicTest {

    // --- BUG 1: mergeRestored ---

    @Test
    fun mergeRestored_restoredFirstThenInSession() {
        val restored = listOf(
            ChatUiMessage("user", "old 1"),
            ChatUiMessage("assistant", "old 2"),
        )
        val current = listOf(ChatUiMessage("user", "new"))
        assertEquals(restored + current, mergeRestored(restored, current))
    }

    @Test
    fun mergeRestored_dropsRestoredDuplicateOfInSessionMessage() {
        val inSession = ChatUiMessage("user", "same goal")
        val restored = listOf(
            ChatUiMessage("user", "earlier"),
            // Persisted copy of the in-session message: restore read the DB
            // after it was saved but before the chat knew about it.
            ChatUiMessage("user", "same goal"),
        )
        val merged = mergeRestored(restored, listOf(inSession))
        assertEquals(listOf("earlier", "same goal"), merged.map { it.text })
        // The in-session copy survives, in its own (later) position.
        assertEquals(inSession.id, merged.last().id)
    }

    @Test
    fun mergeRestored_keepsGenuineRepeatsWithinEachSide() {
        val restored = listOf(
            ChatUiMessage("user", "ok"),
            ChatUiMessage("user", "ok"),
        )
        assertEquals(2, mergeRestored(restored, emptyList()).size)
    }

    @Test
    fun mergeRestored_emptyRestoredKeepsCurrent() {
        val current = listOf(ChatUiMessage("user", "only"))
        assertEquals(current, mergeRestored(emptyList(), current))
    }

    // --- BUG 3: channelEventCollector ---

    @Test
    fun channelCollector_deliversPreCollectionEventsInFifoOrderOnce() = runBlocking {
        val channel = Channel<AgentEvent>(Channel.UNLIMITED)
        val sink = channelEventCollector(channel)
        val plan = Plan(id = "p1", goal = "g")
        val e1 = AgentEvent.PlanCreated(plan)
        val e2 = AgentEvent.AssistantText("hello")
        val e3 = AgentEvent.Finished("done")
        // Emit all three BEFORE any collector subscribes: with the old
        // MutableSharedFlow(replay = 0) every one of these was dropped.
        sink.emit(e1)
        sink.emit(e2)
        sink.emit(e3)
        channel.close()
        val received = mutableListOf<AgentEvent>()
        launch { channel.receiveAsFlow().collect { received += it } }.join()
        assertEquals(listOf(e1, e2, e3), received)
        // A later collector gets nothing: events are delivered once.
        val second = mutableListOf<AgentEvent>()
        launch { channel.receiveAsFlow().collect { second += it } }.join()
        assertTrue(second.isEmpty())
    }
}
