package dev.codex.android.ecosystem

import android.content.Context
import dev.codex.android.skills.Skill
import dev.codex.android.skills.SkillStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/*
 * SKILL.md + plugin support (Subsystem 7 of the Codex Mobile Studio plan).
 *
 * OPEN-SOURCE CHECK (done 2026-11-19 before writing this file):
 *
 * 1. OpenCode (anomalyco/opencode, MIT licence) — official docs fetched at
 *    https://opencode.ai/docs/skills/ plus the repo research in
 *    hidden_files/opencode-research.md. REUSED (behaviour ported to Kotlin;
 *    no TypeScript copied, no code pasted):
 *      - SKILL.md = YAML frontmatter (name, description, optional license /
 *        compatibility / metadata) + Markdown instruction body.
 *      - The skill ID is PATH-DERIVED (directory / file name), case
 *        sensitive; frontmatter `name` is only a display label.
 *      - Portable-id rule: ^[a-z0-9]+(-[a-z0-9]+)*$, 1-64 chars.
 *      - Discovery scopes, low to high precedence: global
 *        ~/.config/opencode/skills (+ ~/.claude/skills, ~/.agents/skills
 *        compat), then project .opencode/skills (+ .claude/skills,
 *        .agents/skills compat). A later source wins on duplicate id.
 *      - A directory containing SKILL.md is one skill; do not search
 *        inside it. Flat <name>.md files count only at a source root.
 *        Search stops six directories below a source root; hidden
 *        files/directories and node_modules are skipped.
 *      - `metadata.opencode/autoinvoke: false` (or
 *        `disable-model-invocation: true`) hides the skill from the
 *        model's list without unregistering it; opencode/autoinvoke wins
 *        when both are set. Unknown frontmatter fields are ignored.
 *      - HTTP skill catalogs are an index.json listing files (not
 *        implemented here — GitHub repo import below covers distribution).
 *    OpenCode is MIT, so porting this behaviour is permitted; the licence
 *    notice lives in the upstream repo, not in this file (no upstream
 *    code is reproduced here).
 *
 * 2. OpenAI Codex CLI (openai/codex) — official docs were NOT directly
 *    reachable from this VM, so the plugin shape below follows secondary
 *    guides (codex.danielvaughan.com, itecsonline.com, opentools.ai) and
 *    must be re-checked against developers.openai.com/codex before
 *    relying on manifest edge cases. ADOPTED from those guides:
 *      - Skills live in $HOME/.agents/skills or .agents/skills, one
 *        directory per skill, SKILL.md + optional scripts/ + references/
 *        + assets/ + agents/openai.yaml.
 *      - A plugin is a manifest-driven bundle: `.codex-plugin/plugin.json`
 *        + skills/ (+ MCP servers + app connectors). Skills are the
 *        authoring format; plugins are the distribution format.
 *
 * ANDROID ADAPTATIONS (this file):
 * - There is no ~/.config/opencode on Android. The global scope is an
 *   app-private directory (default: <filesDir>/skills, see
 *   [SkillPluginManager.defaultGlobalSkillsDir]); repo scope is
 *   <workspace>/.codex/skills (plus .agents/skills and .claude/skills
 *   compat, mirroring OpenCode). Ancestor-directory search is
 *   deliberately NOT done: a phone workspace is self-contained, and
 *   walking up could pull skills from unrelated projects.
 * - SkillStore lives in dev.codex.android.skills (not data/ as the task
 *   brief said). It is used unmodified via the [SkillSink] adapter so
 *   this file stays unit-testable without an Android Context.
 * - No YAML library is in the dependency set, so frontmatter is parsed
 *   by the small hand-rolled parser in [SkillMarkdownParser], covering
 *   exactly the shapes the standard uses (scalars, quoted scalars,
 *   folded `>` / literal `|` blocks, block + flow lists, one level of
 *   nested `metadata:`). Anything else is ignored, as OpenCode ignores
 *   unknown fields.
 * - `allowed-tools` in frontmatter is parsed but NOT enforced by the
 *   agent loop (same as OpenCode's "parsed but not enforced"): the
 *   names are stored on the Skill and the loop resolves them at call
 *   time; an unknown tool name is inert.
 * - A skill without a description is rejected: description is the
 *   routing signal the agent uses to pick skills, and Skill.description
 *   is NOT NULL in the database. OpenCode tolerates description-less
 *   skills (unadvertised but loadable by id); we do not — the rejection
 *   is reported, never silent.
 * - OpenCode V2 does NOT enforce the id pattern, the name==directory
 *   match, or the 1024-char description cap. This manager DOES enforce
 *   the id pattern (Skill.id is a DB primary key and the $skill-name
 *   invocation key) and DOES require a description, but does NOT cap
 *   description length and treats frontmatter `name` as a display label
 *   (no directory-match requirement), matching V2.
 *
 * PLUGIN BUNDLE FORMAT ADOPTED (Codex CLI shape, OpenCode skill layout):
 *   <plugin-root>/
 *     .codex-plugin/plugin.json   # manifest: name, version, description,
 *                                 #   skills: ["skills/<id>", ...]
 *     skills/<skill-id>/SKILL.md # one dir per skill (+ scripts/,
 *                                 #   references/, assets/ beside it)
 *     hooks.json                  # optional; .codex/hooks.json shape
 *     mcp.json                    # optional; MCP server definitions
 *   A bare skills repo (no manifest) also imports: every ** /SKILL.md
 *   found is installed. The manifest only supplies name/version and a
 *   warning when a declared skill is missing from the repo.
 *
 * PENDING — RECORDED, NEVER CLAIMED ACTIVE:
 * - Hooks: hook definitions found in a bundle are parsed and returned
 *   verbatim in [PluginImportResult] with status RECORDED_PENDING.
 *   Nothing is executed: HookEngine (.codex/hooks.json, M6) does not
 *   exist in this build.
 * - MCP: MCP server definitions found in a bundle are parsed (server
 *   names only) and returned with status RECORDED_PENDING. Nothing is
 *   connected: McpHub (M4) does not exist in this build.
 *   The manager must never report a hook or MCP server as active.
 *
 * No API keys, no live model calls, no Coarena, BYOK only. Public
 * GitHub repos need no token; an optional BYOK GitHub PAT may be
 * supplied via [SkillPluginManager.githubToken] and is sent ONLY to
 * api.github.com (never to raw.githubusercontent.com), raising the API
 * rate limit from 60 to 5,000 requests/hour.
 */

