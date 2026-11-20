package dev.codex.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codex.android.artifacts.downloadIndex
import dev.codex.android.attachments.FileLibrary
import dev.codex.android.linux.RootfsManager
import dev.codex.android.linux.ShellResult
import dev.codex.android.tools.InstalledPackage
import dev.codex.android.tools.LinuxPackageService
import dev.codex.android.tools.WorkspaceCleaner
import dev.codex.android.tools.WorkspaceFileTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Storage screen: full delete control for what Codex installed or saved.
 *
 * Every destructive action goes through a confirmation dialog first
 * ([PendingDelete]) — nothing here deletes on a single tap. After the
 * delete runs, the result line says what actually happened (and the
 * counts refresh), so a silent or half-done delete is visible.
 *
 * Sections:
 * - Installed Linux packages: `apk list --installed` via the app's
 *   existing shell path, one Uninstall button per package, verified gone
 *   with `apk info -e` afterwards. A read failure shows the error
 *   plainly, never an empty list.
 * - Delete Linux workspace: wipes the whole Alpine rootfs via
 *   [RootfsManager.wipeRootfs]; afterwards a "Reinstall Linux" button
 *   calls [onReinstallLinux] (the coordinator wires it to the existing
 *   rootfs install flow, `RootfsBootstrap.ensure(context, retry = true)`,
 *   which returns true only when Linux is really installed again). The
 *   reinstall runs through the same busy/result/refresh path as the
 *   deletes, so its outcome is shown on screen instead of being
 *   fire-and-forget.
 * - Clear workspace: deletes all workspace files (keeps the workspace
 *   folder itself and staged attachments, which have their own section).
 * - Clear attachments: deletes every staged attachment and saved library
 *   file, reusing [FileLibrary.delete] per entry.
 * - Clear downloads: deletes every downloadable workspace file via the
 *   existing download-delete path.
 *
 * Wired by the coordinator from MainActivity (NOT edited here): see the
 * report for the exact `showStorage` flag + branch and the
 * `StorageScreen(...)` call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorageScreen(
    workspace: WorkspaceFileTools,
    fileLibrary: FileLibrary,
    rootfsManager: RootfsManager,
    shell: suspend (String) -> ShellResult,
    onBack: () -> Unit,
    /**
     * Reinstalls Linux via the real install flow. Returns true only when
     * Linux is really installed again; the screen shows the outcome and
     * refreshes. Coordinator line:
     * `onReinstallLinux = { RootfsBootstrap.ensure(applicationContext, retry = true) }`.
     */
    onReinstallLinux: suspend () -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val packageService = remember { LinuxPackageService(shell) }

    var packages by remember { mutableStateOf<List<InstalledPackage>?>(null) }
    var packagesError by remember { mutableStateOf<String?>(null) }
    var workspaceFileCount by remember { mutableStateOf(0) }
    var workspaceBytes by remember { mutableStateOf(0L) }
    var attachmentCount by remember { mutableStateOf(0) }
    var downloadCount by remember { mutableStateOf(0) }
    var rootfsInstalled by remember { mutableStateOf(false) }
    var rootfsBytes by remember { mutableStateOf(0L) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingDelete?>(null) }

    fun refresh() {
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val pkgs = packageService.listInstalled()
                    val wsCount = WorkspaceCleaner.countFiles(workspace.rootDir)
                    val wsBytes = WorkspaceCleaner.totalBytes(workspace.rootDir)
                    val attCount = fileLibrary.staged().size + fileLibrary.list("library").size
                    val dlCount = downloadIndex(workspace).size
                    val installed = rootfsManager.isInstalled()
                    val linuxBytes = WorkspaceCleaner.totalBytes(rootfsManager.rootfsDir())
                    RefreshData(pkgs, wsCount, wsBytes, attCount, dlCount, installed, linuxBytes)
                }
                packages = loaded.pkgs.getOrNull()
                packagesError = loaded.pkgs.exceptionOrNull()?.message
                workspaceFileCount = loaded.wsCount
                workspaceBytes = loaded.wsBytes
                attachmentCount = loaded.attCount
                downloadCount = loaded.dlCount
                rootfsInstalled = loaded.installed
                rootfsBytes = loaded.linuxBytes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                resultMessage = "Could not load storage info: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    /** Runs a confirmed delete off the main thread, then reports + refreshes. */
    fun runDelete(action: PendingDelete) {
        pending = null
        busy = true
        resultMessage = null
        scope.launch {
            try {
                val message = withContext(Dispatchers.IO) {
                    when (action) {
                        is PendingDelete.UninstallPackage -> {
                            packageService.uninstall(action.pkg.name).fold(
                                onSuccess = { it },
                                onFailure = { "Could not uninstall ${action.pkg.name}: ${it.message}" },
                            )
                        }
                        PendingDelete.WipeLinux -> {
                            if (rootfsManager.wipeRootfs()) {
                                "Linux workspace deleted. Reinstall Linux to use the terminal again."
                            } else {
                                "Could not delete the Linux workspace. It may still be there — check the terminal."
                            }
                        }
                        PendingDelete.ClearWorkspace -> {
                            val removed = WorkspaceCleaner.clearWorkspaceContents(workspace)
                            "Cleared the workspace: removed $removed file(s). The workspace folder itself was kept."
                        }
                        PendingDelete.ClearAttachments -> {
                            val removed = WorkspaceCleaner.clearAllAttachments(fileLibrary)
                            "Cleared attachments: removed $removed file(s)."
                        }
                        PendingDelete.ClearDownloads -> {
                            val removed = WorkspaceCleaner.clearDownloads(workspace)
                            "Cleared downloads: removed $removed file(s)."
                        }
                    }
                }
                resultMessage = message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                resultMessage = "Delete failed: ${e.message}"
            } finally {
                busy = false
                refresh()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .testTag("storage_screen")
        ) {
            resultMessage?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp).testTag("storage_result")
                    )
                }
            }

            // ---- Installed Linux packages ----
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Installed Linux packages", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = when {
                        packagesError != null -> "Could not read the package list: $packagesError"
                        packages == null -> "Reading package list…"
                        packages!!.isEmpty() -> "No extra packages installed."
                        else -> "${packages!!.size} package(s) installed in the Linux workspace."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp).testTag("storage_packages_status")
                )
            }
            if (packages != null) {
                items(packages!!, key = { it.name }) { pkg ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pkg.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(pkg.version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(
                            enabled = !busy,
                            onClick = { pending = PendingDelete.UninstallPackage(pkg) },
                            modifier = Modifier.testTag("storage_uninstall_${pkg.name}")
                        ) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            // ---- Linux workspace ----
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Linux workspace", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (rootfsInstalled) {
                        "Alpine Linux is installed (${formatBytes(rootfsBytes)}). Deleting it removes every installed package and everything inside the Linux workspace."
                    } else {
                        "Linux is not installed."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                OutlinedButton(
                    enabled = !busy && rootfsInstalled,
                    onClick = { pending = PendingDelete.WipeLinux },
                    modifier = Modifier.fillMaxWidth().testTag("storage_wipe_linux")
                ) { Text("Delete Linux workspace") }
                if (!rootfsInstalled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            resultMessage = null
                            scope.launch {
                                try {
                                    val ok = withContext(Dispatchers.IO) { onReinstallLinux() }
                                    resultMessage = if (ok) {
                                        "Linux reinstalled. The terminal works again."
                                    } else {
                                        "Linux reinstall failed. Check the terminal and try again."
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    resultMessage = "Linux reinstall failed: ${e.message}"
                                } finally {
                                    busy = false
                                    refresh()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("storage_reinstall_linux")
                    ) { Text("Reinstall Linux") }
                }
            }

            // ---- Workspace files ----
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Workspace files", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "$workspaceFileCount file(s), ${formatBytes(workspaceBytes)} in the workspace. " +
                        "Clearing keeps the workspace folder itself and staged attachments.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                OutlinedButton(
                    enabled = !busy && workspaceFileCount > 0,
                    onClick = { pending = PendingDelete.ClearWorkspace },
                    modifier = Modifier.fillMaxWidth().testTag("storage_clear_workspace")
                ) { Text("Clear workspace") }
            }

            // ---- Attachments ----
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Attachments", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "$attachmentCount file(s) staged or saved in the library.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                OutlinedButton(
                    enabled = !busy && attachmentCount > 0,
                    onClick = { pending = PendingDelete.ClearAttachments },
                    modifier = Modifier.fillMaxWidth().testTag("storage_clear_attachments")
                ) { Text("Clear attachments") }
            }

            // ---- Downloads ----
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Downloads", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "$downloadCount downloadable file(s) in the workspace.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                OutlinedButton(
                    enabled = !busy && downloadCount > 0,
                    onClick = { pending = PendingDelete.ClearDownloads },
                    modifier = Modifier.fillMaxWidth().testTag("storage_clear_downloads")
                ) { Text("Clear downloads") }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // ---- One confirmation dialog for every destructive action ----
    pending?.let { action ->
        AlertDialog(
            onDismissRequest = { if (!busy) pending = null },
            title = { Text(action.title) },
            text = { Text(action.message) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { runDelete(action) },
                    modifier = Modifier.testTag("storage_confirm_delete")
                ) { Text(action.confirmLabel, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pending = null }) { Text("Cancel") }
            },
        )
    }
}

