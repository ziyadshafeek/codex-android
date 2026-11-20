package dev.codex.android.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.codex.android.agent.PendingApproval

@Composable
fun ApprovalDialog(action: PendingApproval, onApprove: () -> Unit, onDeny: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text("Approval needed") },
        text = {
            Text(
                "Codex wants to run \"${action.toolName}\".\n" +
                    "${action.summary}\n" +
                    "Arguments: ${action.payloadPreview}",
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onApprove) {
                Text("Allow")
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text("Deny")
            }
        }
    )
}
