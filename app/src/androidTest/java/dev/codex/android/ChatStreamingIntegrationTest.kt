package dev.codex.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onRoot
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.io.ByteArrayOutputStream
import dev.codex.android.data.KeyStore
import dev.codex.android.data.MemoryStore
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatStreamingIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun thePhoneShowsProviderDeltasBeforeTheFinalReply() {
        val application = compose.activity.applicationContext
        val keys = KeyStore(application)
        val firstSent = CountDownLatch(1)
        val releaseFinal = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val provider = Thread {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 20_000
                        val input = socket.getInputStream().buffered()
                        fun readLine(): String {
                            val line = ByteArrayOutputStream()
                            while (true) {
                                val next = input.read()
                                check(next >= 0) { "Unexpected end of request" }
                                if (next == 10) return line.toString("UTF-8").trimEnd('\r')
                                line.write(next)
                            }
                        }
                        check(readLine().contains("/v1/chat/completions"))
                        val headers = generateSequence(::readLine).takeWhile { it.isNotEmpty() }.toList()
                        val length = headers.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                        repeat(length) { check(input.read() >= 0) }
                        val out = socket.getOutputStream()
                        fun send(text: String) { out.write(text.toByteArray()); out.flush() }
                        send("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
                        send("""data: {"choices":[{"delta":{"content":"Hello live"}}]}""" + "\n\n")
                        firstSent.countDown()
                        releaseFinal.await(25, TimeUnit.SECONDS)
                        send("""data: {"choices":[{"delta":{"content":" stream."},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n")
                    }
                }
            }.apply { isDaemon = true; start() }
            try {
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty() ||
                        compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty()
                }
                if (compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty())
                    compose.onNodeWithTag("onboarding_skip").performClick()
                compose.onNodeWithText("Settings").performClick()
                compose.onNodeWithTag("settings_base_url").performTextInput("http://127.0.0.1:${server.localPort}/v1")
                compose.onNodeWithTag("settings_api_key").performTextInput("test-key-streaming")
                compose.onNodeWithTag("settings_save").performClick()
                compose.onNodeWithText("Back").performClick()
                compose.onNodeWithTag("chat_input").performTextInput("Show live text")
                compose.onNodeWithTag("chat_send").performClick()
                assertTrue("No SSE request was received", firstSent.await(15, TimeUnit.SECONDS))
                try {
                    compose.waitUntil(12_000) {
                        compose.onAllNodesWithTag("chat_streamed_reply").fetchSemanticsNodes().isNotEmpty() &&
                            compose.onAllNodesWithText("Hello live").fetchSemanticsNodes().isNotEmpty()
                    }
                } catch (e: Exception) {
                    throw AssertionError(compose.onRoot().printToString().takeLast(2200), e)
                } finally { releaseFinal.countDown() }
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("chat_stop").fetchSemanticsNodes().isEmpty() }
                assertEquals(1, compose.onAllNodesWithText("Hello live stream.").fetchSemanticsNodes().size)
                assertEquals(0, compose.onAllNodesWithTag("chat_streamed_reply").fetchSemanticsNodes().size)
            } finally {
                releaseFinal.countDown()
                server.close()
                provider.join(1000)
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
