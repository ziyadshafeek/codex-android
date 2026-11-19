package dev.codex.android.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/** One interactive question queued for the mobile decision-card UI. */
data class PendingQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val answer: String? = null,
)

/**
 * Process-local in-memory queue for questions raised by [QuestionTool].
 * The tool cannot block on UI directly: it records a [PendingQuestion] here
 * and returns `QUESTION_QUEUED:<id>` so the UI can render option chips and
 * call [answer] when the user responds.
 */
object PendingQuestions {
    private val lock = Any()
    private val items = LinkedHashMap<String, PendingQuestion>()
    private val _pending = MutableStateFlow<List<PendingQuestion>>(emptyList())

    /** Currently unanswered questions, in FIFO order. */
    val pending: StateFlow<List<PendingQuestion>> = _pending.asStateFlow()

    /** Enqueues a new question and returns its created entry. */
    fun enqueue(question: String, options: List<String>): PendingQuestion {
        val entry = PendingQuestion(
            id = "q_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            question = question,
            options = options.toList(),
        )
        synchronized(lock) {
            items[entry.id] = entry
            publishPendingLocked()
        }
        return entry
    }

    /** Returns the question with [id] (answered or unanswered), or null. */
    fun get(id: String): PendingQuestion? = synchronized(lock) { items[id] }

    /** Returns all currently unanswered questions in FIFO order. */
    fun listPending(): List<PendingQuestion> = synchronized(lock) {
        items.values.filter { it.answer == null }
    }

    /** Records the user's [answer] for [id]. Returns false if unknown or already answered. */
    fun answer(id: String, answer: String): Boolean {
        val trimmed = answer.trim()
        if (trimmed.isEmpty()) return false
        synchronized(lock) {
            val current = items[id] ?: return false
            if (current.answer != null) return false
            items[id] = current.copy(answer = trimmed)
            publishPendingLocked()
            return true
        }
    }

    /** Removes [id] from the store. Returns true if it existed. */
    fun remove(id: String): Boolean = synchronized(lock) {
        val removed = items.remove(id) != null
        if (removed) publishPendingLocked()
        removed
    }

    /** Clears all queued and answered questions. */
    fun clear() {
        synchronized(lock) {
            items.clear()
            publishPendingLocked()
        }
    }

    private fun publishPendingLocked() {
        _pending.value = items.values.filter { it.answer == null }
    }
}

/**
 * Interactive question tool (OpenCode `question` alignment) for mobile
 * decision cards. Queues a question with tappable options into
 * [PendingQuestions] and returns `"QUESTION_QUEUED:<id>"`.
 */
object QuestionTool : Tool {
    override val name: String = "question"
    override val description: String =
        "Asks the user a concise question with tappable option choices (mobile decision card) " +
            "and queues it for the UI to answer."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("question") {
                put("type", "string")
                put("description", "Clear, concise question for the user")
            }
            putJsonObject("options") {
                put("type", "array")
                put("description", "List of selectable answer options")
                putJsonObject("items") {
                    put("type", "string")
                }
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("question"))
            add(JsonPrimitive("options"))
        }
    }
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val question = args["question"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: question")

        val optionsEl = args["options"] as? JsonArray
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: options")

        val options = mutableListOf<String>()
        for (item in optionsEl) {
            val primitive = item as? JsonPrimitive
            if (primitive == null || !primitive.isString) {
                return ToolResult(ok = false, output = "", error = "All entries in options must be strings")
            }
            val value = primitive.content.trim()
            if (value.isNotEmpty()) options += value
        }
        if (options.isEmpty()) {
            return ToolResult(ok = false, output = "", error = "options must contain at least one non-empty choice")
        }

        val queued = PendingQuestions.enqueue(question, options)
        return ToolResult(ok = true, output = "QUESTION_QUEUED:${queued.id}")
    }
}
