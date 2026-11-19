package dev.codex.android.security

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for [SecretScrubber]: one test per secret pattern plus
 * clean-text pass-through (including the public UPI id and normal URLs).
 */
class SecretScrubberTest {

    @Test
    fun scrub_redactsSkKeys_keepsSurroundingText() {
        val input = "Using key sk-proj-AbCdEf1234567890xyz and sk-ant-api03-1234567890abcdef for call."
        assertEquals(
            "Using key [REDACTED] and [REDACTED] for call.",
            SecretScrubber.scrub(input),
        )
    }

    @Test
    fun scrub_redactsGitHubTokens() {
        val input = "PATs: ghp_1234567890abcdefghijklmnop and github_pat_11ABCDEF0123456789_abcdefghijklmnop!"
        assertEquals(
            "PATs: [REDACTED] and [REDACTED]!",
            SecretScrubber.scrub(input),
        )
    }

    @Test
    fun scrub_redactsBearerTokens() {
        val input = "Token sent: Bearer eyJhbGciOiJIUzI1NiJ9.payload123.signature456 ok"
        assertEquals(
            "Token sent: Bearer [REDACTED] ok",
            SecretScrubber.scrub(input),
        )
    }

    @Test
    fun scrub_redactsApiKeyAndKeyQueryValues() {
        val input = "GET https://api.example.com/v1/search?q=kotlin&api_key=super_secret_123&page=1 " +
            "and https://api.example.com/v2/items?key=another_secret_456&limit=5"
        assertEquals(
            "GET https://api.example.com/v1/search?q=kotlin&api_key=[REDACTED]&page=1 " +
                "and https://api.example.com/v2/items?key=[REDACTED]&limit=5",
            SecretScrubber.scrub(input),
        )
    }

    @Test
    fun scrub_redactsPemPrivateKeyBlocks() {
        val input = """
            Before key
            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEAtestlineone1234567890
            MIIEowIBAAKCAQEAtestlinetwo1234567890
            -----END RSA PRIVATE KEY-----
            After key
        """.trimIndent()
        val expected = """
            Before key
            [REDACTED]
            After key
        """.trimIndent()
        assertEquals(expected, SecretScrubber.scrub(input))
    }

    @Test
    fun scrub_redactsLongHexAndBase64TokensAfterKeyTokenSecret() {
        val hex32 = "0123456789abcdef0123456789abcdef"
        val b64 = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXowMTIzNDU2Nzg5=="
        val input = "webhook_secret=$hex32; session_token: $b64; signing_key: $hex32"
        assertEquals(
            "webhook_secret=[REDACTED]; session_token: [REDACTED]; signing_key: [REDACTED]",
            SecretScrubber.scrub(input),
        )
    }

    @Test
    fun scrub_redactsAuthorizationHeaders() {
        val input = "Authorization: Bearer my_jwt_token_999\n" +
            "Authorization: Basic dXNlcjpwYXNz\n" +
            "Authorization: rawHeaderSecretValue123\n" +
            "Host: api.example.com"
        val expected = "Authorization: Bearer [REDACTED]\n" +
            "Authorization: Basic [REDACTED]\n" +
            "Authorization: [REDACTED]\n" +
            "Host: api.example.com"
        assertEquals(expected, SecretScrubber.scrub(input))
    }

    @Test
    fun scrub_leavesCleanTextUpiIdAndUrlsUnchanged() {
        val clean = "Support Codex via UPI at ziyad.shafeek@oksbi or " +
            "upi://pay?pa=ziyad.shafeek@oksbi&pn=Ziyad&am=100&cu=INR&tn=Support%20Codex. " +
            "Repo: https://github.com/ziyadshafeek/codex-android/commit/34842d071be543918686584e27bac39985a5c040 " +
            "(task-implementation monkey=banana primary_key=id)."
        assertEquals(clean, SecretScrubber.scrub(clean))
        // Also verify idempotency on already-scrubbed text.
        val scrubbed = SecretScrubber.scrub("Authorization: Bearer top_secret_value")
        assertEquals(scrubbed, SecretScrubber.scrub(scrubbed))
    }
}
