# Codex for Android

**Codex for your phone.** An Android-native autonomous agent: it plans, operates apps, handles errors, remembers tasks, and asks permission for sensitive actions. The brain is an OpenAI-compatible API (the phone calls it for all reasoning and planning); the phone is the body — a lightweight Kotlin runtime with on-device memory, UI automation, skills, and a scheduler. The end user brings their own API key (BYOK) or configures an endpoint; no key is ever shipped in the app. It is built to run on any Android phone, including low-end devices, and to stay alive through a virtual-space survival layer (foreground service, battery exemption, boot receiver, resurrection, full state persistence). Phase 6 adds an agentic app store: the agent builds apps, they publish to a mini store inside the Codex ecosystem, and can be sold, with the platform taking a cut.

## Build phases

- **Phase 0 — Toolchain (this commit):** Java + Android SDK + Gradle + Kotlin skeleton, hello-world APK, CI verification.
- **Phase 1 — Agent runtime + API integration:** OpenAI-compatible API client (BYOK), planning, tool calling, error handling, permission gating for sensitive actions. Fine-tune prompts, tool use, and logic in a build → measure → fine-tune → debug loop.
- **Phase 2 — Android control:** Accessibility Service + UI automation (no root, no ADB), app launching, UI interaction, messaging, browser, files. Optimised for speed and reliability on low-end phones.
- **Phase 3 — Tools + memory + skills:** on-device memory (SQLite), long-term memory, reusable skills, MCP-style tool interface.
- **Phase 4 — Virtual space + background execution:** the mini virtual space (own scheduler, memory, lifecycle, tool runtime) with foreground service, battery exemption, boot receiver, WorkManager + AlarmManager resurrection, OEM whitelisting, full state persistence; scheduled tasks and notifications.
- **Phase 5 — Polish, security, consumer UX:** permissions flow, battery, adaptive performance by device, polished UI. Verified on a low-end device profile and against a kill-and-resume test.
- **Phase 6 — Agentic app building + mini app store:** the agent builds apps (code → APK → test); a mini store lists them; users publish and sell; platform takes a cut. Store listing, payments, review/moderation. Only after Phases 0–5 work.

## How to build

### CI (the verified path)

Every push to `main` builds the debug APK in GitHub Actions (Ubuntu, JDK 17 Temurin) and uploads it as the `app-debug` artifact. Download it from the Actions run.

### Local

Prerequisites: JDK 17, Android SDK with platform 34 and build-tools 34.0.0, Gradle 8.11 (via the wrapper — AGP 8.7.3 requires Gradle 8.9+; the locally installed Gradle 8.7 is too old, so always build through `./gradlew`).

