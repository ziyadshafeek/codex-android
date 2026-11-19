package dev.codex.android.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [GlobPermissionEngine]: last-match-wins, `**` vs `*` vs `?`,
 * case sensitivity, default DENY, and immutable non-widening [GlobPermissionEngine.intersect].
 */
class GlobPermissionEngineTest {

    @Test
    fun defaultDecision_isDenyWhenNoRuleMatches() {
        val empty = GlobPermissionEngine(emptyList())
        assertEquals(GlobDecision.DENY, empty.decide("src/Main.kt"))

        val partial = GlobPermissionEngine(
            listOf(GlobRule("src/*.kt", GlobDecision.ALLOW)),
        )
        assertEquals(GlobDecision.DENY, partial.decide("README.md"))
    }

    @Test
    fun orderedRules_lastMatchWins() {
        val engine = GlobPermissionEngine(
            listOf(
                GlobRule("**", GlobDecision.ALLOW),
                GlobRule("**/.env*", GlobDecision.DENY),
                GlobRule("**/.env.example", GlobDecision.ALLOW),
                GlobRule("**/*.pem", GlobDecision.ASK),
            ),
        )

        assertEquals(GlobDecision.ALLOW, engine.decide("app/src/Main.kt"))
        assertEquals(GlobDecision.DENY, engine.decide(".env"))
        assertEquals(GlobDecision.DENY, engine.decide("config/.env.local"))
        assertEquals(GlobDecision.ALLOW, engine.decide("config/.env.example"))
        assertEquals(GlobDecision.ASK, engine.decide("certs/server.pem"))
    }

    @Test
    fun globSyntax_doubleStarVsSingleStarAndQuestionMark_caseSensitive() {
        val singleStar = GlobPermissionEngine(
            listOf(GlobRule("src/*.kt", GlobDecision.ALLOW)),
        )
        assertEquals(GlobDecision.ALLOW, singleStar.decide("src/Main.kt"))
        assertEquals(GlobDecision.DENY, singleStar.decide("src/sub/Main.kt"))
        assertEquals(GlobDecision.DENY, singleStar.decide("src/Main.KT"))

        val doubleStar = GlobPermissionEngine(
            listOf(GlobRule("src/**/*.kt", GlobDecision.ALLOW)),
        )
        assertEquals(GlobDecision.ALLOW, doubleStar.decide("src/Main.kt"))
        assertEquals(GlobDecision.ALLOW, doubleStar.decide("src/sub/Main.kt"))
        assertEquals(GlobDecision.ALLOW, doubleStar.decide("src/a/b/c/Main.kt"))
        assertEquals(GlobDecision.DENY, doubleStar.decide("other/Main.kt"))

        val question = GlobPermissionEngine(
            listOf(GlobRule("file?.txt", GlobDecision.ALLOW)),
        )
        assertEquals(GlobDecision.ALLOW, question.decide("file1.txt"))
        assertEquals(GlobDecision.ALLOW, question.decide("fileA.txt"))
        assertEquals(GlobDecision.DENY, question.decide("file.txt"))
        assertEquals(GlobDecision.DENY, question.decide("file12.txt"))
        assertEquals(GlobDecision.DENY, question.decide("file/.txt"))
    }

    @Test
    fun intersect_childNeverWidensParentAndMayOnlyRestrict() {
        val parent = GlobPermissionEngine(
            listOf(
                GlobRule("**", GlobDecision.ALLOW),
                GlobRule("**/.env*", GlobDecision.DENY),
                GlobRule("gradle/**", GlobDecision.ASK),
            ),
        )
        val child = GlobPermissionEngine(
            listOf(
                // Attempts to widen .env* to ALLOW and gradle/** to ALLOW.
                GlobRule("**", GlobDecision.ALLOW),
                GlobRule("**/.env*", GlobDecision.ALLOW),
                GlobRule("gradle/**", GlobDecision.ALLOW),
                // Legitimately restricts secrets/** to DENY and docs/** to ASK.
                GlobRule("secrets/**", GlobDecision.DENY),
                GlobRule("docs/**", GlobDecision.ASK),
            ),
        )

        val effective = parent.intersect(child)

        // Both allow -> ALLOW.
        assertEquals(GlobDecision.ALLOW, effective.decide("src/App.kt"))
        // Parent DENY beats child ALLOW -> stays DENY.
        assertEquals(GlobDecision.DENY, effective.decide(".env"))
        assertEquals(GlobDecision.DENY, effective.decide("app/.env.prod"))
        // Parent ASK beats child ALLOW -> stays ASK.
        assertEquals(GlobDecision.ASK, effective.decide("gradle/wrapper.properties"))
        // Child restrictions narrow parent ALLOW -> DENY / ASK.
        assertEquals(GlobDecision.DENY, effective.decide("secrets/token.txt"))
        assertEquals(GlobDecision.ASK, effective.decide("docs/guide.md"))

        // Child attempting ASK on parent DENY must stay DENY.
        val askChild = GlobPermissionEngine(
            listOf(
                GlobRule("**", GlobDecision.ALLOW),
                GlobRule("**/.env*", GlobDecision.ASK),
            ),
        )
        assertEquals(GlobDecision.DENY, parent.intersect(askChild).decide(".env"))

        // Narrow child scope (default DENY outside src/main/**) restricts parent.
        val scopedChild = GlobPermissionEngine(
            listOf(GlobRule("src/main/**", GlobDecision.ALLOW)),
        )
        val scopedEffective = parent.intersect(scopedChild)
        assertEquals(GlobDecision.ALLOW, scopedEffective.decide("src/main/Main.kt"))
        assertEquals(GlobDecision.DENY, scopedEffective.decide("src/test/MainTest.kt"))

        // Original parent/child engines remain completely immutable.
        assertEquals(GlobDecision.ALLOW, parent.decide("secrets/token.txt"))
        assertEquals(GlobDecision.ALLOW, child.decide(".env"))
    }

