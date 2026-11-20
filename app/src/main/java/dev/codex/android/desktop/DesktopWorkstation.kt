package dev.codex.android.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.codex.android.browser.VerificationBrowser
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.CostTelemetry
import dev.codex.android.data.TimelineMessage
import dev.codex.android.linux.ProotShellEngine
import dev.codex.android.subagents.SubagentInfo
import dev.codex.android.terminal.TerminalStateHolder
import dev.codex.android.tools.WorkspaceFileTools
import dev.codex.android.ui.ComposerMode
import dev.codex.android.ui.OledBlack
import dev.codex.android.ui.OledSurface
import dev.codex.android.ui.PreviewScreen
import dev.codex.android.ui.WorkbenchScreen
import dev.codex.android.ui.WorkbenchStateHolder
import dev.codex.android.vcs.SnapshotEngine

/** Pure layout decision helper: which 3-pane mode to show for a given window width. */
enum class DesktopLayoutMode { THREE_PANE, TWO_PANE, SINGLE_PANE }

object DesktopLayout {
    /** Returns the layout mode for a given [WindowWidthSizeClass] (pure, JVM-testable). */
    fun modeFor(widthSizeClass: WindowWidthSizeClass): DesktopLayoutMode = when (widthSizeClass) {
        WindowWidthSizeClass.Expanded -> DesktopLayoutMode.THREE_PANE
        WindowWidthSizeClass.Medium -> DesktopLayoutMode.TWO_PANE
        else -> DesktopLayoutMode.SINGLE_PANE
    }

    /** Returns true when the device qualifies as a desktop-class workstation (DeX/tablet). */
    fun isDesktopClass(widthSizeClass: WindowWidthSizeClass): Boolean =
        widthSizeClass == WindowWidthSizeClass.Expanded
}

/**
 * Desktop Workstation (Milestone M10): a 3-pane DeX/tablet layout when the window is
 * `Expanded`, degrading gracefully to 2-pane (`Medium`) and single-pane (`Compact`).
 *
 * - Left pane: Agent command center (WorkbenchScreen).
 * - Middle pane: Preview / verification browser (PreviewScreen).
 * - Right pane: Subagents & automation status.
 * The panes are independent Composables; on narrow screens only the agent pane shows
 * (with a tab switcher in the real navigation host).
 *
 * Window size class comes from the real
 * `androidx.compose.material3:material3-window-size-class` library
 * (`calculateWindowSizeClass(activity)` in MainActivity — see NOTES.md);
 * there is no custom WindowSizeClass enum in this file.
 *
 * The left pane is the real [WorkbenchScreen], not a default/empty one:
 * the DI root passes the same shared database, cost telemetry, file
 * tools, terminal engine, thread state, subagents, state holders and
 * snapshot engine MainActivity's own workbench branch uses, so the
 * Terminal/Browser/Files tabs are live here too.
 *
 * NOTE (verified 2026-11-20 against the staged H1–H3 sources): this file
 * composes [WorkbenchScreen] and [PreviewScreen] directly. [TerminalScreen]
 * (H2) and [SoraEditorScreen] (H3) are NOT composed here — they are reached
 * through WorkbenchScreen's Terminal / Files tab slots, which the DI root
 * wires (see NOTES.md). [MissionControlPanel],
 * [AgentTabContent], [ArtifactsListPanel] and [DiffCiTabContent] are H1
 * internals of WorkbenchScreen, not references from this file.
 */
