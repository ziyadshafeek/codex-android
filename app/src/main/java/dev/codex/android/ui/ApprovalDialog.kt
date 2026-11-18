package dev.codex.android.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.codex.android.security.PendingAction

@Composable
fun ApprovalDialog(action: PendingAction, onApprove: () -> Unit, onDeny: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text("Approval needed") },
        text = {
            Text(
                "Codex wants to run \"${action.toolName}\".\n" +
                    "${action.description}\n" +
                    "Arguments: ${action.argsJson}"
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
