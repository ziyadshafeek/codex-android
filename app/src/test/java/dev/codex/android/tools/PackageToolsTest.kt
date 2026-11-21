package dev.codex.android.tools

import dev.codex.android.linux.ShellResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM unit tests for the delete-control tools ([PackageTools]).
 *
 * The shell is faked (canned [ShellResult]s keyed by exact command);
 * the filesystem paths use temp dirs. No Android APIs are used.
 * Paths that need a real phone/emulator (FileLibrary-backed attachment
 * clear, `apk` against a live rootfs, the Compose screen) are named in
 * the report, not faked here.
 */
class PackageToolsTest {

    private lateinit var tempDir: File
    private lateinit var workspace: WorkspaceFileTools

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("codex-delete-test").toFile()
        workspace = WorkspaceFileTools(File(tempDir, "workspace"))
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ---- apk parsing / validation ----

    @Test
    fun parseInstalledPackages_parsesApkListOutput() {
        val output = """
            curl-8.5.0-r0 x86_64 {curl} (MIT) [installed]
            py3-pillow-10.3.0-r0 aarch64 {py3-pillow} (HPND) [installed]
            git-2.45.2-r0 aarch64 {git} (GPL-2.0-or-later) [installed]
            sdl2-2.0.20-r0 aarch64 {sdl2} (Zlib) [installed]

            not-a-package-line
        """.trimIndent()
        val parsed = LinuxPackageManager.parseInstalledPackages(output)
        assertEquals(
            listOf(
                InstalledPackage("curl", "8.5.0-r0"),
                InstalledPackage("git", "2.45.2-r0"),
                InstalledPackage("py3-pillow", "10.3.0-r0"),
                InstalledPackage("sdl2", "2.0.20-r0"),
            ),
            parsed,
        )
    }

    @Test
    fun parseInstalledPackages_emptyOutputIsEmptyNotAnError() {
        assertTrue(LinuxPackageManager.parseInstalledPackages("").isEmpty())
        assertTrue(LinuxPackageManager.parseInstalledPackages("   \n").isEmpty())
    }

    @Test
    fun isValidPackageName_acceptsRealNames_rejectsShellMetacharacters() {
        assertTrue(LinuxPackageManager.isValidPackageName("curl"))
        assertTrue(LinuxPackageManager.isValidPackageName("py3-pillow"))
        assertTrue(LinuxPackageManager.isValidPackageName("nodejs"))
        assertTrue(LinuxPackageManager.isValidPackageName("tesseract-ocr"))
        assertFalse(LinuxPackageManager.isValidPackageName("curl; rm -rf /"))
        assertFalse(LinuxPackageManager.isValidPackageName("curl && echo hi"))
        assertFalse(LinuxPackageManager.isValidPackageName("\$(reboot)"))
        assertFalse(LinuxPackageManager.isValidPackageName("../etc"))
        assertFalse(LinuxPackageManager.isValidPackageName("curl`id`"))
        assertFalse(LinuxPackageManager.isValidPackageName("curl bar"))
        assertFalse(LinuxPackageManager.isValidPackageName(""))
        assertFalse(LinuxPackageManager.isValidPackageName("CURL"))
    }

    // ---- fake shell ----

    private class FakeShell(private val canned: Map<String, ShellResult>) {
        val calls = mutableListOf<String>()
        val fn: suspend (String) -> ShellResult = { cmd ->
            calls += cmd
            canned[cmd] ?: ShellResult(0, "", "")
        }
    }

    // ---- uninstall ----

    @Test
    fun uninstall_successVerifiesPackageIsGone() = runBlocking {
        val fake = FakeShell(
            mapOf(
                "apk del curl" to ShellResult(0, "", ""),
                "apk info -e curl" to ShellResult(1, "", "ERROR: curl: package not found"),
            ),
        )
        val result = LinuxPackageService(fake.fn).uninstall("curl")
        assertTrue(result.isSuccess)
        assertEquals(listOf("apk del curl", "apk info -e curl"), fake.calls)
    }

