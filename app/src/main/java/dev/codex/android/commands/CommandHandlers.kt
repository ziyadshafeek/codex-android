package dev.codex.android.commands

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import android.content.ContentValues
import dev.codex.android.agent.AgentRuntime
import dev.codex.android.api.ConnectionTestResult
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.ThreadEntity
import dev.codex.android.data.TimelineMessage
import dev.codex.android.security.ExecutionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

/** Handler signature: context + parsed command -> result for the chat. */
private typealias SlashCommandHandler = suspend (CommandContext, ParsedCommand) -> SlashResult

/**
 * Handlers for the slash commands registered in [SlashCommandRouter].
 *
 * Every handler is wired to a REAL engine that exists in the repo today —
 * no fakes, no invented numbers:
 * - Threads/messages/modes: [CodexDatabase] (`threads` / `messages` /
 *   `mcp_servers` tables). [CodexDatabase] has no `setThreadMode` /
 *   `deleteMessages` / counts API, so those three operations use its
 *   public `writableDatabase` / `readableDatabase` directly (new files
 *   only — no existing file was modified to add them).
 * - Summaries: a one-shot read-only [dev.codex.android.agent.AgentLoop]
 *   run, supplied as [CommandContext.summarise] by the integration
 *   (empty ToolRegistry, so the model cannot call tools). When no
 *   provider/key is configured the handler reports that and never
 *   calls the model — a summary is never faked.
 * - Skills/plugins: [dev.codex.android.skills.SkillStore].
 * - Models: [dev.codex.android.api.ProviderService] + [dev.codex.android.data.KeyStore].
 * - Permissions: [dev.codex.android.security.PermissionPolicy]
 *   (via `AgentRuntime.policy`).
 * - Live state: [AgentRuntime.threads].
 * - AGENTS.md: [dev.codex.android.tools.WorkspaceFileTools.writeFile].
 *
 * NOT registered (engines do not exist yet): `/worktree`, `/agents`,
 * `/subagents`, `/ci`, `/preview`.
 *
 * Summaries are stored as a `system` message with `kind = "summary"` —
 * a new kind value in the existing free-form `kind` column, no schema
 * change. Older messages are marked compacted by adding
 * `"compacted":true` to their existing `metadata_json` (other keys
 * preserved); the rows themselves are kept.
 */
object CommandHandlers {

    private val handlers: Map<String, SlashCommandHandler> = mapOf(
        "plan" to ::handlePlan,
        "model" to ::handleModel,
        "permissions" to ::handlePermissions,
        "compact" to ::handleCompact,
        "recap" to ::handleRecap,
        "fork" to ::handleFork,
        "rename" to ::handleRename,
        "delete" to ::handleDelete,
        "export" to ::handleExport,
        "init" to ::handleInit,
        "skills" to ::handleSkills,
        "plugins" to ::handlePlugins,
        "mcp" to ::handleMcp,
        "usage" to ::handleUsage,
        "status" to ::handleStatus,
        "clear" to ::handleClear,
    )

    /** The handler for [name], or null when no such command is registered. */
    fun handlerFor(name: String): SlashCommandHandler? = handlers[name]

    /** Registered handler names (excludes `/help`, which the router answers). */
    val handlerNames: Set<String> get() = handlers.keys

    // --- /plan ---

    private suspend fun handlePlan(ctx: CommandContext, parsed: ParsedCommand): SlashResult {
        val updated = setThreadMode(ctx.db, ctx.threadId, ExecutionMode.PLAN.name)
        if (updated == 0) {
            return SlashResult(
                "No thread is open yet, so PLAN mode could not be saved. " +
                    "Send a message first, then run /plan — the next turn will use it.",
            )
        }
        val prompt = parsed.rawArgs
        val text = buildString {
            append("Plan mode on for this thread. The next turn will plan first ")
            append("and ask before changing anything.")
            if (prompt.isNotBlank()) {
                append("\nYour prompt will run next, in PLAN mode: \"$prompt\"")
            }
        }
        return SlashResult(text, followUpPrompt = prompt.ifBlank { null })
    }

    // --- /model ---