private data class RefreshData(
    val pkgs: Result<List<InstalledPackage>>,
    val wsCount: Int,
    val wsBytes: Long,
    val attCount: Int,
    val dlCount: Int,
    val installed: Boolean,
    val linuxBytes: Long,
)

/** One pending confirmed delete: title + warning + confirm label for the dialog. */
private sealed class PendingDelete(val title: String, val message: String, val confirmLabel: String) {
    class UninstallPackage(val pkg: InstalledPackage) : PendingDelete(
        title = "Uninstall ${pkg.name}?",
        message = "This removes ${pkg.name} (${pkg.version}) from the Linux workspace. " +
            "Anything that depended on it will stop working until you install it again.",
        confirmLabel = "Uninstall",
    )
    object WipeLinux : PendingDelete(
        title = "Delete Linux workspace?",
        message = "This wipes the whole Alpine Linux rootfs — every installed package " +
            "and everything inside the Linux workspace. The Linux terminal will stop " +
            "working until you reinstall Linux. This cannot be undone.",
        confirmLabel = "Delete",
    )
    object ClearWorkspace : PendingDelete(
        title = "Clear workspace?",
        message = "This deletes ALL workspace files. The workspace folder itself is kept, " +
            "and staged attachments are not touched (use Clear attachments for those). " +
            "This cannot be undone — export anything you need first.",
        confirmLabel = "Delete all",
    )
    object ClearAttachments : PendingDelete(
        title = "Clear attachments?",
        message = "This deletes every staged attachment and every saved file in the library. " +
            "This cannot be undone.",
        confirmLabel = "Delete all",
    )
    object ClearDownloads : PendingDelete(
        title = "Clear downloads?",
        message = "This deletes every downloadable workspace file. Exported copies outside " +
            "the workspace are not affected. This cannot be undone.",
        confirmLabel = "Delete all",
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
