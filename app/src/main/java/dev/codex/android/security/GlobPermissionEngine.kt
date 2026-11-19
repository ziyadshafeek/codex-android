package dev.codex.android.security

/** Decision returned by [GlobPermissionEngine] for a path or target string. */
enum class GlobDecision {
    ALLOW,
    ASK,
    DENY,
}

/** One ordered glob rule: if [pattern] matches the path, [decision] applies. */
data class GlobRule(
    val pattern: String,
    val decision: GlobDecision,
)

/**
 * Ordered glob permission engine (OpenCode alignment): rules are evaluated in
 * order and the LAST matching rule wins; if no rule matches, the default is
 * [GlobDecision.DENY].
 *
 * Glob syntax (case-sensitive):
 * - `*` matches any sequence of characters except `/`.
 * - `**` matches any sequence of characters including `/` (and a double
 *   star followed by `/` matches zero or more leading/intermediate
 *   directory segments).
 * - `?` matches any single character except `/`.
 *
 * [intersect] composes parent and child engines immutably so a child engine
 * (e.g. a subagent) can ONLY restrict, never widen, its parent's decision.
 */
class GlobPermissionEngine private constructor(
    val rules: List<GlobRule>,
    private val layers: List<List<CompiledRule>>,
) {

    constructor(rules: List<GlobRule>) : this(
        rules = rules.toList(),
        layers = listOf(rules.map { CompiledRule(it, compileGlob(it.pattern)) }),
    )

    /**
     * Evaluates [path] and returns the effective [GlobDecision].
     * Defaults to [GlobDecision.DENY] when no rule matches.
     */
    fun decide(path: String): GlobDecision {
        var effective = GlobDecision.ALLOW
        for (layer in layers) {
            val layerDecision = evaluateLayer(layer, path)
            effective = moreRestrictive(effective, layerDecision)
            if (effective == GlobDecision.DENY) return GlobDecision.DENY
        }
        return effective
    }

    /**
     * Returns a new immutable [GlobPermissionEngine] representing the
     * intersection of `this` (parent) and [child].
     *
     * For every path `p`, `parent.intersect(child).decide(p)` equals the more
     * restrictive of `parent.decide(p)` and `child.decide(p)`
     * (`DENY` > `ASK` > `ALLOW`): a child rule may only restrict, never widen.
     */
    fun intersect(child: GlobPermissionEngine): GlobPermissionEngine {
        val intersectedRules = buildIntersectedRuleList(this.rules, child.rules)
        val combinedLayers = this.layers + child.layers
        return GlobPermissionEngine(
            rules = intersectedRules,
            layers = combinedLayers,
        )
    }

    private data class CompiledRule(
        val rule: GlobRule,
        val regex: Regex,
    )

    companion object {
        private fun evaluateLayer(layer: List<CompiledRule>, path: String): GlobDecision {
            var decision = GlobDecision.DENY
            for (compiled in layer) {
                if (compiled.regex.matches(path)) {
                    decision = compiled.rule.decision
                }
            }
            return decision
        }

        private fun restrictionRank(decision: GlobDecision): Int = when (decision) {
            GlobDecision.ALLOW -> 0
            GlobDecision.ASK -> 1
            GlobDecision.DENY -> 2
        }

        private fun moreRestrictive(a: GlobDecision, b: GlobDecision): GlobDecision =
            if (restrictionRank(a) >= restrictionRank(b)) a else b

        /**
         * Builds an immutable rule-list representation for the intersected
         * engine without mutating either input list. When either side is empty
         * (unconditional default DENY), the intersection is empty.
         */
        private fun buildIntersectedRuleList(
            parentRules: List<GlobRule>,
            childRules: List<GlobRule>,
        ): List<GlobRule> {
            if (parentRules.isEmpty() || childRules.isEmpty()) return emptyList()
            val childRestrictions = childRules.filter { it.decision != GlobDecision.ALLOW }
            val parentDenies = parentRules.filter { it.decision == GlobDecision.DENY }
            return buildList {
                addAll(parentRules)
                addAll(childRestrictions)
                addAll(parentDenies)
            }
        }

        /**
         * Compiles a case-sensitive glob pattern supporting `*` (non-`/`),
         * `**` (any chars incl. `/`), and `?` (one non-`/` char).
         */
        internal fun compileGlob(pattern: String): Regex {
            val sb = StringBuilder(pattern.length * 2 + 4)
            sb.append("\\A")
            var i = 0
            while (i < pattern.length) {
                when (val ch = pattern[i]) {
                    '*' -> {
                        var starEnd = i + 1
                        while (starEnd < pattern.length && pattern[starEnd] == '*') {
                            starEnd++
                        }
                        val isDoubleStar = (starEnd - i) >= 2
                        if (isDoubleStar) {
                            if (starEnd < pattern.length && pattern[starEnd] == '/') {
                                // `**/` matches zero or more directories.
                                sb.append("(?:.*/)?")
                                i = starEnd + 1
                            } else {
                                sb.append(".*")
                                i = starEnd
                            }
                        } else {
                            sb.append("[^/]*")
                            i = starEnd
                        }
                    }
                    '?' -> {
                        sb.append("[^/]")
                        i++
                    }
                    '\\', '.', '^', '$', '+', '(', ')', '[', ']', '{', '}', '|' -> {
                        sb.append('\\').append(ch)
                        i++
                    }
                    else -> {
                        sb.append(ch)
                        i++
                    }
                }
            }
            sb.append("\\z")
            return Regex(sb.toString(), setOf(RegexOption.DOT_MATCHES_ALL))
        }
    }
}
