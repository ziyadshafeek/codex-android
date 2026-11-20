package dev.codex.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codex.android.agent.AgentRuntime
import dev.codex.android.agent.PendingApproval
import dev.codex.android.artifacts.Artifact
import dev.codex.android.artifacts.Diff
import dev.codex.android.artifacts.ImplementationPlan
import dev.codex.android.artifacts.InlineComment
import dev.codex.android.artifacts.InlineCommentStore
import dev.codex.android.artifacts.TaskChecklist
import dev.codex.android.artifacts.Walkthrough
import dev.codex.android.artifacts.renderArtifactMarkdown
import dev.codex.android.browser.VerificationBrowser
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.CostTelemetry
import dev.codex.android.data.ThreadEntity
import dev.codex.android.data.TimelineMessage
import dev.codex.android.editor.SoraEditorScreen
import dev.codex.android.linux.ProotShellEngine
import dev.codex.android.subagents.SubagentInfo
import dev.codex.android.terminal.TerminalScreen
import dev.codex.android.terminal.TerminalStateHolder
import dev.codex.android.terminal.runBangCommand
import dev.codex.android.tools.FileChangeSummary
import dev.codex.android.tools.WorkspaceFileTools
import dev.codex.android.vcs.SnapshotEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Extra workbench colours kept here (not part of the shared OledTheme palette).
val OledAmberContainer = Color(0xFF2B1F06)
val OledGreen = Color(0xFF22C55E)
val OledRed = Color(0xFFEF4444)

private val OledCodexColorScheme = darkColorScheme(
    primary = OledAccentCyan,
    onPrimary = Color(0xFF00141F),
    primaryContainer = Color(0xFF0F293A),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = OledAmber,
    onSecondary = Color(0xFF1F1400),
    secondaryContainer = OledAmberContainer,
    onSecondaryContainer = Color(0xFFFDE68A),
    background = OledBlack,
    onBackground = Color(0xFFF1F5F9),
    surface = OledSurface,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = OledSurfaceElevated,
    onSurfaceVariant = Color(0xFF94A3B8),
    error = OledRed,
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFF233044),
)

/** Wraps content in the OLED Material 3 theme used across the workbench and DeX mode. */
@Composable
fun OledCodexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OledCodexColorScheme,
        content = content,
    )
}

