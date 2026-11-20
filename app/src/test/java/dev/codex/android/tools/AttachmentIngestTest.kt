package dev.codex.android.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure logic in [AttachmentIngest]: type
 * detection by MIME type and extension, file-name sanitising, and the
 * size / count / text caps. No Android framework classes are touched,
 * so these run on the JVM with no device or network. ContentResolver
 * copying is deliberately not tested here — it needs Android.
 */
class AttachmentIngestTest {

    @Test
    fun isImage_matchesImageMimeTypes() {
        assertTrue(AttachmentIngest.isImage("image/jpeg", "photo"))
        assertTrue(AttachmentIngest.isImage("image/png", "scan"))
        assertTrue(AttachmentIngest.isImage("image/webp", "shot"))
        assertTrue(AttachmentIngest.isImage("image/heic", "pic"))
    }

    @Test
    fun isImage_matchesImageExtensionsWhenMimeMissing() {
        assertTrue(AttachmentIngest.isImage(null, "photo.jpg"))
        assertTrue(AttachmentIngest.isImage(null, "diagram.PNG"))
        assertTrue(AttachmentIngest.isImage(null, "scan.webp"))
        assertTrue(AttachmentIngest.isImage("application/octet-stream", "pic.heic"))
    }

    @Test
    fun isImage_rejectsNonImages() {
        assertFalse(AttachmentIngest.isImage("application/pdf", "report.pdf"))
        assertFalse(AttachmentIngest.isImage("text/plain", "notes.txt"))
        assertFalse(AttachmentIngest.isImage(null, "report.docx"))
        assertFalse(AttachmentIngest.isImage(null, "archive.zip"))
    }

    @Test
    fun isImage_rejectsSvgAndTiffBitmapFactoryCannotDecode() {
        // BitmapFactory.decodeFile cannot decode SVG or TIFF, so
        // classifying them as images made ingest emit a read_image
        // marker for a file read_image always fails on.
        assertFalse(AttachmentIngest.isImage("image/svg+xml", "diagram.svg"))
        assertFalse(AttachmentIngest.isImage("image/tiff", "scan.tif"))
        assertFalse(AttachmentIngest.isImage(null, "diagram.svg"))
        assertFalse(AttachmentIngest.isImage(null, "scan.tiff"))
        assertFalse(AttachmentIngest.isImage(null, "scan.tif"))
        // Decodable formats still match.
        assertTrue(AttachmentIngest.isImage(null, "photo.bmp"))
        assertTrue(AttachmentIngest.isImage("image/heic", "pic"))
    }

    @Test
    fun isPdf_matchesPdfMimeAndExtension() {
        assertTrue(AttachmentIngest.isPdf("application/pdf", "report"))
        assertTrue(AttachmentIngest.isPdf(null, "report.PDF"))
        assertTrue(AttachmentIngest.isPdf("application/pdf", "report.pdf"))
    }

    @Test
    fun isPdf_rejectsOtherDocuments() {
        assertFalse(AttachmentIngest.isPdf("application/msword", "letter.doc"))
        assertFalse(AttachmentIngest.isPdf(null, "notes.txt"))
        assertFalse(AttachmentIngest.isPdf("image/png", "scan.png"))
    }

    @Test
    fun isDocument_matchesOfficeMimesAndExtensions() {
        assertTrue(
            AttachmentIngest.isDocument(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "letter",
            ),
        )
        assertTrue(
            AttachmentIngest.isDocument(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "sheet",
            ),
        )
        assertTrue(
            AttachmentIngest.isDocument(
                "application/vnd.ms-powerpoint",
                "deck",
            ),
        )
        assertTrue(AttachmentIngest.isDocument(null, "letter.docx"))
        assertTrue(AttachmentIngest.isDocument(null, "data.XLSX"))
        assertTrue(AttachmentIngest.isDocument(null, "deck.ppt"))
        assertTrue(AttachmentIngest.isDocument("application/msword", "letter"))
    }

