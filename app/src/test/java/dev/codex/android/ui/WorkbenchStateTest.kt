package dev.codex.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for [WorkbenchStateHolder] and pure composer helpers. */
class WorkbenchStateTest {

    @Test
    fun detectComposerTrigger_identifiesSlashBangAgentAndFile() {
        assertEquals(ComposerTriggerKind.SHELL_COMMAND, detectComposerTrigger("!git status").kind)
        assertEquals("git status", detectComposerTrigger("!git status").query)

        assertEquals(ComposerTriggerKind.SLASH_COMMAND, detectComposerTrigger("/comp").kind)
        assertEquals("comp", detectComposerTrigger("/comp").query)

        assertEquals(ComposerTriggerKind.AGENT_MENTION, detectComposerTrigger("please @exp").kind)
        assertEquals("exp", detectComposerTrigger("please @exp").query)

        assertEquals(ComposerTriggerKind.FILE_ATTACH, detectComposerTrigger("check @Main").kind)
        assertEquals("Main", detectComposerTrigger("check @Main").query)

        assertEquals(ComposerTriggerKind.NONE, detectComposerTrigger("regular prompt").kind)
    }

    @Test
    fun fuzzyFilterFiles_prefersExactAndSubsequenceMatches() {
        val files = listOf(
            "app/src/main/java/dev/codex/android/MainActivity.kt",
            "app/src/main/java/dev/codex/android/ui/WorkbenchScreen.kt",
            "app/src/main/java/dev/codex/android/agent/AgentLoop.kt",
            "README.md",
        )
        val mainMatches = fuzzyFilterFiles(files, "main")
        assertEquals("app/src/main/java/dev/codex/android/MainActivity.kt", mainMatches.first())

        val subseqMatches = fuzzyFilterFiles(files, "wbs")
        assertEquals(listOf("app/src/main/java/dev/codex/android/ui/WorkbenchScreen.kt"), subseqMatches)
    }

    @Test
    fun stateHolder_togglesModesAppliesCompletionAndComputesContextPercent() {
        val holder = WorkbenchStateHolder()
        assertEquals(ComposerMode.BUILD, holder.state.value.composerMode)
        holder.toggleComposerMode()
        assertEquals(ComposerMode.PLAN, holder.state.value.composerMode)

        holder.toggleThinking()
        holder.toggleDetails()
        assertTrue(holder.state.value.showThinking)
        assertTrue(holder.state.value.showDetails)

        holder.updateContextUsage(usedTokens = 64_000L, contextWindow = 128_000)
        assertEquals(50, holder.state.value.contextPercent)

        holder.updateComposerText("inspect @Main")
        holder.applyCompletion("@src/Main.kt", attachAsFile = "src/Main.kt")
        assertEquals("inspect @src/Main.kt ", holder.state.value.composerText)
        assertEquals(listOf("src/Main.kt"), holder.state.value.attachedFiles)

        holder.insertPin("@src/Main.kt:L10-L30")
        assertTrue(holder.state.value.composerText.contains("@src/Main.kt:L10-L30"))

        holder.clearComposer()
        assertEquals("", holder.state.value.composerText)
        assertTrue(holder.state.value.attachedFiles.isEmpty())
        assertFalse(holder.state.value.isBangCommand)
    }

    @Test
    fun slashPalette_reusesSlashCommandRouter() {
        val matches = filterSlashPalette("co")
        assertTrue(matches.any { it.name == "compact" })
    }
}
