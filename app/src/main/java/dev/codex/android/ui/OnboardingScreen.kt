package dev.codex.android.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.codex.android.service.BatteryHelper

/**
 * Phase 5 — first-run onboarding, four steps.
 *
 * Every step explains why a permission or setting is needed before it is
 * asked for, and every step has a "Skip" action that finishes onboarding
 * immediately — onboarding never blocks the user from reaching the chat.
 * Nothing here asks for anything silently: each system settings screen is
 * opened only from its own step's button, and the user can walk away
 * from any of them with Back or Skip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val activity = context as? Activity

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Welcome to Codex") },
                actions = {
                    TextButton(onClick = onFinish) {
                        Text("Skip")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = "Step ${step + 1} of 4",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            when (step) {
                0 -> {
                    Text(text = "Welcome", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Codex is an agent on your phone. It plans, taps, " +
                            "types and remembers — you bring your own API key.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                1 -> {
                    Text(text = "Accessibility", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Codex needs Accessibility access to tap and type " +
                            "in other apps. Nothing you see is ever logged or sent " +
                            "anywhere except to your own API endpoint.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { BatteryHelper.openAccessibilitySettings(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Accessibility settings")
                    }
                }
                2 -> {
                    Text(text = "Notifications", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Codex notifies you when a scheduled task finishes. " +
                            "Allow notifications so you don't miss it.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent =
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // No-op: the settings screen is unavailable on this device.
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open notification settings")
                    }
                }
                3 -> {
                    Text(text = "Stay alive", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Android may kill background apps. Exempt Codex from " +
                            "battery optimisation so it keeps running.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = {
                            // No-op when there is no Activity to request from.
                            val host = activity ?: return@OutlinedButton
                            val launched = try {
                                BatteryHelper.requestExemption(host)
                            } catch (e: Exception) {
                                false
                            }
                            if (!launched) {
                                BatteryHelper.openBatterySettings(context)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Request exemption")
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (step > 0) {
                    TextButton(onClick = { step -= 1 }) {
                        Text("Back")
                    }
                } else {
                    // Keeps the Next button right-aligned on step 0.
                    Spacer(modifier = Modifier.height(0.dp))
                }
                Button(
                    onClick = {
                        if (step < 3) {
                            step += 1
                        } else {
                            onFinish()
                        }
                    }
                ) {
                    Text(if (step < 3) "Next" else "Done")
                }
            }
        }
    }
}
