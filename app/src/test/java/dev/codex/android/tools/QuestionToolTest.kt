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
}
