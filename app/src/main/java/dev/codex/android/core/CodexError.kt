package dev.codex.android.core

sealed class CodexError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable? = null) : CodexError("Network error — check your connection", cause)
    class Auth(detail: String = "Authentication failed") : CodexError("Auth failed: $detail")
    class RateLimit(val retryAfterSeconds: Long? = null) : CodexError("Rate limited" + if (retryAfterSeconds != null) " — retry after ${retryAfterSeconds}s" else " — try again shortly")
    class Timeout : CodexError("Request timed out")
    class MalformedToolCall(detail: String) : CodexError("Malformed tool call: $detail")
    class ToolFailed(val toolName: String, detail: String) : CodexError("Tool $toolName failed: $detail")
    class Cancelled : CodexError("Cancelled")
    class Unknown(detail: String, cause: Throwable? = null) : CodexError("Unexpected error: $detail", cause)
}
