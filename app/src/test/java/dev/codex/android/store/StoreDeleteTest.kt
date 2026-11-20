package dev.codex.android.store

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM unit tests for [deleteGeneratedAppSource] — the file half of the
 * Store delete (listing + source). The catalogue half
 * ([StoreCatalog.deleteListingAndSource]) needs an Android Context for
 * its JSON file, so it cannot run on the JVM; it is proven on the
 * emulator/phone instead (see report). The traversal guards are tested
 * here because they are the safety-critical part.
 */
class StoreDeleteTest {

    private lateinit var workspaceDir: File

    @Before
    fun setUp() {
        workspaceDir = Files.createTempDirectory("codex-store-delete-test").toFile()
    }

    @After
    fun tearDown() {
        workspaceDir.deleteRecursively()
    }

    private fun sourceDir(appId: String): File {
        val dir = File(workspaceDir, "generated/$appId/app/src/main/java/com/codex/apps/test")
        dir.mkdirs()
        File(dir, "MainActivity.kt").writeText("fake source")
        return File(workspaceDir, "generated/$appId")
    }

    @Test
    fun deleteGeneratedAppSource_removesWholeSourceFolder() {
        val dir = sourceDir("550e8400-e29b-41d4-a716-446655440000")
        assertTrue(dir.exists())
        assertTrue(deleteGeneratedAppSource(workspaceDir, "550e8400-e29b-41d4-a716-446655440000"))
        assertFalse("generated source must be really gone", dir.exists())
    }

    @Test
    fun deleteGeneratedAppSource_alreadyGoneIsSuccess() {
        assertTrue(deleteGeneratedAppSource(workspaceDir, "550e8400-e29b-41d4-a716-446655440000"))
    }

    @Test
    fun deleteGeneratedAppSource_leavesOtherAppsAlone() {
        val victim = sourceDir("aaaaaaaa-0000-0000-0000-000000000000")
        val other = sourceDir("bbbbbbbb-1111-1111-1111-111111111111")
        assertTrue(deleteGeneratedAppSource(workspaceDir, "aaaaaaaa-0000-0000-0000-000000000000"))
        assertFalse(victim.exists())
        assertTrue("a different generated app must survive", other.exists())
    }

    @Test
    fun deleteGeneratedAppSource_rejectsTraversalIds() {
        val escapeTarget = File(workspaceDir, "escape.txt").apply { writeText("keep") }
        assertFalse(deleteGeneratedAppSource(workspaceDir, "../escape.txt"))
        assertFalse(deleteGeneratedAppSource(workspaceDir, ".."))
        assertFalse(deleteGeneratedAppSource(workspaceDir, ""))
        assertFalse(deleteGeneratedAppSource(workspaceDir, "a/b"))
        assertFalse(deleteGeneratedAppSource(workspaceDir, "a\\b"))
        assertTrue("traversal attempts must delete nothing", escapeTarget.isFile)
        assertFalse(File(workspaceDir, "generated").exists())
    }
}
