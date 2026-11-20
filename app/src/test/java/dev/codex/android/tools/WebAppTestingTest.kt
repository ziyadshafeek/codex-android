package dev.codex.android.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for [WebAppTesting]: script generation and result parsing. */
class WebAppTestingTest {

    @Test
    fun defaultScript_usesSystemChromiumAndReportsOneResultLine() {
        val script = WebAppTesting.buildPlaywrightScript(
            "https://example.com",
            listOf(WebCheck("heading", "h1", "Hello")),
        )
        assertTrue(script.contains("require(\"playwright\")"))
        assertTrue(script.contains("PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH"))
        assertTrue(script.contains("WEBAPP_TEST_RESULT"))
        assertTrue(script.contains("\"heading\""))
        assertTrue(script.contains("\"h1\""))
        assertTrue(script.contains("\"Hello\""))
        assertTrue(script.contains("--no-sandbox"))
    }

    @Test
    fun defaultScript_withNoChecks_onlyProvesPageLoad() {
        val script = WebAppTesting.buildPlaywrightScript("https://example.com", emptyList())
        assertTrue(script.contains("page.title()"))
        assertTrue(script.contains("CHECKS.length === 0"))
    }

    @Test
    fun parsePassingOutput_isOk() {
        val output = "some log\nWEBAPP_TEST_RESULT {\"passed\":2,\"failed\":0,\"failures\":[]}\n"
        val result = WebAppTesting.parsePlaywrightOutput(0, output)
        assertTrue(result.ok)
        assertEquals("playwright", result.runner)
        assertEquals(2, result.passed)
        assertEquals(0, result.failed)
        assertTrue(result.failures.isEmpty())
        assertTrue(result.summary.contains("all 2 check(s) passed"))
    }

    @Test
    fun parseFailingOutput_carriesTheFailingAssertion() {
        val output = "WEBAPP_TEST_RESULT {\"passed\":1,\"failed\":1,\"failures\":[\"heading: selector 'h1' not found on https://example.com\"]}\n"
        val result = WebAppTesting.parsePlaywrightOutput(1, output)
        assertFalse(result.ok)
        assertEquals(1, result.passed)
        assertEquals(1, result.failed)
        assertTrue(result.failures[0].contains("h1"))
        assertTrue(result.summary.contains("h1"))
    }

    @Test
    fun parseMissingResultLine_isRunnerFailureNotAPass() {
        val result = WebAppTesting.parsePlaywrightOutput(
            1,
            "Error: Cannot find module 'playwright'\n",
        )
        assertFalse(result.ok)
        assertEquals(1, result.failed)
        assertTrue(result.summary.contains("no test result"))
        assertTrue(result.failures[0].contains("Cannot find module"))
    }

    @Test
    fun parseUnparseableResultLine_isRunnerFailure() {
        val result = WebAppTesting.parsePlaywrightOutput(0, "WEBAPP_TEST_RESULT not-json\n")
        assertFalse(result.ok)
        assertTrue(result.summary.contains("could not be parsed"))
    }

    @Test
    fun isPlaywrightMissing_detectsBothQuoteStyles() {
        assertTrue(WebAppTesting.isPlaywrightMissing("Error: Cannot find module 'playwright'"))
        assertTrue(WebAppTesting.isPlaywrightMissing("Error: Cannot find module \"playwright\""))
        assertFalse(WebAppTesting.isPlaywrightMissing("WEBAPP_TEST_RESULT {\"passed\":1,\"failed\":0,\"failures\":[]}"))
    }

    @Test
    fun parseChecks_rejectsMalformedInput() {
        try {
            WebAppTesting.parseChecks("nope")
            assertFalse("expected IllegalArgumentException", true)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("JSON"))
        }
        try {
            WebAppTesting.parseChecks("[{\"name\":\"x\"}]")
            assertFalse("expected IllegalArgumentException", true)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("selector"))
        }
    }

    @Test
    fun parseChecks_readsExpectText() {
        val checks = WebAppTesting.parseChecks(
            """[{"name":"h","selector":"h1","expectText":"Hi"}]""",
        )
        assertEquals(listOf(WebCheck("h", "h1", "Hi")), checks)
        assertEquals(emptyList<WebCheck>(), WebAppTesting.parseChecks(""))
    }

    @Test
    fun scriptFileName_isStableAndSafe() {
        val name = WebAppTesting.scriptFileName("https://example.com/some page?x=1")
        assertTrue(name.startsWith("test-"))
        assertTrue(name.endsWith(".cjs"))
        assertFalse(name.contains(" "))
        assertFalse(name.contains("?"))
    }
}
