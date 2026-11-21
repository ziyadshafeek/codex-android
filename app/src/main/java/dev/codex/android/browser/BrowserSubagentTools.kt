package dev.codex.android.browser

import android.graphics.BitmapFactory
import dev.codex.android.tools.Tool
import dev.codex.android.tools.ToolResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Browser Subagent tools (master plan Subsystem 6, M7).
 *
 * Tool names and semantics follow the tool surface of anomalyco/browse
 * (MIT, https://github.com/anomalyco/browse — navigate / snapshot /
 * click / fill / eval / screenshot / console), adapted to the
 * single embedded [VerificationBrowser] WebView instead of a desktop
 * Chromium + extension + relay. No code is copied from that project:
 * it has no Android/WebView component to reuse.
 *
 * The tools are NOT registered in [dev.codex.android.tools.ToolRegistry]
 * here — integration wires [browserTools] in later. That keeps this
 * file a pure addition: no existing file is modified.
 *
 * Safety: `browser_click`, `browser_type` and `browser_eval` are
 * sensitive (they can submit forms / run arbitrary page JS), so the
 * approval gate covers them. `browser_open` only navigates the
 * embedded WebView — the network traffic is the page load itself,
 * exactly as if the user had opened the URL in the Preview tab.
 * Nothing here touches credentials: the bridge cannot read files and
 * no key is ever sent to a page.
 */
fun browserTools(browser: VerificationBrowser): List<Tool> = listOf(
    BrowserOpenTool(browser),
    BrowserConsoleLogsTool(browser),
    BrowserDomSnapshotTool(browser),
    BrowserEvalTool(browser),
    BrowserClickTool(browser),
    BrowserTypeTool(browser),
    BrowserScreenshotTool(browser),
)

private fun requiredString(args: JsonObject, name: String): String? =
    args[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun stringSchema(description: String): JsonObjectBuilder.() -> Unit = {
    put("type", "string")
    put("description", description)
}

private fun objectSchema(
    properties: JsonObjectBuilder.() -> Unit,
    required: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties", properties)
    if (required.isNotEmpty()) {
        putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    }
}

/** Opens a URL in the embedded verification browser. */
class BrowserOpenTool(private val browser: VerificationBrowser) : Tool {
    override val name: String = "browser_open"
    override val description: String =
        "Opens a URL in the embedded verification browser (adds https:// when the scheme is missing; localhost uses http://). Returns the URL actually loaded."
    override val parametersSchema: JsonObject = objectSchema(
        properties = {
            putJsonObject("url", stringSchema("URL to open, e.g. https://example.com or localhost:3000"))
        },
        required = listOf("url"),
    )
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val url = requiredString(args, "url")
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: url")
        return try {
            val loaded = browser.openAndWait(url)
            ToolResult(ok = true, output = "Opened $loaded")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "Failed to open $url")
        }
    }
}

/** Returns captured console messages and uncaught page errors. */
class BrowserConsoleLogsTool(private val browser: VerificationBrowser) : Tool {
    override val name: String = "browser_console_logs"
    override val description: String =
        "Returns console messages and uncaught page errors captured from the page in the verification browser, oldest first. Use level=error to check for JS failures after a page load."
    override val parametersSchema: JsonObject = objectSchema(
        properties = {
            putJsonObject(
                "level",
                stringSchema("Optional filter: error, warn, log, info or debug. Omit for all levels."),
            )
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Maximum entries to return (default 100)")
            }
        },
    )
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val levelName = args["level"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        val level = when (levelName) {
            null, "", "all" -> null
            "error" -> ConsoleLevel.ERROR
            "warn", "warning" -> ConsoleLevel.WARN
            "log" -> ConsoleLevel.LOG
            "info" -> ConsoleLevel.INFO
            "debug" -> ConsoleLevel.DEBUG
            else -> return ToolResult(
                ok = false,
                output = "",
                error = "Unknown level: $levelName (use error, warn, log, info, debug or all)",
            )
        }
        val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 500) ?: 100
        return ToolResult(ok = true, output = browser.formatConsoleLogs(level, limit))
    }
}

/** Returns a compact DOM snapshot of interactive elements + headings. */
class BrowserDomSnapshotTool(private val browser: VerificationBrowser) : Tool {
    override val name: String = "browser_dom_snapshot"
    override val description: String =
        "Returns a compact JSON snapshot of the page in the verification browser: URL, title, and the interactive elements (links, buttons, inputs, selects) and headings with their tag, id, class, visible text, href, placeholder and input type. Use the ids/classes/text here to build CSS selectors for browser_click / browser_type."
    override val parametersSchema: JsonObject = objectSchema(
        properties = {
            putJsonObject("max_nodes") {
                put("type", "integer")
                put("description", "Maximum elements to include (default 400, clamped to 50..2000)")
            }
        },
    )
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val maxNodes = args["max_nodes"]?.jsonPrimitive?.intOrNull ?: VerificationBrowser.DEFAULT_MAX_NODES
        return try {
            val snapshot = browser.domSnapshot(maxNodes)
            val output = if (snapshot.length > MAX_SNAPSHOT_CHARS) {
                snapshot.take(MAX_SNAPSHOT_CHARS) + "\n...[truncated]"
            } else {
                snapshot
            }
            ToolResult(ok = true, output = output)
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "DOM snapshot failed")
        }
    }

    companion object {
        private const val MAX_SNAPSHOT_CHARS = 12_000
    }
}

