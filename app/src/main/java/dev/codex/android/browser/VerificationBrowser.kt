package dev.codex.android.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Severity of one captured console / page-error entry. */
enum class ConsoleLevel { LOG, INFO, WARN, ERROR, DEBUG }

/** One captured console message or uncaught page error. */
data class ConsoleLogEntry(
    val level: ConsoleLevel,
    val message: String,
    val source: String = "",
    val line: Int = 0,
    val timestampMillis: Long = System.currentTimeMillis(),
)

/** Result of a page-mutating action (click / type). */
data class BrowserActionResult(val ok: Boolean, val detail: String)

/**
 * Pure, JVM-testable helpers for the verification browser: URL
 * normalisation, JS snippet building and JS-result decoding.
 *
 * Kept free of any Android API so unit tests can cover them without
 * Robolectric. The snippets are written by hand for Android WebView —
 * behaviour is adapted from the tool surface of anomalyco/browse
 * (MIT, https://github.com/anomalyco/browse: navigate / snapshot /
 * click / fill / eval / screenshot / console), which drives a desktop
 * Chromium via an extension and a native relay and therefore has no
 * code reusable inside an Android APK. Nothing is pasted from it.
 */
object BrowserJs {
    /** Matches an explicit scheme, e.g. "https://", "http://", "ftp://". */
    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /**
     * Normalises a user/agent-supplied URL: trims it, keeps it when it
     * already has a scheme, prepends "http://" for localhost loopback
     * (the dev servers the M4 rootfs will run) and "https://" otherwise.
     */
    fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || SCHEME_REGEX.containsMatchIn(trimmed)) return trimmed
        val lower = trimmed.lowercase()
        return if (lower.startsWith("localhost") ||
            lower.startsWith("127.0.0.1") ||
            lower.startsWith("[::1]")
        ) {
            "http://$trimmed"
        } else {
            "https://$trimmed"
        }
    }

    /**
     * Renders [value] as a double-quoted JavaScript string literal,
     * escaping backslashes, quotes and control characters. Use this
     * whenever a selector or typed text is interpolated into a snippet
     * so a quote in the value cannot break out of the literal.
     */
    fun quoteJsString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (ch < ' ') {
                        sb.append("\\u")
                        sb.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Decodes the value [WebView.evaluateJavascript] hands back: string
     * results arrive JSON-encoded (quoted, escapes intact), numbers and
     * booleans arrive bare, and a null/undefined result arrives as the
     * literal "null". Returns null when there is no usable result.
     */
    fun decodeJsStringResult(raw: String?): String? {
        if (raw == null || raw == "null" || raw == "undefined") return null
        if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
            return unescapeJsonString(raw.substring(1, raw.length - 1))
        }
        return raw
    }

    /** Unescapes the body of a JSON string literal (no surrounding quotes). */
    fun unescapeJsonString(body: String): String {
        if (!body.contains('\\')) return body
        val sb = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            if (ch != '\\' || i + 1 >= body.length) {
                sb.append(ch)
                i++
                continue
            }
            when (body[i + 1]) {
                '\\' -> sb.append('\\')
                '"' -> sb.append('"')
                '/' -> sb.append('/')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'u' -> {
                    if (i + 5 < body.length) {
                        val code = body.substring(i + 2, i + 6).toIntOrNull(16)
                        if (code != null) sb.append(code.toChar())
                        i += 4
                    }
                }
                else -> sb.append(body[i + 1])
            }
            i += 2
        }
        return sb.toString()
    }

    /**
     * Installs window.onerror + unhandledrejection handlers that forward
     * to the injected `CodexBridge` (see [VerificationBrowser]).
     * Idempotent per page via a window flag; re-injected on every page
     * load because navigation resets the page's JS world.
     */
    fun buildErrorHookScript(): String = """
        (function () {
          if (window.__codexErrorHookInstalled) return;
          window.__codexErrorHookInstalled = true;
          window.addEventListener("error", function (e) {
            try {
              window.CodexBridge.postError(
                String(e.message || "error"),
                String(e.filename || ""),
                e.lineno || 0,
                e.colno || 0
              );
            } catch (ignored) {}
          });
          window.addEventListener("unhandledrejection", function (e) {
            try {
              var r = e.reason;
              var m = (r && r.message) ? r.message : String(r);
              window.CodexBridge.postError("Unhandled promise rejection: " + m, "", 0, 0);
            } catch (ignored) {}
          });
        })();
    """.trimIndent()

    /**
     * Returns a script that snapshots the page as one JSON string:
     * `{url, title, total, shown, nodes: [{t, i, c, x, h, p, y}]}` where
     * t = tag, i = id, c = class list, x = visible text / input value
     * (max 80 chars), h = link href, p = placeholder, y = input type.
     * Only interactive elements and headings are included so the
     * snapshot stays small enough to feed back to the model; [maxNodes]
     * caps how many are returned (total still reports the full count).
     */
    fun buildDomSnapshotScript(maxNodes: Int): String = """
        (function () {
          var MAX = $maxNodes;
          var out = [];
          var els = document.querySelectorAll(
            'a, button, input, select, textarea, [role="button"], h1, h2, h3, [data-testid]'
          );
          var total = els.length;
          for (var i = 0; i < els.length && out.length < MAX; i++) {
            var el = els[i];
            var tag = el.tagName.toLowerCase();
            var text = "";
            if (tag === "input" || tag === "textarea" || tag === "select") {
              text = String(el.value || el.placeholder || "");
            } else {
              text = (el.textContent || "").replace(/\s+/g, " ").trim();
            }
            if (text.length > 80) text = text.slice(0, 77) + "...";
            var cls = (typeof el.className === "string") ? el.className : "";
            if (cls.length > 60) cls = cls.slice(0, 57) + "...";
            out.push({
              t: tag,
              i: el.id || "",
              c: cls,
              x: text,
              h: tag === "a" ? (el.getAttribute("href") || "") : "",
              p: el.getAttribute("placeholder") || "",
              y: el.getAttribute("type") || ""
            });
          }
          return JSON.stringify({
            url: location.href,
            title: document.title,
            total: total,
            shown: out.length,
            nodes: out
          });
        })();
    """.trimIndent()

    /** Clicks the first element matching [selector]; "OK" / "NOT_FOUND" / "ERROR:...". */
    fun buildClickScript(selector: String): String = """
        (function () {
          try {
            var el = document.querySelector(${quoteJsString(selector)});
            if (!el) return "NOT_FOUND";
            el.scrollIntoView({ block: "center", inline: "center" });
            el.click();
            return "OK";
          } catch (e) {
            return "ERROR:" + (e && e.message ? e.message : e);
          }
        })();
    """.trimIndent()

    /**
     * Types [text] into the first element matching [selector]: focuses
     * it, sets the value through the native setter (so React/Vue
     * controlled inputs notice the change) and dispatches input +
     * change events. Handles input / textarea / select /
     * contenteditable; "OK" / "NOT_FOUND" / "ERROR:...".
     */
    fun buildTypeScript(selector: String, text: String): String {
        val sel = quoteJsString(selector)
        val txt = quoteJsString(text)
        return """
            (function () {
              try {
                var el = document.querySelector($sel);
                if (!el) return "NOT_FOUND";
                el.focus();
                if (el.tagName === "SELECT") {
                  el.value = $txt;
                  el.dispatchEvent(new Event("change", { bubbles: true }));
                  return "OK";
                }
                var proto = null;
                if (el.tagName === "TEXTAREA") proto = HTMLTextAreaElement.prototype;
                else if (el.tagName === "INPUT") proto = HTMLInputElement.prototype;
                if (proto) {
                  var desc = Object.getOwnPropertyDescriptor(proto, "value");
                  if (desc && desc.set) desc.set.call(el, $txt);
                  else el.value = $txt;
                } else if ("value" in el) {
                  el.value = $txt;
                } else if (el.isContentEditable) {
                  el.textContent = $txt;
                } else {
                  return "ERROR:not an editable element";
                }
                el.dispatchEvent(new Event("input", { bubbles: true }));
                el.dispatchEvent(new Event("change", { bubbles: true }));
                return "OK";
              } catch (e) {
                return "ERROR:" + (e && e.message ? e.message : e);
              }
            })();
        """.trimIndent()
    }
}

