package dev.codex.android

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codex.android.data.KeyStore
import dev.codex.android.data.MemoryStore
import dev.codex.android.ui.ChatViewModel
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented regression test for BUG 2 (double-tap race in
 * ChatViewModel.sendGoal).
 *
 * This has to run on the emulator: ChatViewModel needs a real Application,
 * KeyStore and AgentRuntime, none of which exist on the JVM. The run itself
 * points at a dead local port (http://127.0.0.1:1), so the loop fails fast
 * with a connection error and never touches the network — what matters is
 * that startTurn accepts exactly one run.
 *
 * Teardown mirrors ChatSettingsTest: clear stored messages (the run saves
 * the user goal) and reset the KeyStore, so no trace leaks into the next
 * test class in this instrumentation run.
 */
@RunWith(AndroidJUnit4::class)
class ChatViewModelTest {

    private lateinit var app: Application
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        app = context.applicationContext as Application
        val keyStore = KeyStore(context)
        keyStore.setApiKey("test-key")
        keyStore.setBaseUrl("http://127.0.0.1:1")
        viewModel = ChatViewModel(app)
        viewModel.refreshKeyState()
        assertTrue(viewModel.hasKey.value)
    }

    @After
    fun tearDown() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val keyStore = KeyStore(context)
            keyStore.clearApiKey()
            keyStore.setBaseUrl("")
            keyStore.setModel("")
            val memoryStore = MemoryStore(context)
            var emptied = false
            for (attempt in 1..5) {
                memoryStore.clearMessages()
                delay(300)
                if (memoryStore.recentMessages(20).isEmpty()) {
                    emptied = true
                    break
                }
            }
            if (!emptied) fail("tearDown: stored messages never cleared")
        }
    }

    @Test
    fun sendGoal_doubleTapStartsExactlyOneRun() {
        val goal = "double-tap-probe-" + UUID.randomUUID()
        // Two back-to-back sends, before the first launch can run: the
        // second must be refused by the synchronous guard, before any
        // side effect.
        viewModel.sendGoal(goal)
        viewModel.sendGoal(goal)
        // The run must actually start — otherwise this test would pass
        // vacuously with both taps rejected.
        if (!waitUntil(timeoutMs = 10_000) { viewModel.isRunning.value }) {
            fail("run never started")
        }
        if (!waitUntil(timeoutMs = 15_000) { !viewModel.isRunning.value }) {
            fail("run never finished")
        }
        assertEquals(
            1,
            viewModel.messages.value.count { it.role == "user" && it.text == goal },
        )
        assertEquals(1, viewModel.history.value.count { it.text == goal })
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }
}
