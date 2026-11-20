package dev.codex.android.agent

import dev.codex.android.api.ChatChoice
import dev.codex.android.api.ChatMessage
import dev.codex.android.api.ChatResult
import dev.codex.android.api.OpenAiClient
import dev.codex.android.api.ToolDefinition
import dev.codex.android.data.Fact
import dev.codex.android.memory.DEFAULT_SOUL_MARKDOWN
import dev.codex.android.memory.automaticKey
import dev.codex.android.memory.memoryContext
import dev.codex.android.memory.soulForAgentText
import dev.codex.android.security.DefaultApprovalGate
import dev.codex.android.tools.ToolRegistry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end proof that memory and soul reach the model on a real agent
 * turn, built the way ChatViewModel builds it: the same
 * `recalledMemory` / `soulMarkdown` lambdas (real [memoryContext] over
 * notes saved via the real [automaticKey] path, real [soulForAgentText]),
 * the real [AgentLoop] prompt construction, and a canned model that
 * captures the exact messages it was sent. Only the Android storage
 * underneath PersonalMemory (SQLite / SharedPreferences, unrunnable in a
 * JVM unit test) and the network model are substituted — everything
 * between a saved note and the model request is the production code.
 */
class MemorySoulLoopTest {

    private class FakePlanRepository : PlanRepository {
        var latest: Plan? = null
        override suspend fun loadLatest(): Plan? = latest
        override suspend fun save(plan: Plan) {
            latest = plan
        }

        override suspend fun clear() {
            latest = null
        }
    }

    private class CapturingClient(private val reply: String = "Done.") : OpenAiClient {
        var lastMessages: List<ChatMessage> = emptyList()
        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>,
            model: String,
            onPartialText: ((String) -> Unit)?,
        ): ChatResult {
            lastMessages = messages
            return ChatResult(
                choices = listOf(
                    ChatChoice(text = reply, toolCalls = emptyList(), finishReason = "stop"),
                ),
            )
        }
    }

    /** A note exactly as the MemoryCommand save path stores it. */
    private fun savedNote(note: String) =
        Fact(0, automaticKey(note), note, "user", 0, 0)

    private fun loopWith(
        client: CapturingClient,
        facts: List<Fact>,
        storedSoul: String,
    ) = AgentLoop(
        client = client,
        tools = ToolRegistry(),
        gate = DefaultApprovalGate(),
        planStore = FakePlanRepository(),
        model = "fake-model",
        recalledMemory = { goal -> memoryContext(goal, facts) },
        soulMarkdown = { soulForAgentText(storedSoul) },
    )

    @Test fun recalledNoteAndDefaultSoulReachTheModel() = runBlocking {
        val client = CapturingClient()
        val facts = listOf(savedNote("The office printer is on the third floor"))
        val loop = loopWith(client, facts, storedSoul = "")
        val channel = Channel<AgentEvent>(Channel.UNLIMITED)

        val answer = loop.run("Where do I print this?", channel)
        channel.close()

        assertEquals("Done.", answer)
        val system = client.lastMessages.first { it.role == "system" }.content.orEmpty()
        assertTrue(system.contains(DEFAULT_SOUL_MARKDOWN))
        assertTrue(system.contains("plain-spoken"))
        assertTrue(
            "saved note must be sent to the model",
            client.lastMessages.any {
                it.role == "user" && it.content.orEmpty().contains("office printer is on the third floor")
            },
        )
    }

    @Test fun defaultSoulStillAppliesWithNoNotesSaved() = runBlocking {
        val client = CapturingClient()
        val loop = loopWith(client, facts = emptyList(), storedSoul = "")
        val channel = Channel<AgentEvent>(Channel.UNLIMITED)

        loop.run("Hello", channel)
        channel.close()

        val system = client.lastMessages.first { it.role == "system" }.content.orEmpty()
        assertTrue(system.contains(DEFAULT_SOUL_MARKDOWN))
        assertFalse(
            "no note text may be invented when nothing is saved",
            client.lastMessages.any { it.content.orEmpty().contains("Saved notes") },
        )
    }

    @Test fun customSoulOverridesTheDefaultInTheSystemPrompt() = runBlocking {
        val client = CapturingClient()
        val loop = loopWith(client, facts = emptyList(), storedSoul = "Speak like a pirate.")
        val channel = Channel<AgentEvent>(Channel.UNLIMITED)

        loop.run("Hello", channel)
        channel.close()

        val system = client.lastMessages.first { it.role == "system" }.content.orEmpty()
        assertTrue(system.contains("Speak like a pirate."))
        assertFalse(system.contains(DEFAULT_SOUL_MARKDOWN))
    }
}
