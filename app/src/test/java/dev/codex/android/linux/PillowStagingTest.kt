package dev.codex.android.linux

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM test for the Pillow staging entry in [RootfsManager].
 *
 * The Alpine package for Pillow is `py3-pillow` (pre-built and
 * musl-compatible — `pip install Pillow` would need a C toolchain and
 * headers). The image_enhance tool installs it on demand through
 * `ensureToolchain("pillow")`.
 */
class PillowStagingTest {

    @Test
    fun pillow_isRegisteredAsPy3Pillow() {
        assertEquals(listOf("py3-pillow"), RootfsManager.TOOLCHAIN_PACKAGES["pillow"])
    }

    @Test
    fun pythonToolchain_stillInstallsPythonAndPip() {
        assertEquals(listOf("python3", "py3-pip"), RootfsManager.TOOLCHAIN_PACKAGES["python"])
    }
}