/**
 * OLED Material 3 Workbench (Milestone M2).
 *
 * Standalone new screen so ChatScreen.kt / ChatViewModel.kt remain untouched.
 * The Terminal tab hosts the real [TerminalScreen] (H2) driven by
 * [terminalEngine]; the Browser tab hosts the real [PreviewScreen] (M7)
 * driven by [browser] — the same [VerificationBrowser] instance MainActivity
 * already constructs and shares with the agent's browser tools. The Files
 * tab opens the real [SoraEditorScreen] (H3) for the tapped file (the
 * file list is the fallback when no file is open); [snapshotEngine]
 * backs Undo/Redo in the editor and Diff tabs and snapshots every
 * editor save. Pass [onSendPrompt]/[onSteer]/[onStop] from the integration;
 * [onRunShellCommand] defaults to running `!cmd` through [terminalEngine]
 * via [runBangCommand] (output lands in the terminal tab) and only needs
 * an explicit value to route shell commands elsewhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchScreen(
    database: CodexDatabase? = null,
    costTelemetry: CostTelemetry? = null,
    workspaceFiles: WorkspaceFileTools? = null,
    terminalEngine: ProotShellEngine? = null,
    browser: VerificationBrowser? = null,
    activeThreadId: String = "thr_default",
    messages: List<TimelineMessage> = emptyList(),
    artifacts: List<Artifact> = emptyList(),
    subagents: List<SubagentInfo> = emptyList(),
    stateHolder: WorkbenchStateHolder = remember { WorkbenchStateHolder() },
    terminalStateHolder: TerminalStateHolder = remember { TerminalStateHolder() },
    snapshotEngine: SnapshotEngine? = null,
    onSelectThread: (String) -> Unit = {},
    onSendPrompt: (prompt: String, mode: ComposerMode, attachedFiles: List<String>) -> Boolean = { _, _, _ -> false },
    attachmentControls: (@Composable (enabled: Boolean) -> Unit)? = null,
    onSteer: (threadId: String, instruction: String) -> Unit = { id, text -> AgentRuntime.steer(id, text) },
    onStop: (threadId: String) -> Unit = { id -> AgentRuntime.stop(id) },
    onRunShellCommand: ((String) -> Unit)? = null,
    onOpenFile: (relativePath: String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    filesTabContent: (@Composable (List<String>, (String) -> Unit) -> Unit)? = null,
    terminalTabContent: @Composable () -> Unit = {
        if (terminalEngine != null) {
            TerminalScreen(
                engine = terminalEngine,
                stateHolder = terminalStateHolder,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp).testTag("workbench_terminal_unavailable"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Terminal not started.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    },
    browserTabContent: @Composable () -> Unit = {
        if (browser != null) {
            PreviewScreen(browser = browser)
        } else {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp).testTag("workbench_browser_unavailable"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Browser not available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val uiState by stateHolder.state.collectAsState()
    val runtimeThreads by AgentRuntime.threads.collectAsState()

    // `!cmd` from the Power Composer: an explicit handler wins; otherwise run
    // through the terminal engine and show the output in the Terminal tab.
    val runShellCommand: (String) -> Unit = onRunShellCommand ?: { cmd ->
        val engine = terminalEngine
        if (engine != null && cmd.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                val res = runBangCommand(engine, "!$cmd")
                if (res != null) {
                    val combined = buildString {
                        append("$ ").append(cmd).append('\n')
                        if (res.stdout.isNotBlank()) append(res.stdout).append('\n')
                        if (res.stderr.isNotBlank()) append(res.stderr).append('\n')
                        append("[exit ${res.exitCode}]")
                    }
                    terminalStateHolder.setLastCommandOutput(combined)
                }
            }
        }
    }

    val currentThreadRuntime = runtimeThreads[activeThreadId]
    val isRunning = currentThreadRuntime?.isRunning == true || uiState.isRunning
    val pendingApprovals = remember(runtimeThreads) {
        runtimeThreads.values.mapNotNull { it.pendingApproval }
    }

    var threads by remember { mutableStateOf<List<ThreadEntity>>(emptyList()) }
    var costStats by remember { mutableStateOf(CostStatsSummary()) }
    var fileList by remember { mutableStateOf<List<String>>(emptyList()) }
    var fileChanges by remember { mutableStateOf<List<FileChangeSummary>>(emptyList()) }
    var commentVersion by remember { mutableStateOf(0) }

    // Files-tab editor state: the tapped file opens the real Sora editor;
    // null means the file list. `editorVersion` re-creates the editor
    // (reloading disk content) after an undo/redo.
    var openFilePath by remember { mutableStateOf<String?>(null) }
    var editorVersion by remember { mutableStateOf(0) }

    // Reloads the file list + diff state from disk/DB (Main-safe: the
    // I/O runs on Dispatchers.IO, the state writes land on Main).
    val refreshFiles: suspend () -> Unit = {
        if (workspaceFiles != null) {
            val (files, changes) = withContext(Dispatchers.IO) {
                val f = runCatching { workspaceFiles.listFiles(maxResults = 300) }.getOrDefault(emptyList())
                val c = runCatching { workspaceFiles.getChanges() }.getOrDefault(emptyList())
                f to c
            }
            fileList = files
            fileChanges = changes
        }
    }

    // Runs one snapshot action (undo/redo) on IO; when it changed
    // anything, the editor reloads from disk and the lists refresh.
    fun runSnapshotAction(action: suspend SnapshotEngine.() -> Boolean) {
        val engine = snapshotEngine ?: return
        scope.launch {
            val changed = withContext(Dispatchers.IO) {
                runCatching { engine.action() }.getOrDefault(false)
            }
            if (changed) {
                editorVersion++
                refreshFiles()
            }
        }
    }

    // Snapshots an editor save (on IO, best-effort) and refreshes the
    // file list / diff state so the save shows up immediately.
    fun onEditorSaved(relativePath: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { snapshotEngine?.snapshot("edit:$relativePath") }
        }
        scope.launch { refreshFiles() }
    }

    // Zero disk/DB I/O on Main: hydrate Mission Control + Workspace state on Dispatchers.IO.
    LaunchedEffect(database, costTelemetry, activeThreadId, runtimeThreads.size) {
        val loadedThreads = withContext(Dispatchers.IO) {
            runCatching { database?.listThreads().orEmpty() }.getOrDefault(emptyList())
        }
        val loadedCost = withContext(Dispatchers.IO) {
            runCatching {
                val totals = costTelemetry?.totalsAll()
                if (totals != null) {
                    CostStatsSummary(
                        inputTokens = totals.inputTokens,
                        outputTokens = totals.outputTokens,
                        costUsd = totals.costUsd,
                        calls = totals.calls,
                    )
                } else {
                    CostStatsSummary()
                }
            }.getOrDefault(CostStatsSummary())
        }
        threads = loadedThreads
        costStats = loadedCost
    }

    LaunchedEffect(workspaceFiles, uiState.activeTab) {
        refreshFiles()
    }

    val pendingComments = remember(activeThreadId, commentVersion) {
        InlineCommentStore.pendingComments(activeThreadId)
    }

    OledCodexTheme {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = OledSurface,
                    modifier = Modifier.width(320.dp).testTag("workbench_mission_control_drawer"),
                ) {
                    MissionControlPanel(
                        threads = threads,
                        activeThreadId = activeThreadId,
                        subagents = subagents,
                        pendingApprovals = pendingApprovals,
                        costStats = costStats,
                        onSelectThread = { id ->
                            onSelectThread(id)
                            scope.launch { drawerState.close() }
                        },
                        onAnswerApproval = { approvalId, approved, allowSession ->
                            AgentRuntime.answerApproval(context, approvalId, approved, allowSession)
                        },
                    )
                }
            },
            modifier = modifier,
        ) {
            Scaffold(
                containerColor = OledBlack,
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = OledSurface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        navigationIcon = {
                            TextButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.testTag("workbench_open_drawer"),
                            ) {
                                Text("☰", fontFamily = FontFamily.Monospace)
                            }
                        },
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "CODEX STUDIO",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                )
                                if (pendingApprovals.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = OledAmber,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.testTag("workbench_approval_badge"),
                                    ) {
                                        Text(
                                            text = "${pendingApprovals.size} APPROVE",
                                            color = Color.Black,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = onOpenSettings,
                                modifier = Modifier.testTag("workbench_settings"),
                            ) {
                                Text("Settings", fontFamily = FontFamily.Monospace)
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .imePadding(),
                ) {
                    ScrollableTabRow(
                        selectedTabIndex = uiState.activeTab.ordinal,
                        containerColor = OledSurface,
                        contentColor = OledAccentCyan,
                        edgePadding = 8.dp,
                    ) {
                        WorkbenchTab.values().forEach { tab ->
                            Tab(
                                selected = uiState.activeTab == tab,
                                onClick = { stateHolder.selectTab(tab) },
                                modifier = Modifier.testTag(tab.testTag),
                                text = {
                                    Text(
                                        text = tab.label,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (uiState.activeTab) {
                            WorkbenchTab.AGENT -> AgentTabContent(
                                threadId = activeThreadId,
                                messages = messages,
                                artifacts = artifacts,
                                pendingComments = pendingComments,
                                streamingText = currentThreadRuntime?.streamingAssistantText.orEmpty(),
                                statusText = currentThreadRuntime?.statusText.orEmpty(),
                                uiState = uiState,
                                isRunning = isRunning,
                                workspaceFiles = fileList,
                                stateHolder = stateHolder,
                                onAddInlineComment = { artifactId, targetId, text ->
                                    InlineCommentStore.addComment(activeThreadId, artifactId, targetId, text)
                                    commentVersion++
                                },
                                onSteerWithComments = {
                                    val consumed = InlineCommentStore.consumePendingForSteering(activeThreadId)
                                    commentVersion++
                                    if (consumed.isNotEmpty()) {
                                        val note = InlineCommentStore.formatForSteering(consumed)
                                        if (isRunning) {
                                            onSteer(activeThreadId, note)
                                        } else {
                                            onSendPrompt(note, uiState.composerMode, uiState.attachedFiles)
                                        }
                                    }
                                },
                                onSendPrompt = onSendPrompt,
                                attachmentControls = attachmentControls,
                                onSteer = { text -> onSteer(activeThreadId, text) },
                                onStop = { onStop(activeThreadId) },
                                onRunShellCommand = runShellCommand,
                            )

                            WorkbenchTab.FILES -> {
                                val openPath = openFilePath
                                val workspaceRoot = workspaceFiles?.rootDir
                                if (openPath != null && workspaceRoot != null) {
                                    EditorTabContent(
                                        file = File(workspaceRoot, openPath),
                                        relativePath = openPath,
                                        proot = terminalEngine,
                                        snapshotEngine = snapshotEngine,
                                        editorVersion = editorVersion,
                                        onBack = { openFilePath = null },
                                        onUndo = { runSnapshotAction { undo() } },
                                        onRedo = { runSnapshotAction { redo() } },
                                        onSaved = { onEditorSaved(openPath) },
                                        onPinToComposer = { pin ->
                                            stateHolder.insertPin(pin)
                                            stateHolder.selectTab(WorkbenchTab.AGENT)
                                        },
                                    )
                                } else if (filesTabContent != null) {
                                    filesTabContent(fileList) { path ->
                                        openFilePath = path
                                        onOpenFile(path)
                                    }
                                } else {
                                    FilesListTabContent(
                                        files = fileList,
                                        onOpenFile = { path ->
                                            openFilePath = path
                                            onOpenFile(path)
                                        },
                                        onAttachToComposer = { path ->
                                            stateHolder.attachFile(path)
                                            stateHolder.selectTab(WorkbenchTab.AGENT)
                                        },
                                    )
                                }
                            }

                            WorkbenchTab.DIFF_CI -> DiffCiTabContent(
                                changes = fileChanges,
                                onRevertFile = { relPath ->
                                    scope.launch(Dispatchers.IO) {
                                        workspaceFiles?.revertFile(relPath)
                                        val updated = workspaceFiles?.getChanges().orEmpty()
                                        withContext(Dispatchers.Main) { fileChanges = updated }
                                    }
                                },
                                onUndo = if (snapshotEngine != null) {
                                    { runSnapshotAction { undo() } }
                                } else {
                                    null
                                },
                                onRedo = if (snapshotEngine != null) {
                                    { runSnapshotAction { redo() } }
                                } else {
                                    null
                                },
                            )

                            WorkbenchTab.TERMINAL -> terminalTabContent()
                            WorkbenchTab.BROWSER -> browserTabContent()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reusable Mission Control panel (used inside the drawer on phones and as the
 * permanent left pane in [dev.codex.android.desktop.DesktopWorkstation]).
 */
