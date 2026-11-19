package dev.codex.android.terminal

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.codex.android.linux.ProotShellEngine
import dev.codex.android.linux.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Terminal palette, defined locally in this file.
 *
 * ADAPTATION: the staged screen imported `OledBlack`, `OledSurface`,
 * `OledSurfaceElevated`, `OledAmber`, `OledAccentCyan` from
 * `dev.codex.android.ui` — none of those symbols exist anywhere in the real
 * repo (verified at HEAD 4a3d4e8; the repo's screens use
 * `MaterialTheme.colorScheme` only). The staged file would not compile.
 * The palette is defined here instead so this screen is self-contained and
 * no existing file needs to change.
 */
private val TerminalBlack = Color(0xFF000000)
private val TerminalSurface = Color(0xFF111111)
private val TerminalSurfaceElevated = Color(0xFF1C1C1C)
private val TerminalAmber = Color(0xFFFFB000)
private val TerminalCyan = Color(0xFF00E5FF)
private val TerminalGreen = Color(0xFF4ADE80)
private val TerminalMutedText = Color(0xFF9CA3AF)

/**
 * Live Multi-Tab VT100 PTY Terminal tab (Milestone M4).
 *
 * - VT100 emulation & view: Apache-2.0 Termux `terminal-emulator` + `terminal-view` ONLY.
 * - Extra-keys touch bar: written cleanly from scratch in Jetpack Compose
 *   (NEVER copied from GPL-3.0 `termux-app`).
 * - Composer `!command` shortcut helper: [runBangCommand] executes directly via
 *   [ProotShellEngine.shellExec].
 *
 * NOTE: every `com.termux.*` call in this file (TerminalSession constructor,
 * `.pid`, `finishIfRunning()`, `write()`, TerminalView constructor /
 * `setTextSize` / `attachSession` / `currentSession` / `onScreenUpdated()`,
 * and every `TerminalSessionClient` / `TerminalViewClient` override) could
 * NOT be verified against the published AAR from this VM — see NOTES.md §5
 * for the exact unverified list. The staged calls are kept as-is; the
 * integrator must confirm them against the resolved AAR at first Gradle sync.
 */
suspend fun runBangCommand(
    engine: ProotShellEngine,
    rawComposerInput: String,
    timeoutMs: Long = 60_000L,
): ShellResult? {
    val cmd = Vt100KeyEncoder.parseBangCommand(rawComposerInput) ?: return null
    return engine.shellExec(cmd, timeoutMs = timeoutMs)
}

