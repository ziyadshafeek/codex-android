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
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.codex.android.tools.validGitHubRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: ChatViewModel, onBack: () -> Unit, onOpenHelp: () -> Unit) {
    val keyStore = viewModel.keyStore
    var baseUrl by remember { mutableStateOf(keyStore.getBaseUrl()) }
    var model by remember { mutableStateOf(keyStore.getModel()) }
    var apiKey by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    var githubRepo by remember { mutableStateOf(keyStore.githubRepository().orEmpty()) }
    var githubToken by remember { mutableStateOf("") }
    var githubStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
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
            OutlinedButton(
                onClick = onOpenHelp,
                modifier = Modifier.fillMaxWidth().testTag("settings_help")
            ) {
                Text("Help")
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("GitHub Actions", style = MaterialTheme.typography.titleMedium)
            Text("Use an existing build repository and a fine-grained token limited to that repository. " +
                "Source publishing needs Contents write; manual builds need Actions write. " +
                "Enter the token here, never in chat.")
            OutlinedTextField(
                value = githubRepo, onValueChange = { githubRepo = it },
                label = { Text("GitHub repository (owner/repo)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = githubToken, onValueChange = { githubToken = it },
                label = { Text("GitHub token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val repo = githubRepo.trim()
                    val token = githubToken
                    githubToken = ""
                    if (!validGitHubRepository(repo)) {
                        githubStatus = "Use owner/repo (not a URL)."
                    } else scope.launch {
                        githubStatus = withContext(Dispatchers.IO) {
                            runCatching { keyStore.saveGitHub(repo, token) }.fold(
                                { "GitHub settings saved." }, { "Could not access encrypted GitHub storage." },
                            )
                        }
                    }
                }) { Text("Save GitHub") }
                TextButton(onClick = {
                    githubToken = ""
                    scope.launch {
                        val cleared = withContext(Dispatchers.IO) { runCatching { keyStore.clearGitHub() }.isSuccess }
                        if (cleared) githubRepo = ""
                        githubStatus = if (cleared) "GitHub settings cleared." else "Could not clear GitHub settings."
                    }
                }) { Text("Clear GitHub") }
            }
            if (githubStatus.isNotEmpty()) Text(githubStatus)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Keep Codex alive",
                style = MaterialTheme.typography.titleMedium
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
            Spacer(modifier = Modifier.height(24.dp))
            // Voluntary tip for the developer. Unlocks nothing.
            SupportCodexSection()
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
