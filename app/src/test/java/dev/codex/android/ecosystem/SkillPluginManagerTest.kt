package dev.codex.android.ecosystem

import dev.codex.android.skills.Skill
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM unit tests for [SkillMarkdownParser] and [SkillPluginManager].
 *
 * No real network is ever used: GitHub import goes through a fake OkHttp
 * [Interceptor] returning canned responses (same pattern as McpClientTest).
 * No Android Context is needed: installs go through an in-memory
 * [SkillSink]; the real [SkillStore] is only wired via [SkillStoreSink]
 * in production code. JUnit 4 has no @TempDir, so temp directories are
 * created with [Files.createTempDirectory] and deleted in [tearDown].
 */
class SkillPluginManagerTest {

    private class FakeSink : SkillSink {
        val saved = mutableListOf<Skill>()
        override suspend fun save(skill: Skill) {
            saved += skill
        }
    }

    private data class CannedResponse(val code: Int, val body: String)

    /** Routes requests to canned responses by URL; records auth headers. */
    private class CannedInterceptor(
        private val responses: Map<String, CannedResponse>,
        private val defaultCode: Int = 404,
    ) : Interceptor {
        val requests = mutableListOf<String>()
        val authHeaders = mutableMapOf<String, String?>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()
            requests += url
            authHeaders[url] = request.header("Authorization")
            val canned = responses.entries
                .firstOrNull { (key, _) -> url.contains(key) }?.value
                ?: CannedResponse(defaultCode, "")
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(canned.code)
                .message(if (canned.code == 200) "OK" else "Error")
                .header("Content-Type", "application/json")
                .body(canned.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private lateinit var tempDir: File
    private lateinit var globalDir: File
    private lateinit var sink: FakeSink

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("codex-skill-test").toFile()
        globalDir = File(tempDir, "global-skills").apply { mkdirs() }
        sink = FakeSink()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun manager(httpClient: OkHttpClient? = null): SkillPluginManager =
        if (httpClient == null) {
            SkillPluginManager(globalDir, sink)
        } else {
            SkillPluginManager(globalDir, sink, httpClient)
        }

    private fun writeFile(relativePath: String, content: String): File {
        val file = File(tempDir, relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
        return file
    }

    private fun skillText(
        name: String? = "code-review",
        description: String = "Reviews code for security issues and code smells. Use when the user asks to review a PR.",
        extraFrontmatter: String = "",
        body: String = "1. Read the diff.\n2. List issues with file and line.",
    ): String = buildString {
        append("---\n")
        if (name != null) append("name: $name\n")
        append("description: $description\n")
        if (extraFrontmatter.isNotEmpty()) append(extraFrontmatter)
        append("---\n")
        append(body)
        append("\n")
    }

    // ---- SKILL.md parsing -------------------------------------------------

    @Test
    fun validSkill_parsesNameDescriptionBodyAndTools() {
        val text = skillText(
            extraFrontmatter = "allowed-tools:\n  - read\n  - grep\nlicense: MIT\n",
        )
        val outcome = SkillMarkdownParser.parseSkillFile("code-review", text, "code-review/SKILL.md")
        assertTrue(outcome is SkillParseOutcome.Valid)
        val skill = (outcome as SkillParseOutcome.Valid).skill
        assertEquals("code-review", skill.id)
        assertEquals("code-review", skill.displayName)
        assertTrue(skill.description.startsWith("Reviews code"))
        assertEquals("1. Read the diff.\n2. List issues with file and line.", skill.instructions)
        assertEquals(listOf("read", "grep"), skill.toolNames)
        assertEquals("MIT", skill.license)
        assertTrue(skill.autoInvoke)
        val stored = skill.toSkill()
        assertEquals("code-review", stored.id)
        assertTrue(stored.enabled)
    }

    @Test
    fun foldedDescription_isJoinedWithSpaces() {
        val text = """
            ---
            name: deploy-preview
            description: >
              Build and deploy a preview environment
              for the current branch.
            ---
            Never deploy to production.
        """.trimIndent()
        val outcome = SkillMarkdownParser.parseSkillFile("deploy-preview", text, "x")
        assertTrue(outcome is SkillParseOutcome.Valid)
        val skill = (outcome as SkillParseOutcome.Valid).skill
        assertEquals(
            "Build and deploy a preview environment for the current branch.",
            skill.description,
        )
    }

    @Test
    fun flowListAndToolsAlias_areParsed() {
        val text = skillText(
            extraFrontmatter = "tools: [read, write, shell]\ncompatibility: opencode, codex\n",
        )
        val outcome = SkillMarkdownParser.parseSkillFile("code-review", text, "x")
        assertTrue(outcome is SkillParseOutcome.Valid)
        val skill = (outcome as SkillParseOutcome.Valid).skill
        assertEquals(listOf("read", "write", "shell"), skill.toolNames)
        assertEquals(listOf("opencode", "codex"), skill.compatibility)
    }

    @Test
    fun quotedScalars_areUnquoted() {
        val text = skillText(
            name = null,
            extraFrontmatter = "description: \"Quoted: with a colon\"\n",
        )
        // skillText already appends a description line; the extra quoted
        // one comes after it, so the LAST value wins in our parser.
        val outcome = SkillMarkdownParser.parseSkillFile("code-review", text, "x")
        assertTrue(outcome is SkillParseOutcome.Valid)
        val skill = (outcome as SkillParseOutcome.Valid).skill
        assertEquals("Quoted: with a colon", skill.description)
        // No frontmatter name: display name falls back to the id.
        assertEquals("code-review", skill.displayName)
    }

    @Test
    fun missingDescription_isRejected() {
        val text = "---\nname: lonely\n---\nDo the thing.\n"
        val outcome = SkillMarkdownParser.parseSkillFile("lonely", text, "lonely/SKILL.md")
        assertTrue(outcome is SkillParseOutcome.Rejected)
        assertTrue((outcome as SkillParseOutcome.Rejected).reason.contains("description"))
    }

    @Test
    fun missingFrontmatter_isRejected() {
        val outcome = SkillMarkdownParser.parseSkillFile(
            "plain",
            "# Just markdown, no frontmatter\n",
            "plain/SKILL.md",
        )
        assertTrue(outcome is SkillParseOutcome.Rejected)
    }

    @Test
    fun unterminatedFrontmatter_isRejected() {
        val outcome = SkillMarkdownParser.parseSkillFile(
            "broken",
            "---\nname: broken\n",
            "broken/SKILL.md",
        )
        assertTrue(outcome is SkillParseOutcome.Rejected)
    }

    @Test
    fun emptyBody_isRejected() {
        val text = "---\nname: empty\ndescription: Has a description but no instructions at all here.\n---\n"
        val outcome = SkillMarkdownParser.parseSkillFile("empty", text, "empty/SKILL.md")
        assertTrue(outcome is SkillParseOutcome.Rejected)
    }

    @Test
    fun invalidId_isRejected() {
        val text = skillText()
        assertTrue(
            SkillMarkdownParser.parseSkillFile("MySkill", text, "x")
                is SkillParseOutcome.Rejected,
        )
        assertTrue(
            SkillMarkdownParser.parseSkillFile("bad__id", text, "x")
                is SkillParseOutcome.Rejected,
        )
        assertTrue(
            SkillMarkdownParser.parseSkillFile("-leading", text, "x")
                is SkillParseOutcome.Rejected,
        )
        assertTrue(
            SkillMarkdownParser.parseSkillFile("a".repeat(65), text, "x")
                is SkillParseOutcome.Rejected,
        )
    }

    @Test
    fun autoinvokeFalse_disablesSkillOnInstall() {
        val text = skillText(
            extraFrontmatter = "metadata:\n  opencode/autoinvoke: false\n",
        )
        val outcome = SkillMarkdownParser.parseSkillFile("quiet", text, "x")
        assertTrue(outcome is SkillParseOutcome.Valid)
        val skill = (outcome as SkillParseOutcome.Valid).skill
        assertFalse(skill.autoInvoke)
        assertFalse(skill.toSkill().enabled)
    }

    @Test
    fun disableModelInvocation_disablesSkillOnInstall() {
        val text = skillText(extraFrontmatter = "disable-model-invocation: true\n")
        val outcome = SkillMarkdownParser.parseSkillFile("quiet", text, "x")
        assertTrue(outcome is SkillParseOutcome.Valid)
        assertFalse((outcome as SkillParseOutcome.Valid).skill.autoInvoke)
    }

    @Test
    fun autoinvokeMetadata_winsOverDisableFlag() {
        // OpenCode docs: when both are set, opencode/autoinvoke wins.
        val text = skillText(
            extraFrontmatter = "disable-model-invocation: true\n" +
                "metadata:\n  opencode/autoinvoke: true\n",
        )
        val outcome = SkillMarkdownParser.parseSkillFile("loud", text, "x")
        assertTrue(outcome is SkillParseOutcome.Valid)
        assertTrue((outcome as SkillParseOutcome.Valid).skill.autoInvoke)
    }

    // ---- discovery --------------------------------------------------------

    @Test
    fun discovery_projectOverridesGlobal_flatAndNestedFound_hiddenSkipped() {
        writeFile(
            "global-skills/git-release/SKILL.md",
            skillText(description = "Global release notes skill, used for releases."),
        )
        writeFile(
            "global-skills/flat-note.md",
            skillText(description = "A flat markdown skill at the source root."),
        )
        writeFile(
            "global-skills/bad-skill/SKILL.md",
            "---\nname: bad-skill\n---\nNo description here.\n",
        )
        writeFile(
            "global-skills/.hidden/SKILL.md",
            skillText(description = "Hidden directory skill that must be skipped."),
        )
        writeFile(
            "global-skills/SKILL.md",
            skillText(description = "Bare SKILL.md at the root has no id."),
        )
        writeFile(
            "global-skills/teams/release/SKILL.md",
            skillText(description = "Nested grouping directory skill for releases."),
        )
        writeFile(
            "workspace/.codex/skills/git-release/SKILL.md",
            skillText(description = "Project release notes skill, used for releases."),
        )
        writeFile(
            "workspace/.agents/skills/agent-helper/SKILL.md",
            skillText(description = "Compat agents-skills directory skill for helpers."),
        )

        val result = manager().discoverSkills(File(tempDir, "workspace"))

        val byId = result.skills.associateBy { it.skill.id }
        assertEquals(
            setOf("git-release", "flat-note", "release", "agent-helper"),
            byId.keys,
        )
        // Project wins over global for the same id.
        assertEquals(
            "Project release notes skill, used for releases.",
            byId.getValue("git-release").skill.description,
        )
        assertEquals(SkillScope.PROJECT, byId.getValue("git-release").scope)
        assertEquals(SkillScope.GLOBAL, byId.getValue("flat-note").scope)
        assertEquals(SkillScope.GLOBAL, byId.getValue("release").scope)
        assertEquals(1, result.overridden.size)
        assertEquals("git-release", result.overridden.single().skill.id)
        assertEquals(SkillScope.GLOBAL, result.overridden.single().scope)
        // bad-skill (no description) + bare root SKILL.md are rejected.
        assertEquals(2, result.rejected.size)
        assertTrue(result.rejected.any { it.path.contains("bad-skill") })
        assertTrue(result.rejected.any { it.path.endsWith("global-skills/SKILL.md") })
    }

    @Test
    fun discovery_missingDirs_returnsEmpty() {
        val result = manager().discoverSkills(File(tempDir, "no-such-workspace"))
        assertTrue(result.skills.isEmpty())
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun installDiscovered_savesSkillsAndRespectsAutoinvoke() = runBlocking {
        writeFile(
            "global-skills/normal/SKILL.md",
            skillText(description = "A normal skill for everyday use."),
        )
        writeFile(
            "global-skills/quiet/SKILL.md",
            skillText(
                description = "A quiet skill that should not auto-invoke.",
                extraFrontmatter = "metadata:\n  opencode/autoinvoke: false\n",
            ),
        )
        val result = manager().installDiscovered()

        assertEquals(listOf("normal", "quiet"), result.installed.sorted())
        assertTrue(result.rejected.isEmpty())
        assertTrue(result.failed.isEmpty())
        assertEquals(2, sink.saved.size)
        val byId = sink.saved.associateBy { it.id }
        assertTrue(byId.getValue("normal").enabled)
        assertFalse(byId.getValue("quiet").enabled)
        assertEquals(emptyList<String>(), byId.getValue("normal").toolNames)
    }

    // ---- GitHub URL parsing ----------------------------------------------

    @Test
    fun parseGitHubRepoUrl_acceptsShapes() {
        assertEquals(
            GitHubRepoRef("acme", "widgets", null, ""),
            SkillPluginManager.parseGitHubRepoUrl("https://github.com/acme/widgets"),
        )
        assertEquals(
            GitHubRepoRef("acme", "widgets", null, ""),
            SkillPluginManager.parseGitHubRepoUrl("https://github.com/acme/widgets/"),
        )
        assertEquals(
            GitHubRepoRef("acme", "widgets", null, ""),
            SkillPluginManager.parseGitHubRepoUrl("https://github.com/acme/widgets.git"),
        )
        assertEquals(
            GitHubRepoRef("acme", "widgets", "dev", "skills"),
            SkillPluginManager.parseGitHubRepoUrl(
                "https://github.com/acme/widgets/tree/dev/skills",
            ),
        )
        assertEquals(
            GitHubRepoRef("acme", "widgets", "main", ""),
            SkillPluginManager.parseGitHubRepoUrl(
                "https://github.com/acme/widgets/tree/main",
            ),
        )
    }

    @Test
    fun parseGitHubRepoUrl_rejectsNonRepoUrls() {
        assertNull(SkillPluginManager.parseGitHubRepoUrl("https://example.com/acme/widgets"))
        assertNull(SkillPluginManager.parseGitHubRepoUrl("https://github.com/acme"))
        assertNull(SkillPluginManager.parseGitHubRepoUrl("https://github.com/acme/widgets/issues/1"))
        assertNull(SkillPluginManager.parseGitHubRepoUrl("not a url"))
    }

    // ---- GitHub import (fake interceptor, no network) ----------------------

    private fun githubHappyPathClient(): Pair<OkHttpClient, CannedInterceptor> {
        val reviewSkill = skillText(
            description = "Reviews code for security issues and code smells.",
            extraFrontmatter = "allowed-tools:\n  - read\n  - grep\n",
        )
        val deploySkill = skillText(
            name = "deploy-preview",
            description = "Builds and deploys a preview environment for the branch.",
        )
        val interceptor = CannedInterceptor(
            mapOf(
                "/git/trees/" to CannedResponse(
                    200,
                    """{"sha":"abc","truncated":false,"tree":[
                        {"path":"skills/review/SKILL.md","type":"blob"},
                        {"path":"skills/deploy/SKILL.md","type":"blob"},
                        {"path":".codex-plugin/plugin.json","type":"blob"},
                        {"path":"hooks.json","type":"blob"},
                        {"path":"mcp.json","type":"blob"},
                        {"path":"README.md","type":"blob"}
                    ]}""",
                ),
                "api.github.com/repos/acme/widgets" to CannedResponse(
                    200,
                    """{"default_branch":"main"}""",
                ),
                "skills/review/SKILL.md" to CannedResponse(200, reviewSkill),
                "skills/deploy/SKILL.md" to CannedResponse(200, deploySkill),
                "plugin.json" to CannedResponse(
                    200,
                    """{"name":"widgets","version":"1.2.0","description":"Widget helpers","skills":["skills/review","skills/deploy"]}""",
                ),
                "hooks.json" to CannedResponse(
                    200,
                    """{"hooks":{"PreToolUse":"lint.sh","PostToolUse":"format.sh"}}""",
                ),
                "mcp.json" to CannedResponse(
                    200,
                    """{"mcpServers":{"github":{"command":"npx"},"sqlite":{"command":"uvx"}}}""",
                ),
            ),
        )
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        return client to interceptor
    }

    @Test
    fun importFromGitHub_installsSkillsAndRecordsHooksAndMcpAsPending() = runBlocking {
        val (client, interceptor) = githubHappyPathClient()
        val manager = SkillPluginManager(globalDir, sink, client) { "test-pat" }
        val result = manager.importFromGitHub("https://github.com/acme/widgets")

        assertTrue(result.succeeded)
        assertEquals("acme", result.owner)
        assertEquals("widgets", result.repo)
        assertEquals("main", result.branch)
        assertEquals("widgets", result.pluginName)
        assertEquals("1.2.0", result.pluginVersion)
        assertEquals(listOf("deploy", "review"), result.skillsInstalled)
        assertTrue(result.skillsRejected.isEmpty())
        assertTrue(result.errors.isEmpty())
        // Hooks + MCP are RECORDED, never active.
        assertEquals(PendingComponentStatus.RECORDED_PENDING, result.hooksStatus)
        assertEquals(listOf("PostToolUse", "PreToolUse"), result.hooksFound)
        assertTrue(result.hooksRawJson?.contains("lint.sh") == true)
        assertEquals(PendingComponentStatus.RECORDED_PENDING, result.mcpStatus)
        assertEquals(listOf("github", "sqlite"), result.mcpServersFound)
        assertTrue(result.mcpRawJson?.contains("npx") == true)
        // Skills actually landed in the sink.
        assertEquals(listOf("deploy", "review"), sink.saved.map { it.id }.sorted())
        // The PAT went to api.github.com only, never to raw.githubusercontent.com.
        val apiCalls = interceptor.requests.filter { it.contains("api.github.com") }
        val rawCalls = interceptor.requests.filter { it.contains("raw.githubusercontent.com") }
        assertTrue(apiCalls.isNotEmpty())
        assertTrue(rawCalls.isNotEmpty())
        assertTrue(apiCalls.all { interceptor.authHeaders[it] == "Bearer test-pat" })
        assertTrue(rawCalls.all { interceptor.authHeaders[it] == null })
    }

    @Test
    fun importFromGitHub_badUrl_makesNoRequests() = runBlocking {
        val (client, interceptor) = githubHappyPathClient()
        val result = manager(client).importFromGitHub("https://example.com/acme/widgets")

        assertFalse(result.succeeded)
        assertTrue(result.errors.isNotEmpty())
        assertTrue(result.skillsInstalled.isEmpty())
        assertTrue(interceptor.requests.isEmpty())
    }

    @Test
    fun importFromGitHub_repoNotFound_reportsError() = runBlocking {
        val interceptor = CannedInterceptor(
            mapOf(
                "api.github.com/repos/acme/nope" to CannedResponse(404, """{"message":"Not Found"}"""),
            ),
        )
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val result = manager(client).importFromGitHub("https://github.com/acme/nope")

        assertFalse(result.succeeded)
        assertTrue(result.errors.isNotEmpty())
        assertTrue(result.skillsInstalled.isEmpty())
        assertEquals(PendingComponentStatus.NOT_PRESENT, result.hooksStatus)
        assertEquals(PendingComponentStatus.NOT_PRESENT, result.mcpStatus)
    }

    @Test
    fun importFromGitHub_invalidSkillInRepo_isRejectedNotFatal() = runBlocking {
        val interceptor = CannedInterceptor(
            mapOf(
                "/git/trees/" to CannedResponse(
                    200,
                    """{"sha":"abc","truncated":false,"tree":[
                        {"path":"skills/good/SKILL.md","type":"blob"},
                        {"path":"skills/bad/SKILL.md","type":"blob"}
                    ]}""",
                ),
                "api.github.com/repos/acme/widgets" to CannedResponse(
                    200,
                    """{"default_branch":"main"}""",
                ),
                "skills/good/SKILL.md" to CannedResponse(
                    200,
                    skillText(description = "A good skill for everyday use."),
                ),
                "skills/bad/SKILL.md" to CannedResponse(
                    200,
                    "---\nname: bad\n---\nNo description here.\n",
                ),
            ),
        )
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val result = manager(client).importFromGitHub("https://github.com/acme/widgets")

        assertTrue(result.succeeded)
        assertEquals(listOf("good"), result.skillsInstalled)
        assertEquals(1, result.skillsRejected.size)
        assertTrue(result.skillsRejected.single().path.contains("skills/bad/SKILL.md"))
    }
}
