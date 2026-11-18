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

Build/development keys (Coarena and others) are used only during development and are **never** compiled into the app. The shipped app talks to an OpenAI-compatible API using a key the end user provides, or an endpoint they configure.

## Package id

The current package id is `dev.codex.android` — **provisional**. Ziyad has not chosen a final package id; it is trivially changeable (namespace + `applicationId`) before the first release.

## Licence

Apache License 2.0 — see [LICENSE](LICENSE).
