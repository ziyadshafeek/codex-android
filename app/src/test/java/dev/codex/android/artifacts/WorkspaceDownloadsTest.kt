package dev.codex.android.artifacts

import dev.codex.android.tools.WorkspaceFileTools
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceDownloadsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun scansCreatedAndEditedFilesButNotSecretsBuildCachesOrLinks() {
        val root = temp.newFolder("workspace")
        val workspace = WorkspaceFileTools(root)
        val existing = File(root, "index.html").apply { writeText("old") }
        val before = downloadIndex(workspace)
        existing.writeText("new contents")
        File(root, "exports/report.pdf").apply { parentFile!!.mkdirs(); writeText("report") }
        File(root, ".env").writeText("secret")
        File(root, "demo.keystore").writeText("secret")
        File(root, "node_modules/helper.js").apply { parentFile!!.mkdirs(); writeText("skip") }
        val outside = temp.newFile("outside.txt")
        Files.createSymbolicLink(File(root, "linked.txt").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "dir-link").toPath(), outside.parentFile.toPath())
        val changes = changedWorkspaceOutputs(before, downloadIndex(workspace))
        assertEquals(listOf("exports/report.pdf", "index.html"), changes)
        assertEquals(existing.canonicalFile, resolveWorkspaceDownload(workspace, "index.html"))
        assertThrows(Exception::class.java) { resolveWorkspaceDownload(workspace, "../outside.txt") }
        assertThrows(Exception::class.java) { resolveWorkspaceDownload(workspace, "linked.txt") }
        assertThrows(Exception::class.java) { resolveWorkspaceDownload(workspace, "dir-link/outside.txt") }
    }
    @org.junit.Test fun existingOutputCanBeOfferedByNameOrReferenceButNeverGuessedFromTwoFiles() {
        assertEquals("test.html", requestedWorkspaceDownload("can you save it in downloads", null, "Saved as `test.html`."))
        assertEquals("docs/report.pdf", requestedWorkspaceDownload("download docs/report.pdf", null, null))
        org.junit.Assert.assertNull(requestedWorkspaceDownload("download it", "backup.txt", "Files: `a.txt` and `b.txt`"))
        org.junit.Assert.assertNull(requestedWorkspaceDownload("do not download test.html", null, null))
    }
}
