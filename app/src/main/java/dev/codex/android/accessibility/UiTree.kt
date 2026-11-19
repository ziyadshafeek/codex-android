package dev.codex.android.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import dev.codex.android.core.CodexJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Subsystem 8 (Master Plan M8/M9) — structured UI tree for phone computer-use.
 *
 * Replaces the old flat text dump with a numbered, serializable tree the
 * model can act on precisely: every actionable element has a stable `id`
 * and screen bounds, so `tap` / `tap_node` can target it without guessing
 * coordinates from prose.
 *
 * Pattern credit (open-source check, 2026-11-19):
 * - OpenPhoneUse (MIT, joungminsung/OpenPhoneUse) — one numbered line per
 *   element, `[3] Button "Search" clickable (540,212)`; accessibility tree
 *   + screenshot as dual screen reads; re-check the live screen before
 *   acting. The compact format below follows that line shape directly.
 * - Zafiro (MIT, niki914/zafiro) — AccessibilityService node-tree
 *   extraction + gesture dispatch; its two-phase UI-stability detection
 *   (event-stream idle, then tree-hash stable) was noted but NOT ported:
 *   our reads are single-shot and bounded, and a settle-wait belongs in a
 *   later step, not in the tree builder.
 * - Deft (MIT, bedda-tech/deft) — AccessibilityService + MediaProjection
 *   screenshot fallback; its React Native TurboModule bridge and on-device
 *   Gemma/ExecuTorch inference were NOT reusable (native Kotlin + BYOK
 *   cloud model here). The MediaProjection-fallback idea itself is used in
 *   [dev.codex.android.service.MediaProjectionCapture].
 * - OpenCode has no phone computer-use at all — nothing to reuse there.
 *
 * Ownership / recycle rules (same contract as ScreenSnapshot):
 * - [buildUiTree] never recycles the [root] passed in; the caller owns it.
 *   Every child node visited while building is recycled inside the walk.
 * - [findLiveNodeById] returns a caller-owned node (recycle after use),
 *   or the [root] itself for id 0 (do NOT recycle that one — the caller
 *   owns the root). Every other visited node is recycled inside the walk.
 * - Both walks must stay in lock-step: pre-order depth-first, children in
 *   index order, only [includeNode] nodes get ids. If one changes, the
 *   other must change identically or node ids will point at the wrong
 *   element.
 */
