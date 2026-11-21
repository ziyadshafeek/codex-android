package dev.codex.android.tools

import dev.codex.android.linux.ShellResult
import dev.codex.android.skills.Skill
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** JVM tests for the four builder tools registered by [registerBuilderTools]. */
class BuilderToolsTest {

    @get:Rule val temp = TemporaryFolder()

    private val demoSkill = Skill(
        id = "demo",
        name = "Demo skill",
        description = "A skill used by tests.",
        instructions = "Use workspace_read to read the file.",
        toolNames = listOf("workspace_read"),
    )

    private fun registry(
        shell: suspend (String) -> ShellResult = { ShellResult(0, "", "") },
        ensureToolchain: (suspend (String) -> Boolean)? = { true },
        registerStdioServer: (suspend (name: String, command: String, args: List<String>) -> String)? = null,
        browserCheck: (suspend (url: String, checks: List<WebCheck>) -> WebAppTestResult)? = null,
    ): ToolRegistry {
        val workspace = WorkspaceFileTools(temp.newFolder())
        return ToolRegistry().apply {
            registerBuilderTools(
                workspace = workspace,
                shell = shell,
                skillsProvider = { listOf(demoSkill) },
                ensureToolchain = ensureToolchain,
                registerStdioServer = registerStdioServer,
                browserCheck = browserCheck,
            )
        }
    }

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    @Test
    fun allFourBuilderTools_areRegistered() {
        val tools = registry()
        assertEquals(
            listOf("mcp_scaffold", "skill_eval", "webapp_build", "webapp_test"),
            tools.all().map { it.name }.sorted(),
        )
    }

    @Test
    fun mcpScaffold_python_writesFilesAndRegisters() = runBlocking {
        val registered = mutableListOf<Triple<String, String, List<String>>>()
        val tools = registry(registerStdioServer = { name, command, args ->
            registered += Triple(name, command, args)
            "mcp_stdio_test123"
        })
        val tool = tools.get("mcp_scaffold")!!
        val result = tool.execute(args("name" to "notes-server", "language" to "python"))
        assertTrue(result.ok)
        assertTrue(result.output.contains("mcp-servers/notes-server"))
        assertTrue(result.output.contains("mcp_stdio_test123"))
        assertEquals(1, registered.size)
        assertEquals("python3", registered[0].second)
        assertTrue(registered[0].third[0].endsWith("server.py"))
        val root = File(temp.root, temp.root.list()!!.first())
        assertTrue(File(root, "mcp-servers/notes-server/server.py").isFile)
        assertTrue(File(root, "mcp-servers/notes-server/mcp-server.json").isFile)
        assertTrue(File(root, "mcp-servers/notes-server/README.md").isFile)
    }

    @Test
    fun mcpScaffold_existingDirectory_isAClearErrorNoOverwrite() = runBlocking {
        val tools = registry()
        val tool = tools.get("mcp_scaffold")!!
        assertTrue(tool.execute(args("name" to "dup", "language" to "python")).ok)
        val second = tool.execute(args("name" to "dup", "language" to "typescript"))
        assertFalse(second.ok)
        assertTrue(second.error!!.contains("already exists"))
    }

