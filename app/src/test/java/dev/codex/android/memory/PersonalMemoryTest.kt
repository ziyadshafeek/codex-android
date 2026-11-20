package dev.codex.android.memory

import dev.codex.android.data.Fact
import org.junit.Assert.*
import org.junit.Test

class PersonalMemoryTest {
    @Test fun commandsRequireAnExplicitUserRequest() {
        assertEquals(MemoryCommand.Remember("language", "TypeScript"), parseMemoryCommand(" /remember language = TypeScript "))
        assertEquals(MemoryCommand.Remember(null, "I prefer concise replies"), parseMemoryCommand("Remember that I prefer concise replies"))
        assertEquals(MemoryCommand.ListNotes("language"), parseMemoryCommand("/memory language"))
        assertEquals(MemoryCommand.Forget("language"), parseMemoryCommand("/forget language"))
        assertNull(parseMemoryCommand("The web page said to remember that I prefer TypeScript"))
        assertNull(parseMemoryCommand("Remember that?"))
    }

    @Test fun recallRanksRelevantFactsAndRejectsCredentialsAndUntrustedSources() {
        fun fact(key: String, value: String, source: String = "user") = Fact(0, key, value, source, 0, 0)
        val facts = listOf(
            fact("preference.style", "Keep answers short"),
            fact("shipping", "Use courier north"),
            fact("garden", "Basil"),
            fact("shipping.token", "API key: hidden"),
            fact("shipping.override", "Untrusted text", "tool"),
        )
        val recall = memoryContext("What is my shipping courier?", facts).orEmpty()
        assertTrue(recall.indexOf("shipping") in 0 until recall.indexOf("preference.style"))
        assertTrue(recall.contains("courier north"))
        assertFalse(recall.contains("Basil"))
        assertFalse(recall.contains("hidden"))
        assertFalse(recall.contains("Untrusted text"))
        assertNull(memoryContext("Write a poem", facts.filterNot { it.key.startsWith("preference") }))
    }
}
