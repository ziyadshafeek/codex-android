package dev.codex.android.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for [OkHttpOpenAiClient.normaliseBaseUrl]: providers
 * show the full endpoint URL, and a pasted `/chat/completions` suffix
 * must not be doubled onto the request path.
 */
class OpenAiClientTest {

    @Test
    fun plainBaseUrl_isUnchanged() {
        assertEquals(
            "https://api.openai.com/v1",
            OkHttpOpenAiClient.normaliseBaseUrl("https://api.openai.com/v1"),
        )
    }

    @Test
    fun trailingSlash_isTrimmed() {
        assertEquals(
            "https://api.openai.com/v1",
            OkHttpOpenAiClient.normaliseBaseUrl("https://api.openai.com/v1/"),
        )
    }

    @Test
    fun fullEndpointSuffix_isStripped() {
        assertEquals(
            "https://api.openai.com/v1",
            OkHttpOpenAiClient.normaliseBaseUrl("https://api.openai.com/v1/chat/completions"),
        )
        assertEquals(
            "https://api.openai.com/v1",
            OkHttpOpenAiClient.normaliseBaseUrl("https://api.openai.com/v1/chat/completions/"),
        )
    }

    @Test
    fun whitespace_isTrimmed() {
        assertEquals(
            "https://example.com/v1",
            OkHttpOpenAiClient.normaliseBaseUrl("  https://example.com/v1/chat/completions  "),
        )
    }
}
