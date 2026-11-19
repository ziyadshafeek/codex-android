package dev.codex.android.desktop

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for [DesktopLayout]: 3-pane / 2-pane / single-pane mode selection. */
class DesktopWorkstationTest {

    @Test
    fun modeFor_expandedIsThreePane() {
        assertEquals(
            DesktopLayoutMode.THREE_PANE,
            DesktopLayout.modeFor(WindowWidthSizeClass.Expanded),
        )
        assertTrue(DesktopLayout.isDesktopClass(WindowWidthSizeClass.Expanded))
    }

    @Test
    fun modeFor_mediumIsTwoPane() {
        assertEquals(
            DesktopLayoutMode.TWO_PANE,
            DesktopLayout.modeFor(WindowWidthSizeClass.Medium),
        )
        assertFalse(DesktopLayout.isDesktopClass(WindowWidthSizeClass.Medium))
    }

    @Test
    fun modeFor_compactIsSinglePane() {
        assertEquals(
            DesktopLayoutMode.SINGLE_PANE,
            DesktopLayout.modeFor(WindowWidthSizeClass.Compact),
        )
        assertFalse(DesktopLayout.isDesktopClass(WindowWidthSizeClass.Compact))
    }
}
