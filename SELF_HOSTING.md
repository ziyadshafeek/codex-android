# Codex for Android — Self-Hosting Proof (Milestone M10)

This document proves the v2.0.0 app can **clone its own repository, build itself,
push a fix, heal its own CI, download the new APK, and upgrade itself** — the final
self-hosting milestone. Run these 10 steps on a real Android phone (no laptop).

> Execution note (adaptation, 2026-11-20): this proof is EXECUTED by the
> integrator, not by this adaptation — no step below has been run. If no real
> device is available, run it on the CI emulator instead and log that
> substitution against step 10; an emulator run proves the loop but is not a
> real-device proof.

## Prerequisites

- Codex for Android v2.0.0 installed (release APK).
- A BYOK API key + endpoint configured in Settings (any OpenAI-compatible provider).
- A GitHub Personal Access Token (classic, `repo` + `workflow` scopes) saved in
  Settings → GitHub (BYOK, encrypted in KeyStore; never logged).
- Accessibility service enabled (Settings → Accessibility → Codex).
- The phone on Wi-Fi with at least 2 GB free storage (rootfs + Gradle cache).

## The 10 steps

1. **Open Codex → New Thread → "Clone the codex-android repo"**
   Say: `Clone https://github.com/ziyadshafeek/codex-android into ~/codex-android using the GitHub PAT from Settings.`
   Expected: a worktree/clone appears under `~/codex-android` with a clean `git status`.

2. **Ask the agent to make a real change**
   Say: `In ~/codex-android, bump the versionName to 2.0.1 in app/build.gradle.kts and commit it as "chore: self-hosting proof bump".`
   Expected: a commit exists on a new branch; `git log -1` shows the message.

3. **Verify the change in the editor**
   Open Files → `~/codex-android/app/build.gradle.kts`; confirm `versionName = "2.0.1"`.
   Expected: the Sora editor shows the bumped version with syntax highlighting.

4. **Push the branch from the phone**
   Say: `Push the branch to origin and open a Pull Request titled "Self-hosting proof: version bump".`
   Expected: a PR URL is returned (e.g. `https://github.com/ziyadshafeek/codex-android/pull/N`).

5. **Watch CI from the phone**
   Open the PR URL in the Verification Browser (WebView) and wait for the GitHub
   Actions checks to run.
   Expected: both jobs (build + emulator test) appear; wait for completion.

6. **If CI fails, ask the agent to heal it**
   Say: `CI failed on the PR — read the failing log, fix the cause, push again.`
   Expected: the agent reads the log via `gh`/`curl`, edits the failing code,
   commits, and pushes a fix (SelfHealingBuildLoop).

7. **Download the new APK from the phone**
   When CI is green, open the Actions run → Artifacts → download `app-debug.apk`
   (or the release artifact) into `~/Downloads`.
   Expected: the APK file exists and its size is > 10 MB.

8. **One-tap self-install**
   Tap the downloaded APK → Install → allow "Install unknown apps" for Codex once.
   Expected: the installer shows version 2.0.1 and offers Update.

9. **Verify the upgrade**
   After install, open Codex → Settings → About; confirm version `2.0.1`.
   Expected: the app opens, threads/history survive, and the agent still answers.

10. **Record the proof**
    Say: `Record this self-hosting proof in the knowledge base with the PR URL, CI run ID, and APK size.`
    Expected: a `SELF_HOSTING.md` proof entry appears in Knowledge Base search
    for "self-hosting proof".

## Pass criteria

All 10 steps complete with no laptop, no adb, and no manual `git` typing by the
user. Any step that requires a laptop is a FAIL — file it as a v2.0.1 bug with the
exact step number and the blocking error.