    @Test
    fun mcpScaffold_invalidLanguage_isAClearError() = runBlocking {
        val result = registry().get("mcp_scaffold")!!
            .execute(args("name" to "x", "language" to "ruby"))
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("ruby"))
    }

    @Test
    fun mcpScaffold_withoutHub_reportsManualStep() = runBlocking {
        val result = registry().get("mcp_scaffold")!!
            .execute(args("name" to "manual-one", "language" to "typescript"))
        assertTrue(result.ok)
        assertTrue(result.output.contains("Manual step"))
        assertTrue(result.output.contains("node"))
    }

    @Test
    fun skillEval_passAndFail_areReportedNotCrashed() = runBlocking {
        val tool = registry().get("skill_eval")!!
        val cases = """[
            {"name":"reads","input":"read notes.txt","expectedTools":["workspace_read"]},
            {"name":"needs-shell","input":"run it","expectedTools":["shell_exec"]}
        ]"""
        val result = tool.execute(args("skill_id" to "demo", "cases" to cases))
        assertTrue(result.ok)
        assertTrue(result.output.contains("1/2 passed"))
        assertTrue(result.output.contains("PASS [reads]"))
        assertTrue(result.output.contains("FAIL [needs-shell]"))
    }

    @Test
    fun skillEval_unknownSkill_isAClearError() = runBlocking {
        val result = registry().get("skill_eval")!!
            .execute(args("skill_id" to "nope", "cases" to """[{"name":"a","input":"x"}]"""))
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("nope"))
    }

    @Test
    fun webappTest_passingRun_reportsPass() = runBlocking {
        val commands = mutableListOf<String>()
        val tools = registry(shell = { cmd ->
            commands += cmd
            ShellResult(0, "WEBAPP_TEST_RESULT {\"passed\":1,\"failed\":0,\"failures\":[]}\n", "")
        })
        val result = tools.get("webapp_test")!!.execute(
            args("url" to "https://example.com", "checks" to """[{"name":"h","selector":"h1"}]"""),
        )
        assertTrue(result.ok)
        assertTrue(result.output.contains("all 1 check(s) passed"))
        assertTrue(commands[0].contains("node /workspace/webapp-tests/"))
        assertTrue(commands[0].contains("PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH"))
    }

    @Test
    fun webappTest_missingPlaywright_fallsBackToBrowserCheckAndSaysSo() = runBlocking {
        val tools = registry(
            shell = { ShellResult(1, "", "Error: Cannot find module 'playwright'\n") },
            ensureToolchain = { true },
            browserCheck = { _, _ ->
                WebAppTestResult(true, "verification-browser", 1, 0, emptyList(), "Page loaded, 1 check passed.")
            },
        )
        val result = tools.get("webapp_test")!!.execute(args("url" to "https://example.com"))
        assertTrue(result.ok)
        assertTrue(result.output.contains("VerificationBrowser fallback"))
        assertTrue(result.output.contains("NOT Playwright"))
    }

    @Test
    fun webappTest_missingPlaywrightNoFallback_isHonestFailure() = runBlocking {
        val tools = registry(
            shell = { ShellResult(1, "", "Error: Cannot find module 'playwright'\n") },
            ensureToolchain = null,
        )
        val result = tools.get("webapp_test")!!.execute(args("url" to "https://example.com"))
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("Playwright is not staged"))
    }

    @Test
    fun webappBuild_success_returnsDistPath() = runBlocking {
        val commands = mutableListOf<String>()
        val tools = registry(shell = { cmd ->
            commands += cmd
            if (cmd.contains("npm install")) ShellResult(0, "added 100 packages", "")
            else ShellResult(0, "vite building...\n✓ built in 512ms\n", "")
        })
        val result = tools.get("webapp_build")!!.execute(args("name" to "demo-app"))
        assertTrue(result.ok)
        assertTrue(result.output.contains("/workspace/web-artifacts/demo-app/dist"))
        assertTrue(commands.any { it.contains("npm install") })
        assertTrue(commands.any { it.contains("npm run build") })
    }

    @Test
    fun webappBuild_noNodeSeam_failsHonestlyAfterScaffold() = runBlocking {
        val tools = registry(ensureToolchain = null)
        val result = tools.get("webapp_build")!!.execute(args("name" to "demo-app"))
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("did NOT run"))
        assertTrue(result.error!!.contains("Node.js"))
    }

    @Test
    fun webappBuild_failedBuild_reportsErrors() = runBlocking {
        val tools = registry(shell = { cmd ->
            if (cmd.contains("npm install")) ShellResult(0, "ok", "")
            else ShellResult(1, "Error: Failed to resolve import \"./Missing.jsx\"\n", "")
        })
        val result = tools.get("webapp_build")!!.execute(args("name" to "demo-app"))
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("Failed to resolve"))
    }

    @Test
    fun webappBuild_existingDirectory_isAClearError() = runBlocking {
        val tools = registry(shell = { cmd ->
            if (cmd.contains("npm install")) ShellResult(0, "added 100 packages", "")
            else ShellResult(0, "vite building...\n✓ built in 512ms\n", "")
        })
        val tool = tools.get("webapp_build")!!
        assertTrue(tool.execute(args("name" to "dup-app")).ok)
        val second = tool.execute(args("name" to "dup-app"))
        assertFalse(second.ok)
        assertTrue(second.error!!.contains("already exists"))
    }
}
