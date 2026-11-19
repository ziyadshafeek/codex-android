package dev.codex.android.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** JVM unit tests for [QuestionTool] and [PendingQuestions]. */
class QuestionToolTest {

    @Before
    fun setUp() {
        PendingQuestions.clear()
    }

    @After
    fun tearDown() {
        PendingQuestions.clear()
    }

    @Test
    fun execute_queuesQuestionAndReturnsQueuedId() = runBlocking {
        assertTrue(QuestionTool.sensitive)
        assertEquals("question", QuestionTool.name)

        val args = buildJsonObject {
            put("question", "Which database should we use?")
            putJsonArray("options") {
                add(JsonPrimitive("SQLite"))
                add(JsonPrimitive("JSON file"))
            }
        }
        val result = QuestionTool.execute(args)
        assertTrue(result.ok)
        assertTrue(result.output.startsWith("QUESTION_QUEUED:"))

        val id = result.output.removePrefix("QUESTION_QUEUED:")
        val stored = PendingQuestions.get(id)
        assertNotNull(stored)
        assertEquals("Which database should we use?", stored!!.question)
        assertEquals(listOf("SQLite", "JSON file"), stored.options)
        assertEquals(1, PendingQuestions.listPending().size)

        assertTrue(PendingQuestions.answer(id, "SQLite"))
        assertEquals("SQLite", PendingQuestions.get(id)?.answer)
        assertTrue(PendingQuestions.listPending().isEmpty())
        // Answering a second time is rejected.
        assertFalse(PendingQuestions.answer(id, "JSON file"))
    }

    @Test
    fun execute_rejectsMissingQuestionOrEmptyOptions() = runBlocking {
        val blankQuestion = buildJsonObject {
            put("question", "   ")
            putJsonArray("options") { add(JsonPrimitive("Yes")) }
        }
        assertFalse(QuestionTool.execute(blankQuestion).ok)

        val emptyOptions = buildJsonObject {
            put("question", "Proceed?")
            putJsonArray("options") {}
        }
        assertFalse(QuestionTool.execute(emptyOptions).ok)
        assertTrue(PendingQuestions.listPending().isEmpty())
    }

    @Test
    fun execute_rejectsNonStringOptionsAndFiltersBlankEntries() = runBlocking {
        // A non-string option entry is rejected outright.
        val nonString = buildJsonObject {
            put("question", "Pick one")
            putJsonArray("options") { add(JsonPrimitive(42)) }
        }
        assertFalse(QuestionTool.execute(nonString).ok)
        assertTrue(PendingQuestions.listPending().isEmpty())

        // Blank entries are filtered; at least one real option remains.
        val withBlanks = buildJsonObject {
            put("question", "Pick one")
            putJsonArray("options") {
                add(JsonPrimitive("Yes"))
                add(JsonPrimitive("   "))
                add(JsonPrimitive(""))
            }
        }
        val okResult = QuestionTool.execute(withBlanks)
        assertTrue(okResult.ok)
        val id = okResult.output.removePrefix("QUESTION_QUEUED:")
        assertEquals(listOf("Yes"), PendingQuestions.get(id)?.options)

        // All-blank options leave nothing to choose.
        val allBlank = buildJsonObject {
            put("question", "Pick one")
            putJsonArray("options") {
                add(JsonPrimitive("   "))
                add(JsonPrimitive(""))
            }
        }
        assertFalse(QuestionTool.execute(allBlank).ok)
    }

    @Test
    fun answer_rejectsBlankAndUnknownIds() = runBlocking {
        val args = buildJsonObject {
            put("question", "Continue?")
            putJsonArray("options") { add(JsonPrimitive("Yes")) }
        }
        val id = QuestionTool.execute(args).output.removePrefix("QUESTION_QUEUED:")

        assertFalse(PendingQuestions.answer("no-such-id", "Yes"))
        assertFalse(PendingQuestions.answer(id, "   "))
        assertEquals(null, PendingQuestions.get("no-such-id"))
        // The real question is still unanswered after the rejected attempts.
        assertEquals(1, PendingQuestions.listPending().size)
    }

    @Test
    fun pending_fifoOrderAcrossMultipleQuestions() = runBlocking {
        fun enqueue(question: String): String {
            val args = buildJsonObject {
                put("question", question)
                putJsonArray("options") { add(JsonPrimitive("OK")) }
            }
            return QuestionTool.execute(args).output.removePrefix("QUESTION_QUEUED:")
        }

        val first = enqueue("First?")
        val second = enqueue("Second?")
        val third = enqueue("Third?")

        assertEquals(
            listOf(first, second, third),
            PendingQuestions.listPending().map { it.id },
        )
        assertTrue(PendingQuestions.answer(second, "OK"))
        assertEquals(
            listOf(first, third),
            PendingQuestions.listPending().map { it.id },
        )
    }

    @Test
    fun remove_deletesQuestion() = runBlocking {
        val args = buildJsonObject {
            put("question", "Delete me?")
            putJsonArray("options") { add(JsonPrimitive("OK")) }
        }
        val id = QuestionTool.execute(args).output.removePrefix("QUESTION_QUEUED:")

        assertTrue(PendingQuestions.remove(id))
        assertEquals(null, PendingQuestions.get(id))
        assertTrue(PendingQuestions.listPending().isEmpty())
        assertFalse(PendingQuestions.remove(id))
    }
}
