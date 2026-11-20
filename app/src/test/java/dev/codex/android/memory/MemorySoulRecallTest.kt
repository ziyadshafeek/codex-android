package dev.codex.android.memory

import dev.codex.android.data.Fact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proof that saved memory and soul actually reach the agent.
 *
 * Background: the chat wiring was already complete (ChatViewModel passes
 * `personalMemory::recall` and `personalMemory::soulForAgent` into
 * AgentLoop, which calls them every run), but in practice nothing
 * surfaced. The causes were in this file:
 *
 * 1. `soulForAgent()` returned null when the user never wrote a soul, so
 *    the agent got the bare generic system prompt and no voice at all.
 * 2. `memoryContext()` matched on exact token overlap only, so a note
 *    whose wording shared no token with the request ("where do I print?"
 *    vs "The office printer is on the third floor") scored 0 and was
 *    silently dropped — and the `memory_search` fallback uses the same
 *    matcher, so the model could not find it that way either.
 *
 * These tests pin the fixes. They exercise the real matching, key
 * derivation and soul selection; only the Android SQLite / Shared-
 * Preferences storage underneath `PersonalMemory` is substituted by
 * building the [Fact] list the way `remember()` builds it (same
 * [automaticKey], same "user" source). That storage cannot run in a JVM
 * unit test (no Robolectric in this module), and in CI unit tests the
 * same stub `android.jar` applies — the prompt-building path tested
 * here is the whole path from a saved note to the model request.
 */
class MemorySoulRecallTest {

    private fun fact(key: String, value: String, updatedAt: Long = 0) =
        Fact(0, key, value, "user", 0, updatedAt)

    /** A note saved the way the MemoryCommand path saves it. */
    private fun savedNote(note: String, updatedAt: Long = 0) =
        fact(automaticKey(note), note, updatedAt)

    @Test fun recallMatchesWordFormsNotJustExactWords() {
        val facts = listOf(savedNote("The office printer is on the third floor"))
        // "print" vs "printer": no exact token in common, yet the note is the answer.
        val recalled = memoryContext("Where do I print this?", facts)
        assertNotNull(recalled)
        assertTrue(recalled!!.contains("office printer is on the third floor"))
    }

    @Test fun recallMatchesVerbFormsAndSpellingVariants() {
        val code = memoryContext(
            "help me with coding",
            listOf(savedNote("I write code in Kotlin every day")),
        )
        assertNotNull(code)
        assertTrue(code!!.contains("I write code in Kotlin"))

        val colour = memoryContext(
            "pick a color for the banner",
            listOf(savedNote("My favourite colour is blue")),
        )
        assertNotNull(colour)
        assertTrue(colour!!.contains("favourite colour is blue"))

        val running = memoryContext(
            "plan my run",
            listOf(savedNote("I like running in the morning")),
        )
        assertNotNull(running)
        assertTrue(running!!.contains("running in the morning"))
    }

    @Test fun recallMatchesSynonymsStemmingCannotCatch() {
        val recalled = memoryContext(
            "recommend a movie for tonight",
            listOf(savedNote("My favourite film is Dune")),
        )
        assertNotNull(recalled)
        assertTrue(recalled!!.contains("favourite film is Dune"))
    }

    @Test fun recallFindsStatedPreferenceSavedUnderAnOrdinaryKey() {
        // Saved via an explicit key or the Notes tab, not as "preference-...":
        // the value states the preference, so it is standing and surfaces
        // even when the question shares no word with the note.
        val recalled = memoryContext(
            "make the screen brighter",
            listOf(fact("display", "I prefer dark mode")),
        )
        assertNotNull(recalled)
        assertTrue(recalled!!.contains("I prefer dark mode"))
    }

    @Test fun recallStillReturnsNullWhenNothingMatches() {
        val facts = listOf(
            fact("shipping", "Use courier north"),
            fact("garden", "Basil"),
        )
        assertNull(memoryContext("Write a poem", facts))
    }

    @Test fun automaticKeyCarriesContentWords() {
        val preferenceKey = automaticKey("I prefer Kotlin")
        assertTrue(preferenceKey.startsWith("preference-kotlin-"))
        val noteKey = automaticKey("My dog's name is Rex")
        assertTrue(noteKey.startsWith("note-dog-name-"))
        assertTrue(memoryKeyPattern.matches(preferenceKey))
        assertTrue(memoryKeyPattern.matches(noteKey))
        assertTrue(preferenceKey.length <= 48)
        assertTrue(noteKey.length <= 48)
    }

    @Test fun defaultSoulAppliesWhenNoCustomSoulIsSaved() {
        assertEquals(DEFAULT_SOUL_MARKDOWN, soulForAgentText(""))
        assertEquals(DEFAULT_SOUL_MARKDOWN, soulForAgentText(null))
        assertEquals(DEFAULT_SOUL_MARKDOWN, soulForAgentText("   "))
        assertTrue(DEFAULT_SOUL_MARKDOWN.contains("plain-spoken"))
    }

    @Test fun customSoulOverridesTheDefault() {
        val custom = "Speak like a pirate."
        assertEquals(custom, soulForAgentText(custom))
        assertFalse(soulForAgentText(custom).contains(DEFAULT_SOUL_MARKDOWN))
    }

    @Test fun credentialLikeCustomSoulFallsBackToTheDefault() {
        // The looksSensitive guard still applies to custom souls.
        assertEquals(DEFAULT_SOUL_MARKDOWN, soulForAgentText("My password is hunter2"))
    }
}
