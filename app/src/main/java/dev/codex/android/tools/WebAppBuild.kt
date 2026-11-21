package dev.codex.android.tools

/**
 * React + Tailwind web-artifact pipeline (tool: `webapp_build`,
 * skill: web-artifacts-builder).
 *
 * Scaffolds a REAL app from scratch in the workspace — React 18 + Vite 5
 * + Tailwind CSS 3.4 (PostCSS build, no CDN) — then runs `npm install`
 * and `npm run build` inside the Linux workspace and returns the `dist/`
 * output path plus any errors. This is deliberately NOT the store's
 * template path (`store/AppGenerator`): templates are for quick canned
 * apps; this builds a real project the agent then edits file by file.
 *
 * Staging: Node.js comes from `RootfsManager.ensureToolchain("node")`
 * (Alpine `nodejs` + `npm`) via the caller-supplied seam — see
 * [dev.codex.android.linux.BuilderToolchainStaging]. If Node is not
 * staged, the tool fails honestly and never pretends a build happened.
 * Pure logic here (scaffold + output parsing); the tool in [BuilderTools]
 * owns the shell. No Android APIs in this file.
 */
object WebAppBuild {

    /** One file of the generated project, relative to the project directory. */
    data class ScaffoldFile(val relativePath: String, val content: String)

