package dev.codex.android.commands

import dev.codex.android.data.TimelineMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for slash-command routing, parsing, and help metadata.
 *
 * Pure logic only: no Android framework, no database, no network, no
 * model calls. Handlers that need Android APIs (CodexDatabase,
 * KeyStore, AgentRuntime, …) are NOT unit-testable on the JVM and are
 * covered by CI's instrumented tests instead — only the pure helpers
 * in [CommandHandlers] ([CommandHandlers.withCompactedFlag],
 * [CommandHandlers.buildMarkdownTranscript],
 * [CommandHandlers.buildSummaryPrompt],
 * [CommandHandlers.buildAgentsMdContent]) are tested here.
 */
class SlashCommandRouterTest {

    // --- isSlashCommand ---

    @Test
    fun isSlashCommand_detectsLeadingSlash() {
        assertTrue(SlashCommandRouter.isSlashCommand("/plan"))
        assertTrue(SlashCommandRouter.isSlashCommand("  /model gpt-4o"))
        assertTrue(SlashCommandRouter.isSlashCommand("/"))
        assertFalse(SlashCommandRouter.isSlashCommand("plan"))
        assertFalse(SlashCommandRouter.isSlashCommand(""))
        assertFalse(SlashCommandRouter.isSlashCommand("  "))
        assertFalse(SlashCommandRouter.isSlashCommand("hello /plan"))
    }

    // --- parseCommand ---

    @Test
    fun parseCommand_plainCommandHasNoArgs() {
        val parsed = SlashCommandRouter.parseCommand("/compact")
        assertEquals("compact", parsed?.name)
        assertEquals(emptyList<String>(), parsed?.args)
        assertEquals("", parsed?.rawArgs)
    }

    @Test
    fun parseCommand_splitsArgsAndKeepsRawArgs() {
        val parsed = SlashCommandRouter.parseCommand("/rename New Title Here")
        assertEquals("rename", parsed?.name)
        assertEquals(listOf("New", "Title", "Here"), parsed?.args)
        assertEquals("New Title Here", parsed?.rawArgs)
    }

    @Test
    fun parseCommand_nameIsLowerCased() {
        assertEquals("model", SlashCommandRouter.parseCommand("/MODEL gpt-4o")?.name)
    }

    @Test
    fun parseCommand_quotedArgStaysTogether() {
        val parsed = SlashCommandRouter.parseCommand("/rename \"My Title Here\"")
        assertEquals(listOf("My Title Here"), parsed?.args)
        assertEquals("\"My Title Here\"", parsed?.rawArgs)
    }

    @Test
    fun parseCommand_planKeepsInlinePromptAsRawArgs() {
        val parsed = SlashCommandRouter.parseCommand("/plan Propose a migration plan")
        assertEquals("plan", parsed?.name)
        assertEquals("Propose a migration plan", parsed?.rawArgs)
    }

    @Test
    fun parseCommand_nonCommandReturnsNull() {
        assertNull(SlashCommandRouter.parseCommand("hello"))
        assertNull(SlashCommandRouter.parseCommand(""))
        assertNull(SlashCommandRouter.parseCommand("  "))
    }

    @Test
    fun parseCommand_bareSlashReturnsNull() {
        assertNull(SlashCommandRouter.parseCommand("/"))
        assertNull(SlashCommandRouter.parseCommand("  /  "))
    }

    // --- registry: metadata and handlers agree ---

    @Test
    fun registry_hasSixteenSpecifiedCommandsPlusHelp() {
        val names = SlashCommandRouter.commands.map { it.name }
        assertEquals(
            listOf(
                "plan", "model", "permissions", "compact", "recap", "fork",
                "rename", "delete", "export", "init", "skills", "plugins",
                "mcp", "usage", "status", "clear", "help",
            ),
            names,
        )
    }

    @Test
    fun registry_everyCommandHasDescriptionAndUsage() {
        for (meta in SlashCommandRouter.commands) {
            assertTrue("empty description: ${meta.name}", meta.description.isNotBlank())
            assertTrue("usage must start with /: ${meta.name}", meta.usage.startsWith("/${meta.name}"))
        }
    }

