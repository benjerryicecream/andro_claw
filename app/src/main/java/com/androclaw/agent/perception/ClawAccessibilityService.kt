package com.androclaw.agent.perception

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The core accessibility service.
 * Acts as the perception and action layer for the agent.
 * Singleton access via [instance] after binding.
 */
class ClawAccessibilityService : AccessibilityService() {

    companion object {
        private val _instance = MutableStateFlow<ClawAccessibilityService?>(null)
        val instance: StateFlow<ClawAccessibilityService?> = _instance.asStateFlow()

        val isEnabled: Boolean get() = _instance.value != null
    }

    lateinit var actionExecutor: ActionExecutor
        private set

    private var currentPackage: String = ""
    private var currentActivity: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        actionExecutor = ActionExecutor(this)
        _instance.value = this

        // Configure dynamically for all apps
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        event.packageName?.toString()?.let { currentPackage = it }
        // Track activity from window state changed events
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.className?.toString()?.let { currentActivity = it }
        }
    }

    override fun onInterrupt() {
        // No-op; agent loop checks isEnabled
    }

    override fun onDestroy() {
        super.onDestroy()
        _instance.value = null
    }

    /**
     * Build a fresh UI snapshot from the current windows.
     */
    fun buildSnapshot(): UiSnapshot {
        val windows: List<AccessibilityWindowInfo> = windows ?: emptyList()
        return UiTreeBuilder.buildSnapshot(
            windows = windows,
            packageName = currentPackage,
            activityName = currentActivity
        )
    }

    /**
     * Get the root node of the active window.
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }
}
