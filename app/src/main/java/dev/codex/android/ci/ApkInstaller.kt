package dev.codex.android.ci

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

/**
 * Native APK installer (Master Plan Subsystem 5, M3).
 *
 * Verifies the SHA-256 of a downloaded APK, then installs it natively.
 * Two install paths:
 * - [install] (default): [PackageInstaller.Session] — the true native
 *   install, no file-manager step. Status is delivered to
 *   [ApkInstallStatusReceiver] (must be declared in AndroidManifest.xml
 *   — see the manifest snippet in this file's KDoc below).
 * - [installViaIntent]: FileProvider URI + ACTION_VIEW — fallback for
 *   ROMs where the session API misbehaves.
 *
 * Both paths first check [canInstallUnknownSources] and route cleanly
 * to Settings when the "install unknown apps" permission is missing.
 *
 * Manifest needed (integration applies this — this file does NOT edit
 * the manifest):
 * ```xml
 * <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
 * <provider
 *     android:name="androidx.core.content.FileProvider"
 *     android:authorities="${applicationId}.fileprovider"
 *     android:exported="false"
 *     android:grantUriPermissions="true">
 *     <meta-data
 *         android:name="android.support.FILE_PROVIDER_PATHS"
 *         android:resource="@xml/file_paths" />
 * </provider>
 * <receiver
 *     android:name=".ci.ApkInstallStatusReceiver"
 *     android:exported="false" />
 * ```
 * plus `res/xml/file_paths.xml`:
 * ```xml
 * <?xml version="1.0" encoding="utf-8"?>
 * <paths>
 *     <files-path name="builds" path="builds/" />
 * </paths>
 * ```
 * (`files-path` maps to `filesDir/builds/`, where the engine downloads
 * artifacts — the APK must live under filesDir for FileProvider to
 * serve it.)
 */

sealed class ApkInstallResult {
    /** The install session was created and committed; watch the status receiver for the outcome. */
    data class Started(val sessionId: Int) : ApkInstallResult()

    /** The system installer was launched via Intent. */
    object LaunchedViaIntent : ApkInstallResult()

    /** "Install unknown apps" is not granted for this app — open [settingsIntent] first. */
    data class NeedsUnknownSourcesPermission(val settingsIntent: Intent) : ApkInstallResult()

    /** Pre-flight check failed (missing file, not an APK, SHA mismatch, session error). */
    data class Failed(val reason: String) : ApkInstallResult()
}

/** True when [file]'s SHA-256 matches [expectedSha256] (case-insensitive). JVM-testable. */
fun verifySha256(file: File, expectedSha256: String): Boolean =
    file.isFile && sha256Hex(file).equals(expectedSha256.trim(), ignoreCase = true)

class ApkInstaller(private val context: Context) {

    companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        /** FileProvider authority: `<package>.fileprovider` (must match the manifest). */
        fun fileProviderAuthority(context: Context): String = "${context.packageName}.fileprovider"
    }

    /** API 26+ — true when the user allowed this app to install unknown apps. */
    fun canInstallUnknownSources(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Intent opening this app's "Install unknown apps" Settings page. */
    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Full install flow: file checks -> SHA-256 (when [expectedSha256]
     * is given) -> unknown-sources permission -> native session install
     * (or Intent fallback when [useSessionApi] is false).
     */
    fun install(apkFile: File, expectedSha256: String? = null, useSessionApi: Boolean = true): ApkInstallResult {
        val preflight = preflightCheck(apkFile, expectedSha256)
        if (preflight != null) return preflight
        if (!canInstallUnknownSources()) {
            return ApkInstallResult.NeedsUnknownSourcesPermission(unknownSourcesIntent())
        }
        return if (useSessionApi) installViaSession(apkFile) else installViaIntent(apkFile)
    }

    /** FileProvider URI + ACTION_VIEW install intent (also used as the fallback path). */
    fun installIntent(apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Launches the system installer via [installIntent]. Caller must have run the pre-flight checks. */
    fun installViaIntent(apkFile: File): ApkInstallResult {
        return try {
            context.startActivity(installIntent(apkFile))
            ApkInstallResult.LaunchedViaIntent
        } catch (e: Exception) {
            ApkInstallResult.Failed("Could not launch installer: ${e.message}")
        }
    }

    /**
     * Native install via [PackageInstaller.Session]: streams the APK
     * into a session and commits it; the system then shows the install
     * confirmation and reports the outcome to [ApkInstallStatusReceiver].
     */
    fun installViaSession(apkFile: File): ApkInstallResult {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = try {
            installer.createSession(params)
        } catch (e: Exception) {
            return ApkInstallResult.Failed("Could not create install session: ${e.message}")
        }
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("app.apk", 0, apkFile.length()).use { out ->
                    FileInputStream(apkFile).use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
                session.commit(statusIntentSender(sessionId))
            }
        } catch (e: Exception) {
            try {
                installer.abandonSession(sessionId)
            } catch (_: Exception) {
                // Best effort cleanup only.
            }
            return ApkInstallResult.Failed("Install session failed: ${e.message}")
        }
        return ApkInstallResult.Started(sessionId)
    }

    private fun preflightCheck(apkFile: File, expectedSha256: String?): ApkInstallResult.Failed? {
        if (!apkFile.isFile) return ApkInstallResult.Failed("APK not found: ${apkFile.path}")
        if (!apkFile.name.endsWith(".apk", ignoreCase = true)) {
            return ApkInstallResult.Failed("Not an APK file: ${apkFile.name}")
        }
        if (expectedSha256 != null && !verifySha256(apkFile, expectedSha256)) {
            return ApkInstallResult.Failed(
                "SHA-256 mismatch for ${apkFile.name}: expected $expectedSha256, got ${sha256Hex(apkFile)}",
            )
        }
        return null
    }

    private fun statusIntentSender(sessionId: Int): android.content.IntentSender {
        val intent = Intent(context, ApkInstallStatusReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }
}

/**
 * Receives the PackageInstaller session status for installs started by
 * [ApkInstaller.installViaSession]. Must be declared in
 * AndroidManifest.xml (see the snippet in [ApkInstaller]'s KDoc).
 * Logs the outcome; integration can extend this to post a notification
 * or update the CI card's state.
 */
class ApkInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // System needs the user to confirm: forward the intent.
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS ->
                Log.i("ApkInstaller", "Install succeeded")
            else ->
                Log.w("ApkInstaller", "Install failed: status=$status message=$message")
        }
    }
}
