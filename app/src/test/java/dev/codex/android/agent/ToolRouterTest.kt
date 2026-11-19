package dev.codex.android.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for [ToolRouter].
 *
 * Pure-function tests: no Android APIs, no I/O, no network, no model calls.
 * The model-id table below is the contract — every row is one routing
 * decision, and any change to the detection rules must update this table
 * first (see the rule table in [ToolRouter]'s docs).
 */
class ToolRouterTest {

    // --- GPT / Codex family -> APPLY_PATCH ---------------------------------

    @Test
    fun gptModelsRouteToApplyPatch() {
        val ids = listOf(
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4.1",
            "gpt-5",
            "gpt-5.1-codex",
            "chatgpt-4o-latest",
        )
        for (id in ids) {
            assertEquals("model $id", EditStrategy.APPLY_PATCH, ToolRouter.routeFor(id))
        }
    }

    @Test
    fun codexModelsRouteToApplyPatch() {
        val ids = listOf(
            "codex-mini-latest",
            "codex-1",
            "gpt-5.1-codex",
        )
        for (id in ids) {
            assertEquals("model $id", EditStrategy.APPLY_PATCH, ToolRouter.routeFor(id))
        }
    }

    @Test
    fun reasoningModelsRouteToApplyPatch() {
        val ids = listOf(
            "o1",
            "o1-preview",
            "o1-mini",
            "o3",
            "o3-mini",
            "o4-mini",
            "o5",
            "openai/o3-mini",
            "openai/o1",
        )
        for (id in ids) {
            assertEquals("model $id", EditStrategy.APPLY_PATCH, ToolRouter.routeFor(id))
        }
    }

    // --- Everything else -> EDIT_WRITE ---------------------------------------

    @Test
    fun claudeModelsRouteToEditWrite() {
        val ids = listOf(
            "claude-sonnet-4",
            "claude-opus-4",
            "claude-3-5-sonnet",
            "claude-haiku-3-5",
        )
        for (id in ids) {
            assertEquals("model $id", EditStrategy.EDIT_WRITE, ToolRouter.routeFor(id))
        }
    }

    @Test
    fun geminiModelsRouteToEditWrite() {
        val ids = listOf("gemini-2.5-pro", "gemini-2.0-flash")
        for (id in ids) {
            assertEquals("model $id", EditStrategy.EDIT_WRITE, ToolRouter.routeFor(id))
        }
    }

    @Test
    fun openModelsRouteToEditWrite() {
        val ids = listOf(
            "llama-3.3-70b",
            "deepseek-chat",
            // Reasoning is not enough on its own: DeepSeek's reasoner was
            // never trained on the apply_patch grammar, so it stays on
            // edit + write_file.
            "deepseek-reasoner",
            "qwen-2.5-coder",
            "qwen3-32b",
            "mistral-large",
        )
        for (id in ids) {
            assertEquals("model $id", EditStrategy.EDIT_WRITE, ToolRouter.routeFor(id))
        }
    }

    @Test
    fun unknownAndBlankModelsRouteToEditWrite() {
        val ids = listOf(
            "unknown-model-xyz",
            "some-future-model-9",
            "",
            "   ",
        )
        for (id in ids) {
            assertEquals("model '$id'", EditStrategy.EDIT_WRITE, ToolRouter.routeFor(id))
        }
    }

    // --- Guards against sloppy matching --------------------------------------

    @Test
    fun caseInsensitive() {
        assertEquals(EditStrategy.APPLY_PATCH, ToolRouter.routeFor("GPT-5"))
        assertEquals(EditStrategy.APPLY_PATCH, ToolRouter.routeFor("GPT-4O"))
        assertEquals(EditStrategy.APPLY_PATCH, ToolRouter.routeFor("Codex-Mini-Latest"))
        assertEquals(EditStrategy.APPLY_PATCH, ToolRouter.routeFor("O1-PREVIEW"))
        assertEquals(EditStrategy.APPLY_PATCH, ToolRouter.routeFor("OPENAI/O3-MINI"))
        assertEquals(EditStrategy.EDIT_WRITE, ToolRouter.routeFor("Claude-Sonnet-4"))
        assertEquals(EditStrategy.EDIT_WRITE, ToolRouter.routeFor("GEMINI-2.5-PRO"))
    }

    @Test
    fun oPrefixGuards() {
        // "opus" starts with 'o' but is not an o-series id.
        assertEquals(EditStrategy.EDIT_WRITE, ToolRouter.routeFor("opus"))
        assertEquals(EditStrategy.EDIT_WRITE, ToolRouter.routeFor("claude-opus-4"))
        // There is no o2 model, and unknown future o6-style ids must fall
        // back to the safe default until the table is updated.
        assertEquals(EditStrategy.EDIT_WRITE, ToolRouter.routeFor("o2"))
        assertEquals(EditStrategy.EDIT_WRITE, ToolRouter.routeFor("o6-future"))
        // A bare provider name is not a model id.
        assertEquals(EditStrategy.EDIT_WRITE, ToolRouter.routeFor("openai"))
    }

    @Test
    fun whitespaceIsTrimmed() {
        assertEquals(EditStrategy.APPLY_PATCH, ToolRouter.routeFor("  gpt-5  "))
        assertEquals(EditStrategy.EDIT_WRITE, ToolRouter.routeFor("  claude-sonnet-4  "))
    }

    // --- Catalog flags / lookup override heuristics ---------------------------

    @Test
    fun catalogFlagOverridesHeuristics() {
        // A catalog entry saying a GPT model does NOT support apply_patch wins.
        assertEquals(
            EditStrategy.EDIT_WRITE,
            ToolRouter.routeFor("gpt-5", supportsApplyPatch = false),
        )
        // ... and one saying an unknown model DOES support it also wins.
        assertEquals(
            EditStrategy.APPLY_PATCH,
            ToolRouter.routeFor("unknown-model-xyz", supportsApplyPatch = true),
        )
        // The spec's "openai provider with a codex flag" case.
        assertEquals(
            EditStrategy.APPLY_PATCH,
            ToolRouter.routeFor(
                modelId = "some-future-model",
                providerId = "openai",
                supportsApplyPatch = true,
            ),
        )
    }

    @Test
    fun catalogLookupLambda() {
        // Lookup knows this unknown model supports apply_patch.
        assertEquals(
            EditStrategy.APPLY_PATCH,
            ToolRouter.routeFor("unknown-model-xyz") { true },
        )
        // Lookup knows this GPT model does not.
        assertEquals(
            EditStrategy.EDIT_WRITE,
            ToolRouter.routeFor("gpt-5") { false },
        )
        // Lookup returns null (unknown) -> fall through to the heuristics.
        assertEquals(
            EditStrategy.APPLY_PATCH,
            ToolRouter.routeFor("gpt-5") { null },
        )
        assertEquals(
            EditStrategy.EDIT_WRITE,
            ToolRouter.routeFor("claude-sonnet-4") { null },
        )
        // Explicit flag outranks the lookup.
        assertEquals(
            EditStrategy.EDIT_WRITE,
            ToolRouter.routeFor("gpt-5", supportsApplyPatch = false) { true },
        )
    }

    @Test
    fun providerAloneDoesNotFlipStrategy() {
        // Provider "openai" with no flag and an unrecognised model id still
        // takes the safe default — only the catalog flag is a positive signal.
        assertEquals(
            EditStrategy.EDIT_WRITE,
            ToolRouter.routeFor("unknown-model-xyz", providerId = "openai"),
        )
        assertEquals(
            EditStrategy.EDIT_WRITE,
            ToolRouter.routeFor("unknown-model-xyz", providerId = "OpenAI"),
        )
        // Provider name case does not matter when the flag is present.
        assertEquals(
            EditStrategy.APPLY_PATCH,
            ToolRouter.routeFor(
                modelId = "unknown-model-xyz",
                providerId = "OpenAI",
                supportsApplyPatch = true,
            ),
        )
    }

    // --- requiredTools ---------------------------------------------------------

    @Test
    fun requiredToolsForApplyPatch() {
        assertEquals(listOf("apply_patch"), ToolRouter.requiredTools(EditStrategy.APPLY_PATCH))
    }

    @Test
    fun requiredToolsForEditWrite() {
        assertEquals(
            listOf("edit", "write_file"),
            ToolRouter.requiredTools(EditStrategy.EDIT_WRITE),
        )
    }

    @Test
    fun requiredToolsMatchRouting() {
        assertEquals(
            listOf("apply_patch"),
            ToolRouter.requiredTools(ToolRouter.routeFor("gpt-5")),
        )
        assertEquals(
            listOf("apply_patch"),
            ToolRouter.requiredTools(ToolRouter.routeFor("o3-mini")),
        )
        assertEquals(
            listOf("edit", "write_file"),
            ToolRouter.requiredTools(ToolRouter.routeFor("claude-sonnet-4")),
        )
        assertEquals(
            listOf("edit", "write_file"),
            ToolRouter.requiredTools(ToolRouter.routeFor("unknown-model-xyz")),
        )
    }
}