    @Test
    fun registry_noCommandIsRegisteredTwice() {
        val names = SlashCommandRouter.commands.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun registry_everyCommandExceptHelpHasAHandler() {
        for (meta in SlashCommandRouter.commands) {
            if (meta.name == "help") continue
            assertTrue(
                "no handler for /${meta.name}",
                CommandHandlers.handlerFor(meta.name) != null,
            )
        }
    }

    @Test
    fun registry_everyHandlerHasMetadata() {
        val metaNames = SlashCommandRouter.commands.map { it.name }.toSet()
        for (name in CommandHandlers.handlerNames) {
            assertTrue("no metadata for /$name", name in metaNames)
        }
    }

    @Test
    fun registry_unknownCommandHasNoHandler() {
        assertNull(CommandHandlers.handlerFor("bogus"))
        assertNull(CommandHandlers.handlerFor("worktree"))
        assertNull(CommandHandlers.handlerFor("agents"))
        assertNull(CommandHandlers.handlerFor("subagents"))
        assertNull(CommandHandlers.handlerFor("ci"))
        assertNull(CommandHandlers.handlerFor("preview"))
    }

    // --- help / autocomplete ---

    @Test
    fun helpText_listsEveryCommand() {
        val help = SlashCommandRouter.helpText()
        for (meta in SlashCommandRouter.commands) {
            assertTrue("help missing /${meta.name}", help.contains(meta.usage))
            assertTrue("help missing description for /${meta.name}", help.contains(meta.description))
        }
    }

    @Test
    fun autocomplete_emptyPrefixReturnsAll() {
        assertEquals(
            SlashCommandRouter.commands,
            SlashCommandRouter.autocomplete(""),
        )
        assertEquals(
            SlashCommandRouter.commands,
            SlashCommandRouter.autocomplete("/"),
        )
    }

    @Test
    fun autocomplete_filtersByPrefix() {
        assertEquals(
            listOf("model", "mcp"),
            SlashCommandRouter.autocomplete("m").map { it.name },
        )
        assertEquals(
            listOf("plugins"),
            SlashCommandRouter.autocomplete("/pl").map { it.name },
        )
        assertEquals(
            listOf("model"),
            SlashCommandRouter.autocomplete("MO").map { it.name },
        )
        assertTrue(SlashCommandRouter.autocomplete("zzz").isEmpty())
    }

    // --- pure handler helpers ---

    @Test
    fun withCompactedFlag_marksEmptyObject() {
        assertEquals("""{"compacted":true}""", CommandHandlers.withCompactedFlag("{}"))
        assertEquals("""{"compacted":true}""", CommandHandlers.withCompactedFlag(""))
    }

    @Test
    fun withCompactedFlag_preservesExistingKeys() {
        assertEquals(
            """{"a":1,"compacted":true}""",
            CommandHandlers.withCompactedFlag("""{"a":1}"""),
        )
    }

    @Test
    fun withCompactedFlag_leavesAlreadyMarkedAlone() {
        val marked = """{"compacted":true}"""
        assertEquals(marked, CommandHandlers.withCompactedFlag(marked))
    }

    @Test
    fun withCompactedFlag_leavesMalformedAlone() {
        assertEquals("not-json", CommandHandlers.withCompactedFlag("not-json"))
    }

    @Test
    fun buildMarkdownTranscript_containsTitleRolesAndContent() {
        val messages = listOf(
            TimelineMessage(threadId = "thr_x", role = "user", kind = "text", content = "Do the thing"),
            TimelineMessage(threadId = "thr_x", role = "assistant", kind = "text", content = "Done."),
            TimelineMessage(threadId = "thr_x", role = "system", kind = "summary", content = "Summary here."),
        )
        val md = CommandHandlers.buildMarkdownTranscript("My Thread", messages)
        assertTrue(md.startsWith("# My Thread\n"))
        assertTrue(md.contains("## User\n\nDo the thing"))
        assertTrue(md.contains("## Assistant\n\nDone."))
        assertTrue(md.contains("## System (summary)\n\nSummary here."))
    }

    @Test
    fun buildSummaryPrompt_containsTranscriptLines() {
        val messages = listOf(
            TimelineMessage(threadId = "thr_x", role = "user", kind = "text", content = "Fix the crash"),
            TimelineMessage(threadId = "thr_x", role = "assistant", kind = "text", content = "Fixed in Main.kt"),
        )
        val prompt = CommandHandlers.buildSummaryPrompt(messages)
        assertTrue(prompt.contains("user: Fix the crash"))
        assertTrue(prompt.contains("assistant: Fixed in Main.kt"))
        assertTrue(prompt.contains("do not invent"))
    }

    @Test
    fun buildSummaryPrompt_capsTranscriptLength() {
        val long = TimelineMessage(threadId = "thr_x", role = "user", kind = "text", content = "x".repeat(500))
        val prompt = CommandHandlers.buildSummaryPrompt(listOf(long), maxChars = 100)
        assertTrue(prompt.length < 100 + 400) // prompt head + capped transcript
        // The capped transcript is "user: " + 94 x's, so 90 in a row must be present.
        assertTrue(prompt.contains("x".repeat(90)))
    }

    @Test
    fun buildAgentsMdContent_containsProjectAndSkills() {
        val content = CommandHandlers.buildAgentsMdContent(
            projectName = "habit-tracker",
            skillNames = listOf("Summarise screen", "What time is it"),
        )
        assertTrue(content.startsWith("# AGENTS.md"))
        assertTrue(content.contains("habit-tracker"))
        assertTrue(content.contains("Summarise screen"))
        assertTrue(content.contains("What time is it"))
    }

    @Test
    fun buildAgentsMdContent_withoutSkillsOmitsSection() {
        val content = CommandHandlers.buildAgentsMdContent("proj", emptyList())
        assertTrue(content.contains("## Project"))
        assertFalse(content.contains("## Skills available"))
    }
}
