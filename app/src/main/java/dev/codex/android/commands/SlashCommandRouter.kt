package dev.codex.android.commands

import dev.codex.android.api.ProviderService
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.KeyStore
import dev.codex.android.security.PermissionPolicy
import dev.codex.android.skills.SkillStore
import dev.codex.android.tools.WorkspaceFileTools

/**
 * Slash-command router for Codex Mobile Studio (M8).
 *
 * UI-independent: it never touches Compose, the ViewModel, or any UI
 * state. The integration point is ChatViewModel.sendGoal (not this
 * file): when [isSlashCommand] is true, call [handle] instead of
 * starting a normal agent turn, show [SlashResult.text] in the chat,
 * and apply [SlashResult.action] / [SlashResult.followUpPrompt].
 *
 * Behaviour is ported from the two open-source references, never pasted:
 * - OpenAI Codex CLI (Apache-2.0, github.com/openai/codex): `/` opens the
 *   command popup; `/plan` switches to plan mode (optional inline
 *   prompt runs next in that mode); `/compact` summarises the visible
 *   chat to free context; `/recap` generates a summary without
 *   compacting; `/model` picks model + reasoning effort; `/permissions`
 *   sets what Codex can do without asking; `/mcp` lists configured MCP
 *   tools; `/export` writes the conversation as Markdown
 *   (`/export session.md`); `/rename` renames the chat; `/delete`
 *   permanently deletes the session; `/fork` forks the chat;
 *   `/clear` clears the terminal and starts a fresh chat; `/init`
 *   generates an `AGENTS.md` scaffold; `/skills` browses skills;
 *   `/plugins` browses plugins; `/status` shows session config and
 *   token usage; `/usage` shows account token usage.
 *   (Official command reference: learn.chatgpt.com/docs/cli/slash-commands.)
 * - OpenCode (MIT, github.com/anomalyco/opencode): commands carry a
 *   name, a description shown in the TUI, and arguments via
 *   `$ARGUMENTS` / `$1` placeholders; built-ins include `/init`,
 *   `/undo`, `/redo`, `/help`, `/new` (alias of `/clear`) and
 *   `/export`. Custom commands are Markdown files whose filename is
 *   the command name. We reuse the naming + the name/description/
 *   usage metadata model (which drives autocomplete here), not the
 *   Markdown-file mechanism — our commands are built-ins wired to
 *   real on-device engines.
 *
 * Adaptations vs the references (deliberate, see the report):
 * - `/clear` deletes this thread's messages from the DB and keeps the
 *   thread (Codex clears the terminal and starts a fresh chat; the
 *   equivalent destructive step here is `/delete`).
 * - `/usage` reports real on-device counts only. Token/cost tracking
 *   does not exist yet — it lands with the Context Engine — so no
 *   token numbers are ever shown or invented.
 * - `/model` switches the stored model name via [KeyStore]; reasoning
 *   effort (`--reasoning=`) is accepted on the command line but is
 *   NOT persisted yet — there is no effort field in [KeyStore] — so
 *   the result says so plainly.
 * - `/help` is the one command beyond the 16 specified: both
 *   references ship it as a built-in and the help/autocomplete
 *   metadata needs a consumer.
 *
 * Commands NOT registered (engines do not exist yet):
 * `/worktree`, `/agents`, `/subagents`, `/ci`, `/preview`.
 */
object SlashCommandRouter {