    private suspend fun handleModel(ctx: CommandContext, parsed: ParsedCommand): SlashResult {
        val keyStore = ctx.keyStore
        val firstArg = parsed.args.firstOrNull()
        if (firstArg == null) {
            return SlashResult(
                "Current model: ${keyStore.getModel()}\n" +
                    "Endpoint: ${keyStore.getBaseUrl().ifBlank { "(not set)" }}\n" +
                    "API key: ${if (keyStore.hasKey()) "configured" else "not set"}\n" +
                    "Switch with /model <name>, or list what the provider offers with /model list.",
            )
        }
        if (firstArg.lowercase() == "list") {
            if (!keyStore.hasKey() || keyStore.getBaseUrl().isBlank()) {
                return SlashResult(
                    "Listing models needs a configured provider — " +
                        "add your endpoint and key in Settings first.",
                )
            }
            val apiKey = keyStore.getApiKey() ?: return SlashResult(
                "Listing models needs a configured provider — " +
                    "add your endpoint and key in Settings first.",
            )
            return when (val result = ctx.providerService.testAndFetchModels(keyStore.getBaseUrl(), apiKey)) {
                is ConnectionTestResult.Success -> {
                    if (result.modelIds.isEmpty()) {
                        SlashResult("The provider answered but listed no models.")
                    } else {
                        SlashResult(
                            "Models on this provider (${result.modelIds.size}):\n" +
                                result.modelIds.take(30).joinToString("\n") { "• $it" } +
                                if (result.modelIds.size > 30) "\n… and ${result.modelIds.size - 30} more." else "",
                        )
                    }
                }
                is ConnectionTestResult.AuthError ->
                    SlashResult("Could not list models: ${result.message}")
                is ConnectionTestResult.EndpointError ->
                    SlashResult("Could not list models: ${result.message}")
                is ConnectionTestResult.NetworkError ->
                    SlashResult("Could not list models: ${result.message}")
            }
        }
        if (firstArg.startsWith("--")) {
            return SlashResult(
                "Usage: /model [name | list]\n" +
                    "Reasoning effort (--reasoning=low|medium|high) is not stored yet — " +
                    "there is no effort field in the key store — so use /model <name> to switch models.",
            )
        }
        val model = firstArg.trim()
        if (model.isBlank()) return SlashResult("Usage: /model <name>")
        keyStore.setModel(model)
        val reasoningFlag = parsed.args.firstOrNull { it.startsWith("--reasoning=") }
        val note = if (reasoningFlag != null) {
            "\nNote: $reasoningFlag was ignored — reasoning effort is not stored yet."
        } else {
            ""
        }
        return SlashResult("Model switched to $model.$note")
    }

    // --- /permissions ---

    private suspend fun handlePermissions(ctx: CommandContext, parsed: ParsedCommand): SlashResult {
        val thread = currentThread(ctx)
        val modeName = thread?.mode ?: ExecutionMode.AUTO.name
        val mode = runCatching { ExecutionMode.valueOf(modeName) }.getOrDefault(ExecutionMode.AUTO)
        val matrix = when (mode) {
            ExecutionMode.PLAN -> "Read-only tools: allowed.\nWorkspace writes: blocked.\nExternal / destructive tools: blocked."
            ExecutionMode.ASK -> "Read-only tools: allowed.\nWorkspace writes: ask first.\nExternal / destructive tools: ask first."
            ExecutionMode.AUTO -> "Read-only tools: allowed.\nWorkspace writes: allowed.\nExternal / destructive tools: ask first."
        }
        // Per-tool overrides live only in memory in PermissionPolicy (no
        // enumeration API, nothing persisted), so they are described, not
        // listed — listing rules we cannot read would be invented.
        return SlashResult(
            "Permissions for this thread (mode: ${mode.name}):\n$matrix\n\n" +
                "An explicit DENY rule always wins, and a tool the policy blocks is " +
                "never opened for approval. Per-tool overrides are kept in memory " +
                "only and are not listed here.",
        )
    }

    // --- /compact ---

