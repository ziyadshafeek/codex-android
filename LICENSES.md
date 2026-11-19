# Third-Party Open-Source Licence Ledger — Codex for Android

This document records the open-source components, binaries, libraries, reference architectures, and catalog datasets used by **Codex for Android** (`dev.codex.android`), what each covers, and our exact compliance obligations.

## Component & Compliance Ledger

| Component | Licence | What it covers | How we comply |
|---|---|---|---|
| **PRoot** (`libproot.so`, `libproot_loader.so`) | **GPL-2.0** (`GPL-2.0-or-later`) | Standalone user-space `chroot`/`mount --bind` Linux rootfs execution binaries packaged inside the APK (`jniLibs/`) and invoked via `execve`/process boundary (never linked into the Kotlin/Dalvik app process). | Ship the full GPL-2.0 licence text and upstream copyright notices in the repo and APK About/Licences screen; publish the exact corresponding source code, NDK build scripts, toolchain pins, and all patches for every shipped binary version alongside each APK release. |
| **BusyBox** (`libbusybox.so`) | **GPL-2.0** (`GPL-2.0-only`) | Standalone multi-call POSIX core utilities binary packaged inside the APK (`jniLibs/`) and invoked as a separate user-space executable during rootfs bootstrap and shell execution. | Ship the full GPL-2.0 licence text and BusyBox copyright notice; publish the exact BusyBox source archive/commit, `.config` build configuration, build scripts, and any patches corresponding to every shipped binary release. |
| **Rosemoe Sora Editor** (`io.github.Rosemoe.sora-editor`) | **LGPL-2.1** (`LGPL-2.1-only` / `LGPL-2.1-or-later`) | On-device code editor widget consumed as an **unmodified Maven Central AAR** for the workbench `Files` tab. | Consume the upstream Maven Central AAR without modification; include Rosemoe Sora Editor attribution and the LGPL-2.1 licence text in the repo and in-app notices; preserve full relinking/rebuildability via the public Gradle build (`app/build.gradle.kts`) so any user can substitute a modified Sora Editor AAR and rebuild the APK. |
| **Termux `terminal-view` + `terminal-emulator`** (`com.termux.terminal`, `com.termux.view`) | **Apache-2.0** | VT100/ANSI terminal state machine (`terminal-emulator`) and Android `View` renderer (`terminal-view`) modules from Termux, which are explicitly sub-licensed under Apache License 2.0. | Include the Apache-2.0 licence text, upstream Termux copyright notices, and any `NOTICE` attributions in the repo and app; clearly mark any modified files if either module is ever patched locally. |
| **OpenCode** (`anomalyco/opencode`) | **MIT** | Architectural patterns and behavioural conventions ported cleanly from scratch to Kotlin (ordered last-match-wins glob permissions, `SKILL.md` frontmatter discovery rules, `question`/`todowrite`/`todoread` tool contracts, MCP tool naming, multi-provider streaming events). | Keep upstream attribution and the MIT copyright/permission notice in our documentation and licence ledger. (No upstream TypeScript source code is copied verbatim.) |
| **`models.dev` Catalog Data** (`anomalyco/models.dev`) | **MIT** (Public Catalog) | Open model metadata catalog (`https://models.dev/api.json`) and the bundled offline fallback snapshot (`app/src/main/assets/models-snapshot.json`: model IDs, display names, context windows, token pricing, and tool-calling support). | Attribute `models.dev` (`anomalyco/models.dev`) and retain its MIT licence notice; use the data strictly for BYOK metadata enrichment (never shipping default providers or API keys). |

---

## Strict GPL-3.0 Boundary Rule (`termux-app` UI Classes)

> **NON-NEGOTIABLE RULE:**
> Never copy `ExtraKeysView` or any other `termux-app` application/UI classes (which are licensed under **GPL-3.0-only**) into this repository unless the entire Codex for Android application is relicensed under **GPL-3.0**.

- While Termux's `terminal-emulator` and `terminal-view` library modules are licensed under **Apache-2.0**, the top-level `termux-app` module (including `ExtraKeysView`, terminal session activities, drawer UI, and preferences) is **GPL-3.0-only**.
- Any touch extra-keys bar (`ESC`, `TAB`, `CTRL`, `ALT`, arrow keys) or terminal tab chrome in Codex for Android **must** be implemented from scratch in Jetpack Compose.

---

## Release Compliance Checklist

Before publishing or distributing any APK build:

1. **GPL-2.0 Corresponding Source (`PRoot` & `BusyBox`):** Verify that the exact source tarballs/submodules, `.config` files, patches, and reproducible build scripts matching the `libproot.so`, `libproot_loader.so`, and `libbusybox.so` binaries packaged in the APK are committed or published in the release artifacts.
2. **LGPL-2.1 Relinking (`Sora Editor`):** Verify that Sora Editor is referenced as an unmodified Maven Central dependency in `app/build.gradle.kts` and that R8/ProGuard rules do not strip licence notices or prevent rebuilding from source.
3. **In-App Attribution:** Ensure `LICENSES.md` (or its rendered notice list) is accessible from the app's Settings / About section.
