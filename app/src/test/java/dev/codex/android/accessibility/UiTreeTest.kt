package dev.codex.android.accessibility

import dev.codex.android.core.CodexJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure parts of [UiNode]: compact text
 * serialisation, id lookup, node counting, centres, and JSON
 * round-tripping. The live-tree walks ([UiNode.buildUiTree] /
 * [UiNode.findLiveNodeById]) need a real AccessibilityNodeInfo and are
 * therefore NOT testable here — no Robolectric in this project; they
 * are covered by the instrumented suite / on-device checks instead.
 */
class UiTreeTest {

    /** Small hand-built tree: root -> search button + editable field. */
    private fun sampleTree(): UiNode = UiNode(
        id = 0,
        left = 0,
        top = 0,
        right = 1080,
        bottom = 2400,
        className = "android.widget.FrameLayout",
        enabled = true,
        children = listOf(
            UiNode(
                id = 1,
                left = 480,
                top = 162,
                right = 600,
                bottom = 262,
                text = "Search",
                className = "android.widget.Button",
                clickable = true,
                enabled = true,
            ),
            UiNode(
                id = 2,
                left = 60,
                top = 300,
                right = 1020,
                bottom = 400,
                text = "",
                contentDescription = "Search box",
                className = "android.widget.EditText",
                clickable = true,
                enabled = true,
                editable = true,
                focused = true,
            ),
        ),
    )

    @Test
    fun compactText_matchesOpenPhoneUseLineShape() {
        val text = sampleTree().toCompactText()
        assertTrue(text.contains("[1] Button \"Search\" (540,212) clickable"))
        assertTrue(text.contains("[2] EditText (Search box) (540,350) clickable editable focused"))
    }

    @Test
    fun compactText_indentsChildren() {
        val text = sampleTree().toCompactText()
        val lines = text.lines()
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("  [1]"))
        assertTrue(lines[2].startsWith("  [2]"))
    }

    @Test
    fun compactText_truncatesLongLabels() {
        val node = UiNode(
            id = 0,
            left = 0,
            top = 0,
            right = 100,
            bottom = 100,
            text = "x".repeat(200),
            className = "android.widget.TextView",
            enabled = true,
        )
        val text = node.toCompactText()
        assertTrue(text.contains("\"" + "x".repeat(UiNode.MAX_LABEL_CHARS) + "\""))
        assertTrue(text.length < 200)
    }

    @Test
    fun findById_returnsNestedNodeAndNullForMissing() {
        val tree = sampleTree()
        assertEquals("Search", tree.findById(1)?.text)
        assertTrue(tree.findById(2)?.editable == true)
        assertEquals(tree, tree.findById(0))
        assertNull(tree.findById(99))
    }

    @Test
    fun countNodes_countsWholeSubtree() {
        assertEquals(3, sampleTree().countNodes())
        assertEquals(1, sampleTree().findById(1)?.countNodes())
    }

    @Test
    fun centre_isMidpointOfBounds() {
        val node = sampleTree().findById(1)!!
        assertEquals(540, node.centerX())
        assertEquals(212, node.centerY())
    }

    @Test
    fun json_roundTrip_preservesTree() {
        val tree = sampleTree()
        val json = CodexJson.encodeToString(tree)
        val decoded: UiNode = CodexJson.decodeFromString(json)
        assertEquals(tree, decoded)
    }
}
