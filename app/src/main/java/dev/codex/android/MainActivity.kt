package dev.codex.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import dev.codex.android.agent.AgentRuntime
import dev.codex.android.artifacts.InlineCommentStore
import dev.codex.android.browser.VerificationBrowser
import dev.codex.android.context.ContextEngine
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.CostTelemetry
import dev.codex.android.data.KeyStore
import dev.codex.android.data.TimelineMessage
import dev.codex.android.data.OnboardingPrefs
import dev.codex.android.data.WorkspaceEntity
import dev.codex.android.desktop.DesktopLayout
import dev.codex.android.desktop.DesktopWorkstation
import dev.codex.android.hooks.HookEngine
import dev.codex.android.hooks.HookExecution
import dev.codex.android.kb.KnowledgeBase
import dev.codex.android.linux.ProotShellEngine
import dev.codex.android.linux.RootfsBootstrap
import dev.codex.android.linux.ShellResult
import dev.codex.android.mcp.JsonFileStdioConfigStore
import dev.codex.android.mcp.McpHub
import dev.codex.android.mcp.McpManager
import dev.codex.android.security.ExecutionMode
import dev.codex.android.server.CodexCli
import dev.codex.android.server.DaemonAgentRunnerFactory
import dev.codex.android.server.LocalDaemon
import dev.codex.android.subagents.SubagentOrchestrator
import dev.codex.android.terminal.TerminalStateHolder
import dev.codex.android.ui.ApprovalDialog
import dev.codex.android.ui.AttachmentControls
import dev.codex.android.ui.AutomationCreatorScreen
import dev.codex.android.ui.AutomationListScreen
import dev.codex.android.ui.ChatScreen
import dev.codex.android.ui.ChatViewModel
import dev.codex.android.ui.ComposerMode
import dev.codex.android.ui.HelpScreen
import dev.codex.android.ui.HistoryScreen
import dev.codex.android.ui.OnboardingScreen
import dev.codex.android.ui.PreviewScreen
import dev.codex.android.ui.ReviewInboxScreen
import dev.codex.android.ui.SettingsScreen
import dev.codex.android.ui.ShareActivity
import dev.codex.android.ui.SkillsScreen
import dev.codex.android.ui.StorageScreen
import dev.codex.android.ui.StoreScreen
import dev.codex.android.ui.WorkbenchScreen
import dev.codex.android.ui.WorkbenchStateHolder
import dev.codex.android.vcs.SnapshotEngine
import java.io.File
import java.util.UUID

