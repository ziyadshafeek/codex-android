package dev.codex.android.tools

import dev.codex.android.control.UiAutomation
import dev.codex.android.service.MediaProjectionCapture
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Shared failure message when the accessibility service is off.
 * Every control tool fails fast with this instead of hanging or crashing.
 */
private const val NOT_ENABLED_ERROR =
    "Accessibility service not enabled — enable Codex in Settings > Accessibility"

/** JSON Schema for a tool that takes no arguments: {"type":"object","properties":{}}. */
private fun noArgsSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {}
}

/**
 * Taps a UI element on the current screen, by its node id from
 * [ReadScreenTool], by its visible text, or by screen coordinates.
 * Prefer [UiAutomation.tapNode] with a `node_id` from the ui_tree when
 * the element has one — it is exact, unlike text matching (which can
 * hit the wrong duplicate) or raw coordinates (which go stale when the
 * screen moves). Use coordinates only when the target has no node id
 * (e.g. a drawn icon inside a Canvas) and its position is known from
 * the tree's centre coordinates.
 *
 * Takes `node_id` OR `text` OR `x` + `y`. Because [ToolCallParser]
 * rejects a call missing any parameter listed in "required", this
 * schema lists no required parameters and the check happens in
 * [execute] instead. Precedence: node_id, then coordinates, then text.
 */
object TapTool : Tool {
    override val name: String = "tap"
    override val description: String =
        "Taps a UI element on the current screen by its node id from read_screen, by its visible text, or by screen coordinates (x, y)"
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("node_id") {
                put("type", "integer")
                put("description", "Node id from the read_screen ui_tree, e.g. 3 for '[3] Button \"Search\"'")
            }
            putJsonObject("text") {
                put("type", "string")
                put("description", "Visible text of the element to tap, e.g. the label of a button")
            }
            putJsonObject("x") {
                put("type", "integer")
                put("description", "Horizontal screen coordinate to tap; use together with y")
            }
            putJsonObject("y") {
                put("type", "integer")
                put("description", "Vertical screen coordinate to tap; use together with x")
            }
        }
        // No "required": node_id, text, or x+y is valid (see class doc).
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!UiAutomation.isAvailable()) {
            return ToolResult(ok = false, output = "", error = NOT_ENABLED_ERROR)
        }
        val nodeId = args["node_id"]?.jsonPrimitive?.intOrNull
        val x = args["x"]?.jsonPrimitive?.intOrNull
        val y = args["y"]?.jsonPrimitive?.intOrNull
        val text = args["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return try {
            when {
                nodeId != null -> {
                    if (UiAutomation.tapNode(nodeId)) {
                        ToolResult(ok = true, output = "Tapped node $nodeId")
                    } else {
                        ToolResult(
                            ok = false,
                            output = "",
                            error = "No node $nodeId on the current screen — re-read the screen first, ids change when the screen moves",
                        )
                    }
                }
                x != null && y != null -> {
                    if (UiAutomation.tapAt(x, y)) {
                        ToolResult(ok = true, output = "Tapped at ($x, $y)")
                    } else {
                        ToolResult(
                            ok = false,
                            output = "",
                            error = "Tap at ($x, $y) did not land on a tappable element",
                        )
                    }
                }
                text != null -> {
                    if (UiAutomation.tapText(text)) {
                        ToolResult(ok = true, output = "Tapped \"$text\"")
                    } else {
                        ToolResult(
                            ok = false,
                            output = "",
                            error = "No tappable element with text \"$text\" found on screen",
                        )
                    }
                }
                x != null || y != null -> ToolResult(
                    ok = false,
                    output = "",
                    error = "Tap by coordinates needs both x and y",
                )
                else -> ToolResult(
                    ok = false,
                    output = "",
                    error = "Provide 'node_id', 'text', or both 'x' and 'y'",
                )
            }
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "Tap failed")
        }
    }
}

