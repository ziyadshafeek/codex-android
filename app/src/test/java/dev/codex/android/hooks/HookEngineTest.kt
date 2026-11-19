package dev.codex.android.hooks

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** JVM unit tests for [HookEngine]: veto on PRE_TOOL_USE, 10s timeout never blocks, persistence. */
class HookEngineTest {

    private lateinit var tempRoot: File
    private lateinit var configFile: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("codex-hooks-test").toFile()
        configFile = File(tempRoot, "hooks.json")
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun preToolUseHook_vetoOnNonZeroExit() = runBlocking {
        val engine = HookEngine(configFile) { command, _, _ ->
            HookExecution(
                id = "exec_1",
                hookId = "hook_veto",
                event = HookEvent.PRE_TOOL_USE.name,
                command = command,
                exitCode = 1,
                stdout = "blocked",
                stderr = "",
                durationMs = 5,
                timedOut = false,
            )
        }
        engine.registerHook(
            HookDefinition(
                id = "hook_veto",
                event = HookEvent.PRE_TOOL_USE.name,
                matcher = "write_*",
                command = "echo veto",
            ),
        )

        val vetoed = engine.runHooks(HookEvent.PRE_TOOL_USE, toolName = "write_file")
        assertTrue("non-zero PRE_TOOL_USE hook must veto", vetoed)

        // Same hook must NOT veto other tools (glob mismatch).
        val notVetoed = engine.runHooks(HookEvent.PRE_TOOL_USE, toolName = "read_file")
        assertFalse(notVetoed)
    }

    @Test
    fun hookTimeout_neverBlocksMainFlow() = runBlocking {
        val engine = HookEngine(configFile) { _, _, _ ->
            // Simulate a hanging hook that never returns; engine timeout must cut it off.
            delay(60_000)
            null
        }
        engine.registerHook(
            HookDefinition(
                id = "hook_slow",
                event = HookEvent.SESSION_START.name,
                command = "sleep 60",
                timeoutMs = 200,
            ),
        )
        val started = System.currentTimeMillis()
        val vetoed = engine.runHooks(HookEvent.SESSION_START)
        val elapsed = System.currentTimeMillis() - started

        assertFalse(vetoed)
        assertTrue("hook must time out quickly, took ${elapsed}ms", elapsed < 5_000)
        assertTrue(engine.executions.value.any { it.timedOut })
    }

    @Test
    fun hookFailure_neverBlocksToolCall() = runBlocking {
        val engine = HookEngine(configFile) { _, _, _ ->
            throw RuntimeException("executor exploded")
        }
        engine.registerHook(
            HookDefinition(
                id = "hook_broken",
                event = HookEvent.PRE_TOOL_USE.name,
                command = "echo boom",
            ),
        )

        // A crashing hook must not throw and must not veto the tool call.
        val vetoed = engine.runHooks(HookEvent.PRE_TOOL_USE, toolName = "write_file")
        assertFalse("a failing hook must never veto", vetoed)
        assertTrue(engine.executions.value.any { it.exitCode == -1 && !it.timedOut })
    }

    @Test
    fun fire_returnsImmediatelyWithHangingHook() = runBlocking {
        val engine = HookEngine(configFile) { _, _, _ ->
            delay(60_000)
            null
        }
        engine.registerHook(
            HookDefinition(
                id = "hook_slow",
                event = HookEvent.SESSION_START.name,
                command = "sleep 60",
                timeoutMs = 200,
            ),
        )

        val started = System.currentTimeMillis()
        val job = engine.fire(HookEvent.SESSION_START)
        val fireElapsed = System.currentTimeMillis() - started
        assertTrue("fire() must return immediately, took ${fireElapsed}ms", fireElapsed < 5_000)

        // The background run still records the timeout.
        withTimeoutOrNull(5_000) { job.join() }
        assertTrue(engine.executions.value.any { it.timedOut })
    }

    @Test
    fun hooks_persistAcrossEngineInstances() {
        val engine1 = HookEngine(configFile)
        engine1.registerHook(
            HookDefinition(id = "hook_a", event = HookEvent.STOP.name, command = "echo done"),
        )
        assertTrue(configFile.isFile)

        val engine2 = HookEngine(configFile)
        assertEquals(1, engine2.listHooks().size)
        assertEquals("hook_a", engine2.listHooks()[0].id)

        assertTrue(engine2.unregisterHook("hook_a"))
        assertTrue(engine2.listHooks().isEmpty())
    }

    @Test
    fun loadHooksFile_readsCodexHooksJson() {
        // {"hooks": [...]} form, at the conventional .codex/hooks.json path.
        val hooksFile = HookEngine.hooksFileFor(tempRoot)
        hooksFile.parentFile?.mkdirs()
        hooksFile.writeText(
            """{"hooks": [{"id": "hook_json", "event": "STOP", "command": "echo done"}]}"""
        )

        val engine = HookEngine(configFile)
        assertEquals(1, engine.loadHooksFile(hooksFile))
        assertEquals(1, engine.listHooks().size)
        assertEquals("hook_json", engine.listHooks()[0].id)

        // Bare [...] form loads too.
        val bareFile = File(tempRoot, "bare.json")
        bareFile.writeText("""[{"id": "hook_bare", "event": "STOP", "command": "echo bare"}]""")
        assertEquals(1, engine.loadHooksFile(bareFile))
        assertEquals(2, engine.listHooks().size)

        // Missing / malformed files load zero hooks and never throw.
        assertEquals(0, engine.loadHooksFile(File(tempRoot, "missing.json")))
        val badFile = File(tempRoot, "bad.json")
        badFile.writeText("{not json")
        assertEquals(0, engine.loadHooksFile(badFile))
    }
}
