package dev.codex.android.editor

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.codex.android.linux.ProotShellEngine
// Oled palette: top-level vals in dev.codex.android.ui, created by H1.
// In the staged H1 they live in ui/WorkbenchScreen.kt (same package); if the
// integrator moves them to ui/OledTheme.kt this import is unchanged. Do NOT
// define a local copy here.
import dev.codex.android.ui.OledAccentCyan
import dev.codex.android.ui.OledAmber
import dev.codex.android.ui.OledBlack
import dev.codex.android.ui.OledSurface
import dev.codex.android.ui.OledSurfaceElevated
import io.github.rosemoe.sora.event.LongPressEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Native Code Editor embedding the unmodified Maven Central Rosemoe Sora Editor AAR
 * (LGPL-2.1, see H3d DIFF NOTE — never vendored in source).
 *
 * Features:
 * - Dark OLED-compatible color scheme & monospace font
 * - In-editor search (`EditorSearcher` next/prev)
 * - Long-press `@file:L10-L30` line-range pin callback returning the pin string to the composer
 * - Post-edit local formatter + LSP diagnostics via [PostEditToolchain] (best-effort; missing
 *   binary skips with a one-line note and never fails the save/edit)
 */
@Composable
fun SoraEditorScreen(
    file: File,
    onSave: (content: String, postEditSummary: String) -> Unit,
    relativePath: String = file.name,
    proot: ProotShellEngine? = null,
    onPinToComposer: (pinToken: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var editorRef by remember { mutableStateOf<CodeEditor?>(null) }
    var loadedText by remember(file.absolutePath) { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var statusBanner by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    // Never read files on Main: hydrate file content on Dispatchers.IO.
    LaunchedEffect(file.absolutePath) {
        loadedText = withContext(Dispatchers.IO) {
            runCatching { if (file.isFile) file.readText() else "" }.getOrDefault("")
        }
    }

    DisposableEffect(file.absolutePath) {
        onDispose {
            runCatching { editorRef?.release() }
            editorRef = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack)
            .imePadding()
            .testTag("sora_editor_screen"),
    ) {
        // Toolbar: file path, search toggle, pin selection, Save
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(OledSurface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = relativePath,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    showSearch = !showSearch
                    if (!showSearch) editorRef?.searcher?.stopSearch()
                },
                modifier = Modifier.testTag("sora_search_toggle"),
            ) {
                Text("Find", fontFamily = FontFamily.Monospace)
            }
            TextButton(
                onClick = {
                    val ed = editorRef
                    val startLine = (ed?.cursor?.leftLine ?: 0) + 1
                    val endLine = (ed?.cursor?.rightLine ?: (startLine - 1)) + 1
                    val pin = PostEditToolchain.buildLinePin(relativePath, startLine, endLine)
                    statusBanner = "Pinned $pin to composer"
                    onPinToComposer(pin)
                },
                modifier = Modifier.testTag("sora_pin_button"),
            ) {
                Text("@Pin", color = OledAmber, fontFamily = FontFamily.Monospace)
            }
            Button(
                enabled = !saving && loadedText != null,
                onClick = {
                    val content = editorRef?.text?.toString() ?: loadedText.orEmpty()
                    saving = true
                    scope.launch {
                        // A failed disk write must never crash the UI scope or
                        // leave the Save button stuck in "Saving…": every
                        // failure lands in the status banner instead.
                        try {
                            withContext(Dispatchers.IO) {
                                file.parentFile?.mkdirs()
                                file.writeText(content)
                            }
                            val report = PostEditToolchain.runPostEdit(
                                proot = proot,
                                file = file,
                                guestPath = "/workspace/$relativePath",
                            )
                            val summary = report.toToolResultSuffix()
                            // Reload formatted content if formatter modified file on disk.
                            val afterFormat = withContext(Dispatchers.IO) {
                                runCatching { file.readText() }.getOrDefault(content)
                            }
                            if (afterFormat != content) {
                                editorRef?.setText(afterFormat)
                            }
                            statusBanner = "${report.formatterNote} · ${report.lspNote}"
                            onSave(afterFormat, summary)
                        } catch (e: Exception) {
                            statusBanner = "Save failed: ${e.message ?: e.javaClass.simpleName}"
                        } finally {
                            saving = false
                        }
                    }
                },
                modifier = Modifier.testTag("sora_save_button"),
            ) {
                Text(if (saving) "Saving…" else "Save")
            }
        }

        // In-editor search bar
        if (showSearch) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OledSurfaceElevated)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { q ->
                        searchQuery = q
                        val ed = editorRef ?: return@OutlinedTextField
                        if (q.isEmpty()) {
                            ed.searcher.stopSearch()
                        } else {
                            ed.searcher.search(q, EditorSearcher.SearchOptions(true, false))
                        }
                    },
                    singleLine = true,
                    placeholder = { Text("Search in file…") },
                    modifier = Modifier.weight(1f).testTag("sora_search_input"),
                )
                TextButton(onClick = { editorRef?.searcher?.gotoPrevious() }) {
                    Text("▲")
                }
                TextButton(onClick = { editorRef?.searcher?.gotoNext() }) {
                    Text("▼")
                }
            }
        }

        // Rosemoe Sora CodeEditor AndroidView
        AndroidView(
            factory = { ctx ->
                CodeEditor(ctx).apply {
                    typefaceText = Typeface.MONOSPACE
                    setColorScheme(SchemeDarcula())
                    isWordwrap = false
                    setTextSize(13f)
                    // Long-press pins current selection or tapped line as @file:L10-L30.
                    subscribeEvent(LongPressEvent::class.java) { event, _ ->
                        val cur = cursor
                        val startLine = if (cur.isSelected) cur.leftLine + 1 else event.line + 1
                        val endLine = if (cur.isSelected) cur.rightLine + 1 else event.line + 1
                        val pin = PostEditToolchain.buildLinePin(relativePath, startLine, endLine)
                        statusBanner = "Pinned $pin"
                        onPinToComposer(pin)
                    }
                    loadedText?.let { setText(it) }
                    editorRef = this
                }
            },
            update = { editor ->
                editorRef = editor
                val initial = loadedText
                if (initial != null && editor.tag != file.absolutePath) {
                    editor.setText(initial)
                    editor.tag = file.absolutePath
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("sora_code_editor"),
        )

        if (statusBanner.isNotBlank()) {
            Surface(color = OledSurface, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = statusBanner,
                    color = OledAccentCyan,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).testTag("sora_status"),
                )
            }
        }
    }
}
