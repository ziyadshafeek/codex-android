package dev.codex.android.agent

import dev.codex.android.api.ChatChoice
import dev.codex.android.api.ChatMessage
import dev.codex.android.api.ChatResult
import dev.codex.android.api.FunctionCall
import dev.codex.android.api.OpenAiClient
import dev.codex.android.api.ToolCall
import dev.codex.android.api.ToolDefinition
import dev.codex.android.security.DefaultApprovalGate
import dev.codex.android.tools.Tool
import dev.codex.android.tools.ToolRegistry
import dev.codex.android.tools.ToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the M1 AgentLoop batching integration: consecutive
 * read-only calls run in parallel, approval-gated tools act as barriers,
 * results stay in the order the model requested, and malformed/denied
 * calls are reported without aborting the run. Plain [runBlocking] only.
 */
class AgentLoopBatchingTest {

    private class BatchFakePlanRepository : PlanRepository {
        var latest: Plan? = null
        override suspend fun loadLatest(): Plan? = latest
        override suspend fun save(plan: Plan) {
            latest = plan
        }

        override suspend fun clear() {
            latest = null
        }
    }

    private class ScriptedClient(private val script: List<ChatResult>) : OpenAiClient {
        private var index = 0
        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>,
            model: String,
            onPartialText: ((String) -> Unit)?,
        ): ChatResult {
            val result = script.getOrNull(index)
                ?: ChatResult(
                    choices = listOf(
                        ChatChoice(text = "done", toolCalls = emptyList(), finishReason = "stop"),
                    ),
                )
            index++
            return result
        }
    }

    private class RecordingTool(
        override val name: String,
        override val sensitive: Boolean,
        private val output: String,
        private val delayMs: Long = 0,
        private val startedAt: MutableMap<String, Long>? = null,
        private val finishedAt: MutableMap<String, Long>? = null,
    ) : Tool {
        override val description: String = "test tool $name"
        override val parametersSchema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            startedAt?.put(name, System.nanoTime())
            if (delayMs > 0) delay(delayMs)
            finishedAt?.put(name, System.nanoTime())
            return ToolResult(ok = true, output = output)
        }
    }

    private fun toolCallsResult(vararg names: String): ChatResult = ChatResult(
        choices = listOf(
            ChatChoice(
                text = null,
                toolCalls = names.mapIndexed { i, name ->
                    ToolCall(
                        id = "call-$i",
                        function = FunctionCall(name = name, arguments = "{}"),
                    )
                },
                finishReason = "tool_calls",
            ),
        ),
    )

    private fun textResult(text: String): ChatResult = ChatResult(
        choices = listOf(
            ChatChoice(text = text, toolCalls = emptyList(), finishReason = "stop"),
        ),
    )

    private fun loopFor(tools: ToolRegistry, gate: DefaultApprovalGate, client: OpenAiClient) =
        AgentLoop(
            client = client,
            tools = tools,
            gate = gate,
            planStore = BatchFakePlanRepository(),
            model = "fake-model",
        )

    @Test
    fun readsRunInParallelAndResultsStayInRequestedOrder() = runBlocking {
        val startedAt = mutableMapOf<String, Long>()
        val finishedAt = mutableMapOf<String, Long>()
        val tools = ToolRegistry().apply {
            register(RecordingTool("slow_read", false, "slow-done", delayMs = 200, startedAt, finishedAt))
            register(RecordingTool("fast_read", false, "fast-done", 0, startedAt, finishedAt))
        }
        val channel = Channel<AgentEvent>(Channel.UNLIMITED)
        val loop = loopFor(
            tools,
            DefaultApprovalGate(),
            ScriptedClient(listOf(toolCallsResult("slow_read", "fast_read"), textResult("all done"))),
        )

        val answer = loop.run("test", channel)
        channel.close()
        assertEquals("all done", answer)

        val finished = channel.receiveAsFlow().toList()
            .filterIsInstance<AgentEvent.ToolFinished>()
        assertEquals(listOf("slow_read", "fast_read"), finished.map { it.toolName })
        assertEquals(listOf("slow-done", "fast-done"), finished.map { it.output })
        // Overlap: the fast read started before the slow read finished.
        assertTrue(
            "fast read must start while slow read is still running",
            startedAt.getValue("fast_read") < finishedAt.getValue("slow_read"),
        )
    }

    @Test
    fun sensitiveToolIsABarrierBetweenReads() = runBlocking {
        val startedAt = mutableMapOf<String, Long>()
        val finishedAt = mutableMapOf<String, Long>()
        val tools = ToolRegistry().apply {
            register(RecordingTool("slow_read", false, "slow-done", delayMs = 150, startedAt, finishedAt))
            register(RecordingTool("write_note", true, "written", 0, startedAt, finishedAt))
            register(RecordingTool("fast_read", false, "fast-done", 0, startedAt, finishedAt))
        }
        val gate = DefaultApprovalGate()
        val loop = loopFor(
            tools,
            gate,
            ScriptedClient(listOf(toolCallsResult("slow_read", "write_note", "fast_read"), textResult("all done"))),
        )

        val channel = Channel<AgentEvent>(Channel.UNLIMITED)
        val runJob = async { loop.run("test", channel) }
        // Answer the approval prompt when it appears (barrier's gate).
        withTimeout(5_000L) {
            while (gate.pending.value == null) delay(10)
        }
        val pending = gate.pending.value!!
        assertEquals("write_note", pending.toolName)
        gate.answer(pending.id, true)
        val answer = runJob.await()
        channel.close()
        assertEquals("all done", answer)

        val finished = channel.receiveAsFlow().toList()
            .filterIsInstance<AgentEvent.ToolFinished>()
        assertEquals(listOf("slow_read", "write_note", "fast_read"), finished.map { it.toolName })
        // Barrier order is enforced by timestamps, not wall-clock totals:
        // the write started only after the slow read finished, and the
        // fast read started only after the write finished.
        assertTrue(startedAt.getValue("write_note") >= finishedAt.getValue("slow_read"))
        assertTrue(startedAt.getValue("fast_read") >= finishedAt.getValue("write_note"))
    }

    @Test
    fun malformedCallReportsErrorAndLoopContinues() = runBlocking {
        val tools = ToolRegistry().apply {
            register(RecordingTool("fast_read", false, "fast-done"))
        }
        val channel = Channel<AgentEvent>(Channel.UNLIMITED)
        val loop = loopFor(
            tools,
            DefaultApprovalGate(),
            ScriptedClient(listOf(toolCallsResult("nope_unknown", "fast_read"), textResult("done"))),
        )

        val answer = loop.run("test", channel)
        channel.close()
        assertEquals("done", answer)

        val events = channel.receiveAsFlow().toList()
        assertTrue("unknown tool must produce an Error event", events.any { it is AgentEvent.Error })
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>()
        assertEquals(listOf("fast_read"), finished.map { it.toolName })
        assertEquals("fast-done", finished.single().output)
    }

    @Test
    fun deniedSensitiveToolReportsDenialAndLoopContinues() = runBlocking {
        val tools = ToolRegistry().apply {
            register(RecordingTool("write_note", true, "written"))
        }
        val gate = DefaultApprovalGate()
        val loop = loopFor(
            tools,
            gate,
            ScriptedClient(listOf(toolCallsResult("write_note"), textResult("done"))),
        )

        val channel = Channel<AgentEvent>(Channel.UNLIMITED)
        val runJob = async { loop.run("test", channel) }
        withTimeout(5_000L) {
            while (gate.pending.value == null) delay(10)
        }
        gate.answer(gate.pending.value!!.id, false)
        val answer = runJob.await()
        channel.close()
        assertEquals("done", answer)

        val finished = channel.receiveAsFlow().toList()
            .filterIsInstance<AgentEvent.ToolFinished>()
        assertEquals(listOf("write_note"), finished.map { it.toolName })
        assertEquals("The user denied this action.", finished.single().output)
    }
}
