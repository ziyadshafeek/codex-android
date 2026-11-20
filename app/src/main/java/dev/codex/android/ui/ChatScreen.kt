package dev.codex.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.codex.android.agent.AgentRuntime
import dev.codex.android.voice.VoiceInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenPreview: () -> Unit,
    onOpenWorkbench: () -> Unit = {},
) {
    val messages by viewModel.messages.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val activeThreadId by viewModel.activeThreadId.collectAsState()
    val hasKey by viewModel.hasKey.collectAsState()
    val compacting by viewModel.compacting.collectAsState()
    val rateLimitCountdown by viewModel.rateLimitCountdown.collectAsState()
    val listState = rememberLazyListState()
    var text by remember(activeThreadId) { mutableStateOf("") }

    // M8 voice input + M1 Stop/Steer composer controls. The composable
    // calls AgentRuntime directly for stop/steer; the ViewModel-level
    // stop()/steer() API is a follow-up for integration (ChatViewModel
    // is not modified here).
    val context = LocalContext.current
    var isListening by remember { mutableStateOf(false) }
    var voicePartial by remember { mutableStateOf("") }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val voiceInput = remember(context) {
        VoiceInput(
            context = context,
            listener = object : VoiceInput.Listener {
                override fun onPartialResult(partial: String) {
                    voicePartial = partial
                }

                override fun onFinalResult(transcript: String) {
                    // Append to whatever the user already typed, never
                    // replace it.
                    val clean = transcript.trim()
                    if (clean.isNotEmpty()) {
                        text = if (text.isBlank()) clean else "${text.trimEnd()} $clean"
                    }
                }

                override fun onError(message: String) {
                    voiceError = message
                    isListening = false
                    voicePartial = ""
                }

                override fun onListeningChanged(listening: Boolean) {
                    isListening = listening
                    if (!listening) voicePartial = ""
                }
            }
        )
    }
    DisposableEffect(voiceInput) {
        onDispose { voiceInput.destroy() }
    }
    // Mic permission: request it when ungranted and start listening on
    // grant; a denial is reported in the voice status line instead of
    // starting a recogniser that can only fail.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceError = null
            voiceInput.startListening()
        } else {
            voiceError = "Microphone permission is needed for voice input."
        }
    }

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
                    TextButton(onClick = onOpenPreview) {
                        Text("Preview")
                    }
                    TextButton(onClick = onOpenWorkbench) {
                        Text("Workbench")
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
            // Rate-limit countdown: informational only — it never
            // blocks the input, and it disappears when the wait ends.
            val countdownSeconds = rateLimitCountdown
            if (countdownSeconds != null) {
                Text(
                    text = "Rate limited — retrying in ${countdownSeconds}s",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .testTag("chat_rate_limit_countdown"),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { text = ""; viewModel.newChat() }, enabled = !isRunning && !compacting,
                    modifier = Modifier.testTag("chat_new")) { Text("New chat") }
                TextButton(onClick = viewModel::compactChat, enabled = activeThreadId != null && !isRunning && !compacting,
                    modifier = Modifier.testTag("chat_compact")) { Text(if (compacting) "Summarising…" else "Summarise") }
                val summary = messages.indexOfLast { it.contextSummary }
                val chars = (messages.getOrNull(summary)?.text?.length ?: 0) + messages.drop(summary + 1)
                    .filter { it.role in setOf("user", "assistant") && !it.isError }.takeLast(16).sumOf { it.text.length.coerceAtMost(2000) }
                Text("~${(chars + text.length + 3) / 4} tokens", style = MaterialTheme.typography.labelSmall)
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
                TextButton(
                    onClick = {
                        if (isListening) {
                            voiceInput.stopListening()
                        } else {
                            voiceError = null
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                voiceInput.startListening()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    modifier = Modifier.padding(start = 4.dp).testTag("chat_mic")
                ) {
                    Text(if (isListening) "Listening…" else "Mic")
                }
                // M1: Stop is shown ONLY while a run is active.
                if (isRunning) {
                    TextButton(
                        onClick = { activeThreadId?.let { AgentRuntime.stop(it) } },
                        modifier = Modifier.padding(start = 4.dp).testTag("chat_stop")
                    ) {
                        Text("Stop")
                    }
                }
                Button(
                    onClick = {
                        if (isRunning) {
                            // M1: while a run is active, sending STEERS
                            // the active run instead of starting a new goal.
                            activeThreadId?.let { AgentRuntime.steer(it, text) }
                        } else {
                            viewModel.sendGoal(text)
                        }
                        text = ""
                    },
                    enabled = !compacting && text.isNotBlank() && (!isRunning || activeThreadId != null),
                    modifier = Modifier.padding(start = 8.dp).testTag("chat_send")
                ) {
                    Text(if (isRunning) "Steer" else "Send")
                }
            }
            // Voice status line: live partial transcript while listening,
            // or the failure message when recognition degraded gracefully.
            if (isListening || voiceError != null) {
                Text(
                    text = voiceError ?: "Listening… $voicePartial",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .testTag(if (voiceError != null) "voice_error" else "voice_listening"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (voiceError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

}
