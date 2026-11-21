package dev.codex.android.tools

import dev.codex.android.linux.ShellResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM tests for [ImageEnhanceTool].
 *
 * The Linux shell is a fake lambda (no real rootfs, no Pillow on the
 * JVM): it captures the command, stashes the generated Python script
 * it was asked to run, and can create the output file the tool
 * expects — so what is pinned down is argument handling, operation
 * parsing, script generation, Pillow gating, and output verification.
 * Whether Pillow really enhances an image in the guest is a
 * phone/CI matter, not something this JVM test can prove.
 */
class ImageEnhanceToolTest {

    private lateinit var workspaceDir: File
    private lateinit var workspace: WorkspaceFileTools

    @Before
    fun setUp() {
        workspaceDir = Files.createTempDirectory("image-enhance-test").toFile()
        workspace = WorkspaceFileTools(workspaceDir)
    }

    @After
    fun tearDown() {
        workspaceDir.deleteRecursively()
    }

    /** Captured shell invocation: the command plus the script file it named. */
    private class CapturedShell(
        private val workspaceRoot: File,
        private val handler: (String) -> ShellResult,
    ) {
        val commands = mutableListOf<String>()
        val scripts = mutableListOf<String>()
        val shell: suspend (String) -> ShellResult = { command ->
            commands += command
            // The script is the first quoted /workspace/... path in the command.
            val scriptGuest = Regex("'(/workspace/[^']+\\.py)'").find(command)?.groupValues?.get(1)
            if (scriptGuest != null) {
                val scriptFile = File(workspaceRoot, scriptGuest.removePrefix("/workspace/"))
                if (scriptFile.isFile) scripts += scriptFile.readText()
                // The output is the last quoted /workspace/... path: materialise it.
                val paths = Regex("'(/workspace/[^']+)'").findAll(command).map { it.groupValues[1] }.toList()
                val outputGuest = paths.lastOrNull()
                if (outputGuest != null && !outputGuest.endsWith(".py")) {
                    val outputFile = File(workspaceRoot, outputGuest.removePrefix("/workspace/"))
                    outputFile.parentFile?.mkdirs()
                    outputFile.writeBytes(byteArrayOf(7, 7, 7, 7))
                }
            }
            handler(command)
        }
    }

    private fun tool(
        captured: CapturedShell,
        ensurePillow: suspend () -> Boolean = { true },
    ): ImageEnhanceTool = ImageEnhanceTool(workspace, captured.shell, ensurePillow)

    private fun imageFile(name: String = "photo.png"): File =
        File(workspaceDir, name).apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

    @Test
    fun success_enhancesAndReturnsOutputPath() = runBlocking {
        imageFile()
        val captured = CapturedShell(workspaceDir) { ShellResult(0, "OK /workspace/photo-enhanced.png 800x600\n", "") }
        val res = tool(captured).execute(buildJsonObject {
            put("path", "photo.png")
            put("operations", "auto-orient,sharpen:1.5,contrast:1.3")
        })
        assertTrue(res.ok)
        assertTrue(res.output.contains("photo-enhanced.png"))
        assertTrue(res.output.contains("Operations applied: auto-orient, sharpen=1.5, contrast=1.3"))
        assertTrue(res.output.contains("left unchanged"))
        assertTrue(File(workspaceDir, "photo-enhanced.png").isFile)
        assertEquals(1, captured.commands.size)
        val command = captured.commands.single()
        assertTrue(command.startsWith("python3 "))
        assertTrue(command.contains("'/workspace/photo.png'"))
        assertTrue(command.contains("'/workspace/photo-enhanced.png'"))
        // The generated script is real Pillow code, and it is cleaned up.
        assertEquals(1, captured.scripts.size)
        val script = captured.scripts.single()
        assertTrue(script.contains("from PIL import Image, ImageEnhance, ImageOps"))
        assertTrue(script.contains("ImageOps.exif_transpose(img)"))
        assertTrue(script.contains("ImageEnhance.Sharpness(img).enhance(1.5)"))
        assertTrue(script.contains("ImageEnhance.Contrast(img).enhance(1.3)"))
        assertTrue(script.contains("img.save(dst)"))
        assertTrue(File(workspaceDir, ".codex").listFiles()?.none { it.name.endsWith(".py") } ?: true)
    }

