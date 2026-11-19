package dev.codex.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Emulator smoke tests (API 34, GitHub Actions).
 *
 * Launch the real MainActivity on a fresh install and prove the first-run
 * path works: onboarding shows, Skip reaches the chat, the empty state and
 * the no-key banner are visible. No API key, no network, no live model —
 * the tests only assert what the app shows with no key configured.
 *
 * Onboarding state persists across tests in one instrumentation run
 * (SharedPreferences is deliberately NOT cleared — Skip is the real user
 * path), so tests run in name order: the onboarding test first, while the
 * fresh install still shows onboarding; the later tests tolerate onboarding
 * already being skipped.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    /**
     * If onboarding is showing, tap Skip and wait for the chat; if it was
     * already skipped by an earlier test in this run, just wait for the chat.
     */
    private fun skipOnboardingIfShown() {
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithTag("onboarding_skip")
                .fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithTag("empty_state_title")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        if (composeTestRule.onAllNodesWithTag("onboarding_skip")
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            composeTestRule.onNodeWithTag("onboarding_skip").performClick()
            composeTestRule.waitUntil(timeoutMillis = 15_000) {
                composeTestRule.onAllNodesWithTag("empty_state_title")
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun appLaunches_showsOnboarding_skipReachesChat() {
        // Fresh install: onboarding is the first screen.
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText("Welcome to Codex")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Welcome to Codex").assertIsDisplayed()
        composeTestRule.onNodeWithTag("onboarding_step").assertIsDisplayed()

        composeTestRule.onNodeWithTag("onboarding_skip").performClick()

        // Chat empty state: title, input field and Send button.
        composeTestRule.onNodeWithTag("empty_state_title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_send").assertIsDisplayed()
    }

    @Test
    fun chatEmptyState_showsExamplePrompts() {
        skipOnboardingIfShown()

        composeTestRule.onNodeWithTag("empty_state_title").assertIsDisplayed()
        composeTestRule.onNodeWithText("What time is it?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Summarise what's on my screen")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Open the clock app").assertIsDisplayed()
    }

    @Test
    fun noKeyBanner_showsWhenNoKeySet() {
        skipOnboardingIfShown()

        // No key is configured on a fresh install, so the banner shows.
        composeTestRule.onNodeWithTag("no_key_banner").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "No API endpoint or key set — open Settings to add yours."
        ).assertIsDisplayed()
    }
}
