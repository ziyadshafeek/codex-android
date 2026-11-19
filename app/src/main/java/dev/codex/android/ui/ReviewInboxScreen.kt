package dev.codex.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.ThreadEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** One Review Inbox row: a thread waiting for review plus its summary. */
data class ReviewInboxEntry(
    val thread: ThreadEntity,
    /** Last assistant message in the thread (the run's summary), or "". */
    val summary: String,
)

/**
 * Review Inbox (master plan Subsystem 8): the runs scheduled automations
 * leave behind. Lists every thread with status `needs_review` or unread,
 * showing each run's summary (its last assistant message); tapping a row
 * marks it read and opens the thread via [onThreadClick].
 *
 * Standalone composable — NOT wired into navigation. The caller supplies
 * the [CodexDatabase] and receives the tapped thread id; opening the
 * thread itself is the caller's job.
 *
 * Note: per-run file diffs are not stored in the DB (WorkspaceFileTools
 * keeps change snapshots in memory only), so the "diff" a run produced
 * is represented here by its assistant summary text. Persisting diffs
 * per run is a follow-up, not part of this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewInboxScreen(
    database: CodexDatabase,
    onThreadClick: (String) -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var entries by remember { mutableStateOf<List<ReviewInboxEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    // Device timezone, "yyyy-MM-dd HH:mm" (same format as HistoryScreen).
    val dateFormat = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    LaunchedEffect(database) {
        entries = loadInboxEntries(database)
        loading = false
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Review Inbox") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No runs waiting for review.",
                    modifier = Modifier.testTag("review_inbox_empty"),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp)
                    .testTag("review_inbox_list")
            ) {
                items(entries, key = { it.thread.id }) { entry ->
                    Card(
                        onClick = {
                            scope.launch {
                                // Mark read, refresh the list (the row
                                // drops out unless it still needs review),
                                // then hand the thread to the caller.
                                runCatching {
                                    database.updateThreadState(entry.thread.id, unread = false)
                                }
                                entries = loadInboxEntries(database)
                                onThreadClick(entry.thread.id)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = entry.thread.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (entry.thread.status == "needs_review") {
                                        "Needs review"
                                    } else {
                                        "Unread"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = dateFormat.format(Date(entry.thread.updatedAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (entry.summary.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = entry.summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Threads needing review (or unread), each with its run summary. */
private suspend fun loadInboxEntries(database: CodexDatabase): List<ReviewInboxEntry> {
    val threads = runCatching { database.listThreads() }.getOrDefault(emptyList())
        .filter { it.status == "needs_review" || it.unread }
    return threads.map { thread ->
        val summary = runCatching {
            database.getMessages(thread.id)
                .lastOrNull { it.role == "assistant" }
                ?.content?.trim().orEmpty()
        }.getOrDefault("")
        ReviewInboxEntry(thread = thread, summary = summary)
    }
}
