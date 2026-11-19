# Codex for Android — Release Hardening Notes (Milestone M10)

This document records the exact release-hardening settings applied to the final
`v2.0.0` build. It is the single source of truth for the release checklist; do not
rely on memory or on stale `SECURITY.md` snippets.

Adapted 2026-11-20 from the H6 staged delivery. Adaptation: the §1 keep rules
for the app's own service/receiver classes had the WRONG packages in the staged
file (`dev.codex.android.agent.*`); the real packages were verified against the
repo and corrected below (`service.*` / `scheduler.*`). The R8 block in §1 is
identical to the full `app/proguard-rules.pro` shipped with this adaptation.

## 1. R8 / ProGuard keep rules (added to `app/proguard-rules.pro`)

```proguard
# Keep kotlinx-serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep class dev.codex.android.**$$serializer { *; }

# Keep Sora Editor runtime (loaded reflectively by the editor)
-keep class io.github.rosemoe.sora.** { *; }
-dontwarn io.github.rosemoe.sora.**

# Keep Termux terminal-view/emulator runtime
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
-dontwarn com.termux.**

# Keep PRoot native entry points (JNI-free, but keep the wrapper classes)
-keep class dev.codex.android.linux.** { *; }

# Keep WorkManager / foreground-service entry points.
# CORRECTED packages (verified against the repo 2026-11-20): the staged file
# listed these under dev.codex.android.agent.*, which does not exist.
# Real locations: service/CodexAgentService.kt, scheduler/TaskReceiver.kt,
# service/BootReceiver.kt, service/AutomationWorker.kt.
-keep class dev.codex.android.service.CodexAgentService { *; }
-keep class dev.codex.android.scheduler.TaskReceiver { *; }
-keep class dev.codex.android.service.BootReceiver { *; }
-keep class dev.codex.android.service.AutomationWorker { *; }
```

## 2. Backup exclusion rules (added to `AndroidManifest.xml`)

```xml
<application
    android:allowBackup="false"
    android:fullBackupContent="@xml/backup_rules"
    android:dataExtractionRules="@xml/data_extraction_rules">
```

`res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` exclude:

- `codex.db` (memory, plans, threads) — `CodexDatabase.DATABASE_NAME`, verified in repo
- `codex_cost.db` (cost telemetry) — `CostTelemetry.DATABASE_NAME`, verified in repo
- `codex.db-wal` / `codex.db-shm`, `codex_cost.db-wal` / `codex_cost.db-shm` (WAL sidecars)
- `snapshots/` (shadow-git blobs) — `filesDir/snapshots/<workspace>/`, verified in staged `SnapshotEngine`
- `knowledge/` (knowledge base) — `filesDir/knowledge/<workspace>/kb_*.md`, verified in staged `KnowledgeBase`
- `bin/daemon.env` (per-boot Bearer token) — `filesDir/bin/daemon.env`, verified in staged `CodexCli` (`DAEMON_ENV_FILENAME = "daemon.env"`, 0600, rotated per boot)
- `codex_keys.xml` / `codex_keys_fallback.xml` (EncryptedSharedPreferences incl. the encrypted keyset) — `KeyStore.PREFS_FILE = "codex_keys"`, verified in repo `data/KeyStore.kt`. The AndroidKeyStore master key itself is never part of app backup.

Note: `android:allowBackup="false"` alone disables Auto Backup (cloud). The
XMLs are still required: `data_extraction_rules.xml` (API 31+) also governs
device-to-device transfer, and `backup_rules.xml` covers pre-31 devices.
Apply-ready copies of both XMLs ship with this adaptation.

## 3. `applicationId` note

The current `applicationId` is `dev.codex.android` (provisional) — verified in
`app/build.gradle.kts` `defaultConfig` on 2026-11-20 (versionCode 11,
versionName "1.0.0"). The single change site is `app/build.gradle.kts`
`defaultConfig`; `android.namespace` in the same file must change with it.
Before the Play Store listing, settle the final applicationId — changing it
after the first Play release is impossible without losing the listing. The
final release APK must use the settled ID; v2.0.0 keeps `dev.codex.android`
until Ziyad settles the final Play Store applicationId.

## 4. Release checklist (v2.0.0)

- [ ] All 10 milestones green in CI (unit + emulator instrumented tests).
- [ ] `versionCode` / `versionName` bumped to `2.0.0` (currently 11 / "1.0.0" — release-time edit, not done by this adaptation).
- [ ] R8 rules above applied; release APK built with `minifyEnabled = true` (currently `false` in `app/build.gradle.kts` — flip at release time, see BUILD_DIFF.md).
- [ ] Backup exclusion XMLs present; `allowBackup="false"`.
- [ ] `SELF_HOSTING.md` 10-step proof executed on a real device (or emulator if no real device) and logged.
- [ ] No default provider, endpoint, key, or secret anywhere in the APK.
- [ ] Donations: UPI `ziyad.shafeek@oksbi` only; tip unlocks nothing.
- [ ] LICENSES.md complete (PRoot GPL-2.0, BusyBox GPL-2.0, Sora LGPL-2.1,
      Termux Apache-2.0, OpenCode MIT, models.dev MIT) — verified present in repo `LICENSES.md`.
