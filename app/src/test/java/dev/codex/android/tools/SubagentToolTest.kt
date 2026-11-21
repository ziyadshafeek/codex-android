package dev.codex.android.tools

import dev.codex.android.agent.AgentEvent
import dev.codex.android.agent.AgentLoop
import dev.codex.android.agent.AgentRuntime
import dev.codex.android.agent.Plan
import dev.codex.android.agent.PlanRepository
import dev.codex.android.api.ChatChoice
import dev.codex.android.api.ChatMessage
import dev.codex.android.api.ChatResult
import dev.codex.android.api.FunctionCall
import dev.codex.android.api.OpenAiClient
import dev.codex.android.api.ToolCall
import dev.codex.android.api.ToolDefinition
import dev.codex.android.context.ThreadHistoryStore
import dev.codex.android.data.ThreadEntity
import dev.codex.android.data.TimelineMessage
import dev.codex.android.data.WorkspaceEntity
import dev.codex.android.security.DefaultApprovalGate
import dev.codex.android.security.ExecutionMode
import dev.codex.android.security.GlobPermissionEngine
import dev.codex.android.subagents.SubagentLoopFactory
import dev.codex.android.subagents.SubagentOrchestrator
import dev.codex.android.subagents.TurnRunner
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [SubagentTool] against the REAL [SubagentOrchestrator]
 * with the same fakes [dev.codex.android.subagents.SubagentOrchestratorTest]
 * uses — no Android `Context`, no network, no model key.
 *
 * What this proves on the JVM: spawn → await → result-returned-to-parent,
 * failure reported promptly instead of hanging, and timeout kills the
 * runaway sub-agent. What it CANNOT prove here (needs emulator/phone):
 * the production [DefaultSubagentLoopFactory] path — its runner lambda
 * needs a real [AgentRuntime.TurnContext], which needs an Android
 * `Context`, and the factory itself reads the BYOK key from `KeyStore`.
 * The loop-level test below ([subagentLoop_runsToolsAndReturnsResult])
 * covers the other half with a scripted model client: the exact
 * [AgentLoop] the factory builds does call its tools and does return
 * tool-derived results.
 */
class SubagentToolTest {

    /** In-memory [ThreadHistoryStore] (mirrors the DB: replace re-assigns fresh ids). */
    private class FakeHistoryStore : ThreadHistoryStore {
        val threads = LinkedHashMap<String, ThreadEntity>()
        val messages = LinkedHashMap<String, MutableList<TimelineMessage>>()
        private var idCounter = 0L
        var failCreateThread: Exception? = null

        override suspend fun ensureWorkspace(workspace: WorkspaceEntity) = Unit

        override suspend fun listThreads(workspaceId: String): List<ThreadEntity> =
            threads.values.filter { it.workspaceId == workspaceId }

        override suspend fun createThread(workspaceId: String, title: String, mode: String): ThreadEntity {
            failCreateThread?.let { throw it }
            val entity = ThreadEntity(
                id = "thr_${threads.size + 1}",
                workspaceId = workspaceId,
                title = title,
                mode = mode,
                status = "idle",
                unread = false,
                updatedAt = System.currentTimeMillis(),
            )
            threads[entity.id] = entity
            messages[entity.id] = mutableListOf()
            return entity
        }

        override suspend fun getMessages(threadId: String): List<TimelineMessage> =
            messages[threadId].orEmpty().toList()

        override suspend fun appendMessage(message: TimelineMessage) {
            val stored = message.copy(id = ++idCounter)
            messages.getOrPut(message.threadId) { mutableListOf() } += stored
        }

        override suspend fun replaceMessages(threadId: String, messages: List<TimelineMessage>) {
            this.messages[threadId] = messages.map { it.copy(id = ++idCounter) }.toMutableList()
        }
    }

    /**
     * [TurnRunner] fake that simulates a sub-agent finishing: when the
     * turn is awaited it appends the scripted assistant answer to the
     * thread history (exactly what the real turn leaves behind) and then
     * returns, so the orchestrator marks the sub-agent `done` with that
     * answer as its output.
     */
    private class AnsweringTurnRunner(
        private val history: FakeHistoryStore,
        private val answer: String,
    ) : TurnRunner {
        data class Start(val threadId: String, val goal: String, val mode: ExecutionMode, val maxSteps: Int)
        val starts = mutableListOf<Start>()
        val stops = mutableListOf<String>()
        var returnStarted = true

        override fun startTurn(
            threadId: String,
            goal: String,
            mode: ExecutionMode,
            maxSteps: Int,
            runner: suspend AgentRuntime.TurnContext.() -> String,
        ): Boolean {
            starts += Start(threadId, goal, mode, maxSteps)
            return returnStarted
        }

        override fun stopTurn(threadId: String) {
            stops += threadId
        }

        override suspend fun awaitTurnFinished(threadId: String) {
            if (!returnStarted) return
            history.appendMessage(
                TimelineMessage(threadId = threadId, role = "assistant", kind = "text", content = answer),
            )
        }
    }

