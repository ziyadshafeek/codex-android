package dev.codex.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.codex.android.context.contextTokens
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
    val streamedText by viewModel.streamingText.collectAsState()
    val stepStatus by viewModel.stepStatus.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val activeThreadId by viewModel.activeThreadId.collectAsState()
    val hasKey by viewModel.hasKey.collectAsState()
    val summaryProgress by viewModel.summaryProgress.collectAsState()
    val compacting by viewModel.compacting.collectAsState()
    val rateLimitCountdown by viewModel.rateLimitCountdown.collectAsState()
    val download = rememberWorkspaceDownload(viewModel.workspaceFileTools)
    var showFiles by remember { mutableStateOf(false) }
    if (showFiles) WorkspaceFilesDialog(viewModel.workspaceFileTools, download, canDelete = !isRunning && !compacting, onClose = { showFiles = false })
    val attaching by viewModel.attaching.collectAsState()
    val listState = rememberLazyListState()
    var text by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    var showNewChat by remember { mutableStateOf(false) }
    if (showNewChat) NewChatFilesDialog(viewModel, activeThreadId, onClose = { showNewChat = false }, onStart = {
        text = ""
        viewModel.newChat()
        showNewChat = false
    })

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

    LaunchedEffect(messages.lastOrNull()?.id, streamedText.isNotEmpty()) {
        val last = messages.lastIndex + if (streamedText.isNotEmpty()) 1 else 0
        if (last >= 0) listState.scrollToItem(last)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onOpenHistory) { Text("History", maxLines = 1) }
                        TextButton(onClick = onOpenStore) { Text("Store", maxLines = 1) }
                        TextButton(onClick = onOpenPreview) { Text("Preview", maxLines = 1) }
                        TextButton(onClick = onOpenWorkbench) { Text("Workbench", maxLines = 1) }
                    }
                },
                actions = { TextButton(onClick = onOpenSettings) { Text("Settings", maxLines = 1) } },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (messages.isEmpty() && streamedText.isEmpty() && !isRunning) {
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
                    val path = message.artifactPath
                    if (path != null) {
                        WorkspaceFileCard(path, download, Modifier.padding(vertical = 4.dp))
                        return@items
                    }
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
                if (streamedText.isNotEmpty()) item(key = "streamed_reply") {
                    Text(streamedText, Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("chat_streamed_reply"))
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
                TextButton(onClick = { focusManager.clearFocus(force = true); showNewChat = true }, enabled = !isRunning && !compacting && !attaching,
                    modifier = Modifier.testTag("chat_new")) { Text("New chat") }
                TextButton(onClick = if (compacting) viewModel::cancelSummary else viewModel::compactChat,
                    enabled = activeThreadId != null && !isRunning,
                    modifier = Modifier.testTag("chat_compact")) { Text(if (compacting) "Cancel summary" else "Summarise") }
                if (text.isNotBlank()) Text("Draft ~${contextTokens(text)} tokens", style = MaterialTheme.typography.labelSmall)
            }
            if (isRunning && stepStatus.isNotBlank()) Text(stepStatus, modifier = Modifier.padding(horizontal = 12.dp).testTag("chat_step_status"),
                style = MaterialTheme.typography.bodySmall)
            if (compacting) Text("Summarising ${if (summaryProgress == 0) "chat" else "section $summaryProgress"}…",
                modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            AttachmentControls(viewModel, enabled = !isRunning && !compacting, modifier = Modifier.padding(horizontal = 12.dp),
                onOpenWorkspace = { showFiles = true })
            DownloadStatus(download, Modifier.padding(horizontal = 12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    maxLines = 5,
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
                            text = ""
                        } else if (viewModel.sendGoal(text)) text = ""
                    },
                    enabled = !compacting && !attaching && text.isNotBlank() && (!isRunning || activeThreadId != null),
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