/**
 * Types text into an editable field: the node with `node_id` from the
 * ui_tree when given, otherwise the currently focused editable field.
 * Sensitive: typed text can send messages or fill in forms, so the
 * approval gate must cover it. Tap the field first to focus it.
 */
object TypeTextTool : Tool {
    override val name: String = "type_text"
    override val description: String =
        "Types text into an editable field — the node_id from read_screen when given, otherwise the currently focused editable field, e.g. a search box or message field"
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") {
                put("type", "string")
                put("description", "Text to type into the field")
            }
            putJsonObject("node_id") {
                put("type", "integer")
                put("description", "Node id of the editable field from the read_screen ui_tree; omit to type into the focused field")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("text")) }
    }
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!UiAutomation.isAvailable()) {
            return ToolResult(ok = false, output = "", error = NOT_ENABLED_ERROR)
        }
        val text = args["text"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: text")
        if (text.isEmpty()) {
            return ToolResult(ok = false, output = "", error = "Text must not be empty")
        }
        val nodeId = args["node_id"]?.jsonPrimitive?.intOrNull
        return try {
            val typed = if (nodeId != null) {
                UiAutomation.typeIntoNode(nodeId, text)
            } else {
                UiAutomation.typeText(text)
            }
            if (typed) {
                ToolResult(ok = true, output = "Typed text")
            } else if (nodeId != null) {
                ToolResult(
                    ok = false,
                    output = "",
                    error = "Could not type into node $nodeId — it is not an editable field or no longer exists. Re-read the screen first.",
                )
            } else {
                ToolResult(
                    ok = false,
                    output = "",
                    error = "Could not type text — no editable field is focused. Tap the field first.",
                )
            }
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "Typing failed")
        }
    }
}

/**
 * Returns the structured UI tree (`ui_tree`) of the current screen —
 * one numbered line per element, `[3] Button "Search" clickable
 * (540,212)` — from the accessibility service. Use the node ids and
 * centre coordinates to tap / type precisely. Read it before tapping
 * or typing, and re-read it after every action: ids go stale as soon
 * as the screen moves.
 *
 * Fallback chain when the tree is empty (WebViews, Canvas / game /
 * map apps expose no nodes):
 * 1. the old flat text dump, when the screen has any readable text;
 * 2. an Accessibility `takeScreenshot()` PNG in the app's private
 *    cacheDir (no extra permission needed);
 * 3. a MediaProjection capture, but only after the user granted
 *    screen-capture consent — never silently. Without consent the
 *    tool fails with instructions instead of capturing.
 */
object ReadScreenTool : Tool {
    override val name: String = "read_screen"
    override val description: String =
        "Returns the structured UI tree of the current screen (one numbered line per element with node id and centre coordinates); use it to see what is on screen before tapping or typing"
    override val parametersSchema: JsonObject = noArgsSchema()
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!UiAutomation.isAvailable()) {
            return ToolResult(ok = false, output = "", error = NOT_ENABLED_ERROR)
        }
        return try {
            // 1. Structured tree — the normal path.
            val tree = UiAutomation.readScreenTree()
            if (tree != null) {
                val useful = tree.countNodes() > 1 ||
                    tree.text.isNotBlank() ||
                    tree.contentDescription.isNotBlank() ||
                    tree.clickable ||
                    tree.editable
                if (useful) {
                    return ToolResult(ok = true, output = tree.toCompactText())
                }
            }
            // 2. Flat text dump — tree empty but text exists.
            val dump = UiAutomation.readScreen()
            if (dump.startsWith("Accessibility service not enabled")) {
                return ToolResult(ok = false, output = "", error = NOT_ENABLED_ERROR)
            }
            if (dump.isNotBlank()) {
                return ToolResult(
                    ok = true,
                    output = "ui_tree empty — text dump:\n$dump",
                )
            }
            // 3. Screenshot fallbacks — no nodes and no text.
            val screenshotPath = UiAutomation.captureScreenshotToFile()
            if (screenshotPath != null) {
                return ToolResult(
                    ok = true,
                    output = "No readable UI tree on this screen (WebView / Canvas app). " +
                        "Screenshot saved to $screenshotPath — describe what you see and tap by coordinates from it.",
                )
            }
            if (MediaProjectionCapture.isReady()) {
                val file = MediaProjectionCapture.captureToCacheFile()
                if (file != null) {
                    return ToolResult(
                        ok = true,
                        output = "No readable UI tree on this screen (WebView / Canvas app). " +
                            "Screenshot saved to ${file.absolutePath} — describe what you see and tap by coordinates from it.",
                    )
                }
                return ToolResult(
                    ok = false,
                    output = "",
                    error = MediaProjectionCapture.lastError
                        ?: "Screenshot capture failed",
                )
            }
            ToolResult(
                ok = false,
                output = "",
                error = "No readable UI on the current screen (WebView / Canvas / game / map app). " +
                    "Grant screen-capture permission in Codex settings first, then read the screen again.",
            )
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "Failed to read screen")
        }
    }
}

