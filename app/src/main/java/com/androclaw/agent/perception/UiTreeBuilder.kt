package com.androclaw.agent.perception

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Builds a compact UiSnapshot from AccessibilityNodeInfo trees.
 * Prunes: invisible nodes, nodes with no useful content, duplicate subtrees.
 */
object UiTreeBuilder {

    private const val MAX_DEPTH = 20
    private const val MAX_NODES = 300

    private var nodeIdCounter = 0
    // Map from nodeId -> AccessibilityNodeInfo for action execution
    private val nodeRegistry = mutableMapOf<Int, AccessibilityNodeInfo>()

    /**
     * Build a fresh snapshot from the accessibility windows.
     * Caller must call clearRegistry() after using the registry for actions.
     */
    @Synchronized
    fun buildSnapshot(
        windows: List<AccessibilityWindowInfo>,
        activeRoot: AccessibilityNodeInfo? = null,
        packageName: String,
        activityName: String
    ): UiSnapshot {
        nodeIdCounter = 0
        nodeRegistry.clear()

        val roots = mutableListOf<UiNode>()
        var totalNodes = 0

        for (window in windows) {
            if (totalNodes >= MAX_NODES) break
            // The IME keyboard is not part of the app UI: it must never feed the
            // snapshot the LLM plans from (it turns typing targets into keys).
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = window.root ?: continue
            try {
                val node = buildNode(root, 0, totalNodes)
                if (node != null) {
                    roots.add(node)
                    totalNodes += node.flatten().size
                }
            } finally {
                root.recycle()
            }
        }

        // Fallback to active window root if windows list didn't yield any nodes
        if (roots.isEmpty() && activeRoot != null) {
            try {
                val node = buildNode(activeRoot, 0, 0)
                if (node != null) {
                    roots.add(node)
                }
            } finally {
                activeRoot.recycle()
            }
        }

        return UiSnapshot(
            packageName = packageName,
            activityName = activityName,
            nodes = roots
        )
    }

    fun getNodeById(id: Int): AccessibilityNodeInfo? = nodeRegistry[id]

    @Synchronized
    fun clearRegistry() {
        nodeRegistry.clear()
    }

    private fun buildNode(
        info: AccessibilityNodeInfo,
        depth: Int,
        currentCount: Int
    ): UiNode? {
        if (depth > MAX_DEPTH) return null
        if (currentCount >= MAX_NODES) return null

        // Prune invisible nodes
        if (!info.isVisibleToUser) return null

        val bounds = Rect()
        info.getBoundsInScreen(bounds)

        // Prune zero-size nodes unless they have children
        val hasSize = bounds.width() > 0 && bounds.height() > 0
        val text = if (info.isPassword) "***" else (info.text?.toString()?.trim() ?: "")
        val contentDesc = info.contentDescription?.toString()?.trim() ?: ""
        val resourceId = info.viewIdResourceName ?: ""
        val className = info.className?.toString() ?: ""
        val packageName = info.packageName?.toString() ?: ""

        // Build children first
        val children = mutableListOf<UiNode>()
        var childCount = currentCount
        for (i in 0 until info.childCount) {
            if (childCount >= MAX_NODES) break
            val child = info.getChild(i) ?: continue
            try {
                val childNode = buildNode(child, depth + 1, childCount)
                if (childNode != null) {
                    children.add(childNode)
                    childCount += childNode.flatten().size
                }
            } finally {
                child.recycle()
            }
        }

        // Prune empty, non-interactive, non-sized leaf nodes
        val isInteractive = info.isClickable || info.isLongClickable ||
                info.isEditable || info.isScrollable
        val hasContent = text.isNotBlank() || contentDesc.isNotBlank() || resourceId.isNotBlank()
        val hasChildren = children.isNotEmpty()

        if (!hasSize && !hasChildren && !isInteractive) return null
        if (!hasContent && !isInteractive && !hasChildren) return null

        val id = nodeIdCounter++

        // Store a fresh reference for action execution
        // Note: we refresh by bounds+text match in ActionExecutor
        nodeRegistry[id] = info

        return UiNode(
            id = id,
            packageName = packageName,
            className = className,
            text = text,
            contentDesc = contentDesc,
            resourceId = resourceId,
            boundsLeft = bounds.left,
            boundsTop = bounds.top,
            boundsRight = bounds.right,
            boundsBottom = bounds.bottom,
            isClickable = info.isClickable,
            isLongClickable = info.isLongClickable,
            isEditable = info.isEditable,
            isScrollable = info.isScrollable,
            isChecked = info.isChecked,
            isEnabled = info.isEnabled,
            isFocused = info.isFocused,
            children = children
        )
    }
}
