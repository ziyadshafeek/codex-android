package dev.codex.android.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for [ModelPolicy] and [ModelPolicyTool]. */
class ModelPolicyTest {

    @Test
    fun policy_containsAllFiveUserModels() {
        val ids = ModelPolicy.MODELS.map { it.id }
        assertEquals(
            listOf(
                "gpt-6-luna",
                "gemini-3-8-flash",
                "deepseek-v4-1-flash-1017",
                "nvidia/nemotron-3-ultra-550b-a55b",
                "gemini-4-hydrogen",
            ),
            ids,
        )
    }

    @Test
    fun lookup_knownModelIsCaseInsensitive() {
        assertEquals("gpt-6-luna", ModelPolicy.lookup("GPT-6-Luna")?.id)
        assertEquals("gemini-4-hydrogen", ModelPolicy.lookup("gemini-4-hydrogen")?.id)
        assertNull(ModelPolicy.lookup("gpt-99"))
        assertNull(ModelPolicy.lookup(""))
    }

    @Test
    fun policy_verifiedCapabilities() {
        val luna = ModelPolicy.lookup("gpt-6-luna")!!
        assertEquals(ModelCapability.YES, luna.vision)
        assertEquals(1_050_000, luna.contextWindow)
        assertEquals(128_000, luna.maxOutputTokens)

        val nemotron = ModelPolicy.lookup("nvidia/nemotron-3-ultra-550b-a55b")!!
        assertEquals(ModelCapability.NO, nemotron.vision)
        assertEquals(ModelCapability.NO, nemotron.imageGeneration)
        assertEquals(1_048_576, nemotron.contextWindow)
        assertNull(nemotron.maxOutputTokens) // unknown: not published on the model card
    }

    @Test
    fun policy_unknownCapabilitiesAreMarkedUnknown() {
        // Image generation via the provider's OpenAI-compatible endpoint was
        // not verifiable for any of these chat models.
        for (id in listOf("gpt-6-luna", "gemini-3-8-flash", "deepseek-v4-1-flash-1017", "gemini-4-hydrogen")) {
            assertEquals("image generation for $id", ModelCapability.UNKNOWN, ModelPolicy.lookup(id)!!.imageGeneration)
        }
        // Max output is unknown where the provider does not publish it.
        assertNull(ModelPolicy.lookup("deepseek-v4-1-flash-1017")!!.maxOutputTokens)
        assertNull(ModelPolicy.lookup("nvidia/nemotron-3-ultra-550b-a55b")!!.maxOutputTokens)
    }

    @Test
    fun recommend_visionPicksLargestVisionWindow() {
        val rec = ModelPolicy.recommend("vision")
        assertEquals("gpt-6-luna", rec?.id)
        assertEquals("gpt-6-luna", ModelPolicy.recommend("read this screenshot")?.id)
        assertEquals("gpt-6-luna", ModelPolicy.recommend("describe the photo")?.id)
    }

    @Test
    fun recommend_imageGenerationHasNoKnownModel() {
        assertNull(ModelPolicy.recommend("image"))
        assertNull(ModelPolicy.recommend("generate an image"))
        assertNull(ModelPolicy.recommend("draw a cat"))
    }

    @Test
    fun recommend_longContextPicksLargestWindow() {
        assertEquals("gpt-6-luna", ModelPolicy.recommend("long-context")?.id)
        assertEquals("gpt-6-luna", ModelPolicy.recommend("summarise this whole codebase")?.id)
    }

    @Test
    fun recommend_codePicksNemotron() {
        assertEquals("nvidia/nemotron-3-ultra-550b-a55b", ModelPolicy.recommend("code")?.id)
        assertEquals("nvidia/nemotron-3-ultra-550b-a55b", ModelPolicy.recommend("debug this crash")?.id)
    }

    @Test
    fun recommend_generalDefaultsToLuna() {
        assertEquals("gpt-6-luna", ModelPolicy.recommend("general")?.id)
        assertEquals("gpt-6-luna", ModelPolicy.recommend("chat")?.id)
        assertEquals("gpt-6-luna", ModelPolicy.recommend("")?.id)
    }

    @Test
    fun tool_recommendationForEachTaskType() = runBlocking {
        val vision = ModelPolicyTool.execute(buildJsonObject { put("task", "vision") })
        assertTrue(vision.ok)
        assertTrue(vision.output.contains("gpt-6-luna"))

        val image = ModelPolicyTool.execute(buildJsonObject { put("task", "image") })
        assertTrue(image.ok) // not an error — a plain statement
        assertTrue(image.output.contains("No recommendation"))
        assertTrue(image.output.contains("image-generate"))

        val code = ModelPolicyTool.execute(buildJsonObject { put("task", "code") })
        assertTrue(code.ok)
        assertTrue(code.output.contains("nvidia/nemotron-3-ultra-550b-a55b"))
    }

    @Test
    fun tool_knownModelReturnsCapabilitiesWithUnknowns() = runBlocking {
        val res = ModelPolicyTool.execute(buildJsonObject { put("model", "nvidia/nemotron-3-ultra-550b-a55b") })
        assertTrue(res.ok)
        assertTrue(res.output.contains("Vision (image input): no"))
        assertTrue(res.output.contains("Image generation: no"))
        assertTrue(res.output.contains("Context window: 1048576 tokens"))
        assertTrue(res.output.contains("Max output tokens: unknown"))
    }

    @Test
    fun tool_unknownModelIsStatementNotError() = runBlocking {
        val res = ModelPolicyTool.execute(buildJsonObject { put("model", "gpt-99-turbo") })
        assertTrue(res.ok)
        assertTrue(res.output.contains("not in the policy"))
        assertTrue(res.output.contains("gpt-6-luna"))
    }

    @Test
    fun tool_modelPlusTaskEvaluatesThatModel() = runBlocking {
        val res = ModelPolicyTool.execute(buildJsonObject {
            put("model", "gpt-6-luna")
            put("task", "vision")
        })
        assertTrue(res.ok)
        assertTrue(res.output.contains("For vision: yes."))

        val noVision = ModelPolicyTool.execute(buildJsonObject {
            put("model", "nvidia/nemotron-3-ultra-550b-a55b")
            put("task", "vision")
        })
        assertTrue(noVision.output.contains("For vision: no."))
    }

    @Test
    fun tool_noArgsListsAllModels() = runBlocking {
        val res = ModelPolicyTool.execute(buildJsonObject {})
        assertTrue(res.ok)
        for (id in ModelPolicy.MODELS.map { it.id }) {
            assertTrue(res.output.contains(id))
        }
    }

    @Test
    fun tool_nameAndSensitivity() {
        assertEquals("model-policy", ModelPolicyTool.name)
        assertFalse(ModelPolicyTool.sensitive)
    }
}
