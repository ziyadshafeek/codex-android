package dev.codex.android.linux

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for [BuilderToolchainStaging]: manifest shape + staging flow with fakes. */
class BuilderToolchainStagingTest {

    private val okShell: suspend (String) -> ShellResult = { ShellResult(0, "ok", "") }

    @Test
    fun manifest_listsAllFourStagesWithDistinctIds() {
        val ids = BuilderToolchainStaging.STAGES.map { it.id }
        assertEquals(listOf("firebase-tools", "node", "playwright", "python"), ids.sorted())
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(BuilderToolchainStaging.PLAYWRIGHT, BuilderToolchainStaging.find("playwright"))
        assertEquals(null, BuilderToolchainStaging.find("nope"))
    }

    @Test
    fun playwrightStage_needsNodeChromiumAndNpmPlaywright() {
        val stage = BuilderToolchainStaging.PLAYWRIGHT
        assertEquals(listOf("node"), stage.requiresToolchain)
        assertEquals(listOf("chromium"), stage.apkPackages)
        assertEquals(listOf("playwright"), stage.npmGlobalPackages)
        assertEquals("/usr/bin/chromium-browser", stage.env["PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH"])
    }

    @Test
    fun firebaseToolsStage_needsNodeAndNpmFirebaseTools() {
        val stage = BuilderToolchainStaging.FIREBASE_TOOLS
        assertEquals(listOf("node"), stage.requiresToolchain)
        assertEquals(listOf("firebase-tools"), stage.npmGlobalPackages)
        assertTrue(stage.apkPackages.isEmpty())
    }

    @Test
    fun stage_runsToolchainThenApkThenNpm_inOrder() = runBlocking {
        val seen = mutableListOf<String>()
        val toolchains = mutableListOf<String>()
        val result = BuilderToolchainStaging.stage(
            BuilderToolchainStaging.PLAYWRIGHT,
            ensureToolchain = { name -> toolchains += name; true },
            shell = { cmd ->
                seen += cmd
                ShellResult(0, "ok", "")
            },
        )
        assertTrue(result.ok)
        assertEquals(listOf("node"), toolchains)
        assertEquals(2, seen.size)
        assertTrue(seen[0].startsWith("apk add --no-cache chromium"))
        assertTrue(seen[1].startsWith("npm install -g playwright"))
    }

    @Test
    fun stage_failsHonestlyWhenToolchainFails() = runBlocking {
        var shellCalls = 0
        val result = BuilderToolchainStaging.stage(
            BuilderToolchainStaging.PLAYWRIGHT,
            ensureToolchain = { false },
            shell = { shellCalls++; ShellResult(0, "", "") },
        )
        assertFalse(result.ok)
        assertTrue(result.detail.contains("node"))
        assertEquals(0, shellCalls)
    }

    @Test
    fun stage_failsHonestlyWhenApkFails() = runBlocking {
        val result = BuilderToolchainStaging.stage(
            BuilderToolchainStaging.PLAYWRIGHT,
            ensureToolchain = { true },
            shell = { cmd ->
                if (cmd.startsWith("apk")) ShellResult(1, "", "apk: no such package") else ShellResult(0, "", "")
            },
        )
        assertFalse(result.ok)
        assertTrue(result.detail.contains("apk install failed"))
        assertTrue(result.detail.contains("chromium"))
    }

    @Test
    fun stage_failsHonestlyWhenNpmFails() = runBlocking {
        val result = BuilderToolchainStaging.stage(
            BuilderToolchainStaging.FIREBASE_TOOLS,
            ensureToolchain = { true },
            shell = { ShellResult(1, "", "npm ERR! network timeout") },
        )
        assertFalse(result.ok)
        assertTrue(result.detail.contains("npm install failed"))
        assertTrue(result.detail.contains("firebase-tools"))
    }

    @Test
    fun nodeStage_onlyNeedsTheToolchain() = runBlocking {
        var shellCalls = 0
        val result = BuilderToolchainStaging.stage(
            BuilderToolchainStaging.NODE,
            ensureToolchain = { it == "node" },
            shell = { shellCalls++; okShell(it) },
        )
        assertTrue(result.ok)
        assertEquals(0, shellCalls)
    }
}
