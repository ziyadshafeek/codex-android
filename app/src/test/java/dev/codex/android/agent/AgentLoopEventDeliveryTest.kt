package dev.codex.android.agent

import dev.codex.android.api.ChatChoice
import dev.codex.android.api.ChatMessage
import dev.codex.android.api.ChatResult
import dev.codex.android.api.OpenAiClient
import dev.codex.android.api.ToolDefinition
import dev.codex.android.security.DefaultApprovalGate
import dev.codex.android.tools.ToolRegistry
import dev.codex.android.ui.runWithEventChannel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM unit tests for BUG 3 (deep fix): all events emitted before
 * collection starts are delivered in exact FIFO order without dropping
 * or replaying, and consumer failures propagate cleanly.
 * Plain [runBlocking] only — no kotlinx-coroutines-test.
 */
class AgentLoopEventDeliveryTest {

    private class FakePlanRepository : PlanRepository {
        var latest: Plan? = null
        override suspend fun loadLatest(): Plan? = latest
        override suspend fun save(plan: Plan) {
            latest = plan
        }

        override suspend fun clear() {
            latest = null
        }
    }

    private class CannedClient(private val reply: String) : OpenAiClient {
        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>,
            model: String,
            onPartialText: ((String) -> Unit)?,
        ): ChatResult = ChatResult(
            choices = listOf(
                ChatChoice(text = reply, toolCalls = emptyList(), finishReason = "stop"),
            ),
        )
    }

    @Test
    fun interactiveLoopContinuesPastOldLimitAndEndsWithToolsDisabled() = runBlocking {
        val registry = ToolRegistry().apply { register(dev.codex.android.tools.EchoTool) }
        val channel = Channel<AgentEvent>(Channel.UNLIMITED)
        var requests = 0
        val limit = AgentLoop.INTERACTIVE_STEPS
        val client = object : OpenAiClient {
            override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDefinition>, model: String,
                onPartialText: ((String) -> Unit)?): ChatResult {
                requests++
                if (requests <= limit) {
                    assertTrue(tools.isNotEmpty())
                    return ChatResult(listOf(ChatChoice(null, listOf(dev.codex.android.api.ToolCall(
                        id = "call_$requests", function = dev.codex.android.api.FunctionCall("echo", """{"text":"step"}"""))), "tool_calls")))
                }
                assertTrue(tools.isEmpty())
                assertTrue(messages.last().content.orEmpty().contains("Tools are disabled"))
                onPartialText?.invoke("Progress ")
                onPartialText?.invoke("report.")
                return ChatResult(listOf(ChatChoice("Progress report.", listOf(dev.codex.android.api.ToolCall(
                    id = "not_allowed", function = dev.codex.android.api.FunctionCall("echo", """{"text":"must not run"}"""))), "tool_calls")))
            }
        }
        val loop = AgentLoop(client, registry, DefaultApprovalGate(), FakePlanRepository(), "test", modelSteps = limit)
        val final = loop.run("Long task", channel, streamText = true)
        channel.close()
        val events = channel.receiveAsFlow().toList()
        assertEquals(limit + 1, requests)
        assertEquals(limit, events.count { it is AgentEvent.ToolStarted })
        assertEquals(listOf("Progress ", "report."), events.filterIsInstance<AgentEvent.AssistantDelta>().map { it.text })
        assertTrue(final.contains("Send a follow-up to continue."))
        assertEquals(AgentEvent.Finished(final), events.last())
    }

    @Test
    fun preCollectionEvents_fromAgentLoop_allDeliveredInFifoOrderWithoutReplay() = runBlocking {
        val channel = Channel<AgentEvent>(Channel.UNLIMITED)
        val loop = AgentLoop(
            client = CannedClient("done!"),
            tools = ToolRegistry(),
            gate = DefaultApprovalGate(),
            planStore = FakePlanRepository(),
            model = "fake-model",
        )

        // Run the entire loop BEFORE any collector subscribes.
        val answer = loop.run("say done", channel)
        channel.close()
        assertEquals("done!", answer)

        val delivered = channel.receiveAsFlow().toList()
        assertEquals(4, delivered.size)
        assertTrue(delivered[0] is AgentEvent.PlanCreated)
        assertEquals(AgentEvent.AssistantText("done!"), delivered[1])
        assertTrue(delivered[2] is AgentEvent.PlanUpdated)
        assertEquals(AgentEvent.Finished("done!"), delivered[3])

        // Single-delivery semantics: a subsequent collector must not replay old events.
        val secondCollector = channel.receiveAsFlow().toList()
        assertTrue(secondCollector.isEmpty())
    }

    @Test
    fun preCollectionEvents_exceeding64Buffer_areAllDeliveredInOrder() = runBlocking {
        val channel = Channel<AgentEvent>(Channel.UNLIMITED)
        val count = 100
        for (i in 0 until count) {
            channel.send(AgentEvent.AssistantText("chunk-$i"))
        }
        channel.close()

        val delivered = channel.receiveAsFlow().toList()
        assertEquals(count, delivered.size)
        assertEquals(
            (0 until count).map { AgentEvent.AssistantText("chunk-$it") },
            delivered,
        )
    }

    @Test
    fun runWithEventChannel_drainsEarlyAndTrailingEventsDeterministically() = runBlocking {
        val seen = mutableListOf<String>()
        val result = withTimeout(5_000L) {
            runWithEventChannel<AgentEvent, String>(
                consume = { event ->
                    when (event) {
                        is AgentEvent.AssistantText -> seen += "text:${event.text}"
                        is AgentEvent.Finished -> seen += "finished:${event.answer}"
                        else -> seen += event.javaClass.simpleName
                    }
                },
                produce = { events ->
                    events.send(AgentEvent.AssistantText("one"))
                    events.send(AgentEvent.AssistantText("two"))
                    events.send(AgentEvent.Finished("final"))
                    "final"
                },
            )
        }

        assertEquals("final", result)
        assertEquals(listOf("text:one", "text:two", "finished:final"), seen)
    }

    @Test
    fun runWithEventChannel_consumerFailureCancelsProducerAndPropagates() = runBlocking {
        val producerCancelled = AtomicBoolean(false)
        try {
            withTimeout(5_000L) {
                runWithEventChannel<AgentEvent, String>(
                    consume = {
                        throw IllegalStateException("Stopped: reached max step budget (1)")
                    },
                    produce = { events ->
                        events.send(AgentEvent.AssistantText("trigger"))
                        try {
                            awaitCancellation()
                        } finally {
                            producerCancelled.set(true)
                        }
                    },
                )
            }
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("max step budget"))
        }
        assertTrue("producer must be cancelled when consumer throws", producerCancelled.get())
    }
}
