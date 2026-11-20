package dev.codex.android.agent

/**
 * Model-adaptive edit-strategy router (M1-B).
 *
 * Pure Kotlin logic: no Android APIs, no I/O, no network, and no model calls.
 * Given a model id (plus optional provider id / catalog flags) it decides which
 * file-editing strategy the agent should offer that model. The caller is
 * responsible for only exposing the returned tools to the model once the
 * matching [dev.codex.android.tools.Tool] implementations exist and are
 * registered (see the tool-name note below).
 *
 * ## Strategies
 *
 * * [EditStrategy.APPLY_PATCH] — a single `apply_patch` tool. GPT / Codex-family
 *   models are specifically trained to emit this patch grammar, so for them one
 *   patch call replaces the edit + write pair.
 * * [EditStrategy.EDIT_WRITE] — the `edit` + `write_file` tools (exact-string
 *   replacement / whole-file write). This is the safe default for every other
 *   model family and for any model the router does not recognise.
 *
 * ## Detection rules (in precedence order)
 *
 * 1. Explicit catalog data wins. If [routeFor]'s `supportsApplyPatch` argument
 *    is non-null, its value decides outright — a catalog entry saying a model
 *    supports (or does not support) `apply_patch` outranks name heuristics.
 *    The same applies to a non-null result from the optional `catalogLookup`
 *    lambda (the hook the parallel ModelCatalog workstream plugs into; it
 *    receives the raw model id and returns `true` / `false` / `null` for
 *    "supports" / "does not support" / "unknown"). `supportsApplyPatch`
 *    outranks `catalogLookup` when both are given.
 * 2. GPT / Codex family, by model id (case-insensitive, leading/trailing
 *    whitespace ignored):
 *    * id contains `gpt` (e.g. `gpt-4o`, `gpt-5`, `gpt-5.1-codex`,
 *      `chatgpt-4o-latest`), or
 *    * id contains `codex` (e.g. `codex-mini-latest`, `gpt-5.1-codex`), or
 *    * id is an OpenAI o-series reasoning id: the id, or any `/`- or
 *      `:`-separated segment of it, starts with `o1`, `o3`, `o4` or `o5`
 *      (e.g. `o1`, `o1-preview`, `o3-mini`, `openai/o3-mini`). There is no
 *      `o2` model, and unknown future `o6`-style ids are deliberately NOT
 *      matched — they fall through to the safe default until the table is
 *      updated.
 *    → [EditStrategy.APPLY_PATCH].
 * 3. Everything else → [EditStrategy.EDIT_WRITE]. This includes blank ids and
 *    unrecognised ids: an unknown model must never be handed a patch grammar
 *    it was not trained to produce.
 *
 * `providerId` is accepted (and passed through to callers that need it) but
 * today does not by itself flip the strategy: an unrecognised model from
 * provider `openai` still falls back to [EditStrategy.EDIT_WRITE], because
 * only the catalog flag — "provider is `openai` with a codex flag" in the
 * M1-B spec, i.e. `supportsApplyPatch == true` — is a positive signal, not
 * the provider name alone. `providerId` stays in the signature so callers
 * do not need to change when catalog-driven routing lands.
 *
 * ## Open-source basis (behaviour only — no code copied)
 *
 * * OpenCode (MIT): its native editing tools are string replacement
 *   (`edit` / `write`), but it added an `apply_patch` tool specifically for
 *   GPT / Codex models, because pointing GPT-5.x models at the string-
 *   replacement tools measurably hurt performance. The harness detects the
 *   active model family and presents the appropriate tool set — exactly the
 *   routing this file does.
 * * OpenAI Codex CLI (Apache-2.0): `apply_patch` is the CLI's sole editing
 *   mechanism, emitting diffs in the purpose-built V4A grammar
 *   (`*** Begin Patch` / `*** Add File:` / `*** Update File:` / `*** Move to:` /
 *   `*** Delete File:` / `*** End Patch`, hunks anchored by `@@` context lines
 *   instead of line numbers). GPT-5.x / Codex models are trained on this
 *   grammar; the Responses API exposes it as a first-class tool type.
 *
 * ## Tool-name note — read before wiring this into the agent loop
 *
 * The tools named by [requiredTools] now exist and are registered per
 * chat thread in `ChatViewModel.policyGatedRegistry` (via
 * `registerFileTools`): `edit`, `apply_patch` and `write_file`. They
 * are NOT in `ToolRegistry.withBuiltins` — the file tools need the
 * chat's shared `WorkspaceFileTools` instance (snapshots live per
 * instance), so automations, sub-agents and the daemon do not offer
 * them. Do not offer these tool names to a model until they exist and
 * are registered.
 *
 * ## V4A honesty note
 *
 * OpenAI's Codex CLI `apply_patch` uses the purpose-built V4A grammar
 * (`*** Begin Patch` / `*** Add File:` / `*** Update File:` / hunks
 * anchored by `@@` context lines). This build's `apply_patch` tool
 * does NOT parse V4A: it applies strict unified diffs (`---`/`+++`
 * headers, `@@` hunks). GPT / Codex-family models are trained on V4A,
 * so the tool description must tell the model the unified-diff format
 * explicitly — and a model that emits a V4A patch anyway will get a
 * clear parse error, not a silent mis-apply. A future V4A parser
 * would be a new `UnifiedDiffApplier` mode, not a comment change.
 * Related: `WorkspaceFileTools.applyPatch()` is a plain
 * search-and-replace snippet helper, NOT the V4A envelope.
 */
