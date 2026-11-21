package dev.codex.android.skills

/**
 * Image skills for the agent.
 *
 * Attribution: the `image` skill replaces the minimal built-in entry of
 * the same id in SkillStore (which named only `attachment_read`);
 * `image-enhancer` is adapted from Composio's image-enhancer skill
 * (description and behaviour only — all wording below is written fresh
 * for this app); `image-generate` covers the app's own BYOK
 * image-generate tool. No source skill text is copied here.
 *
 * The coordinator replaces SkillStore's minimal `image` entry with
 * [IMAGE] (same id, so the swap is clean) and adds [IMAGE_ENHANCER]
 * and [IMAGE_GENERATE] to BUILTINS.
 */
object ImageSkills {

    /**
     * Full image skill (id "image"): replaces the minimal SkillStore
     * entry. Names the standalone `read_image` tool plus
     * `attachment_read`, and teaches the agent to describe / OCR
     * genuinely — only from an image the model actually saw — and that
     * vision only works when the current model supports it.
     */
    val IMAGE: Skill = Skill(
        id = "image",
        name = "Describe images",
        description = "Describes an image file or an image attached to this chat, reads the text visible in it, and answers questions about what it shows.",
        instructions = "When the user asks about an image file or attaches one, look at the image itself — never guess from its filename. " +
            "For an image file in the workspace, call read_image with the file path and a prompt saying what to describe or extract; read_image genuinely sends the image to the vision model and returns the model's own description, so quote that result and say which file it came from. " +
            "For an image attached to this chat, it is already sent to the model inline with the message (as an image part, not a file to open): describe what is actually visible — objects, people, layout, colours — and read out any text in the image word for word. " +
            "If the image is a page of an attached PDF, use attachment_read with the attachment id and page number to get that page's text, and cite the page for every fact as [p. N]. " +
            "If the image is blurry, dim, sideways, or too small to read, enhance it first with image_enhance (auto-orient, sharpen, contrast) and then read the enhanced copy — never squint and guess. " +
            "Answer only what the image shows. If read_image reports the model does not support vision, say so plainly, offer to read the file's text with attachment_read instead, and never invent a description. " +
            "If no image reached you in this chat and no image file was given, say so clearly instead of describing an image you have not been shown.",
        toolNames = listOf("read_image", "attachment_read"),
        version = 1,
        enabled = true,
    )

    /**
     * Image enhancer: names the `image_enhance` tool (Pillow in the
     * Linux workspace) and teaches when to enhance and which
     * operations to use — above all before OCR / reading, for
     * readability.
     */
    val IMAGE_ENHANCER: Skill = Skill(
        id = "image-enhancer",
        name = "Enhance images",
        description = "Improves the quality of an image — sharper, clearer, better lit, correctly oriented — especially before reading text in it or sharing it.",
        instructions = "When an image is blurry, dim, low-contrast, sideways, or too large, enhance it with image_enhance before doing anything else with it — above all before OCR or reading text from it, since a clean image reads far better than a noisy one. " +
            "Pass the workspace-relative path and the operations to apply, in order: auto-orient first when the photo is sideways (it reads the EXIF orientation), then resize when it is oversized (a max side such as 1024, or an exact size such as 800x600), then sharpen (about 1.5 for a blurry screenshot, less for a photo), contrast (about 1.2) and brightness (about 1.1) for a dim or washed-out image, and convert when a different format is needed (png for crisp screenshots and text, jpg for smaller photos). " +
            "Example operations string: \"auto-orient,sharpen:1.5,contrast:1.2\". " +
            "image_enhance never modifies the original: it saves a new file (default <name>-enhanced.<ext> next to the input) and returns its path — use that enhanced copy for the next step and tell the user where it is. " +
            "If image_enhance reports Pillow is missing, say so plainly and do not pretend the image was enhanced. " +
            "Do not upscale beyond what the source supports: enhancement sharpens and clarifies what is there, it cannot recover detail that was never captured. " +
            "After enhancing, look at the result with read_image to confirm it actually reads better before handing it on.",
        toolNames = listOf("image_enhance", "read_image"),
        version = 1,
        enabled = true,
    )

    /**
     * Image generation: names the app's own BYOK `image-generate`
     * tool. Verified: tools/ImageGenerateTool.kt exists in this base
     * with name "image-generate".
     */
    val IMAGE_GENERATE: Skill = Skill(
        id = "image-generate",
        name = "Generate images",
        description = "Generates an image from a text prompt with the user's own configured image endpoint and saves it to a file.",
        instructions = "When the user asks for a new image from a description, call image-generate with a clear, specific prompt: subject, style, composition, and mood in plain words, one scene only. " +
            "Pass a size such as 1024x1024 when the user wants one, and an output_path when they name a file or folder; otherwise image-generate picks a filename in the images directory and returns the saved path — report that path to the user. " +
            "If image-generate reports the provider has no image endpoint, or that no provider is configured, say so plainly and do not pretend an image was made. " +
            "Never claim an image exists before the tool returns its saved path. " +
            "After generating, offer to describe the result with read_image, or to enhance it with image_enhance if it needs sharpening.",
        toolNames = listOf("image-generate"),
        version = 1,
        enabled = true,
    )

    /** All image skills in this file, in id order. */
    val ALL: List<Skill> = listOf(IMAGE, IMAGE_ENHANCER, IMAGE_GENERATE)
}
