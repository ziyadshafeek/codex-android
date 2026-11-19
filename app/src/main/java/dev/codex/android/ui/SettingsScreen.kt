package dev.codex.android.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.codex.android.service.BatteryHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    val keyStore = viewModel.keyStore
    var baseUrl by remember { mutableStateOf(keyStore.getBaseUrl()) }
    var model by remember { mutableStateOf(keyStore.getModel()) }
    var apiKey by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var batteryExempt by remember { mutableStateOf(BatteryHelper.isExempt(context)) }
    var accessibilityEnabled by remember {
        mutableStateOf(BatteryHelper.isAccessibilityEnabled(context))
    }
    // Re-read the live statuses when returning from a system settings
    // screen, so the rows show the choice the user just made.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt = BatteryHelper.isExempt(context)
                accessibilityEnabled = BatteryHelper.isAccessibilityEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; saved = false },
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth().testTag("settings_base_url")
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; saved = false },
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth().testTag("settings_model")
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; saved = false },
                label = { Text("API key") },
                placeholder = { Text("Paste your key here") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("settings_api_key")
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    keyStore.setBaseUrl(baseUrl)
                    keyStore.setModel(model)
                    if (apiKey.isNotBlank()) {
                        keyStore.setApiKey(apiKey)
                        apiKey = ""
                    }
                    viewModel.refreshKeyState()
                    saved = true
                },
                modifier = Modifier.fillMaxWidth().testTag("settings_save")
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
                modifier = Modifier.fillMaxWidth().testTag("settings_clear_key")
            ) {
                Text("Clear key")
            }
            if (saved) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Saved",
                    modifier = Modifier.testTag("settings_saved"),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Your key is stored encrypted on this device only (BYOK). " +
                    "It is sent only to the API endpoint above. " +
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
            SurvivalRow(
                label = "Battery optimisation",
                status = if (batteryExempt) "Exempt" else "Not exempt",
                actionText = "Request exemption",
                onAction = {
                    val activity = context as? Activity ?: return@SurvivalRow
                    val launched = try {
                        BatteryHelper.requestExemption(activity)
                    } catch (e: Exception) {
                        false
                    }
                    if (!launched) {
                        BatteryHelper.openBatterySettings(context)
                    }
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
            SurvivalRow(
                label = "Accessibility service",
                status = if (accessibilityEnabled) "Enabled" else "Not enabled",
                actionText = "Open Accessibility settings",
                onAction = { BatteryHelper.openAccessibilitySettings(context) }
            )
            Spacer(modifier = Modifier.height(12.dp))
            SurvivalRow(
                label = "Notifications",
                status = "Notifications let Codex tell you when a task finishes.",
                actionText = "Open notification settings",
                onAction = {
                    try {
                        val intent =
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        // No-op: the settings screen is unavailable on this device.
                    }
                }
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "On Xiaomi, Oppo, Vivo and OnePlus, also allow autostart for Codex " +
                    "and lock it in the Recents screen — otherwise the phone may still " +
                    "kill it even with battery exemption.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** One survival row: a label plus its live status, with an action button. */
@Composable
private fun SurvivalRow(
    label: String,
    status: String,
    actionText: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(onClick = onAction) {
            Text(actionText)
        }
    }
}
