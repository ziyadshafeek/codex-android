package dev.codex.android.ui

import androidx.compose.ui.graphics.Color

/**
 * OLED Material 3 palette for Codex Mobile Studio, defined once here.
 *
 * Imported by the workbench ([WorkbenchScreen]), the Sora editor (H3) and
 * the desktop workstation (H6) so every screen shares one palette:
 * pure-black background (true OLED off-pixels), dark blue-grey surfaces,
 * cyan primary accent, amber for warnings / the approval queue.
 */
val OledBlack = Color(0xFF000000)
val OledSurface = Color(0xFF0A0D12)
val OledSurfaceElevated = Color(0xFF131922)
val OledAccentCyan = Color(0xFF38BDF8)
val OledAmber = Color(0xFFF59E0B)