@Composable
fun MissionControlPanel(
    threads: List<ThreadEntity>,
    activeThreadId: String,
    subagents: List<SubagentInfo>,
    pendingApprovals: List<PendingApproval>,
    costStats: CostStatsSummary,
    onSelectThread: (String) -> Unit,
    onAnswerApproval: (approvalId: String, approved: Boolean, allowForSession: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reviewThreads = remember(threads) {
        threads.filter { it.status == "needs_review" || it.unread }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .background(OledSurface)
            .padding(12.dp)
            .testTag("mission_control_panel"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "MISSION CONTROL",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = OledAccentCyan,
            )
        }

        // 1. Amber Approval Queue
        item {
            SectionHeader("APPROVAL QUEUE (${pendingApprovals.size})", color = OledAmber)
            if (pendingApprovals.isEmpty()) {
                Text(
                    text = "No pending approvals.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("mission_approvals_empty"),
                )
            }
        }
        items(pendingApprovals, key = { it.approvalId }) { approval ->
            Card(
                colors = CardDefaults.cardColors(containerColor = OledAmberContainer),
                border = BorderStroke(1.dp, OledAmber),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("mission_approval_${approval.approvalId}"),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = approval.toolName,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = OledAmber,
                    )
                    Text(
                        text = approval.summary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (approval.payloadPreview.isNotBlank()) {
                        Text(
                            text = approval.payloadPreview.take(140),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { onAnswerApproval(approval.approvalId, true, false) }) {
                            Text("Allow", color = OledGreen)
                        }
                        TextButton(onClick = { onAnswerApproval(approval.approvalId, true, true) }) {
                            Text("Session", color = OledAccentCyan)
                        }
                        TextButton(onClick = { onAnswerApproval(approval.approvalId, false, false) }) {
                            Text("Deny", color = OledRed)
                        }
                    }
                }
            }
        }

        // 2. Parallel Threads
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(6.dp))
            SectionHeader("PARALLEL THREADS (${threads.size})")
            if (threads.isEmpty()) {
                Text(
                    text = "No threads yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(threads, key = { it.id }) { thread ->
            val selected = thread.id == activeThreadId
            Surface(
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else OledSurfaceElevated,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectThread(thread.id) }
                    .testTag("mission_thread_${thread.id}"),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = thread.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${thread.mode} · ${thread.status}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 3. Subagent Tree (fed by H4 SubagentOrchestrator)
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(6.dp))
            SectionHeader("SUBAGENT TREE (${subagents.size})")
            if (subagents.isEmpty()) {
                Text(
                    text = "No active subagents.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("mission_subagents_empty"),
                )
            }
        }
        items(subagents, key = { it.id }) { sub ->
            Surface(
                color = OledSurfaceElevated,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("mission_subagent_${sub.id}"),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "└─ ${sub.id} [${sub.status}]",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                        color = OledAccentCyan,
                    )
                    Text(
                        text = sub.task,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 4. Review Inbox
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(6.dp))
            SectionHeader("REVIEW INBOX (${reviewThreads.size})")
            if (reviewThreads.isEmpty()) {
                Text(
                    text = "Inbox clear.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(reviewThreads, key = { "review_${it.id}" }) { thread ->
            TextButton(
                onClick = { onSelectThread(thread.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "• ${thread.title}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 5. Cost Stats (from CostTelemetry.totalsAll())
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(6.dp))
            SectionHeader("COST TELEMETRY")
            Card(
                colors = CardDefaults.cardColors(containerColor = OledSurfaceElevated),
                modifier = Modifier.fillMaxWidth().testTag("mission_cost_stats"),
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Spend: $${"%.4f".format(costStats.costUsd)}",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = OledGreen,
                    )
                    Text(
                        text = "Calls: ${costStats.calls} · Tokens: ${costStats.totalTokens}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "In: ${costStats.inputTokens} · Out: ${costStats.outputTokens}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/**
 * Reusable Agent tab content (timeline + artifacts + Power Composer).
 * Also composed directly into the right pane of DesktopWorkstation (H6a).
 */
@Composable
fun AgentTabContent(
    threadId: String,
    messages: List<TimelineMessage>,
    artifacts: List<Artifact>,
    pendingComments: List<InlineComment>,
    streamingText: String,
    statusText: String,
    uiState: WorkbenchUiState,
    isRunning: Boolean,
    workspaceFiles: List<String>,
    stateHolder: WorkbenchStateHolder,
    onAddInlineComment: (artifactId: String, targetId: String, text: String) -> Unit,
    onSteerWithComments: () -> Unit,
    onSendPrompt: (prompt: String, mode: ComposerMode, attachedFiles: List<String>) -> Boolean,
    attachmentControls: (@Composable (enabled: Boolean) -> Unit)? = null,
    onSteer: (String) -> Unit,
    onStop: () -> Unit,
    onRunShellCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (statusText.isNotBlank()) {
            Surface(color = OledSurfaceElevated, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = OledAccentCyan,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .testTag("workbench_timeline"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (artifacts.isNotEmpty()) {
                item {
                    ArtifactsListPanel(
                        artifacts = artifacts,
                        pendingComments = pendingComments,
                        onAddInlineComment = onAddInlineComment,
                        onSteerWithComments = onSteerWithComments,
                    )
                }
            }

            if (messages.isEmpty() && streamingText.isBlank()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Ready.",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("workbench_empty_agent"),
                        )
                    }
                }
            }

            items(messages) { msg ->
                val showMessage = when (msg.kind) {
                    "thinking" -> uiState.showThinking
                    "tool_call" -> uiState.showDetails
                    else -> true
                }
                if (showMessage) {
                    Surface(
                        color = if (msg.role == "user") MaterialTheme.colorScheme.primaryContainer else OledSurfaceElevated,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "${msg.role.uppercase()} · ${msg.kind}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SelectionContainer {
                                Text(
                                    text = msg.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = if (msg.kind == "tool_call" || msg.kind == "diff") {
                                        FontFamily.Monospace
                                    } else {
                                        FontFamily.Default
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (streamingText.isNotBlank()) {
                item {
                    Surface(
                        color = OledSurfaceElevated,
                        border = BorderStroke(1.dp, OledAccentCyan),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = streamingText,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }

        attachmentControls?.invoke(!isRunning)
        PowerComposer(
            uiState = uiState,
            isRunning = isRunning,
            workspaceFiles = workspaceFiles,
            stateHolder = stateHolder,
            onSend = { prompt ->
                if (prompt.trimStart().startsWith("!")) {
                    val cmd = prompt.trimStart().removePrefix("!").trim()
                    if (cmd.isNotEmpty()) { onRunShellCommand(cmd); stateHolder.clearComposer() }
                } else if (onSendPrompt(prompt, uiState.composerMode, uiState.attachedFiles)) {
                    stateHolder.clearComposer()
                }
            },
            onSteer = { instruction ->
                if (instruction.isNotBlank()) {
                    onSteer(instruction.trim())
                    stateHolder.clearComposer()
                }
            },
            onStop = onStop,
        )
    }
}

/**
 * Verifiable Artifacts panel with tap-to-comment inline commenting.
 * Reusable in both WorkbenchScreen and DesktopWorkstation's Artifacts sub-tab.
 */
@Composable
fun ArtifactsListPanel(
    artifacts: List<Artifact>,
    pendingComments: List<InlineComment>,
    onAddInlineComment: (artifactId: String, targetId: String, text: String) -> Unit,
    onSteerWithComments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var commentTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var commentDraft by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("workbench_artifacts_panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (pendingComments.isNotEmpty()) {
            Surface(
                color = OledAmberContainer,
                border = BorderStroke(1.dp, OledAmber),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${pendingComments.size} inline comment(s) pending",
                        color = OledAmber,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onSteerWithComments,
                        modifier = Modifier.testTag("workbench_steer_comments"),
                    ) {
                        Text("Steer Agent", color = OledAmber)
                    }
                }
            }
        }

        for (artifact in artifacts) {
            Card(
                colors = CardDefaults.cardColors(containerColor = OledSurfaceElevated),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth().testTag("artifact_card_${artifact.id}"),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = artifact.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = artifact.status.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = OledAccentCyan,
                        )
                    }
                    Text(
                        text = renderArtifactMarkdown(artifact),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val targetIds = when (artifact) {
                        is TaskChecklist -> artifact.items.map { it.id }
                        is ImplementationPlan -> artifact.steps.map { it.id }
                        is Walkthrough -> artifact.steps.map { it.id }
                        is Diff -> artifact.items.map { it.id }
                    }.ifEmpty { listOf("root") }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        targetIds.take(4).forEach { targetId ->
                            OutlinedButton(
                                onClick = {
                                    commentTarget = artifact.id to targetId
                                    commentDraft = ""
                                },
                                modifier = Modifier.testTag("artifact_comment_${artifact.id}_$targetId"),
                            ) {
                                Text("Comment $targetId", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    val activeTarget = commentTarget
    if (activeTarget != null) {
        AlertDialog(
            onDismissRequest = { commentTarget = null },
            title = { Text("Inline comment (${activeTarget.second})") },
            text = {
                OutlinedTextField(
                    value = commentDraft,
                    onValueChange = { commentDraft = it },
                    placeholder = { Text("Tell the agent what to adjust…") },
                    modifier = Modifier.fillMaxWidth().testTag("artifact_comment_input"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (commentDraft.isNotBlank()) {
                            onAddInlineComment(activeTarget.first, activeTarget.second, commentDraft.trim())
                        }
                        commentTarget = null
                    },
                    modifier = Modifier.testTag("artifact_comment_save"),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { commentTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * OpenCode-style mobile Power Composer:
 * Build/Plan one-tap toggle, @file fuzzy attach, @agent mention, !cmd shortcut,
 * / slash palette, Thinking/Details toggles, context % meter, Stop & Steer buttons.
 */
@Composable
fun PowerComposer(
    uiState: WorkbenchUiState,
    isRunning: Boolean,
    workspaceFiles: List<String>,
    stateHolder: WorkbenchStateHolder,
    onSend: (String) -> Unit,
    onSteer: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = OledSurface,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Contextual autocomplete palette
            when (uiState.activeTrigger.kind) {
                ComposerTriggerKind.SLASH_COMMAND -> {
                    val matches = remember(uiState.activeTrigger.query) {
                        filterSlashPalette(uiState.activeTrigger.query).take(5)
                    }
                    if (matches.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(OledSurfaceElevated, RoundedCornerShape(8.dp))
                                .padding(6.dp)
                                .testTag("workbench_slash_palette"),
                        ) {
                            matches.forEach { meta ->
                                Text(
                                    text = "${meta.usage} — ${meta.description}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { stateHolder.applyCompletion("/${meta.name}") }
                                        .padding(vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                ComposerTriggerKind.AGENT_MENTION -> {
                    val agents = remember(uiState.activeTrigger.query) {
                        filterAgentMentions(DEFAULT_SUBAGENT_MENTIONS, uiState.activeTrigger.query)
                    }
                    if (agents.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .testTag("workbench_agent_palette"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            agents.forEach { agent ->
                                FilterChip(
                                    selected = false,
                                    onClick = { stateHolder.applyCompletion("@$agent") },
                                    label = { Text("@$agent", fontFamily = FontFamily.Monospace) },
                                )
                            }
                        }
                    }
                }

                ComposerTriggerKind.FILE_ATTACH -> {
                    val matches = remember(workspaceFiles, uiState.activeTrigger.query) {
                        fuzzyFilterFiles(workspaceFiles, uiState.activeTrigger.query, limit = 5)
                    }
                    if (matches.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(OledSurfaceElevated, RoundedCornerShape(8.dp))
                                .padding(6.dp)
                                .testTag("workbench_file_palette"),
                        ) {
                            matches.forEach { path ->
                                Text(
                                    text = "@$path",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            stateHolder.applyCompletion("@$path", attachAsFile = path)
                                        }
                                        .padding(vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                ComposerTriggerKind.SHELL_COMMAND -> {
                    Text(
                        text = "! Shell command",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = OledAmber,
                        modifier = Modifier.testTag("workbench_shell_hint"),
                    )
                }

                ComposerTriggerKind.NONE -> Unit
            }

            // Attached @file chips
            if (uiState.attachedFiles.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    uiState.attachedFiles.forEach { file ->
                        FilterChip(
                            selected = true,
                            onClick = { stateHolder.removeAttachedFile(file) },
                            label = { Text("@$file ×", fontFamily = FontFamily.Monospace) },
                        )
                    }
                }
            }

            // Utility strip: Build/Plan pill + Thinking/Details toggles + context % meter
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = uiState.composerMode == ComposerMode.PLAN,
                    onClick = { stateHolder.toggleComposerMode() },
                    modifier = Modifier.testTag("workbench_mode_toggle"),
                    label = {
                        Text(
                            text = uiState.composerMode.label,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                )
                FilterChip(
                    selected = uiState.showThinking,
                    onClick = { stateHolder.toggleThinking() },
                    modifier = Modifier.testTag("workbench_toggle_thinking"),
                    label = { Text("Thinking", fontFamily = FontFamily.Monospace) },
                )
                FilterChip(
                    selected = uiState.showDetails,
                    onClick = { stateHolder.toggleDetails() },
                    modifier = Modifier.testTag("workbench_toggle_details"),
                    label = { Text("Details", fontFamily = FontFamily.Monospace) },
                )
                Column(
                    modifier = Modifier
                        .width(92.dp)
                        .padding(start = 4.dp)
                        .testTag("workbench_context_meter"),
                ) {
                    Text(
                        text = "CTX ${uiState.contextPercent}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (uiState.contextPercent >= 80) OledAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { uiState.contextPercent / 100f },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = if (uiState.contextPercent >= 80) OledAmber else OledAccentCyan,
                    )
                }
            }

            // Main input + Stop & Steer/Send controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = uiState.composerText,
                    onValueChange = { stateHolder.updateComposerText(it) },
                    placeholder = {
                        Text(
                            text = if (isRunning) "Steer active run…" else "Prompt, @file, @agent, !cmd, /slash…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    maxLines = 4,
                    modifier = Modifier.weight(1f).testTag("workbench_composer"),
                )
                OutlinedButton(
                    onClick = onStop,
                    enabled = isRunning,
                    modifier = Modifier.testTag("workbench_stop"),
                ) {
                    Text("Stop", color = if (isRunning) OledRed else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = {
                        if (isRunning) onSteer(uiState.composerText) else onSend(uiState.composerText)
                    },
                    enabled = uiState.composerText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) OledAmber else OledAccentCyan,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier.testTag(if (isRunning) "workbench_steer" else "workbench_send"),
                ) {
                    Text(if (isRunning) "Steer" else "Run")
                }
            }
        }
    }
}

/**
 * Files-tab editor view: the real Sora editor for the tapped file, with
 * Back to the file list and Undo/Redo (backed by [SnapshotEngine])
 * above it. `editorVersion` is a recompose key: bumping it recreates
 * the editor so it reloads the file from disk (e.g. after undo/redo).
 */
@Composable
private fun EditorTabContent(
    file: File,
    relativePath: String,
    proot: ProotShellEngine?,
    snapshotEngine: SnapshotEngine?,
    editorVersion: Int,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSaved: () -> Unit,
    onPinToComposer: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(OledSurface).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.testTag("workbench_editor_back"),
            ) {
                Text("← Files", fontFamily = FontFamily.Monospace)
            }
            if (snapshotEngine != null) {
                TextButton(
                    onClick = onUndo,
                    modifier = Modifier.testTag("workbench_editor_undo"),
                ) {
                    Text("Undo", fontFamily = FontFamily.Monospace)
                }
                TextButton(
                    onClick = onRedo,
                    modifier = Modifier.testTag("workbench_editor_redo"),
                ) {
                    Text("Redo", fontFamily = FontFamily.Monospace)
                }
            }
        }
        key(editorVersion) {
            SoraEditorScreen(
                file = file,
                onSave = { _, _ -> onSaved() },
                relativePath = relativePath,
                proot = proot,
                onPinToComposer = onPinToComposer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FilesListTabContent(
    files: List<String>,
    onOpenFile: (String) -> Unit,
    onAttachToComposer: (String) -> Unit,
) {
    if (files.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp).testTag("workbench_files_empty"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No files in the active workspace yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp).testTag("workbench_files_list"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(files) { path ->
            Surface(
                color = OledSurfaceElevated,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().clickable { onOpenFile(path) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = path,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onAttachToComposer(path) }) {
                        Text("@Attach", fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

/** Reusable Diff & CI tab content (also composed inside DesktopWorkstation centre pane). */
@Composable
fun DiffCiTabContent(
    changes: List<FileChangeSummary>,
    onRevertFile: (String) -> Unit,
    onUndo: (() -> Unit)? = null,
    onRedo: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (onUndo != null || onRedo != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onUndo != null) {
                    TextButton(
                        onClick = onUndo,
                        modifier = Modifier.testTag("workbench_diff_undo"),
                    ) {
                        Text("Undo", fontFamily = FontFamily.Monospace)
                    }
                }
                if (onRedo != null) {
                    TextButton(
                        onClick = onRedo,
                        modifier = Modifier.testTag("workbench_diff_redo"),
                    ) {
                        Text("Redo", fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        if (changes.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp).testTag("workbench_diff_empty"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Working tree clean.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp).testTag("workbench_diff_list"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(changes, key = { it.relativePath }) { change ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = OledSurfaceElevated),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${change.relativePath} (+${change.addedLines}/-${change.removedLines})",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { onRevertFile(change.relativePath) }) {
                                    Text("Revert", color = OledAmber)
                                }
                            }
                            Text(
                                text = change.unifiedDiff.take(2_000),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