/** Evaluates JavaScript in the page and returns its result as text. */
class BrowserEvalTool(private val browser: VerificationBrowser) : Tool {
    override val name: String = "browser_eval"
    override val description: String =
        "Evaluates a JavaScript expression in the page in the verification browser and returns its result as text. Use for checks the other browser tools cannot express (e.g. reading a value or testing a condition)."
    override val parametersSchema: JsonObject = objectSchema(
        properties = {
            putJsonObject("js", stringSchema("JavaScript expression to evaluate, e.g. document.title"))
        },
        required = listOf("js"),
    )
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val js = requiredString(args, "js")
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: js")
        return try {
            val result = browser.eval(js)
                ?: return ToolResult(ok = false, output = "", error = "Evaluation timed out or returned nothing")
            val output = if (result.length > MAX_EVAL_CHARS) {
                result.take(MAX_EVAL_CHARS) + "\n...[truncated]"
            } else {
                result
            }
            ToolResult(ok = true, output = output)
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "Evaluation failed")
        }
    }

    companion object {
        private const val MAX_EVAL_CHARS = 8_000
    }
}

/** Clicks the first element matching a CSS selector. */
class BrowserClickTool(private val browser: VerificationBrowser) : Tool {
    override val name: String = "browser_click"
    override val description: String =
        "Clicks the first element matching a CSS selector in the page in the verification browser. Build the selector from browser_dom_snapshot (e.g. #submit, button.primary, a[href=\"/login\"])."
    override val parametersSchema: JsonObject = objectSchema(
        properties = {
            putJsonObject("selector", stringSchema("CSS selector of the element to click"))
        },
        required = listOf("selector"),
    )
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val selector = requiredString(args, "selector")
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: selector")
        return try {
            val result = browser.click(selector)
            ToolResult(ok = result.ok, output = if (result.ok) "Clicked $selector" else "", error = if (result.ok) null else result.detail)
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "Click failed")
        }
    }
}

/** Types text into the first element matching a CSS selector. */
class BrowserTypeTool(private val browser: VerificationBrowser) : Tool {
    override val name: String = "browser_type"
    override val description: String =
        "Types text into the first element matching a CSS selector in the page in the verification browser (input, textarea, select or contenteditable). Focuses the field first and dispatches input/change events so framework-controlled inputs update."
    override val parametersSchema: JsonObject = objectSchema(
        properties = {
            putJsonObject("selector", stringSchema("CSS selector of the field to type into"))
            putJsonObject("text", stringSchema("Text to type"))
        },
        required = listOf("selector", "text"),
    )
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val selector = requiredString(args, "selector")
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: selector")
        val text = args["text"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: text")
        return try {
            val result = browser.type(selector, text)
            ToolResult(ok = result.ok, output = if (result.ok) "Typed into $selector" else "", error = if (result.ok) null else result.detail)
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "Type failed")
        }
    }
}

/**
 * Captures the current viewport as a PNG and saves it under the app
 * cache dir (`cacheDir/browser-screenshots/`). Returns the file path
 * plus dimensions and size — a later vision step reads the file to
 * visually verify layout. Files are cache: the system may reclaim
 * them, and nothing is uploaded anywhere.
 */
class BrowserScreenshotTool(private val browser: VerificationBrowser) : Tool {
    override val name: String = "browser_screenshot"
    override val description: String =
        "Captures the current viewport of the page in the verification browser as a PNG and saves it to the app cache. Returns the file path, dimensions and byte size. Read the file to visually verify layout, spacing and broken components."
    override val parametersSchema: JsonObject = objectSchema(properties = {})
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        return try {
            val png = browser.screenshot()
                ?: return ToolResult(ok = false, output = "", error = "Screenshot failed (page not rendered yet?)")
            val (path, width, height) = withContext(Dispatchers.IO) {
                val dir = File(browser.appContext.cacheDir, "browser-screenshots").apply { mkdirs() }
                val file = File(dir, "browser-${System.currentTimeMillis()}.png")
                file.writeBytes(png)
                // Prune old screenshots: every capture used to write a
                // new file and nothing ever deleted them, so a long
                // verification session grew the cache without bound.
                pruneOldScreenshots(dir)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(png, 0, png.size, options)
                Triple(file.absolutePath, options.outWidth, options.outHeight)
            }
            ToolResult(
                ok = true,
                output = "Screenshot saved to $path (${width}x$height, ${png.size} bytes)",
            )
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "Screenshot failed")
        }
    }
}

/**
 * Keeps only the [keep] newest screenshots in [dir] (by last-modified),
 * deleting the rest. Best-effort: a pruning failure never fails the
 * capture that just succeeded. Top-level so JVM tests can call it
 * directly.
 */
internal fun pruneOldScreenshots(dir: File, keep: Int = 10) {
    runCatching {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("browser-") && f.name.endsWith(".png") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(keep).forEach { runCatching { it.delete() } }
    }
}