    @Test
    fun globSyntax_doubleStarSlashMatchesZeroDirectories() {
        val engine = GlobPermissionEngine(
            listOf(GlobRule("**/config.yaml", GlobDecision.ALLOW)),
        )
        assertEquals(GlobDecision.ALLOW, engine.decide("config.yaml"))
        assertEquals(GlobDecision.ALLOW, engine.decide("a/config.yaml"))
        assertEquals(GlobDecision.ALLOW, engine.decide("a/b/c/config.yaml"))
        assertEquals(GlobDecision.DENY, engine.decide("other.yaml"))

        val mid = GlobPermissionEngine(
            listOf(GlobRule("a/**/b.txt", GlobDecision.ALLOW)),
        )
        assertEquals(GlobDecision.ALLOW, mid.decide("a/b.txt"))
        assertEquals(GlobDecision.ALLOW, mid.decide("a/x/b.txt"))
        assertEquals(GlobDecision.ALLOW, mid.decide("a/x/y/b.txt"))
        assertEquals(GlobDecision.DENY, mid.decide("other/b.txt"))
    }

    @Test
    fun globSyntax_questionMarkMatchesExactlyOneNonSlashChar() {
        val engine = GlobPermissionEngine(
            listOf(GlobRule("a?b", GlobDecision.ALLOW)),
        )
        assertEquals(GlobDecision.ALLOW, engine.decide("a.b"))
        assertEquals(GlobDecision.ALLOW, engine.decide("a1b"))
        assertEquals(GlobDecision.DENY, engine.decide("a/b"))
        assertEquals(GlobDecision.DENY, engine.decide("ab"))
        assertEquals(GlobDecision.DENY, engine.decide("a12b"))
    }

    @Test
    fun caseSensitivity_patternCaseMustMatch() {
        val upper = GlobPermissionEngine(
            listOf(GlobRule("SRC/*.kt", GlobDecision.ALLOW)),
        )
        assertEquals(GlobDecision.DENY, upper.decide("src/Main.kt"))
        assertEquals(GlobDecision.ALLOW, upper.decide("SRC/Main.kt"))

        val extCase = GlobPermissionEngine(
            listOf(GlobRule("src/*.KT", GlobDecision.ALLOW)),
        )
        assertEquals(GlobDecision.DENY, extCase.decide("src/Main.kt"))
        assertEquals(GlobDecision.ALLOW, extCase.decide("src/Main.KT"))
    }

    @Test
    fun intersect_neverWidens_propertyAcrossPathSet() {
        fun rank(d: GlobDecision): Int = when (d) {
            GlobDecision.ALLOW -> 0
            GlobDecision.ASK -> 1
            GlobDecision.DENY -> 2
        }
        fun moreRestrictive(a: GlobDecision, b: GlobDecision): GlobDecision =
            if (rank(a) >= rank(b)) a else b

        val paths = listOf(
            "src/App.kt",
            ".env",
            "app/.env.prod",
            "gradle/wrapper.properties",
            "secrets/token.txt",
            "docs/guide.md",
            "README.md",
            "a/b/c.txt",
            "x.env",
            ".env.example",
        )
        val pairs = listOf(
            GlobPermissionEngine(listOf(GlobRule("**", GlobDecision.ALLOW))) to
                GlobPermissionEngine(
                    listOf(
                        GlobRule("**", GlobDecision.ALLOW),
                        GlobRule("**/.env*", GlobDecision.DENY),
                    ),
                ),
            GlobPermissionEngine(
                listOf(
                    GlobRule("**", GlobDecision.ALLOW),
                    GlobRule("gradle/**", GlobDecision.ASK),
                ),
            ) to GlobPermissionEngine(listOf(GlobRule("**", GlobDecision.ALLOW))),
            GlobPermissionEngine(
                listOf(
                    GlobRule("**", GlobDecision.ALLOW),
                    GlobRule("secrets/**", GlobDecision.DENY),
                ),
            ) to GlobPermissionEngine(
                listOf(
                    GlobRule("**", GlobDecision.ALLOW),
                    GlobRule("docs/**", GlobDecision.ASK),
                    GlobRule("**/.env*", GlobDecision.ALLOW),
                ),
            ),
        )

        for ((parent, child) in pairs) {
            val effective = parent.intersect(child)
            for (path in paths) {
                val expected = moreRestrictive(parent.decide(path), child.decide(path))
                assertEquals(
                    "intersect must equal moreRestrictive(parent, child) for $path",
                    expected,
                    effective.decide(path),
                )
            }
            // The intersected engine is a new instance; inputs are untouched.
            assertTrue(effective !== parent)
            assertTrue(effective !== child)
        }
    }
}