    @Test
    fun defaultOperations_appliedWhenOperationsMissing() = runBlocking {
        imageFile()
        val captured = CapturedShell(workspaceDir) { ShellResult(0, "OK", "") }
        val res = tool(captured).execute(buildJsonObject { put("path", "photo.png") })
        assertTrue(res.ok)
        val script = captured.scripts.single()
        assertTrue(script.contains("ImageOps.exif_transpose(img)"))
        assertTrue(script.contains("ImageEnhance.Sharpness(img).enhance(1.5)"))
    }

    @Test
    fun convertOperation_changesOutputExtension() = runBlocking {
        imageFile()
        val captured = CapturedShell(workspaceDir) { ShellResult(0, "OK", "") }
        val res = tool(captured).execute(buildJsonObject {
            put("path", "photo.png")
            put("operations", "convert:webp")
        })
        assertTrue(res.ok)
        assertTrue(res.output.contains("photo-enhanced.webp"))
        assertTrue(File(workspaceDir, "photo-enhanced.webp").isFile)
    }

    @Test
    fun explicitOutputPath_isUsed() = runBlocking {
        imageFile()
        val captured = CapturedShell(workspaceDir) { ShellResult(0, "OK", "") }
        val res = tool(captured).execute(buildJsonObject {
            put("path", "photo.png")
            put("operations", "sharpen")
            put("output_path", "done/sharp.png")
        })
        assertTrue(res.ok)
        assertTrue(res.output.contains("done/sharp.png"))
        assertTrue(File(workspaceDir, "done/sharp.png").isFile)
    }

    @Test
    fun unknownOperation_returnsClearErrorAndNeverRunsShell() = runBlocking {
        imageFile()
        val captured = CapturedShell(workspaceDir) { ShellResult(0, "OK", "") }
        val res = tool(captured).execute(buildJsonObject {
            put("path", "photo.png")
            put("operations", "blur:2")
        })
        assertFalse(res.ok)
        val err = res.error!!
        assertTrue(err.contains("Unknown image operation \"blur\""))
        assertTrue(err.contains("resize"))
        assertTrue(captured.commands.isEmpty())
    }

    @Test
    fun malformedResize_returnsClearError() = runBlocking {
        imageFile()
        val captured = CapturedShell(workspaceDir) { ShellResult(0, "OK", "") }
        val res = tool(captured).execute(buildJsonObject {
            put("path", "photo.png")
            put("operations", "resize:wide")
        })
        assertFalse(res.ok)
        assertTrue(res.error!!.contains("Invalid resize value"))
        assertTrue(captured.commands.isEmpty())
    }

    @Test
    fun missingInput_returnsClearErrorAndNeverRunsShell() = runBlocking {
        val captured = CapturedShell(workspaceDir) { ShellResult(0, "OK", "") }
        val res = tool(captured).execute(buildJsonObject {
            put("path", "no-such-image.png")
            put("operations", "sharpen")
        })
        assertFalse(res.ok)
        assertTrue(res.error!!.contains("not found"))
        assertTrue(captured.commands.isEmpty())
    }

    @Test
    fun pillowMissing_returnsClearErrorAndNeverRunsEnhancement() = runBlocking {
        imageFile()
        val captured = CapturedShell(workspaceDir) { ShellResult(0, "OK", "") }
        val res = tool(captured, ensurePillow = { false }).execute(buildJsonObject {
            put("path", "photo.png")
            put("operations", "sharpen")
        })
        assertFalse(res.ok)
        val err = res.error!!
        assertTrue(err.contains("Pillow"))
        assertTrue(err.contains("py3-pillow"))
        assertTrue(captured.commands.isEmpty())
    }

    @Test
    fun shellFailure_returnsStderr() = runBlocking {
        imageFile()
        val captured = CapturedShell(workspaceDir) {
            ShellResult(1, "", "Traceback (most recent call last):\nPIL.UnidentifiedImageError: cannot identify image file")
        }
        val res = tool(captured).execute(buildJsonObject {
            put("path", "photo.png")
            put("operations", "sharpen")
        })
        assertFalse(res.ok)
        val err = res.error!!
        assertTrue(err.contains("exit 1"))
        assertTrue(err.contains("UnidentifiedImageError"))
    }

    @Test
    fun shellSuccessWithoutOutputFile_returnsClearError() = runBlocking {
        imageFile()
        // Fake shell that succeeds but creates no output file.
        val shell: suspend (String) -> ShellResult = { ShellResult(0, "OK", "") }
        val res = ImageEnhanceTool(workspace, shell, { true }).execute(buildJsonObject {
            put("path", "photo.png")
            put("operations", "sharpen")
        })
        assertFalse(res.ok)
        assertTrue(res.error!!.contains("produced no output file"))
    }

