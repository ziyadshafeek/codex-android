package dev.codex.android.memory

import android.content.Context
import dev.codex.android.data.Fact
import dev.codex.android.data.KeyStore
import dev.codex.android.data.MemoryStore
import dev.codex.android.security.SecretScrubber
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Durable notes are the agent's memory of the person, always on — like
 * Muse's. The person writes directly (`/remember ...`, the Notes tab),
 * asks the agent in chat to save something (the `memory_save` tool on an
 * explicit ask), or simply shares a durable fact, preference, decision or
 * commitment in conversation — the agent saves those itself, without
 * being asked. It never saves credentials, passing remarks, or anything
 * the user asked it to forget. [remember] still refuses anything
 * credential-looking, and any note can be removed from the Notes tab or
 * with `/forget`.
 */
class PersonalMemory(context: Context) {
    private val store = MemoryStore(context)
    private val settings = KeyStore(context)

    fun isRecallEnabled(): Boolean = settings.isMemoryRecallEnabled()
    fun setRecallEnabled(enabled: Boolean) = settings.setMemoryRecallEnabled(enabled)

    /** The user's name for the system prompt, or null when never set. */
    fun userName(): String? = settings.userName()

    /** Stores the user's name; blank clears it. */
    fun setUserName(name: String) = settings.setUserName(name)
    suspend fun list(): List<Fact> = store.allFacts().filter { it.source == "user" }
    suspend fun forget(key: String): Boolean = store.deleteFact(key.trim().lowercase(Locale.ROOT))
    suspend fun clear(): Boolean = store.clearFacts()

    suspend fun remember(key: String?, note: String): String {
        val text = note.trim()
        val name = key?.trim()?.lowercase(Locale.ROOT) ?: automaticKey(text)
        if (!memoryKeyPattern.matches(name) || text.isBlank() || text.length > MAX_NOTE_LENGTH) {
            return "Use a name of up to 48 letters, numbers, dots, underscores or hyphens, and a note of 1–$MAX_NOTE_LENGTH characters."
        }
        if (looksSensitive(text) || looksSensitive(key.orEmpty())) {
            return "That looks like a credential. It was not stored. Keep provider keys in Settings."
        }
        return if (store.saveFact(name, text, source = "user")) "Remembered: $name."
        else "Could not save the note. Memory holds up to ${MemoryStore.MAX_FACTS} notes."
    }

    internal suspend fun saveMarkdown(expected: Map<String, String>, markdown: String): String? {
        val notes = try {
            MemoryMarkdown.parse(markdown)
        } catch (e: IllegalArgumentException) {
            return e.message ?: "Check the Markdown and try again."
        } catch (e: IllegalStateException) {
            return e.message ?: "Check the Markdown and try again."
        }
        if (notes.isEmpty() && expected.isNotEmpty()) return "To delete every note, use Clear all in the Notes tab."
        return when (store.replaceUserFacts(expected, notes)) {
            MemoryStore.ReplaceResult.SAVED -> null
            MemoryStore.ReplaceResult.CHANGED -> "Notes changed elsewhere. Reload the saved notes and reapply your edits."
            MemoryStore.ReplaceResult.FAILED -> "Could not save the Markdown. Saved notes were not changed."
        }
    }

    internal fun loadSoul(): String = settings.soulMarkdown()

    internal fun hasCustomSoul(): Boolean = loadSoul().isNotBlank()

    /**
     * The soul the agent actually speaks with. A saved custom soul wins;
     * when the user never wrote one, the built-in default below applies so
     * the agent still has a voice instead of falling back to a bare
     * generic prompt. The default lives only in code: it is never written
     * into the user's stored soul, so the user can still tell default
     * from custom apart and override it cleanly.
     */
    internal fun soulForAgent(): String = soulForAgentText(loadSoul())

    internal suspend fun saveSoul(markdown: String): String? = withContext(Dispatchers.IO) {
        val text = markdown.trim()
        when {
            text.length > MAX_SOUL_LENGTH -> "Use at most $MAX_SOUL_LENGTH characters."
            looksSensitive(text) -> "This looks like a credential. Nothing was saved."
            !settings.saveSoulMarkdown(text) -> "Could not save Soul.md."
            else -> null
        }
    }

    suspend fun recall(goal: String): String? {
        if (!isRecallEnabled()) return null
        return memoryContext(goal, list())
    }

    internal suspend fun search(query: String): String? {
        if (!isRecallEnabled()) return null
        return memoryContext(query, list(), includeStanding = false)
    }
}

internal const val MAX_NOTE_LENGTH = 400

/**
 * The voice the agent speaks with when the user never wrote a custom soul.
 * Plain, warm and direct, in our own words. It contains no personal data
 * and is never written into the user's stored soul (see [soulForAgent]).
 */