    /** [TurnRunner] fake whose turn never finishes (cancellable via kill). */
    private class HangingTurnRunner : TurnRunner {
        val starts = mutableListOf<String>()
        val stops = mutableListOf<String>()

        override fun startTurn(
            threadId: String,
            goal: String,
            mode: ExecutionMode,
            maxSteps: Int,
            runner: suspend AgentRuntime.TurnContext.() -> String,
        ): Boolean {
            starts += threadId
            return true
        }

        override fun stopTurn(threadId: String) {
            stops += threadId
        }

        override suspend fun awaitTurnFinished(threadId: String) {
            delay(Long.MAX_VALUE) // cancellable: kill cancels the spawn job
        }
    }

    /** [SubagentLoopFactory] fake: the runner lambda is never invoked by the fakes above. */
    private class FakeLoopFactory : SubagentLoopFactory {
        override suspend fun runGoal(
            turn: AgentRuntime.TurnContext,
            threadId: String,
            goal: String,
            mode: ExecutionMode,
            effectiveRules: GlobPermissionEngine,
        ): String = "fake answer"

        override fun drainSteeringNotes(): List<String> = emptyList()
    }

    private fun args(goal: String? = null, timeoutSeconds: Int? = null, agent: String? = null): JsonObject =
        buildJsonObject {
            if (goal != null) put("goal", goal)
            if (timeoutSeconds != null) put("timeout_seconds", timeoutSeconds)
            if (agent != null) put("agent", agent)
        }

    @Test
    fun spawnWorks_resultReturnsToParent() = runBlocking {
        val history = FakeHistoryStore()
        val runner = AnsweringTurnRunner(history, "The answer is 42.")
        val orchestrator = SubagentOrchestrator(history, runner, FakeLoopFactory())
        val tool = SubagentTool(orchestrator)

        val result = tool.execute(args(goal = "Find the answer to everything"))

        assertTrue(result.ok)
        assertTrue(result.output.contains("The answer is 42."))
        assertEquals(1, runner.starts.size)
        assertTrue(runner.starts[0].goal.contains("Find the answer to everything"))
        withTimeout(5_000) {
            while (orchestrator.listSubagents().firstOrNull()?.status == "running") delay(10)
        }
        assertEquals("done", orchestrator.listSubagents().first().status)
    }

    @Test
    fun subagentResultReflectsItsOwnToolUse() = runBlocking {
        // The sub-agent's answer embeds a REAL get_time result: the tool
        // must return whatever the sub-agent produced, including
        // tool-derived content, verbatim to the parent.
        val time = GetTimeTool.execute(buildJsonObject {}).output
        assertTrue(time.isNotBlank())
        val history = FakeHistoryStore()
        val runner = AnsweringTurnRunner(history, "The current time is $time.")
        val orchestrator = SubagentOrchestrator(history, runner, FakeLoopFactory())
        val tool = SubagentTool(orchestrator)

        val result = tool.execute(args(goal = "Tell me the current time"))

        assertTrue(result.ok)
        assertTrue(result.output.contains(time))
    }

    @Test
    fun subagentLoop_runsToolsAndReturnsResult() = runBlocking {
        // The other half of (b), at the loop level the factory builds: a
        // scripted model that calls get_time, then answers from the tool
        // result. Same ScriptedClient pattern as SkillScopingTest.
        val clock = RecordingTool("get_time", "2026-11-21 01:27:00 IST (Asia/Kolkata)")
        val tools = ToolRegistry().apply { register(clock) }
        val client = RecordingClient(
            listOf(
                toolCallsResult("get_time" to "{}"),
                textResult("The current time is 2026-11-21 01:27:00 IST (Asia/Kolkata)."),
            ),
        )
        val loop = AgentLoop(
            client = client,
            tools = tools,
            gate = DefaultApprovalGate(),
            planStore = FakePlanRepository(),
            model = "fake-model",
        )
        val channel = Channel<AgentEvent>(Channel.UNLIMITED)
        val answer = loop.run("Tell me the current time", channel)
        channel.close()

        assertTrue("the loop must actually execute the tool", clock.executed)
        assertTrue(answer.contains("2026-11-21 01:27:00 IST (Asia/Kolkata)"))
    }

