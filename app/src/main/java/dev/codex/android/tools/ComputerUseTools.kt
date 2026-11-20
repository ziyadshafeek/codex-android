package dev.codex.android.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * Subsystem 8 (Master Plan M8/M9) — precise phone computer-use tools:
 * swipe by coordinates, tap/type by ui_tree node id, intent dispatch,
 * and clipboard get/set.
 *
 * Pattern credit (open-source check, 2026-11-19):
 * - OpenPhoneUse (MIT) — tool set `tap`, `type_text`, `scroll`,
 *   `open_app`, `batch`; screen re-checked live before acting.
 * - Deft (MIT) — 22-tool registry including tap / swipe / type /
 *   scroll / open app / global actions / find node.
 * - Zafiro (MIT) — gesture dispatch + app launch via Accessibility
 *   and (root/Shizuku-only) shell taps; the shell/Shizuku paths were
 *   NOT reused — this app stays unrooted, gestures go through the
 *   AccessibilityService only.
 *
 * Registration: these tools ARE registered by
 * [ToolRegistry.withBuiltins] (the single registry builder used by chat,
 * automations, subagents and the daemon), so the model can call them.
 * Names are new (`swipe`, `tap_node`, `send_intent`, `clipboard_get`,
 * `clipboard_set`) — the existing `tap` / `type_text` / `read_screen` /
 * `scroll` / `go_back` / `press_home` tools in ControlTools.kt keep
 * their names and schemas, extended only with an optional `node_id`
 * where it fits.
 */

private const val COMPUTER_USE_NOT_ENABLED_ERROR =
    "Accessibility service not enabled — enable Codex in Settings > Accessibility"

/**
 * System-only privileged-call action. There is no public
 * `Intent.ACTION_CALL_PRIVILEGED` constant (the action is hidden in
 * the SDK), so it is spelled out here for the send_intent guard.
 */
private const val ACTION_CALL_PRIVILEGED = "android.intent.action.CALL_PRIVILEGED"

/** Swipes from (x1, y1) to (x2, y2): precise scrolls, dismissals, drags. */
object SwipeTool : Tool {
    override val name: String = "swipe"
    override val description: String =
        "Swipes on the screen from (x1, y1) to (x2, y2), e.g. to scroll a list precisely, dismiss a card, or drag an element"
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("x1") {
                put("type", "integer")
                put("description", "Horizontal start coordinate")
            }
            putJsonObject("y1") {
                put("type", "integer")
                put("description", "Vertical start coordinate")
            }
            putJsonObject("x2") {
                put("type", "integer")
                put("description", "Horizontal end coordinate")
            }
            putJsonObject("y2") {
                put("type", "integer")
                put("description", "Vertical end coordinate")
            }
            putJsonObject("duration_ms") {
                put("type", "integer")
                put("description", "Swipe duration in milliseconds, 100–2000; default 300")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("x1"))
            add(JsonPrimitive("y1"))
            add(JsonPrimitive("x2"))
            add(JsonPrimitive("y2"))
        }
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!UiAutomation.isAvailable()) {
            return ToolResult(ok = false, output = "", error = COMPUTER_USE_NOT_ENABLED_ERROR)
        }
        val x1 = args["x1"]?.jsonPrimitive?.intOrNull
        val y1 = args["y1"]?.jsonPrimitive?.intOrNull
        val x2 = args["x2"]?.jsonPrimitive?.intOrNull
        val y2 = args["y2"]?.jsonPrimitive?.intOrNull
        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            return ToolResult(
                ok = false,
                output = "",
                error = "Missing required argument: x1, y1, x2, y2",
            )
        }
        val durationMs = args["duration_ms"]?.jsonPrimitive?.intOrNull ?: 300
        return try {
            if (UiAutomation.swipe(x1, y1, x2, y2, durationMs)) {
                ToolResult(ok = true, output = "Swiped from ($x1, $y1) to ($x2, $y2)")
            } else {
                ToolResult(
                    ok = false,
                    output = "",
                    error = "Swipe from ($x1, $y1) to ($x2, $y2) did not complete",
                )
            }
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "Swipe failed")
        }
    }
}

/**
 * Taps the node with `node_id` from the read_screen ui_tree.
 * Same action as `tap` with `node_id`, as its own explicit tool so the
 * model can call it without the tap tool's either/or argument shape.
 */
