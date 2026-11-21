package dev.codex.android.linux

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM unit tests for [RootfsManager.wipeRootfs] — the one public delete
 * method added for the Storage screen.
 *
 * Uses the internal constructor with temp dirs; no network, no Android
 * APIs, no install/bootstrap logic is exercised (and none was changed).
 */
class RootfsManagerDeleteTest {

    private lateinit var filesDir: File
    private lateinit var manager: RootfsManager

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("codex-rootfs-delete-test").toFile()
        manager = RootfsManager(filesDir, filesDir)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    private fun installFakeRootfs() {
        val root = manager.rootfsDir()
        File(root, "bin").mkdirs()
        File(root, "bin/sh").writeText("#!/bin/sh\n")
        File(root, "usr/lib").mkdirs()
        File(root, "usr/lib/libfoo.so").writeText("fake")
        File(root, ".codex_rootfs_ready").writeText("alpine-minirootfs-aarch64\n")
        assertTrue(manager.isInstalled())
    }

    @Test
    fun wipeRootfs_removesWholeRootfsAndReportsGone() {
        installFakeRootfs()
        assertTrue(manager.wipeRootfs())
        assertFalse("rootfs dir must be really gone", manager.rootfsDir().exists())
        assertFalse(manager.isInstalled())
    }

    @Test
    fun wipeRootfs_alreadyGoneIsSuccess() {
        assertFalse(manager.rootfsDir().exists())
        assertTrue(manager.wipeRootfs())
    }

    @Test
    fun wipeRootfs_secondWipeIsStillSuccess() {
        installFakeRootfs()
        assertTrue(manager.wipeRootfs())
        assertTrue(manager.wipeRootfs())
    }

    @Test
    fun wipeRootfs_doesNotFollowSymlinksOutsideRootfs() {
        installFakeRootfs()
        val outside = File(filesDir, "outside.txt").apply { writeText("do not delete") }
        val link = File(manager.rootfsDir(), "link-to-outside")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertTrue(manager.wipeRootfs())
        assertFalse(manager.rootfsDir().exists())
        assertTrue("a symlink inside the rootfs must not delete its target", outside.isFile)
        assertEquals("do not delete", outside.readText())
    }

    @Test
    fun wipeRootfs_leavesSiblingLinuxDirsAlone() {
        installFakeRootfs()
        val stage = File(filesDir, "linux/native-stage").apply { mkdirs() }
        File(stage, "libproot.so").writeText("staged")
        assertTrue(manager.wipeRootfs())
        assertTrue("native-stage (reinstall inputs) must survive a rootfs wipe", File(stage, "libproot.so").isFile)
    }

    private fun assertEquals(expected: String, actual: String) =
        org.junit.Assert.assertEquals(expected, actual)
}
