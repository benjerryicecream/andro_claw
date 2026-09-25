package com.androclaw.agent.perception

import android.graphics.Rect
import kotlinx.serialization.Serializable

/**
 * A compact, serializable representation of a single node in the accessibility tree.
 */
@Serializable
data class UiNode(
    val id: Int,
    val packageName: String,
    val className: String,
    val text: String,
    val contentDesc: String,
    val resourceId: String,
    val boundsLeft: Int,
    val boundsTop: Int,
    val boundsRight: Int,
    val boundsBottom: Int,
    val isClickable: Boolean,
    val isLongClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isChecked: Boolean,
    val isEnabled: Boolean,
    val isFocused: Boolean,
    val children: List<UiNode> = emptyList()
) {
    /** Returns the node label for the LLM prompt. */
    fun toPromptString(indent: Int = 0): String {
        val sb = StringBuilder()
        val prefix = "  ".repeat(indent)
        val label = when {
            text.isNotBlank() -> text.take(80)
            contentDesc.isNotBlank() -> "[${contentDesc.take(80)}]"
            resourceId.isNotBlank() -> resourceId.substringAfterLast("/")
            else -> className.substringAfterLast(".")
        }
        val actions = buildList {
            if (isClickable) add("click")
            if (isLongClickable) add("long_click")
            if (isEditable) add("edit")
            if (isScrollable) add("scroll")
        }.joinToString(",")
        sb.appendLine("$prefix[$id] $label${if (actions.isNotEmpty()) " ($actions)" else ""}")
        children.forEach { sb.append(it.toPromptString(indent + 1)) }
        return sb.toString()
    }

    /** Flat list of all descendants including self. */
    fun flatten(): List<UiNode> = listOf(this) + children.flatMap { it.flatten() }
}

@Serializable
data class UiSnapshot(
    val packageName: String,
    val activityName: String,
    val nodes: List<UiNode>,  // roots
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun toPromptString(): String {
        val sb = StringBuilder()
        sb.appendLine("App: $packageName")
        sb.appendLine("Activity: $activityName")
        sb.appendLine("UI Tree:")
        nodes.forEach { sb.append(it.toPromptString()) }
        return sb.toString()
    }

    fun hash(): Int = toPromptString().hashCode()

    fun isEmpty(): Boolean = nodes.isEmpty() || nodes.all { n ->
        n.flatten().all { it.text.isBlank() && it.contentDesc.isBlank() }
    }
}
