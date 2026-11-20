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

/** Durable notes are opt-in writes from the person, not from model or tool output. */
class PersonalMemory(context: Context) {
    private val store = MemoryStore(context)
    private val settings = KeyStore(context)

    fun isRecallEnabled(): Boolean = settings.isMemoryRecallEnabled()
    fun setRecallEnabled(enabled: Boolean) = settings.setMemoryRecallEnabled(enabled)
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

    internal suspend fun saveSoul(markdown: String): String? = withContext(Dispatchers.IO) {
        val text = markdown.trim()
        when {
            text.length > MAX_SOUL_LENGTH -> "Use at most $MAX_SOUL_LENGTH characters."
            looksSensitive(text) -> "This looks like a credential. Nothing was saved."
            !settings.saveSoulMarkdown(text) -> "Could not save Soul.md."
            else -> null
        }
    }

    internal fun soulForAgent(): String? = loadSoul().take(MAX_SOUL_LENGTH).takeIf { it.isNotBlank() && !looksSensitive(it) }

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
private val tokenPattern = Regex("[\\p{L}\\p{N}]{2,}")
private val stopWords = setOf("and", "can", "could", "do", "does", "for", "have", "how", "in", "is", "it", "me", "my", "of", "on", "please", "the", "to", "use", "what", "with", "would", "you", "about", "that", "this", "will", "which", "should", "know", "remember", "prefer", "preference", "preferences", "note")
private val credentialLabel = Regex("(?i)\\b(?:password|passphrase|api[_ -]?key|access[_ -]?token|private[_ -]?key|recovery phrase|seed phrase|secret key)\\b")
private fun words(text: String): Set<String> = tokenPattern.findAll(text.take(4000).lowercase(Locale.ROOT))
    .map { it.value }.filterNot { it in stopWords }.flatMap { token ->
        if (token.length > 4 && token.endsWith("s") && !token.endsWith("ss")) sequenceOf(token, token.dropLast(1))
        else sequenceOf(token)
    }.toSet()
private val broadRecall = Regex("(?i)\\b(?:what (?:do you (?:know|remember)|have you saved) about me|what are my (?:saved )?preferences|what (?:have you|did you) remember)\\b")
internal fun looksSensitive(text: String): Boolean = SecretScrubber.scrub(text) != text || credentialLabel.containsMatchIn(text)

private fun automaticKey(note: String): String {
    val normal = note.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    val suffix = MessageDigest.getInstance("SHA-256").digest(normal.toByteArray()).take(6)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val prefix = if (Regex("(?i)^i (?:prefer|like|always use)\\b").containsMatchIn(note)) "preference" else "note"
    return "$prefix-$suffix"
}

/** Relevant first; a small budget for explicit standing preferences. Never inject raw transcripts. */
internal fun memoryContext(goal: String, facts: List<Fact>, includeStanding: Boolean = true): String? {
    val query = words(goal)
    val asksAboutMemory = broadRecall.containsMatchIn(goal)
    val matches = facts.asSequence()
        .filter { it.source == "user" && !looksSensitive(it.key) && !looksSensitive(it.value) }
        .map { fact ->
            val score = (words(fact.key) intersect query).size * 3 + (words(fact.value) intersect query).size
            fact to score
        }
        .filter { (fact, score) -> score > 0 || asksAboutMemory ||
            (includeStanding && (fact.key.startsWith("preference-") || fact.key.startsWith("preference."))) }
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
