package dev.codex.android.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** JVM tests for [McpScaffold]: scaffold contents, validation, and a live smoke test. */
class McpScaffoldTest {

    @Test
    fun pythonScaffold_hasServerManifestAndReadme() {
        val files = McpScaffold.scaffoldFiles("notes-server", "python")
        assertEquals(listOf("README.md", "mcp-server.json", "server.py"), files.map { it.relativePath }.sorted())
        val server = files.first { it.relativePath == "server.py" }.content
        assertTrue(server.contains("tools/list"))
        assertTrue(server.contains("\"echo\""))
        assertTrue(server.contains("initialize"))
        assertTrue("no third-party imports" !in server)
        val manifest = files.first { it.relativePath == "mcp-server.json" }.content
        assertTrue(manifest.contains("\"name\": \"notes-server\""))
        assertTrue(manifest.contains("\"command\": \"python3\""))
        assertTrue(manifest.contains("server.py"))
    }

    @Test
    fun typescriptScaffold_hasServerManifestAndReadme() {
        val files = McpScaffold.scaffoldFiles("notes-server", "typescript")
        assertEquals(listOf("README.md", "mcp-server.json", "server.js"), files.map { it.relativePath }.sorted())
        val server = files.first { it.relativePath == "server.js" }.content
        assertTrue(server.contains("tools/list"))
        assertTrue(server.contains("echo"))
        assertTrue(server.contains("node:readline"))
        val (command, args) = McpScaffold.serverCommand("typescript", "/workspace/mcp-servers/notes-server")
        assertEquals("node", command)
        assertEquals(listOf("/workspace/mcp-servers/notes-server/server.js"), args)
    }

    @Test
    fun invalidLanguage_isAClearError() {
        val error = McpScaffold.validate("notes-server", "ruby")
        assertTrue(error != null && error.contains("ruby") && error.contains("python"))
        assertThrows(IllegalArgumentException::class.java) {
            McpScaffold.scaffoldFiles("notes-server", "ruby")
        }
    }

    @Test
    fun invalidNames_areRejected() {
        assertTrue(McpScaffold.validate("", "python") != null)
        assertTrue(McpScaffold.validate("1bad", "python") != null)
        assertTrue(McpScaffold.validate("has space", "python") != null)
        assertTrue(McpScaffold.validate("ok-name_1", "python") == null)
    }

    @Test
    fun pythonServer_answersInitializeAndToolsList() {
        val python = listOf("python3", "python").firstOrNull { isOnPath(it) } ?: return
        val dir = Files.createTempDirectory("mcp-scaffold-test").toFile()
        try {
            val file = File(dir, "server.py")
            file.writeText(McpScaffold.scaffoldFiles("smoke", "python").first { it.relativePath == "server.py" }.content)
            val proc = ProcessBuilder(python, file.absolutePath)
                .redirectErrorStream(true).start()
            proc.outputStream.bufferedWriter().use { w ->
                w.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n")
                w.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n")
                w.flush()
            }
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            assertTrue("initialize reply missing: $out", out.contains("\"serverInfo\""))
            assertTrue("tools/list reply missing echo: $out", out.contains("\"echo\""))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun typescriptServer_answersInitializeAndToolsList() {
        val node = if (isOnPath("node")) "node" else return
        val dir = Files.createTempDirectory("mcp-scaffold-test").toFile()
        try {
            val file = File(dir, "server.js")
            file.writeText(McpScaffold.scaffoldFiles("smoke", "typescript").first { it.relativePath == "server.js" }.content)
            val proc = ProcessBuilder(node, file.absolutePath)
                .redirectErrorStream(true).start()
            proc.outputStream.bufferedWriter().use { w ->
                w.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n")
                w.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n")
                w.flush()
            }
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            assertTrue("initialize reply missing: $out", out.contains("\"serverInfo\""))
            assertTrue("tools/list reply missing echo: $out", out.contains("\"echo\""))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun isOnPath(binary: String): Boolean = runCatching {
        ProcessBuilder("which", binary).start().waitFor() == 0
    }.getOrDefault(false)
}
