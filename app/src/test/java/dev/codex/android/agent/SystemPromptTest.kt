package dev.codex.android.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * JVM unit tests for the system prompt builder and the correction
 * detector in SystemPrompt.kt. Pure Kotlin — no Android APIs, no
 * coroutines-test, plain runBlocking only.
 */
class SystemPromptTest {

    private val kolkata = ZoneId.of("Asia/Kolkata")

    /** Saturday 21 November 2026, 01:30 IST. */
    private fun clockAt21Nov2026(): Clock =
        Clock.fixed(Instant.parse("2026-11-20T20:00:00Z"), kolkata)

    /** Sunday 22 November 2026, 01:30 IST. */
    private fun clockAt22Nov2026(): Clock =
        Clock.fixed(Instant.parse("2026-11-21T20:00:00Z"), kolkata)

    private fun params(
        userName: String? = "Ziyad",
        soulMarkdown: String? = "Be direct and plain-spoken.",
        clock: Clock = clockAt21Nov2026(),
    ) = SystemPromptParams(userName = userName, soulMarkdown = soulMarkdown, zoneId = kolkata, clock = clock)

    @Test
    fun promptContainsUserNameWhenPassed() {
        val prompt = buildSystemPrompt(params(userName = "Ziyad"))
        assertTrue(prompt.contains("You are helping Ziyad."))
    }

    @Test
    fun promptDegradesGracefullyWithoutAName() {
        val prompt = buildSystemPrompt(params(userName = null))
        assertTrue(prompt.contains("You are helping the user."))
        assertFalse(prompt.contains("Ziyad"))
    }

    @Test
    fun promptContainsCurrentDate() {
        val prompt = buildSystemPrompt(params())
        assertTrue(prompt.contains("Saturday, 21 November 2026, 01:30 IST (Asia/Kolkata)"))
    }

    @Test
    fun promptContainsPassedSoulText() {
        val prompt = buildSystemPrompt(params(soulMarkdown = "Be direct and plain-spoken."))
        assertTrue(prompt.contains("Be direct and plain-spoken."))
    }

    @Test
    fun promptUsesOneLineFallbackWhenNoSoulIsSet() {
        val prompt = buildSystemPrompt(params(soulMarkdown = null))
        assertTrue(prompt.contains(SYSTEM_PROMPT_IDENTITY_FALLBACK))
        assertFalse(prompt.contains("Active user style (Soul.md)"))
    }

    @Test
    fun promptContainsEveryRule() {
        val prompt = buildSystemPrompt(params())
        // Honesty / anti-hallucination
        assertTrue(prompt.contains("Never invent a fact, a file, or a result."))
        assertTrue(prompt.contains("If you do not know, say so."))
        assertTrue(prompt.contains("Verify before claiming something is done."))
        assertTrue(prompt.contains("If you were wrong, correct it plainly."))
        // Resourcefulness (the PDF bug: never hand the job back to the user)
        assertTrue(prompt.contains("if a tool is missing, install it or find another way yourself."))
        assertTrue(prompt.contains("Never tell the user to do on their phone what you can do yourself."))
        assertTrue(prompt.contains("When one approach fails, try the next real one before giving up."))
        // Follow-through
        assertTrue(prompt.contains("Anticipate the next useful step, do the preparation, and finish the job."))
        assertTrue(prompt.contains("A task is done only when the result is delivered and verified."))
        // Discretion
        assertTrue(prompt.contains("Protect the user's private information"))
        assertTrue(prompt.contains("never paste credentials or keys anywhere."))
        // Safety boundaries
        assertTrue(prompt.contains("Never handle seed phrases, private keys, or passwords."))
        assertTrue(prompt.contains("Never run live trading or move money without explicit approval."))
        assertTrue(prompt.contains("Never bypass a service's limits or terms."))
        // Communication style
        assertTrue(prompt.contains("Write like a person in chat: short replies, lead with the answer, plain language, no filler."))
        // Learning
        assertTrue(prompt.contains("When the user corrects you, remember it and do not make the same mistake again."))
        // Always-on memory (v2.3.2, at the user's explicit order): the agent
        // saves durable facts itself, without being asked — like Muse.
        assertTrue(prompt.contains("Remember what the user tells you, the way a person would"))
        assertTrue(prompt.contains("call memory_save with it in their own words"))
        assertTrue(prompt.contains("you do not need to be asked"))
        assertFalse(prompt.contains("Only save what the user explicitly asks to save"))
        assertFalse(prompt.contains("never save anything unprompted"))
        // Manage + refer (v2.3.2, same order): keep memory true, and show it working.
        assertTrue(prompt.contains("Keep memory true"))
        assertTrue(prompt.contains("forget the old note with memory_forget and save the new one"))
        assertTrue(prompt.contains("Use what you remember"))
        assertTrue(prompt.contains("like you told me"))
        // Tool discipline (kept from the original prompt)
        assertTrue(prompt.contains("Never invent a tool that is not listed."))
        assertTrue(prompt.contains("Read a tool's result before acting on it."))
        assertTrue(prompt.contains("Do not repeat a failed action unchanged."))
        assertTrue(prompt.contains("Do not claim a tool worked until its result proves it."))
        assertTrue(prompt.contains("If a tool call fails or the user denies an action, explain what happened and offer the next best step."))
    }

    @Test
    fun promptIsBuiltFreshPerRun() {
        val first = buildSystemPrompt(params(clock = clockAt21Nov2026()))
        val second = buildSystemPrompt(params(clock = clockAt22Nov2026()))
        assertTrue(first.contains("Saturday, 21 November 2026"))
        assertTrue(second.contains("Sunday, 22 November 2026"))
        assertNotEquals(first, second)
        assertFalse(second.contains("21 November 2026"))
    }

    @Test
    fun correctionDetectorSavesGenuineCorrectionAndIgnoresOrdinaryMessages() = runBlocking {
        val saved = mutableListOf<String>()
        val saver: suspend (String?, String) -> String = { _, note -> saved.add(note); "Remembered." }

        val result = recordCorrectionIfAny(
            "No, that's wrong — the meeting is on Tuesday, not Monday.",
            saver,
        )
        assertEquals("Remembered.", result)
        assertEquals(1, saved.size)
        assertTrue(saved.single().contains("Tuesday"))

        // More genuine corrections, each saved.
        assertEquals("Remembered.", recordCorrectionIfAny("Actually, it should be Tuesday, not Monday.", saver))
        assertEquals("Remembered.", recordCorrectionIfAny("I meant the blue one, not the red one.", saver))
        assertEquals("Remembered.", recordCorrectionIfAny("Wrong.", saver))
        assertEquals(4, saved.size)

        // Ordinary messages — none saved.
        assertNull(recordCorrectionIfAny("What's the weather today?", saver))
        assertNull(recordCorrectionIfAny("Can you convert this PDF for me?", saver))
        assertNull(recordCorrectionIfAny("No thanks, that's fine.", saver))
        assertNull(recordCorrectionIfAny("That should be fine.", saver))
        assertNull(recordCorrectionIfAny("Actually, I like tea.", saver))
        assertNull(recordCorrectionIfAny("That's not that bad, keep going.", saver))
        assertNull(recordCorrectionIfAny("", saver))
        assertEquals(4, saved.size)
    }

    @Test
    fun detectCorrectionCapsLongNotesSoMemoryAcceptsThem() {
        val long = "No, that's wrong. " + "x".repeat(1000)
        val note = detectCorrection(long)
        assertTrue(note != null)
        assertTrue(note!!.length <= MAX_CORRECTION_LENGTH)
    }
}
