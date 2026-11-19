package dev.codex.android.ci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * JVM unit tests for the installer hash helpers in ApkInstaller.kt.
 * These are pure java.io / java.security functions — no Android
 * framework call is made, so they run on the JVM without Robolectric.
 * (The Context-dependent install paths are exercised on-device / in
 * instrumented tests, not here.)
 */
class ApkInstallerTest {

    @Test
    fun sha256Hex_matchesKnownVector() {
        val root = Files.createTempDirectory("ci-hash-test-").toFile()
        try {
            val file = java.io.File(root, "a.txt").apply { writeText("abc") }
            // SHA-256("abc") — standard test vector.
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                sha256Hex(file),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun verifySha256_acceptsMatchRejectsMismatch() {
        val root = Files.createTempDirectory("ci-verify-test-").toFile()
        try {
            val file = java.io.File(root, "app-debug.apk").apply { writeText("fake-apk") }
            val good = sha256Hex(file)
            assertTrue(verifySha256(file, good))
            assertTrue(verifySha256(file, good.uppercase())) // case-insensitive
            assertFalse(verifySha256(file, "00".repeat(32)))
            assertFalse(verifySha256(java.io.File(root, "missing.apk"), good))
        } finally {
            root.deleteRecursively()
        }
    }
}
