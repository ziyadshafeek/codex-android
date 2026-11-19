package dev.codex.android.tools

import dev.codex.android.control.UiAutomation
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
 * Taps a UI element on the current screen, by its visible text
 * or by screen coordinates. Prefer [UiAutomation.tapText] when the
 * element has readable text; use coordinates only when the target
 * has no text (e.g. an icon) and its position is known from
 * [ReadScreenTool] or a previous tap.
 *
 * Takes EITHER `text` OR `x` + `y`. Because [ToolCallParser] rejects
 * a call missing any parameter listed in "required", this schema
 * lists no required parameters and the either/or check happens in
 * [execute] instead. When both forms are given, coordinates win.
 */
object TapTool : Tool {
    override val name: String = "tap"
    override val description: String =
        "Taps a UI element on the current screen by its visible text, or by screen coordinates (x, y)"
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
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
        // No "required": either text or x+y is valid (see class doc).
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!UiAutomation.isAvailable()) {
            return ToolResult(ok = false, output = "", error = NOT_ENABLED_ERROR)
        }
        val x = args["x"]?.jsonPrimitive?.intOrNull
        val y = args["y"]?.jsonPrimitive?.intOrNull
        val text = args["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return try {
            when {
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
                    error = "Provide either 'text' or both 'x' and 'y'",
                )
            }
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "Tap failed")
        }
    }
}

/**
 * Types text into the currently focused editable field.
 * Sensitive: typed text can send messages or fill in forms, so the
 * approval gate must cover it. Tap the field first to focus it.
 */
object TypeTextTool : Tool {
    override val name: String = "type_text"
    override val description: String =
        "Types text into the currently focused editable field, e.g. a search box or message field"
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") {
                put("type", "string")
                put("description", "Text to type into the focused field")
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
        return try {
            if (UiAutomation.typeText(text)) {
                ToolResult(ok = true, output = "Typed text")
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
 * Returns the visible text of the current screen, as a dump from
 * the accessibility service. Use it to see what is on screen before
 * deciding what to tap or type.
 */
object ReadScreenTool : Tool {
    override val name: String = "read_screen"
    override val description: String =
        "Returns the visible text of the current screen; use it to see what is on screen before tapping or typing"
    override val parametersSchema: JsonObject = noArgsSchema()
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!UiAutomation.isAvailable()) {
            return ToolResult(ok = false, output = "", error = NOT_ENABLED_ERROR)
        }
        return try {
            val dump = UiAutomation.readScreen()
            // readScreen() reports an unavailable service as an error
            // message starting with this prefix; surface it as a failure.
            if (dump.startsWith("Accessibility service not enabled")) {
                ToolResult(ok = false, output = "", error = NOT_ENABLED_ERROR)
            } else if (dump.isBlank()) {
                ToolResult(
                    ok = false,
                    output = "",
                    error = "No readable text on the current screen",
                )
            } else {
                ToolResult(ok = true, output = dump)
            }
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
