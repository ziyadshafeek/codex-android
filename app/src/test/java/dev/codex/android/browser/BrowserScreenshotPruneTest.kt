package dev.codex.android.browser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM unit tests for [pruneOldScreenshots]: the screenshot tool used to
 * write a new file per capture and never delete anything, growing the
 * cache without bound. No Android framework classes are touched, so
 * these run on the JVM.
 */
class BrowserScreenshotPruneTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun keepsOnlyNewestTenScreenshots() {
        val dir = temp.newFolder("browser-screenshots")
        val now = System.currentTimeMillis()
        (0 until 12).forEach { i ->
            File(dir, "browser-$i.png").apply {
                writeBytes(byteArrayOf(0x89.toByte(), 0x50))
                setLastModified(now - (12 - i) * 1000L)
            }
        }
        pruneOldScreenshots(dir)
        val remaining = dir.listFiles()?.map { it.name }.orEmpty()
        assertEquals(10, remaining.size)
        // The two oldest are gone; the newest ten remain.
        assertTrue(remaining.none { it == "browser-0.png" || it == "browser-1.png" })
        assertTrue(remaining.contains("browser-11.png"))
    }

    @Test
    fun ignoresNonScreenshotFiles() {
        val dir = temp.newFolder("browser-screenshots")
        File(dir, "notes.txt").writeText("keep me")
        File(dir, "browser-0.png").writeBytes(byteArrayOf(1))
        pruneOldScreenshots(dir, keep = 0)
        assertTrue(File(dir, "notes.txt").exists())
        assertEquals(0, dir.listFiles { f -> f.name.endsWith(".png") }?.size)
    }

    @Test
    fun missingDirectoryDoesNotThrow() {
        pruneOldScreenshots(File(temp.root, "no-such-dir"))
    }
}
