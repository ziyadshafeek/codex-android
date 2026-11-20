package dev.codex.android.agent

import dev.codex.android.api.ChatChoice
import dev.codex.android.api.ChatMessage
import dev.codex.android.api.ChatResult
import dev.codex.android.api.FunctionCall
import dev.codex.android.api.OpenAiClient
import dev.codex.android.api.ToolCall
import dev.codex.android.api.ToolDefinition
import dev.codex.android.security.DefaultApprovalGate
import dev.codex.android.skills.ActiveSkillScope
import dev.codex.android.skills.SkillStore
import dev.codex.android.skills.SkillTool
import dev.codex.android.tools.GetTimeTool
import dev.codex.android.tools.Tool
import dev.codex.android.tools.ToolRegistry
import dev.codex.android.tools.ToolResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests proving TRUE tool-scoping in [AgentLoop]: when the model
 * invokes the `skill` tool, the loop offers only that skill's tools
 * on the next turn AND refuses a call to any other tool, even if the
 * model makes one anyway. Follows the ScriptedClient pattern from
 * AgentLoopBatchingTest; no Android APIs, no network, no model.
 */
class SkillScopingTest {

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

    /** Records every chat() call's offered tools + messages; replays a script. */
    private class RecordingClient(private val script: List<ChatResult>) : OpenAiClient {
        val offeredToolsPerTurn = mutableListOf<List<String>>()
        val messagesPerTurn = mutableListOf<List<ChatMessage>>()
        private var index = 0
        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>,
            model: String,
            onPartialText: ((String) -> Unit)?,
        ): ChatResult {
            offeredToolsPerTurn.add(tools.map { it.function.name })
            messagesPerTurn.add(messages.toList())
            return script.getOrNull(index++) ?: ChatResult(
                choices = listOf(ChatChoice(text = "done", toolCalls = emptyList(), finishReason = "stop")),
            )
        }
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

    @Test
    fun skillCall_narrowsNextTurnToolsToSkillToolsPlusSkill() = runBlocking {
        val scope = ActiveSkillScope()
        val tools = ToolRegistry().apply {
            register(GetTimeTool)
            register(SkillTool(skillsProvider = { SkillStore.BUILTINS }, skillScope = scope))
            register(RecordingTool("echo", "echoed"))
        }
        val client = RecordingClient(
            listOf(
                toolCallsResult("skill" to """{"skill_id":"what-time"}"""),
                textResult("It is daytime."),
            ),
        )
        val loop = AgentLoop(
            client = client,
            tools = tools,
            gate = DefaultApprovalGate(),
            planStore = FakePlanRepository(),
            model = "fake-model",
            skills = SkillStore.BUILTINS,
            skillScope = scope,
        )
        val channel = Channel<AgentEvent>(Channel.UNLIMITED)
        assertEquals("It is daytime.", loop.run("what time is it?", channel))
        channel.close()

        assertEquals("turn 1 offers everything", listOf("get_time", "skill", "echo"), client.offeredToolsPerTurn[0])
        assertEquals(
            "turn 2 offers only the what-time skill's tools + skill itself",
            listOf("get_time", "skill"),
            client.offeredToolsPerTurn[1],
        )
        assertEquals("what-time", scope.activeSkill?.id)
    }

    @Test
    fun scopedOutToolCall_isRefusedAndNeverExecutes() = runBlocking {
        val scope = ActiveSkillScope()
        val echo = RecordingTool("echo", "echoed")
        val tools = ToolRegistry().apply {
            register(GetTimeTool)
            register(SkillTool(skillsProvider = { SkillStore.BUILTINS }, skillScope = scope))
            register(echo)
        }
        val client = RecordingClient(
            listOf(
                toolCallsResult("skill" to """{"skill_id":"what-time"}"""),
                // The model ignores the narrowed tool list and calls echo anyway.
                toolCallsResult("echo" to """{"text":"hi"}"""),
                textResult("done without echo"),
            ),
        )
        val loop = AgentLoop(
            client = client,
            tools = tools,
            gate = DefaultApprovalGate(),
            planStore = FakePlanRepository(),
            model = "fake-model",
            skills = SkillStore.BUILTINS,
            skillScope = scope,
        )
        val channel = Channel<AgentEvent>(Channel.UNLIMITED)
        assertEquals("done without echo", loop.run("what time is it?", channel))
        channel.close()

        assertFalse("a scoped-out tool must never execute", echo.executed)
        val denial = client.messagesPerTurn[2].joinToString("\n") { it.content.orEmpty() }
        assertTrue(denial.contains("not available while skill 'what-time'"))
        assertTrue(denial.contains("echo"))
    }
}
