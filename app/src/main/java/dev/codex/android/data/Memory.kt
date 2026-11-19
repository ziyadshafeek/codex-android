package dev.codex.android.data

/**
 * One stored chat message, as read back from the `messages` table.
 *
 * @param role "user" | "assistant" | "system".
 * @param createdAtMillis wall-clock time the message was stored
 *   ([System.currentTimeMillis]).
 */
data class StoredMessage(
    val id: Long,
    val role: String,
    val content: String,
    val createdAtMillis: Long,
)

/**
 * One durable fact, as read back from the `facts` table.
 *
 * Facts are keyed by [key] (unique) and upserted by [MemoryStore.saveFact].
 * [source] records where the fact came from (e.g. "user").
 */
data class Fact(
    val id: Long,
    val key: String,
    val value: String,
    val source: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
