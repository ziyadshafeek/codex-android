package dev.codex.android.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

const val SUPPORT_UPI_ID: String = "ziyad.shafeek@oksbi"
const val SUPPORT_PAYEE_NAME: String = "Ziyad"

private val PRESET_AMOUNTS_INR: List<Int> = listOf(25, 50, 100, 250)

/**
 * Pure UPI payment URI builder for voluntary tips.
 *
 * Format: `upi://pay?pa=ziyad.shafeek@oksbi&pn=Ziyad&am=<amount>&cu=INR&tn=Support%20Codex`
 */
fun buildUpiUri(amount: Int): String {
    require(amount > 0) { "Amount must be greater than zero" }
    return "upi://pay?pa=$SUPPORT_UPI_ID&pn=$SUPPORT_PAYEE_NAME&am=$amount&cu=INR&tn=Support%20Codex"
}

/** Parses a user-entered custom rupee amount; returns null when blank or non-positive. */
internal fun parseCustomAmount(raw: String): Int? =
    raw.trim().toIntOrNull()?.takeIf { it > 0 }

/**
 * Voluntary "Support Codex" tip section (Material 3, same visual style as
 * [SettingsScreen]). Tips are completely optional and unlock nothing — no
 * Play Billing, no payment SDK, no feature gating.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SupportCodexSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var selectedPreset by remember { mutableStateOf<Int?>(100) }
    var isCustomSelected by remember { mutableStateOf(false) }
    var customAmountText by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val resolvedAmount: Int? = if (isCustomSelected) {
        parseCustomAmount(customAmountText)
    } else {
        selectedPreset
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("support_codex_section"),
    ) {
        Text(
            text = "Support Codex",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Tips are completely voluntary and unlock nothing — every feature in Codex is free.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (preset in PRESET_AMOUNTS_INR) {
                FilterChip(
                    selected = !isCustomSelected && selectedPreset == preset,
                    onClick = {
                        isCustomSelected = false
                        selectedPreset = preset
                        statusMessage = null
                    },
                    label = { Text("₹$preset") },
                    modifier = Modifier.testTag("donate_chip_$preset"),
                )
            }
            FilterChip(
                selected = isCustomSelected,
                onClick = {
                    isCustomSelected = true
                    statusMessage = null
                },
                label = { Text("Custom") },
                modifier = Modifier.testTag("donate_chip_custom"),
            )
        }

        if (isCustomSelected) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = customAmountText,
                onValueChange = { input ->
                    customAmountText = input.filter { it.isDigit() }
                    statusMessage = null
                },
                label = { Text("Custom amount (₹)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("donate_custom_input"),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                val amount = resolvedAmount ?: return@Button
                val uri = Uri.parse(buildUpiUri(amount))
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                    statusMessage = null
                } catch (_: ActivityNotFoundException) {
                    statusMessage = "No UPI app found. Copy the UPI ID below to tip from any app."
                } catch (_: Exception) {
                    statusMessage = "No UPI app available. Copy the UPI ID below to tip from any app."
                }
            },
            enabled = resolvedAmount != null && resolvedAmount > 0,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("donate_upi_button"),
        ) {
            Text("Donate via UPI")
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                try {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("UPI ID", SUPPORT_UPI_ID))
                    statusMessage = "Copied UPI ID: $SUPPORT_UPI_ID"
                } catch (_: Exception) {
                    statusMessage = "UPI ID: $SUPPORT_UPI_ID"
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("donate_copy_upi_button"),
        ) {
            Text("Copy UPI ID ($SUPPORT_UPI_ID)")
        }

        statusMessage?.let { msg ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("donate_status_message"),
            )
        }
    }
}
