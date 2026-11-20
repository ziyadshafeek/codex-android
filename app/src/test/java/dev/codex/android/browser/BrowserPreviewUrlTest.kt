package dev.codex.android.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserPreviewUrlTest {
    @Test fun normalisesWebAndLocalPreviewAddresses() {
        assertEquals("https://example.com/", BrowserJs.webUrl("example.com"))
        assertEquals("http://localhost:3000/", BrowserJs.webUrl("localhost:3000"))
        assertNull(BrowserJs.webUrl("file:///data/local/tmp/example.html"))
        assertNull(BrowserJs.webUrl("https://person:password@example.com"))
    }

    @Test fun domSnapshotDoesNotReadFormValues() {
        assertFalse(BrowserJs.buildDomSnapshotScript(100).contains("el.value"))
    }
}