/**
 * Phase 1 entry point: a chat UI wired to the agent loop, plus a settings
 * screen for the user's own API key (BYOK). No key is ever shipped.
 *
 * Phase 5: first launch shows the onboarding flow (gated on
 * [OnboardingPrefs]); onboarding is skippable on every step and never
 * blocks the chat. A History screen is reachable from the chat.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()

    private val localDaemon: LocalDaemon get() = PhoneDaemon.get(application)

    /**
     * Unified MCP hub (M8): registered at the DI root with the real
     * manager (KeyStore-backed bearer tokens via `mcp_token_<id>`),
     * the JSON stdio store, and the KeyStore token sink. No screen
     * consumes it yet — the MCP settings surface wires to this hub
     * when it lands; the hub is warmed in [onCreate] (IO) so its
     * stores are loaded and a misconfigured registry fails loudly
     * at startup instead of at first use.
     */
    private val mcpHub: McpHub by lazy {
        val database = CodexDatabase.getInstance(applicationContext)
        McpHub(
            mcpManager = McpManager(
                database,
                tokenProvider = { id ->
                    KeyStore.prefsFor(applicationContext).getString("mcp_token_$id", null)
                },
            ),
            agentRuntime = AgentRuntime,
            database = database,
            stdioStore = JsonFileStdioConfigStore(File(filesDir, "mcp-stdio-servers.json")),
            tokenSink = { id, token ->
                KeyStore.prefsFor(applicationContext).edit()
                    .putString("mcp_token_$id", token).apply()
            },
        )
    }

    /**
     * The shared browser, for teardown in [onDestroy]: its WebView must
     * be destroyed (the existing suspend VerificationBrowser.destroy(),
     * which also detaches the JS bridge) or its renderer process leaks
     * across configuration changes and activity restarts.
     */
    private var browserRef: VerificationBrowser? = null

    override fun onDestroy() {
        browserRef?.let { browser ->
            // UNDISPATCHED: lifecycleScope is cancelled when the lifecycle
            // reaches DESTROYED (right after super.onDestroy()), so a
            // normally-dispatched launch would be cancelled before it ever
            // runs. Undispatched starts on this (main) thread at once, and
            // destroy()'s withContext(Main) runs inline here — the WebView
            // is really torn down before the activity finishes destroying.
            lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                browser.destroy()
            }
        }
        browserRef = null
        super.onDestroy()
    }
    // Lazy: `application` is only set after attach(), i.e. after field
    // initialisers run, so an eager OnboardingPrefs(application) would NPE.
    private val onboardingPrefs by lazy { OnboardingPrefs(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Verifiable artifacts: inline comments persist under filesDir so
        // they survive process death; without this the store is memory-only.
        InlineCommentStore.configure(File(filesDir, "inline_comments"))
        // DI root (M8), all off Main: install the on-device `codex` CLI
        // into the rootfs (writes filesDir/linux/daemon.env, 0600, with
        // the daemon's per-boot token) and start the headless daemon;
        // warm the MCP hub; and make sure the phone workspace row exists
        // so daemon turns can create threads (FK discipline, same as
        // ChatViewModel.ensureThread). A failure here never crashes the
        // app: the chat still works, the daemon/CLI just stay down.
        lifecycleScope.launch(Dispatchers.IO) {
            val linuxDir = File(filesDir, "linux")
            // Prepare the host-side bind before the first shell can open.
            runCatching { CodexCli.writeDaemonEnv(linuxDir, localDaemon.bearerToken) }
            // Shell bootstrap must not block the Android-side daemon or workspace.
            launch {
                RootfsBootstrap.ensure(applicationContext)
                RootfsBootstrap.status.collect { status ->
                    if (status == RootfsBootstrap.Status.Ready) {
                        runCatching {
                            CodexCli.installIntoRootfs(
                                rootfsDir = RootfsBootstrap.manager(applicationContext).rootfsDir(),
                                linuxDir = linuxDir,
                                bearerToken = localDaemon.bearerToken,
                                port = LocalDaemon.DEFAULT_PORT,
                            )
                        }
                    }
                }
            }
            runCatching {
                localDaemon.start()
                mcpHub.listMcpServers()
                CodexDatabase.getInstance(applicationContext).upsertWorkspace(
                    WorkspaceEntity(
                        id = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID,
                        name = "This Phone",
                        kind = "phone",
                        rootPath = "",
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Window-size routing (M10): Expanded (tablet/DeX)
                    // shows the DesktopWorkstation, anything narrower
                    // keeps the existing ChatScreen flow.
                    val windowSizeClass = calculateWindowSizeClass(this)
                    var showOnboarding by remember {
                        mutableStateOf(!onboardingPrefs.isComplete())
                    }
                    var showSettings by rememberSaveable { mutableStateOf(false) }
                    var showSkills by remember { mutableStateOf(false) }
                    var showStorage by remember { mutableStateOf(false) }
                    var showAutomations by remember { mutableStateOf(false) }
                    var createAutomation by remember { mutableStateOf(false) }
                    var showReviewInbox by remember { mutableStateOf(false) }
                    var showHistory by remember { mutableStateOf(false) }
                    var showStore by remember { mutableStateOf(false) }
                    var showPreview by remember { mutableStateOf(false) }
                    var showHelp by remember { mutableStateOf(false) }
                    var showWorkbench by remember { mutableStateOf(false) }
                    // One browser for the whole activity: the WebView is
                    // created on the main thread and shared by the
                    // Preview tab and the agent's browser tools.
                    val browser = remember { VerificationBrowser(applicationContext) }
                    DisposableEffect(browser) {
                        viewModel.attachBrowser(browser)
                        onDispose { viewModel.attachBrowser(null) }
                    }
                    // Publish the shared browser for onDestroy teardown.
                    SideEffect { browserRef = browser }
                    // Composition root for the workbench engines (M2/M4/M6):
                    // one workspace dir shared by the file tools, the
                    // context engine, the knowledge base and the hook
                    // engine, so all of them see the same workspace.
                    val workspaceDir = remember { File(filesDir, "workspace") }
                    val database = remember { CodexDatabase.getInstance(applicationContext) }
                    val costTelemetry = remember { CostTelemetry(applicationContext) }
                    // Shared workbench state: one holder for the whole
                    // activity, so the workbench branch and the desktop
                    // workstation branch show the same tabs/composer state.
                    val workbenchStateHolder = remember { WorkbenchStateHolder() }
                    val terminalStateHolder = remember { TerminalStateHolder() }
                    val terminalEngine = remember {
                        ProotShellEngine(
                            rootfsDir = RootfsBootstrap.manager(applicationContext).rootfsDir(),
                            nativeLibDir = File(applicationInfo.nativeLibraryDir),
                            workspaceBindDir = workspaceDir,
                            // daemon.env extra bind (M8): the on-device
                            // `codex` CLI reads its port + token from
                            // /etc/codex/daemon.env inside the guest.
                            // onCreate prepares the file on every boot;
                            // the engine can be constructed before it lands.
                            extraBinds = listOf(
                                File(filesDir, "linux/daemon.env").absolutePath to "/etc/codex/daemon.env",
                            ),
                        )
                    }
                    val workspaceFiles = remember { viewModel.workspaceFileTools }
                    // Snapshot engine (H3): one per workspace, baseline
                    // captured on Dispatchers.IO below — never on Main.
                    val snapshotEngine = remember { SnapshotEngine(workspaceDir, filesDir, true) }
                    LaunchedEffect(snapshotEngine) {
                        withContext(Dispatchers.IO) { snapshotEngine.initialize() }
                    }
                    val orchestrator = remember { SubagentOrchestrator(applicationContext, database) }
                    val subagents by orchestrator.subagents.collectAsState()
                    // Hand the activity-owned orchestrator + MCP hub to the
                    // ViewModel so the chat registry can offer the subagent
                    // and MCP tools (chat-only registrations). Runs after
                    // every composition; setting the same refs is harmless.
                    SideEffect {
                        viewModel.subagentOrchestrator = orchestrator
                        viewModel.mcpHub = mcpHub
                    }
                    @Suppress("UNUSED_VARIABLE")
                    val contextEngine = remember { ContextEngine(database, workspaceDir) }
                    @Suppress("UNUSED_VARIABLE")
                    val knowledgeBase = remember { KnowledgeBase(database, applicationContext) }
                    @Suppress("UNUSED_VARIABLE")
                    val hookEngine = remember {
                        HookEngine(HookEngine.hooksFileFor(workspaceDir)) { command, env, timeoutMs ->
                            // Hooks run inside the Linux runtime: the
                            // executor delegates to PRoot and maps the
                            // result back. hookId is empty here because
                            // the executor layer has no hook identity;
                            // HookEngine fills it on its own error and
                            // timeout records.
                            val started = System.currentTimeMillis()
                            val result = terminalEngine.shellExecWithEnv(command, env, timeoutMs)
                            HookExecution(
                                id = "exec_${UUID.randomUUID().toString().take(8)}",
                                hookId = "",
                                event = env["CODEX_HOOK_EVENT"].orEmpty(),
                                command = command,
                                exitCode = result.exitCode,
                                stdout = result.stdout,
                                stderr = result.stderr,
                                durationMs = System.currentTimeMillis() - started,
                                timedOut = result.timedOut,
                            )
                        }
                    }
                    val chatMessages by viewModel.messages.collectAsState()
                    val activeThreadId by viewModel.activeThreadId.collectAsState()
                    // Context meter: the workbench shows a live CTX %
                    // fed by the ViewModel's per-run estimate.
                    // updateContextUsage used to have no caller, so the
                    // meter was stuck at 0%.
                    val contextUsage by viewModel.contextUsage.collectAsState()
                    LaunchedEffect(contextUsage) {
                        workbenchStateHolder.updateContextUsage(contextUsage.first, contextUsage.second)
                    }
                    val workbenchMessages = chatMessages.mapIndexed { index, msg ->
                        TimelineMessage(
                            id = index.toLong(),
                            threadId = activeThreadId ?: "",
                            role = msg.role,
                            kind = if (msg.artifactPath != null) "artifact" else if (msg.isError) "error" else "text",
                            content = msg.artifactPath ?: msg.text,
                            createdAt = System.currentTimeMillis(),
                        )
                    }
                    when {
                        showOnboarding -> {
                            OnboardingScreen(
                                onFinish = {
                                    onboardingPrefs.setComplete()
                                    showOnboarding = false
                                }
                            )
                        }
                        showHistory -> {
                            HistoryScreen(
                                viewModel = viewModel,
                                onBack = { showHistory = false },
                                // Tapping a row opens its thread, then
                                // returns to the chat view.
                                onOpenThread = { entry ->
                                    viewModel.openThread(entry.threadId)
                                    showHistory = false
                                }
                            )
                        }
                        showStore -> {
                            StoreScreen(
                                onBack = { showStore = false }
                            )
                        }
                        showPreview -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                TextButton(onClick = { showPreview = false }) {
                                    Text("Back")
                                }
                                PreviewScreen(
                                    browser = browser,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                )
                            }
                        }
                        showHelp -> {
                            HelpScreen(
                                onBack = { showHelp = false },
                            )
                        }
                        createAutomation -> AutomationCreatorScreen(
                            database = database,
                            onSaved = { createAutomation = false },
                            onBack = { createAutomation = false },
                        )
                        showReviewInbox -> ReviewInboxScreen(
                            database = database,
                            onBack = { showReviewInbox = false },
                            onThreadClick = { id ->
                                viewModel.openThread(id)
                                showReviewInbox = false
                                showAutomations = false
                                showSettings = false
                            },
                        )
                        showAutomations -> AutomationListScreen(
                            database = database,
                            onCreateClick = { createAutomation = true },
                            onOpenReview = { showReviewInbox = true },
                            onBack = { showAutomations = false },
                        )
                        // showSkills is checked BEFORE showSettings: opening
                        // Skills from Settings leaves showSettings true, so
                        // the Skills branch must win or it never shows.
                        showSkills -> {
                            SkillsScreen(
                                onBack = { showSkills = false },
                            )
                        }
                        // showStorage is checked BEFORE showSettings for the
                        // same reason: it is opened from Settings.
                        showStorage -> {
                            StorageScreen(
                                workspace = workspaceFiles,
                                fileLibrary = viewModel.fileLibrary,
                                rootfsManager = RootfsBootstrap.manager(applicationContext),
                                shell = { command ->
                                    if (!RootfsBootstrap.ensure(applicationContext)) {
                                        ShellResult(127, "", "Linux setup failed. Open Workbench → Terminal and tap Retry.")
                                    } else {
                                        terminalEngine.shellExec(command)
                                    }
                                },
                                onBack = { showStorage = false },
                                onReinstallLinux = { RootfsBootstrap.ensure(applicationContext, retry = true) },
                            )
                        }
                        showSettings -> {
                            SettingsScreen(
                                viewModel = viewModel,
                                onBack = { showSettings = false },
                                onOpenHelp = { showHelp = true },
                                onOpenAutomations = { showAutomations = true },
                                onOpenSkills = { showSkills = true },
                                onOpenStorage = { showStorage = true },
                            )
                        }
                        showWorkbench -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                TextButton(onClick = { showWorkbench = false }) {
                                    Text("Back")
                                }
                                WorkbenchScreen(
                                    database = database,
                                    costTelemetry = costTelemetry,
                                    workspaceFiles = workspaceFiles,
                                    terminalEngine = terminalEngine,
                                    browser = browser,
                                    activeThreadId = activeThreadId ?: "thr_default",
                                    messages = workbenchMessages,
                                    subagents = subagents,
                                    stateHolder = workbenchStateHolder,
                                    terminalStateHolder = terminalStateHolder,
                                    snapshotEngine = snapshotEngine,
                                    onSelectThread = { threadId -> viewModel.openThread(threadId) },
                                    onSendPrompt = { prompt, mode, files -> viewModel.sendGoal(prompt, mode.toExecutionMode(), files) },
                                    attachmentControls = { enabled -> AttachmentControls(viewModel, enabled) },
                                    onOpenSettings = { showSettings = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                )
                            }
                        }
                        else -> {
                            if (DesktopLayout.isDesktopClass(windowSizeClass.widthSizeClass)) {
                                DesktopWorkstation(
                                    windowSizeClass = windowSizeClass,
                                    browser = browser,
                                    subagents = subagents,
                                    database = database,
                                    costTelemetry = costTelemetry,
                                    workspaceFiles = workspaceFiles,
                                    terminalEngine = terminalEngine,
                                    activeThreadId = activeThreadId ?: "thr_default",
                                    messages = workbenchMessages,
                                    stateHolder = workbenchStateHolder,
                                    terminalStateHolder = terminalStateHolder,
                                    snapshotEngine = snapshotEngine,
                                    onSelectThread = { threadId -> viewModel.openThread(threadId) },
                                    onSendPrompt = { prompt, mode, files -> viewModel.sendGoal(prompt, mode.toExecutionMode(), files) },
                                    attachmentControls = { enabled -> AttachmentControls(viewModel, enabled) },
                                    onOpenSettings = { showSettings = true },
                                )
                            } else {
                                ChatScreen(
                                    viewModel = viewModel,
                                    onOpenSettings = { showSettings = true },
                                    onOpenHistory = { showHistory = true },
                                    onOpenStore = { showStore = true },
                                    onOpenPreview = { showPreview = true },
                                    onOpenWorkbench = { showWorkbench = true },
                                )
                            }
                        }
                    }
                    val pendingApproval by viewModel.pendingApproval.collectAsState()
                    pendingApproval?.let { approval ->
                        ApprovalDialog(
                            approval,
                            onApprove = { AgentRuntime.answerApproval(applicationContext, approval.approvalId, true) },
                            onDeny = { AgentRuntime.answerApproval(applicationContext, approval.approvalId, false) },
                        )
                    }
                }
            }
        }
        handleSharedThread(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedThread(intent)
    }

    /**
     * Share-to-Codex / QS tile entry point: an intent carrying
     * [ShareActivity.EXTRA_THREAD_ID] opens that thread in the chat.
     * MainActivity is standard launch mode, so a share normally creates
     * a fresh instance (handled in onCreate); onNewIntent covers a
     * re-delivery to an existing instance.
     */
    private fun handleSharedThread(intent: Intent?) {
        val threadId = intent?.getStringExtra(ShareActivity.EXTRA_THREAD_ID)
            ?: return
        viewModel.openThread(threadId)
    }
}

private fun ComposerMode.toExecutionMode(): ExecutionMode =
    if (this == ComposerMode.PLAN) ExecutionMode.PLAN else ExecutionMode.AUTO

/** Keep one port and bearer token when Android recreates the activity. */
private object PhoneDaemon {
    private var instance: LocalDaemon? = null

    @Synchronized
    fun get(app: android.app.Application): LocalDaemon = instance ?: LocalDaemon(
        app,
        CodexDatabase.getInstance(app),
        runnerFactory = DaemonAgentRunnerFactory(app),
    ).also { instance = it }
}
