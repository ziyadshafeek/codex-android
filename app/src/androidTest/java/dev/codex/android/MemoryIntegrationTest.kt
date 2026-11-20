package dev.codex.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.codex.android.data.KeyStore
import dev.codex.android.data.MemoryStore
import dev.codex.android.agent.PlanStore
import dev.codex.android.memory.PersonalMemory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun saveRecallPauseAndForgetUsingTheRealComposerAndProviderClient() {
        val context = compose.activity.applicationContext
        val memory = PersonalMemory(context)
        val keyStore = KeyStore(context)
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            try {
                waitForChat()
                send("/remember garden-test = Marigold is the garden test plant")
                waitForText("Remembered: garden-test.")
                assertEquals(0, server.requestCount)
                compose.activityRule.scenario.recreate()
                waitForChat()
                compose.onNodeWithText("Settings").performClick()
                compose.onNodeWithTag("settings_base_url").performTextInput("http://127.0.0.1:${server.port}/v1")
                compose.onNodeWithTag("settings_api_key").performTextInput("test-key-memory")
                compose.onNodeWithTag("settings_save").performScrollTo().performClick()
                compose.onNodeWithTag("settings_memory").performScrollTo().performClick()
                compose.onNodeWithTag("memory_tab_MARKDOWN").performClick()
                compose.onNodeWithTag("memory_markdown").performTextReplacement("# Memory\n\n## garden-test\n**Basil** is the garden test plant.")
                compose.activityRule.scenario.recreate()
                compose.onNodeWithTag("memory_markdown_save").performClick()
                waitForText("Memory.md saved.")
                compose.onNodeWithTag("memory_tab_SOUL").performClick()
                compose.onNodeWithTag("soul_markdown").performTextReplacement("# Style\nUse tiny numbered steps.")
                compose.onNodeWithTag("soul_markdown_save").performClick()
                waitForText("Soul.md saved.")
                compose.onNodeWithText("Done").performClick()
                compose.onNodeWithText("Back").performClick()

                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"search_memory","type":"function","function":{"name":"memory_search","arguments":"{\"query\":\"garden\"}"}}]},"finish_reason":"tool_calls"}]}""",
                ))
                enqueueReply(server, "Garden reply one")
                send("Which bloom did I choose earlier?")
                val first = server.takeRequest(12, TimeUnit.SECONDS) ?: error("No model request")
                val firstBody = first.body.readUtf8()
                assertEquals("/v1/chat/completions", first.path)
                assertTrue(firstBody.contains("memory_search"))
                assertFalse(firstBody.contains("Basil"))
                assertTrue(firstBody.contains("Active user style (Soul.md)"))
                assertTrue(firstBody.contains("Use tiny numbered steps."))
                val afterSearch = server.takeRequest(12, TimeUnit.SECONDS) ?: error("No request after tool search")
                assertTrue(afterSearch.body.readUtf8().contains("**Basil** is the garden test plant"))
                waitForText("Garden reply one")
                waitForReady()
                compose.onNodeWithTag("chat_new").performClick()
                compose.onNodeWithText("Settings").performClick()
                compose.onNodeWithTag("settings_memory").performScrollTo().performClick()
                compose.onNodeWithTag("memory_note_garden-test").assertIsDisplayed()
                compose.onNodeWithTag("memory_recall").performClick()
                compose.onNodeWithText("Done").performClick()
                compose.onNodeWithText("Back").performClick()

                enqueueReply(server, "Garden reply two")
                send("What is the garden test plant now?")
                val second = server.takeRequest(12, TimeUnit.SECONDS) ?: error("No model request")
                val secondBody = second.body.readUtf8()
                assertFalse(secondBody.contains("Basil"))
                assertFalse(secondBody.contains("memory_search"))
                assertTrue(secondBody.contains("Use tiny numbered steps."))
                waitForText("Garden reply two")
                waitForReady()
                send("/forget garden-test")
                waitForText("Forgot the saved note.")
                assertEquals(3, server.requestCount)
                assertNull(runBlocking { memory.list().find { it.key == "garden-test" } })
            } finally {
                runBlocking {
                    memory.forget("garden-test")
                    memory.setRecallEnabled(true)
                    memory.saveSoul("")
                    keyStore.clearApiKey()
                    keyStore.setBaseUrl("")
                    keyStore.setModel("")
                    MemoryStore(context).clearMessages()
                    PlanStore(context).clear()
                }
            }
        }
    }

    private fun waitForChat() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("onboarding_skip").performClick()
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun send(text: String) {
        compose.onNodeWithTag("chat_input").performTextInput(text)
        compose.onNodeWithTag("chat_send").performClick()
    }

    private fun waitForText(value: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(value, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForReady() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Stop").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun enqueueReply(server: MockWebServer, text: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"choices":[{"message":{"role":"assistant","content":"$text"},"finish_reason":"stop"}]}""",
        ))
    }
}