    private suspend fun handleCompact(ctx: CommandContext, parsed: ParsedCommand): SlashResult {
        val previous = ctx.db.getMessages(ctx.threadId, limit = 1, mostRecent = true, kind = "summary").lastOrNull()
        val contentMessages = ctx.db.getMessages(ctx.threadId, limit = 200, mostRecent = true)
            .filter { it.id > (previous?.id ?: 0) && it.kind == "text" && it.role in setOf("user", "assistant") }
        val messages = listOfNotNull(previous) + contentMessages
        if (contentMessages.isEmpty()) {
            return SlashResult("Nothing to compact yet — this thread has no messages.")
        }
        val summary = summariseOrExplain(ctx, messages)
            ?: return SlashResult(PROVIDER_NEEDED_COMPACT)
        return when (summary) {
            is SummariseResult.Ok -> {
                val summaryText = summary.text.trim().take(8000)
                if (summaryText.isBlank()) {
                    return SlashResult("Compaction failed: the model returned an empty summary.")
                }
                val summaryRowId = ctx.db.appendMessage(
                    TimelineMessage(
                        threadId = ctx.threadId,
                        role = "system",
                        kind = "summary",
                        content = summaryText,
                    ),
                )
                markMessagesCompacted(ctx.db, messages, exceptIds = setOf(summaryRowId))
                SlashResult(
                    "Compacted ${contentMessages.size} messages into a summary " +
                        "(${summaryText.length} chars). Older messages are kept, marked compacted.",
                )
            }
            is SummariseResult.NotConfigured -> SlashResult(PROVIDER_NEEDED_COMPACT)
            is SummariseResult.Failed -> SlashResult(
                "Compaction failed: ${summary.message}",
            )
        }
    }

    // --- /recap ---

    private suspend fun handleRecap(ctx: CommandContext, parsed: ParsedCommand): SlashResult {
        val messages = ctx.db.getMessages(ctx.threadId, limit = 200)
        val stored = messages.lastOrNull { it.kind == "summary" }
        if (stored != null) {
            return SlashResult(
                "Summary (stored ${Date(stored.createdAt)}):\n\n${stored.content}",
            )
        }
        if (messages.isEmpty()) {
            return SlashResult("Nothing to recap yet — this thread has no messages.")
        }
        val summary = summariseOrExplain(ctx, messages)
            ?: return SlashResult(
                "No summary is stored yet, and generating one needs a configured " +
                    "provider — add your endpoint and key in Settings first.",
            )
        return when (summary) {
            is SummariseResult.Ok -> {
                val summaryText = summary.text.trim()
                if (summaryText.isBlank()) {
                    return SlashResult("Recap failed: the model returned an empty summary.")
                }
                ctx.db.appendMessage(
                    TimelineMessage(
                        threadId = ctx.threadId,
                        role = "system",
                        kind = "summary",
                        content = summaryText,
                    ),
                )
                SlashResult(
                    "Summary (generated just now; stored for next time — " +
                        "use /compact to also mark older messages compacted):\n\n$summaryText",
                )
            }
            is SummariseResult.NotConfigured -> SlashResult(
                "No summary is stored yet, and generating one needs a configured " +
                    "provider — add your endpoint and key in Settings first.",
            )
            is SummariseResult.Failed -> SlashResult("Recap failed: ${summary.message}")
        }
    }

    /**
     * Shared one-shot summarise path for /compact and /recap. Returns null
     * when the caller should report "provider needed" (no key/base URL, or
     * no summariser wired); otherwise the summariser's outcome. Never
     * fabricates a summary.
     */
    private suspend fun summariseOrExplain(
        ctx: CommandContext,
        messages: List<TimelineMessage>,
    ): SummariseResult? {
        if (!ctx.keyStore.hasKey() || ctx.keyStore.getBaseUrl().isBlank()) return null
        val summarise = ctx.summarise ?: return null
        return summarise(buildSummaryPrompt(messages))
    }

    // --- /fork ---

    private suspend fun handleFork(ctx: CommandContext, parsed: ParsedCommand): SlashResult {
        val thread = currentThread(ctx)
            ?: return SlashResult("No thread is open yet, so there is nothing to fork.")
        val title = parsed.rawArgs.ifBlank { "Fork of ${thread.title}" }.take(120)
        val forked = ctx.db.createThread(
            workspaceId = thread.workspaceId,
            title = title,
            mode = thread.mode,
        )
        val messages = ctx.db.getMessages(ctx.threadId, limit = 500)
        for (msg in messages) {
            ctx.db.appendMessage(msg.copy(id = 0, threadId = forked.id))
        }
        return SlashResult(
            "Forked \"${thread.title}\" into \"$title\" (${messages.size} messages copied).",
            action = SlashAction.SwitchThread(forked.id, forked.title),
        )
    }

    // --- /rename ---

    private suspend fun handleRename(ctx: CommandContext, parsed: ParsedCommand): SlashResult {
        val title = parsed.rawArgs.trim()
        if (title.isBlank()) return SlashResult("Usage: /rename <new title>")
        ctx.db.updateThreadState(ctx.threadId, title = title.take(120))
        return SlashResult("Thread renamed to \"$title\".")
    }