internal const val DEFAULT_SOUL_MARKDOWN =
    "Be warm, direct and plain-spoken. Get to the point in simple words, and say what you are " +
        "going to do before you do it. Prefer short replies with a clear next step over long " +
        "explanations. If you do not know something, say so plainly instead of guessing. Use " +
        "what the user has already told you without making a fuss about it, and never invent " +
        "details about them."

/**
 * Picks the soul for one agent turn from the stored custom soul: the custom
 * soul wins when it is non-blank and not credential-like, otherwise the
 * built-in [DEFAULT_SOUL_MARKDOWN] applies. Pure, so it is unit-testable
 * without Android storage; [PersonalMemory.soulForAgent] is the thin
 * wrapper that reads the stored value. Recall stays user-controlled: this
 * only chooses a voice, never a note.
 */
internal fun soulForAgentText(storedSoul: String?): String {
    val custom = storedSoul.orEmpty().take(MAX_SOUL_LENGTH)
        .takeIf { it.isNotBlank() && !looksSensitive(it) }
    return custom ?: DEFAULT_SOUL_MARKDOWN
}

private val tokenPattern = Regex("[\\p{L}\\p{N}]{2,}")
// NOTE: "prefer" / "preference" / "preferences" are deliberately NOT stop
// words: they are the topic words of a preference question ("which language
// do you prefer?"), and dropping them was one reason preference notes never
// matched the questions asked about them.
private val stopWords = setOf("and", "can", "could", "do", "does", "for", "have", "how", "in", "is", "it", "me", "my", "of", "on", "please", "the", "to", "use", "what", "with", "would", "you", "about", "that", "this", "will", "which", "should", "know", "remember", "note")
private val credentialLabel = Regex("(?i)\\b(?:password|passphrase|api[_ -]?key|access[_ -]?token|private[_ -]?key|recovery phrase|seed phrase|secret key)\\b")
/**
 * Light stemming + spelling normalisation, applied identically to the query
 * and to saved notes so a true match can never be split: "running" and
 * "run" become "run", "printers" becomes "printer", "colour" becomes
 * "color" and "favourite" becomes "favorite". This is a recall heuristic,
 * not identity: two distinct words may rarely share a stem, which only
 * affects ranking among at most four surfaced notes.
 */
private fun stemToken(token: String): String {
    var stem = token.replace("our", "or")
    stem = when {
        stem.length > 4 && stem.endsWith("ies") -> stem.dropLast(3) + "y"
        stem.length > 5 && stem.endsWith("ing") -> stem.dropLast(3).deduped()
        stem.length > 4 && stem.endsWith("ed") -> stem.dropLast(2).deduped()
        stem.length > 3 && stem.endsWith("es") -> stem.dropLast(2)
        stem.length > 3 && stem.endsWith("s") && !stem.endsWith("ss") -> stem.dropLast(1)
        else -> stem
    }
    return stem
}

private fun String.deduped(): String =
    if (length >= 2 && last() == this[length - 2]) dropLast(1) else this

private fun words(text: String): Set<String> = tokenPattern.findAll(text.take(4000).lowercase(Locale.ROOT))
    .map { it.value }.filterNot { it in stopWords }.map(::stemToken).toSet()

/** A few everyday paraphrases stemming cannot catch (film/movie, tv/television). */
private val synonyms = mapOf(
    "film" to setOf("movie"),
    "movie" to setOf("film"),
    "tv" to setOf("television"),
    "television" to setOf("tv"),
    "phone" to setOf("mobile"),
    "mobile" to setOf("phone"),
)

private fun expandQuery(text: String): Set<String> {
    val base = words(text)
    return base + base.flatMap { synonyms[it].orEmpty() }
}

/**
 * Two stems match when they are equal, when the shorter is a prefix of the
 * longer (print/printer, cod/code), or when the shorter is contained in the
 * longer and is at least 4 letters (avoids "art" matching "start"). A
 * 3-letter prefix is the price of catching verb forms such as code/coding;
 * the cost of a rare false hit is one extra background note for the model,
 * which the prompt tells it to treat as data, not instruction.
 */
private fun tokenMatches(queryStem: String, factStem: String): Boolean {
    if (queryStem == factStem) return true
    val (short, long) = if (queryStem.length <= factStem.length) queryStem to factStem else factStem to queryStem
    if (short.length < 3) return false
    return long.startsWith(short) || (short.length >= 4 && long.contains(short))
}

private val standingValue = Regex("(?i)^\\s*i (?:prefer|like|always)\\b")

/**
 * A standing preference is not only a "preference-" key: a note saved with
 * an explicit key or through the Notes tab can state a preference too
 * ("I prefer dark mode" under ## display). Judging by the value as well as
 * the key is what lets those notes surface without an exact word match.
 */
private fun isStandingPreference(fact: Fact): Boolean =
    fact.key.startsWith("preference-") || fact.key.startsWith("preference.") ||
        standingValue.containsMatchIn(fact.value)
