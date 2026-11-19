# Security review — Codex for Android, Phase 5

Reviewed by reading the code on 2026-11-19 (HEAD `fc3706d`, Phase 4).
No live device test was run; Gradle is broken on this VM, so nothing
was compiled or executed for this review.

## What was checked

- `data/KeyStore.kt` — BYOK key storage.
- `agent/AgentLoop.kt`, `control/ScreenSnapshot.kt`,
  `service/CodexAccessibilityService.kt` — all `Log.*` calls.
- `service/BootReceiver.kt`, `service/CodexAgentService.kt`,
  `service/NotificationHelper.kt` — all `Log.*` calls (17 total in `app/`).
- Grep for hardcoded secrets across `app/src/main/java` (see below).
- `tools/ToolManifest.kt` — what the tool manifest exposes.
- `tools/ScheduleTaskTool.kt`, `agent/AgentLoop.kt` — rate limiting.
- `ui/SettingsScreen.kt` — how the key is shown on screen.
- `ui/ChatViewModel.kt` + `agent/PlanStore.kt` / `agent/RunStateStore.kt` —
  the process-death restore chain.

## Findings

1. **No hardcoded key, token, or secret.** Grep for
   `"sk-[A-Za-z0-9]{10,}"`, `"sk-proj-…"`, `AKIA[0-9A-Z]{16}`,
   `ghp_/gho_[A-Za-z0-9]{20,}`, `AIza…`, and
   `(apiKey|password|secret|token) = "…"` across `app/src/main/java`
   returned no matches. The only near-hit is the constant name
   `KEY_API_KEY = "api_key"` in `KeyStore.kt` — a preference key name,
   not a credential. `DEFAULT_BASE_URL` is `""`; there is no default
   provider or endpoint. The key is used in exactly one place:
   `ChatViewModel` passes `keyStore.getApiKey()!!` to the API client.
2. **BYOK key storage.** The key lives only in `EncryptedSharedPreferences`
   (AES256-GCM master key, AES256-SIV key encryption, AES256-GCM value
   encryption). It is never logged (`KeyStore` logs one fixed warning
   string when encrypted storage is unavailable, with no key or exception
   detail), and `SettingsScreen` shows the entry field masked with
   `PasswordVisualTransformation()` and never pre-fills the stored key.
3. **Logging is clean.** `AgentLoop.kt` and `ScreenSnapshot.kt` contain
   zero `Log.*` calls. `CodexAccessibilityService.kt` has four:
   `Log.i` "Accessibility service connected", `Log.i` "… interrupted",
   and two `Log.w` "Root window operation failed: ${e.javaClass.simpleName}".
   The other 13 `Log.*` calls (BootReceiver, CodexAgentService,
   NotificationHelper, KeyStore) are the same pattern: a fixed message
   plus an exception class name. No call logs screen text, package
   names, view ids, user messages, goals, or key material.
   `ScreenSnapshot`'s own header comment states the rule explicitly:
   "Nothing here logs anything: node text belongs to other apps."
4. **Tool manifest exposes metadata only.** `ToolRegistry.manifestJson()`
   emits per tool only `name`, `description`, and `inputSchema`.
   Tools carry no keys, tokens, or secrets, so none can leak through it.

## Gaps / open items

1. **Plaintext fallback for the key.** If `EncryptedSharedPreferences`
   cannot be created (broken Android Keystore on some devices),
   `KeyStore` falls back to plain `SharedPreferences`
   (`codex_keys_fallback`, `MODE_PRIVATE`). On such a device the API
   key is stored in plaintext on disk, readable by any process with
   root or a backup of the app's data. Consider warning the user, or
   refusing to store the key, when the encrypted store is unavailable.
2. **Kill-and-resume path is code-reviewed only, never exercised.**
   The restore chain is: `ChatViewModel` init loads the latest plan
   (`PlanStore.loadLatest()`, "Resumed plan: …") and the newest
   messages (`MemoryStore.recentMessages`), while `RunStateStore`
   persists run state for the agent service. No on-device test has
   verified process death → relaunch actually restores all three
   consistently. Needs a real-device kill test before this is claimed.
3. **No rate limit on tool calls beyond the turn budget.**
   `AgentLoop.MAX_TURNS = 12` bounds model turns, but a single turn
   can invoke several tools, and `schedule_task` (not approval-gated)
   has no per-run or per-day cap — a runaway model could schedule
   many alarms. Consider a per-run tool-call cap and a cap on
   scheduled tasks.
