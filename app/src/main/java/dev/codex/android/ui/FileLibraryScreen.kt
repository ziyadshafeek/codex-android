package dev.codex.android.ui

import android.text.format.Formatter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codex.android.attachments.FileStorage
import dev.codex.android.attachments.ManagedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun FileLibraryDialog(viewModel: ChatViewModel, canAttach: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val library = viewModel.fileLibrary
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("All") }
    var storage by remember { mutableStateOf<FileStorage?>(null) }
    var list by remember { mutableStateOf<List<ManagedFile>?>(null) }
    var deleting by remember { mutableStateOf<ManagedFile?>(null) }
    var selected by remember { mutableStateOf<ManagedFile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var limit by remember { mutableStateOf(library.limitMiB.toString()) }
    val download = rememberWorkspaceDownload(null,
        resolve = { key -> library.resolve((library.list("library") + library.staged()).firstOrNull { it.key == key }
            ?: error("File was deleted.")) })
    LaunchedEffect(tab, revision) {
        list = null
        try {
            if (tab == 2) storage = library.storage()
            else list = withContext(Dispatchers.IO) { if (tab == 0) library.list("library") else library.staged() }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { status = e.message ?: "Could not load files." }
    }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("File library") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Library", "Chat files", "Storage").forEachIndexed { index, label ->
                    FilterChip(selected = tab == index, onClick = { tab = index; query = ""; type = "All"; selected = null }, label = { Text(label) })
                }
            }
            if (tab == 2) {
                Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (storage == null) Text("Measuring storage…")
                    storage?.let { s ->
                        fun size(n: Long) = Formatter.formatFileSize(context, n)
                        Text("Managed files: ${size(s.managed)}\nWorkspace projects: ${size(s.workspace)}\nLinux runtime: ${size(s.linux)}\nPhone storage free: ${size(s.free)}\nPhone RAM available: ${size(s.ramAvailable)} / ${size(s.ramTotal)}")
                    }
                    Text("Linux shares the phone’s RAM and has no separate VM allocation. The limit applies to library copies and chat originals, not project files.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(limit, { limit = it.filter(Char::isDigit).take(4) }, label = { Text("Managed limit (MiB)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("file_library_limit"))
                    val valid = limit.toIntOrNull()?.takeIf { it in 0..4096 }
                    TextButton(enabled = valid != null, onClick = { if (valid != null) {
                        library.limitMiB = valid
                        status = "Limit saved. Existing files were not deleted."
                        revision++
                    } }) { Text("Save limit") }
                }
            } else {
                Text(if (tab == 0) "Reusable copies stay here until you delete them." else
                    "Originals from drafts and chats. Deleting a file does not erase exported copies or excerpts already in chat.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, label = { Text("Search files") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("file_library_search"))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("All", "Images", "Videos", "Documents", "Other").forEach { label ->
                        FilterChip(selected = type == label, onClick = { type = label }, label = { Text(label) })
                    }
                }
                val matches = list?.filter { it.name.contains(query.trim(), true) && (type == "All" || fileType(it.name) == type) }
                when {
                    matches == null -> Text("Reading files…")
                    matches.isEmpty() -> Text("No files match.")
                    else -> LazyColumn(Modifier.heightIn(max = 215.dp).testTag("file_library_list")) {
                        items(matches, key = { it.key }) { entry ->
                            TextButton(onClick = { selected = entry }, modifier = Modifier.fillMaxWidth().testTag("library_file_${entry.name}")) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${fileType(entry.name)} · ${Formatter.formatFileSize(context, entry.bytes)}" +
                                        if (entry.group == "drafts") " · Draft" else "", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            DownloadStatus(download)
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Done") } })

    selected?.let { entry ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(entry.name) }, text = {
            Column {
                Text(Formatter.formatFileSize(context, entry.bytes))
                if (entry.saved) TextButton(enabled = canAttach && !busy, onClick = { viewModel.attachLibrary(entry); onClose() }) { Text("Attach to draft") }
                if (!entry.saved) TextButton(enabled = !busy, onClick = {
                    selected = null
                    busy = true
                    scope.launch {
                        try { library.save(entry); status = "Saved to library." }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { status = e.message ?: "Could not save the file." }
                        finally { busy = false; revision++ }
                    }
                }) { Text("Copy to library") }
                TextButton(enabled = !download.busy, onClick = { download.start(entry.key); selected = null }) { Text("Download") }
                TextButton(enabled = !busy && (entry.saved || canAttach), onClick = { selected = null; deleting = entry }) { Text("Delete file", color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } })
    }
    deleting?.let { entry ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${entry.name}?") },
            text = { Text("Only this copy will be deleted. This cannot be undone.") },
            confirmButton = { TextButton(onClick = {
                deleting = null
                busy = true
                scope.launch {
                    try { viewModel.deleteManagedFile(entry); status = "Deleted ${entry.name}." }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { status = e.message ?: "Could not delete the file." }
                    finally { busy = false; revision++ }
                }
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

private fun fileType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png", "jpg", "jpeg", "gif", "webp", "heic", "svg" -> "Images"
    "mp4", "mov", "mkv", "webm", "avi" -> "Videos"
    "pdf", "txt", "md", "doc", "docx", "csv", "odt", "rtf", "xls", "xlsx" -> "Documents"
    else -> "Other"
}

private data class ChatFileChoice(val key: String, val label: String, val original: ManagedFile? = null)

@Composable
internal fun NewChatFilesDialog(viewModel: ChatViewModel, threadId: String?, onClose: () -> Unit, onStart: () -> Unit) {
    val drafts by viewModel.attachments.collectAsState()
    val scope = rememberCoroutineScope()
    val library = viewModel.fileLibrary
    var files by remember { mutableStateOf<List<ChatFileChoice>?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var saved by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(threadId) {
        try {
            files = withContext(Dispatchers.IO) {
                val originals = drafts.map { it.original } + (threadId?.let(library::list) ?: emptyList())
                val outputs = threadId?.let { viewModel.newChatOutputs(it) }.orEmpty()
                originals.map { ChatFileChoice(it.key, it.name, it) } + outputs.map { ChatFileChoice(it, it) }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "Could not list the chat files. You can still start without saving." }
    }
    fun saveAndStart(choices: List<ChatFileChoice>) {
        busy = true
        error = null
        scope.launch {
            try {
                for (choice in choices.filterNot { it.key in saved }) {
                    if (choice.original != null) library.save(choice.original) else library.fromWorkspace(choice.key, "library")
                    saved = saved + choice.key
                }
                onStart()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Some files could not be saved. The current chat is still open." }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("Start a new chat?") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Choose files to copy into your reusable library. The previous chat and workspace stay intact; unsent draft files are cleared.")
            val options = files
            if (options == null && error == null) Text("Finding chat files…")
            if (options?.isEmpty() == true) Text("No files to save.")
            if (!options.isNullOrEmpty()) LazyColumn(Modifier.heightIn(max = 220.dp)) {
                items(options, key = { it.key }) { file ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = file.key in selected || file.key in saved, enabled = !busy && file.key !in saved, onCheckedChange = { checked ->
                            selected = if (checked) selected + file.key else selected - file.key
                        }, modifier = Modifier.testTag("keep_${file.label}"))
                        Text(file.label + if (file.key in saved) " · saved" else "", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!files.isNullOrEmpty()) {
                TextButton(enabled = !busy, onClick = { saveAndStart(files.orEmpty()) }, modifier = Modifier.testTag("new_chat_save_all")) { Text("Save all & start") }
                TextButton(enabled = !busy && selected.isNotEmpty(), onClick = { saveAndStart(files.orEmpty().filter { it.key in selected }) },
                    modifier = Modifier.testTag("new_chat_save_selected")) { Text("Save selected & start") }
            }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = onStart) { Text("Start without saving") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Cancel") } })
}
