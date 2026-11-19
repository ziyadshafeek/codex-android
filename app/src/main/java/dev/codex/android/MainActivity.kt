package dev.codex.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import dev.codex.android.browser.VerificationBrowser
import dev.codex.android.data.OnboardingPrefs
import dev.codex.android.ui.ChatScreen
import dev.codex.android.ui.ChatViewModel
import dev.codex.android.ui.HelpScreen
import dev.codex.android.ui.HistoryScreen
import dev.codex.android.ui.OnboardingScreen
import dev.codex.android.ui.PreviewScreen
import dev.codex.android.ui.SettingsScreen
import dev.codex.android.ui.ShareActivity
import dev.codex.android.ui.StoreScreen

/**
 * Phase 1 entry point: a chat UI wired to the agent loop, plus a settings
 * screen for the user's own API key (BYOK). No key is ever shipped.
 *
 * Phase 5: first launch shows the onboarding flow (gated on
 * [OnboardingPrefs]); onboarding is skippable on every step and never
 * blocks the chat. A History screen is reachable from the chat.
 */
class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()
    // Lazy: `application` is only set after attach(), i.e. after field
    // initialisers run, so an eager OnboardingPrefs(application) would NPE.
    private val onboardingPrefs by lazy { OnboardingPrefs(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showOnboarding by remember {
                        mutableStateOf(!onboardingPrefs.isComplete())
                    }
                    var showSettings by remember { mutableStateOf(false) }
                    var showHistory by remember { mutableStateOf(false) }
                    var showStore by remember { mutableStateOf(false) }
                    var showPreview by remember { mutableStateOf(false) }
                    var showHelp by remember { mutableStateOf(false) }
                    // One browser for the whole activity: the WebView is
                    // created on the main thread and shared by the
                    // Preview tab and the agent's browser tools.
                    val browser = remember { VerificationBrowser(applicationContext) }
                    when {
                        showOnboarding -> {
                            OnboardingScreen(
                                onFinish = {
                                    onboardingPrefs.setComplete()
                                    showOnboarding = false
                                }
                            )
                        }
                        showHistory -> {
                            HistoryScreen(
                                viewModel = viewModel,
                                onBack = { showHistory = false }
                            )
                        }
                        showStore -> {
                            StoreScreen(
                                onBack = { showStore = false }
                            )
                        }
                        showPreview -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                TextButton(onClick = { showPreview = false }) {
                                    Text("Back")
                                }
                                PreviewScreen(
                                    browser = browser,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                )
                            }
                        }
                        showHelp -> {
                            HelpScreen(
                                onBack = { showHelp = false },
                            )
                        }
                        showSettings -> {
                            SettingsScreen(
                                viewModel = viewModel,
                                onBack = { showSettings = false },
                                onOpenHelp = { showHelp = true },
                            )
                        }
                        else -> {
                            ChatScreen(
                                viewModel = viewModel,
                                onOpenSettings = { showSettings = true },
                                onOpenHistory = { showHistory = true },
                                onOpenStore = { showStore = true },
                                onOpenPreview = { showPreview = true },
                            )
                        }
                    }
                }
            }
        }
        handleSharedThread(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedThread(intent)
    }

    /**
     * Share-to-Codex / QS tile entry point: an intent carrying
     * [ShareActivity.EXTRA_THREAD_ID] opens that thread in the chat.
     * MainActivity is standard launch mode, so a share normally creates
     * a fresh instance (handled in onCreate); onNewIntent covers a
     * re-delivery to an existing instance.
     */
    private fun handleSharedThread(intent: Intent?) {
        val threadId = intent?.getStringExtra(ShareActivity.EXTRA_THREAD_ID)
            ?: return
        viewModel.openThread(threadId)
    }
}
