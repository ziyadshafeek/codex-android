package dev.codex.android.tools

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for [WhereIsTool]. */
class WhereIsToolTest {

    private fun tool(): Pair<WhereIsTool, File> {
        val tmp = createTempDir("where-is-test")
        val filesDir = File(tmp, "files").apply { mkdirs() }
        val workspaceRoot = File(filesDir, "workspace").apply { mkdirs() }
        return WhereIsTool(filesDir = filesDir, workspaceRoot = workspaceRoot) to filesDir
    }

    private fun execute(tool: WhereIsTool, name: String) = runBlocking {
        tool.execute(buildJsonObject { put("name", name) })
    }

    @Test
    fun pdftoppm_resolvesToBothPaths() {
        val (tool, filesDir) = tool()
        val rootfs = File(filesDir, "linux/rootfs/usr/bin").apply { mkdirs() }
        File(rootfs, "pdftoppm").writeText("fake binary")

        val result = execute(tool, "pdftoppm")
        assertTrue(result.ok)
        assertTrue(result.output.contains("Package: poppler"))
        assertTrue(result.output.contains("In the shell: /usr/bin/pdftoppm"))
        assertTrue(
            result.output.contains(
                "On this phone: ${File(filesDir, "linux/rootfs/usr/bin/pdftoppm").path}",
            ),
        )
        assertTrue(result.output.contains("Installed: yes"))
    }

    @Test
    fun popplerPackage_resolvesToItsBinary() {
        val (tool, filesDir) = tool()
        val result = execute(tool, "poppler")
        assertTrue(result.ok)
        assertTrue(result.output.contains("In the shell: /usr/bin/pdftoppm"))
        assertTrue(
            result.output.contains(
                "On this phone: ${File(filesDir, "linux/rootfs/usr/bin/pdftoppm").path}",
            ),
        )
        // Nothing installed in this temp rootfs: must say so, not claim it is there.
        assertTrue(result.output.contains("Installed: no, not present at that location."))
    }

    @Test
    fun inShellAbsolutePath_resolvesUnderRootfs() {
        val (tool, filesDir) = tool()
        val result = execute(tool, "/usr/bin/tesseract")
        assertTrue(result.ok)
        assertTrue(result.output.contains("In the shell: /usr/bin/tesseract"))
        assertTrue(
            result.output.contains(
                "On this phone: ${File(filesDir, "linux/rootfs/usr/bin/tesseract").path}",
            ),
        )
    }

    @Test
    fun workspaceFile_returnsWorkspaceAndDevicePaths() {
        val (tool, filesDir) = tool()
        val workspace = File(filesDir, "workspace/notes").apply { mkdirs() }
        File(workspace, "app.kt").writeText("fun main() {}")

        val result = execute(tool, "notes/app.kt")
        assertTrue(result.ok)
        assertTrue(result.output.contains("In the shell: /workspace/notes/app.kt"))
        assertTrue(
            result.output.contains(
                "On this phone: ${File(filesDir, "workspace/notes/app.kt").path}",
            ),
        )
        assertTrue(result.output.contains("Installed: yes"))

        val prefixed = execute(tool, "/workspace/notes/app.kt")
        assertTrue(prefixed.ok)
        assertTrue(prefixed.output.contains("In the shell: /workspace/notes/app.kt"))
    }

    @Test
    fun missingWorkspaceFile_saysSoPlainly() {
        val (tool, _) = tool()
        val result = execute(tool, "notes/missing.kt")
        assertTrue(result.ok)
        assertTrue(result.output.contains("In the shell: /workspace/notes/missing.kt"))
        assertTrue(result.output.contains("Installed: no, not present at that location."))
    }

    @Test
    fun attachment_returnsAttachmentsPath() {
        val (tool, filesDir) = tool()
        val key = "drafts/123e4567-e89b-12d3-a456-426614174000/photo.jpg"
        val staged = File(filesDir, "workspace/.attachments/drafts/123e4567-e89b-12d3-a456-426614174000")
            .apply { mkdirs() }
        File(staged, "photo.jpg").writeText("fake image")

        val result = execute(tool, "/workspace/.attachments/$key")
        assertTrue(result.ok)
        assertTrue(result.output.contains("In the shell: /workspace/.attachments/$key"))
        assertTrue(
            result.output.contains(
                "On this phone: ${File(filesDir, "workspace/.attachments/$key").path}",
            ),
        )
        assertTrue(result.output.contains("Installed: yes"))

        val bareKey = execute(tool, key)
        assertTrue(bareKey.ok)
        assertTrue(bareKey.output.contains("In the shell: /workspace/.attachments/$key"))
    }

    @Test
    fun unknownName_isHonestNotFound_notAFabricatedPath() {
        val (tool, _) = tool()
        val result = execute(tool, "definitely-not-a-real-tool-xyz")
        assertTrue(result.ok)
        assertTrue(result.output.contains("not a known installed package or binary"))
        assertTrue(result.output.contains("not installed"))
        assertFalse(result.output.contains("Installed: yes"))
    }

    @Test
    fun genericBinary_foundUnderUsrBin_isReported() {
        val (tool, filesDir) = tool()
        val bin = File(filesDir, "linux/rootfs/usr/bin").apply { mkdirs() }
        File(bin, "htop").writeText("fake binary")

        val result = execute(tool, "htop")
        assertTrue(result.ok)
        assertTrue(result.output.contains("In the shell: /usr/bin/htop"))
        assertTrue(result.output.contains("Installed: yes"))
    }

    @Test
    fun libraryPackage_hasNoInventedFilePath() {
        val (tool, _) = tool()
        val result = execute(tool, "pillow")
        assertTrue(result.ok)
        assertTrue(result.output.contains("not a standalone binary"))
        assertTrue(result.output.contains("python3 -c"))
        assertFalse(result.output.contains("On this phone:"))
    }

    @Test
    fun missingName_isAnError() = runBlocking {
        val (tool, _) = tool()
        val result = tool.execute(buildJsonObject { put("name", "   ") })
        assertFalse(result.ok)
    }

    @Test
    fun toolMetadata() {
        val (tool, _) = tool()
        assertEquals("where_is", tool.name)
        assertFalse(tool.sensitive)
        assertTrue(tool.parametersSchema["properties"].toString().contains("name"))
    }
}
