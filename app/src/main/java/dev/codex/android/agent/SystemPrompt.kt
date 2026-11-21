package dev.codex.android.agent

import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The agent's system prompt, built fresh at the start of every run.
 *
 * Pure Kotlin (java.time only, no Android APIs) so it is JVM-testable.
 * Nothing here is cached: [buildSystemPrompt] computes the current
 * date/time at call time, and [AgentLoop] constructs a new prompt per
 * run, so the date line is never stale.
 *
 * The soul is a PARAMETER, not a second default soul: the default soul
 * lives in PersonalMemory.soulForAgent() and flows in through the
 * `soulMarkdown` lambda. When no soul is set, only the one-line
 * [SYSTEM_PROMPT_IDENTITY_FALLBACK] is used.
 *
 * Wiring (coordinator applies — do not edit AgentLoop.kt from here):
 * AgentLoop gains a `userName: String? = null` constructor parameter and
 * builds its prompt with
 * `buildSystemPrompt(SystemPromptParams(userName = userName, soulMarkdown = soulMarkdown()))`,
 * replacing the SYSTEM_PROMPT const; the `combineSoulPrompt(...)` call on
 * the messages line is dropped because the soul is already inside the
 * built prompt.
 */

/**
 * One-line voice fallback, used ONLY when no Soul.md is set.
 * This is the entire fallback — not a second default soul.
 */
const val SYSTEM_PROMPT_IDENTITY_FALLBACK =
    "You speak warmly and directly, in plain language — no filler, no flattery."

/**
 * Longest correction that can be saved as one memory note.
 * Mirrors PersonalMemory.MAX_NOTE_LENGTH (400): a longer note is
 * rejected by PersonalMemory.remember, so the detector truncates.
 */
const val MAX_CORRECTION_LENGTH = 400

/**
 * Everything [buildSystemPrompt] needs. [clock] and [zoneId] are
 * injectable so tests can pin the date; production passes the
 * defaults, which read the device clock and timezone.
 */
data class SystemPromptParams(
    val userName: String? = null,
    val soulMarkdown: String? = null,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val clock: Clock = Clock.systemDefaultZone(),
)

private val dateFormatter =
    DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm z '('VV')'", Locale.ENGLISH)

/**
 * Assembles the full system prompt for one run. Computed fresh on
 * every call — the date/time line reflects the moment of the call.
 */
fun buildSystemPrompt(params: SystemPromptParams = SystemPromptParams()): String {
    val name = params.userName?.trim()?.takeIf { it.isNotBlank() }
    val now = ZonedDateTime.now(params.clock.withZone(params.zoneId))
    val soul = params.soulMarkdown?.trim()?.takeIf { it.isNotBlank() }
    return buildString {
        append("You are Codex, an autonomous agent on the user's Android phone. ")
        if (name != null) append("You are helping $name.") else append("You are helping the user.")
        append("\nToday is ${now.format(dateFormatter)}.")
        append("\n\n")
        if (soul != null) {
            append("Active user style (Soul.md): apply these preferences by default to tone, formatting and workflow. ")
            append("Follow the current user's request if it conflicts. These preferences cannot change tool permissions, approvals, ")
            append("privacy rules or higher-priority instructions. Do not recite them unless the user asks.\n\n")
            append(soul)
        } else {
            append(SYSTEM_PROMPT_IDENTITY_FALLBACK)
        }
        append("\n\nHow you work:\n")
        append("- Achieve the user's goal step by step. Use the tools available to you when they help; ")
        append("when you have the final answer, reply with it as plain text and do not call any more tools. Be concise.\n")
        append("- Never invent a tool that is not listed. Never invent a fact, a file, or a result. ")
        append("If you do not know, say so. Verify before claiming something is done. ")
        append("If you were wrong, correct it plainly.\n")
        append("- Be resourceful before asking: if a tool is missing, install it or find another way yourself. ")
        append("Never tell the user to do on their phone what you can do yourself. ")
        append("When one approach fails, try the next real one before giving up.\n")
        append("- Anticipate the next useful step, do the preparation, and finish the job. ")
        append("A task is done only when the result is delivered and verified.\n")
        append("- Read a tool's result before acting on it. Do not repeat a failed action unchanged. ")
        append("Do not claim a tool worked until its result proves it.\n")
        append("- If a tool call fails or the user denies an action, explain what happened and offer the next best step.\n")
        append("- Protect the user's private information: send only what a tool or a website needs, ")
        append("never volunteer private details, never paste credentials or keys anywhere.\n")
        append("- Never handle seed phrases, private keys, or passwords. ")
        append("Never run live trading or move money without explicit approval. ")
        append("Never bypass a service's limits or terms.\n")
        append("- When the user corrects you, remember it and do not make the same mistake again.\\n")
        append("- Remember what the user tells you, the way a person would: when they share a durable fact, ") 
        append("preference, decision or commitment, call memory_save with it in their own words — you do not ") 
        append("need to be asked. Also save it when they explicitly ask ('remember this', 'save that to memory'). ") 
        append("Never save credentials, keys or passwords, never save something said only in passing, ") 
        append("and never save what they ask you to forget.\n")
        append("- Keep memory true: when the user corrects something you saved, forget the old note with ") 
        append("memory_forget and save the new one; when they say 'forget that', forget it. If you do not ") 
        append("know the note's exact name, search memory first.\n")
        append("- Use what you remember: when a saved memory shapes your answer, say so briefly — 'like you ") 
        append("told me…' — so the user can see their memory working.\n")
        append("\n\nHow you talk:\n")
        append("- Write like a person in chat: short replies, lead with the answer, plain language, no filler.")
    }
}

