package dev.codex.android.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress

/**
 * Raw-body fetcher (`name = "http-body"`).
 *
 * Unlike [WebFetchTool] — which strips HTML to plain text and caps output
 * at 200 KB — this tool returns the response body exactly as received, so
 * the agent can read JSON, XML, CSV, Markdown, or raw HTML.
 *
 * Safety is not re-implemented here: every URL (including redirect hops)
 * is validated by [WebFetchTool.validateSafeTarget] and fetched through
 * [WebFetchTool]'s pinned-DNS client, so the same SSRF rules apply
 * (private/loopback/link-local IPs, metadata hostnames, max 3 redirects,
 * http/https only). The body is capped at [MAX_BODY_BYTES] (1 MB) with a
 * truncation note; non-text content types are refused with the content
 * type named in the error.
 */
class HttpBodyTool internal constructor(
    httpClient: OkHttpClient,
    private val dnsResolver: (String) -> List<InetAddress>,
) : Tool {

    constructor(
        httpClient: OkHttpClient = WebFetchTool.defaultHttpClient(),
    ) : this(
        httpClient = httpClient,
        dnsResolver = { host -> InetAddress.getAllByName(host).toList() },
    )

    /** Shared validator + pinned-DNS client from [WebFetchTool]. */
    private val fetcher = WebFetchTool(httpClient, dnsResolver)

    override val name: String = "http-body"
    override val description: String =
        "Fetches an HTTP/HTTPS URL and returns the FULL raw response body as-is " +
            "(JSON, XML, CSV, Markdown, or raw HTML), up to 1 MB. SSRF-safe: refuses " +
            "private, loopback, link-local, and cloud metadata addresses."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") {
                put("type", "string")
                put("description", "Public http:// or https:// URL to fetch")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("url"))
        }
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val rawUrl = args["url"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return@withContext ToolResult(ok = false, output = "", error = "Missing required argument: url")

        val initialScheme = rawUrl.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (initialScheme != "http" && initialScheme != "https") {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = "Refused URL scheme: only http and https are allowed",
            )
        }

        var currentUrl: HttpUrl = rawUrl.toHttpUrlOrNull()
            ?: return@withContext ToolResult(ok = false, output = "", error = "Invalid URL: $rawUrl")

        for (hop in 0..WebFetchTool.MAX_REDIRECTS) {
            val ssrfError = fetcher.validateSafeTarget(currentUrl)
            if (ssrfError != null) {
                return@withContext ToolResult(ok = false, output = "", error = ssrfError)
            }

            val request = Request.Builder()
                .url(currentUrl)
                .get()
                .header("Accept", "*/*")
                .header("User-Agent", "CodexAndroid/1.0")
                .build()

            val response = try {
                fetcher.client.newCall(request).execute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return@withContext ToolResult(
                    ok = false,
                    output = "",
                    error = "Fetch failed: ${e.message ?: e.javaClass.simpleName}",
                )
            }

            response.use { resp ->
                if (resp.code in REDIRECT_CODES) {
                    if (hop >= WebFetchTool.MAX_REDIRECTS) {
                        return@withContext ToolResult(
                            ok = false,
                            output = "",
                            error = "Too many redirects (max ${WebFetchTool.MAX_REDIRECTS})",
                        )
                    }
                    val location = resp.header("Location")?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return@withContext ToolResult(
                            ok = false,
                            output = "",
                            error = "Redirect (HTTP ${resp.code}) missing Location header",
                        )
                    val targetScheme = location.substringBefore("://", missingDelimiterValue = "")
                        .lowercase()
                    if (targetScheme.isNotEmpty() && targetScheme != "http" && targetScheme != "https") {
                        return@withContext ToolResult(
                            ok = false,
                            output = "",
                            error = "Refused redirect to non-http/https scheme: $targetScheme",
                        )
                    }
                    currentUrl = currentUrl.resolve(location)
                        ?: return@withContext ToolResult(
                            ok = false,
                            output = "",
                            error = "Invalid redirect Location: $location",
                        )
                    return@use
                }

                if (!resp.isSuccessful) {
                    return@withContext ToolResult(
                        ok = false,
                        output = "",
                        error = "HTTP ${resp.code} fetching $currentUrl",
                    )
                }

                val contentType = resp.header("Content-Type").orEmpty()
                if (!WebFetchTool.isAllowedTextContentType(contentType)) {
                    return@withContext ToolResult(
                        ok = false,
                        output = "",
                        error = "Refused non-text content type: ${contentType.ifBlank { "unknown" }}",
                    )
                }

                val body = resp.body
                    ?: return@withContext ToolResult(ok = true, output = "")
                val (rawBytes, truncated) = readBoundedBytes(body.byteStream(), MAX_BODY_BYTES)
                val text = String(rawBytes, Charsets.UTF_8)
                val finalOutput = if (truncated) {
                    text + "\n\n[truncated: response body capped at $MAX_BODY_BYTES bytes]"
                } else {
                    text
                }
                return@withContext ToolResult(ok = true, output = finalOutput)
            }
        }

        ToolResult(ok = false, output = "", error = "Too many redirects (max ${WebFetchTool.MAX_REDIRECTS})")
    }

    companion object {
        const val MAX_BODY_BYTES: Int = 1024 * 1024
        private val REDIRECT_CODES: Set<Int> = setOf(301, 302, 303, 307, 308)

        /**
         * Reads at most [maxBytes] bytes; also reports whether more bytes
         * remained unread (one extra probe byte), so the caller can note
         * truncation instead of silently cutting the body.
         */
        internal fun readBoundedBytes(stream: InputStream, maxBytes: Int): Pair<ByteArray, Boolean> {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var remaining = maxBytes
            while (remaining > 0) {
                val read = stream.read(buf, 0, minOf(buf.size, remaining))
                if (read == -1) return out.toByteArray() to false
                out.write(buf, 0, read)
                remaining -= read
            }
            val probe = stream.read()
            return out.toByteArray() to (probe != -1)
        }
    }
}
