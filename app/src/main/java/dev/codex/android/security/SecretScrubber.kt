package dev.codex.android.security

/**
 * Redacts credentials, tokens, and private keys from logs, tool output, and
 * diagnostics by replacing the secret value with `"[REDACTED]"` while keeping
 * surrounding context intact.
 *
 * Deliberately leaves public identifiers (such as the support UPI id
 * `ziyad.shafeek@oksbi`), normal URLs, and ordinary prose untouched.
 */
object SecretScrubber {

    const val REDACTED: String = "[REDACTED]"

    // 1. Multi-line PEM private key blocks.
    private val PEM_PRIVATE_KEY_REGEX = Regex(
        """-----BEGIN [A-Z0-9 ]*PRIVATE KEY(?: BLOCK)?-----[\s\S]*?-----END [A-Z0-9 ]*PRIVATE KEY(?: BLOCK)?-----""",
    )

    // 2. Bearer tokens (standalone or inside Authorization headers).
    private val BEARER_TOKEN_REGEX = Regex(
        """(?i)\b(Bearer\s+)(?!\[REDACTED\])([A-Za-z0-9\-._~+/=]{6,})""",
    )

    // 3. Authorization headers with an explicit scheme (Basic, Token, Digest, etc.).
    private val AUTH_HEADER_SCHEME_REGEX = Regex(
        """(?i)(\bAuthorization["']?\s*[:=]\s*["']?(?:Basic|Token|Digest|ApiKey|Key|Bot|OAuth)\s+)(?!\[REDACTED\])([A-Za-z0-9\-._~+/=]{4,})""",
    )

    // 4. Authorization headers with a raw credential value (no scheme prefix).
    private val AUTH_HEADER_RAW_REGEX = Regex(
        """(?i)(\bAuthorization["']?\s*[:=]\s*["']?)(?!(?:Bearer|Basic|Token|Digest|ApiKey|Key|Bot|OAuth)\b|\[REDACTED\])([A-Za-z0-9\-._~+/=]{4,})""",
    )

    // 5. OpenAI / Anthropic / Stripe-style sk-... keys.
    private val SK_KEY_REGEX = Regex(
        """(?<![A-Za-z0-9_-])sk-(?:proj-|ant-[A-Za-z0-9_-]*|live-|test-)?[A-Za-z0-9_-]{12,}""",
    )

    // 6. GitHub personal access tokens (classic ghp_/gho_/ghu_/ghs_/ghr_ and fine-grained github_pat_).
    private val GITHUB_TOKEN_REGEX = Regex(
        """(?<![A-Za-z0-9_])(?:gh[pousr]_[A-Za-z0-9]{12,}|github_pat_[A-Za-z0-9_]{16,})""",
    )

    // 7. Query-string and assignment values for api_key= / apikey= / key=.
    private val QUERY_KEY_PARAM_REGEX = Regex(
        """(?i)(?<![A-Za-z0-9_-])((?:x-api-key|api[_-]?key|apikey|key)\s*=\s*)(?!\[REDACTED\])([^\s&"'`,;}\]]+)""",
    )

    // 8. Long hex/base64-like tokens (32+ chars) following key / token / secret labels.
    private val LABELED_LONG_SECRET_REGEX = Regex(
        """(?i)(\b[A-Za-z0-9_.-]*(?:key|token|secret)[A-Za-z0-9_.-]*["']?\s*(?:[:=]\s*|\s+is\s+|\s+)["']?)(?!https?://|/)(?!\[REDACTED\])([A-Za-z0-9+/_-]{32,}={0,2})""",
    )

    /** Returns [text] with all detected secret values replaced by `[REDACTED]`. */
    fun scrub(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        result = PEM_PRIVATE_KEY_REGEX.replace(result, REDACTED)
        result = BEARER_TOKEN_REGEX.replace(result, "$1$REDACTED")
        result = AUTH_HEADER_SCHEME_REGEX.replace(result, "$1$REDACTED")
        result = AUTH_HEADER_RAW_REGEX.replace(result, "$1$REDACTED")
        result = SK_KEY_REGEX.replace(result, REDACTED)
        result = GITHUB_TOKEN_REGEX.replace(result, REDACTED)
        result = QUERY_KEY_PARAM_REGEX.replace(result, "$1$REDACTED")
        result = LABELED_LONG_SECRET_REGEX.replace(result, "$1$REDACTED")
        return result
    }
}