    @Test
    fun failingSubagent_reportsErrorPromptly_notAfterFullTimeout() = runBlocking {
        val history = FakeHistoryStore()
        val runner = AnsweringTurnRunner(history, "unused").also { it.returnStarted = false }
        val orchestrator = SubagentOrchestrator(history, runner, FakeLoopFactory())
        val tool = SubagentTool(orchestrator)

        val startedAt = System.currentTimeMillis()
        val result = tool.execute(args(goal = "Task that cannot start", timeoutSeconds = 300))
        val elapsedMs = System.currentTimeMillis() - startedAt

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("Could not start"))
        assertTrue("must return promptly, not after the 300s timeout (took ${elapsedMs}ms)", elapsedMs < 10_000)
    }

    @Test
    fun spawnThrowing_reportsErrorPromptly() = runBlocking {
        val history = FakeHistoryStore().also {
            it.failCreateThread = IllegalStateException("database unavailable")
        }
        val orchestrator = SubagentOrchestrator(history, AnsweringTurnRunner(history, "unused"), FakeLoopFactory())
        val tool = SubagentTool(orchestrator)

        val startedAt = System.currentTimeMillis()
        val result = tool.execute(args(goal = "Task whose spawn throws", timeoutSeconds = 300))
        val elapsedMs = System.currentTimeMillis() - startedAt

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("database unavailable"))
        assertTrue("must return promptly (took ${elapsedMs}ms)", elapsedMs < 10_000)
    }

    @Test
    fun timeout_killsSubagentAndReportsError() = runBlocking {
        val history = FakeHistoryStore()
        val runner = HangingTurnRunner()
        val orchestrator = SubagentOrchestrator(history, runner, FakeLoopFactory())
        val tool = SubagentTool(orchestrator)

        val startedAt = System.currentTimeMillis()
        val result = tool.execute(args(goal = "Long running task", timeoutSeconds = 5))
        val elapsedMs = System.currentTimeMillis() - startedAt

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("did not finish within 5 seconds"))
        assertTrue("must return near the timeout, not hang (took ${elapsedMs}ms)", elapsedMs < 10_000)
        assertTrue("timed-out sub-agent must be killed", orchestrator.listSubagents().isEmpty())
        assertEquals(listOf(runner.starts[0]), runner.stops)
    }

    @Test
    fun timeoutSeconds_clampedToMinimum() = runBlocking {
        val history = FakeHistoryStore()
        val runner = HangingTurnRunner()
        val orchestrator = SubagentOrchestrator(history, runner, FakeLoopFactory())
        val tool = SubagentTool(orchestrator)

        val startedAt = System.currentTimeMillis()
        val result = tool.execute(args(goal = "Long running task", timeoutSeconds = 1))
        val elapsedMs = System.currentTimeMillis() - startedAt

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("did not finish within 5 seconds"))
        assertTrue("1s must clamp up to the 5s minimum (took ${elapsedMs}ms)", elapsedMs >= 4_500)
    }

    @Test
    fun missingGoal_isRejectedWithoutSpawning() = runBlocking {
        val history = FakeHistoryStore()
        val runner = AnsweringTurnRunner(history, "unused")
        val orchestrator = SubagentOrchestrator(history, runner, FakeLoopFactory())
        val tool = SubagentTool(orchestrator)

        val result = tool.execute(args(goal = "   "))

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("goal"))
        assertTrue("blank goal must not spawn anything", runner.starts.isEmpty())
    }

    @Test
    fun unknownAgentName_fallsBackToGeneral() = runBlocking {
        val history = FakeHistoryStore()
        val runner = AnsweringTurnRunner(history, "Done by general.")
        val orchestrator = SubagentOrchestrator(history, runner, FakeLoopFactory())
        val tool = SubagentTool(orchestrator)

        val result = tool.execute(args(goal = "Do it", agent = "no-such-agent"))

        assertTrue(result.ok)
        assertTrue(result.output.contains("Done by general."))
        assertEquals("general", orchestrator.listSubagents().first().agentName)
    }

    @Test
    fun emptyFinishedResult_isErrorNotEmptySuccess() = runBlocking {
        val history = FakeHistoryStore()
        val runner = AnsweringTurnRunner(history, "   ")
        val orchestrator = SubagentOrchestrator(history, runner, FakeLoopFactory())
        val tool = SubagentTool(orchestrator)

        val result = tool.execute(args(goal = "Say nothing"))

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("without producing a result"))
    }

    // ---- Scripted-model helpers (SkillScopingTest pattern) ----

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

    private class RecordingClient(private val script: List<ChatResult>) : OpenAiClient {
        private var index = 0
        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>,
            model: String,
            onPartialText: ((String) -> Unit)?,
        ): ChatResult = script.getOrNull(index++) ?: ChatResult(
            choices = listOf(ChatChoice(text = "done", toolCalls = emptyList(), finishReason = "stop")),
        )
    }

    private class RecordingTool(
        override val name: String,
        private val output: String,
    ) : Tool {
        var executed = false
        override val description: String = "test tool $name"
        override val sensitive: Boolean = false
        override val parametersSchema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            executed = true
            return ToolResult(ok = true, output = output)
        }
    }

    private fun toolCallsResult(vararg calls: Pair<String, String>): ChatResult = ChatResult(
        choices = listOf(
            ChatChoice(
                text = null,
                toolCalls = calls.mapIndexed { i, (name, arguments) ->
                    ToolCall(id = "call-$i", function = FunctionCall(name = name, arguments = arguments))
                },
                finishReason = "tool_calls",
            ),
        ),
    )

    private fun textResult(text: String): ChatResult = ChatResult(
        choices = listOf(ChatChoice(text = text, toolCalls = emptyList(), finishReason = "stop")),
    )
}
