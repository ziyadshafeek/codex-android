package dev.codex.android.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import dev.codex.android.agent.AgentRuntime
import dev.codex.android.ui.ShareActivity
import dev.codex.android.ui.createCodexThread
import dev.codex.android.ui.openCodex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile for Codex (Subsystem 8: cross-app surfaces).
 *
 * Tap opens [TileOverlayActivity], a translucent overlay with the quick
 * actions, over whatever app is currently in the foreground. Voice is
 * deliberately NOT here — a parallel workstream owns voice.
 *
 * Tile state: ACTIVE while any agent run is in progress (read from
 * [AgentRuntime.threads]), INACTIVE otherwise. Every callback is wrapped
 * so a failure can never crash SystemUI: a dead [qsTile] (service
 * unavailable) is a no-op, and a failed overlay launch falls back to a
 * plain startActivity before giving up quietly.
 *
 * No API key, no model call, no network — the tile only reads local
 * runtime state and launches local activities.
 */
class CodexTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTileState()
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onStopListening() {
        super.onStopListening()
        refreshTileState()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        // Nothing to clean up; the tile just stops being shown.
    }

    override fun onClick() {
        super.onClick()
        try {
            // Show the tile as active while the overlay is up.
            refreshTileState(activeOverride = true)
            val intent = Intent(this, TileOverlayActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+: launch via PendingIntent, the path the
                // framework blesses for tile services under the tightened
                // background-activity rules.
                val pending = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                startActivityAndCollapse(pending)
            } else {
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tile overlay launch via startActivityAndCollapse failed, retrying plain", e)
            try {
                startActivity(
                    Intent(this, TileOverlayActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
                // Never crash SystemUI: the tile simply stays as it was.
            }
            refreshTileState()
        }
    }

    /**
     * ACTIVE iff an agent run is currently in progress, unless
     * [activeOverride] forces a state (the overlay-open moment).
     * Safe when the tile service is unavailable: [qsTile] is null and
     * this is a no-op.
     */
    private fun refreshTileState(activeOverride: Boolean? = null) {
        try {
            val tile = qsTile ?: return
            val anyRunning = try {
                AgentRuntime.threads.value.values.any { it.isRunning }
            } catch (_: Exception) {
                false
            }
            tile.state = when {
                activeOverride != null -> if (activeOverride) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                anyRunning -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            tile.updateTile()
        } catch (_: Exception) {
            // A tile update must never crash the service.
        }
    }

    private companion object {
        const val TAG = "CodexTileService"
    }
}

/**
 * Translucent overlay opened from [CodexTileService], shown over any app.
 *
 * Declared in the manifest with a translucent theme; the Compose content
 * is a dim scrim (tap outside the card to dismiss) with a centred card.
 *
 * Quick actions:
 * - "New chat": creates a thread in `ws_phone` via [createCodexThread]
 *   and opens the app to it ([ShareActivity.EXTRA_THREAD_ID]).
 * - "Open Codex": just opens the app.
 *
 * A DB failure never crashes the overlay: the error is shown in the card
 * and the user can retry or close. Voice is out of scope here.
 */
class TileOverlayActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var isBusy by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Dim scrim: tap outside the card dismisses the overlay.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f))
                            .clickable { finish() },
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
                        ) {
                            Text("Codex", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Quick actions",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(
                                onClick = ::onNewChat,
                                enabled = !isBusy,
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) {
                                Text("New chat")
                            }
                            Button(
                                onClick = ::onOpenCodex,
                                enabled = !isBusy,
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) {
                                Text("Open Codex")
                            }
                            errorMessage?.let { message ->
                                Text(
                                    message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            TextButton(
                                onClick = { finish() },
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text("Close")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun onNewChat() {
        if (isBusy) return
        isBusy = true
        errorMessage = null
        scope.launch {
            try {
                val thread = createCodexThread(this@TileOverlayActivity, "Quick chat")
                openCodex(this@TileOverlayActivity, thread.id)
                finish()
            } catch (e: Exception) {
                Log.w(TAG, "New chat from tile failed", e)
                errorMessage = "Couldn't start a new chat: ${e.message ?: "unknown error"}"
                isBusy = false
            }
        }
    }

    private fun onOpenCodex() {
        try {
            openCodex(this, null)
            finish()
        } catch (e: Exception) {
            Log.w(TAG, "Open Codex from tile failed", e)
            errorMessage = "Couldn't open Codex: ${e.message ?: "unknown error"}"
        }
    }

    private companion object {
        const val TAG = "TileOverlayActivity"
    }
}