@Composable
fun TerminalScreen(
    engine: ProotShellEngine,
    stateHolder: TerminalStateHolder = remember { TerminalStateHolder() },
    onBangCommandResult: ((command: String, result: ShellResult) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val uiState by stateHolder.state.collectAsState()
    val scope = rememberCoroutineScope()
    var quickCmd by remember { mutableStateOf("") }
    var terminalViewRef by remember { mutableStateOf<TerminalView?>(null) }
    // Pinch-zoom text size, tracked here so onScale can apply it to the
    // live view (the view itself is the source of truth after first set).
    val textSizeState = remember { mutableStateOf(28) }

    val sessionClient = remember(stateHolder) {
        object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                terminalViewRef?.onScreenUpdated()
            }

            override fun onTitleChanged(changedSession: TerminalSession) = Unit

            override fun onSessionFinished(finishedSession: TerminalSession) {
                val matched = engine.listSessions().firstOrNull { it.terminalSession === finishedSession }
                matched?.let { stateHolder.markSessionExited(it.id) }
            }

            override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
            override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
            override fun onBell(session: TerminalSession) = Unit
            override fun onColorsChanged(session: TerminalSession) = Unit
            override fun onTerminalCursorStateChange(state: Boolean) = Unit
            override fun getTerminalCursorStyle(): Int = 0
            override fun logError(tag: String?, message: String?) = Unit
            override fun logWarn(tag: String?, message: String?) = Unit
            override fun logInfo(tag: String?, message: String?) = Unit
            override fun logDebug(tag: String?, message: String?) = Unit
            override fun logVerbose(tag: String?, message: String?) = Unit
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
            override fun logStackTrace(tag: String?, e: Exception?) = Unit
        }
    }

    val viewClient = remember(stateHolder) {
        object : TerminalViewClient {
            // Pinch zoom actually resizes the terminal text: returning the
            // coerced scale without applying it left the text unchanged.
            override fun onScale(scale: Float): Float {
                val view = terminalViewRef ?: return scale
                val current = textSizeState.value
                val newSize = (current * scale).toInt().coerceIn(10, 60)
                if (newSize == current) return 1f
                textSizeState.value = newSize
                view.setTextSize(newSize)
                return newSize.toFloat() / current
            }
            // A tap focuses the terminal and raises the keyboard: with an
            // empty handler the view never received key input.
            override fun onSingleTapUp(e: MotionEvent?) {
                val view = terminalViewRef ?: return
                view.requestFocus()
                val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(view, 0)
            }
            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = true
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) = Unit
            override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
            override fun onLongPress(event: MotionEvent?): Boolean = false
            override fun readControlKey(): Boolean = stateHolder.state.value.ctrlLatched
            override fun readAltKey(): Boolean = stateHolder.state.value.altLatched
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
                val (ctrl, alt) = stateHolder.consumeLatchedModifiers()
                // The latch and the physically-held Ctrl are two sources
                // of the same modifier: union them. Previously a held Ctrl
                // with no latch fell through to the default path, which
                // writes the raw code point and drops the modifier.
                val useCtrl = ctrl || ctrlDown
                if (!useCtrl && !alt) return false
                val encoded = Vt100KeyEncoder.applyModifiers(
                    String(Character.toChars(codePoint)),
                    ctrl = useCtrl,
                    alt = alt,
                )
                session?.write(encoded)
                return true
            }
            override fun onEmulatorSet() = Unit
            override fun logError(tag: String?, message: String?) = Unit
            override fun logWarn(tag: String?, message: String?) = Unit
            override fun logInfo(tag: String?, message: String?) = Unit
            override fun logDebug(tag: String?, message: String?) = Unit
            override fun logVerbose(tag: String?, message: String?) = Unit
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
            override fun logStackTrace(tag: String?, e: Exception?) = Unit
        }
    }

    fun openNewTab() {
        val created = engine.startPtySession(
            cols = 80,
            rows = 24,
            sessionClient = sessionClient,
        )
        stateHolder.addTab(created.id, created.title)
    }

    // Ensure at least one PTY tab exists on first open.
    LaunchedEffect(engine) {
        val existing = engine.listSessions()
        if (existing.isEmpty()) {
            openNewTab()
        } else if (uiState.tabs.isEmpty()) {
            existing.forEach { stateHolder.addTab(it.id, it.title) }
        }
    }

    val activePty = remember(uiState.activeSessionId, uiState.tabs) {
        engine.listSessions().firstOrNull { it.id == uiState.activeSessionId }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .imePadding()
            .testTag("terminal_screen"),
    ) {
        // Multi-tab PTY header strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalSurface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            uiState.tabs.forEach { tab ->
                FilterChip(
                    selected = tab.sessionId == uiState.activeSessionId,
                    onClick = { stateHolder.selectTab(tab.sessionId) },
                    modifier = Modifier.testTag("terminal_tab_${tab.sessionId}"),
                    label = {
                        Text(
                            text = if (tab.isAlive) tab.title else "${tab.title} (exited)",
                            fontFamily = FontFamily.Monospace,
                        )
                    },
                )
            }
            OutlinedButton(
                onClick = ::openNewTab,
                modifier = Modifier.testTag("terminal_new_tab"),
            ) {
                Text("+ Tab", fontFamily = FontFamily.Monospace)
            }
            if (uiState.activeSessionId != null) {
                TextButton(
                    onClick = {
                        val id = uiState.activeSessionId ?: return@TextButton
                        engine.killSession(id)
                        stateHolder.closeTab(id)
                    },
                    modifier = Modifier.testTag("terminal_kill_tab"),
                ) {
                    Text("Kill", color = TerminalAmber, fontFamily = FontFamily.Monospace)
                }
            }
            TextButton(
                onClick = {
                    // /proc scanning is file I/O: never on the Main thread.
                    scope.launch(Dispatchers.IO) {
                        stateHolder.setPhantomReport(engine.diagnosePhantomProcesses())
                    }
                },
                modifier = Modifier.testTag("terminal_diagnose_phantom"),
            ) {
                Text("Diag", fontFamily = FontFamily.Monospace)
            }
        }

        // Live VT100 TerminalView (Apache 2.0)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(TerminalBlack),
        ) {
            val termSession = activePty?.terminalSession
            if (termSession != null) {
                AndroidView(
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            // Without focusable flags the view never
                            // receives key input at all.
                            isFocusable = true
                            isFocusableInTouchMode = true
                            setTextSize(textSizeState.value)
                            setTerminalViewClient(viewClient)
                            attachSession(termSession)
                            terminalViewRef = this
                        }
                    },
                    update = { view ->
                        terminalViewRef = view
                        if (view.currentSession !== termSession) {
                            view.attachSession(termSession)
                        }
                    },
                    modifier = Modifier.fillMaxSize().testTag("terminal_vt100_view"),
                )
            } else {
                // No live session (e.g. libproot.so not packaged yet, or the
                // session failed to start). Short status + a working control,
                // not an instruction paragraph.
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Shell not started.",
                        color = TerminalMutedText,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = ::openNewTab,
                        modifier = Modifier.testTag("terminal_start_shell"),
                    ) {
                        Text("Start shell", fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // !cmd results stay visible while a PTY is live too: previously
        // they were only rendered in the "Shell not started" branch, so a
        // running shell hid every !cmd result.
        if (uiState.lastCommandOutput.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalSurface)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = uiState.lastCommandOutput,
                    color = TerminalGreen,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).testTag("terminal_last_command_output"),
                )
                TextButton(
                    onClick = { stateHolder.setLastCommandOutput("") },
                    modifier = Modifier.testTag("terminal_clear_command_output"),
                ) {
                    Text("Clear", fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Inline !cmd runner bar wired directly to ProotShellEngine.shellExec
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalSurface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = quickCmd,
                onValueChange = { quickCmd = it },
                singleLine = true,
                placeholder = { Text("!git status or shell cmd…", fontFamily = FontFamily.Monospace) },
                modifier = Modifier.weight(1f).testTag("terminal_bang_input"),
            )
            TextButton(
                onClick = {
                    val raw = quickCmd.trim()
                    if (raw.isEmpty()) return@TextButton
                    val normalized = if (raw.startsWith("!")) raw else "!$raw"
                    quickCmd = ""
                    scope.launch {
                        val res = runBangCommand(engine, normalized)
                        if (res != null) {
                            val combined = buildString {
                                append("$ ").append(normalized.removePrefix("!")).append('\n')
                                if (res.stdout.isNotBlank()) append(res.stdout).append('\n')
                                if (res.stderr.isNotBlank()) append(res.stderr).append('\n')
                                append("[exit ${res.exitCode}]")
                            }
                            stateHolder.setLastCommandOutput(combined)
                            onBangCommandResult?.invoke(normalized.removePrefix("!"), res)
                        }
                    }
                },
                modifier = Modifier.testTag("terminal_bang_run"),
            ) {
                Text("Run", color = TerminalCyan, fontFamily = FontFamily.Monospace)
            }
        }

        // Compose-native Extra-Keys bar (built cleanly from scratch — NO GPL-3.0 code)
        ComposeExtraKeysBar(
            ctrlLatched = uiState.ctrlLatched,
            altLatched = uiState.altLatched,
            onKeyClick = { key ->
                when (key) {
                    ExtraKey.CTRL -> stateHolder.toggleCtrl()
                    ExtraKey.ALT -> stateHolder.toggleAlt()
                    else -> {
                        val seq = Vt100KeyEncoder.encodeExtraKey(key) ?: return@ComposeExtraKeysBar
                        val (ctrl, alt) = stateHolder.consumeLatchedModifiers()
                        val payload = Vt100KeyEncoder.applyModifiers(seq, ctrl = ctrl, alt = alt)
                        activePty?.terminalSession?.write(payload)
                    }
                }
            },
        )
    }

    val diag = uiState.phantomReport
    if (diag != null) {
        AlertDialog(
            onDismissRequest = { stateHolder.setPhantomReport(null) },
            title = { Text("Phantom-Process Diagnostic") },
            text = { Text(diag, fontFamily = FontFamily.Monospace) },
            confirmButton = {
                TextButton(onClick = { stateHolder.setPhantomReport(null) }) {
                    Text("Close")
                }
            },
        )
    }
}

/** Compose-native extra-keys touch bar (ESC, TAB, CTRL, ALT, arrows, symbols). */
@Composable
fun ComposeExtraKeysBar(
    ctrlLatched: Boolean,
    altLatched: Boolean,
    onKeyClick: (ExtraKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TerminalSurfaceElevated)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .testTag("terminal_extra_keys_bar"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ExtraKey.values().forEach { key ->
            val latched = (key == ExtraKey.CTRL && ctrlLatched) || (key == ExtraKey.ALT && altLatched)
            Surface(
                color = if (latched) TerminalCyan else TerminalSurface,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.height(36.dp).testTag(key.testTag),
            ) {
                TextButton(onClick = { onKeyClick(key) }) {
                    Text(
                        text = key.label,
                        color = if (latched) Color.Black else Color.White,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
