package dev.codex.android.api

import dev.codex.android.core.CodexJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Text-only messages keep the existing wire shape. Vision input uses OpenAI content parts. */
internal fun chatMessagesWire(messages: List<ChatMessage>): JsonArray = JsonArray(messages.map { message ->
    val base = CodexJson.encodeToJsonElement(ChatMessage.serializer(), message).jsonObject
    if (message.imageUrls.isEmpty()) base else {
        val parts = buildList {
            message.content?.let { add(buildJsonObject { put("type", "text"); put("text", it) }) }
            message.imageUrls.forEach { url -> add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", url) })
            }) }
        }
        JsonObject(base + ("content" to JsonArray(parts)))
    }
})