```sh
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## BYOK — keys are never shipped

Build/development is driven directly by the Nyx agent — no third-party API key is used during development and none is ever compiled into the app. The shipped app talks to an OpenAI-compatible API using a key the end user provides, or an endpoint they configure.

## Package id

The current package id is `dev.codex.android` — **provisional**. Ziyad has not chosen a final package id; it is trivially changeable (namespace + `applicationId`) before the first release.

## Licence

Apache License 2.0 — see [LICENSE](LICENSE).

## Low-end device profile

The target is phones with 3–4 GB RAM, 8 cores or fewer, Android 8+ (minSdk 26) — e.g. the developer's own NARZO 70 Pro 5G class of device and cheaper. The app is built to stay light on such phones:

- **No on-device LLM** — all reasoning happens through the user's own OpenAI-compatible API (the API brain); the phone only runs a lightweight Kotlin runtime.
- **SQLite, no Room** — on-device memory is plain SQLite, with no heavy ORM overhead.
- **Bounded work everywhere** — node walks are capped at 500 nodes, screen text at 4000 chars, and stored message history at 500 rows, so no single operation can balloon memory on a constrained phone.
- **Capped message history** — the chat restores at most 20 recent messages on launch (10 on low-RAM devices, via `PerformancePolicy.historyLimit`).
- **Adaptive resurrection interval** — the periodic resurrection worker re-arms alarms every 12 hours on low-RAM devices and every 6 hours otherwise (`DeviceProfile` + `PerformancePolicy.resurrectionIntervalHours`), so low-end phones wake less often and save battery.
- **3s operation timeouts** — operations time out quickly instead of hanging a slow phone.

Device detection (`dev.codex.android.util.DeviceProfile`) uses `ActivityManager.isLowRamDevice` and total RAM: LOW when the OS flags low-RAM or total RAM is under 4 GB, HIGH when RAM is 8 GB+ and there are 8+ cores, otherwise MEDIUM. Detection never crashes — any failure degrades to MEDIUM. The agent loop's turn budget is likewise scaled by device class in `PerformancePolicy.maxTurns` (8 on LOW, 12 otherwise), as the documented default for a future wiring; the loop currently still hardcodes 12.

**Not yet verified on a low-end device:** kill-and-resume, exact-alarm delivery, and tap/scroll timing on a real low-end phone. These have not been tested on real low-end hardware — stated plainly, not assumed.

## Mini app store (Phase 6)

The agent can generate small apps from three templates (counter, notes, timer) and list them in a local store catalogue with a price in INR and a moderation flag (approved / pending). The APK build runs on a build service, not on the phone.

Payments are NOT live: the Buy button shows a "Payments are not live yet" dialog, and real payments and publishing are future work. The store is a skeleton, not a marketplace.

### Coding and building from the phone

- In **Workbench → Agent**, switch from **Plan** to **Build** to allow edits and commands. Ask the agent to create or edit source files; review each approval. The source is visible under **Workbench → Files**. The Linux shell is separate from Android Accessibility and requires the ARM64 runtime. To check it, ask the agent to run `echo terminal-ok` and approve the exact command.
- In **Settings → GitHub Actions**, select an existing project repository (`owner/repo`). Configure a fine-grained token limited to that repository in the app's Settings field, not in the conversation. Use **Contents: write** for publishing and **Actions: write** for manual dispatch. Keep build secrets and signing keys in GitHub Actions, not in the workspace.
- For an APK, use a repository with an Android build workflow and an APK-upload step. The workflow must accept `workflow_dispatch` on the default branch for the in-app dispatch to work. This repository's `.github/workflows/build.yml` supports manual dispatch once that version is on the default branch; its normal PR and main builds continue to work.
- Ask the agent to publish the selected project files on a `codex/<name>` branch, then dispatch `build.yml`. Approve the target and file list before publishing, and the workflow and branch before dispatch. The agent does not initialize an empty repository, force-push, publish hidden files, or upload an APK from the phone. Use a dedicated project repository for a new app rather than publishing unrelated code into your existing app.
- After GitHub accepts the request, ask **“Check the build status for `codex/<name>`”**. Compare the commit SHA to the published commit if there are several runs. Open the reported run for logs or its artifact link to download the ZIP containing the APK. A queued build is not an APK. The template Store only saves template source; it does not submit builds.

### Providers, chat context, and automations

- In **Settings**, choose a provider preset or **Custom**, enter your key, and tap **List models**. Selecting a returned model saves that selection. A stored key is reused only for its saved endpoint.
- **New chat** starts a separate thread; earlier threads are still available in the Workbench menu. **Summarise** (or `/compact`) uses the configured model to save a handoff summary. Later turns use that summary instead of the older messages. The composer estimates draft tokens, not provider usage. **Settings → Chat context** configures the model window and automatic compaction. When the history budget is full, an extra model call summarises an older prefix and keeps recent messages as written when they fit. The transcript is not deleted.
- **Settings → Automations** opens the recurring task manager. Runs are scheduled by Android WorkManager, with a minimum 15-minute interval; timing is not exact. Network and device constraints still apply. For comparison, OpenCode has model-aware automatic context compaction with a retained recent window. Its schedule schema also tracks time zones, next due times, run limits and retry times. Codex Android uses a configurable history budget rather than a provider tokenizer and does not run when the phone is off.
- **Preview** opens HTTP or HTTPS pages, including loopback pages when you have a server running there. The interactive agent can open and inspect the shared browser. Page navigation and interactions from the agent require approval.

### Memory across chats

Say `Remember that I prefer short answers` or enter `/remember preference.style = Short answers` in the composer. Named notes can be updated by saving the same name again. `/memory [search]` lists saved notes locally; `/forget name` removes one. **Settings → Memory** can search, add, edit, delete, or pause recall without deleting saved notes. No provider is needed for these actions.

The agent retrieves a small set of relevant notes plus standing preferences for new requests, including CLI and scheduled runs. Recall sends those notes to the configured provider as background data. Notes are stored in the existing app-private database, excluded from backup; no separate memory service, raw transcript indexing, or credential storage. Removing a note stops future retrieval of it, but doesn't erase older chats or data already sent to a provider.

The design adapts the [OpenClaw memory model](https://github.com/openclaw/openclaw/blob/main/docs/concepts/memory.md): durable, user-visible notes separate from session transcripts, with bounded recall. This is a native implementation using the app's database and runner rather than a port of the upstream gateway.
