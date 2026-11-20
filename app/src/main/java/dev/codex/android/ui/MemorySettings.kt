package dev.codex.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codex.android.data.Fact
import dev.codex.android.memory.PersonalMemory
import kotlinx.coroutines.launch

@Composable
internal fun MemorySettings(memory: PersonalMemory) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("settings_memory")) {
        Text("Memory")
    }
    if (open) MemoryDialog(memory, onClose = { open = false })
}

@Composable
private fun MemoryDialog(memory: PersonalMemory, onClose: () -> Unit) {
    var rows by remember { mutableStateOf<List<Fact>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var recall by remember { mutableStateOf(memory.isRecallEnabled()) }
    var filter by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf(false) }
    var originalKey by remember { mutableStateOf<String?>(null) }
    var key by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(memory) {
        rows = memory.list()
        loading = false
    }

    fun runChange(operation: suspend () -> String) {
        busy = true
        scope.launch {
            try {
                status = operation()
                rows = memory.list()
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Notes stay on this device. With recall on, relevant notes can be sent to your configured provider during chat and scheduled runs.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recall saved notes", modifier = Modifier.weight(1f))
                    Switch(checked = recall, onCheckedChange = {
                        recall = it
                        memory.setRecallEnabled(it)
                    }, modifier = Modifier.testTag("memory_recall"))
                }
                if (confirmClear) {
                    Text("Delete all saved notes? This does not delete past chats or copies already sent to your provider.")
                    Row {
                        TextButton(onClick = {
                            confirmClear = false
                            runChange { if (memory.clear()) "Saved notes deleted." else "Could not delete saved notes." }
                        }, enabled = !busy) { Text("Delete all notes") }
                        TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
                    }
                } else if (edit) {
                    OutlinedTextField(key, { key = it }, label = { Text("Name") }, enabled = originalKey == null && !busy, singleLine = true)
                    OutlinedTextField(note, { note = it }, label = { Text("Note") }, enabled = !busy, maxLines = 3)
                    Row {
                        TextButton(onClick = {
                            runChange {
                                memory.remember(originalKey ?: key, note).also {
                                    if (it.startsWith("Remembered:")) edit = false
                                }
                            }
                        }, enabled = !busy) { Text("Save note") }
                        originalKey?.let { name ->
                            TextButton(onClick = {
                                runChange {
                                    if (memory.forget(name)) { edit = false; "Note deleted." }
                                    else "Could not delete the note."
                                }
                            }, enabled = !busy) { Text("Delete") }
                        }
                        TextButton(onClick = { edit = false; status = "" }, enabled = !busy) { Text("Cancel") }
                    }
                } else {
                    OutlinedTextField(filter, { filter = it }, label = { Text("Search notes") }, singleLine = true)
                    val filtered = rows.filter { it.key.contains(filter, true) || it.value.contains(filter, true) }
                    when {
                        loading -> Text("Loading saved notes…")
                        filtered.isEmpty() -> Text(if (rows.isEmpty()) "No saved notes yet." else "No matching notes.")
                        else -> LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
                            items(filtered, key = { it.id }) { fact ->
                                TextButton(
                                    onClick = { originalKey = fact.key; key = fact.key; note = fact.value; edit = true; status = "" },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().testTag("memory_note_${fact.key}"),
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(fact.key, style = MaterialTheme.typography.labelMedium)
                                        Text(fact.value, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                    Row {
                        TextButton(onClick = { originalKey = null; key = ""; note = ""; edit = true; status = "" }, enabled = !busy) { Text("Add note") }
                        if (rows.isNotEmpty()) TextButton(onClick = { confirmClear = true; status = "" }, enabled = !busy) { Text("Clear notes…") }
                    }
                }
                if (status.isNotEmpty()) Text(status, modifier = Modifier.testTag("memory_status"))
            }
        },
        confirmButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Done") } },
    )
}
