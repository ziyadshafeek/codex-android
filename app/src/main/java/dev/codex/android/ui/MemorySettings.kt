package dev.codex.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.codex.android.data.Fact
import dev.codex.android.memory.MAX_MEMORY_MARKDOWN_LENGTH
import dev.codex.android.memory.MAX_NOTE_LENGTH
import dev.codex.android.memory.MAX_SOUL_LENGTH
import dev.codex.android.memory.MemoryMarkdown
import dev.codex.android.memory.PersonalMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class MemoryTab(val label: String) { NOTES("Notes"), MARKDOWN("Memory.md"), SOUL("Soul.md") }

@Composable
internal fun MemorySettings(memory: PersonalMemory) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("settings_memory")) {
        Text("Memory & style")
    }
    if (open) MemoryDialog(memory, onClose = { open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryDialog(memory: PersonalMemory, onClose: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(MemoryTab.NOTES) }
    var rows by remember { mutableStateOf<List<Fact>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var recall by remember { mutableStateOf(memory.isRecallEnabled()) }
    var filter by rememberSaveable { mutableStateOf("") }
    var editingNote by rememberSaveable { mutableStateOf(false) }
    var originalKey by rememberSaveable { mutableStateOf<String?>(null) }
    var key by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var originalNote by rememberSaveable { mutableStateOf("") }
    var savedNotes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var savedMarkdown by rememberSaveable { mutableStateOf("") }
    var markdown by rememberSaveable { mutableStateOf("") }
    var savedSoul by remember { mutableStateOf("") }
    var soul by rememberSaveable { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun syncNotes(newRows: List<Fact>) {
        val hasDraft = markdown != savedMarkdown
        rows = newRows
        if (!hasDraft) {
            savedNotes = newRows.associate { it.key to it.value }
            savedMarkdown = MemoryMarkdown.render(savedNotes)
            markdown = savedMarkdown
        }
    }

    LaunchedEffect(memory) {
        if (markdown != savedMarkdown) {
            // Restore the original snapshot for conflict detection, not the latest rows.
            savedNotes = runCatching { MemoryMarkdown.parse(savedMarkdown) }.getOrDefault(emptyMap())
        }
        syncNotes(memory.list())
        savedSoul = withContext(Dispatchers.IO) { memory.loadSoul() }
        if (soul == null) soul = savedSoul
        loading = false
    }

    fun requestClose() {
        if (busy) return
        val pendingNote = editingNote && (note != originalNote || (originalKey == null && key.isNotBlank()))
        if (markdown != savedMarkdown || soul.orEmpty() != savedSoul || pendingNote) confirmation = "close"
        else onClose()
    }

    fun change(success: String, refreshNotes: Boolean = false, action: suspend () -> String?) {
        if (busy) return
        busy = true
        feedback = null
        scope.launch {
            try {
                val error = action()
                if (error == null && refreshNotes) syncNotes(memory.list())
                failed = error != null
                feedback = error ?: success
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
                feedback = "Could not complete the change. Try again."
            } finally {
                busy = false
            }
        }
    }

    fun startNote(fact: Fact?) {
        originalKey = fact?.key
        key = fact?.key.orEmpty()
        note = fact?.value.orEmpty()
        originalNote = note
        feedback = null
        editingNote = true
    }

    Dialog(onDismissRequest = ::requestClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { requestClose() }
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding().testTag("memory_editor"),
            topBar = {
                Column {
                    TopAppBar(title = { Text("Memory & style") }, actions = {
                        TextButton(onClick = ::requestClose, enabled = !busy) { Text("Done") }
                    })
                    TabRow(selectedTabIndex = selected.ordinal) {
                        MemoryTab.entries.forEach { choice ->
                            Tab(selected = selected == choice, onClick = { selected = choice; feedback = null },
                                enabled = !busy, text = { Text(choice.label) }, modifier = Modifier.testTag("memory_tab_${choice.name}"))
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(12.dp))
                if (loading) {
                    Text("Loading saved settings…")
                    return@Column
                }
                when (selected) {
                    MemoryTab.NOTES -> {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("Recall saved notes", style = MaterialTheme.typography.titleSmall)
                                    Text("When on, relevant notes are sent to your model.", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = recall, onCheckedChange = {
                                    memory.setRecallEnabled(it)
                                    recall = it
                                }, enabled = !busy, modifier = Modifier.testTag("memory_recall"))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (editingNote) {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (originalKey == null) "New note" else "Edit note", style = MaterialTheme.typography.titleMedium)
                                OutlinedTextField(key, { key = it.take(48) }, label = { Text("Name") },
                                    enabled = originalKey == null && !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(note, { note = it.take(MAX_NOTE_LENGTH) }, label = { Text("Note") },
                                    enabled = !busy, minLines = 3, modifier = Modifier.fillMaxWidth())
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        change("Note saved.", refreshNotes = true) {
                                            val reply = memory.remember(originalKey ?: key, note)
                                            if (reply.startsWith("Remembered:")) { editingNote = false; null } else reply
                                        }
                                    }, enabled = !busy && key.isNotBlank() && note.isNotBlank()) { Text("Save note") }
                                    TextButton(onClick = { editingNote = false; feedback = null }, enabled = !busy) { Text("Cancel") }
                                    if (originalKey != null) {
                                        TextButton(onClick = { confirmation = "delete" }, enabled = !busy) { Text("Delete") }
                                    }
                                }
                            }
                        } else {
                            OutlinedTextField(filter, { filter = it }, label = { Text("Search notes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            val filtered = rows.filter { it.key.contains(filter, true) || it.value.contains(filter, true) }
                            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                                if (filtered.isEmpty()) item {
                                    Text(if (rows.isEmpty()) "No saved notes yet. Add one here or ask in chat." else "No matching notes.",
                                        modifier = Modifier.padding(vertical = 20.dp))
                                }
                                items(filtered, key = { it.id }) { fact ->
                                    TextButton(onClick = { startNote(fact) }, modifier = Modifier.fillMaxWidth().testTag("memory_note_${fact.key}"), enabled = !busy) {
                                        ListItem(headlineContent = { Text(fact.key) }, supportingContent = {
                                            Text(fact.value, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        })
                                    }
                                    HorizontalDivider()
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { startNote(null) }, enabled = !busy) { Text("Add note") }
                                if (rows.isNotEmpty()) TextButton(onClick = { confirmation = "clear" }, enabled = !busy) { Text("Clear all…") }
                            }
                        }
                    }
                    MemoryTab.MARKDOWN -> MarkdownEditor(
                        title = "Memory.md", text = markdown, onChange = { markdown = it; feedback = null },
                        description = "Each ## note-name heading starts a saved note. Its body can use Markdown; write \\## for a literal note-shaped heading.",
                        tag = "memory_markdown", maxLength = MAX_MEMORY_MARKDOWN_LENGTH, dirty = markdown != savedMarkdown,
                        busy = busy, modifier = Modifier.weight(1f),
                        onSave = {
                            change("Memory.md saved.", refreshNotes = true) {
                                val error = memory.saveMarkdown(savedNotes, markdown)
                                if (error == null) savedMarkdown = markdown
                                error
                            }
                        },
                        onReset = {
                            if (markdown != savedMarkdown) confirmation = "reload"
                            else change("Saved notes reloaded.", refreshNotes = true) { null }
                        },
                        resetLabel = "Reload notes", resetWhenClean = true,
                    )
                    MemoryTab.SOUL -> MarkdownEditor(
                        title = "Soul.md", text = soul.orEmpty(), onChange = { soul = it; feedback = null },
                        description = "Describe how Codex should communicate and work. Sent to your model on future runs. It cannot override safety or approvals. Save empty to restore the default.",
                        tag = "soul_markdown", maxLength = MAX_SOUL_LENGTH, dirty = soul.orEmpty() != savedSoul,
                        busy = busy, modifier = Modifier.weight(1f),
                        onSave = {
                            change("Soul.md saved.") {
                                val error = memory.saveSoul(soul.orEmpty())
                                if (error == null) { savedSoul = soul.orEmpty().trim(); soul = savedSoul }
                                error
                            }
                        },
                        onReset = { soul = savedSoul; feedback = null },
                        resetLabel = "Revert edits",
                    )
                }
                feedback?.let {
                    Text(it, modifier = Modifier.padding(top = 4.dp).testTag("memory_status"),
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        confirmation?.let { action ->
            val (title, description) = when (action) {
                "close" -> "Discard unsaved edits?" to "Saved notes and style will not change."
                "reload" -> "Reload saved notes?" to "Unsaved changes in Memory.md will be discarded."
                "delete" -> "Delete this note?" to "It will no longer be recalled. Past chats are unchanged."
                else -> "Delete every saved note?" to "This leaves Soul.md and existing chats unchanged."
            }
            AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(title) }, text = { Text(description) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmation = null
                        when (action) {
                            "close" -> onClose()
                            "reload" -> change("Saved notes reloaded.", refreshNotes = true) { markdown = savedMarkdown; null }
                            "delete" -> change("Note deleted.", refreshNotes = true) {
                                if (originalKey?.let { memory.forget(it) } == true) { editingNote = false; null } else "Could not delete the note."
                            }
                            else -> change("Saved notes deleted.", refreshNotes = true) { if (memory.clear()) null else "Could not delete saved notes." }
                        }
                    }) { Text(when (action) { "close" -> "Discard"; "reload" -> "Reload"; else -> "Delete" }) }
                }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } })
        }
    }
}

@Composable
private fun MarkdownEditor(title: String, text: String, onChange: (String) -> Unit, description: String,
    tag: String, maxLength: Int, dirty: Boolean, busy: Boolean, onSave: () -> Unit, onReset: () -> Unit,
    resetLabel: String, modifier: Modifier = Modifier, resetWhenClean: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(description, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = text, onValueChange = { if (it.length <= maxLength) onChange(it) },
            label = { Text("$title editor") }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1f).fillMaxWidth().testTag(tag), enabled = !busy,
        )
        Text(if (dirty) "Unsaved changes · ${text.length} characters" else "Saved · ${text.length} characters", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = dirty && !busy, modifier = Modifier.testTag("${tag}_save")) { Text("Save $title") }
            TextButton(onClick = onReset, enabled = (dirty || resetWhenClean) && !busy) { Text(resetLabel) }
        }
    }
}
