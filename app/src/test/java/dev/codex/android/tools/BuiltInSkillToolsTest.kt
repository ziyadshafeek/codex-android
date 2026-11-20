package dev.codex.android.tools

import android.content.Intent
import dev.codex.android.accessibility.UiNode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the tools the 3 built-in skills name.
 *
 * Honest limits of a JVM unit test (no Robolectric, no device):
 * - [GetTimeTool] runs for real (pure java.time).
 * - [OpenAppTool] runs for real against a fake package resolver:
 *   the resolver returning null proves the "no launchable app"
 *   path without launching anything; the resolver is the injected
 *   seam in BuiltinTools.kt. The tool is built with a null Context
 *   (its constructor accepts one) because a real android.content
 *   .Context cannot exist in a JVM test — the tests never touch the
 *   Context (missing-arg and null-resolver paths return first).
 * - [ReadScreenTool] cannot reach a real screen in a JVM test: the
 *   accessibility service does not exist here, so the test proves
 *   the fail-fast contract (ok=false + the not-enabled error) and
 *   proves the screen text itself is real via [UiNode.toCompactText],
 *   the exact text read_screen returns on a device. The live
 *   tree/tap path still needs the phone.
 */
class BuiltInSkillToolsTest {

    // --- what-time: get_time ------------------------------------------

    @Test
    fun getTime_returnsCurrentDateTime() = runBlocking {
        val result = GetTimeTool.execute(buildJsonObject {})
        assertTrue(result.ok)
        // e.g. "2026-11-21 01:12:00 IST (Asia/Kolkata)"
        assertTrue(
            "unexpected get_time output: ${result.output}",
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} .+""").matches(result.output),
        )
    }

    // --- open-app-and-read: open_app ----------------------------------
    // Built without a constructor: OpenAppTool needs an Android
    // Context, which cannot exist in a JVM unit test. The injected
    // resolver is set by reflection; the Context field stays null and
    // is never touched on the paths tested here.

    private fun openAppToolWithResolver(resolver: (String) -> Intent?): OpenAppTool =
        OpenAppTool(context = null, launchIntentForPackage = resolver)

    @Test
    fun openApp_missingPackageName_clearError() = runBlocking {
        val result = openAppToolWithResolver { null }.execute(buildJsonObject {})
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("package_name"))
    }

    @Test
    fun openApp_unknownPackage_noLaunchableAppAndNothingLaunched() = runBlocking {
        var resolverCalls = 0
        val tool = openAppToolWithResolver { packageName ->
            resolverCalls++
            assertEquals("com.whatsapp", packageName)
            null // no launch intent: nothing to launch, nothing launched
        }
        val result = tool.execute(buildJsonObject { put("package_name", "com.whatsapp") })
        assertEquals(1, resolverCalls)
        assertFalse(result.ok)
        assertEquals("No launchable app for package com.whatsapp", result.error)
    }

    // --- summarise-screen + open-app-and-read: read_screen -----------

    @Test
    fun readScreen_withoutAccessibilityService_failsFastWithClearError() = runBlocking {
        // No accessibility service in a JVM test: the tool must fail
        // fast with the not-enabled error, never crash or hang.
        val result = ReadScreenTool.execute(buildJsonObject {})
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("Accessibility service not enabled"))
    }

    @Test
    fun readScreen_screenText_isRealUiNodeText() {
        // The exact text read_screen returns on a device is
        // UiNode.toCompactText(): prove it carries real screen text.
        val tree = UiNode(
            id = 0, left = 0, top = 0, right = 1080, bottom = 2400,
            className = "android.widget.FrameLayout",
            children = listOf(
                UiNode(
                    id = 1, left = 100, top = 200, right = 980, bottom = 300,
                    text = "Hello, Ziyad", className = "android.widget.TextView",
                ),
                UiNode(
                    id = 2, left = 100, top = 400, right = 500, bottom = 500,
                    text = "Search", className = "android.widget.Button",
                    clickable = true, enabled = true,
                ),
            ),
        )
        val compact = tree.toCompactText()
        assertTrue(compact.contains("Hello, Ziyad"))
        assertTrue(compact.contains("[2] Button \"Search\""))
        assertTrue(compact.contains("clickable"))
        assertEquals(3, tree.countNodes())
    }

    @Test
    fun skillTools_areRegisteredUnderTheirSkillNames() {
        val registry = ToolRegistry().apply {
            register(GetTimeTool)
            register(ReadScreenTool)
            register(openAppToolWithResolver { null })
        }
        assertEquals(GetTimeTool, registry.get("get_time"))
        assertEquals(ReadScreenTool, registry.get("read_screen"))
        assertEquals("open_app", registry.get("open_app")?.name)
    }
}