/**
 * Embedded WebView controlled by the Browser Subagent (master plan
 * Subsystem 6, M7) and inspectable live by the human user in the
 * Browser tab.
 *
 * Owns a single [WebView] and exposes it for the Preview UI
 * ([webView]); the agent drives the same instance through the suspend
 * functions below, so the human and the agent always see the same page.
 *
 * - Console capture: [WebChromeClient.onConsoleMessage] plus an
 *   injected `window.onerror` / `unhandledrejection` hook that reports
 *   through the `CodexBridge` [@JavascriptInterface] (bridge exposes
 *   only error reporting — it cannot read files or run shell commands).
 * - Main-frame HTTP errors (4xx/5xx) are recorded as console entries.
 * - Works against external URLs now; the `http://127.0.0.1:<port>`
 *   dev-server loop arrives with the M4 rootfs.
 *
 * Must be constructed with an application context; the WebView itself
 * is created on the main thread (blocking the calling thread briefly
 * when constructed off it). Call [destroy] when the browser is no
 * longer needed. No API keys, no model calls, no secrets.
 */
class VerificationBrowser(context: Context) {

    /** Application context, used for the WebView and screenshot storage. */
    val appContext: Context = context.applicationContext

    private val _currentUrl = MutableStateFlow("")
    /** URL of the page currently loaded (or being loaded). */
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _pageTitle = MutableStateFlow("")
    /** Title reported by the page, empty until the first page loads. */
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    /** True between page start and page finish. */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadProgress = MutableStateFlow(0)
    /** Load progress 0..100 reported by the page. */
    val loadProgress: StateFlow<Int> = _loadProgress.asStateFlow()