enum class EditStrategy {
    /** Single `apply_patch` tool (GPT / Codex-family models). */
    APPLY_PATCH,

    /** `edit` + `write_file` tools (every other model; safe default). */
    EDIT_WRITE,
}

object ToolRouter {

    /** Tool name for [EditStrategy.APPLY_PATCH]. */
    const val APPLY_PATCH_TOOL: String = "apply_patch"

    /** Tool names for [EditStrategy.EDIT_WRITE]. */
    val EDIT_WRITE_TOOLS: List<String> = listOf("edit", "write_file")

    /** Model-id prefixes of the OpenAI o-series reasoning models. */
    private val REASONING_PREFIXES: List<String> = listOf("o1", "o3", "o4", "o5")

    /**
     * Decides the edit strategy for [modelId].
     *
     * @param modelId the model identifier as configured by the user, e.g.
     *   `"gpt-5"`, `"claude-sonnet-4"`, `"openai/o3-mini"`. Blank or
     *   unrecognised ids route to [EditStrategy.EDIT_WRITE].
     * @param providerId optional provider identifier, e.g. `"openai"`.
     *   Accepted for pass-through / future catalog use; it does not by
     *   itself change the result (see class docs).
     * @param supportsApplyPatch optional explicit catalog flag for this
     *   model: `true` forces [EditStrategy.APPLY_PATCH], `false` forces
     *   [EditStrategy.EDIT_WRITE]. When non-null it outranks everything
     *   else, including the model-id heuristics.
     * @param catalogLookup optional lightweight metadata lookup, e.g. backed
     *   by ModelCatalog: given the raw [modelId] it returns `true` /
     *   `false` when the catalog knows whether the model supports
     *   `apply_patch`, or `null` when it does not know (fall through to
     *   the heuristics). Outranked by [supportsApplyPatch].
     */
    fun routeFor(
        modelId: String,
        providerId: String? = null,
        supportsApplyPatch: Boolean? = null,
        catalogLookup: ((String) -> Boolean?)? = null,
    ): EditStrategy {
        // 1. Explicit catalog data outranks name heuristics.
        if (supportsApplyPatch != null) {
            return if (supportsApplyPatch) EditStrategy.APPLY_PATCH else EditStrategy.EDIT_WRITE
        }
        val catalogResult = catalogLookup?.invoke(modelId)
        if (catalogResult != null) {
            return if (catalogResult) EditStrategy.APPLY_PATCH else EditStrategy.EDIT_WRITE
        }

        // 2. GPT / Codex family heuristics on the model id.
        // providerId is intentionally not consulted here: see class docs.
        // (It is a public-API parameter, so the compiler does not flag it
        // as unused; it exists for pass-through / future catalog use.)
        val id = modelId.trim()
        if (id.isEmpty()) return EditStrategy.EDIT_WRITE
        if (id.contains("gpt", ignoreCase = true)) return EditStrategy.APPLY_PATCH
        if (id.contains("codex", ignoreCase = true)) return EditStrategy.APPLY_PATCH
        if (isReasoningModelId(id)) return EditStrategy.APPLY_PATCH

        // 3. Safe default: unknown / everything else.
        return EditStrategy.EDIT_WRITE
    }

    /**
     * The tool names the agent must expose for [strategy], in the order the
     * model should prefer them.
     *
     * * [EditStrategy.APPLY_PATCH] → `["apply_patch"]`
     * * [EditStrategy.EDIT_WRITE] → `["edit", "write_file"]`
     *
     * These are the intended canonical names (see the tool-name note in the
     * class docs): the matching `Tool` implementations do not exist in the
     * registry yet, so callers must not present these names to a model
     * until they do.
     */
    fun requiredTools(strategy: EditStrategy): List<String> = when (strategy) {
        EditStrategy.APPLY_PATCH -> listOf(APPLY_PATCH_TOOL)
        EditStrategy.EDIT_WRITE -> EDIT_WRITE_TOOLS
    }

    /**
     * True when [id] (already trimmed) is an OpenAI o-series reasoning id:
     * the id itself, or any `/`- or `:`-separated segment of it (for
     * provider-prefixed ids such as `openai/o3-mini`), starts with `o1`,
     * `o3`, `o4` or `o5`. Case-insensitive.
     */
    private fun isReasoningModelId(id: String): Boolean {
        return id.split('/', ':').any { segment ->
            REASONING_PREFIXES.any { prefix -> segment.startsWith(prefix, ignoreCase = true) }
        }
    }
}
