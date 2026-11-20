package dev.codex.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The single place for explanatory text. Main screens carry no
 * paragraphs about what the app does; anything that needs explaining
 * lives here, reachable from Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Help") },
                actions = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Your key",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Your key is stored encrypted on this device only (BYOK). " +
                    "It is sent only to the API endpoint you enter in Settings. " +
                    "The app ships with no key and no default provider. " +
                    "Enter any OpenAI-compatible API base URL and your key for it.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Keep Codex alive",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Android may kill background apps. These steps keep Codex running.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "On Xiaomi, Oppo, Vivo and OnePlus, also allow autostart for Codex " +
                    "and lock it in the Recents screen — otherwise the phone may still " +
                    "kill it even with battery exemption.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Slash commands",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Type / in the chat to run a command instead of asking the model: " +
                    "/help lists them all. /plan drafts a plan first, /model switches model, " +
                    "/compact and /recap summarise the thread, /fork copies it, /rename and " +
                    "/delete manage it, /export saves it as a file, /skills and /plugins list " +
                    "what is enabled, /mcp lists servers, /usage shows cost, /status shows " +
                    "the run state, and /clear wipes the thread's messages.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
