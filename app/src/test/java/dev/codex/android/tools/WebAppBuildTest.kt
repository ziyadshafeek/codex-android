package dev.codex.android.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for [WebAppBuild]: scaffold contents and build-output parsing. */
class WebAppBuildTest {

    @Test
    fun scaffold_hasPackageJsonViteTailwindAndStarterPage() {
        val files = WebAppBuild.scaffoldFiles("invoice-tracker")
        val paths = files.map { it.relativePath }.sorted()
        assertEquals(
            listOf(
                "README.md", "index.html", "package.json", "postcss.config.js",
                "src/App.jsx", "src/index.css", "src/main.jsx",
                "tailwind.config.js", "vite.config.js",
            ),
            paths,
        )
        val pkg = files.first { it.relativePath == "package.json" }.content
        assertTrue(pkg.contains("\"react\""))
        assertTrue(pkg.contains("\"vite\""))
        assertTrue(pkg.contains("\"tailwindcss\""))
        assertTrue(pkg.contains("\"build\": \"vite build\""))
        val app = files.first { it.relativePath == "src/App.jsx" }.content
        assertTrue(app.contains("invoice-tracker"))
        assertTrue(app.contains("useState"))
        val tailwind = files.first { it.relativePath == "tailwind.config.js" }.content
        assertTrue(tailwind.contains("./src/**/*.{js,jsx}"))
        val css = files.first { it.relativePath == "src/index.css" }.content
        assertTrue(css.contains("@tailwind base"))
    }

    @Test
    fun scaffold_rejectsBadNames() {
        assertTrue(WebAppBuild.validate("") != null)
        assertTrue(WebAppBuild.validate("1bad") != null)
        assertTrue(WebAppBuild.validate("ok-name") == null)
        try {
            WebAppBuild.scaffoldFiles("bad name")
            assertFalse("expected IllegalArgumentException", true)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("bad name"))
        }
    }

    @Test
    fun installAndBuildCommands_targetGuestDir() {
        assertEquals(
            "cd /workspace/web-artifacts/demo && npm install --no-audit --no-fund",
            WebAppBuild.installCommand("/workspace/web-artifacts/demo"),
        )
        assertEquals(
            "cd /workspace/web-artifacts/demo && npm run build",
            WebAppBuild.buildCommand("/workspace/web-artifacts/demo"),
        )
    }

    @Test
    fun parseSuccessfulBuild_returnsDistPath() {
        val output = "vite v5.4.0 building for production...\n✓ 34 modules transformed.\ndist/index.html  0.45 kB\n✓ built in 512ms\n"
        val result = WebAppBuild.parseBuildOutput(output, 0, "/workspace/web-artifacts/demo")
        assertTrue(result.ok)
        assertEquals("/workspace/web-artifacts/demo/dist", result.distPath)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun parseFailedBuild_carriesErrors() {
        val output = "vite v5.4.0 building for production...\nError: Failed to resolve import \"./Missing.jsx\" from \"src/App.jsx\"\n"
        val result = WebAppBuild.parseBuildOutput(output, 1, "/workspace/web-artifacts/demo")
        assertFalse(result.ok)
        assertNull(result.distPath)
        assertTrue(result.errors.any { it.contains("Failed to resolve") })
    }

    @Test
    fun parseExitZeroWithoutBuiltLine_isNotAPass() {
        // An exit-0 run that never printed Vite's "built in" line must not
        // be reported as a successful build.
        val result = WebAppBuild.parseBuildOutput("npm run build\nup to date\n", 0, "/workspace/x")
        assertFalse(result.ok)
        assertNull(result.distPath)
    }
}
