package dev.codex.android.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the image skills in [ImageSkills].
 *
 * Pure-data tests: no Android APIs, no I/O. The hard contract is the
 * tool-name one — every tool a skill names must actually exist in
 * this build:
 * - `read_image`: tools/ReadImageTool.kt (this task).
 * - `image_enhance`: tools/ImageEnhanceTool.kt (this task).
 * - `attachment_read`: registered per chat thread in
 *   ChatViewModel.policyGatedRegistry.
 * - `image-generate`: tools/ImageGenerateTool.kt exists with that
 *   name (its registration is the coordinator's job).
 */
class ImageSkillsTest {

    /** Tools verified (by reading the sources above) to exist in this build. */
    private val existingTools = setOf(
        "read_image",
        "image_enhance",
        "attachment_read",
        "image-generate",
    )

    @Test
    fun imageSkills_hasThreeSkillsWithUniqueIds() {
        val ids = ImageSkills.ALL.map { it.id }
        assertEquals(listOf("image", "image-enhancer", "image-generate"), ids.sorted())
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyImageSkill_isEnabledAndHasInstructions() {
        for (skill in ImageSkills.ALL) {
            assertTrue("skill ${skill.id} must be enabled by default", skill.enabled)
            assertTrue("skill ${skill.id} needs a description", skill.description.isNotBlank())
            assertTrue("skill ${skill.id} needs instructions", skill.instructions.isNotBlank())
            assertTrue("skill ${skill.id} must name at least one tool", skill.toolNames.isNotEmpty())
        }
    }

    @Test
    fun everyNamedTool_actuallyExistsInThisBuild() {
        for (skill in ImageSkills.ALL) {
            for (tool in skill.toolNames) {
                assertTrue(
                    "skill ${skill.id} names '$tool', which does not exist in this build",
                    tool in existingTools,
                )
            }
        }
    }

    @Test
    fun imageSkill_replacesMinimalEntryWithReadImagePlusAttachmentRead() {
        val skill = SkillRunner.findSkill(ImageSkills.ALL, "image")!!
        assertEquals(listOf("read_image", "attachment_read"), skill.toolNames)
        assertTrue(skill.instructions.contains("read_image"))
        assertTrue(skill.instructions.contains("attachment_read"))
        // Vision only works when the model supports it — the skill must say so.
        assertTrue(skill.instructions.contains("does not support vision"))
        assertTrue(skill.instructions.contains("never guess from its filename"))
        // The image skill is about images, not documents: it must not
        // name the document tools (they exist in this build, but they
        // belong to the document skills, not here).
        for (docTool in listOf("read_pdf", "read_document", "read_text_file")) {
            assertFalse("image skill must not name '$docTool'", docTool in skill.toolNames)
        }
    }

    @Test
    fun imageEnhancerSkill_namesImageEnhanceAndTeachesEnhanceBeforeOcr() {
        val skill = SkillRunner.findSkill(ImageSkills.ALL, "image-enhancer")!!
        assertEquals(listOf("image_enhance", "read_image"), skill.toolNames)
        assertTrue(skill.instructions.contains("image_enhance"))
        assertTrue(skill.instructions.contains("before OCR"))
        assertTrue(skill.instructions.contains("auto-orient"))
        assertTrue(skill.instructions.contains("never modifies the original"))
    }

    @Test
    fun imageGenerateSkill_namesImageGenerateTool() {
        val skill = SkillRunner.findSkill(ImageSkills.ALL, "image-generate")!!
        assertEquals(listOf("image-generate"), skill.toolNames)
        assertTrue(skill.instructions.contains("image-generate"))
        assertTrue(skill.instructions.contains("saved path"))
    }

    @Test
    fun skillPrompt_listsAllThreeImageSkills() {
        val prompt = SkillRunner.buildSkillPrompt(ImageSkills.ALL)
        for (id in listOf("image", "image-enhancer", "image-generate")) {
            assertTrue(prompt.contains(id))
        }
    }

    @Test
    fun skillInvocation_limitsToolsToTheNamedOnes() {
        val skill = SkillRunner.findSkill(ImageSkills.ALL, "image-enhancer")!!
        val invocation = SkillRunner.buildSkillInvocation(skill, "make this screenshot readable")
        assertTrue(invocation.contains("ONLY these tools: image_enhance, read_image"))
        assertTrue(invocation.contains("make this screenshot readable"))
    }
}
