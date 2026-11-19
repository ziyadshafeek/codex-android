package dev.codex.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codex.android.data.AutomationEntity
import dev.codex.android.data.CodexDatabase
import dev.codex.android.service.AutomationScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Automations UI (master plan Subsystem 8): a creator form plus the
 * automation list with enable/disable toggles.
 *
 * Standalone composables — NOT wired into navigation. Both take the
 * [CodexDatabase] and drive [AutomationScheduler] directly: saving or
 * toggling an automation writes the row with
 * [CodexDatabase.upsertAutomation] and (re-)schedules or cancels its
 * WorkManager periodic work in the same step.
 */

/** Interval presets offered in the creator, in minutes. */
private val INTERVAL_PRESETS_MINUTES = listOf(15L to "15m", 60L to "1h", 360L to "6h", 1440L to "24h")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationListScreen(
    database: CodexDatabase,
    onCreateClick: () -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var automations by remember { mutableStateOf<List<AutomationEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // Bumped after every mutation so the list reloads from the DB.
    var refreshKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    // Device timezone, "yyyy-MM-dd HH:mm" (same format as HistoryScreen).
    val dateFormat = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    LaunchedEffect(database, refreshKey) {
        automations = runCatching { database.listAutomations() }.getOrDefault(emptyList())
        loading = false
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Automations") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                },
                actions = {
                    TextButton(onClick = onCreateClick) {
                        Text("New")
                    }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (automations.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No automations yet. Create one to run a prompt on a schedule.",
                    modifier = Modifier.testTag("automation_list_empty"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp)
                    .testTag("automation_list")
            ) {
                items(automations, key = { it.id }) { automation ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = automation.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = automation.prompt,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Switch(
                                    checked = automation.enabled,
                                    onCheckedChange = { checked ->
                                        scope.launch {
                                            val updated = automation.copy(enabled = checked)
                                            runCatching { database.upsertAutomation(updated) }
                                            if (checked) {
                                                AutomationScheduler.sync(context, updated)
                                            } else {
                                                AutomationScheduler.cancel(context, updated.id)
                                            }
                                            refreshKey++
                                        }
                                    },
                                    modifier = Modifier.testTag("automation_toggle_${automation.id}")
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = buildString {
                                    append("Every ${automation.intervalMinutes} min")
                                    if (automation.requireWifi) append(" · Wi-Fi only")
                                    if (automation.requireCharging) append(" · Charging")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (automation.lastRunAt != null) {
                                    "Last run: ${dateFormat.format(Date(automation.lastRunAt))}" +
                                        (automation.lastStatus?.let { " · $it" } ?: "")
                                } else {
                                    "Never run"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationCreatorScreen(
    database: CodexDatabase,
    onSaved: (AutomationEntity) -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var intervalText by remember { mutableStateOf("60") }
    var requireWifi by remember { mutableStateOf(false) }
    var requireCharging by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("New automation") },
                navigationIcon = {
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
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("automation_name")
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Prompt — what should it do each run?") },
                minLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("automation_prompt")
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = intervalText,
                onValueChange = { intervalText = it.filter { ch -> ch.isDigit() } },
                label = { Text("Interval (minutes)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("automation_interval")
            )
            Text(
                text = "WorkManager enforces a minimum of 15 minutes — " +
                    "shorter intervals are raised to 15.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                INTERVAL_PRESETS_MINUTES.forEach { (minutes, label) ->
                    TextButton(onClick = { intervalText = minutes.toString() }) {
                        Text(label)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Wi-Fi only",
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = requireWifi, onCheckedChange = { requireWifi = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Only while charging",
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = requireCharging, onCheckedChange = { requireCharging = it })
            }
            error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    val trimmedName = name.trim()
                    val trimmedPrompt = prompt.trim()
                    val interval = intervalText.toLongOrNull()
                    when {
                        trimmedName.isBlank() -> error = "Give the automation a name."
                        trimmedPrompt.isBlank() -> error = "Write what the automation should do."
                        interval == null || interval <= 0 ->
                            error = "Interval must be a number of minutes."
                        else -> {
                            error = null
                            saving = true
                            val automation = AutomationEntity(
                                id = "auto_${UUID.randomUUID().toString().take(12)}",
                                name = trimmedName,
                                workspaceId = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID,
                                prompt = trimmedPrompt,
                                // Coerced to the 15-minute platform
                                // minimum here so the stored value is the
                                // value that actually runs.
                                intervalMinutes = AutomationScheduler.coerceIntervalMinutes(interval),
                                requireWifi = requireWifi,
                                requireCharging = requireCharging,
                                enabled = true,
                                lastRunAt = null,
                                lastStatus = null,
                            )
                            scope.launch {
                                runCatching { database.upsertAutomation(automation) }
                                    .onSuccess {
                                        AutomationScheduler.sync(context, automation)
                                        onSaved(automation)
                                    }
                                    .onFailure {
                                        error = "Could not save: ${it.message}"
                                    }
                                saving = false
                            }
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("automation_save")
            ) {
                Text(if (saving) "Saving…" else "Save automation")
            }
        }
    }
}