    private val _consoleLogs = MutableStateFlow<List<ConsoleLogEntry>>(emptyList())
    /** Captured console messages + page errors, oldest first, capped. */
    val consoleLogs: StateFlow<List<ConsoleLogEntry>> = _consoleLogs.asStateFlow()

    /** The single WebView this browser owns; shown by PreviewScreen. */
    val webView: WebView = createWebViewOnMainThread()

    init {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                _currentUrl.value = url
                _isLoading.value = true
            }

            override fun onPageFinished(view: WebView, url: String) {
                _currentUrl.value = url
                _isLoading.value = false
                // Navigation resets the page's JS world: re-install the hook.
                view.evaluateJavascript(BrowserJs.buildErrorHookScript(), null)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                // Keep every navigation inside this WebView.
                return false
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    appendConsole(
                        ConsoleLevel.ERROR,
                        "Page error loading ${request.url}: ${error.description}",
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (request.isForMainFrame) {
                    appendConsole(
                        ConsoleLevel.ERROR,
                        "HTTP ${errorResponse.statusCode} loading ${request.url}",
                    )
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val level = when (message.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> ConsoleLevel.ERROR
                    ConsoleMessage.MessageLevel.WARNING -> ConsoleLevel.WARN
                    ConsoleMessage.MessageLevel.LOG -> ConsoleLevel.LOG
                    ConsoleMessage.MessageLevel.DEBUG -> ConsoleLevel.DEBUG
                    ConsoleMessage.MessageLevel.TIP -> ConsoleLevel.INFO
                    else -> ConsoleLevel.LOG
                }
                appendConsole(
                    level,
                    message.message(),
                    message.sourceId() ?: "",
                    message.lineNumber(),
                )
                // True: we handled it (still shown in logcat by the system).
                return true
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                _loadProgress.value = newProgress
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                _pageTitle.value = title ?: ""
            }
        }
    }