    // --- /delete ---

    private suspend fun handleDelete(ctx: CommandContext, parsed: ParsedCommand): SlashResult {
        // Stop any active run first: the runtime appends messages for the
        // thread, and deleting the thread under a running turn would fail
        // its writes. Session grants for the thread go with it.
        AgentRuntime.stop(ctx.threadId)
        AgentRuntime.policy.clearSession(ctx.threadId)
        ctx.db.deleteThread(ctx.threadId)
        return SlashResult(
            "Thread deleted.",
            action = SlashAction.DeleteCurrentThread(ctx.threadId),
        )
    }

    // --- /export ---

    private suspend fun handleExport(ctx: CommandContext, parsed: ParsedCommand): SlashResult {
        val thread = currentThread(ctx)
        val messages = ctx.db.getMessages(ctx.threadId, limit = 500)
        if (messages.isEmpty()) {
            return SlashResult("Nothing to export yet — this thread has no messages.")
        }
        val filename = parsed.args.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.contains('/') && !it.contains('\\') }
            ?.let { if (it.endsWith(".md")) it else "$it.md" }
            ?: "codex-${ctx.threadId}.md"
        val markdown = buildMarkdownTranscript(thread?.title ?: "Codex thread", messages)
        return SlashResult(
            "Exported ${messages.size} messages as Markdown (${markdown.length} chars) to $filename.",
            action = SlashAction.Export(markdown, filename),
        )
    }

    // --- /init ---

    private suspend fun handleInit(ctx: CommandContext, parsed: ParsedCommand): SlashResult {
        val files = ctx.workspaceFiles
            ?: return SlashResult(
                "No workspace is open — /init needs a workspace to write AGENTS.md into.",
            )
        val existing = runCatching { files.readFile("AGENTS.md") }.getOrNull().orEmpty()
        if (!existing.startsWith("Error:")) {
            return SlashResult(
                "AGENTS.md already exists in this workspace — left untouched. " +
                    "Edit it directly to change the project instructions.",
            )
        }
        val thread = currentThread(ctx)
        val skills = runCatching { ctx.skillStore.all() }.getOrDefault(emptyList())
        val content = buildAgentsMdContent(
            projectName = thread?.title ?: "This workspace",
            skillNames = skills.map { it.name },
        )
        val result = runCatching { files.writeFile("AGENTS.md", content) }
            .getOrElse { return SlashResult("Could not write AGENTS.md: ${it.message}") }
        return SlashResult("$result\nCodex will read AGENTS.md at the start of every run in this workspace.")
    }

    // --- /skills ---

    private suspend fun handleSkills(ctx: CommandContext, parsed: ParsedCommand): SlashResult {
        val skills = ctx.skillStore.all()
        if (skills.isEmpty()) return SlashResult("No skills installed.")
        return SlashResult(
            "Skills (${skills.size}):\n" + skills.joinToString("\n") { skill ->
                "• ${skill.name} — ${skill.description}" +
                    if (skill.enabled) "" else " (disabled)"
            },
        )
    }

    // --- /plugins ---

    private suspend fun handlePlugins(ctx: CommandContext, parsed: ParsedCommand): SlashResult {
        val skills = ctx.skillStore.all()
        val listing = if (skills.isEmpty()) {
            "No plugins installed."
        } else {
            "Plugins (${skills.size}):\n" + skills.joinToString("\n") { skill ->
                "• ${skill.name} — ${skill.description}" +
                    if (skill.enabled) "" else " (disabled)"
            }
        }
        return SlashResult(
            "$listing\n\nGitHub plugin import arrives with PluginManager " +
                "(a parallel workstream) — /plugins lists installed skills for now.",
        )
    }

    // --- /mcp ---

    private suspend fun handleMcp(ctx: CommandContext, parsed: ParsedCommand): SlashResult {
        val servers = ctx.db.listMcpServers()
        if (servers.isEmpty()) {
            return SlashResult("No MCP servers configured.")
        }
        return SlashResult(
            "MCP servers (${servers.size}):\n" + servers.joinToString("\n") { server ->
                "• ${server.name} — ${server.endpointUrl}" +
                    if (server.enabled) "" else " (disabled)"
            } + "\n\nServer auth tokens are stored in the key store, never shown here.",
        )
    }

    // --- /usage ---

    private suspend fun handleUsage(ctx: CommandContext, parsed: ParsedCommand): SlashResult {
        val db = ctx.db
        val threads = countRows(db, CodexDatabase.TABLE_THREADS)
        val messagesTotal = countRows(db, CodexDatabase.TABLE_MESSAGES)
        val messagesHere = countMessagesInThread(db, ctx.threadId)
        val audited = countRows(db, CodexDatabase.TABLE_AUDIT_LOG)
        val automations = countRows(db, CodexDatabase.TABLE_AUTOMATIONS)
        val mcpServers = countRows(db, CodexDatabase.TABLE_MCP_SERVERS)
        val skills = countRows(db, CodexDatabase.TABLE_SKILLS)
        val facts = countRows(db, CodexDatabase.TABLE_FACTS)
        return SlashResult(
            "Usage (real on-device counts):\n" +
                "• Threads: $threads\n" +
                "• Messages: $messagesTotal total ($messagesHere in this thread)\n" +
                "• Audited tool decisions: $audited\n" +
                "• Automations: $automations\n" +
                "• MCP servers: $mcpServers\n" +
                "• Skills: $skills\n" +
                "• Stored facts: $facts\n\n" +
                "Token and cost tracking lands with the Context Engine — " +
                "no token numbers are tracked yet, so none are shown.",
        )
    }

    // --- /status ---

    private suspend fun handleStatus(ctx: CommandContext, parsed: ParsedCommand): SlashResult {
        val thread = currentThread(ctx)
        val runtime = AgentRuntime.threads.value[ctx.threadId]
        val activeThreads = AgentRuntime.threads.value.values.count { it.isRunning }
        val running = runtime?.isRunning == true
        val text = buildString {
            append("Status:\n")
            append("• Thread: ${thread?.title ?: "(no thread open)"}\n")
            append("• Mode: ${thread?.mode ?: ExecutionMode.AUTO.name}\n")
            append("• Thread state: ${thread?.status ?: "unknown"}\n")
            append("• Run: ${if (running) "running — step ${runtime?.stepIndex ?: 0}/${runtime?.maxSteps ?: 0}" else "idle"}")
            if (runtime?.statusText?.isNotBlank() == true) append(" (${runtime.statusText})")
            append("\n")
            val pending = runtime?.pendingApproval
            if (pending != null) append("• Waiting for approval: ${pending.toolName}\n")
            append("• Active threads: $activeThreads\n")
            append("• Provider: ${if (ctx.keyStore.hasKey() && ctx.keyStore.getBaseUrl().isNotBlank()) "configured" else "not configured"}")
            append(" (model ${ctx.keyStore.getModel()})")
        }
        return SlashResult(text)
    }

    // --- /clear ---

    private suspend fun handleClear(ctx: CommandContext, parsed: ParsedCommand): SlashResult {
        if (AgentRuntime.threads.value[ctx.threadId]?.isRunning == true) {
            return SlashResult(
                "A run is active in this thread — stop it first, then run /clear.",
            )
        }
        val deleted = deleteMessages(ctx.db, ctx.threadId)
        if (deleted == 0) {
            return SlashResult("This thread is already empty.")
        }
        return SlashResult(
            "Cleared $deleted messages from this thread. The thread itself is kept — " +
                "use /delete to remove it.",
            action = SlashAction.ClearThread(ctx.threadId),
        )
    }

    // --- DB helpers (no setThreadMode / deleteMessages / counts API exists
    // in CodexDatabase, so these use its public readable/writable DB) ---

    private suspend fun currentThread(ctx: CommandContext): ThreadEntity? =
        ctx.db.listThreads(ctx.workspaceId).firstOrNull { it.id == ctx.threadId }

    private suspend fun setThreadMode(db: CodexDatabase, threadId: String, mode: String): Int =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put("mode", mode)
                put("updated_at", System.currentTimeMillis())
            }
            db.writableDatabase.update(
                CodexDatabase.TABLE_THREADS, values, "id = ?", arrayOf(threadId),
            )
        }

    private suspend fun deleteMessages(db: CodexDatabase, threadId: String): Int =
        withContext(Dispatchers.IO) {
            db.writableDatabase.delete(
                CodexDatabase.TABLE_MESSAGES, "thread_id = ?", arrayOf(threadId),
            )
        }

    private suspend fun countRows(db: CodexDatabase, table: String): Long =
        withContext(Dispatchers.IO) {
            // Table names come only from CodexDatabase constants in this
            // file — never from user input — so this is not injectable.
            db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        }

    private suspend fun countMessagesInThread(db: CodexDatabase, threadId: String): Long =
        withContext(Dispatchers.IO) {
            db.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM ${CodexDatabase.TABLE_MESSAGES} WHERE thread_id = ?",
                arrayOf(threadId),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        }

    /**
     * Adds `"compacted":true` to each message's `metadata_json` except the
     * ids in [exceptIds] (the freshly stored summary). Existing keys are
     * preserved; already-marked rows are left untouched.
     */
    private suspend fun markMessagesCompacted(
        db: CodexDatabase,
        messages: List<TimelineMessage>,
        exceptIds: Set<Long>,
    ) = withContext(Dispatchers.IO) {
        val writable = db.writableDatabase
        for (msg in messages) {
            if (msg.id in exceptIds) continue
            val updated = withCompactedFlag(msg.metadataJson)
            if (updated == msg.metadataJson) continue
            val values = ContentValues().apply { put("metadata_json", updated) }
            writable.update(
                CodexDatabase.TABLE_MESSAGES, values, "id = ?", arrayOf(msg.id.toString()),
            )
        }
    }

    /** Pure metadata helper, JVM-testable: see SlashCommandRouterTest. */
    internal fun withCompactedFlag(metadataJson: String): String {
        val trimmed = metadataJson.trim()
        if (trimmed.isEmpty() || trimmed == "{}") return """{"compacted":true}"""
        // Check the real "compacted" KEY, not a substring: the old
        // `"compacted" in trimmed` test skipped marking whenever the word
        // appeared anywhere (e.g. inside another value). Unparseable
        // metadata falls back to the substring test so a row we cannot
        // read is never corrupted.
        val alreadyMarked = try {
            val parsed = dev.codex.android.core.CodexJson.parseToJsonElement(trimmed)
            val obj = parsed as? JsonObject
            obj?.get("compacted")?.jsonPrimitive?.booleanOrNull == true
        } catch (e: Exception) {
            "compacted" in trimmed
        }
        if (alreadyMarked) return metadataJson
        if (trimmed.endsWith("}")) {
            return trimmed.dropLast(1) + ""","compacted":true}"""
        }
        return metadataJson
    }

    /** Pure transcript builder, JVM-testable. */
    internal fun buildMarkdownTranscript(title: String, messages: List<TimelineMessage>): String {
        val sb = StringBuilder()
        sb.append("# ").append(title).append("\n\n")
        sb.append("_Exported from Codex Mobile Studio._\n\n")
        for (msg in messages) {
            val role = msg.role.replaceFirstChar { it.uppercaseChar() }
            sb.append("## ").append(role)
            if (msg.kind != "text") sb.append(" (").append(msg.kind).append(")")
            sb.append("\n\n").append(msg.content).append("\n\n")
        }
        return sb.toString()
    }

    /** Pure summarise-prompt builder, JVM-testable. Transcript is capped so the prompt stays bounded. */
    internal fun buildSummaryPrompt(messages: List<TimelineMessage>, maxChars: Int = 12_000): String {
        val transcript = messages
            .joinToString("\n") { "${it.role}: ${it.content}" }
            .take(maxChars)
        return "Summarise the conversation below into a compact handoff note. " +
            "Keep the user's goal, key decisions, files changed, open questions, " +
            "and anything needed to continue. Be concise and factual; do not invent details.\n\n" +
            transcript
    }

    /** Pure AGENTS.md template, JVM-testable. Built from real state: thread title + installed skill names. */
    internal fun buildAgentsMdContent(projectName: String, skillNames: List<String>): String =
        buildString {
            append("# AGENTS.md\n\n")
            append("Project instructions for Codex Mobile Studio. The agent reads this file ")
            append("at the start of every run in this workspace.\n\n")
            append("## Project\n\n")
            append("- Name: ").append(projectName).append("\n")
            append("- The agent works on the files in this workspace. Ask before changing anything outside it.\n\n")
            if (skillNames.isNotEmpty()) {
                append("## Skills available\n\n")
                for (name in skillNames) append("- ").append(name).append("\n")
                append("\n")
            }
            append("## Rules\n\n")
            append("- Plan before large edits; explain what will change and why.\n")
            append("- Never invent file contents, test results, or API responses — report what actually happened.\n")
            append("- Keep secrets out of code, logs, and chat. API keys live in the app's key store, never in files.\n")
        }

    private const val PROVIDER_NEEDED_COMPACT =
        "Compaction needs a configured provider — add your endpoint and key in Settings first."
}
