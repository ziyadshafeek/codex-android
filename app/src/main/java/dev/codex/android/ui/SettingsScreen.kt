package dev.codex.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    val keyStore = viewModel.keyStore
    var baseUrl by remember { mutableStateOf(keyStore.getBaseUrl()) }
    var model by remember { mutableStateOf(keyStore.getModel()) }
    var apiKey by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
        ) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key") },
                placeholder = { Text("Paste your key here") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    keyStore.setBaseUrl(baseUrl)
                    keyStore.setModel(model)
                    if (apiKey.isNotBlank()) {
                        keyStore.setApiKey(apiKey)
                    }
                    viewModel.refreshKeyState()
                    saved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    keyStore.clearApiKey()
                    viewModel.refreshKeyState()
                    saved = false
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear key")
            }
            if (saved) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Saved", color = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Your key is stored encrypted on this device only (BYOK). " +
                    "It is sent only to the API endpoint above. " +
                    "The app ships with no key and no default provider. " +
                    "Enter any OpenAI-compatible API base URL and your key for it.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