/**
 * A parsed SKILL.md, ready to install as a [Skill].
 *
 * [id] is path-derived (directory / file name), never taken from
 * frontmatter. [displayName] is the frontmatter `name` when present,
 * otherwise [id]. [instructions] is the Markdown body after the
 * frontmatter, verbatim (trimmed).
 */
data class ParsedSkill(
    val id: String,
    val displayName: String,
    val description: String,
    val instructions: String,
    val toolNames: List<String>,
    val license: String?,
    val compatibility: List<String>,
    val autoInvoke: Boolean,
    val sourcePath: String,
    val extraFrontmatter: Map<String, String> = emptyMap(),
) {
    /**
     * Converts to the storage model. `enabled` follows [autoInvoke]:
     * a skill marked `opencode/autoinvoke: false` (or
     * `disable-model-invocation: true`) is installed DISABLED — present
     * in the store, hidden from the agent's skill list until the user
     * enables it — which is the closest the current [Skill] model gets
     * to OpenCode's "registered but not advertised" state.
     */
    fun toSkill(): Skill = Skill(
        id = id,
        name = displayName,
        description = description,
        instructions = instructions,
        toolNames = toolNames,
        version = 1,
        enabled = autoInvoke,
    )
}

/** Outcome of parsing one SKILL.md file. Rejections carry the reason. */
sealed class SkillParseOutcome {
    data class Valid(val skill: ParsedSkill) : SkillParseOutcome()
    data class Rejected(val path: String, val reason: String) : SkillParseOutcome()
}

/** Scope a skill was discovered in. Project beats global on equal id. */
enum class SkillScope { GLOBAL, PROJECT }

/** One discovered skill: the parsed content, where it came from. */
data class DiscoveredSkill(
    val skill: ParsedSkill,
    val scope: SkillScope,
    val file: File,
)

/** Result of [SkillPluginManager.discoverSkills]. Never throws. */
data class SkillDiscoveryResult(
    val skills: List<DiscoveredSkill>,
    val rejected: List<SkillParseOutcome.Rejected>,
    val overridden: List<DiscoveredSkill>,
)

/** Result of [SkillPluginManager.installDiscovered]. Never throws. */
data class SkillInstallResult(
    val installed: List<String>,
    val rejected: List<SkillParseOutcome.Rejected>,
    val overridden: List<DiscoveredSkill>,
    val failed: List<SkillParseOutcome.Rejected>,
)

/**
 * Where a plugin component stands. [RECORDED_PENDING] means the
 * definition was found in the bundle and recorded in the import result,
 * but its engine does not exist yet, so nothing is active.
 */
enum class PendingComponentStatus { NOT_PRESENT, RECORDED_PENDING }

/** Result of [SkillPluginManager.importFromGitHub]. Never throws. */
data class PluginImportResult(
    val repoUrl: String,
    val owner: String,
    val repo: String,
    val branch: String,
    val pluginName: String?,
    val pluginVersion: String?,
    val skillsInstalled: List<String>,
    val skillsRejected: List<SkillParseOutcome.Rejected>,
    val hooksFound: List<String>,
    val hooksStatus: PendingComponentStatus,
    val hooksRawJson: String?,
    val mcpServersFound: List<String>,
    val mcpStatus: PendingComponentStatus,
    val mcpRawJson: String?,
    val warnings: List<String>,
    val errors: List<String>,
) {
    /** True when the import ran without transport/API errors. */
    val succeeded: Boolean get() = errors.isEmpty()
}

/** Minimal write target for installed skills (see [SkillStoreSink]). */
interface SkillSink {
    suspend fun save(skill: Skill)
}

/**
 * Adapts the existing [SkillStore] to [SkillSink] without modifying it.
 * Production wiring uses this; tests use an in-memory [SkillSink].
 */
class SkillStoreSink(private val store: SkillStore) : SkillSink {
    override suspend fun save(skill: Skill) {
        store.save(skill)
    }
}

