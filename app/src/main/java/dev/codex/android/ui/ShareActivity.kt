package dev.codex.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.codex.android.MainActivity
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.ThreadEntity
import dev.codex.android.data.TimelineMessage
import dev.codex.android.data.WorkspaceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Intent extra carrying the thread id to open in [MainActivity].
 *
 * The receiving side honours it: [MainActivity] reads this extra in
 * onCreate / onNewIntent and opens the thread via
 * ChatViewModel.openThread. The thread and its first message ARE
 * persisted in codex.db by [ShareActivity] / [createCodexThread], so
 * shared content opens directly in its thread.
 */
private const val THREAD_ID_EXTRA = "dev.codex.android.extra.THREAD_ID"

/**
 * Creates a thread in the "This Phone" workspace ([CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID]).
 * Shared by [ShareActivity] and the tile overlay. The workspace row is
 * re-asserted first (FK discipline: threads reference workspaces), and
 * the write runs in [NonCancellable] so dismissing the calling activity
 * mid-write cannot leave a half-created thread.
 */
internal suspend fun createCodexThread(context: Context, title: String): ThreadEntity =
    withContext(NonCancellable + Dispatchers.IO) {
        val db = CodexDatabase.getInstance(context)
        db.upsertWorkspace(
            WorkspaceEntity(
                id = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID,
                name = "This Phone",
                kind = "phone",
                rootPath = "",
                createdAt = System.currentTimeMillis(),
            ),
        )
        db.createThread(
            workspaceId = CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID,
            title = title.take(60).ifBlank { "New thread" },
            mode = "AUTO",
        )
    }

/** Opens the app, optionally to the thread [threadId] (see [THREAD_ID_EXTRA]). */
internal fun openCodex(context: Context, threadId: String?) {
    val intent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        threadId?.let { putExtra(ShareActivity.EXTRA_THREAD_ID, it) }
    }
    context.startActivity(intent)
}

/**
 * Share-to-Codex target (Subsystem 8): handles [Intent.ACTION_SEND] and
 * [Intent.ACTION_SEND_MULTIPLE] for text, images, URLs and files.
 *
 * Flow: collect the shared content -> create a thread in `ws_phone` via
 * [createCodexThread] -> append the shared content as the thread's first
 * user message ([CodexDatabase.appendMessage]) -> open the app to that
 * thread ([openCodex]).
 *
 * Content URIs are read only through [android.content.ContentResolver] —
 * a file path is never assumed. Text files are inlined (capped);
 * images and other files are recorded by name / type / size only, since
 * the chat is text-only and the URI permission grant ends with this
 * activity. A URI that cannot be read is recorded as such in the thread
 * instead of crashing; if nothing at all can be read, a clear error is
 * shown in this activity and no thread is created.
 *
 * No API key, no model call, no network — local DB writes only.
 */
class ShareActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var isWorking by mutableStateOf(true)
    private var errorMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f))
                            .clickable(enabled = !isWorking) { finish() },
                    )
                    Card(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(24.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (isWorking) {
                                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                                Text(
                                    "Sharing to Codex…",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            } else {
                                Text(
                                    errorMessage ?: "Sharing failed.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Button(
                                    onClick = {
                                        try {
                                            openCodex(this@ShareActivity, null)
                                        } catch (_: Exception) {
                                            // App launch failed; just close.
                                        }
                                        finish()
                                    },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                ) {
                                    Text("Open Codex")
                                }
                                TextButton(onClick = { finish() }) {
                                    Text("Close")
                                }
                            }
                        }
                    }
                }
            }
        }
        handleShare(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun handleShare(shareIntent: Intent) {
        scope.launch {
            try {
                val message = withContext(Dispatchers.IO) { buildSharedMessage(shareIntent) }
                if (message.isBlank()) {
                    showError("Nothing to share — the shared content was empty or could not be read.")
                    return@launch
                }
                val thread = createCodexThread(
                    this@ShareActivity,
                    deriveTitle(shareIntent),
                )
                withContext(NonCancellable + Dispatchers.IO) {
                    CodexDatabase.getInstance(this@ShareActivity).appendMessage(
                        TimelineMessage(
                            threadId = thread.id,
                            role = "user",
                            kind = "text",
                            content = message,
                        ),
                    )
                }
                openCodex(this@ShareActivity, thread.id)
                finish()
            } catch (e: Exception) {
                Log.w(TAG, "Share to Codex failed", e)
                showError("Couldn't share to Codex: ${e.message ?: "unknown error"}")
            }
        }
    }

    private fun showError(message: String) {
        errorMessage = message
        isWorking = false
    }

    /**
     * Builds the first message from the share intent. Runs on [Dispatchers.IO]:
     * ContentResolver reads happen here. Never throws for an unreadable
     * URI — that is recorded in the message instead.
     */
    private fun buildSharedMessage(intent: Intent): String {
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            throw IllegalArgumentException("Unsupported share action: $action")
        }
        val parts = mutableListOf<String>()
        intent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?.takeIf { it.isNotBlank() }
            ?.let { parts += "Subject: $it" }
        intent.getStringExtra(Intent.EXTRA_TEXT)
            ?.takeIf { it.isNotBlank() }
            ?.let { text ->
                parts += if (text.length > MAX_SHARED_TEXT_CHARS) {
                    text.take(MAX_SHARED_TEXT_CHARS) +
                        "\n[Shared text truncated to $MAX_SHARED_TEXT_CHARS characters]"
                } else {
                    text
                }
            }
        collectStreamUris(intent).forEach { uri -> parts += describeSharedUri(uri) }
        return parts.joinToString("\n\n").trim()
    }

    /**
     * Stream URIs from EXTRA_STREAM (single or list) plus ClipData, which
     * also carries the sender's URI permission grant. De-duplicated:
     * senders often put the same URI in both places.
     */
    @Suppress("DEPRECATION")
    private fun collectStreamUris(intent: Intent): List<Uri> {
        val uris = linkedSetOf<Uri>()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        } else {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { uris += it }
            }
        }
        return uris.toList()
    }

    /**
     * One human-readable block per shared URI. Never assumes a file path
     * and never throws: when metadata or content cannot be read, the
     * block records the name, type and size only.
     */
    private fun describeSharedUri(uri: Uri): String {
        val (displayName, size) = queryUriMeta(uri)
        val name = displayName
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "shared file"
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
        val mimeText = mime?.let { " ($it)" } ?: ""
        val sizeText = size?.let { " (${formatSize(it)})" } ?: ""
        return when {
            mime?.startsWith("image/") == true ->
                "Shared image: $name$mimeText$sizeText"
            isInlineText(mime, name) -> {
                val text = readTextContent(uri, MAX_SHARED_FILE_TEXT_CHARS)
                if (text != null) {
                    "Shared file: $name$mimeText$sizeText\n$text"
                } else {
                    "Shared file: $name$mimeText$sizeText"
                }
            }
            else ->
                "Shared file: $name$mimeText$sizeText"
        }
    }

    private fun isInlineText(mime: String?, name: String): Boolean {
        if (mime?.startsWith("text/") == true) return true
        val lower = name.lowercase()
        return TEXT_EXTENSIONS.any { lower.endsWith(it) }
    }

    /** Display name + size via OpenableColumns; nulls on any failure. */
    private fun queryUriMeta(uri: Uri): Pair<String?, Long?> {
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null to null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                name to size
            } ?: (null to null)
        } catch (_: Exception) {
            null to null
        }
    }

    /**
     * Reads a text file through the ContentResolver, capped at [maxChars]
     * (with a truncation note). Returns null when the stream cannot be
     * opened or read at all — the caller records that instead of crashing.
     */
    private fun readTextContent(uri: Uri, maxChars: Int): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val reader = stream.bufferedReader(Charsets.UTF_8)
                val out = StringBuilder()
                val buf = CharArray(4096)
                while (out.length < maxChars) {
                    val read = reader.read(buf, 0, minOf(buf.size, maxChars - out.length))
                    if (read <= 0) break
                    out.append(buf, 0, read)
                }
                val truncated = reader.read() != -1
                val text = out.toString()
                if (truncated) "$text\n[File text truncated to $maxChars characters]" else text
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    private fun deriveTitle(intent: Intent): String {
        intent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        intent.getStringExtra(Intent.EXTRA_TEXT)
            ?.takeIf { it.isNotBlank() }
            ?.let { text ->
                val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                if (!firstLine.isNullOrBlank()) return firstLine
            }
        return "Shared to Codex"
    }

    companion object {
        /** Intent extra: id of the thread MainActivity should open to. */
        const val EXTRA_THREAD_ID: String = THREAD_ID_EXTRA
        private const val TAG = "ShareActivity"
        private const val MAX_SHARED_TEXT_CHARS = 8000
        private const val MAX_SHARED_FILE_TEXT_CHARS = 32768
        private val TEXT_EXTENSIONS = listOf(
            ".txt", ".md", ".log", ".json", ".csv", ".xml",
            ".kt", ".java", ".py", ".js", ".ts", ".toml", ".gradle",
        )
    }
}
