package dev.codex.android

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codex.android.agent.PlanStore
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.MemoryStore
import dev.codex.android.data.TimelineMessage
import dev.codex.android.data.WorkspaceEntity
import dev.codex.android.ui.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M9 finish: PreviewScreen wired into navigation, Help screen reachable
 * from Settings, Share-to-Codex receiving (ChatViewModel.openThread).
 *
 * The mic permission launcher is NOT tested here: it drives the system
 * permission dialog, which the emulator suite cannot grant or dismiss.
 * VoiceInput itself is covered by the existing voice tests.
 */
@RunWith(AndroidJUnit4::class)
class M9FinishTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private var openedThreadId: String? = null
    private var decoyThreadId: String? = null

    @After
    fun tearDown() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val db = CodexDatabase.getInstance(context)
            // Messages first: threads are FK-referenced by messages.
            MemoryStore(context).clearMessages()
            PlanStore(context).clear()
            openedThreadId?.let { db.deleteThread(it) }
            decoyThreadId?.let { db.deleteThread(it) }
            openedThreadId = null
            decoyThreadId = null
        }
    }

    /** Mirrors SmokeTest: dismiss onboarding if it is showing. */
    private fun reachChat() {
        composeTestRule.waitUntil(timeoutMillis = 30_000) {
            composeTestRule.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithTag("empty_state_title").fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeTestRule.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty()) {
            composeTestRule.onNodeWithTag("onboarding_skip").performClick()
            composeTestRule.waitUntil(timeoutMillis = 15_000) {
                composeTestRule.onAllNodesWithTag("empty_state_title").fetchSemanticsNodes().isNotEmpty() ||
                    composeTestRule.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun previewButtonOpensPreviewScreenAndBackReturnsToChat() {
        reachChat()
        composeTestRule.onNodeWithText("Preview").performClick()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithTag("preview_url_bar").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("preview_url_bar").assertExists()
        composeTestRule.onNodeWithText("Back").performClick()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("chat_input").assertExists()
    }

    @Test
    fun helpOpensFromSettingsAndBackReturnsToSettings() {
        reachChat()
        composeTestRule.onNodeWithText("Settings").performClick()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithTag("settings_save").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("settings_help").performClick()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodes(hasText("Your key")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Your key").assertExists()
        composeTestRule.onNodeWithText("Back").performClick()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithTag("settings_save").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("settings_save").assertExists()
    }

    /**
     * openThread loads exactly the opened thread's messages — no
     * messages from other threads leak in. The decoy thread's message
     * is in the global recent history, so it IS what the init restore
     * would merge: if the restore landed after openThread without the
     * opened-thread guard, the decoy would appear in the chat.
     * openThread is called immediately after ViewModel creation, while
     * the init restore is still seeding skills, so the guard is the
     * thing standing between the restore and the opened view.
     */
    @Test
    fun openThreadLoadsOnlyTheOpenedThreadsMessages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as Application
        runBlocking {
            val db = CodexDatabase.getInstance(context)
            db.upsertWorkspace(
                WorkspaceEntity(
                    id = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID,
                    name = "This Phone",
                    kind = "phone",
                    rootPath = "",
                    createdAt = System.currentTimeMillis(),
                )
            )
            val opened = db.createThread(
                workspaceId = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID,
                title = "Shared thread",
                mode = "AUTO",
            )
            openedThreadId = opened.id
            db.appendMessage(TimelineMessage(threadId = opened.id, role = "user", kind = "text", content = "Shared hello"))
            db.appendMessage(TimelineMessage(threadId = opened.id, role = "assistant", kind = "text", content = "Shared reply"))
            val decoy = db.createThread(
                workspaceId = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID,
                title = "Decoy thread",
                mode = "AUTO",
            )
            decoyThreadId = decoy.id
            db.appendMessage(TimelineMessage(threadId = decoy.id, role = "user", kind = "text", content = "Decoy other thread"))
        }
        val viewModel = ChatViewModel(app)
        viewModel.openThread(openedThreadId!!)
        // Poll the live messages: openThread loads on Dispatchers.IO.
        val deadline = System.currentTimeMillis() + 10_000
        var shown: List<String> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            shown = viewModel.messages.value.map { it.text }
            if (shown == listOf("Shared hello", "Shared reply")) break
            runBlocking { delay(100) }
        }
        assertEquals(
            listOf("Shared hello", "Shared reply"),
            shown,
        )
        assertTrue(viewModel.messages.value.none { it.text == "Decoy other thread" })
    }
}
