package dev.codex.android.tools

import android.content.Context
import dev.codex.android.api.FunctionDefinition
import dev.codex.android.api.ToolDefinition
import kotlinx.serialization.json.JsonObject

/**
 * Result of executing a [Tool].
 *
 * @property ok true when the tool completed successfully.
 * @property output human/model-readable output on success (empty on failure).
 * @property error human-readable failure detail when [ok] is false, null otherwise.
 */
data class ToolResult(val ok: Boolean, val output: String, val error: String? = null)

/**
 * A single callable tool the agent can invoke via a function call.
 *
 * Implementations must be suspend-safe, fast, and must not perform network
 * calls. Tools that touch messages, calls, settings, money, or deletion
 * must set [sensitive] to true so the caller can ask the user first.
 */
interface Tool {
    /** Stable identifier used by the model, e.g. "get_time". */
    val name: String

    /** Short description of what the tool does, shown to the model. */
    val description: String

    /** JSON Schema object describing the tool's arguments. */
    val parametersSchema: JsonObject

    /** True when the user must be asked before executing this tool. */
    val sensitive: Boolean

    /** Executes the tool with the given (already validated) arguments. */
    suspend fun execute(args: JsonObject): ToolResult
}

/**
 * Registry of the tools available to the agent.
 *
 * Registration order is preserved by [all] and [definitions].
 */
class ToolRegistry {
    private val tools = LinkedHashMap<String, Tool>()

    /** Registers (or replaces) a tool under its [Tool.name]. */
    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    /** Returns the tool with the given name, or null if none is registered. */
    fun get(name: String): Tool? = tools[name]

    /** All registered tools, in registration order. */
    fun all(): List<Tool> = tools.values.toList()

    /**
     * One [ToolDefinition] per registered tool, in registration order,
     * with `parameters` set to the tool's [Tool.parametersSchema].
     */
    fun definitions(): List<ToolDefinition> = tools.values.map { tool ->
        ToolDefinition(
            function = FunctionDefinition(
                name = tool.name,
                description = tool.description,
                parameters = tool.parametersSchema,
            ),
        )
    }

    companion object {
        /** A registry with all built-in tools registered. */
        fun withBuiltins(context: Context): ToolRegistry =
            ToolRegistry().apply {
                register(GetTimeTool)
                register(DeviceInfoTool)
                register(EchoTool)
                register(OpenAppTool(context))
                register(TapTool)
                register(TypeTextTool)
                register(ReadScreenTool)
                register(ScrollTool)
                register(GoBackTool)
                register(PressHomeTool)
                register(ScheduleTaskTool(context))
                register(GenerateAppTool(context))
                register(ListStoreTool(context))
                // Filler (other AI): question / todo / web tools. The web
                // search endpoint + key are blank until a BYOK search
                // setting exists in KeyStore — the tool then reports
                // itself unconfigured rather than calling anything.
                register(QuestionTool)
                register(TodoWriteTool(context))
                register(TodoReadTool(context))
                register(WebFetchTool())
                register(WebSearchTool(endpoint = "", apiKey = ""))
                register(ReadPdfTool)
                register(ReadDocumentTool)
                register(ReadTextFileTool)
                register(ReadImageTool(context))
            }
    }
}
