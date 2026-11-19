package dev.codex.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.codex.android.browser.ConsoleLevel
import dev.codex.android.browser.VerificationBrowser
import kotlinx.coroutines.launch

/**
 * Preview tab UI for the verification browser (master plan Subsystem 6,
 * M7): URL bar, live page view, console drawer and screenshot view.
 *
 * Standalone composable taking the [VerificationBrowser] — NOT wired
 * into navigation yet; integration adds it as the Browser tab of the
 * workbench. The same browser instance is driven by the agent through
 * the browser_* tools, so the human and the agent always see the same
 * page: a URL the agent opens appears here, and a console error the
 * user sees here is what the agent reads via browser_console_logs.
 *
 * No API keys, no model calls, no secrets.
 */
@Composable
fun PreviewScreen(
    browser: VerificationBrowser,
    modifier: Modifier = Modifier,
) {
    val currentUrl by browser.currentUrl.collectAsState()
    val pageTitle by browser.pageTitle.collectAsState()
    val isLoading by browser.isLoading.collectAsState()
    val progress by browser.loadProgress.collectAsState()
    val consoleLogs by browser.consoleLogs.collectAsState()

    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Follows navigation; while the user types the URL does not change,
    // so typing is not interrupted by recomposition.
    var urlText by remember(currentUrl) { mutableStateOf(currentUrl) }
    var showConsole by remember { mutableStateOf(false) }
    var screenshotBytes by remember { mutableStateOf<ByteArray?>(null) }
    var showScreenshot by remember { mutableStateOf(false) }

    fun navigate() {
        val url = urlText.trim()
        if (url.isEmpty()) return
        keyboardController?.hide()
        scope.launch { browser.loadUrl(url) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // URL bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                singleLine = true,
                placeholder = { Text("https://example.com or localhost:3000") },
                modifier = Modifier
                    .weight(1f)
                    .testTag("preview_url_bar"),
            )
            TextButton(
                onClick = ::navigate,
                modifier = Modifier.testTag("preview_go_button"),
            ) {
                Text("Go")
            }
        }

        // Page title + load progress.
        if (pageTitle.isNotEmpty()) {
            Text(
                text = pageTitle,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        if (isLoading) {
            LinearProgressIndicator(
                progress = { (progress.coerceIn(0, 100)) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Toolbar: reload, console drawer toggle, screenshot.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { scope.launch { browser.reload() } }) {
                Text("Reload")
            }
            TextButton(
                onClick = { showConsole = !showConsole },
                modifier = Modifier.testTag("preview_console_toggle"),
            ) {
                val errorCount = consoleLogs.count { it.level == ConsoleLevel.ERROR }
                Text(
                    if (showConsole) "Hide console" else "Console (${consoleLogs.size})" +
                        if (errorCount > 0) " · $errorCount errors" else "",
                )
            }
            TextButton(
                onClick = {
                    scope.launch {
                        screenshotBytes = browser.screenshot()
                        showScreenshot = true
                    }
                },
                modifier = Modifier.testTag("preview_screenshot_button"),
            ) {
                Text("Screenshot")
            }
        }

        // Live page view: the browser's own WebView, shared with the agent.
        AndroidView(
            factory = { browser.webView },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("preview_webview"),
        )

        // Console drawer (collapsed by default).
        if (showConsole) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .testTag("preview_console_panel"),
                tonalElevation = 2.dp,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Console",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { browser.clearConsole() }) {
                            Text("Clear")
                        }
                        TextButton(onClick = { showConsole = false }) {
                            Text("Close")
                        }
                    }
                    if (consoleLogs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No console messages yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                        ) {
                            // Newest last, like a terminal; cap the rows drawn.
                            items(consoleLogs.takeLast(200)) { entry ->
                                val color = when (entry.level) {
                                    ConsoleLevel.ERROR -> MaterialTheme.colorScheme.error
                                    ConsoleLevel.WARN -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                val location =
                                    if (entry.source.isNotBlank()) " (${entry.source}:${entry.line})" else ""
                                Text(
                                    text = "[${entry.level}] ${entry.message}$location",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = color,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Screenshot view.
    if (showScreenshot) {
        val bitmap = remember(screenshotBytes) {
            screenshotBytes?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
        AlertDialog(
            onDismissRequest = { showScreenshot = false },
            title = { Text("Screenshot") },
            text = {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Viewport screenshot of the loaded page",
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    )
                } else {
                    Text("Screenshot failed or the page has not rendered yet.")
                }
            },
            confirmButton = {
                TextButton(onClick = { showScreenshot = false }) {
                    Text("Close")
                }
            },
        )
    }
}
