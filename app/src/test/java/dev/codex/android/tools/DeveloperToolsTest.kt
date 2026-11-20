package dev.codex.android.tools

import dev.codex.android.agent.AgentEvent
import dev.codex.android.agent.AgentLoop
import dev.codex.android.agent.Plan
import dev.codex.android.agent.PlanRepository
import dev.codex.android.api.ChatChoice
import dev.codex.android.api.ChatMessage
import dev.codex.android.api.ChatResult
import dev.codex.android.api.FunctionCall
import dev.codex.android.api.OpenAiClient
import dev.codex.android.api.ToolCall
import dev.codex.android.api.ToolDefinition
import dev.codex.android.ci.GitHubActionsEngine
import dev.codex.android.linux.ShellResult
import dev.codex.android.security.DefaultApprovalGate
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeveloperToolsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun modelCanRequestWorkspaceShellButOnlyAnApprovedCommandRuns() = runBlocking {
        val commands = mutableListOf<String>()
        val tools = ToolRegistry().apply {
            registerDeveloperTools(WorkspaceFileTools(temp.newFolder()), shell = {
                commands += it
                ShellResult(0, "terminal-ok", "")
            }, github = GitHubActionsEngine({ null }), repository = { null })
        }
        for (approve in listOf(false, true)) {
            val gate = DefaultApprovalGate()
            var count = 0
            var result = ""
            val client = object : OpenAiClient {
                override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDefinition>, model: String,
                                          onPartialText: ((String) -> Unit)?): ChatResult {
                    assertTrue(tools.any { it.function.name == "shell_exec" })
                    assertEquals("previous turn", messages[1].content)
                    val choice = if (count++ == 0) {
                        ChatChoice(null, listOf(ToolCall("shell-1", function =
                            FunctionCall("shell_exec", """{"command":"echo terminal-ok"}"""))), "tool_calls")
                    } else {
                        result = messages.last().content.orEmpty()
                        ChatChoice("done", emptyList(), "stop")
                    }
                    return ChatResult(listOf(choice))
                }
            }
            val store = object : PlanRepository {
                override suspend fun loadLatest(): Plan? = null
                override suspend fun save(plan: Plan) = Unit
                override suspend fun clear() = Unit
            }
            val loop = AgentLoop(client, tools, gate, store, "test",
                recentConversation = listOf(ChatMessage("user", "previous turn")))
            val events = Channel<AgentEvent>(Channel.UNLIMITED)
            withTimeout(5000) {
                val run = async { loop.run("continue", events) }
                val approval = gate.pending.filterNotNull().first()
                assertEquals("shell_exec", approval.toolName)
                assertTrue(commands.isEmpty())
                gate.answer(approval.id, approve)
                assertEquals("done", run.await())
            }
            events.close()
            assertTrue(if (approve) result.contains("terminal-ok") else result.contains("denied"))
        }
        assertEquals(listOf("echo terminal-ok"), commands)
    }

    @Test
    fun sourceToGitHubToApkLink_reportsOnlyWhatGitHubReturned() = runBlocking {
        val workspace = temp.newFolder()
        val seen = mutableListOf<String>()
        var success = false
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("api.github.com", request.url.host)
            assertEquals("Bearer test-token", request.header("Authorization"))
            val path = request.url.encodedPath.removePrefix("/repos/owner/app")
            seen += "${request.method} $path"
            val body = Buffer().apply { request.body?.writeTo(this) }.readUtf8()
            val (code, json) = when (path) {
                "" -> 200 to """{"default_branch":"main"}"""
                "/git/ref/heads/codex/runner" -> 404 to "{}"
                "/git/ref/heads/main" -> 200 to """{"object":{"sha":"parent"}}"""
                "/git/commits/parent" -> 200 to """{"tree":{"sha":"base"}}"""
                "/git/trees" -> {
                    assertTrue(body.contains("\"base_tree\":\"base\""))
                    assertTrue(body.contains("src/Game.kt"))
                    assertTrue(body.contains("class Game"))
                    201 to """{"sha":"tree"}"""
                }
                "/git/commits" -> 201 to """{"sha":"new-commit"}"""
                "/git/refs" -> {
                    assertTrue(body.contains("refs/heads/codex/runner"))
                    201 to "{}"
                }
                "/actions/workflows/build.yml/dispatches" -> {
                    assertTrue(body.contains("\"ref\":\"codex/runner\""))
                    204 to ""
                }
                "/actions/runs" -> {
                    assertEquals("codex/runner", request.url.queryParameter("branch"))
                    if (success) 200 to """{"workflow_runs":[{"id":42,"status":"completed","conclusion":"success"}]}"""
                    else 200 to """{"workflow_runs":[{"id":42,"status":"in_progress"}]}"""
                }
                "/actions/runs/42/artifacts" -> 200 to """{"artifacts":[{"name":"app-debug","id":7,"expired":false}]}"""
                else -> error("Unexpected request: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("fixture").body(json.toResponseBody()).build()
        }.build()
        val tools = ToolRegistry().apply {
            registerDeveloperTools(WorkspaceFileTools(workspace), shell = { error("No local build") },
                github = GitHubActionsEngine({ "test-token" }, http), repository = { "owner/app" })
        }
        suspend fun invoke(tool: String, vararg fields: Pair<String, String>): ToolResult =
            tools.get(tool)!!.execute(buildJsonObject { fields.forEach { (key, value) -> put(key, value) } })
        assertTrue(invoke("workspace_write", "path" to "src/Game.kt", "content" to "class Game").ok)
        assertEquals("class Game", File(workspace, "src/Game.kt").readText())
        assertFalse(invoke("github_publish", "branch" to "codex/runner", "paths" to "../token", "message" to "unsafe").ok)
        assertTrue(seen.isEmpty())
        assertTrue(invoke("github_publish", "branch" to "codex/runner", "paths" to "src/Game.kt", "message" to "Publish game").ok)
        assertTrue(invoke("github_build", "branch" to "codex/runner", "workflow" to "build.yml").output.contains("accepted"))
        assertFalse(invoke("github_build_status", "branch" to "codex/runner").output.contains("artifacts"))
        success = true
        assertTrue(invoke("github_build_status", "branch" to "codex/runner").output.contains("actions/runs/42/artifacts/7"))
        assertEquals(1, seen.count { it.endsWith("/artifacts") })
    }
}
