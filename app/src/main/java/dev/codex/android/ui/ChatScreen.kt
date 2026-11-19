package dev.codex.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenStore: () -> Unit,
) {
    val messages by viewModel.messages.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val hasKey by viewModel.hasKey.collectAsState()
    val pending by viewModel.gate.pending.collectAsState()
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Codex") },
                actions = {
                    TextButton(onClick = onOpenHistory) {
                        Text("History")
                    }
                    TextButton(onClick = onOpenStore) {
                        Text("Store")
                    }
                    TextButton(onClick = onOpenSettings) {
                        Text("Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (messages.isEmpty()) {
                // Empty state: example prompts fill the input, never send.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "What should Codex do?",
                            modifier = Modifier.testTag("empty_state_title"),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        TextButton(onClick = { text = "What time is it?" }) {
                            Text("What time is it?")
                        }
                        TextButton(onClick = { text = "Summarise what's on my screen" }) {
                            Text("Summarise what's on my screen")
                        }
                        TextButton(onClick = { text = "Open the clock app" }) {
                            Text("Open the clock app")
                        }
                    }
                }
            } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .testTag("chat_messages")
            ) {
                items(messages) { message ->
                    val displayText = if (message.isError) "⚠ ${message.text}" else message.text
                    when (message.role) {
                        "user" -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = displayText,
                                        modifier = Modifier.padding(12.dp),
                                        color = if (message.isError) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        "assistant" -> {
                            Text(
                                text = displayText,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                color = if (message.isError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        else -> {
                            Text(
                                text = displayText,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (message.isError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            } // end else (messages non-empty)
            if (!hasKey) {
                Text(
                    text = "No API endpoint or key set — open Settings to add yours.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .testTag("no_key_banner"),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Ask Codex to do something…") },
                    modifier = Modifier.weight(1f).testTag("chat_input")
                )
                Button(
                    onClick = {
                        viewModel.sendGoal(text)
                        text = ""
                    },
                    enabled = text.isNotBlank() && !isRunning,
                    modifier = Modifier.padding(start = 8.dp).testTag("chat_send")
                ) {
                    Text("Send")
                }
            }
        }
    }

    if (pending != null) {
        ApprovalDialog(
            action = pending!!,
            onApprove = { viewModel.approvePending(true) },
            onDeny = { viewModel.approvePending(false) }
        )
    }
}
