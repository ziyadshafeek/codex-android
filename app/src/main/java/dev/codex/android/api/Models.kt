package dev.codex.android.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/**
 * A single chat message in an OpenAI-compatible conversation.
 *
 * @property role "system" | "user" | "assistant" | "tool"
 * @property content the message text (null for a pure tool-call message)
 * @property toolCalls function calls requested by the assistant
 * @property toolCallId the id of the tool call this message answers (role "tool" only)
 * @property name optional tool name for a "tool" message
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null,
    @Transient val imageUrls: List<String> = emptyList(),
)

/** One function call requested by the assistant. */
@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String = "{}",
)

/** A tool call: the id plus the function to invoke. */
@Serializable
data class ToolCall(
    val id: String = "",
    val type: String = "function",
    val function: FunctionCall,
)

/** The JSON Schema description of one function, sent to the model. */
@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/** One tool the model may call, in OpenAI function-calling shape. */
@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition,
)

/** The assistant's reply to one chat request: text and/or tool calls. */
data class ChatChoice(
    val text: String?,
    val toolCalls: List<ToolCall>,
    val finishReason: String?,
)

/** What a single chat-completions call returns (non-streaming view). */
data class ChatResult(
    val choices: List<ChatChoice>,
    val usagePromptTokens: Int? = null,
    val usageCompletionTokens: Int? = null,
)