/** Scrolls the current screen down or up, e.g. to reveal more content. */
object ScrollTool : Tool {
    override val name: String = "scroll"
    override val description: String =
        "Scrolls the current screen down or up to reveal more content"
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("direction") {
                put("type", "string")
                put("description", "Direction to scroll: \"down\" or \"up\"")
                putJsonArray("enum") {
                    add(JsonPrimitive("down"))
                    add(JsonPrimitive("up"))
                }
            }
        }
        putJsonArray("required") { add(JsonPrimitive("direction")) }
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!UiAutomation.isAvailable()) {
            return ToolResult(ok = false, output = "", error = NOT_ENABLED_ERROR)
        }
        val direction = args["direction"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                ok = false,
                output = "",
                error = "Missing required argument: direction",
            )
        return try {
            val scrolled = when (direction) {
                "down" -> UiAutomation.scrollDown()
                "up" -> UiAutomation.scrollUp()
                else -> return ToolResult(
                    ok = false,
                    output = "",
                    error = "Unknown scroll direction \"$direction\" — use \"down\" or \"up\"",
                )
            }
            if (scrolled) {
                ToolResult(ok = true, output = "Scrolled $direction")
            } else {
                ToolResult(
                    ok = false,
                    output = "",
                    error = "Could not scroll $direction — already at the edge or nothing scrollable on screen",
                )
            }
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "Scroll failed")
        }
    }
}

/** Goes back one step, as if pressing the system Back button. */
object GoBackTool : Tool {
    override val name: String = "go_back"
    override val description: String =
        "Goes back one step, as if pressing the system Back button"
    override val parametersSchema: JsonObject = noArgsSchema()
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!UiAutomation.isAvailable()) {
            return ToolResult(ok = false, output = "", error = NOT_ENABLED_ERROR)
        }
        return try {
            if (UiAutomation.goBack()) {
                ToolResult(ok = true, output = "Went back")
            } else {
                ToolResult(
                    ok = false,
                    output = "",
                    error = "Could not go back — already at the first screen",
                )
            }
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "Go back failed")
        }
    }
}

/** Goes to the Android home screen. */
object PressHomeTool : Tool {
    override val name: String = "press_home"
    override val description: String = "Goes to the Android home screen"
    override val parametersSchema: JsonObject = noArgsSchema()
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!UiAutomation.isAvailable()) {
            return ToolResult(ok = false, output = "", error = NOT_ENABLED_ERROR)
        }
        return try {
            if (UiAutomation.goHome()) {
                ToolResult(ok = true, output = "Went to the home screen")
            } else {
                ToolResult(
                    ok = false,
                    output = "",
                    error = "Could not go to the home screen",
                )
            }
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "Press home failed")
        }
    }
}
