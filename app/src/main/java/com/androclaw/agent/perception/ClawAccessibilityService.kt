package com.androclaw.agent.perception

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/**
 * The core accessibility service.
 * Acts as the perception and action layer for the agent.
 * Singleton access via [instance] after binding.
 */
class ClawAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClawAccessibilityService"
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
        Log.i(TAG, "onServiceConnected pid=${android.os.Process.myPid()} actionExecutor=${actionExecutor != null}")

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

    private var eventCount = 0

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        event.packageName?.toString()?.let { currentPackage = it }
        // Track activity from window state changed events
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.className?.toString()?.let { currentActivity = it }
        }
        eventCount++
        if (eventCount % 25 == 0) {
            Log.d(TAG, "events=$eventCount lastPkg=$currentPackage")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
        // No-op; agent loop checks isEnabled
    }

    override fun onDestroy() {
        super.onDestroy()
        _instance.value = null
        Log.w(TAG, "onDestroy pid=${android.os.Process.myPid()}")
    }

    /**
     * Snapshot that can never wedge the agent: if the a11y window query blocks
     * (stalled binder during a heavy page transition) we give up and return null
     * rather than hanging the loop forever. Runs on an unbounded helper thread so
     * a wedged binder call leaks a disposable thread instead of a coroutine pool
     * worker (the shared pools are tiny on this device and starvation froze runs).
     */
    suspend fun safeSnapshot(timeoutMs: Long = 2500L): UiSnapshot? {
        val future = snapshotPool.submit(java.util.concurrent.Callable<UiSnapshot> { buildSnapshot() })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.w(TAG, "safeSnapshot: window query stalled >${timeoutMs}ms; proceeding without snapshot")
            null
        } catch (e: Exception) {
            Log.w(TAG, "safeSnapshot failed: ${e.message}")
            null
        }
    }

    private val snapshotPool = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "snapshot-deadline").apply { isDaemon = true }
    }

    /**
     * Build a fresh UI snapshot from the current windows.
     */
    fun buildSnapshot(): UiSnapshot {
        val windowsList: List<AccessibilityWindowInfo> = windows ?: emptyList()
        val activeRoot = rootInActiveWindow
        // Prefer the app window over rootInActiveWindow: the "active" window is
        // the IME whenever the keyboard is focused, which would mislabel the
        // snapshot and replace the app tree with keyboard keys.
        val appWindow = windowsList.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }
        val appRoot = appWindow?.root
        val pkg = appRoot?.packageName?.toString()?.takeIf { it.isNotBlank() }
            ?: currentPackage
        val activity = if (appWindow != null && !pkg.startsWith("com.google.android.inputmethod")) {
            pkg
        } else currentActivity
        return UiTreeBuilder.buildSnapshot(
            windows = windowsList,
            activeRoot = activeRoot,
            packageName = pkg,
            activityName = activity
        )
    }

    /**
     * Get the root node of the active window.
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }
}
