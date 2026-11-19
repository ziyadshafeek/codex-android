package dev.codex.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codex.android.store.AppGenerator
import dev.codex.android.store.AppTemplate
import dev.codex.android.store.StoreCatalog
import dev.codex.android.store.StoreListing
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Phase 6 mini app store (skeleton, not a marketplace).
 *
 * The agent generates small apps from three templates (counter, notes,
 * timer) and lists them in a local catalogue with a price in INR and a
 * moderation flag (approved / pending). The APK build runs on a build
 * service, not on the phone. Payments are NOT live: the Buy button only
 * shows a "not live yet" dialog. No payment code anywhere in this file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val catalog = remember { StoreCatalog(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var templates by remember { mutableStateOf<List<AppTemplate>>(emptyList()) }
    var approved by remember { mutableStateOf<List<StoreListing>>(emptyList()) }
    var pending by remember { mutableStateOf<List<StoreListing>>(emptyList()) }
    var selectedTemplateId by remember { mutableStateOf("counter") }
    var appName by remember { mutableStateOf("") }
    var appDescription by remember { mutableStateOf("") }
    var priceRupees by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var buyTarget by remember { mutableStateOf<StoreListing?>(null) }

    fun reload() {
        scope.launch {
            try {
                approved = catalog.approved()
                pending = catalog.pending()
            } catch (e: Exception) {
                statusMessage = "Could not load the store: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            templates = AppGenerator.templates()
            if (templates.isNotEmpty() && templates.none { it.id == selectedTemplateId }) {
                selectedTemplateId = templates.first().id
            }
        } catch (e: Exception) {
            // Templates stay empty; the picker falls back to the three
            // built-in template ids and Generate validates against them.
            statusMessage = "Could not load templates: ${e.message}"
        }
        reload()
    }

    // Picker entries: the generator's own templates when loaded, else the
    // three built-in ones. Labels are the short spec names (Counter / Notes
    // / Timer); unknown future templates fall back to their own name.
    val pickerEntries: List<Pair<String, String>> =
        if (templates.isNotEmpty()) templates.map {
            it.id to when (it.id) {
                "counter" -> "Counter"
                "notes" -> "Notes"
                "timer" -> "Timer"
                else -> it.name
            }
        }
        else listOf("counter" to "Counter", "notes" to "Notes", "timer" to "Timer")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Store") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            // ---- Build an app ----
            item {
                Text(
                    text = "Build an app",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    for ((id, label) in pickerEntries) {
                        val selected = id == selectedTemplateId
                        TextButton(onClick = { selectedTemplateId = id }) {
                            Text(
                                text = if (selected) "✓ $label" else label,
                                fontWeight = if (selected) FontWeight.Bold
                                else FontWeight.Normal
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = appName,
                    onValueChange = { appName = it },
                    label = { Text("App name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = appDescription,
                    onValueChange = { appDescription = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = priceRupees,
                    onValueChange = { priceRupees = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Price in rupees (empty = free)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val name = appName.trim()
                                val description = appDescription.trim()
                                if (name.isBlank() || description.isBlank()) {
                                    statusMessage =
                                        "Enter an app name and a description first."
                                    return@launch
                                }
                                if (templates.isNotEmpty() &&
                                    templates.none { it.id == selectedTemplateId }
                                ) {
                                    statusMessage = "Unknown template selected."
                                    return@launch
                                }
                                val rupees = if (priceRupees.isBlank()) 0L
                                else priceRupees.toLongOrNull()
                                if (rupees == null) {
                                    statusMessage =
                                        "Price must be a whole number of rupees."
                                    return@launch
                                }
                                val generated = AppGenerator.generate(
                                    name = name,
                                    description = description,
                                    templateId = selectedTemplateId
                                )
                                // The build runs on a build service, not on
                                // the phone; requestBuild only files the
                                // request and returns the app with status
                                // BUILD_REQUESTED. The listing links back to
                                // the generated app via its id.
                                val requested = AppGenerator.requestBuild(generated)
                                catalog.save(
                                    StoreListing(
                                        id = UUID.randomUUID().toString(),
                                        appId = requested.id,
                                        name = name,
                                        description = description,
                                        pricePaise = rupees * 100,
                                        status = requested.status,
                                        approved = false,
                                        createdAtMillis = System.currentTimeMillis()
                                    )
                                )
                                appName = ""
                                appDescription = ""
                                priceRupees = ""
                                statusMessage = "Generated '$name'. Build requested. " +
                                    "Listed as pending approval."
                                reload()
                            } catch (e: Exception) {
                                statusMessage = "Could not generate the app: ${e.message}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Generate")
                }
                if (statusMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = statusMessage!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ---- Approved listings ----
            item {
                Text(
                    text = "Store",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            if (approved.isEmpty()) {
                item {
                    Text(
                        text = "No apps listed yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            } else {
                items(approved) { listing ->
                    StoreRow(listing = listing, onBuy = { buyTarget = listing })
                }
            }

            // ---- Pending approval ----
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Pending approval",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            if (pending.isEmpty()) {
                item {
                    Text(
                        text = "Nothing waiting for approval.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(pending) { listing ->
                    StoreRow(listing = listing, onBuy = null)
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Payments are not live yet. The store lists apps and takes " +
                        "requests; real payments and publishing come later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    val target = buyTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { buyTarget = null },
            title = { Text("Payments are not live yet") },
            text = {
                Text(
                    "Buying is not enabled in this version. The store lists apps " +
                        "and takes requests; real payments and publishing come later."
                )
            },
            confirmButton = {
                TextButton(onClick = { buyTarget = null }) {
                    Text("OK")
                }
            }
        )
    }
}

/** One store row: name, one-line description, price, status, Buy button. */
@Composable
private fun StoreRow(listing: StoreListing, onBuy: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = listing.name,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = listing.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatPrice(listing.pricePaise)} · " +
                        listing.status.name.replace('_', ' '),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onBuy != null) {
                TextButton(onClick = onBuy) {
                    Text("Buy")
                }
            }
        }
    }
}

private fun formatPrice(pricePaise: Long): String {
    if (pricePaise <= 0) return "Free"
    val rupees = pricePaise / 100
    val paise = pricePaise % 100
    return if (paise == 0L) "₹$rupees" else "₹$rupees.${paise.toString().padStart(2, '0')}"
}
