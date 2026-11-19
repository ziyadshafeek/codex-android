# Add project specific ProGuard rules here.
# Phase 0: no custom rules needed.

# --- Milestone M10 (H6) release hardening ---
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
# CORRECTED packages (verified against the repo 2026-11-20): the staged H6
# file listed these under dev.codex.android.agent.*, which does not exist.
# Real locations: service/CodexAgentService.kt, scheduler/TaskReceiver.kt,
# service/BootReceiver.kt, service/AutomationWorker.kt.
-keep class dev.codex.android.service.CodexAgentService { *; }
-keep class dev.codex.android.scheduler.TaskReceiver { *; }
-keep class dev.codex.android.service.BootReceiver { *; }
-keep class dev.codex.android.service.AutomationWorker { *; }

# Release R8: Tink (via androidx.security:security-crypto) references
# com.google.errorprone.annotations.* (CanIgnoreReturnValue,
# CheckReturnValue, Immutable, RestrictedApi), which are compile-time-only
# annotations absent from the runtime classpath. R8 fails
# minifyReleaseWithR8 on the missing classes (CI runs 44639596138,
# 44639680528) — silence those warnings; nothing at runtime needs them.
-dontwarn com.google.errorprone.annotations.**
