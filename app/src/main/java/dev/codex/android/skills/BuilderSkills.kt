package dev.codex.android.skills

/**
 * Builder skills for the Codex agent (M9 builder-skills batch).
 *
 * Seeded by the coordinator alongside `SkillStore.BUILTINS` — this file
 * only DEFINES the entries; it does not write them to any database.
 * Every `toolNames` entry names a tool that actually exists in this
 * build (see `tools/BuilderTools.kt`, `tools/DeveloperTools.kt`,
 * `tools/Tool.kt`, `browser/BrowserSubagentTools.kt`):
 * - `mcp_scaffold`, `skill_eval`, `webapp_test`, `webapp_build`:
 *   the four new builder tools.
 * - `workspace_write`, `workspace_read`, `workspace_list`,
 *   `shell_exec`, `github_publish`: developer tools.
 * - `browser_open`, `browser_dom_snapshot`, `browser_console_logs`:
 *   VerificationBrowser tools. (`browser_eval` exists as a tool class
 *   but the chat registry filters it out, so no skill may name it.)
 *
 * Deliberately NOT named anywhere here: the `skill` tool is registered
 * in the chat loop (ChatViewModel.policyGatedRegistry), but no skill
 * needs to name it — the loop offers it directly. The document and
 * image tools (`read_pdf` / `read_document` / `read_text_file` /
 * `read_image`) exist in this build and are named by the document and
 * image skills instead.
 *
 * These entries are for APP-BUILDING ONLY where stated (firebase,
 * mobile-ads): they teach the agent to add Firebase / Mobile Ads to
 * apps it builds for the user. They do NOT add Firebase or Mobile Ads
 * SDKs to the Codex app itself.
 *
 * Attribution: the instructions below were written fresh for this app.
 * They were informed by the Apache-2.0 reference skills studied in
 * `~/workspace/skills-research` — anthropics mcp-builder,
 * skill-creator, webapp-testing, web-artifacts-builder; composio
 * mcp-builder, skill-creator, webapp-testing; google firebase-basics —
 * and no reference text or script was copied verbatim.
 */
