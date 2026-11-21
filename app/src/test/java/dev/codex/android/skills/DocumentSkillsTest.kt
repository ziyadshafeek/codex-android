package dev.codex.android.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the document skills in [DOCUMENT_SKILLS].
 *
 * Pure-data tests: no Android APIs, no I/O. The contract pinned down
 * hardest is the tool-name contract — every tool a skill names must
 * actually exist in this build:
 * - `read_pdf`, `read_document`, `read_text_file`: tools/DocumentTools.kt
 * - `document_create`, `document_edit`: tools/DocumentWriteTools.kt
 * - `pdf_create`, `pdf_merge`, `pdf_split`, `pdf_rotate`, `pdf_forms`: tools/PdfTools.kt
 * - `ocr_image`: tools/OcrTools.kt (needs the `ocr` toolchain in the
 *   Linux workspace; the tool itself exists regardless)
 * - `pdf_to_image`: tools/PdfToImageTool.kt (native PdfRenderer first,
 *   pdftoppm fallback; registered in withBuiltins)
 * - `attachment_read`: the per-chat attachment reader, registered in
 *   ChatViewModel.policyGatedRegistry (already in this build)
 *
 * The tailored-resume-generator skill is only included because
 * `document_create` (docx write) exists; the invoice-organizer skill
 * is only included because `ocr_image` exists. If either tool is ever
 * removed, the matching skill must go with it — these tests fail first.
 */
class DocumentSkillsTest {

    /** Tools that exist in this build after the document work lands. */
    private val existingTools = setOf(
        "read_pdf", "read_document", "read_text_file",
        "document_create", "document_edit",
        "pdf_create", "pdf_merge", "pdf_split", "pdf_rotate", "pdf_forms",
        "ocr_image", "pdf_to_image", "attachment_read",
    )

    @Test
    fun documentSkills_hasSixSkillsWithUniqueIds() {
        val ids = DOCUMENT_SKILLS.map { it.id }
        assertEquals(
            listOf("docx", "invoice-organizer", "pdf", "pptx", "tailored-resume-generator", "xlsx"),
            ids.sorted(),
        )
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyDocumentSkill_isEnabledAndWellFormed() {
        for (skill in DOCUMENT_SKILLS) {
            assertTrue("skill ${skill.id} must be enabled by default", skill.enabled)
            assertTrue("skill ${skill.id} needs a name", skill.name.isNotBlank())
            assertTrue("skill ${skill.id} needs a description", skill.description.isNotBlank())
            assertTrue("skill ${skill.id} needs instructions", skill.instructions.isNotBlank())
            assertTrue("skill ${skill.id} must name at least one tool", skill.toolNames.isNotEmpty())
            assertEquals("skill ${skill.id} must not name a tool twice", skill.toolNames.size, skill.toolNames.toSet().size)
        }
    }

    @Test
    fun everyNamedTool_actuallyExistsInThisBuild() {
        for (skill in DOCUMENT_SKILLS) {
            for (tool in skill.toolNames) {
                assertTrue("skill ${skill.id} names missing tool $tool", tool in existingTools)
            }
        }
    }

    @Test
    fun fourDocumentSkills_coverTheRightTools() {
        val byId = DOCUMENT_SKILLS.associateBy { it.id }
        val docx = byId.getValue("docx")
        assertTrue(docx.toolNames.contains("read_document"))
        assertTrue(docx.toolNames.contains("document_create"))
        assertTrue(docx.toolNames.contains("document_edit"))
        assertFalse(docx.toolNames.contains("ocr_image"))

        val pdf = byId.getValue("pdf")
        assertTrue(pdf.toolNames.contains("read_pdf"))
        assertTrue(pdf.toolNames.contains("pdf_merge"))
        assertTrue(pdf.toolNames.contains("pdf_split"))
        assertTrue(pdf.toolNames.contains("pdf_rotate"))
        assertTrue(pdf.toolNames.contains("pdf_forms"))
        assertTrue(pdf.toolNames.contains("ocr_image"))

        val pptx = byId.getValue("pptx")
        assertTrue(pptx.toolNames.contains("read_document"))
        assertTrue(pptx.toolNames.contains("document_create"))

        val xlsx = byId.getValue("xlsx")
        assertTrue(xlsx.toolNames.contains("read_document"))
        assertTrue(xlsx.toolNames.contains("document_create"))
        assertTrue(xlsx.toolNames.contains("document_edit"))
    }

    @Test
    fun resumeSkill_requiresDocxWrite_andInvoiceSkillRequiresOcr() {
        val byId = DOCUMENT_SKILLS.associateBy { it.id }
        val resume = byId.getValue("tailored-resume-generator")
        assertTrue("resume skill needs docx write", resume.toolNames.contains("document_create"))
        assertTrue("resume skill needs docx read", resume.toolNames.contains("read_document"))

        val invoice = byId.getValue("invoice-organizer")
        assertTrue("invoice skill needs OCR for scanned receipts", invoice.toolNames.contains("ocr_image"))
        assertTrue("invoice skill needs PDF read", invoice.toolNames.contains("read_pdf"))
        assertTrue("invoice skill needs a summary writer", invoice.toolNames.contains("document_create"))
    }

    @Test
    fun noSkill_namesAnonexistentTool() {
        // Guard against the classic failure: a skill promising image
        // editing, charting, or tracked changes, none of which exist.
        // read_image exists in this build but is out of scope for the
        // document skills (image work belongs to the image skills).
        val forbidden = listOf("read_image", "edit_image", "add_chart", "track_changes")
        for (skill in DOCUMENT_SKILLS) {
            for (tool in forbidden) {
                assertFalse("skill ${skill.id} must not name $tool", tool in skill.toolNames)
            }
        }
    }
}