/**
 * Parses SKILL.md files in the standard format (OpenCode docs /
 * Anthropic Agent Skills spec): YAML frontmatter + Markdown body.
 *
 * Pure functions: no I/O, no Android APIs, never throws.
 * Only the frontmatter fields the standard defines are interpreted;
 * unknown fields are ignored (kept in [ParsedSkill.extraFrontmatter]).
 */
object SkillMarkdownParser {

    /** Portable skill-id rule, from the OpenCode docs. */
    val ID_PATTERN: Regex = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    const val MAX_ID_LENGTH: Int = 64

    private val KNOWN_SCALARS: Set<String> = setOf(
        "name", "description", "license", "disable-model-invocation",
    )

    /**
     * Parses [text] as the SKILL.md of the skill whose path-derived id
     * is [skillId]. [sourcePath] is used only in rejection reasons.
     */
    fun parseSkillFile(
        skillId: String,
        text: String,
        sourcePath: String,
    ): SkillParseOutcome {
        if (skillId.length !in 1..MAX_ID_LENGTH || !ID_PATTERN.matches(skillId)) {
            return SkillParseOutcome.Rejected(
                sourcePath,
                "skill id \"$skillId\" must match ^[a-z0-9]+(-[a-z0-9]+)*\$ " +
                    "and be 1-64 characters (OpenCode portable-id rule)",
            )
        }
        val lines = text.lines()
        val start = lines.indexOfFirst { it.isNotBlank() }
        if (start == -1 || lines[start].trim() != "---") {
            return SkillParseOutcome.Rejected(
                sourcePath,
                "missing YAML frontmatter: file must start with a --- block",
            )
        }
        var end = -1
        for (i in start + 1 until lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed == "---" || trimmed == "...") {
                end = i
                break
            }
        }
        if (end == -1) {
            return SkillParseOutcome.Rejected(
                sourcePath,
                "unterminated YAML frontmatter: no closing ---",
            )
        }
        val frontmatter = parseFrontmatter(lines.subList(start + 1, end))
        val description = frontmatter.scalars["description"]?.trim().orEmpty()
        if (description.isBlank()) {
            return SkillParseOutcome.Rejected(
                sourcePath,
                "missing required frontmatter field: description " +
                    "(the agent routes on it, and it cannot be empty)",
            )
        }
        val instructions = lines.subList(end + 1, lines.size).joinToString("\n").trim()
        if (instructions.isBlank()) {
            return SkillParseOutcome.Rejected(
                sourcePath,
                "empty instructions body: nothing after the frontmatter",
            )
        }
        val toolNames = distinctTools(
            frontmatter.lists["allowed-tools"]
                ?: frontmatter.lists["tools"]
                ?: splitCsv(frontmatter.scalars["allowed-tools"])
                ?: splitCsv(frontmatter.scalars["tools"])
                ?: emptyList(),
        )
        val compatibility = distinctTools(
            frontmatter.lists["compatibility"]
                ?: splitCsv(frontmatter.scalars["compatibility"])
                ?: emptyList(),
        )
        val autoInvoke = parseBoolean(frontmatter.metadata["opencode/autoinvoke"])
            ?: parseBoolean(frontmatter.scalars["disable-model-invocation"])?.not()
            ?: true
        val displayName = frontmatter.scalars["name"]?.trim()
            ?.takeIf { it.isNotBlank() } ?: skillId
        val extra = frontmatter.scalars.filterKeys { it !in KNOWN_SCALARS }
        return SkillParseOutcome.Valid(
            ParsedSkill(
                id = skillId,
                displayName = displayName,
                description = description,
                instructions = instructions,
                toolNames = toolNames,
                license = frontmatter.scalars["license"]?.trim()?.takeIf { it.isNotBlank() },
                compatibility = compatibility,
                autoInvoke = autoInvoke,
                sourcePath = sourcePath,
                extraFrontmatter = extra,
            ),
        )
    }

    private fun distinctTools(names: List<String>): List<String> =
        names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun splitCsv(raw: String?): List<String>? =
        raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

    private fun parseBoolean(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /**
     * Minimal YAML-subset parser for skill frontmatter. Handles:
     * `key: scalar`, `key: "quoted"`, `key: 'quoted'`, `key: [a, b]`
     * (flow list), `key: >` / `key: |` block scalars (with optional
     * `-`/`+` chomping marker), block sequences (`key:` then `- item`
     * lines), and one level of nested mapping under `metadata:`.
     * Comment lines (`# ...`) and blank lines are skipped. Any other
     * nested mapping is consumed and ignored.
     */
    private class Frontmatter {
        val scalars: MutableMap<String, String> = mutableMapOf()
        val lists: MutableMap<String, List<String>> = mutableMapOf()
        val metadata: MutableMap<String, String> = mutableMapOf()
    }

    private fun parseFrontmatter(lines: List<String>): Frontmatter {
        val out = Frontmatter()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank() || line.trimStart().startsWith("#")) {
                i++
                continue
            }
            val indent = line.length - line.trimStart().length
            if (indent > 0) {
                // Stray indented line (continuation of something already
                // consumed, or an unknown nested value): skip it.
                i++
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) {
                i++
                continue
            }
            val key = line.substring(0, colon).trim()
            val rawValue = line.substring(colon + 1).trim()
            if (rawValue.isEmpty()) {
                // Empty value: a block sequence, a nested map, or nothing.
                val next = nextNonBlank(lines, i + 1)
                if (next == -1) {
                    i++
                    continue
                }
                val nextLine = lines[next]
                val nextIndent = nextLine.length - nextLine.trimStart().length
                val nextTrimmed = nextLine.trimStart()
                if (nextIndent > 0 && (nextTrimmed.startsWith("- ") || nextTrimmed == "-")) {
                    val items = mutableListOf<String>()
                    var j = next
                    while (j < lines.size) {
                        val itemLine = lines[j]
                        if (itemLine.isBlank()) {
                            j++
                            continue
                        }
                        val itemIndent = itemLine.length - itemLine.trimStart().length
                        val itemTrimmed = itemLine.trimStart()
                        if (itemIndent == 0 || !itemTrimmed.startsWith("-")) break
                        val item = itemTrimmed.removePrefix("-").trim()
                        if (item.isNotEmpty()) items += parseScalar(item)
                        j++
                    }
                    out.lists[key] = items
                    i = j
                    continue
                }
                if (nextIndent > 0 && nextTrimmed.contains(':')) {
                    // Nested mapping (e.g. metadata:). Only `metadata:`
                    // is interpreted; anything else is consumed + ignored.
                    var j = next
                    while (j < lines.size) {
                        val nestedLine = lines[j]
                        if (nestedLine.isBlank()) {
                            j++
                            continue
                        }
                        val nestedIndent = nestedLine.length - nestedLine.trimStart().length
                        if (nestedIndent == 0) break
                        val nestedTrimmed = nestedLine.trimStart()
                        val nestedColon = nestedTrimmed.indexOf(':')
                        if (nestedColon > 0) {
                            val nestedKey = nestedTrimmed.substring(0, nestedColon).trim()
                            val nestedValue = nestedTrimmed.substring(nestedColon + 1).trim()
                            if (key == "metadata" && nestedValue.isNotEmpty()) {
                                out.metadata[nestedKey] = parseScalar(nestedValue)
                            }
                        }
                        j++
                    }
                    i = j
                    continue
                }
                i++
                continue
            }
            // A block scalar header is exactly `>` or `|` plus an
            // optional chomping marker — a plain value that merely
            // starts with `>` (e.g. `description: > awesome`) is not.
            if (rawValue.matches(Regex("^[>|][+-]?$"))) {
                val folded = rawValue.startsWith(">")
                val (block, nextIndex) = readBlockScalar(lines, i + 1, folded)
                out.scalars[key] = block
                i = nextIndex
                continue
            }
            if (rawValue.startsWith("[") && rawValue.endsWith("]")) {
                out.lists[key] = rawValue.removePrefix("[").removeSuffix("]")
                    .split(",").map { parseScalar(it.trim()) }
                    .filter { it.isNotEmpty() }
            } else {
                out.scalars[key] = parseScalar(rawValue)
            }
            i++
        }
        return out
    }

    private fun nextNonBlank(lines: List<String>, from: Int): Int {
        for (i in from until lines.size) {
            if (lines[i].isNotBlank()) return i
        }
        return -1
    }

    /**
     * Reads an indented block scalar starting at [from]. Returns the
     * block text and the index of the first line after the block.
     * Folded (`>`): lines joined with spaces, blank lines become a
     * newline. Literal (`|`): lines joined with newlines.
     */
    private fun readBlockScalar(
        lines: List<String>,
        from: Int,
        folded: Boolean,
    ): Pair<String, Int> {
        var indent: Int? = null
        var j = from
        val collected = mutableListOf<String>()
        while (j < lines.size) {
            val line = lines[j]
            if (line.isBlank()) {
                collected += ""
                j++
                continue
            }
            val lineIndent = line.length - line.trimStart().length
            if (lineIndent == 0) break
            val currentIndent = indent ?: lineIndent
            if (indent == null) indent = lineIndent
            if (lineIndent < currentIndent) break
            collected += line.substring(minOf(currentIndent, line.length))
            j++
        }
        // Drop trailing blank lines collected past the block end.
        while (collected.isNotEmpty() && collected.last().isBlank()) {
            collected.removeAt(collected.lastIndex)
        }
        val text = if (folded) {
            val sb = StringBuilder()
            for ((index, piece) in collected.withIndex()) {
                if (piece.isBlank()) {
                    sb.append('\n')
                } else {
                    if (index > 0 && collected[index - 1].isNotBlank()) sb.append(' ')
                    sb.append(piece)
                }
            }
            sb.toString()
        } else {
            collected.joinToString("\n")
        }
        return text to j
    }

    /** Parses one YAML scalar: quoted, or plain (trailing comment cut). */
    private fun parseScalar(raw: String): String {
        val value = raw.trim()
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return unescapeDoubleQuoted(value.substring(1, value.length - 1))
        }
        if (value.length >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length - 1).replace("''", "'")
        }
        val commentAt = value.indexOf(" #")
        return if (commentAt >= 0) value.substring(0, commentAt).trim() else value
    }

    private fun unescapeDoubleQuoted(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    else -> sb.append(raw[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}

/** A GitHub repository reference parsed from a repo URL. */
data class GitHubRepoRef(
    val owner: String,
    val repo: String,
    /** Branch from a /tree/<branch>/ URL, or null to use the default. */
    val branch: String?,
    /** Subdirectory inside the repo ("" = repo root). */
    val subpath: String,
)

/**
 * Discovers SKILL.md files (global + repo scopes), parses them, and
 * installs them into the [SkillStore] via a [SkillSink]. Imports whole
 * plugin bundles from public GitHub repos.
 *
 * Discovery and parsing never throw; import reports failures in its
 * result instead of throwing. All file I/O runs on the calling thread
 * for discovery (small local reads); network runs on Dispatchers.IO.
 *
 * @param globalSkillsDir app-private global skills dir
 *   (default: <filesDir>/skills — see [defaultGlobalSkillsDir]).
 * @param skillSink where installed skills are saved (production:
 *   [SkillStoreSink] wrapping the real [SkillStore]).
 * @param httpClient injectable for tests (fake interceptor, no network).
 * @param githubToken optional BYOK GitHub PAT supplier; sent ONLY to
 *   api.github.com, never to raw.githubusercontent.com. May return null.
 */
class SkillPluginManager(
    private val globalSkillsDir: File,
    private val skillSink: SkillSink,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val githubToken: (() -> String?)? = null,
) {
    private val json: Json = Json { ignoreUnknownKeys = true }

    /**
     * Discovers skills from the global dir and, when [workspaceRoot] is
     * given, from `<root>/.codex/skills` (+ `.agents/skills` and
     * `.claude/skills` compat). Sources are scanned low-to-high
     * precedence, so a project skill with the same id replaces a global
     * one (the replaced definition is listed in [overridden]).
     * Never throws.
     */
    fun discoverSkills(workspaceRoot: File? = null): SkillDiscoveryResult {
        val sources = mutableListOf<Pair<SkillScope, File>>()
        sources += SkillScope.GLOBAL to globalSkillsDir
        if (workspaceRoot != null) {
            sources += SkillScope.PROJECT to File(workspaceRoot, ".claude/skills")
            sources += SkillScope.PROJECT to File(workspaceRoot, ".agents/skills")
            sources += SkillScope.PROJECT to File(workspaceRoot, ".codex/skills")
        }
        val byId = LinkedHashMap<String, DiscoveredSkill>()
        val rejected = mutableListOf<SkillParseOutcome.Rejected>()
        val overridden = mutableListOf<DiscoveredSkill>()
        for ((scope, dir) in sources) {
            for (found in scanSource(dir, scope, rejected)) {
                val previous = byId.put(found.skill.id, found)
                if (previous != null) overridden += previous
            }
        }
        return SkillDiscoveryResult(
            skills = byId.values.toList(),
            rejected = rejected,
            overridden = overridden,
        )
    }

    /**
     * Discovers and installs every winning skill into the [SkillSink]
     * (upsert by id — installing a newer copy of a skill replaces the
     * old one). Returns installed ids plus everything that was
     * rejected, overridden, or failed to save. Never throws.
     */
    suspend fun installDiscovered(workspaceRoot: File? = null): SkillInstallResult {
        val discovery = discoverSkills(workspaceRoot)
        val installed = mutableListOf<String>()
        val failed = mutableListOf<SkillParseOutcome.Rejected>()
        for (found in discovery.skills) {
            try {
                skillSink.save(found.skill.toSkill())
                installed += found.skill.id
            } catch (e: Exception) {
                failed += SkillParseOutcome.Rejected(
                    found.file.path,
                    "install failed: ${e.message}",
                )
            }
        }
        return SkillInstallResult(
            installed = installed,
            rejected = discovery.rejected,
            overridden = discovery.overridden,
            failed = failed,
        )
    }

    /**
     * Imports a plugin bundle (or a bare skills repo) from a public
     * GitHub repo URL — see [parseGitHubRepoUrl] for accepted shapes.
     *
     * Flow: repo lookup (learns the default branch) -> recursive git
     * tree (finds SKILL.md files, the plugin manifest, hooks.json and
     * mcp.json) -> raw download of each file -> parse + install skills.
     * Hook and MCP definitions are RECORDED ONLY (see
     * [PendingComponentStatus]); nothing is executed or connected.
     * Never throws; failures land in [PluginImportResult.errors].
     */
    suspend fun importFromGitHub(repoUrl: String): PluginImportResult =
        withContext(Dispatchers.IO) {
            val ref = parseGitHubRepoUrl(repoUrl)
                ?: return@withContext emptyResult(repoUrl).copy(
                    errors = listOf(
                        "not a GitHub repository URL " +
                            "(expected https://github.com/<owner>/<repo>)",
                    ),
                )
            importFromGitHub(ref, repoUrl)
        }

    private suspend fun importFromGitHub(
        ref: GitHubRepoRef,
        repoUrl: String,
    ): PluginImportResult {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val base = emptyResult(repoUrl).copy(owner = ref.owner, repo = ref.repo)

        // 1. Repo lookup: validates the repo exists, learns default branch.
        val repoResp = httpGet(
            "https://api.github.com/repos/${ref.owner}/${ref.repo}",
            sendToken = true,
        )
        if (repoResp.code != 200 || repoResp.body == null) {
            return base.copy(
                errors = listOf(
                    "GitHub API rejected the repository lookup " +
                        "(HTTP ${repoResp.code}); check the owner/repo " +
                        "and the network connection",
                ),
            )
        }
        val defaultBranch = runCatching {
            json.parseToJsonElement(repoResp.body)
                .jsonObject["default_branch"]?.jsonPrimitive?.contentOrNull
        }.getOrNull().orEmpty()

        // 2. Tree listing. Branch names may contain "/" (e.g. feature/x),
        // which a /tree/<branch>/<path> URL cannot disambiguate from a
        // subpath, so on a 404 retry once treating the whole tail after
        // /tree/ as a path under the default branch.
        val candidates = mutableListOf<Pair<String, String>>()
        if (ref.branch != null) candidates += ref.branch to ref.subpath
        val fallbackSubpath = listOfNotNull(ref.branch, ref.subpath)
            .filter { it.isNotEmpty() }.joinToString("/")
        if (defaultBranch.isNotBlank() && fallbackSubpath.isNotEmpty()) {
            candidates += defaultBranch to fallbackSubpath
        }
        if (candidates.isEmpty()) candidates += defaultBranch.ifBlank { "main" } to ""
        var treeBody: String? = null
        var branch = ""
        var subpath = ""
        for ((candidateBranch, candidateSubpath) in candidates) {
            val treeResp = httpGet(
                "https://api.github.com/repos/${ref.owner}/${ref.repo}" +
                    "/git/trees/$candidateBranch?recursive=1",
                sendToken = true,
            )
            if (treeResp.code == 200 && treeResp.body != null) {
                treeBody = treeResp.body
                branch = candidateBranch
                subpath = candidateSubpath
                break
            }
            if (treeResp.code != 404) {
                return base.copy(
                    errors = listOf(
                        "GitHub API rejected the file listing " +
                            "(HTTP ${treeResp.code})",
                    ),
                )
            }
        }
        if (treeBody == null) {
            return base.copy(
                errors = listOf(
                    "could not list files for ${ref.owner}/${ref.repo} " +
                        "(branch \"${ref.branch}\" not found)",
                ),
            )
        }
        val tree = runCatching {
            json.parseToJsonElement(treeBody).jsonObject
        }.getOrNull()
        if (tree == null) {
            return base.copy(errors = listOf("GitHub API returned an unreadable file listing"))
        }
        if (tree["truncated"]?.jsonPrimitive?.booleanOrNull == true) {
            warnings += "repository file listing was truncated by GitHub " +
                "(very large repo); some skills may have been missed"
        }
        val paths = tree["tree"]?.jsonArray?.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (type != "blob") return@mapNotNull null
            obj["path"]?.jsonPrimitive?.contentOrNull
        }.orEmpty().filter { path ->
            subpath.isEmpty() || path == subpath || path.startsWith("$subpath/")
        }.toSet()
        fun under(name: String): String =
            if (subpath.isEmpty()) name else "$subpath/$name"

        // 3. Plugin manifest (optional): name/version + declared skills.
        var pluginName: String? = null
        var pluginVersion: String? = null
        var declaredSkills: List<String> = emptyList()
        val manifestPath = listOf(
            under(".codex-plugin/plugin.json"),
            under("plugin.json"),
        ).firstOrNull { it in paths }
        if (manifestPath != null) {
            val manifestBody = downloadRaw(ref.owner, ref.repo, branch, manifestPath)
            val manifest = manifestBody?.let {
                runCatching { json.decodeFromString<PluginManifest>(it) }.getOrNull()
            }
            if (manifest == null) {
                warnings += "plugin manifest $manifestPath could not be " +
                    "parsed; importing skills without manifest metadata"
            } else {
                pluginName = manifest.name.takeIf { it.isNotBlank() }
                pluginVersion = manifest.version.takeIf { it.isNotBlank() }
                declaredSkills = manifest.skills
            }
        }

        // 4. Skills: every **/SKILL.md under the import root.
        val skillPaths = paths.filter { path ->
            path == "SKILL.md" || path.endsWith("/SKILL.md")
        }.sorted()
        val installed = mutableListOf<String>()
        val rejected = mutableListOf<SkillParseOutcome.Rejected>()
        for (skillPath in skillPaths) {
            val parentDir = skillPath.substringBeforeLast("/", missingDelimiterValue = "")
            val skillId = parentDir.substringAfterLast("/")
            if (skillId.isEmpty()) {
                rejected += SkillParseOutcome.Rejected(
                    skillPath,
                    "SKILL.md at the repository root has no directory " +
                        "name to derive an id from",
                )
                continue
            }
            val raw = downloadRaw(ref.owner, ref.repo, branch, skillPath)
            if (raw == null) {
                rejected += SkillParseOutcome.Rejected(
                    skillPath,
                    "download failed from raw.githubusercontent.com",
                )
                continue
            }
            when (val parsed = SkillMarkdownParser.parseSkillFile(skillId, raw, skillPath)) {
                is SkillParseOutcome.Valid -> {
                    try {
                        skillSink.save(parsed.skill.toSkill())
                        installed += parsed.skill.id
                    } catch (e: Exception) {
                        rejected += SkillParseOutcome.Rejected(
                            skillPath,
                            "install failed: ${e.message}",
                        )
                    }
                }
                is SkillParseOutcome.Rejected -> rejected += parsed
            }
        }
        for (declared in declaredSkills) {
            val declaredPath = declared.trim().removeSuffix("/")
            val present = skillPaths.any { skillPath ->
                skillPath == "$declaredPath/SKILL.md" ||
                    skillPath == declaredPath ||
                    skillPath.removeSuffix("/SKILL.md") == declaredPath
            }
            if (!present) {
                warnings += "plugin manifest declares skill " +
                    "\"$declared\" but no SKILL.md was found for it"
            }
        }

        // 5. Hooks + MCP: recorded only — their engines do not exist yet.
        val (hookNames, hooksRaw) = recordJsonConfig(
            paths = paths,
            candidates = listOf(under("hooks.json"), under(".codex/hooks.json")),
            branch = branch,
            ref = ref,
            topLevelObjectKey = "hooks",
        )
        val (mcpNames, mcpRaw) = recordJsonConfig(
            paths = paths,
            candidates = listOf(under("mcp.json"), under(".mcp.json")),
            branch = branch,
            ref = ref,
            topLevelObjectKey = "mcpServers",
        )

        return PluginImportResult(
            repoUrl = repoUrl,
            owner = ref.owner,
            repo = ref.repo,
            branch = branch,
            pluginName = pluginName,
            pluginVersion = pluginVersion,
            skillsInstalled = installed,
            skillsRejected = rejected,
            hooksFound = hookNames,
            hooksStatus = if (hooksRaw != null) {
                PendingComponentStatus.RECORDED_PENDING
            } else {
                PendingComponentStatus.NOT_PRESENT
            },
            hooksRawJson = hooksRaw,
            mcpServersFound = mcpNames,
            mcpStatus = if (mcpRaw != null) {
                PendingComponentStatus.RECORDED_PENDING
            } else {
                PendingComponentStatus.NOT_PRESENT
            },
            mcpRawJson = mcpRaw,
            warnings = warnings,
            errors = errors,
        )
    }

    /**
     * Downloads a JSON config file from the bundle (hooks.json /
     * mcp.json) and records it WITHOUT activating anything. Returns the
     * entry names found (keys of the top-level object, or of the
     * [topLevelObjectKey] object when present) plus the raw text.
     */
    private fun recordJsonConfig(
        paths: Set<String>,
        candidates: List<String>,
        branch: String,
        ref: GitHubRepoRef,
        topLevelObjectKey: String,
    ): Pair<List<String>, String?> {
        val path = candidates.firstOrNull { it in paths } ?: return emptyList<String>() to null
        val raw = downloadRaw(ref.owner, ref.repo, branch, path) ?: return emptyList<String>() to null
        val names = runCatching {
            val obj = json.parseToJsonElement(raw).jsonObject
            val inner = obj[topLevelObjectKey]?.jsonObject ?: obj
            inner.keys.sorted()
        }.getOrNull().orEmpty()
        return names to raw
    }

    /**
     * Scans one skills source directory (OpenCode rules): a directory
     * containing SKILL.md is one skill and is not descended into; flat
     * *.md files count only at the root; hidden entries and
     * node_modules are skipped; search stops six directories below the
     * root. A bare SKILL.md at the root itself is rejected (no id).
     * Never throws; unreadable files are reported as rejections.
     */
    private fun scanSource(
        dir: File,
        scope: SkillScope,
        rejected: MutableList<SkillParseOutcome.Rejected>,
    ): List<DiscoveredSkill> {
        if (!dir.isDirectory) return emptyList()
        if (File(dir, "SKILL.md").isFile) {
            rejected += SkillParseOutcome.Rejected(
                File(dir, "SKILL.md").path,
                "SKILL.md at a skills root has no directory name " +
                    "to derive an id from",
            )
        }
        val candidates = mutableListOf<Pair<String, File>>()
        val dirFormIds = mutableSetOf<String>()
        dir.walkTopDown()
            .onEnter { child ->
                if (child != dir &&
                    (child.name.startsWith(".") || child.name == "node_modules")
                ) {
                    return@onEnter false
                }
                if (child != dir && File(child, "SKILL.md").isFile) {
                    candidates += child.name to File(child, "SKILL.md")
                    dirFormIds += child.name
                    return@onEnter false
                }
                true
            }
            .maxDepth(7) // root(0) + six directories below it + the file
            .forEach { entry ->
                if (entry.isFile && entry.parentFile == dir &&
                    entry.name.endsWith(".md") && entry.name != "SKILL.md" &&
                    !entry.name.startsWith(".")
                ) {
                    val id = entry.nameWithoutExtension
                    if (id !in dirFormIds) candidates += id to entry
                }
            }
        val found = mutableListOf<DiscoveredSkill>()
        val seenIds = mutableSetOf<String>()
        for ((id, file) in candidates.sortedBy { it.second.path }) {
            if (!seenIds.add(id)) {
                rejected += SkillParseOutcome.Rejected(
                    file.path,
                    "duplicate skill id \"$id\" in the same source; " +
                        "the first definition wins",
                )
                continue
            }
            val text = runCatching { file.readText() }.getOrNull()
            if (text == null) {
                rejected += SkillParseOutcome.Rejected(file.path, "could not read file")
                continue
            }
            when (val parsed = SkillMarkdownParser.parseSkillFile(id, text, file.path)) {
                is SkillParseOutcome.Valid -> found += DiscoveredSkill(parsed.skill, scope, file)
                is SkillParseOutcome.Rejected -> rejected += parsed
            }
        }
        return found
    }

    private data class HttpResult(val code: Int, val body: String?)

    /**
     * Plain GET. When [sendToken] is true and [githubToken] supplies a
     * non-blank PAT, it is sent as a Bearer token — only ever to
     * api.github.com (callers must not pass sendToken=true for other
     * hosts). Returns code -1 on transport failure.
     */
    private fun httpGet(url: String, sendToken: Boolean): HttpResult {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
        if (sendToken) {
            val token = runCatching { githubToken?.invoke() }.getOrNull()
                ?.takeIf { it.isNotBlank() }
            if (token != null) {
                requestBuilder.header("Authorization", "Bearer $token")
            }
        }
        return try {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                HttpResult(response.code, response.body?.string())
            }
        } catch (e: IOException) {
            HttpResult(-1, null)
        }
    }

    /** Downloads one file via raw.githubusercontent.com (public repos need no token). */
    private fun downloadRaw(owner: String, repo: String, branch: String, path: String): String? {
        // Built with addPathSegment so spaces etc. in paths are encoded.
        val encoded = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("raw.githubusercontent.com")
            .addPathSegment(owner)
            .addPathSegment(repo)
            .addPathSegment(branch)
            .apply { path.split("/").forEach { addPathSegment(it) } }
            .build()
            .toString()
        val result = httpGet(encoded, sendToken = false)
        return if (result.code == 200) result.body else null
    }

    private fun emptyResult(repoUrl: String): PluginImportResult = PluginImportResult(
        repoUrl = repoUrl,
        owner = "",
        repo = "",
        branch = "",
        pluginName = null,
        pluginVersion = null,
        skillsInstalled = emptyList(),
        skillsRejected = emptyList(),
        hooksFound = emptyList(),
        hooksStatus = PendingComponentStatus.NOT_PRESENT,
        hooksRawJson = null,
        mcpServersFound = emptyList(),
        mcpStatus = PendingComponentStatus.NOT_PRESENT,
        mcpRawJson = null,
        warnings = emptyList(),
        errors = emptyList(),
    )

    companion object {
        private val OWNER_PATTERN: Regex = Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$")
        private val REPO_PATTERN: Regex = Regex("^[A-Za-z0-9_.-]+$")

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Default global skills dir: <filesDir>/skills. */
        fun defaultGlobalSkillsDir(context: Context): File =
            File(context.filesDir, "skills")

        /**
         * Production wiring: global dir + real [SkillStore] via
         * [SkillStoreSink]. The [githubToken] supplier (BYOK GitHub PAT)
         * is wired by the caller from KeyStore; pass null for anonymous
         * (60 GitHub API requests/hour) access.
         */
        fun create(
            context: Context,
            globalSkillsDir: File = defaultGlobalSkillsDir(context),
            httpClient: OkHttpClient = defaultHttpClient(),
            githubToken: (() -> String?)? = null,
        ): SkillPluginManager = SkillPluginManager(
            globalSkillsDir = globalSkillsDir,
            skillSink = SkillStoreSink(SkillStore(context)),
            httpClient = httpClient,
            githubToken = githubToken,
        )

        /**
         * Parses a GitHub repo URL. Accepted:
         * - https://github.com/<owner>/<repo> (trailing `/` / `.git` ok)
         * - https://github.com/<owner>/<repo>/tree/<branch>[/<subpath>]
         * Returns null for anything else. LIMITATION: a branch name
         * containing "/" cannot be told apart from a subpath in a
         * /tree/ URL — the importer retries with the default branch
         * when the first guess 404s (see [SkillPluginManager]).
         */
        fun parseGitHubRepoUrl(url: String): GitHubRepoRef? {
            val clean = url.trim().removeSuffix("/").removeSuffix(".git")
            val afterHost = when {
                clean.startsWith("https://github.com/") ->
                    clean.removePrefix("https://github.com/")
                clean.startsWith("http://github.com/") ->
                    clean.removePrefix("http://github.com/")
                else -> return null
            }
            val parts = afterHost.split("/").filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            val owner = parts[0]
            val repo = parts[1]
            if (!OWNER_PATTERN.matches(owner) || !REPO_PATTERN.matches(repo)) return null
            if (parts.size == 2) return GitHubRepoRef(owner, repo, null, "")
            if (parts[2] != "tree" || parts.size < 4) return null
            return GitHubRepoRef(
                owner = owner,
                repo = repo,
                branch = parts[3],
                subpath = parts.drop(4).joinToString("/"),
            )
        }
    }
}

/**
 * Plugin manifest (`.codex-plugin/plugin.json`, Codex CLI shape).
 * Only name/version/description/skills are interpreted; any hooks or
 * MCP server entries are recorded raw by the importer, never wired up.
 */
@Serializable
data class PluginManifest(
    val name: String = "",
    val version: String = "",
    val description: String = "",
    val skills: List<String> = emptyList(),
)
