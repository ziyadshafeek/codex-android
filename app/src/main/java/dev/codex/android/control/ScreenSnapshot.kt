package dev.codex.android.control

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Phase 2 — bounded, recycle-safe walks over an [AccessibilityNodeInfo] tree.
 *
 * Shared by [dev.codex.android.service.CodexAccessibilityService]
 * for screen reads, find-by-text, editable-field lookup and scroll targets.
 *
 * Ownership rules (the reason this file exists instead of ad-hoc loops):
 * - None of these functions recycles the [root] passed in; the caller owns it.
 * - A node a function *returns* (or puts in a returned list) is owned by the
 *   caller, who must recycle it after use. Every other node visited is
 *   recycled inside the walk.
 * - Every walk is bounded by [MAX_NODES] so a pathological screen cannot
 *   stall a low-end phone, and [collectText] is capped at [MAX_CHARS].
 * - Nothing here logs anything: node text belongs to other apps.
 */
object ScreenSnapshot {

    /** Maximum nodes visited in a single walk. */
    const val MAX_NODES = 500

    /** Maximum characters returned by [collectText]. */
    const val MAX_CHARS = 4000

    /** Maximum scrollable candidates returned by [findScrollableNodes]. */
    private const val MAX_SCROLLABLES = 10

    // ------------------------------------------------------------------
    // Text dump
    // ------------------------------------------------------------------

    /**
     * Visible text of the tree: each node's [AccessibilityNodeInfo.getText]
     * plus its content description, in traversal order, deduplicated,
     * one entry per line, capped at [MAX_CHARS]. Returns "" for a null root.
     */
    fun collectText(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val seen = LinkedHashSet<String>()
        val out = StringBuilder()
        var visited = 0

        fun add(value: CharSequence?) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isEmpty() || !seen.add(text)) return
            if (out.isNotEmpty()) out.append('\n')
            val remaining = MAX_CHARS - out.length
            if (remaining <= 0) return
            out.append(text.take(remaining))
        }

        fun walk(node: AccessibilityNodeInfo) {
            if (visited >= MAX_NODES || out.length >= MAX_CHARS) return
            visited++
            if (node.isVisibleToUser) {
                add(node.text)
                add(node.contentDescription)
            }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until childCount) {
                if (visited >= MAX_NODES || out.length >= MAX_CHARS) return
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                walk(child)
                runCatching { child.recycle() }
            }
        }

        walk(root)
        return out.toString().trim()
    }

    // ------------------------------------------------------------------
    // Find node by text
    // ------------------------------------------------------------------

    /**
     * Finds the first node whose text or content description contains
     * [query] (case-insensitive). Prefers a clickable target: the matching
     * node itself when clickable, else its nearest clickable ancestor.
     * Falls back to the first matching node of any kind.
     *
     * Returns a node owned by the caller (recycle after use), or null.
     */
    fun findByText(root: AccessibilityNodeInfo?, query: String): AccessibilityNodeInfo? {
        val needle = query.trim().lowercase()
        if (root == null || needle.isEmpty()) return null
        return findMatching(root, needle, requireClickable = true)
            ?: findMatching(root, needle, requireClickable = false)
    }

    private fun findMatching(
        root: AccessibilityNodeInfo,
        needle: String,
        requireClickable: Boolean,
    ): AccessibilityNodeInfo? {
        var visited = 0

        fun matches(node: AccessibilityNodeInfo): Boolean {
            val text = runCatching { node.text?.toString() }.getOrNull()
            val desc = runCatching { node.contentDescription?.toString() }.getOrNull()
            return (text?.lowercase()?.contains(needle) == true) ||
                (desc?.lowercase()?.contains(needle) == true)
        }

        /** The node itself if clickable, else its nearest clickable ancestor (caller-owned). */
        fun clickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (runCatching { node.isClickable }.getOrDefault(false)) return node
            var parent = runCatching { node.parent }.getOrNull()
            while (parent != null) {
                if (runCatching { parent.isClickable }.getOrDefault(false)) return parent
                val next = runCatching { parent.parent }.getOrNull()
                runCatching { parent.recycle() }
                parent = next
            }
            return null
        }

        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (visited >= MAX_NODES) return null
            visited++
            if (matches(node)) {
                if (!requireClickable) return node
                val target = clickableTarget(node)
                if (target != null) return target
                // Matching but not clickable anywhere up the chain: keep
                // looking — a descendant may be the clickable target.
            }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until childCount) {
                if (visited >= MAX_NODES) return null
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                val result = walk(child)
                if (result != null) {
                    // The found node may be the child itself (leave it for
                    // the caller) or an ancestor of it (recycle the child).
                    if (result !== child) runCatching { child.recycle() }
                    return result
                }
                runCatching { child.recycle() }
            }
            return null
        }

        return walk(root)
    }

    // ------------------------------------------------------------------
    // Editable field
    // ------------------------------------------------------------------

    /**
     * Finds the editable field to type into: first the focused editable
     * field, otherwise any editable field. Returns a caller-owned node,
     * or null when the screen has no editable field.
     */
    fun findFocusedEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        return findEditable(root, requireFocus = true)
            ?: findEditable(root, requireFocus = false)
    }

    private fun findEditable(root: AccessibilityNodeInfo, requireFocus: Boolean): AccessibilityNodeInfo? {
        var visited = 0

        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (visited >= MAX_NODES) return null
            visited++
            val editable = runCatching { node.isEditable }.getOrDefault(false)
            val focused = runCatching { node.isFocused }.getOrDefault(false)
            if (editable && (!requireFocus || focused)) return node
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until childCount) {
                if (visited >= MAX_NODES) return null
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                val result = walk(child)
                if (result != null) {
                    if (result !== child) runCatching { child.recycle() }
                    return result
                }
                runCatching { child.recycle() }
            }
            return null
        }

        return walk(root)
    }

    // ------------------------------------------------------------------
    // Scrollable nodes
    // ------------------------------------------------------------------

    /**
     * All scrollable nodes in the tree (pre-order, at most [MAX_SCROLLABLES]).
     * Every returned node is caller-owned and must be recycled after use.
     */
    fun findScrollableNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val found = ArrayList<AccessibilityNodeInfo>()
        var visited = 0

        fun walk(node: AccessibilityNodeInfo) {
            if (visited >= MAX_NODES || found.size >= MAX_SCROLLABLES) return
            visited++
            if (runCatching { node.isScrollable }.getOrDefault(false)) {
                found.add(node)
            }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until childCount) {
                if (visited >= MAX_NODES || found.size >= MAX_SCROLLABLES) return
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                walk(child)
                if (found.none { it === child }) runCatching { child.recycle() }
            }
        }

        walk(root)
        return found
    }
}