    @Test
    fun uninstall_failsWhenPackageStillInstalledAfterDel() = runBlocking {
        val fake = FakeShell(
            mapOf(
                "apk del curl" to ShellResult(0, "", ""),
                "apk info -e curl" to ShellResult(0, "curl-8.5.0-r0", ""),
            ),
        )
        val result = LinuxPackageService(fake.fn).uninstall("curl")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("still installed"))
    }

    @Test
    fun uninstall_delFailureReportsStderrAndSkipsVerify() = runBlocking {
        val fake = FakeShell(
            mapOf("apk del nosuchpkg" to ShellResult(1, "", "ERROR: unable to select packages:\n  nosuchpkg (no such package)")),
        )
        val result = LinuxPackageService(fake.fn).uninstall("nosuchpkg")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("no such package"))
        assertEquals(listOf("apk del nosuchpkg"), fake.calls)
    }

    @Test
    fun uninstall_invalidNameNeverReachesShell() = runBlocking {
        val fake = FakeShell(emptyMap())
        val result = LinuxPackageService(fake.fn).uninstall("curl; rm -rf /")
        assertTrue(result.isFailure)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun listInstalled_failureSurfacesShellError() = runBlocking {
        val fake = FakeShell(
            mapOf("apk list --installed" to ShellResult(1, "", "apk: database error")),
        )
        val result = LinuxPackageService(fake.fn).listInstalled()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("database error"))
    }

    // ---- tool-level confirmation guard ----

    @Test
    fun uninstallPackageTool_refusesWithoutConfirmed() = runBlocking {
        val fake = FakeShell(emptyMap())
        val tool = UninstallPackageTool(LinuxPackageService(fake.fn))
        val result = tool.execute(buildJsonObject { put("package", "curl") })
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("confirmed=true"))
        assertTrue(fake.calls.isEmpty())
        assertTrue(tool.sensitive)
    }

    @Test
    fun uninstallPackageTool_confirmedRunsAndReports() = runBlocking {
        val fake = FakeShell(
            mapOf(
                "apk del curl" to ShellResult(0, "", ""),
                "apk info -e curl" to ShellResult(1, "", ""),
            ),
        )
        val tool = UninstallPackageTool(LinuxPackageService(fake.fn))
        val result = tool.execute(buildJsonObject { put("package", "curl"); put("confirmed", true) })
        assertTrue(result.ok)
        assertTrue(result.output.contains("Uninstalled curl"))
    }

    @Test
    fun clearWorkspaceTool_refusesWithoutConfirmedForEveryScope() = runBlocking {
        val tool = ClearWorkspaceTool(workspace, fileLibrary = null)
        for (scope in listOf("workspace", "attachments", "downloads")) {
            val result = tool.execute(buildJsonObject { put("scope", scope) })
            assertFalse("scope $scope should refuse without confirmed", result.ok)
            assertTrue(result.error!!.contains("confirmed=true"))
        }
        assertTrue(tool.sensitive)
    }

    // ---- workspace / downloads clearing ----

    @Test
    fun clearWorkspaceContents_removesFilesKeepsRootAndAttachments() {
        val root = workspace.rootDir
        File(root, "a.txt").writeText("a")
        File(root, "sub").mkdirs()
        File(root, "sub/b.txt").writeText("b")
        File(root, ".attachments/drafts").mkdirs()
        File(root, ".attachments/drafts/keep.txt").writeText("keep")
        val removed = WorkspaceCleaner.clearWorkspaceContents(workspace)
        assertEquals(2, removed)
        assertTrue("workspace root itself must be kept", root.isDirectory)
        assertFalse(File(root, "a.txt").exists())
        assertFalse(File(root, "sub").exists())
        assertTrue("staged attachments must survive a workspace clear", File(root, ".attachments/drafts/keep.txt").isFile)
        assertEquals("only the kept attachment should remain", 1, WorkspaceCleaner.countFiles(root))
    }

    @Test
    fun clearWorkspaceTool_workspaceScopeDeletesAndReportsCount() = runBlocking {
        File(workspace.rootDir, "app.js").writeText("console.log(1)")
        File(workspace.rootDir, "notes.md").writeText("# hi")
        val tool = ClearWorkspaceTool(workspace, fileLibrary = null)
        val result = tool.execute(buildJsonObject { put("scope", "workspace"); put("confirmed", true) })
        assertTrue(result.ok)
        assertTrue(result.output.contains("removed 2 file(s)"))
        assertFalse(File(workspace.rootDir, "app.js").exists())
        assertFalse(File(workspace.rootDir, "notes.md").exists())
        assertTrue(workspace.rootDir.isDirectory)
    }

    @Test
    fun clearDownloads_removesDownloadableFilesOnly() {
        File(workspace.rootDir, "app.js").writeText("x")
        File(workspace.rootDir, ".hidden").writeText("secret")
        val removed = WorkspaceCleaner.clearDownloads(workspace)
        assertEquals(1, removed)
        assertFalse(File(workspace.rootDir, "app.js").exists())
        assertTrue("hidden files are not in the download index and must survive", File(workspace.rootDir, ".hidden").isFile)
    }

    @Test
    fun clearWorkspaceTool_unknownScopeIsAnError() = runBlocking {
        val tool = ClearWorkspaceTool(workspace, fileLibrary = null)
        val result = tool.execute(buildJsonObject { put("scope", "everything"); put("confirmed", true) })
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("Unknown scope"))
    }

    @Test
    fun clearWorkspaceTool_attachmentsScopeWithoutLibraryFailsHonestly() = runBlocking {
        val tool = ClearWorkspaceTool(workspace, fileLibrary = null)
        val result = tool.execute(buildJsonObject { put("scope", "attachments"); put("confirmed", true) })
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("no file library"))
    }

    // NOTE: the attachments scope needs a real FileLibrary (Android
    // Context), which cannot exist on the JVM — that path is proven on
    // the emulator/phone instead (see report). These tests pass
    // fileLibrary = null, which the tool reports honestly.
}
