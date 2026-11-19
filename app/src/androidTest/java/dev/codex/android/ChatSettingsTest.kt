package dev.codex.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codex.android.data.KeyStore
import dev.codex.android.data.MemoryStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Instrumented UI tests for the chat screen and the settings screen
 * (API 34 emulator, GitHub Actions).
 *
 * No API key, no network, no live model calls: every test runs with no
 * real key configured, and the only "credentials" used are the obviously
 * fake values [FAKE_BASE_URL], [FAKE_MODEL] and [FAKE_KEY], which are
 * never sent anywhere (the no-key path in ChatViewModel.sendGoal returns
 * before any client is built).
 *
 * Tests run in name order ([FixMethodOrder]), matching SmokeTest's
 * pattern, because state is shared across tests in one instrumentation
 * run:
 * - Onboarding is shown only on a fresh install, so every test tolerates
 *   it already being skipped (see [skipOnboardingIfShown]).
 * - The chat tests that need the empty state (chatEmptyState_*,
 *   examplePrompt_*, sendButton_*) sort before sendWithNoKey_*, which is
 *   the first test to put a message in the chat.
 * - [tearDown] clears the stored chat messages and resets the KeyStore
 *   to its defaults after every test, so a sent message / saved fake
 *   endpoint can never leak into the next test (or into SmokeTest, if
 *   this class runs first): ChatViewModel restores the newest stored
 *   messages on launch, so without the cleanup the empty state would
 *   disappear for every later test in the run.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ChatSettingsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    companion object {
        // Obviously fake values only — never a real endpoint or key.
        private const val FAKE_BASE_URL = "https://example.invalid/api/v1"
        private const val FAKE_MODEL = "test-model"
        private const val FAKE_KEY = "test-key-12345"

        private const val NO_KEY_SYSTEM_MESSAGE =
            "Add your API endpoint and key in Settings first — " +
                "the app never ships with a key or a provider."
    }

    /**
     * If onboarding is showing, tap Skip and wait for the chat; if it was
     * already skipped by an earlier test in this run, just wait for the
     * chat. Copied from SmokeTest.
     */
    private fun skipOnboardingIfShown() {
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithTag("onboarding_skip")
                .fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithTag("empty_state_title")
                    .fetchSemanticsNodes().isNotEmpty() ||
                // After a message has been sent (and restored on launch),
                // there is no empty state — the input field proves we are
                // on the chat screen instead.
                composeTestRule.onAllNodesWithTag("chat_input")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        if (composeTestRule.onAllNodesWithTag("onboarding_skip")
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            composeTestRule.onNodeWithTag("onboarding_skip").performClick()
            composeTestRule.waitUntil(timeoutMillis = 15_000) {
                composeTestRule.onAllNodesWithTag("empty_state_title")
                    .fetchSemanticsNodes().isNotEmpty() ||
                    composeTestRule.onAllNodesWithTag("chat_input")
                        .fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    /** From the chat screen, open Settings and wait for its fields. */
    private fun openSettings() {
        composeTestRule.onNodeWithText("Settings").performClick()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithTag("settings_base_url")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** From Settings, tap Back and wait for the chat input to return. */
    private fun backToChat() {
        composeTestRule.onNodeWithText("Back").performClick()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithTag("chat_input")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Leave no trace for the next test (or test class) in this run:
     * delete stored chat messages — ChatViewModel restores them on the
     * next launch, which would hide the empty state — and reset the
     * KeyStore to its defaults (blank base URL / default model / no key).
     * The send in sendGoal saves the user message from a fire-and-forget
     * IO coroutine, so clear twice with a short delay between them: the
     * second clear catches a save that lands after the first.
     */
    @After
    fun tearDown() {
        runBlocking {
            val context =
                InstrumentationRegistry.getInstrumentation().targetContext
            val keyStore = KeyStore(context)
            keyStore.clearApiKey()
            keyStore.setBaseUrl("")
            // Blank model falls back to KeyStore.DEFAULT_MODEL on read.
            keyStore.setModel("")
            val memoryStore = MemoryStore(context)
            memoryStore.clearMessages()
            delay(300)
            memoryStore.clearMessages()
        }
    }

    @Test
    fun chatEmptyState_showsTitleAndPrompts() {
        skipOnboardingIfShown()

        composeTestRule.onNodeWithTag("empty_state_title").assertIsDisplayed()
        composeTestRule.onNodeWithText("What should Codex do?")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("What time is it?")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Summarise what's on my screen")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Open the clock app")
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_send").assertIsDisplayed()
    }

    @Test
    fun examplePrompt_fillsInput() {
        skipOnboardingIfShown()

        composeTestRule.onNodeWithText("What time is it?").performClick()

        // The prompt fills the input; it does NOT send: the empty state
        // is still showing and no message list exists yet.
        composeTestRule.onNodeWithTag("chat_input")
            .assertTextEquals("What time is it?")
        composeTestRule.onNodeWithTag("empty_state_title").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("chat_messages").assertCountEquals(0)
    }

    @Test
    fun sendButton_disabledWhenInputEmpty() {
        skipOnboardingIfShown()

        composeTestRule.onNodeWithTag("chat_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_send").assertIsNotEnabled()
    }

    @Test
    fun sendWithNoKey_showsNoKeyMessage() {
        skipOnboardingIfShown()

        val goal = "Test goal with no key"
        composeTestRule.onNodeWithTag("chat_input")
            .performTextInput(goal)
        composeTestRule.onNodeWithTag("chat_send").performClick()

        // sendGoal appends the no-key system message synchronously (no
        // network, no client is built); wait for it to appear, then
        // assert both it and the user's own message are in the list.
        // If the app crashed on send, the wait itself would fail.
        // The message is rendered with a "⚠ " prefix (isError), so
        // match by substring, not by exact text.
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText(NO_KEY_SYSTEM_MESSAGE, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(goal).assertIsDisplayed()
        composeTestRule.onNodeWithText(NO_KEY_SYSTEM_MESSAGE, substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_messages").assertIsDisplayed()
    }

    @Test
    fun settings_backReturnsToChat() {
        skipOnboardingIfShown()
        openSettings()

        composeTestRule.onNodeWithTag("settings_base_url").assertIsDisplayed()
        backToChat()

        composeTestRule.onNodeWithTag("chat_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_send").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("settings_base_url")
            .assertCountEquals(0)
    }

    @Test
    fun settings_keyFieldNeverDisplaysStoredKey() {
        skipOnboardingIfShown()
        openSettings()

        composeTestRule.onNodeWithTag("settings_api_key")
            .performTextInput(FAKE_KEY)
        composeTestRule.onNodeWithTag("settings_save").performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag("settings_saved")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Re-open Settings: the field starts blank by design (SettingsScreen
        // initialises apiKey to "" and never reads the stored key back).
        backToChat()
        openSettings()

        // The fake key must not appear anywhere on screen (the field also
        // masks input with PasswordVisualTransformation, so the literal
        // value can never be shown as text).
        composeTestRule.onAllNodesWithText(FAKE_KEY).assertCountEquals(0)
        // Stronger check on the field itself: its editable text is empty.
        // (SemanticsConfiguration has no getOrNull in this Compose version,
        // so check contains() first, then read with the operator get.)
        val semanticsNode = composeTestRule.onNodeWithTag("settings_api_key")
            .fetchSemanticsNode()
        val editableText = if (SemanticsProperties.EditableText in semanticsNode.config) {
            semanticsNode.config[SemanticsProperties.EditableText].text
        } else {
            null
        }
        assertEquals("", editableText)

        // Leave the device clean: remove the fake key again.
        composeTestRule.onNodeWithTag("settings_clear_key").performClick()
        backToChat()
        composeTestRule.onNodeWithTag("no_key_banner").assertIsDisplayed()
    }

    @Test
    fun settings_opensAndShowsFields() {
        skipOnboardingIfShown()
        openSettings()

        composeTestRule.onNodeWithText("Base URL").assertIsDisplayed()
        composeTestRule.onNodeWithText("Model").assertIsDisplayed()
        composeTestRule.onNodeWithText("API key").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_base_url").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_model").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_api_key").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_save").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_clear_key").assertIsDisplayed()
        composeTestRule.onNodeWithText("Keep Codex alive").assertIsDisplayed()
    }

    @Test
    fun settings_savePersistsBaseUrlAndModel() {
        skipOnboardingIfShown()
        openSettings()

        // Model ships with the default "gpt-4o-mini" pre-filled, so clear
        // both fields before typing; base URL starts blank.
        composeTestRule.onNodeWithTag("settings_base_url")
            .performTextClearance()
        composeTestRule.onNodeWithTag("settings_base_url")
            .performTextInput(FAKE_BASE_URL)
        composeTestRule.onNodeWithTag("settings_model")
            .performTextClearance()
        composeTestRule.onNodeWithTag("settings_model")
            .performTextInput(FAKE_MODEL)
        composeTestRule.onNodeWithTag("settings_save").performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag("settings_saved")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("settings_saved").assertIsDisplayed()
        composeTestRule.onNodeWithText("Saved").assertIsDisplayed()

        // Back to chat and re-open Settings: the saved values must still
        // be there (read back from the KeyStore on recomposition).
        // assertTextEquals cannot be used here: the field's semantics
        // carry BOTH the label ("Base URL"/"Model") as Text and the
        // saved value as EditableText, so the merged text never equals
        // the value alone. Assert on EditableText directly instead.
        backToChat()
        openSettings()

        assertEditableText("settings_base_url", FAKE_BASE_URL)
        assertEditableText("settings_model", FAKE_MODEL)
    }

    /**
     * Asserts a TextField's current value by reading its EditableText
     * semantics property (the actual input), ignoring the label text
     * that assertTextEquals would also see.
     */
    private fun assertEditableText(tag: String, expected: String) {
        val node = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode()
        val actual = if (SemanticsProperties.EditableText in node.config) {
            node.config[SemanticsProperties.EditableText].text
        } else {
            null
        }
        assertEquals(expected, actual)
    }
}
