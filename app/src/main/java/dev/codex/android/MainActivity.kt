package dev.codex.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import dev.codex.android.artifacts.InlineCommentStore
import dev.codex.android.browser.VerificationBrowser
import dev.codex.android.context.ContextEngine
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.CostTelemetry
import dev.codex.android.data.TimelineMessage
import dev.codex.android.data.OnboardingPrefs
import dev.codex.android.hooks.HookEngine
import dev.codex.android.hooks.HookExecution
import dev.codex.android.kb.KnowledgeBase
import dev.codex.android.linux.ProotShellEngine
import dev.codex.android.linux.RootfsManager
import dev.codex.android.subagents.SubagentOrchestrator
import dev.codex.android.tools.WorkspaceFileTools
import dev.codex.android.ui.ChatScreen
import dev.codex.android.ui.ChatViewModel
import dev.codex.android.ui.HelpScreen
import dev.codex.android.ui.HistoryScreen
import dev.codex.android.ui.OnboardingScreen
import dev.codex.android.ui.PreviewScreen
import dev.codex.android.ui.SettingsScreen
import dev.codex.android.ui.ShareActivity
import dev.codex.android.ui.StoreScreen
import dev.codex.android.ui.WorkbenchScreen
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
class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()

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
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showOnboarding by remember {
                        mutableStateOf(!onboardingPrefs.isComplete())
                    }
                    var showSettings by remember { mutableStateOf(false) }
                    var showHistory by remember { mutableStateOf(false) }
                    var showStore by remember { mutableStateOf(false) }
                    var showPreview by remember { mutableStateOf(false) }
                    var showHelp by remember { mutableStateOf(false) }
                    var showWorkbench by remember { mutableStateOf(false) }
                    // One browser for the whole activity: the WebView is
                    // created on the main thread and shared by the
                    // Preview tab and the agent's browser tools.
                    val browser = remember { VerificationBrowser(applicationContext) }
                    // Publish the shared browser for onDestroy teardown.
                    SideEffect { browserRef = browser }
                    // Composition root for the workbench engines (M2/M4/M6):
                    // one workspace dir shared by the file tools, the
                    // context engine, the knowledge base and the hook
                    // engine, so all of them see the same workspace.
                    val workspaceDir = remember { File(filesDir, "workspace") }
                    val database = remember { CodexDatabase.getInstance(applicationContext) }
                    val costTelemetry = remember { CostTelemetry(applicationContext) }
                    val workspaceFiles = remember { WorkspaceFileTools(workspaceDir) }
                    val terminalEngine = remember {
                        ProotShellEngine(
                            rootfsDir = RootfsManager(applicationContext).rootfsDir(),
                            nativeLibDir = File(applicationInfo.nativeLibraryDir),
                            workspaceBindDir = workspaceDir,
                        )
                    }
                    val orchestrator = remember { SubagentOrchestrator(applicationContext, database) }
                    val subagents by orchestrator.subagents.collectAsState()
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
                    val workbenchMessages = chatMessages.mapIndexed { index, msg ->
                        TimelineMessage(
                            id = index.toLong(),
                            threadId = activeThreadId ?: "",
                            role = msg.role,
                            kind = if (msg.isError) "error" else "text",
                            content = msg.text,
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
                                onBack = { showHistory = false }
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
                        showSettings -> {
                            SettingsScreen(
                                viewModel = viewModel,
                                onBack = { showSettings = false },
                                onOpenHelp = { showHelp = true },
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
                                    onSelectThread = { threadId -> viewModel.openThread(threadId) },
                                    // ChatViewModel.sendGoal runs an AUTO turn
                                    // on the active thread; the composer's
                                    // BUILD/PLAN mode is not plumbed through
                                    // sendGoal (it takes no mode), so a
                                    // workbench prompt always starts a real
                                    // AUTO turn.
                                    onSendPrompt = { prompt, _, _ -> viewModel.sendGoal(prompt) },
                                    onOpenSettings = { showSettings = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                )
                            }
                        }
                        else -> {
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
