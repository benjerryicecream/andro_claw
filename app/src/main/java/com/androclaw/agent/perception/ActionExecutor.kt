package com.androclaw.agent.perception

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.androclaw.agent.agent.AgentAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Executes agent actions via the AccessibilityService.
 */
class ActionExecutor(
    private val service: AccessibilityService
) {
    companion object {
        private const val TAG = "ActionExecutor"
        private const val UI_SETTLE_POLL_MS = 200L
        private const val UI_SETTLE_TIMEOUT_MS = 1500L
        private const val UI_SETTLE_STABLE_COUNT = 2

        private val SUBMIT_LABELS = setOf(
            "enter", "go", "search", "done", "send", "submit", "next", "ok", "return", "arrow"
        )
    }

    /**
     * Execute an action and return a description of what happened.
     */
    suspend fun execute(action: AgentAction): String = withContext(Dispatchers.Main) {
        Log.i(TAG, "execute entry: $action on ${Thread.currentThread().name}")
        try {
            when (action) {
                is AgentAction.Click -> executeClick(action.nodeId)
                is AgentAction.LongClick -> executeLongClick(action.nodeId)
                is AgentAction.SetText -> executeSetText(action.nodeId, action.text)
                is AgentAction.Scroll -> executeScroll(action.nodeId, action.direction)
                is AgentAction.Back -> {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    "Pressed BACK"
                }
                is AgentAction.Home -> {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    "Pressed HOME"
                }
                is AgentAction.Recents -> {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                    "Opened RECENTS"
                }
                is AgentAction.OpenApp -> executeOpenApp(action.packageName)
                is AgentAction.OpenUrl -> executeOpenUrl(action.url)
                is AgentAction.PressEnter -> executePressEnter()
                is AgentAction.Wait -> {
                    delay(action.millis)
                    "Waited ${action.millis}ms"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "execute threw", e)
            "error: ${e.message}"
        }
    }

    private fun executeClick(nodeId: Int): String {
        val node = UiTreeBuilder.getNodeById(nodeId)
            ?: return "Failed: node $nodeId not found"
        return if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "Clicked node $nodeId"
        } else {
            // Fallback: gesture tap at center
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val cx = bounds.centerX().toFloat()
            val cy = bounds.centerY().toFloat()
            gestureClick(cx, cy)
            "Tapped center of node $nodeId at ($cx, $cy)"
        }
    }

    private fun executeLongClick(nodeId: Int): String {
        val node = UiTreeBuilder.getNodeById(nodeId)
            ?: return "Failed: node $nodeId not found"
        return if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            "Long-clicked node $nodeId"
        } else {
            "Failed: long-click on node $nodeId not supported"
        }
    }

    private fun executeSetText(nodeId: Int, text: String): String {
        val registered = UiTreeBuilder.getNodeById(nodeId)
            ?: return "Failed: node $nodeId not found"

        // Re-resolve against the live tree: the registry holds references from the
        // last observation, but the keyboard animation can churn the tree between
        // that observation and this action. Screen bounds are stable across
        // rebuilds; opaque node ids are not.
        val bounds = Rect()
        registered.getBoundsInScreen(bounds)
        val node = findEditableNode(bounds) ?: registered

        if (!node.isEditable) {
            val cls = node.className
            Log.w(TAG, "set_text node=$nodeId is not editable (cls=$cls res=${node.viewIdResourceName} bounds=$bounds)")
            return "Failed: node $nodeId is not an editable field ($cls)"
        }

        // Request focus explicitly and VERIFY the live state, retrying once. Some
        // chat inputs ignore ACTION_FOCUS until the IME is attached; the check
        // must read a freshly-fetched node, not the pre-focus registry instance.
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        var live = findEditableNode(bounds) ?: node
        var focusOk = live.isFocused
        if (!focusOk) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            SystemClock.sleep(250)
            live = findEditableNode(bounds) ?: node
            focusOk = live.isFocused
        }
        val focusState = if (focusOk) "focused" else "field not reporting focus"

        // Clear existing text (selection = full range), then write the new text on
        // the freshest node we have.
        val clearArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, live.text?.length ?: 0)
        }
        live.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, clearArgs)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val setOk = live.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        // Post-typing confirmation: is a send/submit target exposed now?
        val sendTargets = describeSubmitNodes()
        Log.i(
            TAG,
            "set_text node=$nodeId cls=${live.className} res=${live.viewIdResourceName} " +
                "editable=${live.isEditable} supportsSetText=${live.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id }} " +
                "$focusState setOk=$setOk pkg=${service.rootInActiveWindow?.packageName} submit_nodes=[$sendTargets]"
        )

        val result =
            if (setOk) "Set text on node $nodeId: \"$text\"" else "Failed: set_text on node $nodeId not supported"
        return if (sendTargets.isNotBlank()) {
            "$result [$focusState; send button: $sendTargets]"
        } else {
            Log.w(TAG, "set_text done but NO send/submit node is visible after typing")
            "$result [$focusState; NO send button visible — tap a send button or IME key]"
        }
    }

    private fun executePressEnter(): String {
        // 1) Prefer the IME keyboard's enter/search key (separate accessibility window).
        val imeKey = service.windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .mapNotNull { it.root }
            .mapNotNull { findSubmitNode(it) }
            .firstOrNull()
        if (imeKey != null && imeKey.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return "Pressed IME ${imeKey.contentDescription ?: "enter"} key"
        }

        // 2) Fall back to a submit/Go/Search button in the app's own window tree.
        service.rootInActiveWindow?.let { root ->
            findSubmitNode(root)?.let { node ->
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return "Pressed ${node.contentDescription ?: "submit"} button"
                }
            }
        }

        return run {
            val candidates = describeSubmitNodes()
            Log.w(TAG, "press_enter failed; visible submit-ish labels=[$candidates]")
            "Failed: press_enter — no enter/search key found; tap a visible suggestion or Go button instead (visible: [${candidates.ifBlank { "none" }}])"
        }
    }

    /**
     * Human-readable list of clickable nodes whose labels read as a submit key,
     * across the IME window and the app's own window. Scanned fresh so "the send
     * button exists but wasn't exposed when we looked" is confirmable in logs.
     */
    private fun describeSubmitNodes(): String {
        val found = LinkedHashSet<String>()
        for (win in service.windows) win.root?.let { collectSubmitLabels(it, found) }
        service.rootInActiveWindow?.let { collectSubmitLabels(it, found) }
        return found.joinToString(", ")
    }

    private fun collectSubmitLabels(node: AccessibilityNodeInfo, out: MutableSet<String>) {
        val label = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
        if (node.isClickable && label.isNotBlank()) {
            val lower = label.lowercase()
            if (SUBMIT_LABELS.any { lower == it || lower.contains(it) }) out.add(label)
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectSubmitLabels(it, out) }
    }

    /** All window roots currently exposed to the a11y service. */
    private fun liveRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        service.windows.forEach { win -> win.root?.let { roots.add(it) } }
        if (roots.isEmpty()) service.rootInActiveWindow?.let { roots.add(it) }
        return roots
    }

    /**
     * The node at [bounds] in the CURRENT tree, preferring an editable field.
     * Returns null when nothing is at that location anymore.
     */
    private fun findEditableNode(bounds: Rect): AccessibilityNodeInfo? {
        val hits = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        for (root in liveRoots()) collectNodesAt(root, bounds, 0, hits)
        return hits.firstOrNull { it.first.isEditable }?.first
    }

    private fun collectNodesAt(
        node: AccessibilityNodeInfo,
        bounds: Rect,
        depth: Int,
        out: MutableList<Pair<AccessibilityNodeInfo, Int>>
    ) {
        if (depth > 20) return
        val b = Rect()
        node.getBoundsInScreen(b)
        if (b.contains(bounds.centerX(), bounds.centerY())) out.add(node to depth)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectNodesAt(it, bounds, depth + 1, out) }
        }
    }

    /** Depth-first search for a clickable node whose label reads as a submit key. */
    private fun findSubmitNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun isSubmit(node: AccessibilityNodeInfo, contain: Boolean): Boolean {
            val label = (node.text ?: node.contentDescription)?.toString()?.trim()?.lowercase().orEmpty()
            val keyMatch = if (contain) {
                SUBMIT_LABELS.any { label.contains(it) }
            } else {
                label in SUBMIT_LABELS
            }
            if (!keyMatch) return false
            return node.isClickable ||
                node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id }
        }

        fun dfs(contain: Boolean): AccessibilityNodeInfo? {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (isSubmit(node, contain)) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
            }
            return null
        }

        return dfs(contain = false) ?: dfs(contain = true)
    }

    private fun executeScroll(nodeId: Int, direction: String): String {
        val node = UiTreeBuilder.getNodeById(nodeId)
            ?: return "Failed: node $nodeId not found"
        val action = when (direction.lowercase()) {
            "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        return if (node.performAction(action)) {
            "Scrolled $direction on node $nodeId"
        } else {
            "Failed: scroll $direction on node $nodeId not supported"
        }
    }

    private suspend fun executeOpenApp(packageName: String): String {
        val targetPkg = resolvePackageName(packageName)
        val intent = service.packageManager.getLaunchIntentForPackage(targetPkg)
            ?: return "Failed: could not find or launch app package '$packageName'"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        delay(1200L) // Allow time for app launch and window transition
        return "Opened app: $targetPkg"
    }

    private fun resolvePackageName(input: String): String {
        val trimmed = input.trim().lowercase()
        val knownMap = mapOf(
            "chrome" to "com.android.chrome",
            "google chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "youtube" to "com.google.android.youtube",
            "gmail" to "com.google.android.gm",
            "settings" to "com.android.settings",
            "photos" to "com.google.android.apps.photos",
            "clock" to "com.google.android.deskclock",
            "calculator" to "com.google.android.calculator",
            "calendar" to "com.google.android.calendar",
            "messages" to "com.google.android.apps.messaging",
            "phone" to "com.google.android.dialer",
            "whatsapp" to "com.whatsapp",
            "spotify" to "com.spotify.music"
        )
        knownMap[trimmed]?.let { return it }

        if (service.packageManager.getLaunchIntentForPackage(input) != null) {
            return input
        }

        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = service.packageManager.queryIntentActivities(mainIntent, 0)
        for (app in apps) {
            val pkg = app.activityInfo.packageName
            val label = app.loadLabel(service.packageManager).toString().lowercase()
            if (pkg.lowercase() == trimmed || label == trimmed || label.contains(trimmed)) {
                return pkg
            }
        }
        return input
    }

    private suspend fun executeOpenUrl(rawUrl: String): String {
        var formattedUrl = rawUrl.trim()
        if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
            formattedUrl = "https://$formattedUrl"
        }
        val uri = Uri.parse(formattedUrl)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.android.chrome")
        }
        return try {
            service.startActivity(intent)
            delay(1200L)
            "Opened URL in Chrome: $formattedUrl"
        } catch (e: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                service.startActivity(fallbackIntent)
                delay(1200L)
                "Opened URL: $formattedUrl"
            } catch (ex: Exception) {
                "Failed to open URL $formattedUrl: ${ex.message}"
            }
        }
    }

    private fun gestureClick(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
    }

    /**
     * Wait for the UI to stabilize after an action.
     * Polls the UI tree hash until it's stable for [UI_SETTLE_STABLE_COUNT] consecutive polls.
     */
    suspend fun waitForUiStable(
        timeoutMs: Long = UI_SETTLE_TIMEOUT_MS,
        getSnapshot: () -> UiSnapshot
    ): UiSnapshot = withContext(Dispatchers.Default) {
        val start = SystemClock.elapsedRealtime()
        var lastHash = -1
        var stableCount = 0
        var snapshot = getSnapshot()

        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            delay(UI_SETTLE_POLL_MS)
            snapshot = getSnapshot()
            val hash = snapshot.hash()
            if (hash == lastHash) {
                stableCount++
                if (stableCount >= UI_SETTLE_STABLE_COUNT) break
            } else {
                stableCount = 0
                lastHash = hash
            }
        }
        snapshot
    }
}
