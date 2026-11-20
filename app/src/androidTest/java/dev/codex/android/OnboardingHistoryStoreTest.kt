package dev.codex.android

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.codex.android.store.AppBuildStatus
import dev.codex.android.store.StoreCatalog
import dev.codex.android.store.StoreListing
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Instrumented UI tests for onboarding, History and the Store
 * (API 34 emulator, GitHub Actions).
 *
 * No API key, no network, no live model calls: onboarding, History and
 * store generation are all local. Store generation files a build request
 * only — no build service and no payment is ever invoked.
 *
 * Shared-state rules (same as SmokeTest):
 * - Onboarding completion persists in SharedPreferences
 *   ("codex_onboarding", see OnboardingPrefs) across tests in one
 *   instrumentation run, and the store catalogue persists in the app's
 *   files dir ("codex_store.json", see StoreCatalog). Neither is cleared.
 * - Tests therefore run in name order and each test tolerates onboarding
 *   being either present or already complete.
 * - a_ walks the four onboarding steps but deliberately does NOT tap
 *   Done, so onboarding is still incomplete (and shows again, from step
 *   1) when b_ launches. b_ is the test that completes onboarding.
 * - c_/d_/e_/f_ run after onboarding is complete, so they reach the chat
 *   directly; they still Skip onboarding if it is somehow showing.
 * - d_ must run before e_/f_: it asserts the store's empty sections
 *   ("No apps listed yet." / "Nothing waiting for approval."), which
 *   stop being true once e_ generates a listing and f_ seeds one.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OnboardingHistoryStoreTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private fun isOnboardingShown(): Boolean =
        composeTestRule.onAllNodesWithTag("onboarding_skip")
            .fetchSemanticsNodes().isNotEmpty()

    private fun isChatShown(): Boolean =
        composeTestRule.onAllNodesWithTag("empty_state_title")
            .fetchSemanticsNodes().isNotEmpty()

    /** Waits until onboarding, the chat empty state, or the chat input is on screen. */
    private fun waitForOnboardingOrChat() {
        // 30 s: this is the app-launch wait, and a cold start on the
        // slow no-KVM emulator can take well over 15 s. Only the timeout
        // is raised — the wait still returns as soon as any condition is
        // true. chat_input is a third condition: if an earlier test
        // class left a stored message behind (ChatViewModel restores it
        // on launch), the empty state never appears, and without the
        // input fallback this helper would time out instead of reaching
        // the chat.
        composeTestRule.waitUntil(timeoutMillis = 30_000) {
            isOnboardingShown() || isChatShown() ||
                composeTestRule.onAllNodesWithTag("chat_input")
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** If onboarding is showing, Skip it and wait for the chat; else no-op. */
    private fun skipOnboardingIfShown() {
        waitForOnboardingOrChat()
        if (isOnboardingShown()) {
            composeTestRule.onNodeWithTag("onboarding_skip").performClick()
            composeTestRule.waitUntil(timeoutMillis = 15_000) {
                isChatShown() ||
                    composeTestRule.onAllNodesWithTag("chat_input")
                        .fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    /** Leaves the test on the chat screen, onboarding finished or skipped. */
    private fun reachChat() {
        skipOnboardingIfShown()
        // The chat input is present whether or not the chat is empty; the
        // empty-state title is NOT asserted here — a message restored from
        // an earlier test class hides it, and these tests only need to be
        // on the chat screen (b_ is the test that asserts the empty state,
        // on a path where no message can exist yet).
        composeTestRule.onNodeWithTag("chat_input").assertIsDisplayed()
    }

    /** From the chat, opens History and waits for its empty state or list. */
    private fun openHistory() {
        reachChat()
        composeTestRule.onNodeWithText("History").performClick()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithTag("history_empty")
                .fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithTag("history_list")
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** From the chat, opens the Store and waits for the Generate button. */
    private fun openStore() {
        reachChat()
        composeTestRule.onNodeWithText("Store").performClick()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithTag("store_generate")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Taps the Back button of History/Store and waits for the chat. */
    private fun backToChat() {
        composeTestRule.onNodeWithText("Back").performClick()
        // Wait on the chat input, not the empty-state title: a message
        // restored from an earlier test class hides the empty state,
        // but the input is present on the chat screen either way.
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithTag("chat_input")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("chat_input").assertIsDisplayed()
    }

    /** Asserts the onboarding step indicator reads "Step N of 4". */
    private fun assertStep(step: Int) {
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText("Step $step of 4")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Step $step of 4").assertIsDisplayed()
    }

    private fun tapNext() {
        composeTestRule.onNodeWithTag("onboarding_next").performClick()
    }

    @Test
    fun a_onboarding_showsFourSteps() {
        waitForOnboardingOrChat()
        // Tolerate onboarding already being complete (e.g. another test
        // class in the same run finished it first): then the chat is
        // showing and there is nothing to walk through here. Assert the
        // chat input, not the empty-state title — a message restored
        // from an earlier class would hide the empty state.
        if (!isOnboardingShown()) {
            composeTestRule.onNodeWithTag("chat_input").assertIsDisplayed()
            return
        }

        // Step 1: Welcome.
        composeTestRule.onNodeWithText("Welcome to Codex").assertIsDisplayed()
        composeTestRule.onNodeWithTag("onboarding_step").assertIsDisplayed()
        assertStep(1)
        composeTestRule.onNodeWithText("Welcome").assertIsDisplayed()

        // Step 2: Accessibility.
        tapNext()
        assertStep(2)
        composeTestRule.onNodeWithText("Accessibility").assertIsDisplayed()

        // Step 3: Notifications.
        tapNext()
        assertStep(3)
        composeTestRule.onNodeWithText("Notifications").assertIsDisplayed()

        // Step 4: Stay alive.
        tapNext()
        assertStep(4)
        composeTestRule.onNodeWithText("Stay alive").assertIsDisplayed()

        // Back returns to step 3; Next returns to step 4.
        composeTestRule.onNodeWithText("Back").performClick()
        assertStep(3)
        tapNext()
        assertStep(4)

        // On step 4 the Next button reads "Done". Deliberately NOT tapped:
        // tapping it would complete onboarding here, and b_ is the test
        // that completes it (via Done).
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
        composeTestRule.onNodeWithTag("onboarding_next").assertIsDisplayed()
    }

    @Test
    fun b_onboarding_doneReachesChat() {
        waitForOnboardingOrChat()
        if (isOnboardingShown()) {
            // Walk to step 4 and finish with Done (not Skip), exercising
            // the Done path: OnboardingScreen calls onFinish, MainActivity
            // marks OnboardingPrefs complete and shows the chat.
            assertStep(1)
            tapNext()
            assertStep(2)
            tapNext()
            assertStep(3)
            tapNext()
            assertStep(4)
            composeTestRule.onNodeWithText("Done").assertIsDisplayed()
            composeTestRule.onNodeWithTag("onboarding_next").performClick()
            composeTestRule.waitUntil(timeoutMillis = 15_000) { isChatShown() }
        }

        // Chat empty state.
        composeTestRule.onNodeWithTag("empty_state_title").assertIsDisplayed()
        composeTestRule.onNodeWithText("What should Codex do?").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_send").assertIsDisplayed()
    }

    @Test
    fun c_history_emptyState() {
        openHistory()

        // No runs have ever been sent, so the empty state shows.
        composeTestRule.onNodeWithTag("history_empty").assertIsDisplayed()
        composeTestRule.onNodeWithText("No past runs yet.").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("history_list").assertCountEquals(0)

        backToChat()
    }

    @Test
    fun d_store_opensAndShowsSections() {
        openStore()

        // Build-an-app section: template picker and fields.
        composeTestRule.onNodeWithText("Build an app").assertIsDisplayed()
        // "counter" is the default selection, so it renders with a check.
        composeTestRule.onNodeWithText("✓ Counter").assertIsDisplayed()
        composeTestRule.onNodeWithText("Notes").assertIsDisplayed()
        composeTestRule.onNodeWithText("Timer").assertIsDisplayed()
        composeTestRule.onNodeWithTag("store_app_name").assertIsDisplayed()
        composeTestRule.onNodeWithTag("store_app_description").assertIsDisplayed()
        composeTestRule.onNodeWithTag("store_generate").assertIsDisplayed()
        composeTestRule.onNodeWithText("Generate").assertIsDisplayed()

        // Approved section (empty) and pending section (empty).
        // Both sit below the fold in the LazyColumn, so scroll to them.
        composeTestRule.onNodeWithTag("store_approved").performScrollTo()
        composeTestRule.onNodeWithTag("store_approved").assertIsDisplayed()
        composeTestRule.onNodeWithText("No apps listed yet.").assertIsDisplayed()
        composeTestRule.onNodeWithTag("store_pending").performScrollTo()
        composeTestRule.onNodeWithTag("store_pending").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pending approval").assertIsDisplayed()
        composeTestRule.onNodeWithText("Nothing waiting for approval.")
            .assertIsDisplayed()

        backToChat()
    }

    @Test
    fun e_store_generateCreatesPendingListing() {
        openStore()

        composeTestRule.onNodeWithTag("store_app_name").performClick()
        composeTestRule.onNodeWithTag("store_app_name")
            .performTextInput("Test Counter")
        composeTestRule.onNodeWithTag("store_app_description").performClick()
        composeTestRule.onNodeWithTag("store_app_description")
            .performTextInput("A test counter app")
        // Price left empty: free (0 paise).

        composeTestRule.onNodeWithTag("store_generate").performClick()

        // The template store saves real source, without claiming to start a build.
        val status = "Saved source for 'Test Counter'. No APK build was started."
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(status, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(status, substring = true).assertIsDisplayed()

        // The new listing appears under "Pending approval" (approved=false)
        // with its name and description; the pending-empty text is gone.
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText("Test Counter")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Test Counter").performScrollTo()
        composeTestRule.onNodeWithText("Test Counter").assertIsDisplayed()
        composeTestRule.onNodeWithText("A test counter app").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Nothing waiting for approval.")
            .assertCountEquals(0)

        backToChat()
    }

    @Test
    fun f_store_approvedListingHasNoBuyButton() {
        // Seed ONE approved listing directly in the catalogue before
        // opening the Store. StoreCatalog is a plain class over
        // codex_store.json in the app files dir; StoreScreen creates its
        // own catalog and reloads it in LaunchedEffect when the Store
        // first composes, so seeding here (before tapping Store) is
        // enough — no activity relaunch is needed. save() is suspend, so
        // runBlocking is used on the test thread.
        val context = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext
        runBlocking {
            StoreCatalog(context).save(
                StoreListing(
                    id = UUID.randomUUID().toString(),
                    appId = UUID.randomUUID().toString(),
                    name = "Approved Test App",
                    description = "An approved test app",
                    status = AppBuildStatus.BUILD_REQUESTED,
                    approved = true,
                    createdAtMillis = System.currentTimeMillis(),
                )
            )
        }

        openStore()

        // The seeded listing is in the approved section, so the approved
        // empty text is gone.
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText("Approved Test App")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Approved Test App").performScrollTo()
        composeTestRule.onNodeWithText("Approved Test App").assertIsDisplayed()
        composeTestRule.onNodeWithText("An approved test app").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("No apps listed yet.").assertCountEquals(0)

        // The store lists apps only: no Buy control anywhere, and no
        // payments dialog text.
        composeTestRule.onAllNodesWithText("Buy").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Payments are not live yet")
            .assertCountEquals(0)

        backToChat()
    }
}
