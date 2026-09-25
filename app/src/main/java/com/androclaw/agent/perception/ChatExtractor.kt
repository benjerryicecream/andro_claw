package com.androclaw.agent.perception

enum class ChatSender { SEBASTIAN, OWN, UNKNOWN }

data class ChatMessage(
    val text: String,
    val sender: ChatSender,
    val boundsTop: Int,
    val signature: String
)

/**
 * Best-effort extraction of chat message bubbles from a UI snapshot.
 *
 * A bubble is any non-editable text leaf whose row or ancestor cluster carries
 * message content. Sender attribution looks first at the bubble's own cluster
 * (the sender label/avatar text rendered alongside the bubble), then falls back
 * to a 1-on-1 heuristic: when exactly one participant name is visible anywhere
 * on screen, unlabeled bubbles belong to that participant.
 */
object ChatExtractor {

    private val SEBASTIAN_MARKERS = listOf("sebastian", "sebastien", "seb ", "seby")

    private val TIME_LIKE = Regex("""^\s*\d{1,2}[:.]\d{2}\s*(am|pm)?\s*$""", RegexOption.IGNORE_CASE)

    fun extract(
        snapshot: UiSnapshot,
        ownOutgoingHints: Set<String> = emptySet()
    ): List<ChatMessage> {
        val all = mutableListOf<UiNode>()
        val parent = HashMap<Int, UiNode?>()
        fun walk(node: UiNode, par: UiNode?) {
            all += node
            parent[node.id] = par
            node.children.forEach { walk(it, node) }
        }
        snapshot.nodes.forEach { walk(it, null) }

        val wholeText = buildString {
            all.forEach { n ->
                append(n.text).append(' ').append(n.contentDesc).append(' ')
            }
        }.lowercase()
        val wholeHasSebastian = containsAny(wholeText, SEBASTIAN_MARKERS)
        val wholeHasOwn = wholeText.contains("chase")

        return all
            .filter { it.text.isNotBlank() && !it.isEditable && !TIME_LIKE.matches(it.text) }
            .sortedBy { it.boundsTop }
            .map { node ->
                val cluster = collectCluster(node, parent)
                val sender = when {
                    node.text.trim() in ownOutgoingHints -> ChatSender.OWN
                    containsAny(cluster, SEBASTIAN_MARKERS) -> ChatSender.SEBASTIAN
                    cluster.contains("chase") -> ChatSender.OWN
                    wholeHasSebastian && !wholeHasOwn -> ChatSender.SEBASTIAN
                    wholeHasOwn && !wholeHasSebastian -> ChatSender.OWN
                    else -> ChatSender.UNKNOWN
                }
                ChatMessage(
                    text = node.text.trim(),
                    sender = sender,
                    boundsTop = node.boundsTop,
                    signature = node.text.trim().lowercase()
                )
            }
    }

    private fun containsAny(text: String, markers: List<String>): Boolean {
        val t = text.lowercase()
        return markers.any { t.contains(it) }
    }

    private fun collectCluster(node: UiNode, parent: Map<Int, UiNode?>): String = buildString {
        append(node.text).append(' ').append(node.contentDesc).append(' ')
        var cur: UiNode? = parent[node.id]
        var hop = 0
        while (cur != null && hop < 3) {
            append(cur.text).append(' ').append(cur.contentDesc).append(' ')
            cur = parent[cur.id]
            hop++
        }
    }
}