    /** Error message for an invalid app [name], null when fine. */
    fun validate(name: String): String? {
        if (name.isBlank()) return "App name must not be blank."
        if (!name.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}"))) {
            return "Invalid app name '$name': start with a letter, then letters, digits, '-' or '_' (max 64 chars)."
        }
        return null
    }

    /** `npm install` command for a project at guest path [projectDir]. */
    fun installCommand(projectDir: String): String =
        "cd $projectDir && npm install --no-audit --no-fund"

    /** `npm run build` command for a project at guest path [projectDir]. */
    fun buildCommand(projectDir: String): String =
        "cd $projectDir && npm run build"

    /** Result of parsing one `npm run build` run. */
    data class BuildResult(
        val ok: Boolean,
        /** Guest-side `dist/` path when the build succeeded, null otherwise. */
        val distPath: String?,
        /** Build error lines (empty on success). */
        val errors: List<String>,
        /** Last lines of raw output, for the model to read. */
        val rawTail: String,
    )

    /**
     * Parses an `npm run build` run: [exitCode] and the combined
     * stdout+stderr. Success requires exit 0 AND Vite's `built in`
     * line — an exit-0 run that never built is treated as a failure,
     * never as a pass.
     */
    fun parseBuildOutput(output: String, exitCode: Int, projectDir: String): BuildResult {
        val built = "built in" in output
        val errors = output.lineSequence()
            .filter { line ->
                val lower = line.lowercase()
                "error" in lower || "failed to resolve" in lower || "module not found" in lower
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(10)
            .toList()
        val ok = exitCode == 0 && built && errors.isEmpty()
        return BuildResult(
            ok = ok,
            distPath = if (ok) "$projectDir/dist" else null,
            errors = if (ok) emptyList() else errors.ifEmpty {
                listOf("Build failed with exit code $exitCode and no error line was captured.")
            },
            rawTail = output.takeLast(1500),
        )
    }

    /**
     * All scaffold files for an app called [name]. Throws
     * [IllegalArgumentException] when the name is invalid. Versions are
     * pinned to the majors the scaffold was written against (React 18,
     * Vite 5, Tailwind 3.4) so a fresh `npm install` cannot silently
     * pull a breaking major.
     */
    fun scaffoldFiles(name: String): List<ScaffoldFile> {
        validate(name)?.let { throw IllegalArgumentException(it) }
        val packageJson = """
            |{
            |  "name": "$name",
            |  "private": true,
            |  "version": "0.1.0",
            |  "type": "module",
            |  "scripts": {
            |    "dev": "vite",
            |    "build": "vite build",
            |    "preview": "vite preview"
            |  },
            |  "dependencies": {
            |    "react": "^18.3.1",
            |    "react-dom": "^18.3.1"
            |  },
            |  "devDependencies": {
            |    "@vitejs/plugin-react": "^4.3.1",
            |    "autoprefixer": "^10.4.19",
            |    "postcss": "^8.4.38",
            |    "tailwindcss": "^3.4.10",
            |    "vite": "^5.4.0"
            |  }
            |}
            |
        """.trimMargin()
        val viteConfig = """
            |import { defineConfig } from "vite";
            |import react from "@vitejs/plugin-react";
            |
            |export default defineConfig({
            |  plugins: [react()],
            |});
            |
        """.trimMargin()
        val tailwindConfig = """
            |/** @type {import('tailwindcss').Config} */
            |export default {
            |  content: ["./index.html", "./src/**/*.{js,jsx}"],
            |  theme: { extend: {} },
            |  plugins: [],
            |};
            |
        """.trimMargin()
        val postcssConfig = """
            |export default {
            |  plugins: {
            |    tailwindcss: {},
            |    autoprefixer: {},
            |  },
            |};
            |
        """.trimMargin()
        val indexHtml = """
            |<!doctype html>
            |<html lang="en">
            |  <head>
            |    <meta charset="UTF-8" />
            |    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            |    <title>$name</title>
            |  </head>
            |  <body>
            |    <div id="root"></div>
            |    <script type="module" src="/src/main.jsx"></script>
            |  </body>
            |</html>
            |
        """.trimMargin()
        val mainJsx = """
            |import React from "react";
            |import ReactDOM from "react-dom/client";
            |import App from "./App.jsx";
            |import "./index.css";
            |
            |ReactDOM.createRoot(document.getElementById("root")).render(
            |  <React.StrictMode>
            |    <App />
            |  </React.StrictMode>,
            |);
            |
        """.trimMargin()
        val indexCss = """
            |@tailwind base;
            |@tailwind components;
            |@tailwind utilities;
            |
        """.trimMargin()
        val appJsx = """
            |import { useState } from "react";
            |
            |export default function App() {
            |  const [count, setCount] = useState(0);
            |  return (
            |    <main className="min-h-screen bg-white text-slate-900 flex flex-col items-center justify-center gap-4 p-6">
            |      <h1 className="text-3xl font-bold">$name</h1>
            |      <p className="text-slate-600">
            |        Built with React + Vite + Tailwind. Replace this starter page with the real app.
            |      </p>
            |      <button
            |        className="rounded bg-slate-900 px-4 py-2 text-white"
            |        onClick={() => setCount((c) => c + 1)}
            |      >
            |        Count: {count}
            |      </button>
            |    </main>
            |  );
            |}
            |
        """.trimMargin()
        val readme = """
            |# $name
            |
            |React + Vite + Tailwind web artifact scaffolded by Codex (`webapp_build`).
            |
            |## Develop
            |
            |```
            |npm install
            |npm run dev
            |```
            |
            |## Build
            |
            |```
            |npm run build
            |```
            |
            |The production output lands in `dist/`. Edit `src/App.jsx` first,
            |then add components under `src/`. Keep the design plain and
            |readable: no purple gradients, no wall of centred text, no
            |decorative filler — every element on the page must do something.
            |
        """.trimMargin()
        return listOf(
            ScaffoldFile("package.json", packageJson),
            ScaffoldFile("vite.config.js", viteConfig),
            ScaffoldFile("tailwind.config.js", tailwindConfig),
            ScaffoldFile("postcss.config.js", postcssConfig),
            ScaffoldFile("index.html", indexHtml),
            ScaffoldFile("src/main.jsx", mainJsx),
            ScaffoldFile("src/index.css", indexCss),
            ScaffoldFile("src/App.jsx", appJsx),
            ScaffoldFile("README.md", readme),
        )
    }
}
