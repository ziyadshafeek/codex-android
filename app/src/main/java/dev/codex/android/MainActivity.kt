package dev.codex.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dev.codex.android.data.OnboardingPrefs
import dev.codex.android.ui.ChatScreen
import dev.codex.android.ui.ChatViewModel
import dev.codex.android.ui.HistoryScreen
import dev.codex.android.ui.OnboardingScreen
import dev.codex.android.ui.SettingsScreen

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
                        showSettings -> {
                            SettingsScreen(
                                viewModel = viewModel,
                                onBack = { showSettings = false },
                            )
                        }
                        else -> {
                            ChatScreen(
                                viewModel = viewModel,
                                onOpenSettings = { showSettings = true },
                                onOpenHistory = { showHistory = true },
                            )
                        }
                    }
                }
            }
        }
    }
}