    @Test
    fun parseOperations_acceptsStringArrayAndObjectForms() {
        val fromString = ImageEnhanceTool.parseOperations(JsonPrimitive("sharpen:2,contrast"))
        assertEquals(
            listOf(EnhanceOp("sharpen", "2"), EnhanceOp("contrast", "1.2")),
            fromString,
        )
        val fromArray = ImageEnhanceTool.parseOperations(
            buildJsonArray {
                add(JsonPrimitive("resize:800x600"))
                add(JsonPrimitive("auto-orient"))
            },
        )
        assertEquals(
            listOf(EnhanceOp("resize", "800x600"), EnhanceOp("auto-orient", null)),
            fromArray,
        )
        val fromObject = ImageEnhanceTool.parseOperations(
            buildJsonObject {
                put("sharpen", JsonPrimitive(1.5))
                put("convert", JsonPrimitive("jpg"))
            },
        )
        assertEquals(
            listOf(EnhanceOp("sharpen", "1.5"), EnhanceOp("convert", "jpg")),
            fromObject,
        )
        // Blank / absent means the default operations.
        assertEquals(ImageEnhanceTool.DEFAULT_OPERATIONS, ImageEnhanceTool.parseOperations(JsonPrimitive("")))
        assertEquals(ImageEnhanceTool.DEFAULT_OPERATIONS, ImageEnhanceTool.parseOperations(null))
    }

    @Test
    fun parseOperations_rejectsBadValues() {
        val bad = listOf(
            JsonPrimitive("sharpen:fast"),
            JsonPrimitive("convert:bmp"),
            JsonPrimitive("resize:800x"),
            JsonPrimitive("brightness:0"),
        )
        for (raw in bad) {
            try {
                ImageEnhanceTool.parseOperations(raw)
                throw AssertionError("Expected parseOperations to reject $raw")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun buildEnhanceScript_coversEverySupportedOperation() {
        val script = ImageEnhanceTool.buildEnhanceScript(
            listOf(
                EnhanceOp("auto-orient", null),
                EnhanceOp("resize", "800x600"),
                EnhanceOp("sharpen", "1.5"),
                EnhanceOp("contrast", "1.2"),
                EnhanceOp("brightness", "1.1"),
                EnhanceOp("convert", "jpg"),
            ),
        )
        assertTrue(script.contains("ImageOps.exif_transpose(img)"))
        assertTrue(script.contains("img = img.resize((800, 600))"))
        assertTrue(script.contains("ImageEnhance.Sharpness(img).enhance(1.5)"))
        assertTrue(script.contains("ImageEnhance.Contrast(img).enhance(1.2)"))
        assertTrue(script.contains("ImageEnhance.Brightness(img).enhance(1.1)"))
        assertTrue(script.contains("img.save(dst)"))

        val maxSide = ImageEnhanceTool.buildEnhanceScript(listOf(EnhanceOp("resize", "1024")))
        assertTrue(maxSide.contains("img.thumbnail((1024, 1024))"))
    }

    @Test
    fun ensurePillowViaShell_installsWhenProbeFailsFirst() = runBlocking {
        val commands = mutableListOf<String>()
        var probes = 0
        val shell: suspend (String) -> ShellResult = { command ->
            commands += command
            when {
                command.startsWith("python3 -c") -> {
                    probes++
                    if (probes == 1) ShellResult(1, "", "ModuleNotFoundError: No module named 'PIL'") else ShellResult(0, "", "")
                }
                command.startsWith("apk add") -> ShellResult(0, "OK", "")
                else -> ShellResult(1, "", "unexpected")
            }
        }
        assertTrue(ImageEnhanceTool.ensurePillowViaShell(shell))
        assertEquals(
            listOf(
                "python3 -c \"import PIL\"",
                "apk add --no-cache py3-pillow",
                "python3 -c \"import PIL\"",
            ),
            commands,
        )
    }

    @Test
    fun ensurePillowViaShell_returnsFalseWhenInstallFails() = runBlocking {
        val shell: suspend (String) -> ShellResult = { command ->
            if (command.startsWith("apk add")) ShellResult(1, "", "ERROR: unable to select packages") else ShellResult(1, "", "no PIL")
        }
        assertFalse(ImageEnhanceTool.ensurePillowViaShell(shell))
    }

    @Test
    fun shellQuote_escapesSingleQuotes() {
        assertEquals("'plain'", ImageEnhanceTool.shellQuote("plain"))
        assertEquals("'it'\\''s.png'", ImageEnhanceTool.shellQuote("it's.png"))
    }
}
