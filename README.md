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
