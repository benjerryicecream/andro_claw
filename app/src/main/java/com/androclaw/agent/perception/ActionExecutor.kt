package com.androclaw.agent.perception

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
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
        private const val UI_SETTLE_POLL_MS = 200L
        private const val UI_SETTLE_TIMEOUT_MS = 1500L
        private const val UI_SETTLE_STABLE_COUNT = 2
    }

    /**
     * Execute an action and return a description of what happened.
     */
    suspend fun execute(action: AgentAction): String = withContext(Dispatchers.Main) {
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
            is AgentAction.Wait -> {
                delay(action.millis)
                "Waited ${action.millis}ms"
            }
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
        val node = UiTreeBuilder.getNodeById(nodeId)
            ?: return "Failed: node $nodeId not found"
        // Focus the field first
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        // Clear existing text by setting selection to full range
        val clearArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                node.text?.length ?: 0
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, clearArgs)
        // Set new text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            "Set text on node $nodeId: \"$text\""
        } else {
            "Failed: set_text on node $nodeId not supported"
        }
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
