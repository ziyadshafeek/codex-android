package dev.codex.android.tools

import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Diagnostic + regression test for the PdfBox-Android resources the
 * document tests need (Worker I's DocumentTools/PdfTools).
 *
 * PdfBox-Android loads its standard-14 AFM files and its glyph list
 * through the classloader when PDFBoxResourceLoader is not initialised
 * (the JVM unit-test case): `SomeClass.getResourceAsStream("/" + path)`.
 * The files live in app/src/test/resources so Gradle puts them on the
 * unit-test runtime classpath. If this test fails in CI, the classpath
 * does not contain the test resources — see the printed classpath.
 */
class PdfBoxResourceProbeTest {

    @Test
    fun probe() {
        val cp = System.getProperty("java.class.path") ?: ""
        println("TEST RUNTIME CLASSPATH:")
        cp.split(":").forEach { println("  $it") }
        val afm = javaClass.getResourceAsStream(
            "/com/tom_roush/pdfbox/resources/afm/Helvetica.afm",
        )
        println("AFM Helvetica.afm: ${if (afm == null) "MISSING" else "found (${afm.available()} bytes available)"}")
        val glyph = javaClass.getResourceAsStream(
            "/com/tom_roush/pdfbox/resources/glyphlist/glyphlist.txt",
        )
        println("glyphlist.txt: ${if (glyph == null) "MISSING" else "found (${glyph.available()} bytes available)"}")
        assertNotNull("Helvetica.afm must be on the unit-test classpath (app/src/test/resources)", afm)
        assertNotNull("glyphlist.txt must be on the unit-test classpath (app/src/test/resources)", glyph)
        afm?.close()
        glyph?.close()
    }
}
