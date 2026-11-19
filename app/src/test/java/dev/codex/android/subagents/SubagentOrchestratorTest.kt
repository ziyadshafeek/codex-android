package dev.codex.android.subagents

import dev.codex.android.agent.AgentRuntime
import dev.codex.android.context.ThreadHistoryStore
import dev.codex.android.data.ThreadEntity
import dev.codex.android.data.TimelineMessage
import dev.codex.android.data.WorkspaceEntity
import dev.codex.android.security.ExecutionMode
import dev.codex.android.security.GlobDecision
import dev.codex.android.security.GlobPermissionEngine
import dev.codex.android.security.GlobRule
import dev.codex.android.tools.Tool
import dev.codex.android.tools.ToolResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for [SubagentOrchestrator]: permission intersection never widens, spawn/kill lifecycle. */
class SubagentOrchestratorTest {

    /** In-memory [ThreadHistoryStore] (mirrors the DB: replace re-assigns fresh ids). */
    private class FakeHistoryStore : ThreadHistoryStore {
        val threads = LinkedHashMap<String, ThreadEntity>()
        val messages = LinkedHashMap<String, MutableList<TimelineMessage>>()
        private var idCounter = 0L

        override suspend fun ensureWorkspace(workspace: WorkspaceEntity) = Unit

        override suspend fun listThreads(workspaceId: String): List<ThreadEntity> =
            threads.values.filter { it.workspaceId == workspaceId }

        override suspend fun createThread(workspaceId: String, title: String, mode: String): ThreadEntity {
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

    /** [TurnRunner] fake: records starts/stops, never invokes the runner (no TurnContext on the JVM). */
    private class FakeTurnRunner(
        private val autoFinish: Boolean = true,
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
            if (!autoFinish) delay(Long.MAX_VALUE) // cancellable: kill cancels the spawn job
        }
    }

    /** [SubagentLoopFactory] fake: the runner lambda is never invoked by [FakeTurnRunner]. */
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

    private fun orchestrator(
        parentRules: List<GlobRule> = emptyList(),
        turnRunner: TurnRunner = FakeTurnRunner(),
        history: FakeHistoryStore = FakeHistoryStore(),
    ): Pair<SubagentOrchestrator, FakeHistoryStore> {
        val orch = SubagentOrchestrator(history, turnRunner, FakeLoopFactory(), parentRules = parentRules)
        return orch to history
    }

    @Test
    fun effectiveChildRules_neverWidensParentPermissions() {
        val parent = listOf(
            GlobRule("src/**", GlobDecision.ALLOW),
            GlobRule("**/*.key", GlobDecision.DENY),
        )
        val child = listOf(
            GlobRule("**", GlobDecision.ALLOW),
        )
        val engine = SubagentOrchestrator.effectiveChildRules(parent, child)

        // Child's blanket ALLOW cannot override parent's DENY on keys.
        assertEquals(GlobDecision.DENY, engine.decide("secrets/api.key"))
        // Parent's src/** ALLOW is preserved.
        assertEquals(GlobDecision.ALLOW, engine.decide("src/Main.kt"))
        // Paths outside parent's ALLOW must remain denied (default deny).
        assertEquals(GlobDecision.DENY, engine.decide("docs/README.md"))
    }

    @Test
    fun secretDenyRules_neverWidenedByParentOrChild() {
        // Even a parent + child that both allow everything cannot open secret files.
        val (orch, _) = orchestrator(parentRules = listOf(GlobRule("**", GlobDecision.ALLOW)))
        val childAllowAll = listOf(GlobRule("**", GlobDecision.ALLOW))
        assertFalse(orch.isPathAllowed(".env", childAllowAll))
        assertFalse(orch.isPathAllowed("config/.env.local", childAllowAll))
        assertFalse(orch.isPathAllowed("keys/release.pem", childAllowAll))
        assertFalse(orch.isPathAllowed("keys/debug.jks", childAllowAll))
        assertFalse(orch.isPathAllowed("keys/app.keystore", childAllowAll))
        assertTrue(orch.isPathAllowed("src/Main.kt", childAllowAll))
    }

    @Test
    fun nestedOrchestrator_intersectsAgain() {
        val (orch, _) = orchestrator(parentRules = listOf(GlobRule("src/**", GlobDecision.ALLOW)))
        val nested = orch.nestedOrchestrator(listOf(GlobRule("**", GlobDecision.ALLOW)))
        // The nested child's blanket ALLOW still cannot escape src/**.
        assertEquals(GlobDecision.ALLOW, nested.effectiveRules(emptyList()).decide("src/Main.kt"))
        assertEquals(GlobDecision.DENY, nested.effectiveRules(emptyList()).decide("docs/README.md"))
        // And secrets stay denied at depth 2 as well.
        assertEquals(GlobDecision.DENY, nested.effectiveRules(emptyList()).decide("src/keys.pem"))
    }