    /** Metadata for every registered command, in help-listing order. */
    val commands: List<CommandMeta> = listOf(
        CommandMeta(
            name = "plan",
            description = "Switch this thread to PLAN mode (read-only planning; the next turn uses it)",
            usage = "/plan [prompt]",
        ),
        CommandMeta(
            name = "model",
            description = "Show or switch the active model",
            usage = "/model [name | list]",
        ),
        CommandMeta(
            name = "permissions",
            description = "Show the approval policy for this thread",
            usage = "/permissions",
        ),
        CommandMeta(
            name = "compact",
            description = "Summarise the full thread and tool activity without deleting the transcript",
            usage = "/compact",
        ),
        CommandMeta(
            name = "recap",
            description = "Show the stored summary, or generate one",
            usage = "/recap",
        ),
        CommandMeta(
            name = "fork",
            description = "Fork this thread into a new thread with a copy of the transcript",
            usage = "/fork [title]",
        ),
        CommandMeta(
            name = "rename",
            description = "Rename this thread",
            usage = "/rename <new title>",
        ),
        CommandMeta(
            name = "delete",
            description = "Permanently delete this thread",
            usage = "/delete",
        ),
        CommandMeta(
            name = "export",
            description = "Export this thread as Markdown",
            usage = "/export [filename.md]",
        ),
        CommandMeta(
            name = "init",
            description = "Generate an AGENTS.md scaffold in the workspace",
            usage = "/init",
        ),
        CommandMeta(
            name = "skills",
            description = "List installed skills",
            usage = "/skills",
        ),
        CommandMeta(
            name = "plugins",
            description = "List installed plugins (skills for now; GitHub import lands with PluginManager)",
            usage = "/plugins",
        ),
        CommandMeta(
            name = "mcp",
            description = "List configured MCP servers",
            usage = "/mcp",
        ),
        CommandMeta(
            name = "usage",
            description = "Show on-device usage counts (threads, messages, runs)",
            usage = "/usage",
        ),
        CommandMeta(
            name = "status",
            description = "Show live thread and service state",
            usage = "/status",
        ),
        CommandMeta(
            name = "clear",
            description = "Delete this thread's messages (keeps the thread)",
            usage = "/clear",
        ),
        CommandMeta(
            name = "help",
            description = "List all slash commands",
            usage = "/help",
        ),
    )

    /** True when [text] looks like a slash command (leading `/` after trim). */
    fun isSlashCommand(text: String): Boolean =
        text.trimStart().startsWith("/")

    /**
     * Parses [text] into a [ParsedCommand], or null when it is not a slash
     * command (no leading `/`, or a bare `/` with no name). The name is
     * lower-cased, so `/MODEL` works. [ParsedCommand.args] honours double
     * quotes, so `/rename "My Title"` is one argument.
     */
    fun parseCommand(text: String): ParsedCommand? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return null
        val body = trimmed.removePrefix("/").trimStart()
        if (body.isEmpty()) return null
        val firstSpace = body.indexOfFirst { it.isWhitespace() }
        val name = if (firstSpace == -1) body else body.substring(0, firstSpace)
        val rawArgs = if (firstSpace == -1) "" else body.substring(firstSpace).trim()
        return ParsedCommand(
            name = name.lowercase(),
            args = splitArgs(rawArgs),
            rawArgs = rawArgs,
        )
    }

    /** Splits [rawArgs] on whitespace, keeping double-quoted spans together. */
    internal fun splitArgs(rawArgs: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var hasToken = false
        for (ch in rawArgs) {
            when {
                ch == '"' -> {
                    inQuotes = !inQuotes
                    hasToken = true
                }
                ch.isWhitespace() && !inQuotes -> {
                    if (hasToken) {
                        out += current.toString()
                        current.clear()
                        hasToken = false
                    }
                }
                else -> {
                    current.append(ch)
                    hasToken = true
                }
            }
        }
        if (hasToken) out += current.toString()
        return out
    }

    /**
     * Autocomplete candidates for [prefix] (with or without a leading
     * `/`, case-insensitive). An empty prefix returns every command.
     */
    fun autocomplete(prefix: String): List<CommandMeta> {
        val needle = prefix.trim().removePrefix("/").lowercase()
        if (needle.isEmpty()) return commands
        return commands.filter { it.name.startsWith(needle) }
    }

    /** The `/help` listing: one `usage — description` line per command. */
    fun helpText(): String = buildString {
        append("Slash commands:\n")
        for (meta in commands) {
            append("\n").append(meta.usage).append(" — ").append(meta.description)
        }
        append("\n\nType a command to run it. Commands act on the current thread.")
    }

    /**
     * Dispatches a slash-command [input] to its handler. A non-command
     * input or an unknown command never throws — it returns a [SlashResult]
     * telling the user what happened (and, for unknown commands, to try
     * `/help`).
     */
    suspend fun handle(input: String, ctx: CommandContext): SlashResult {
        val parsed = parseCommand(input)
            ?: return SlashResult("Type /help to list slash commands.")
        if (parsed.name == "help") {
            return SlashResult(helpText())
        }
        val handler = CommandHandlers.handlerFor(parsed.name)
            ?: return SlashResult(
                "Unknown command /${parsed.name}. Type /help to list slash commands.",
            )
        return handler(ctx, parsed)
    }
}

