package dev.codex.android.ui

import android.app.Activity
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codex.android.artifacts.downloadIndex
import dev.codex.android.artifacts.resolveWorkspaceDownload
import dev.codex.android.tools.WorkspaceFileTools
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class WorkspaceDownload(val start: (String) -> Unit, val busy: Boolean, val status: String?, val failed: Boolean)

@Composable
internal fun rememberWorkspaceDownload(workspace: WorkspaceFileTools?, resolve: ((String) -> java.io.File)? = null): WorkspaceDownload {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = pending
        pending = null
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null || path == null || (workspace == null && resolve == null)) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val file = resolve?.invoke(path) ?: resolveWorkspaceDownload(requireNotNull(workspace), path)
                    require(uri.scheme == "content") { "Choose a destination from Android Files." }
                    Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { source ->
                        val destination = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the save destination.")
                        destination.use { source.copyTo(it) }
                    }
                }
                failed = false
                status = "Saved ${path.substringAfterLast('/')} to the location you chose."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                status = when (e) {
                    is IllegalArgumentException -> e.message
                    else -> "Could not save the file. Check the workspace and try another destination."
                }
            } finally { busy = false }
        }
    }
    return WorkspaceDownload(start = { path ->
        if ((workspace != null || resolve != null) && pending == null && !busy) {
            status = null
            pending = path
            val name = path.substringAfterLast('/')
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
                ?: "application/octet-stream"
            try {
                launcher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mime
                    putExtra(Intent.EXTRA_TITLE, name)
                })
            } catch (_: android.content.ActivityNotFoundException) {
                pending = null
                failed = true
                status = "No Android Files app is available to save this document."
            }
        }
    }, busy = busy || pending != null, status = status, failed = failed)
}

@Composable
internal fun DownloadStatus(download: WorkspaceDownload, modifier: Modifier = Modifier) {
    download.status?.let { Text(it, modifier.testTag("workspace_download_status"),
        color = if (download.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodySmall) }
}

@Composable
internal fun WorkspaceFileCard(path: String, download: WorkspaceDownload, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth().testTag("workspace_output_$path"),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Workspace / $path", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { download.start(path) }, enabled = !download.busy,
                modifier = Modifier.testTag("download_$path")) { Text("Download") }
        }
    }
}

@Composable
internal fun WorkspaceFilesDialog(workspace: WorkspaceFileTools, download: WorkspaceDownload, canDelete: Boolean, onClose: () -> Unit) {
    var files by remember { mutableStateOf<List<String>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var deletionStatus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(workspace, revision) {
        try { files = withContext(Dispatchers.IO) { downloadIndex(workspace).keys.sorted() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    AlertDialog(
        title = { Text("Workspace files") },
        onDismissRequest = onClose,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Download a copy of the current saved file. Choose a folder in Android Files.")
                OutlinedTextField(query, { query = it }, label = { Text("Filter by name or path") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("workspace_file_filter"))
                val matches = files?.filter { it.contains(query.trim(), ignoreCase = true) }
                when {
                    failed -> Text("Could not list the workspace.", color = MaterialTheme.colorScheme.error)
                    matches == null -> Text("Reading workspace…")
                    matches.isEmpty() -> Text(if (query.isBlank()) "No downloadable files in this workspace." else "No files match the filter.")
                    else -> LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(matches, key = { it }) { path ->
                            Column {
                                WorkspaceFileCard(path, download)
                                if (canDelete) TextButton(onClick = { confirmation = path }) { Text("Delete file") }
                            }
                        }
                    }
                }
                DownloadStatus(download)
                deletionStatus?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
    confirmation?.let { path ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("Delete $path?") },
            text = { Text("This deletes the workspace copy. Exported copies and library files are not affected.") },
            confirmButton = { TextButton(enabled = canDelete, onClick = {
                confirmation = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { Files.delete(resolveWorkspaceDownload(workspace, path).toPath()) }
                        deletionStatus = "Deleted $path."
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { deletionStatus = "Could not delete $path." }
                    revision++
                }
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } })
    }
}