    @Test
    fun spawnAndKillSubagent_tracksLifecycle() = runBlocking {
        val turnRunner = FakeTurnRunner()
        val history = FakeHistoryStore()
        val (orchestrator, _) = orchestrator(turnRunner = turnRunner, history = history)

        val info = orchestrator.spawnSubagent("Write unit tests for the auth module")
        assertTrue(info.id.isNotBlank())
        assertEquals(1, orchestrator.listSubagents().size)

        // The spawn job finishes (fake turn completes at once) → done.
        withTimeout(5_000) {
            while (orchestrator.listSubagents().firstOrNull()?.status == "running") delay(10)
        }
        assertEquals("done", orchestrator.listSubagents().first().status)
        assertEquals("general", orchestrator.listSubagents().first().agentName)
        assertEquals(1, turnRunner.starts.size)
        assertTrue(turnRunner.starts[0].goal.contains("Write unit tests for the auth module"))
        assertNotNull(turnRunner.starts[0].threadId)
        assertTrue(history.threads.containsKey(turnRunner.starts[0].threadId))

        assertTrue(orchestrator.killSubagent(info.id))
        assertTrue(orchestrator.listSubagents().isEmpty())
        assertFalse(orchestrator.killSubagent("nonexistent"))
    }

    @Test
    fun killSubagent_stopsRunningTurn() = runBlocking {
        val turnRunner = FakeTurnRunner(autoFinish = false)
        val (orchestrator, _) = orchestrator(turnRunner = turnRunner)

        val info = orchestrator.spawnSubagent("Long running task")
        withTimeout(5_000) {
            while (turnRunner.starts.isEmpty()) delay(10)
        }
        assertEquals("running", orchestrator.listSubagents().first().status)

        assertTrue(orchestrator.killSubagent(info.id))
        assertEquals(listOf(turnRunner.starts[0].threadId), turnRunner.stops)
        assertTrue(orchestrator.listSubagents().isEmpty())
    }

    @Test
    fun spawnSubagent_startRefused_marksFailed() = runBlocking {
        val turnRunner = FakeTurnRunner().also { it.returnStarted = false }
        val (orchestrator, _) = orchestrator(turnRunner = turnRunner)

        orchestrator.spawnSubagent("Task that cannot start")
        withTimeout(5_000) {
            while (orchestrator.listSubagents().firstOrNull()?.status == "running") delay(10)
        }
        assertEquals("failed", orchestrator.listSubagents().first().status)
    }

    @Test
    fun isolateToolResult_scrubsSecrets() {
        val result = ToolResult(
            ok = true,
            output = "key is sk-proj-abcdefghijklmnop and done",
            error = null,
        )
        val scrubbed = isolateToolResult(result)
        assertTrue(scrubbed.ok)
        assertFalse(scrubbed.output.contains("sk-proj-abcdefghijklmnop"))
        assertTrue(scrubbed.output.contains("done"))
    }

    @Test
    fun globGatedTool_deniesSecretPathBeforeDelegateRuns() = runBlocking {
        var delegateRan = false
        val delegate = object : Tool {
            override val name: String = "write_file"
            override val description: String = "fake"
            override val parametersSchema: JsonObject = buildJsonObject {}
            override val sensitive: Boolean = false
            override suspend fun execute(args: JsonObject): ToolResult {
                delegateRan = true
                return ToolResult(ok = true, output = "wrote")
            }
        }
        val engine = SubagentOrchestrator.effectiveChildRules(
            listOf(GlobRule("**", GlobDecision.ALLOW)),
            listOf(GlobRule("**", GlobDecision.ALLOW)),
        )
        // Engine alone has no secret rules here; layer them as spawn does:
        // SECRET_DENY_RULES go LAST so last-match-wins keeps them denied.
        val withSecrets = GlobPermissionEngine(
            listOf(GlobRule("**", GlobDecision.ALLOW)) + SubagentOrchestrator.SECRET_DENY_RULES,
        )
        val gated = GlobGatedTool(delegate, withSecrets)

        val denied = gated.execute(buildJsonObject { put("path", "keys/release.pem") })
        assertFalse(denied.ok)
        assertFalse(delegateRan)
        assertTrue(denied.error.orEmpty().contains("Blocked by subagent permissions"))

        val allowed = gated.execute(buildJsonObject { put("path", "src/Main.kt") })
        assertTrue(allowed.ok)
        assertTrue(delegateRan)
        assertEquals("wrote", allowed.output)
        assertEquals(engine.decide("src/Main.kt"), GlobDecision.ALLOW) // engine sanity, unused above
    }

    @Test
    fun parseAgentToml_parsesPermissionsAndMode() {
        val toml = """
            name = "scout"
            description = "Read-only scout"
            mode = "PLAN"
            tools = ["read_file"]
            [permissions]
            allow = ["src/**"]
            deny = ["**/*.key"]
        """.trimIndent()
        val def = parseAgentToml(toml, "scout")
        assertEquals("scout", def.name)
        assertEquals("Read-only scout", def.description)
        assertEquals(ExecutionMode.PLAN, def.defaultMode)
        assertEquals(listOf("read_file"), def.tools)
        assertTrue(def.rules.contains(GlobRule("src/**", GlobDecision.ALLOW)))
        assertTrue(def.rules.contains(GlobRule("**/*.key", GlobDecision.DENY)))
    }

    @Test
    fun builtinAgents_coverExploreWorkerReviewGeneral() {
        assertEquals(setOf("explore", "worker", "review", "general"), BUILTIN_AGENTS.keys)
        assertEquals(ExecutionMode.PLAN, BUILTIN_AGENTS.getValue("explore").defaultMode)
        assertEquals(ExecutionMode.PLAN, BUILTIN_AGENTS.getValue("review").defaultMode)
        assertEquals(ExecutionMode.AUTO, BUILTIN_AGENTS.getValue("worker").defaultMode)
        assertEquals(ExecutionMode.AUTO, BUILTIN_AGENTS.getValue("general").defaultMode)
    }
}
