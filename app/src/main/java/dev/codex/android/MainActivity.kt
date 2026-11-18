package dev.codex.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Phase 0 entry point: a minimal Compose hello-world screen.
 * The agent runtime, API client, and background services land in later phases.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CodexApp()
        }
    }
}

@Composable
fun CodexApp() {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Codex",
                    style = MaterialTheme.typography.headlineLarge
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Phase 0 — toolchain check: the build works, the agent runtime lands next."
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Status: skeleton only. No agent, no API calls, no background service yet.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