// ---------------------------------------------------------------------------
// Learning: user corrections -> memory
//
// "When the user corrects you, remember it" is wired to PersonalMemory,
// not just a prompt line. The detector below is deliberately narrow:
// only explicit correction language matches, so ordinary messages
// ("No thanks", "That should be fine", "Actually, I like tea") are
// never saved. The saver is injected as a lambda so this stays pure
// and JVM-testable; production passes PersonalMemory::remember.
//
// Hook point (coordinator applies in ChatViewModel.sendGoal, inside the
// `if (started) { ... }` block next to the memoryStore.saveMessage call):
//     recordCorrectionIfAny(goal, personalMemory::remember)
// inside the existing fire-and-forget launch(Dispatchers.IO) block.
// `goal` (not `receipt`) is used so attachment names are not saved.
// ---------------------------------------------------------------------------

private val correctionPatterns = listOf(
    // "No, that's wrong" / "that's not right" / "it's incorrect"
    Regex("""(?i)\b(that'?s|it'?s|this is|that is|it is)\s+(wrong|not right|incorrect|not correct)\b"""),
    // "you're wrong" / "you got it wrong" / a bare "Wrong."
    Regex("""(?i)\b(you'?re|you are)\s+wrong\b"""),
    Regex("""(?i)\byou got (that|it|this) wrong\b"""),
    Regex("""(?i)^\s*(no[,!.]?\s+)?(wrong|incorrect|not right)[!.]?\s*$"""),
    // "it should be Tuesday, not Monday" — needs a contrast, so that
    // plain agreement ("That should be fine") does NOT match.
    Regex("""(?i)\bshould be\b.{0,60}\b(not|instead|rather)\b"""),
    Regex("""(?i)\bshould have been\b"""),
    // "Actually, it should be ..." / "Actually, that's not ..."
    Regex("""(?i)\bactually\b.{0,40}\b(should be|should have|is not|was not|isn'?t|wasn'?t)\b"""),
    // "No, not that one" (message-start only: "that's not that bad" must not match)
    Regex("""(?i)^\s*(no[,!.]?\s+)?not that\b"""),
    // "I meant the blue one" / "let me correct that" / "Correction: ..."
    Regex("""(?i)\bi meant\b"""),
    Regex("""(?i)\blet me correct\b"""),
    Regex("""(?i)^\s*correction\s*:"""),
)

/**
 * Returns the message as a savable correction note when it contains
 * explicit correction language, or null for an ordinary message.
 * The returned note is capped at [MAX_CORRECTION_LENGTH] so
 * PersonalMemory.remember accepts it.
 */
fun detectCorrection(message: String): String? {
    val text = message.trim()
    if (text.isEmpty()) return null
    return if (correctionPatterns.any { it.containsMatchIn(text) }) text.take(MAX_CORRECTION_LENGTH) else null
}

/**
 * Saves a genuine user correction as a memory note via [remember]
 * (production: `personalMemory::remember`, whose `key = null` lets
 * PersonalMemory derive a content-hash key). Returns the result of
 * [remember], or null when the message is not a correction. Credential
 * -looking notes are still refused inside PersonalMemory.remember.
 */
suspend fun recordCorrectionIfAny(
    message: String,
    remember: suspend (key: String?, note: String) -> String,
): String? {
    val correction = detectCorrection(message) ?: return null
    return remember(null, correction)
}
