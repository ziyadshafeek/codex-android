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
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * SSRF-safe web fetcher (`name = "webfetch"`).
 *
 * Security invariants:
 * - Only `http` and `https` URLs are permitted.
 * - Refuses `localhost` / `.localhost` / `.local` / `.internal` hostnames,
 *   cloud metadata hosts (`169.254.169.254`, `metadata.google.internal`,
 *   `fd00:ec2::254`, `100.100.100.200`), loopback (`127.0.0.0/8`, `::1`),
 *   private RFC 1918 (`10/8`, `172.16/12`, `192.168/16`), link-local
 *   (`169.254/16`, `fe80::/10`), CGNAT (`100.64/10`), ULA (`fc00::/7`),
 *   multicast, unspecified (`0.0.0.0`, `::`), and IPv4-mapped IPv6 equivalents.
 * - Automatic redirects are disabled; at most [MAX_REDIRECTS] (3) redirects
 *   are followed manually, re-running the full SSRF validation on every hop.
 * - 10-second timeout; refuses non-text content types; strips HTML to plain
 *   text and caps output at [MAX_OUTPUT_BYTES] (200 KB).
 */
class WebFetchTool internal constructor(
    httpClient: OkHttpClient,
    private val dnsResolver: (String) -> List<InetAddress>,
) : Tool {

    constructor(
        httpClient: OkHttpClient = defaultHttpClient(),
    ) : this(
        httpClient = httpClient,
        dnsResolver = { host -> InetAddress.getAllByName(host).toList() },
    )

    /**
     * Pinned DNS: every OkHttp connection resolves through the SAME injected
     * resolver the SSRF validator uses, and the lookup itself refuses any
     * blocked address. Without this, validation and the connection resolve
     * independently (platform DNS vs [dnsResolver]) and a rebinding answer
     * can return public to the validator and private to OkHttp (DNS TOCTOU).
     * With the pin, an address OkHttp connects to was checked in the same
     * lookup that produced it — a lookup that returns a blocked address
     * throws instead of connecting. Literal-IP hosts never reach DNS at all
     * (OkHttp uses the parsed address directly); those are refused by
     * [validateSafeTarget] before any call is made.
     */
    internal val pinnedDns: Dns = Dns { host ->
        val resolved = dnsResolver(host)
        for (addr in resolved) {
            if (isBlockedInetAddress(addr)) {
                throw UnknownHostException(
                    "SSRF protection: host '$host' resolved to disallowed address ${addr.hostAddress}",
                )
            }
        }
        resolved
    }

    internal val client: OkHttpClient = httpClient.newBuilder()
        .dns(pinnedDns)
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override val name: String = "webfetch"
    override val description: String =
        "Fetches an HTTP/HTTPS URL and returns its text content (HTML stripped to plain text, max 200 KB). " +
            "Refuses private, loopback, link-local, and cloud metadata addresses."
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

        for (hop in 0..MAX_REDIRECTS) {
            val ssrfError = validateSafeTarget(currentUrl)
            if (ssrfError != null) {
                return@withContext ToolResult(ok = false, output = "", error = ssrfError)
            }

            val request = Request.Builder()
                .url(currentUrl)
                .get()
                .header(
                    "Accept",
                    "text/html, text/plain, text/markdown, application/json, application/xml;q=0.9",
                )
                .header("User-Agent", "CodexAndroid/1.0")
                .build()

            val response = try {
                client.newCall(request).execute()
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
                    if (hop >= MAX_REDIRECTS) {
                        return@withContext ToolResult(
                            ok = false,
                            output = "",
                            error = "Too many redirects (max $MAX_REDIRECTS)",
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
                if (!isAllowedTextContentType(contentType)) {
                    return@withContext ToolResult(
                        ok = false,
                        output = "",
                        error = "Refused non-text content type: ${contentType.ifBlank { "unknown" }}",
                    )
                }

                val body = resp.body
                    ?: return@withContext ToolResult(ok = true, output = "")
                val rawBytes = readBoundedBytes(body.byteStream(), MAX_DOWNLOAD_BYTES)
                val rawText = rawBytes.toString(Charsets.UTF_8)
                val processed = if (isHtmlContentType(contentType)) {
                    stripHtmlToText(rawText)
                } else {
                    rawText
                }
                val finalOutput = truncateUtf8Bytes(processed, MAX_OUTPUT_BYTES)
                return@withContext ToolResult(ok = true, output = finalOutput)
            }
        }

        ToolResult(ok = false, output = "", error = "Too many redirects (max $MAX_REDIRECTS)")
    }

    /**
     * Returns an error description when [url] violates SSRF policy, or null
     * when the target is safe to contact.
     */
    internal fun validateSafeTarget(url: HttpUrl): String? {
        val scheme = url.scheme.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "Refused URL scheme '$scheme': only http and https are allowed"
        }

        val normalizedHost = url.host.trim().trimEnd('.').removeSurrounding("[", "]").lowercase()
        if (normalizedHost.isEmpty()) {
            return "Invalid empty host"
        }

        if (isBlockedHostname(normalizedHost)) {
            return "SSRF protection: refused blocked host '$normalizedHost'"
        }

        // Literal IP check before DNS lookup.
        parseLiteralIpBytes(normalizedHost)?.let { literalBytes ->
            if (isBlockedIpBytes(literalBytes)) {
                return "SSRF protection: refused private/reserved IP '$normalizedHost'"
            }
        }

        val resolved = try {
            dnsResolver(normalizedHost)
        } catch (_: UnknownHostException) {
            return "Could not resolve host '$normalizedHost'"
        } catch (e: Exception) {
            return "DNS resolution failed for '$normalizedHost': ${e.message}"
        }

        if (resolved.isEmpty()) {
            return "Could not resolve host '$normalizedHost'"
        }

        for (addr in resolved) {
            if (isBlockedInetAddress(addr)) {
                return "SSRF protection: host '$normalizedHost' resolved to disallowed address ${addr.hostAddress}"
            }
        }
        return null
    }

    companion object {
        const val TIMEOUT_SECONDS: Long = 10L
        const val MAX_REDIRECTS: Int = 3
        const val MAX_OUTPUT_BYTES: Int = 200 * 1024
        private const val MAX_DOWNLOAD_BYTES: Int = 1024 * 1024
        private val REDIRECT_CODES: Set<Int> = setOf(301, 302, 303, 307, 308)

        private val BLOCKED_HOSTNAMES: Set<String> = setOf(
            "localhost",
            "localhost.localdomain",
            "ip6-localhost",
            "ip6-loopback",
            "0.0.0.0",
            "metadata",
            "metadata.google.internal",
            "metadata.goog",
            "instance-data",
            "169.254.169.254",
            "100.100.100.200",
            "fd00:ec2::254",
        )

        private val SCRIPT_STYLE_COMMENT_REGEX = Regex(
            """(?is)<(script|style|noscript)\b[^>]*>.*?</\1>|<!--.*?-->""",
        )
        private val BLOCK_TAG_REGEX = Regex(
            """(?i)</?(p|div|br|hr|li|ul|ol|tr|td|th|table|h[1-6]|section|article|header|footer|blockquote|pre)\b[^>]*>""",
        )
        private val ANY_TAG_REGEX = Regex("""(?s)<[^>]+>""")
        private val NUMERIC_ENTITY_DEC_REGEX = Regex("""&#(\d{1,7});""")
        private val NUMERIC_ENTITY_HEX_REGEX = Regex("""(?i)&#x([0-9a-f]{1,6});""")
        private val HORIZONTAL_SPACE_REGEX = Regex("""[ \t\x0B\f\r]+""")
        private val MULTIPLE_NEWLINES_REGEX = Regex("""\n{3,}""")

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        internal fun isBlockedHostname(host: String): Boolean {
            if (host in BLOCKED_HOSTNAMES) return true
            if (host.endsWith(".localhost") ||
                host.endsWith(".localdomain") ||
                host.endsWith(".local") ||
                host.endsWith(".internal")
            ) {
                return true
            }
            return false
        }

        internal fun isBlockedInetAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                address.isMulticastAddress
            ) {
                return true
            }
            return isBlockedIpBytes(address.address)
        }

        internal fun isBlockedIpBytes(bytes: ByteArray): Boolean {
            if (bytes.size == 4) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                val b2 = bytes[2].toInt() and 0xFF
                return when {
                    b0 == 0 -> true // 0.0.0.0/8
                    b0 == 10 -> true // 10.0.0.0/8 RFC 1918
                    b0 == 100 && (b1 in 64..127) -> true // 100.64.0.0/10 CGNAT + 100.100.100.200 metadata
                    b0 == 127 -> true // 127.0.0.0/8 loopback
                    b0 == 169 && b1 == 254 -> true // 169.254.0.0/16 link-local + 169.254.169.254 metadata
                    b0 == 172 && (b1 in 16..31) -> true // 172.16.0.0/12 RFC 1918
                    b0 == 192 && b1 == 168 -> true // 192.168.0.0/16 RFC 1918
                    b0 == 192 && b1 == 0 && b2 == 0 -> true // 192.0.0.0/24 IETF protocol
                    b0 >= 224 -> true // Multicast 224/4 + Reserved 240/4 + Broadcast
                    else -> false
                }
            }
            if (bytes.size == 16) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                // All zeros (::) or loopback (::1).
                val first15Zero = (0 until 15).all { bytes[it].toInt() == 0 }
                val lastByte = bytes[15].toInt() and 0xFF
                if (first15Zero && (lastByte == 0 || lastByte == 1)) return true

                // Unique local fc00::/7 (includes AWS IMDSv2 fd00:ec2::254).
                if ((b0 and 0xFE) == 0xFC) return true
                // Link-local fe80::/10.
                if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true
                // Multicast ff00::/8.
                if (b0 == 0xFF) return true

                // IPv4-mapped IPv6 ::ffff:a.b.c.d or IPv4-compatible ::a.b.c.d.
                val first10Zero = (0 until 10).all { bytes[it].toInt() == 0 }
                val isMapped = first10Zero &&
                    (bytes[10].toInt() and 0xFF) == 0xFF &&
                    (bytes[11].toInt() and 0xFF) == 0xFF
                val isCompat = first10Zero &&
                    bytes[10].toInt() == 0 &&
                    bytes[11].toInt() == 0 &&
                    !(bytes[12].toInt() == 0 && bytes[13].toInt() == 0 && bytes[14].toInt() == 0)
                if (isMapped || isCompat) {
                    return isBlockedIpBytes(bytes.copyOfRange(12, 16))
                }
                // 6to4 2002::/16 embeds IPv4 in bytes 2..5.
                if (b0 == 0x20 && b1 == 0x02) {
                    return isBlockedIpBytes(bytes.copyOfRange(2, 6))
                }
            }
            return false
        }

        /** Parses dotted-quad IPv4 or colon-hex IPv6 literals without triggering DNS. */
        private fun parseLiteralIpBytes(host: String): ByteArray? {
            val ipv4Parts = host.split('.')
            if (ipv4Parts.size == 4 && ipv4Parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
                val out = ByteArray(4)
                for (i in 0 until 4) {
                    val v = ipv4Parts[i].toIntOrNull() ?: return null
                    if (v !in 0..255) return null
                    out[i] = v.toByte()
                }
                return out
            }
            if (host.contains(':')) {
                return runCatching { InetAddress.getByName(host).address }.getOrNull()
            }
            return null
        }

        internal fun isAllowedTextContentType(rawContentType: String?): Boolean {
            val mediaType = rawContentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (mediaType.isEmpty()) return false
            if (mediaType.startsWith("text/")) return true
            if (mediaType.endsWith("+json") || mediaType.endsWith("+xml")) return true
            return mediaType in setOf(
                "application/json",
                "application/xml",
                "application/xhtml+xml",
                "application/javascript",
                "application/x-javascript",
            )
        }

        private fun isHtmlContentType(rawContentType: String?): Boolean {
            val mediaType = rawContentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            return mediaType == "text/html" || mediaType == "application/xhtml+xml"
        }

        internal fun stripHtmlToText(html: String): String {
            var s = SCRIPT_STYLE_COMMENT_REGEX.replace(html, " ")
            s = BLOCK_TAG_REGEX.replace(s, "\n")
            s = ANY_TAG_REGEX.replace(s, " ")
            s = decodeHtmlEntities(s)
            val cleanedLines = s.lines().map { line ->
                HORIZONTAL_SPACE_REGEX.replace(line, " ").trim()
            }
            return MULTIPLE_NEWLINES_REGEX.replace(cleanedLines.joinToString("\n"), "\n\n").trim()
        }

        private fun decodeHtmlEntities(input: String): String {
            if (!input.contains('&')) return input
            var out = input
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
            out = NUMERIC_ENTITY_DEC_REGEX.replace(out) { match ->
                val code = match.groupValues[1].toIntOrNull()
                if (code != null && code in 32..0x10FFFF) String(Character.toChars(code)) else match.value
            }
            out = NUMERIC_ENTITY_HEX_REGEX.replace(out) { match ->
                val code = match.groupValues[1].toIntOrNull(16)
                if (code != null && code in 32..0x10FFFF) String(Character.toChars(code)) else match.value
            }
            return out.replace("&amp;", "&")
        }

        private fun readBoundedBytes(stream: java.io.InputStream, maxBytes: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var remaining = maxBytes
            while (remaining > 0) {
                val read = stream.read(buf, 0, minOf(buf.size, remaining))
                if (read == -1) break
                out.write(buf, 0, read)
                remaining -= read
            }
            return out.toByteArray()
        }

        internal fun truncateUtf8Bytes(text: String, maxBytes: Int): String {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return text
            // Step back across any incomplete trailing UTF-8 continuation bytes.
            var cut = maxBytes
            while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) {
                cut--
            }
            return String(bytes, 0, cut, Charsets.UTF_8)
        }
    }
}
