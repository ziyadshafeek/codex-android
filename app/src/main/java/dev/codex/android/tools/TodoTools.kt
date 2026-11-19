package dev.codex.android.tools

import android.content.Context
import dev.codex.android.core.CodexJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One item in a per-thread todo list. */
@Serializable
data class TodoItem(
    val content: String,
    val status: String,
)

/**
 * Per-thread todo storage kept in memory and persisted as one JSON file per
 * thread under [storageDir] (defaults to `<filesDir>/todos/`).
 */
class TodoStore(storageDir: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, DIR_NAME))

    private val rootDir: File = storageDir.canonicalFile
    private val cache = ConcurrentHashMap<String, List<TodoItem>>()
    private val listSerializer = ListSerializer(TodoItem.serializer())

    /** Replaces and persists the todo list for [threadId]. */
    suspend fun write(threadId: String, todos: List<TodoItem>) {
        val key = sanitizeThreadId(threadId)
        val snapshot = todos.toList()
        cache[key] = snapshot
        withContext(Dispatchers.IO) {
            runCatching {
                rootDir.mkdirs()
                val file = fileForKey(key)
                val encoded = CodexJson.encodeToString(listSerializer, snapshot)
                file.writeText(encoded, Charsets.UTF_8)
            }
        }
    }

    /** Returns the todo list for [threadId], loading from disk if not yet cached. */
    suspend fun read(threadId: String): List<TodoItem> {
        val key = sanitizeThreadId(threadId)
        cache[key]?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val file = fileForKey(key)
                if (!file.isFile) return@runCatching emptyList()
                val raw = file.readText(Charsets.UTF_8)
                if (raw.isBlank()) return@runCatching emptyList()
                CodexJson.decodeFromString(listSerializer, raw)
            }.getOrDefault(emptyList())
        }
        cache[key] = loaded
        return loaded
    }

    /** Formats a todo list as plain text for the model. */
    fun formatPlainText(todos: List<TodoItem>): String {
        if (todos.isEmpty()) return "No todos."
        return todos.mapIndexed { index, item ->
            "${index + 1}. [${item.status}] ${item.content}"
        }.joinToString("\n")
    }

    private fun fileForKey(safeKey: String): File = File(rootDir, "todos_$safeKey.json")

    companion object {
        const val DIR_NAME: String = "todos"
        const val DEFAULT_THREAD_ID: String = "default"
        val VALID_STATUSES: Set<String> = setOf("pending", "in_progress", "completed")

        private val sharedStores = ConcurrentHashMap<String, TodoStore>()

        /** Returns a shared [TodoStore] instance for `<filesDir>/todos/` so `todowrite` and `todoread` share in-memory state. */
        fun shared(context: Context): TodoStore {
            val dir = File(context.applicationContext.filesDir, DIR_NAME).canonicalFile
            return sharedStores.getOrPut(dir.path) { TodoStore(dir) }
        }

        internal fun sanitizeThreadId(raw: String): String {
            val cleaned = raw.trim().map { ch ->
                if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '-') {
                    ch
                } else {
                    '_'
                }
            }.joinToString("").trim('_')
            return cleaned.ifEmpty { DEFAULT_THREAD_ID }
        }
    }
}

/**
 * Writes the per-thread todo list (`name = "todowrite"`).
 * Keeps items in memory and persists them to a per-thread JSON file under the
 * app's files directory.
 */
class TodoWriteTool(
    private val store: TodoStore,
    private val threadIdProvider: () -> String = { TodoStore.DEFAULT_THREAD_ID },
) : Tool {

    constructor(
        context: Context,
        threadIdProvider: () -> String = { TodoStore.DEFAULT_THREAD_ID },
    ) : this(TodoStore.shared(context), threadIdProvider)

    override val name: String = "todowrite"
    override val description: String =
        "Writes the current thread's todo list (each item has content and status: pending, in_progress, or completed) " +
            "and returns the updated list as plain text."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("todos") {
                put("type", "array")
                put("description", "Full list of todo items for this thread")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("content") {
                            put("type", "string")
                            put("description", "Task description")
                        }
                        putJsonObject("status") {
                            put("type", "string")
                            put("description", "Task status: pending, in_progress, or completed")
                            putJsonArray("enum") {
                                add(JsonPrimitive("pending"))
                                add(JsonPrimitive("in_progress"))
                                add(JsonPrimitive("completed"))
                            }
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("content"))
                        add(JsonPrimitive("status"))
                    }
                }
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("todos"))
        }
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val todosArray = args["todos"] as? JsonArray
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: todos")

        val parsed = mutableListOf<TodoItem>()
        for ((index, element) in todosArray.withIndex()) {
            val obj = element as? JsonObject
                ?: return ToolResult(
                    ok = false,
                    output = "",
                    error = "Todo item at index $index must be a JSON object",
                )
            val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return ToolResult(
                    ok = false,
                    output = "",
                    error = "Todo item at index $index is missing non-empty 'content'",
                )
            val status = obj["status"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: return ToolResult(
                    ok = false,
                    output = "",
                    error = "Todo item at index $index is missing 'status'",
                )
            if (status !in TodoStore.VALID_STATUSES) {
                return ToolResult(
                    ok = false,
                    output = "",
                    error = "Invalid status '$status' at index $index — must be pending, in_progress, or completed",
                )
            }
            parsed += TodoItem(content = content, status = status)
        }

        val threadId = threadIdProvider()
        store.write(threadId, parsed)
        return ToolResult(ok = true, output = store.formatPlainText(parsed))
    }
}

/**
 * Reads the per-thread todo list (`name = "todoread"`).
 * Takes no arguments and returns the current list as plain text.
 */
class TodoReadTool(
    private val store: TodoStore,
    private val threadIdProvider: () -> String = { TodoStore.DEFAULT_THREAD_ID },
) : Tool {

    constructor(
        context: Context,
        threadIdProvider: () -> String = { TodoStore.DEFAULT_THREAD_ID },
    ) : this(TodoStore.shared(context), threadIdProvider)

    override val name: String = "todoread"
    override val description: String =
        "Reads the current thread's todo list and returns it as plain text."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val threadId = threadIdProvider()
        val items = store.read(threadId)
        return ToolResult(ok = true, output = store.formatPlainText(items))
    }
}
