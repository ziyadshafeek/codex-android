package dev.codex.android.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryMarkdownTest {
    @Test fun notesRoundTripIncludingEscapedSectionLikeLines() {
        val notes = mapOf("reading" to "**Markdown**\n\n## table\n\\## also\n- item", "work" to "Use tests")
        val exported = MemoryMarkdown.render(notes)
        assertTrue(exported.contains("\n\\## table\n"))
        assertTrue(exported.contains("\n\\\\## also\n"))
        assertEquals(notes, MemoryMarkdown.parse(exported))
    }

    @Test fun editingDocumentAddsUpdatesAndDeletesNotes() {
        val changed = MemoryMarkdown.parse("# Memory\r\n\r\n## Preference.Style\r\n- Short answers\r\n\r\n## project\r\n**Use Gradle**")
        assertEquals(mapOf("preference.style" to "- Short answers", "project" to "**Use Gradle**"), changed)
        assertEquals(emptyMap<String, String>(), MemoryMarkdown.parse("# Memory\n"))
    }

    @Test fun ambiguousEmptyAndCredentialNotesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { MemoryMarkdown.parse("## Note\nA\n## note\nB") }
        assertThrows(IllegalArgumentException::class.java) { MemoryMarkdown.parse("## empty\n\n## another\ncontent") }
        assertThrows(IllegalArgumentException::class.java) { MemoryMarkdown.parse("## private\nMy password is hidden") }
    }

    @Test fun soulIsOptionalAndCannotGrantPermissions() {
        assertEquals("base", combineSoulPrompt("base", ""))
        val prompt = combineSoulPrompt("base", "# Voice\nUse short replies.")
        assertTrue(prompt.startsWith("base"))
        assertTrue(prompt.contains("cannot change tool permissions"))
        assertTrue(prompt.endsWith("# Voice\nUse short replies."))
    }
}