object TapNodeTool : Tool {
    override val name: String = "tap_node"
    override val description: String =
        "Taps the UI element with the given node id from the read_screen ui_tree"
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("node_id") {
                put("type", "integer")
                put("description", "Node id from the read_screen ui_tree, e.g. 3 for '[3] Button \"Search\"'")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("node_id")) }
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!UiAutomation.isAvailable()) {
            return ToolResult(ok = false, output = "", error = COMPUTER_USE_NOT_ENABLED_ERROR)
        }
        val nodeId = args["node_id"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: node_id")
        return try {
            if (UiAutomation.tapNode(nodeId)) {
                ToolResult(ok = true, output = "Tapped node $nodeId")
            } else {
                ToolResult(
                    ok = false,
                    output = "",
                    error = "No node $nodeId on the current screen — re-read the screen first, ids change when the screen moves",
                )
            }
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "Tap failed")
        }
    }
}

/**
 * Dispatches an Android intent: opens a URL / deep link, or launches
 * an app by package name. With only `package_name` this is the same
 * launch [OpenAppTool] does; with `action` + `uri` it opens links and
 * deep links in the app that handles them.
 *
 * Safety: direct-call intents are refused — the agent must never place
 * a call without the user dialling. ACTION_DIAL (opens the dialer
 * without calling) is allowed.
 */
class SendIntentTool(private val context: Context) : Tool {
    override val name: String = "send_intent"
    override val description: String =
        "Dispatches an Android intent: opens a URL or deep link, or launches an app by package name"
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "Intent action, e.g. android.intent.action.VIEW; defaults to VIEW when a uri is given, MAIN when only a package is given")
            }
            putJsonObject("uri") {
                put("type", "string")
                put("description", "URI to open, e.g. https://example.com or a deep link like myapp://route")
            }
            putJsonObject("package_name") {
                put("type", "string")
                put("description", "Package to target, e.g. com.example.app; with no action/uri this just launches the app")
            }
        }
        // No "required": package_name alone, or action+uri, is valid.
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val uri = args["uri"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val packageName = args["package_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        if (action == null && uri == null && packageName == null) {
            return ToolResult(
                ok = false,
                output = "",
                error = "Provide 'package_name', or 'action' + 'uri'",
            )
        }
        if (action != null && (action == Intent.ACTION_CALL ||
                action == ACTION_CALL_PRIVILEGED || action.endsWith(".CALL"))
        ) {
            return ToolResult(
                ok = false,
                output = "",
                error = "Direct-call intents are refused — use ACTION_DIAL to open the dialer instead",
            )
        }
        return try {
            val intent = when {
                action == null && uri == null && packageName != null -> {
                    context.packageManager.getLaunchIntentForPackage(packageName)
                        ?: return ToolResult(
                            ok = false,
                            output = "",
                            error = "No launchable app for package $packageName",
                        )
                }
                else -> Intent(action ?: Intent.ACTION_VIEW).apply {
                    if (uri != null) data = Uri.parse(uri)
                    if (packageName != null) setPackage(packageName)
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult(
                ok = true,
                output = "Dispatched intent" + (packageName?.let { " to $it" }.orEmpty()),
            )
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "Intent dispatch failed")
        }
    }
}

/**
 * Returns the current clipboard text. Sensitive: the clipboard can
 * hold passwords, codes and private text, so the approval gate must
 * cover it — and on Android 12+ the system itself shows the user a
 * toast that Codex read the clipboard.
 */
class ClipboardGetTool(private val context: Context) : Tool {
    override val name: String = "clipboard_get"
    override val description: String = "Returns the current clipboard text"
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        return try {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = manager.primaryClip
                ?: return ToolResult(ok = false, output = "", error = "Clipboard is empty")
            if (clip.itemCount == 0) {
                return ToolResult(ok = false, output = "", error = "Clipboard is empty")
            }
            val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
            if (text.isEmpty()) {
                ToolResult(ok = false, output = "", error = "Clipboard is empty")
            } else {
                ToolResult(ok = true, output = text)
            }
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "Could not read clipboard")
        }
    }
}

/**
 * Sets the clipboard text. Sensitive: it overwrites whatever the user
 * had copied, so the approval gate must cover it.
 */
class ClipboardSetTool(private val context: Context) : Tool {
    override val name: String = "clipboard_set"
    override val description: String = "Sets the clipboard text"
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") {
                put("type", "string")
                put("description", "Text to put on the clipboard")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("text")) }
    }
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: text")
        return try {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("Codex", text))
            ToolResult(ok = true, output = "Copied to clipboard")
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "Could not set clipboard")
        }
    }
}

/**
 * Registers the computer-use tools on [registry]. Called by
 * `ToolRegistry.withBuiltins`. Safe to call twice: registering replaces
 * the previous tool of the same name.
 */
fun registerComputerUseTools(registry: ToolRegistry, context: Context) {
    registry.register(SwipeTool)
    registry.register(TapNodeTool)
    registry.register(SendIntentTool(context))
    registry.register(ClipboardGetTool(context))
    registry.register(ClipboardSetTool(context))
}
