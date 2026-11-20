package dev.codex.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
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
import dev.codex.android.service.CalendarSchedule
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

/** Interval presets offered in the creator, in minutes. */
private val INTERVAL_PRESETS_MINUTES = listOf(15L to "15m", 60L to "1h", 360L to "6h", 1440L to "24h")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationListScreen(
    database: CodexDatabase,
    onCreateClick: () -> Unit = {},
    onOpenReview: () -> Unit,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var automations by remember { mutableStateOf<List<AutomationEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var mutation by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf<AutomationEntity?>(null) }
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

    delete?.let { item ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete automation?") },
            text = { Text("Stop future runs of ${item.name}. Existing results stay in the Review Inbox.") },
            confirmButton = { TextButton(onClick = {
                delete = null
                mutation = true
                scope.launch {
                    runCatching {
                        database.deleteAutomation(item.id)
                        AutomationScheduler.cancel(context, item.id)
                    }.onFailure { status = "Could not finish removing the automation." }
                    mutation = false
                    refreshKey++
                }
            }) { Text("Delete automation") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } },
        )
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
                    TextButton(onClick = onOpenReview) { Text("Review inbox") }
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
                status?.let { text -> item { Text(text, color = MaterialTheme.colorScheme.error) } }
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
                                    enabled = !mutation,
                                    onCheckedChange = { checked ->
                                        mutation = true
                                        status = null
                                        scope.launch {
                                            runCatching {
                                                database.setAutomationEnabled(automation.id, checked)
                                                val updated = database.getAutomation(automation.id) ?: error("Automation was deleted")
                                                AutomationScheduler.sync(context, updated)
                                            }.onFailure {
                                                if (checked) database.setAutomationEnabled(automation.id, false)
                                                status = "Could not update the schedule."
                                            }
                                            mutation = false
                                            refreshKey++
                                        }
                                    },
                                    modifier = Modifier.testTag("automation_toggle_${automation.id}")
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = buildString {
                                    val calendar = automation.calendar
                                    if (calendar == null) append("Every ${automation.intervalMinutes} min")
                                    else {
                                        val day = calendar.weekday?.getDisplayName(TextStyle.FULL, Locale.getDefault())
                                        append(if (day == null) "Daily" else "Every $day")
                                        append(" at ${calendar.time} · ${calendar.zone.id}")
                                    }
                                    if (automation.requireWifi) append(" · Wi-Fi only")
                                    if (automation.requireCharging) append(" · Charging")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            automation.calendar?.takeIf { automation.enabled }?.let { calendar ->
                                val next = calendar.nextAfter(Instant.now()).atZone(calendar.zone)
                                Text("Next due: ${DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm", Locale.getDefault()).format(next)}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
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
                            TextButton(onClick = { delete = automation }, enabled = !mutation) { Text("Delete") }
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
    var calendarMode by remember { mutableStateOf(false) }
    var time by remember { mutableStateOf("09:00") }
    var zone by remember { mutableStateOf(ZoneId.systemDefault().id) }
    var weekday by remember { mutableStateOf<DayOfWeek?>(null) }
    var dayMenu by remember { mutableStateOf(false) }
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
        // Scrollable: on a small screen with the keyboard up the
        // lower fields (interval, Save) would otherwise be unreachable.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Use a calendar schedule", modifier = Modifier.weight(1f))
                Switch(checked = calendarMode, onCheckedChange = { calendarMode = it }, modifier = Modifier.testTag("automation_calendar"))
            }
            if (calendarMode) {
                OutlinedButton(onClick = { dayMenu = true }) {
                    Text(weekday?.getDisplayName(TextStyle.FULL, Locale.getDefault()) ?: "Every day")
                }
                DropdownMenu(expanded = dayMenu, onDismissRequest = { dayMenu = false }) {
                    DropdownMenuItem(text = { Text("Every day") }, onClick = { weekday = null; dayMenu = false })
                    DayOfWeek.values().forEach { day ->
                        DropdownMenuItem(text = { Text(day.getDisplayName(TextStyle.FULL, Locale.getDefault())) },
                            onClick = { weekday = day; dayMenu = false })
                    }
                }
                OutlinedTextField(time, { time = it.take(5) }, label = { Text("Local time (HH:mm)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("automation_time"))
                OutlinedTextField(zone, { zone = it.take(100) }, label = { Text("Time zone (e.g. Asia/Kolkata)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("automation_zone"))
                Text("The phone may run the task late. Missed runs more than six hours old are skipped.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
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
                    val calendar = if (calendarMode) CalendarSchedule.parse(time, zone, weekday) else null
                    when {
                        trimmedName.isBlank() -> error = "Give the automation a name."
                        trimmedPrompt.isBlank() -> error = "Write what the automation should do."
                        calendarMode && calendar == null -> error = "Enter a valid 24-hour time and a time zone such as Asia/Kolkata."
                        !calendarMode && (interval == null || interval <= 0 || interval > 43_200) ->
                            error = "Use an interval of up to 43,200 minutes (30 days)."
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
                                intervalMinutes = if (calendar != null) AutomationScheduler.MIN_INTERVAL_MINUTES
                                    else AutomationScheduler.coerceIntervalMinutes(requireNotNull(interval)),
                                requireWifi = requireWifi,
                                requireCharging = requireCharging,
                                enabled = true,
                                lastRunAt = null,
                                lastStatus = null,
                                calendar = calendar,
                                scheduleStartedAt = System.currentTimeMillis(),
                            )
                            scope.launch {
                                runCatching {
                                    database.upsertAutomation(automation)
                                    AutomationScheduler.sync(context, automation)
                                }
                                    .onSuccess { onSaved(automation) }
                                    .onFailure {
                                        database.deleteAutomation(automation.id)
                                        error = "Could not save or schedule the automation."
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