private val broadRecall = Regex("(?i)\\b(?:what (?:do you (?:know|remember)|have you saved) about me|what are my (?:saved )?preferences|what (?:have you|did you) remember)\\b")
internal fun looksSensitive(text: String): Boolean = SecretScrubber.scrub(text) != text || credentialLabel.containsMatchIn(text)

/** First content words of a note, in order, for a readable derived key. */
private val keyNoise = setOf("prefer", "like", "always", "use")
private fun contentKeywords(note: String): List<String> =
    tokenPattern.findAll(note.lowercase(Locale.ROOT)).map { it.value }
        .filterNot { it in stopWords || it in keyNoise }
        .map { it.filter { ch -> ch.isLetterOrDigit() } }
        .filter { it.length in 2..8 }.distinct().toList()

/**
 * Derives the storage key for a note saved without an explicit name.
 * The old key was a bare "note-<random hash>", so the key contributed
 * nothing to recall scoring and /memory lists were unreadable. The key now
 * carries the note's first content words ("I prefer Kotlin" becomes
 * "preference-kotlin-<hash>"), which lets the key's x3 recall weight work
 * and makes the saved name meaningful. The hash suffix keeps keys unique.
 * Exposed internally so the recall path is unit-testable without storage.
 */
internal fun automaticKey(note: String): String {
    val normal = note.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    val suffix = MessageDigest.getInstance("SHA-256").digest(normal.toByteArray()).take(6)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val prefix = if (Regex("(?i)^i (?:prefer|like|always use)\\b").containsMatchIn(note)) "preference" else "note"
    val keywords = contentKeywords(note).take(2)
    return (listOf(prefix) + keywords + suffix).joinToString("-")
}

/**
 * Relevant first; a small budget for explicit standing preferences. Never inject raw transcripts.
 *
 * Root-cause note: this used to match on exact token-set intersection only,
 * so any note whose wording shared no token with the request — "where do I
 * print?" against "The office printer is on the third floor", "recommend a
 * movie" against "My favourite film is Dune" — scored 0 and silently
 * returned null, even though recall itself was enabled and wired up. It now
 * matches on stemmed tokens with prefix/substring tolerance plus a small
 * synonym expansion, and treats a stated "I prefer / I like / I always ..."
 * note as standing whatever key it was saved under.
 */
internal fun memoryContext(goal: String, facts: List<Fact>, includeStanding: Boolean = true): String? {
    val query = expandQuery(goal)
    val asksAboutMemory = broadRecall.containsMatchIn(goal)
    val matches = facts.asSequence()
        .filter { it.source == "user" && !looksSensitive(it.key) && !looksSensitive(it.value) }
        .map { fact ->
            val keyStems = words(fact.key)
            val valueStems = words(fact.value)
            val keyHits = query.count { q -> keyStems.any { tokenMatches(q, it) } }
            val valueHits = query.count { q -> valueStems.any { tokenMatches(q, it) } }
            fact to (keyHits * 3 + valueHits)
        }
        .filter { (fact, score) -> score > 0 || asksAboutMemory ||
            (includeStanding && isStandingPreference(fact)) }
        .sortedWith(compareByDescending<Pair<Fact, Int>> { it.second }.thenByDescending { it.first.updatedAtMillis })
        .take(4).map { it.first }.toList()
    if (matches.isEmpty()) return null
    return buildJsonObject {
        matches.forEach { put(it.key.take(48), JsonPrimitive(it.value.take(MAX_NOTE_LENGTH))) }
    }.toString()
}

internal sealed interface MemoryCommand {
    data class Remember(val key: String?, val note: String) : MemoryCommand
    data class ListNotes(val filter: String) : MemoryCommand
    data class Forget(val key: String) : MemoryCommand
    data object Help : MemoryCommand
}

/** Only entire, directly entered composer commands can mutate persistent memory. */
internal fun parseMemoryCommand(input: String): MemoryCommand? {
    val text = input.trim().replaceFirst(Regex("(?i)^please\\s+(?=remember that )"), "")
    if (text.length < 8) return if (text.equals("/memory", true)) MemoryCommand.ListNotes("") else null
    val command = text.substringBefore(' ').lowercase(Locale.ROOT)
    val argument = text.substringAfter(' ', "").trim()
    return when {
        command == "/remember" -> {
            val proposedKey = argument.substringBefore('=').trim()
            if ('=' in argument && memoryKeyPattern.matches(proposedKey.lowercase(Locale.ROOT))) {
                MemoryCommand.Remember(proposedKey, argument.substringAfter('=').trim())
            } else if (argument.isBlank()) MemoryCommand.Help else MemoryCommand.Remember(null, argument)
        }
        command == "/forget" -> MemoryCommand.Forget(argument)
        command == "/memory" -> MemoryCommand.ListNotes(argument)
        text.startsWith("remember that ", ignoreCase = true) -> MemoryCommand.Remember(null, text.drop(14).trim())
        else -> null
    }
}