val BUILDER_SKILLS: List<Skill> = listOf(
    Skill(
        id = "mcp-builder",
        name = "Build MCP servers",
        description = "Scaffolds and registers minimal MCP (Model Context Protocol) servers in Python or TypeScript that expose tools to LLM clients over stdio. Use when the user wants a new MCP server, or wants to add tools to an existing one.",
        instructions = "When the user wants an MCP server, first pin down what it is for: which external service or data it wraps, and which concrete tasks it must enable (not just which API endpoints exist). Design workflow tools that finish a whole task in one call (e.g. create-and-schedule, not just create), with short prefixed action names (e.g. notes_create), concise descriptions, and results that return focused data, never whole API dumps. Errors must say what went wrong and what to do next. Then scaffold with mcp_scaffold (language python or typescript; default python): it writes a working stdio JSON-RPC server with one echo tool, a manifest and a README under mcp-servers/<name>/, and registers it in the app MCP config when a hub is wired. Add each real tool to the tool list and to the tools/call handler with workspace_write, keeping the server dependency-free unless the user asks for a library. Smoke-test the server with shell_exec by piping one JSON-RPC line into it (initialize, then tools/list, then tools/call for the new tool) and read the reply before declaring it done. Publish the finished server with github_publish when the user asks. Honest limit: live stdio calls inside this app need the stdio transport, which is not built yet — until then the server runs standalone from the Linux workspace, and the scaffold output says so.",
        toolNames = listOf("mcp_scaffold", "workspace_write", "workspace_read", "shell_exec", "github_publish"),
        version = 1,
        enabled = true,
    ),
    Skill(
        id = "skill-creator",
        name = "Create and evaluate skills",
        description = "Drafts a new skill for the agent, writes test cases for it, and runs the cases through the skill-eval harness to measure whether the skill names the right tools and covers the right guidance. Use when the user wants a new skill, wants to improve one, or wants to check one works.",
        instructions = "Work in a loop: intent, draft, test, revise. First capture intent from the user: what the skill must enable, when it should trigger (exact phrases or situations), what output format is expected, and whether test cases are wanted (yes for skills with verifiable outputs — file transforms, code generation, fixed steps; optional for style/subjective skills). Draft the skill as: a one-line description that states when to use it, instructions written as concrete steps naming the exact tools to call, and a tool list naming ONLY tools that exist in this build. Then write 3-5 test cases as JSON [{name, input, expectedTools, expectedOutputSubstring, forbiddenTools}] and run them with skill_eval. Read the per-case PASS/FAIL report: a FAIL means the skill definition is missing a tool or a piece of guidance — fix the draft and re-run, do not argue with the report. Repeat until all cases pass, then expand the test set if the skill matters. Honest limit: skill_eval is a static eval — it proves the skill resolves and names the right tools through the real skill tool path, but it does not run a live model turn; the final check is always the user trying the skill on a real request.",
        toolNames = listOf("skill_eval", "workspace_write", "workspace_read"),
        version = 1,
        enabled = true,
    ),
    Skill(
        id = "webapp-testing",
        name = "Test web apps",
        description = "Tests a web page with Playwright in the Linux workspace: checks that selectors exist and expected text appears, and reports console errors. Use when the user wants a built web app or page verified, or a UI bug reproduced.",
        instructions = "To test a page, call webapp_test with its URL (http(s):// or file://) and a list of checks [{name, selector, expectText?}]. Each check must name a real CSS selector visible on the page — read the page source first with workspace_read when it is a local artifact, or open it with browser_open and inspect it with browser_dom_snapshot when it is live. One check per behaviour that matters (page loads, key heading present, button present, form submits). Report the result exactly as the tool returns it: pass/fail per check plus the failing assertion. If the tool says it fell back to the VerificationBrowser, say so plainly and do not call it a Playwright pass. Console errors reported by the run are failures, not noise — include them. When a check fails, fix the app (workspace_write) and re-run the same checks; never edit the checks to make them pass. Never claim a page works because it looked right in a screenshot or in the source — only a green test run counts.",
        toolNames = listOf("webapp_test", "workspace_write", "workspace_read", "shell_exec", "browser_open", "browser_dom_snapshot", "browser_console_logs"),
        version = 1,
        enabled = true,
    ),
    Skill(
        id = "web-artifacts-builder",
        name = "Build React web apps",
        description = "Builds real React + Vite + Tailwind CSS web apps from scratch in the workspace and builds them with npm in the Linux workspace. Use when the user wants a web app or artifact with state, routing, or components — not a single static page.",
        instructions = "When the user wants a web app, scaffold it with webapp_build (name only; it creates a React 18 + Vite + Tailwind project under web-artifacts/<name>/ and runs npm install + npm run build). Then build the real app file by file with workspace_write: start from src/App.jsx, add components under src/, keep state in React (useState/useEffect), and style with Tailwind classes. Design plain and readable: no purple gradients, no wall of centred text, no decorative filler — every element must do something, and every button must work. After each meaningful change, rebuild with shell_exec (cd /workspace/web-artifacts/<name> && npm run build) and read the errors before continuing; a build that never ran is not a build. Verify the finished app with the webapp-testing skill before handing it over. Publish the source with github_publish when the user asks. Honest limit: the build runs in the Linux workspace and needs the Node.js toolchain staged — if webapp_build reports Node is unavailable, say so and stop; never claim a build succeeded without a dist/ path in the tool output.",
        toolNames = listOf("webapp_build", "workspace_write", "workspace_read", "shell_exec", "github_publish"),
        version = 1,
        enabled = true,
    ),
    Skill(
        id = "firebase",
        name = "Add Firebase to built apps",
        description = "Adds Firebase to apps the agent builds for the user: CLI setup and login, project init, Hosting deploys, and Auth/Firestore basics for generated apps. App-building only — never adds Firebase to the Codex app itself. Use when a built app needs a backend, login, database, or hosting.",
        instructions = "Firebase here is for APPS THE AGENT BUILDS, never for the Codex app itself — do not add Firebase SDKs, config, or dependencies to Codex. For a generated app: first make sure the app builds on its own (web-artifacts-builder skill), then work in the Linux workspace with shell_exec. Check the CLI with `npx -y firebase-tools@latest --version` (it is staged with npm; if it is missing, say so and stop). Log in only when the user is present: `firebase login --no-localhost` is interactive and prints a URL for the user to open — never automate, fake, or bypass login, and never put service-account keys or any credential in workspace source (workspace_write refuses credential-like content). Create or select the project with `firebase projects:create` / `firebase use`, initialise with `firebase init` (Hosting, and Firestore/Auth only if the app needs them), and deploy with `firebase deploy --only hosting`. For Auth, use email or Google sign-in in the generated app and keep the Firebase config (apiKey etc.) as the project's public web config, not a secret. For Firestore, start with locked-down rules and open only the collections the app actually reads/writes. After deploy, verify the live URL with the webapp-testing skill before reporting success.",
        toolNames = listOf("shell_exec", "workspace_write", "workspace_read", "webapp_build", "github_publish"),
        version = 1,
        enabled = true,
    ),
    Skill(
        id = "mobile-ads",
        name = "Add Mobile Ads to built apps",
        description = "Adds Google Mobile Ads (AdMob) to Android/iOS apps the agent builds for the user: AdMob setup, banner and interstitial ads, with test ads first. App-building only — never adds Mobile Ads to the Codex app itself. Use when a built app should earn from ads.",
        instructions = "Mobile Ads here is for APPS THE AGENT BUILDS, never for the Codex app itself — do not add any ads SDK, ad unit, or AdMob config to Codex. For a generated Android/iOS app: first confirm the user has (or will create) their own AdMob account and app entry — ads cannot be tested without one, and the AdMob app ID and ad unit IDs belong to the user, never to a shared or example account. Add the Google Mobile Ads SDK to the generated app only (Gradle dependency for Android, Swift Package/CocoaPods for iOS), initialise it once at app start, and put the AdMob app ID in the app manifest/plist exactly as AdMob documents. Implement banner ads first (anchored, never covering content or controls), then interstitials only at natural breaks (never on launch, never mid-task, at most one per user action). ALWAYS use Google's official test ad unit IDs and test devices during development — real ads during testing can get the user's AdMob account flagged. Only swap in the user's real ad unit IDs when they explicitly say the app is ready, and tell them to check the AdMob dashboard themselves for fill and policy status. Never click the app's own ads, never promise earnings, and never add ads to an app the user did not ask to monetise.",
        toolNames = listOf("workspace_write", "workspace_read", "shell_exec", "github_publish"),
        version = 1,
        enabled = true,
    ),
)