    /**
     * Loads [rawUrl] (scheme added when missing — see
     * [BrowserJs.normalizeUrl]) and returns the normalised URL that was
     * actually loaded. Safe to call from any thread; runs on Main.
     */
    /**
     * Releases the WebView. Call from the main thread when the owning
     * Activity is destroyed (or its composition is disposed) — a
     * WebView that is never destroyed leaks its renderer process and
     * memory, including across configuration changes.
     */
    fun destroy() {
        runCatching { webView.destroy() }
    }

    suspend fun loadUrl(rawUrl: String): String = withContext(Dispatchers.Main) {
        val normalized = BrowserJs.normalizeUrl(rawUrl)
        if (normalized.isNotEmpty()) {
            webView.loadUrl(normalized)
        }
        normalized
    }

    /** Reloads the current page. No-op when nothing has been loaded yet. */
    suspend fun reload() = withContext(Dispatchers.Main) {
        if (_currentUrl.value.isNotEmpty()) webView.reload()
    }

    /**
     * Evaluates [js] in the page and returns the result decoded by
     * [BrowserJs.decodeJsStringResult], or null on timeout / failure.
     * Never throws.
     */
    suspend fun eval(js: String, timeoutMs: Long = 10_000L): String? =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String?>()
            try {
                webView.evaluateJavascript(js) { result -> deferred.complete(result) }
            } catch (e: Exception) {
                return@withContext null
            }
            val raw = withTimeoutOrNull(timeoutMs) { deferred.await() }
            BrowserJs.decodeJsStringResult(raw)
        }

    /**
     * Compact DOM snapshot (see [BrowserJs.buildDomSnapshotScript]).
     * Returns the JSON text, or a small `{"error": ...}` object when the
     * page has not loaded or evaluation failed.
     */
    suspend fun domSnapshot(maxNodes: Int = DEFAULT_MAX_NODES): String {
        val raw = eval(BrowserJs.buildDomSnapshotScript(maxNodes.coerceIn(50, 2000)))
        return raw ?: """{"error":"DOM snapshot unavailable (page not loaded?)"}"""
    }

    /** Clicks the first element matching [selector]. Never throws. */
    suspend fun click(selector: String): BrowserActionResult {
        val raw = eval(BrowserJs.buildClickScript(selector)) ?: return BrowserActionResult(
            ok = false,
            detail = "ERROR: click timed out (page not responding)",
        )
        return parseActionResult(raw, "click", selector)
    }

    /** Types [text] into the first element matching [selector]. Never throws. */
    suspend fun type(selector: String, text: String): BrowserActionResult {
        val raw = eval(BrowserJs.buildTypeScript(selector, text)) ?: return BrowserActionResult(
            ok = false,
            detail = "ERROR: type timed out (page not responding)",
        )
        return parseActionResult(raw, "type", selector)
    }

    /**
     * Renders the current viewport to PNG bytes, or null on failure.
     * Must run on Main (the WebView draws on the UI thread). When the
     * WebView has never been laid out (headless agent use, Preview tab
     * never opened) it is measured at a 1080x1920 viewport first; on
     * some devices a never-attached WebView still renders blank until
     * it has been shown once — prefer capturing with the Preview tab
     * visible for visual verification.
     */
    suspend fun screenshot(): ByteArray? = withContext(Dispatchers.Main) {
        try {
            if (webView.width == 0 || webView.height == 0) {
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(DEFAULT_CAPTURE_WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(DEFAULT_CAPTURE_HEIGHT, View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, DEFAULT_CAPTURE_WIDTH, DEFAULT_CAPTURE_HEIGHT)
            }
            val bitmap = Bitmap.createBitmap(
                webView.width,
                webView.height,
                Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Formats captured console entries as one line each:
     * `[LEVEL] message (source:line)`. Filters by [level] when given
     * and returns at most the last [limit] entries.
     */
    fun formatConsoleLogs(level: ConsoleLevel? = null, limit: Int = 100): String {
        val entries = _consoleLogs.value
            .filter { level == null || it.level == level }
            .takeLast(limit.coerceIn(1, MAX_CONSOLE_ENTRIES))
        if (entries.isEmpty()) return "No console entries yet."
        return entries.joinToString("\n") { entry ->
            val location = if (entry.source.isNotBlank()) " (${entry.source}:${entry.line})" else ""
            "[${entry.level}] ${entry.message}$location"
        }
    }

    /** Number of entries currently captured (after capping). */
    fun consoleLogCount(): Int = _consoleLogs.value.size

    /** Clears all captured console entries. */
    fun clearConsole() {
        _consoleLogs.value = emptyList()
    }

    /** Stops loading, detaches the JS bridge and destroys the WebView. */
    suspend fun destroy() = withContext(Dispatchers.Main) {
        try {
            webView.stopLoading()
            webView.removeJavascriptInterface(BRIDGE_NAME)
            webView.destroy()
        } catch (e: Exception) {
            // Destroy is best-effort during teardown.
        }
    }

    private fun parseActionResult(
        raw: String,
        action: String,
        selector: String,
    ): BrowserActionResult = when {
        raw == "OK" -> BrowserActionResult(ok = true, detail = "OK")
        raw == "NOT_FOUND" -> BrowserActionResult(
            ok = false,
            detail = "No element matches selector: $selector",
        )
        raw.startsWith("ERROR:") -> BrowserActionResult(ok = false, detail = raw)
        else -> BrowserActionResult(
            ok = false,
            detail = "ERROR: unexpected $action result: $raw",
        )
    }

    private fun appendConsole(
        level: ConsoleLevel,
        message: String,
        source: String = "",
        line: Int = 0,
    ) {
        val entry = ConsoleLogEntry(level, message, source, line)
        _consoleLogs.update { existing -> (existing + entry).takeLast(MAX_CONSOLE_ENTRIES) }
    }

    private fun createWebViewOnMainThread(): WebView {
        if (Looper.myLooper() == Looper.getMainLooper()) return createWebView()
        val latch = CountDownLatch(1)
        var created: WebView? = null
        Handler(Looper.getMainLooper()).post {
            try {
                created = createWebView()
            } finally {
                latch.countDown()
            }
        }
        val done = latch.await(WEBVIEW_CREATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return created ?: throw IllegalStateException(
            if (done) "WebView creation failed" else "WebView creation timed out",
        )
    }

    private fun createWebView(): WebView {
        val view = WebView(appContext)
        view.addJavascriptInterface(CodexBridge(), BRIDGE_NAME)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = false
            // The browser verifies the agent's own pages and public URLs;
            // never let a page reach app-private files.
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        return view
    }

    /**
     * JS bridge: exposes ONLY error reporting to pages. Called on a
     * WebView background thread; StateFlow updates are thread-safe.
     */
    private inner class CodexBridge {
        @JavascriptInterface
        fun postError(message: String, source: String, line: Int, col: Int) {
            val detail = buildString {
                append("Uncaught error: ").append(message)
                if (source.isNotBlank()) {
                    append(" (").append(source).append(":").append(line)
                        .append(":").append(col).append(")")
                }
            }
            appendConsole(ConsoleLevel.ERROR, detail, source, line)
        }
    }

    companion object {
        /** JS bridge name; must match the name used in the error hook. */
        const val BRIDGE_NAME = "CodexBridge"

        /** Default cap for one DOM snapshot. */
        const val DEFAULT_MAX_NODES = 400

        /** Console entries kept in memory; older entries drop off. */
        const val MAX_CONSOLE_ENTRIES = 500

        /** Viewport used for a headless screenshot before first layout. */
        const val DEFAULT_CAPTURE_WIDTH = 1080
        const val DEFAULT_CAPTURE_HEIGHT = 1920

        private const val WEBVIEW_CREATE_TIMEOUT_MS = 10_000L
    }
}
