package dev.codex.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.codex.android.data.KeyStore
import dev.codex.android.data.MemoryStore
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatStreamingIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun thePhoneShowsProviderDeltasBeforeTheFinalReply() {
        val application = compose.activity.applicationContext
        val keys = KeyStore(application)
        MockWebServer().use { server ->
            try {
                server.start(InetAddress.getByName("127.0.0.1"), 0)
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty() ||
                        compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty()
                }
                if (compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty())
                    compose.onNodeWithTag("onboarding_skip").performClick()
                compose.onNodeWithText("Settings").performClick()
                compose.onNodeWithTag("settings_base_url").performTextInput("http://127.0.0.1:${server.port}/v1")
                compose.onNodeWithTag("settings_api_key").performTextInput("test-key-streaming")
                compose.onNodeWithTag("settings_save").performClick()
                compose.onNodeWithText("Back").performClick()
                val first = """data: {"choices":[{"delta":{"content":"Hello live"}}]}""" + "\n\n"
                val second = """data: {"choices":[{"delta":{"content":" stream."},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n"
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody(first + second).throttleBody(first.length.toLong(), 3, TimeUnit.SECONDS))
                compose.onNodeWithTag("chat_input").performTextInput("Show live text")
                compose.onNodeWithTag("chat_send").performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("chat_streamed_reply").fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodesWithText("Hello live").fetchSemanticsNodes().isNotEmpty()
                }
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("chat_stop").fetchSemanticsNodes().isEmpty() }
                assertEquals(1, compose.onAllNodesWithText("Hello live stream.").fetchSemanticsNodes().size)
                assertEquals(0, compose.onAllNodesWithTag("chat_streamed_reply").fetchSemanticsNodes().size)
            } finally {
                runBlocking {
                    keys.clearApiKey()
                    keys.setBaseUrl("")
                    keys.setModel("")
                    MemoryStore(application).clearMessages()
                }
            }
        }
    }
}