@Serializable
data class UiNode(
    /** Pre-order id, stable for one screen snapshot; 0 is the root. */
    val id: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val text: String = "",
    val contentDescription: String = "",
    val className: String = "",
    val clickable: Boolean = false,
    val enabled: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val focused: Boolean = false,
    val children: List<UiNode> = emptyList(),
) {
    /** Horizontal centre of the node's on-screen bounds. */
    fun centerX(): Int = (left + right) / 2

    /** Vertical centre of the node's on-screen bounds. */
    fun centerY(): Int = (top + bottom) / 2

    /** Number of nodes in this subtree, including this node. */
    fun countNodes(): Int = 1 + children.sumOf { it.countNodes() }

    /** Finds the node with [nodeId] in this subtree, or null. Pure. */
    fun findById(nodeId: Int): UiNode? {
        if (id == nodeId) return this
        for (child in children) {
            val found = child.findById(nodeId)
            if (found != null) return found
        }
        return null
    }

    /**
     * Compact one-line-per-node serialisation for the model, in the
     * OpenPhoneUse shape: `[3] Button "Search" clickable (540,212)`.
     * Pure — works on a plain data tree, which is why it has a JVM test.
     * Capped at [maxLines] lines / [maxChars] chars so a pathological
     * screen cannot blow up the context window.
     */
    fun toCompactText(maxLines: Int = 200, maxChars: Int = 6000): String {
        val out = StringBuilder()
        var lines = 0
        fun shortClass(name: String): String = name.substringAfterLast('.')
        fun quote(value: String): String {
            val clean = value.replace('\n', ' ').trim().take(MAX_LABEL_CHARS)
            return "\"$clean\""
        }
        fun walk(node: UiNode, depth: Int) {
            if (lines >= maxLines || out.length >= maxChars) return
            lines++
            if (out.isNotEmpty()) out.append('\n')
            repeat(depth) { out.append("  ") }
            out.append('[').append(node.id).append("] ")
            val cls = shortClass(node.className)
            if (cls.isNotEmpty()) out.append(cls).append(' ')
            if (node.text.isNotBlank()) out.append(quote(node.text)).append(' ')
            if (node.contentDescription.isNotBlank()) {
                out.append('(').append(node.contentDescription.replace('\n', ' ').trim().take(MAX_LABEL_CHARS)).append(") ")
            }
            out.append('(').append(node.centerX()).append(',').append(node.centerY()).append(')')
            if (node.clickable) out.append(" clickable")
            if (node.editable) out.append(" editable")
            if (node.scrollable) out.append(" scrollable")
            if (node.focused) out.append(" focused")
            if (!node.enabled) out.append(" disabled")
            val remaining = maxChars - out.length
            if (remaining <= 0) return
            for (child in node.children) {
                if (lines >= maxLines || out.length >= maxChars) return
                walk(child, depth + 1)
            }
        }
        walk(this, 0)
        return out.toString()
    }

    /** Full JSON serialisation of the tree (for logging/debugging, not the model). */
    fun toJson(): String = CodexJson.encodeToString(this)

    companion object {
        /** Labels longer than this are truncated in [toCompactText]. */
        const val MAX_LABEL_CHARS = 60

        /** Maximum live nodes visited in a single tree walk. */
        const val MAX_NODES = 500

        /** Maximum tree depth walked (guards against pathological screens). */
        private const val MAX_DEPTH = 32

        /**
         * Builds a structured tree from a live [AccessibilityNodeInfo]
         * root. Returns null when [root] is null. Pure-data result: the
         * returned tree holds no live node references.
         */
        fun buildUiTree(root: AccessibilityNodeInfo?): UiNode? {
            if (root == null) return null
            // The root is ALWAYS id 0 — included or not — so ids match
            // [findLiveNodeById], which also seeds its counter at 1
            // after treating the root as id 0. When the root itself is
            // excluded (invisible), a synthetic "Root" node holds id 0
            // and the visible children are promoted under it.
            var nextId = 1
            var visited = 1 // the root itself

            // Local funs must be declared before use: snapshot first.
            fun snapshot(node: AccessibilityNodeInfo, id: Int): MutableNode {
                val bounds = Rect()
                runCatching { node.getBoundsInScreen(bounds) }
                return MutableNode(
                    id = id,
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    text = runCatching { node.text?.toString().orEmpty() }.getOrDefault(""),
                    contentDescription = runCatching { node.contentDescription?.toString().orEmpty() }.getOrDefault(""),
                    className = runCatching { node.className?.toString().orEmpty() }.getOrDefault(""),
                    clickable = runCatching { node.isClickable }.getOrDefault(false),
                    enabled = runCatching { node.isEnabled }.getOrDefault(false),
                    editable = runCatching { node.isEditable }.getOrDefault(false),
                    scrollable = runCatching { node.isScrollable }.getOrDefault(false),
                    focused = runCatching { node.isFocused }.getOrDefault(false),
                )
            }

            val rootBuilder: MutableNode = if (includeNode(root)) {
                snapshot(root, 0)
            } else {
                MutableNode(id = 0, className = "Root")
            }

            fun walk(node: AccessibilityNodeInfo, parent: MutableNode, depth: Int) {
                if (visited >= MAX_NODES || depth > MAX_DEPTH) return
                visited++
                // An included node becomes a tree node; an excluded one is
                // transparent — its children attach to its nearest
                // included ancestor.
                val self: MutableNode = if (includeNode(node)) {
                    val built = snapshot(node, nextId++)
                    parent.children.add(built)
                    built
                } else {
                    parent
                }
                val childCount = runCatching { node.childCount }.getOrDefault(0)
                for (i in 0 until childCount) {
                    if (visited >= MAX_NODES) return
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    walk(child, self, depth + 1)
                    runCatching { child.recycle() }
                }
            }

            val childCount = runCatching { root.childCount }.getOrDefault(0)
            for (i in 0 until childCount) {
                if (visited >= MAX_NODES) break
                val child = runCatching { root.getChild(i) }.getOrNull() ?: continue
                walk(child, rootBuilder, 1)
                runCatching { child.recycle() }
            }
            return rootBuilder.toUiNode()
        }

        /**
         * Finds the live node with pre-order [id] in the current tree,
         * using the exact same walk order and [includeNode] rule as
         * [buildUiTree] — so an id from [UiNode.toCompactText] points at
         * the same element here. Returns a caller-owned node (recycle
         * after use), the [root] itself for id 0 (do NOT recycle it), or
         * null when the id does not exist.
         */
        fun findLiveNodeById(root: AccessibilityNodeInfo?, id: Int): AccessibilityNodeInfo? {
            if (root == null || id < 0) return null
            if (id == 0) {
                return if (runCatching { root.isVisibleToUser }.getOrDefault(false)) root else null
            }
            // Id 0 is the root (handled above); ids 1.. are the included
            // descendants in pre-order, exactly as [buildUiTree] assigns.
            // visited seeds at 1, like buildUiTree: the root itself counts
            // as visited, so the MAX_NODES cap trips at the same node in
            // both walks and an id from toCompactText always resolves.
            var nextId = 1
            var visited = 1

            fun walk(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
                if (visited >= MAX_NODES || depth > MAX_DEPTH) return null
                visited++
                if (includeNode(node)) {
                    if (nextId++ == id) return node
                }
                val childCount = runCatching { node.childCount }.getOrDefault(0)
                for (i in 0 until childCount) {
                    if (visited >= MAX_NODES) return null
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    val found = walk(child, depth + 1)
                    if (found != null) {
                        if (found !== child) runCatching { child.recycle() }
                        return found
                    }
                    runCatching { child.recycle() }
                }
                return null
            }

            // The root itself is id 0 and is handled above; the walk
            // starts at the root's children. Same outer break as
            // buildUiTree: once the cap is hit, no further subtree is
            // entered (walk would return null at once anyway).
            val childCount = runCatching { root.childCount }.getOrDefault(0)
            for (i in 0 until childCount) {
                if (visited >= MAX_NODES) break
                val child = runCatching { root.getChild(i) }.getOrNull() ?: continue
                val found = walk(child, 1)
                if (found != null) {
                    if (found !== child) runCatching { child.recycle() }
                    return found
                }
                runCatching { child.recycle() }
            }
            return null
        }

        /**
         * Which live nodes become tree nodes. A node is included when it
         * is visible to the user AND it carries information or is
         * actionable: text, a content description, clickable / editable /
         * scrollable / focused — or it is a leaf (an icon with no label
         * is still a tappable target by coordinates). Pure layout
         * containers with children and no text are transparent: their
         * children are promoted to the nearest included ancestor.
         * MUST match in [buildUiTree] and [findLiveNodeById].
         */
        private fun includeNode(node: AccessibilityNodeInfo): Boolean {
            if (!runCatching { node.isVisibleToUser }.getOrDefault(false)) return false
            if (!runCatching { node.text }.getOrNull().isNullOrBlank()) return true
            if (!runCatching { node.contentDescription }.getOrNull().isNullOrBlank()) return true
            if (runCatching { node.isClickable }.getOrDefault(false)) return true
            if (runCatching { node.isEditable }.getOrDefault(false)) return true
            if (runCatching { node.isScrollable }.getOrDefault(false)) return true
            if (runCatching { node.isFocused }.getOrDefault(false)) return true
            return runCatching { node.childCount }.getOrDefault(0) == 0
        }
    }
}

/** Mutable builder used only while walking a live tree; converted to [UiNode] at the end. */
private class MutableNode(
    var id: Int = -1,
    var left: Int = 0,
    var top: Int = 0,
    var right: Int = 0,
    var bottom: Int = 0,
    var text: String = "",
    var contentDescription: String = "",
    var className: String = "",
    var clickable: Boolean = false,
    var enabled: Boolean = false,
    var editable: Boolean = false,
    var scrollable: Boolean = false,
    var focused: Boolean = false,
    val children: MutableList<MutableNode> = mutableListOf(),
) {
    fun toUiNode(): UiNode = UiNode(
        id = id,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        text = text,
        contentDescription = contentDescription,
        className = className,
        clickable = clickable,
        enabled = enabled,
        editable = editable,
        scrollable = scrollable,
        focused = focused,
        children = children.map { it.toUiNode() },
    )
}
