package dev.codex.android.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/** JVM unit tests for [CodexCli]: script generation, daemon.env format, install permissions. */
class CodexCliTest {

    @Test
    fun generateScript_targetsLoopbackOnlyAndReadsTokenFromEnv() {
        val script = CodexCli.generateScript()
        // Port comes from daemon.env at run time — never hardcoded here.
        assertTrue(script.contains("127.0.0.1"))
        assertTrue(script.contains("CODEX_DAEMON_PORT"))
        assertTrue(!script.contains("8787"))
        assertTrue(script.contains("daemon.env"))
        assertTrue(script.contains("/etc/codex/daemon.env"))
        assertTrue(script.contains("CODEX_DAEMON_TOKEN"))
        assertTrue(script.contains("codex run"))
        assertTrue(script.contains("codex threads"))
        assertTrue(script.contains("codex messages"))
        assertTrue(script.contains("codex stop"))
        assertTrue(script.contains("codex status"))
        assertTrue(script.contains("/v1/health"))
        // Script must never contain a hardcoded token.
        assertTrue(!script.contains("Bearer sk-"))
        assertTrue(!script.contains("CODEX_DAEMON_TOKEN=sk-"))
    }

    @Test
    fun helpWorksBeforeDaemonStarts() {
        val dir = Files.createTempDirectory("codex-cli-help").toFile()
        try {
            val script = File(dir, "codex").apply { writeText(CodexCli.buildCliScript()) }
            val process = ProcessBuilder("/bin/sh", script.absolutePath, "--help").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor())
            assertTrue(output.contains("Usage: codex"))
            assertTrue(script.readText().contains("/bin/busybox wget -Y off"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun buildCliScript_matchesGenerateScript() {
        assertEquals(CodexCli.generateScript(), CodexCli.buildCliScript())
    }

    @Test
    fun generateDaemonEnv_containsPortAndTokenOnly() {
        val env = CodexCli.generateDaemonEnv("test-token-abc123", 8787)
        assertEquals("CODEX_DAEMON_PORT=8787\nCODEX_DAEMON_TOKEN=test-token-abc123\n", env)
    }

    @Test
    fun install_writesExecutableScriptAndEnvFile() {
        val tempDir = Files.createTempDirectory("codex-cli-test").toFile()
        try {
            val binDir = File(tempDir, "bin")
            val scriptFile = CodexCli.install(binDir, "boot-token-xyz", 8787)
            assertTrue(scriptFile.isFile)
            assertTrue(scriptFile.canExecute())
            val envFile = File(binDir, "daemon.env")
            assertTrue(envFile.isFile)
            assertEquals(
                "CODEX_DAEMON_PORT=8787\nCODEX_DAEMON_TOKEN=boot-token-xyz\n",
                envFile.readText(),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun writeDaemonEnv_writesPortAndTokenWithOwnerOnlyPermissions() {
        val tempDir = Files.createTempDirectory("codex-cli-env-test").toFile()
        try {
            val linuxDir = File(tempDir, "linux")
            val envFile = CodexCli.writeDaemonEnv(linuxDir, "boot-token-xyz", 18787)
            assertTrue(envFile.isFile)
            assertEquals(
                "CODEX_DAEMON_PORT=18787\nCODEX_DAEMON_TOKEN=boot-token-xyz\n",
                envFile.readText(),
            )
            assertOwnerOnly(envFile)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun installIntoRootfs_writesGuestScriptAndHostEnvFile() {
        val tempDir = Files.createTempDirectory("codex-cli-rootfs-test").toFile()
        try {
            val rootfsDir = File(tempDir, "rootfs")
            val linuxDir = File(tempDir, "linux")
            val scriptFile = CodexCli.installIntoRootfs(rootfsDir, linuxDir, "boot-token-xyz", 8787)
            // Guest ~/bin/codex.
            assertEquals(File(File(File(rootfsDir, "root"), "bin"), "codex"), scriptFile)
            assertTrue(scriptFile.isFile)
            assertTrue(scriptFile.canExecute())
            assertTrue(scriptFile.readText().contains("CODEX_DAEMON_PORT"))
            // Host filesDir/linux/daemon.env, 0600.
            val envFile = File(linuxDir, "daemon.env")
            assertTrue(envFile.isFile)
            assertEquals(
                "CODEX_DAEMON_PORT=8787\nCODEX_DAEMON_TOKEN=boot-token-xyz\n",
                envFile.readText(),
            )
            assertOwnerOnly(envFile)
            // The token lives in exactly one host file: no daemon.env
            // is written next to the guest script.
            assertTrue(!File(scriptFile.parentFile, "daemon.env").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** Asserts rw------- where the platform reports POSIX permissions; no-op elsewhere. */
    private fun assertOwnerOnly(file: File) {
        val perms = runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrNull()
            ?: return
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            perms,
        )
    }
}
