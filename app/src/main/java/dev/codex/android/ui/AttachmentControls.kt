package dev.codex.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.InputChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codex.android.attachments.MAX_ATTACHMENTS

/** The same draft and Android file picker are available in phone chat and Workbench. */
@Composable
internal fun AttachmentControls(viewModel: ChatViewModel, enabled: Boolean, modifier: Modifier = Modifier,
    onOpenWorkspace: (() -> Unit)? = null,
) {
    val attachments by viewModel.attachments.collectAsState()
    val loading by viewModel.attaching.collectAsState()
    val error by viewModel.attachmentError.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), viewModel::attachFiles)
    var showLibrary by remember { mutableStateOf(false) }
    var showImages by remember { mutableStateOf(false) }
    if (showLibrary) FileLibraryDialog(viewModel, enabled && !loading && attachments.size < MAX_ATTACHMENTS) { showLibrary = false }
    if (showImages) AlertDialog(onDismissRequest = { showImages = false }, title = { Text("Inline images") }, text = {
        Column {
            Text("Applies to the current API endpoint and model. Auto sends image previews only for known vision models. Enable only if your endpoint accepts image input. Originals stay on the phone; previews and requested tool excerpts go to the provider.")
            val current = viewModel.imageMode()
            listOf("auto" to "Auto", "on" to "Enable images", "off" to "Never send inline images").forEach { (value, label) ->
                TextButton(onClick = { viewModel.setImageMode(value); showImages = false }, modifier = Modifier.testTag("inline_images_$value")) {
                    Text(label + if (current == value) " (selected)" else "")
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { showImages = false }) { Text("Done") } })
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { picker.launch(arrayOf("*/*")) },
                enabled = enabled && !loading && attachments.size < MAX_ATTACHMENTS,
                modifier = Modifier.testTag("attach_files")) {
                Text(if (loading) "Reading files…" else "Attach files")
            }
            TextButton(onClick = { showLibrary = true }, modifier = Modifier.testTag("open_file_library")) { Text("Library") }
            if (onOpenWorkspace != null) TextButton(onClick = onOpenWorkspace, modifier = Modifier.testTag("chat_workspace_files")) {
                Text("Workspace files")
            }
            if (onOpenWorkspace == null) TextButton(onClick = { showImages = true }, enabled = enabled,
                modifier = Modifier.testTag("image_options")) { Text("Image options") }
        }
        if (attachments.isNotEmpty()) Text("${attachments.size}/$MAX_ATTACHMENTS · Attached to your next prompt",
            style = MaterialTheme.typography.labelSmall)
        if (attachments.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            attachments.forEach { file ->
                InputChip(selected = true, onClick = { viewModel.removeAttachment(file.id) }, enabled = enabled,
                    modifier = Modifier.testTag("attachment_${file.name}").semantics { contentDescription = "Remove ${file.name}" },
                    label = { Text("${file.name} · ${file.detail}  ×", maxLines = 1, overflow = TextOverflow.Ellipsis) })
            }
        }
        if (onOpenWorkspace != null && attachments.isNotEmpty()) TextButton(onClick = { showImages = true }, enabled = enabled,
            modifier = Modifier.testTag("image_options")) { Text("Image options") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("attachment_error")) }
    }
}