    @Test
    fun isDocument_rejectsPdfAndText() {
        assertFalse(AttachmentIngest.isDocument("application/pdf", "report.pdf"))
        assertFalse(AttachmentIngest.isDocument("text/plain", "notes.txt"))
        assertFalse(AttachmentIngest.isDocument(null, "photo.jpg"))
    }

    @Test
    fun isText_matchesTextMimeAndExtensions() {
        assertTrue(AttachmentIngest.isText("text/plain", "notes"))
        assertTrue(AttachmentIngest.isText("text/markdown", "notes"))
        assertTrue(AttachmentIngest.isText("text/csv", "data"))
        assertTrue(AttachmentIngest.isText("application/json", "data"))
        assertTrue(AttachmentIngest.isText(null, "notes.txt"))
        assertTrue(AttachmentIngest.isText(null, "README.MD"))
        assertTrue(AttachmentIngest.isText(null, "data.csv"))
        assertTrue(AttachmentIngest.isText(null, "app.kt"))
    }

    @Test
    fun isText_rejectsBinaryTypes() {
        assertFalse(AttachmentIngest.isText("application/pdf", "report.pdf"))
        assertFalse(
            AttachmentIngest.isText(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "letter.docx",
            ),
        )
        assertFalse(AttachmentIngest.isText("image/jpeg", "photo.jpg"))
        assertFalse(AttachmentIngest.isText(null, "archive.zip"))
    }

    @Test
    fun sanitizeFileName_replacesUnsafeCharacters() {
        assertEquals("my_file__1_.pdf", AttachmentIngest.sanitizeFileName("my file (1).pdf"))
        assertEquals("a_b_c.txt", AttachmentIngest.sanitizeFileName("a/b\\c.txt"))
        assertEquals("report_2026.pdf", AttachmentIngest.sanitizeFileName("report: 2026.pdf"))
    }

    @Test
    fun sanitizeFileName_keepsLettersDigitsDotDashUnderscore() {
        assertEquals(
            "Report-2026_final.v2.pdf",
            AttachmentIngest.sanitizeFileName("Report-2026_final.v2.pdf"),
        )
    }

    @Test
    fun sanitizeFileName_fallsBackForBlankName() {
        assertEquals("file", AttachmentIngest.sanitizeFileName(""))
        assertEquals("file", AttachmentIngest.sanitizeFileName("   "))
        assertEquals("file", AttachmentIngest.sanitizeFileName("..."))
    }

    @Test
    fun isOverSizeLimit_enforcesTenMegabyteCap() {
        assertEquals(10L * 1024L * 1024L, AttachmentIngest.MAX_ATTACHMENT_BYTES)
        assertFalse(AttachmentIngest.isOverSizeLimit(null))
        assertFalse(AttachmentIngest.isOverSizeLimit(0L))
        assertFalse(AttachmentIngest.isOverSizeLimit(AttachmentIngest.MAX_ATTACHMENT_BYTES))
        assertTrue(AttachmentIngest.isOverSizeLimit(AttachmentIngest.MAX_ATTACHMENT_BYTES + 1))
    }

    @Test
    fun exceedsAttachmentCount_enforcesThreeFileCap() {
        assertEquals(3, AttachmentIngest.MAX_ATTACHMENTS_PER_MESSAGE)
        assertFalse(AttachmentIngest.exceedsAttachmentCount(0))
        assertFalse(AttachmentIngest.exceedsAttachmentCount(3))
        assertTrue(AttachmentIngest.exceedsAttachmentCount(4))
    }

    @Test
    fun capExtractedText_capsLongTextWithTruncationNote() {
        val long = "x".repeat(25_000)
        val capped = AttachmentIngest.capExtractedText(long)
        assertTrue(capped.startsWith("x".repeat(20_000)))
        assertTrue(capped.contains("[truncated]"))
        assertEquals(20_000 + "\n[truncated]".length, capped.length)
    }

    @Test
    fun capExtractedText_leavesShortTextUnchanged() {
        assertEquals("hello", AttachmentIngest.capExtractedText("hello"))
        assertEquals(
            "x".repeat(20_000),
            AttachmentIngest.capExtractedText("x".repeat(20_000)),
        )
    }
}