@Composable
fun DesktopWorkstation(
    windowSizeClass: WindowSizeClass,
    browser: VerificationBrowser,
    subagents: List<SubagentInfo> = emptyList(),
    database: CodexDatabase? = null,
    costTelemetry: CostTelemetry? = null,
    workspaceFiles: WorkspaceFileTools? = null,
    terminalEngine: ProotShellEngine? = null,
    activeThreadId: String = "thr_default",
    messages: List<TimelineMessage> = emptyList(),
    stateHolder: WorkbenchStateHolder = remember { WorkbenchStateHolder() },
    terminalStateHolder: TerminalStateHolder = remember { TerminalStateHolder() },
    snapshotEngine: SnapshotEngine? = null,
    onSelectThread: (String) -> Unit = {},
    onSendPrompt: (prompt: String, mode: ComposerMode, attachedFiles: List<String>) -> Unit = { _, _, _ -> },
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val mode = DesktopLayout.modeFor(windowSizeClass.widthSizeClass)
    var activePane by remember { mutableStateOf(0) }

    // The shared workbench, with the DI root's live state passed through
    // (same instances MainActivity's own workbench branch uses).
    val workbench: @Composable (Modifier) -> Unit = { workbenchModifier ->
        WorkbenchScreen(
            database = database,
            costTelemetry = costTelemetry,
            workspaceFiles = workspaceFiles,
            terminalEngine = terminalEngine,
            browser = browser,
            activeThreadId = activeThreadId,
            messages = messages,
            subagents = subagents,
            stateHolder = stateHolder,
            terminalStateHolder = terminalStateHolder,
            snapshotEngine = snapshotEngine,
            onSelectThread = onSelectThread,
            onSendPrompt = onSendPrompt,
            onOpenSettings = onOpenSettings,
            browserTabContent = {
                if (mode == DesktopLayoutMode.THREE_PANE || (mode == DesktopLayoutMode.TWO_PANE && activePane == 0)) {
                    Text("Preview is open in the adjacent panel.", modifier = Modifier.padding(16.dp))
                } else {
                    PreviewScreen(browser = browser)
                }
            },
            modifier = workbenchModifier,
        )
    }

    Surface(
        color = OledBlack,
        modifier = modifier.fillMaxSize().testTag("desktop_workstation"),
    ) {
        when (mode) {
            DesktopLayoutMode.THREE_PANE -> {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(0.42f)) {
                        workbench(Modifier.fillMaxSize())
                    }
                    Box(modifier = Modifier.weight(0.33f)) {
                        PreviewScreen(browser = browser, modifier = Modifier.fillMaxSize())
                    }
                    Box(modifier = Modifier.weight(0.25f)) {
                        SubagentStatusPane(subagents = subagents, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            DesktopLayoutMode.TWO_PANE -> {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(0.6f)) {
                        workbench(Modifier.fillMaxSize())
                    }
                    Column(modifier = Modifier.weight(0.4f)) {
                        // Working pane switcher: the staged file kept
                        // `activePane` state with no control that could ever
                        // change it, so the Subagents pane was unreachable.
                        // These two buttons are the switch.
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TextButton(
                                onClick = { activePane = 0 },
                                modifier = Modifier.testTag("desktop_pane_preview"),
                            ) {
                                Text("Preview")
                            }
                            TextButton(
                                onClick = { activePane = 1 },
                                modifier = Modifier.testTag("desktop_pane_subagents"),
                            ) {
                                Text("Subagents (${subagents.size})")
                            }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            if (activePane == 0) {
                                PreviewScreen(browser = browser, modifier = Modifier.fillMaxSize())
                            } else {
                                SubagentStatusPane(subagents = subagents, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
            DesktopLayoutMode.SINGLE_PANE -> {
                workbench(Modifier.fillMaxSize())
            }
        }
    }
}

/** Right-pane status list: running subagents and their latest output. */
@Composable
fun SubagentStatusPane(
    subagents: List<SubagentInfo>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(12.dp)
            .testTag("subagent_status_pane"),
    ) {
        Text(
            text = "SUBAGENTS (${subagents.size})",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (subagents.isEmpty()) {
            Text(
                text = "No subagents running.",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            subagents.forEach { sub ->
                Surface(
                    color = OledSurface,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "${sub.id} — ${sub.status}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = sub.task.take(120),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (sub.output.isNotBlank()) {
                            Text(
                                text = sub.output.take(200),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
