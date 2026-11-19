package dev.codex.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** JVM unit tests for the pure UPI URI and custom-amount helpers in [DonateSection.kt]. */
class DonateSectionTest {

    @Test
    fun buildUpiUri_formatsExactExpectedUriForPresetAndCustomAmounts() {
        assertEquals(
            "upi://pay?pa=ziyad.shafeek@oksbi&pn=Ziyad&am=25&cu=INR&tn=Support%20Codex",
            buildUpiUri(25),
        )
        assertEquals(
            "upi://pay?pa=ziyad.shafeek@oksbi&pn=Ziyad&am=50&cu=INR&tn=Support%20Codex",
            buildUpiUri(50),
        )
        assertEquals(
            "upi://pay?pa=ziyad.shafeek@oksbi&pn=Ziyad&am=100&cu=INR&tn=Support%20Codex",
            buildUpiUri(100),
        )
        assertEquals(
            "upi://pay?pa=ziyad.shafeek@oksbi&pn=Ziyad&am=250&cu=INR&tn=Support%20Codex",
            buildUpiUri(250),
        )
        assertEquals(
            "upi://pay?pa=ziyad.shafeek@oksbi&pn=Ziyad&am=500&cu=INR&tn=Support%20Codex",
            buildUpiUri(500),
        )
    }

    @Test
    fun buildUpiUri_rejectsZeroOrNegativeAmount() {
        assertThrows(IllegalArgumentException::class.java) {
            buildUpiUri(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildUpiUri(-50)
        }
    }

    @Test
    fun parseCustomAmount_acceptsPositiveIntsAndRejectsInvalidInput() {
        assertEquals(75, parseCustomAmount("75"))
        assertEquals(150, parseCustomAmount(" 150 "))
        assertNull(parseCustomAmount(""))
        assertNull(parseCustomAmount("0"))
        assertNull(parseCustomAmount("-10"))
        assertNull(parseCustomAmount("abc"))
    }
}