/** Help/autocomplete metadata for one command. [name] has no leading `/`. */
data class CommandMeta(
    val name: String,
    val description: String,
    val usage: String,
)

/** A parsed slash command: lower-cased [name], split [args], raw [rawArgs]. */
data class ParsedCommand(
    val name: String,
    val args: List<String>,
    val rawArgs: String,
)

/**
 * Everything a handler needs, supplied by the integration (ChatViewModel).
 * The router never constructs engines itself and never touches UI state.
 *
 * @param summarise one-shot summariser, or null when the integration has
 *   not wired one. Wire it to a one-shot [dev.codex.android.agent.AgentLoop]
 *   run with an EMPTY ToolRegistry (no tools, so the model can only reply
 *   with text — read-only by construction) and the summarise prompt the
 *   handler builds. Return [SummariseResult.NotConfigured] when no
 *   provider/key is configured; the handler checks the [KeyStore] first
 *   and never calls this when unconfigured.
 * @param workspaceFiles the workspace's [WorkspaceFileTools], or null when
 *   no workspace is open (`/init` reports that instead of writing).
 */
data class CommandContext(
    val threadId: String,
    val workspaceId: String,
    val db: CodexDatabase,
    val keyStore: KeyStore,
    val providerService: ProviderService,
    val skillStore: SkillStore,
    val policy: PermissionPolicy,
    val workspaceFiles: WorkspaceFileTools?,
    val summarise: (suspend (String) -> SummariseResult)? = null,
)

/** Outcome of the one-shot summariser supplied in [CommandContext]. */
sealed class SummariseResult {
    /** The model produced this summary text. */
    data class Ok(val text: String) : SummariseResult()

    /** No provider/key is configured, so no model call was attempted. */
    object NotConfigured : SummariseResult()

    /** A model call was attempted and failed. */
    data class Failed(val message: String) : SummariseResult()
}

/**
 * What the UI should do after a command runs. [text] is always shown in
 * the chat; [action] is a UI-side effect the router itself never performs.
 */
data class SlashResult(
    val text: String,
    val action: SlashAction = SlashAction.None,
    /**
     * A normal goal the UI should send as the next turn after handling
     * the command (set by `/plan <prompt>` — Codex runs the inline
     * prompt next, in plan mode).
     */
    val followUpPrompt: String? = null,
)

/** UI-side effects a command can request. The router never performs them. */
sealed class SlashAction {
    /** No UI side effect. */
    object None : SlashAction()

    /** Clear the current thread's messages from the chat view. */
    data class ClearThread(val threadId: String) : SlashAction()

    /** Switch the chat view to this (e.g. freshly forked) thread. */
    data class SwitchThread(val threadId: String, val title: String) : SlashAction()

    /** The current thread was deleted; the UI must leave it. */
    data class DeleteCurrentThread(val threadId: String) : SlashAction()

    /** Offer this Markdown transcript for share/save under [filename]. */
    data class Export(val markdown: String, val filename: String) : SlashAction()
}
