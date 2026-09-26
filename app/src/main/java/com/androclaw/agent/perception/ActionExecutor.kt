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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

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
        private const val SCAN_DEADLINE_MS = 2000L
        private const val EXECUTE_DEADLINE_MS = 8000L
        private const val TAP_RESOLVE_POLL_MS = 400L
        private const val TAP_RESOLVE_TIMEOUT_MS = 10 * 1000L

        private val SUBMIT_LABELS = setOf(
            "enter", "go", "search", "done", "send", "submit", "next", "ok", "return", "arrow"
        )
    }

    /**
     * Execute an action and return a description of what happened.
     */
    suspend fun execute(action: AgentAction): String = withContext(Dispatchers.IO) {
        Log.i(TAG, "execute entry: $action on ${Thread.currentThread().name}")
        try {
            when (action) {
                // These perform app/service-side work off the a11y tree (a11y
                // binder calls can block forever on this device mid-render), so
                // they run under a wall-clock deadline on a helper thread.
                is AgentAction.Click,
                is AgentAction.LongClick,
                is AgentAction.SetText,
                is AgentAction.Scroll,
                is AgentAction.Back,
                is AgentAction.Home,
                is AgentAction.Recents,
                is AgentAction.PressEnter -> withDeadline(EXECUTE_DEADLINE_MS) { execNow(action) }
                    ?: "Failed: action did not complete in ${EXECUTE_DEADLINE_MS}ms ($action)"
                is AgentAction.OpenApp -> executeOpenApp(action.packageName)
                is AgentAction.OpenUrl -> executeOpenUrl(action.url)
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

    /**
     * The a11y-tree-bound actions under [execute]'s deadline. [AgentAction.OpenApp],
     * [AgentAction.OpenUrl] and [AgentAction.Wait] are handled directly by [execute]
     * because they do I/O or suspend rather than touch the live tree.
     */
    private fun execNow(action: AgentAction): String = when (action) {
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
        is AgentAction.PressEnter -> executePressEnter()
        else -> error("action not handled synchronously: $action")
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

        return typeIntoEditable(nodeId, node, text)
    }

    /**
     * Focus, clear, and type [text] into [node] (a live editable field), then
     * report whether a send/submit key became exposed. Shared by the set_text
     * tool and the deterministic in-app search.
     */
    private fun typeIntoEditable(nodeId: Int, node: AccessibilityNodeInfo, text: String): String {
        // Request focus explicitly and VERIFY the live state, retrying once. Some
        // chat inputs ignore ACTION_FOCUS until the IME is attached; the check
        // must read a freshly-fetched node, not the pre-focus registry instance.
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
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

    /**
     * Deterministic in-app search: locate the foreground app's search field
     * (tapping a search affordance first if the field is hidden), type [query]
     * and submit. Never opens the browser. Returns a description of what
     * happened, or a string starting with "Failed" when no field exists.
     */
    suspend fun searchInForeground(
        query: String,
        siteHint: String? = null,
        siteRegion: android.graphics.RectF? = null
    ): String {
        val appPkg = service.rootInActiveWindow?.packageName?.toString()
            ?: return "Failed: no active window to search in"
        delay(600L) // let the app's search UI settle before scanning it
        val winInv = withDeadline(1000L) {
            service.windows.map { w ->
                "${w.type}/${w.title ?: "?"}:${w.root?.packageName ?: "?"}:${w.root?.childCount ?: -1}"
            }.joinToString(" | ")
        }.orEmpty()
        Log.i(TAG, "in-app search start: pkg=$appPkg siteHint=$siteHint windows=[$winInv]")
        if (!siteHint.isNullOrBlank()) dumpReachableTree(appPkg)
        return try {
            withTimeout(20000L) {
                // The app's search UI (field or affordance) may take several
                // renders to appear in the a11y tree — especially on a site page
                // that keeps streaming after first paint. Poll in one patient
                // loop instead of a fixed attempt count: an affordance tap opens
                // a dedicate search page whose input we then poll again.
                var editable: AccessibilityNodeInfo? = null
                var affordanceTapped = false
                var poked = false
                var regionTapped = false
                val epoch = SystemClock.elapsedRealtime()
                while (editable == null &&
                    SystemClock.elapsedRealtime() - epoch < TAP_RESOLVE_TIMEOUT_MS + TAP_RESOLVE_TIMEOUT_MS
                ) {
                    editable = findEditableField(appPkg, siteHint)
                    if (editable != null) break
                    val affordance = findSearchAffordance(appPkg, siteHint)
                    if (affordance != null && !affordanceTapped) {
                        Log.i(TAG, "in-app search: no field yet; tapping search affordance res=${affordance.viewIdResourceName} desc=${affordance.contentDescription}")
                        val clicked = withDeadline(3000L) {
                            affordance.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        } ?: false
                        if (!clicked) {
                            val b = Rect()
                            withDeadline(1500L) { affordance.getBoundsInScreen(b) }
                            if (b.width() > 0 && b.height() > 0) {
                                gestureClick(b.centerX().toFloat(), b.centerY().toFloat())
                            }
                        }
                        affordanceTapped = true
                        // The affordance expands a search page/input; poll for the
                        // freshly-exposed field rather than one-shot re-finding —
                        // and never settle for the omnibox.
                        editable = pollForHotElement("search field after affordance", TAP_RESOLVE_TIMEOUT_MS) {
                            findEditableField(appPkg, siteHint)
                        }
                    } else {
                        // On a freshly-attained site tab Chrome may not have built
                        // the annotated web a11y tree yet; one scroll interaction
                        // forces Blink to emit the page's virtual nodes.
                        if (!siteHint.isNullOrBlank() && !poked &&
                            SystemClock.elapsedRealtime() - epoch > 2000L
                        ) {
                            poked = true
                            Log.i(TAG, "in-app search: poking page scroll to surface the search control")
                            pokeScroll()
                        }
                        // Documented last resort for site pages: when the a11y
                        // tree never exposes the search control, tap the site's
                        // header search REGION (known mobile-web layout), then the
                        // resulting input still has to appear + become focused over
                        // the tree before anything is typed.
                        if (!siteHint.isNullOrBlank() && !regionTapped && siteRegion != null &&
                            SystemClock.elapsedRealtime() - epoch > 6000L
                        ) {
                            regionTapped = true
                            val screen = rootScreenBounds()
                            val cx = (screen.left + screen.width() * siteRegion.left +
                                screen.left + screen.width() * siteRegion.right) / 2f
                            val cy = (screen.top + screen.height() * siteRegion.top +
                                screen.top + screen.height() * siteRegion.bottom) / 2f
                            Log.i(TAG, "in-app search: a11y search control unavailable; tapping site header search region (screen fraction ${siteRegion.left}..${siteRegion.right} x ${siteRegion.top}..${siteRegion.bottom}) at (${cx.toInt()}, ${cy.toInt()})")
                            gestureClick(cx, cy)
                            delay(1200L)
                        }
                        delay(400L)
                    }
                }
                if (editable == null) {
                    "Failed: no search field found in $appPkg (tap the app's search box to proceed)"
                } else {
                    // Click the search field for real: the IME only attaches after a
                    // physical tap gains focus (ACTION_FOCUS alone is silently ignored
                    // during the keyboard animation on some apps). Poll until the field
                    // is present+ready, then click with fresh bounds on every attempt.
                    // The field must report focused after the click, else we re-resolve
                    // and click again — never a tap on stale coordinates.
                    Log.i(TAG, "in-app search: polling for the search field to click it")
                    val epoch = SystemClock.elapsedRealtime()
                    var focused = false
                    while (SystemClock.elapsedRealtime() - epoch < TAP_RESOLVE_TIMEOUT_MS && !focused) {
                        val f = pollForHotElement("search field in $appPkg") {
                            findEditableField(appPkg, siteHint)
                        } ?: break
                        val b = Rect()
                        withDeadline(1500L) { f.getBoundsInScreen(b) }
                        if (b.width() > 4 && b.height() > 4) {
                            gestureClick(b.centerX().toFloat(), b.centerY().toFloat())
                            delay(500L)
                            editable = f
                            val after = findEditableField(appPkg, siteHint)
                            if (after != null) {
                                withDeadline(1500L) { focused = after.isFocused }
                            }
                        }
                        if (!focused) {
                            Log.w(TAG, "in-app search: field not focused after click; re-resolving and retrying (${SystemClock.elapsedRealtime() - epoch}ms elapsed)")
                        }
                    }
                    Log.i(TAG, "in-app search: field focused=$focused after ${SystemClock.elapsedRealtime() - epoch}ms")
                    val field = editable
                    if (field != null) {
                        Log.i(TAG, "in-app search: typing")
                        val typed = withDeadline(8000L) { typeIntoEditable(-1, field, query) }
                            ?: "Failed: typing into field did not complete in 8000ms"
                        Log.i(TAG, "in-app search: typed -> $typed")
                        val submitted = executePressEnter()
                        Log.i(TAG, "in-app search '$query' in $appPkg: typed=$typed submitted=$submitted")
                        if (submitted.startsWith("Failed")) {
                            // A search that was never submitted has NO results on
                            // screen — claiming completion on the typed field text
                            // would be navigation-state, not goal-state.
                            "Failed: could not submit search in $appPkg ($submitted)"
                        } else {
                            "Searched \"$query\" in $appPkg: $submitted"
                        }
                    } else {
                        "Failed: search bar disappeared in $appPkg before typing"
                    }
                }
            }
        } catch (t: TimeoutCancellationException) {
            Log.w(TAG, "in-app search timed out in $appPkg", t)
            "Failed: in-app search timed out in $appPkg (tap the app's search box to proceed)"
        }
    }

    /**
     * Runs a blocking accessibility-window walk on a helper thread and gives up if
     * it does not finish in time. `service.windows` is a binder call that can block
     * forever on this device; coroutine timeouts cannot interrupt a synchronous
     * binder call, so the only reliable bound is a wall-clock deadline. A wedged
     * helper thread is leaked but the agent moves on instead of freezing.
     */
    private fun <T> withDeadline(timeoutMs: Long, block: () -> T): T? {
        val future = deadlinePool.submit(block)
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.w(TAG, "UI window query did not finish in $timeoutMs ms; proceeding without it")
            null
        } catch (e: Exception) {
            Log.w(TAG, "UI window query failed: ${e.message}")
            null
        }
    }

    /** Cached daemon threads used by [withDeadline]; unused helpers exit when idle. */
    private val deadlinePool = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "action-deadline").apply { isDaemon = true }
    }

    /** Label/resource-id fragments that identify a browser's own address bar rather
     * than a page's search box; typing a scoped query there would search the web. */
    private val OMNI_SIGNALS = listOf(
        "search google", "type url", "url_bar", "omnibox", "address bar", "location bar", "search or type"
    )

    /**
     * Browser-chrome container subtrees to skip when walking a window root for a
     * SITE search. They share the window root with the rendered page (the web
     * content subtree sibling), so only the browser UI must be excluded — not
     * the whole window. Identifying them by resource id keeps the page's own
     * inputs/affordances reachable while never returning the address bar.
     */
    private fun isBrowserChromeContainer(node: AccessibilityNodeInfo): Boolean {
        val resId = node.viewIdResourceName?.lowercase().orEmpty()
        return resId.contains("control_container") ||
            resId.contains("toolbar_container") ||
            resId.contains("/toolbar") ||
            resId.contains("location_bar") ||
            resId.contains("omnibox") ||
            resId.contains("bottom_controls")
    }

    /**
     * First enabled, visible editable field inside the app's windows. With a
     * [siteHint] the field belonging to the SITE itself (e.g. youtube.com's
     * "Search YouTube" box) is preferred over the browser chrome (the omnibox):
     * when a scoped search runs inside a site opened in the browser, the query
     * must go to the page's search box, not the address bar. Browser chrome is
     * skipped as individual subtrees, so the page's inputs remain reachable;
     * an omnibox editable is never a valid answer. Returning null until the
     * page's own input renders lets callers keep polling (or tap the page's
     * search affordance).
     */
    private fun findEditableField(appPkg: String, siteHint: String? = null): AccessibilityNodeInfo? {
        val browserContext = !siteHint.isNullOrBlank()
        val edits = withDeadline(SCAN_DEADLINE_MS) {
            val out = mutableListOf<AccessibilityNodeInfo>()
            for (root in liveRoots().filter { it.packageName == appPkg }) {
                if (browserContext && isBrowserChromeContainer(root)) continue
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    if (browserContext && isBrowserChromeContainer(node)) continue
                    if (node.isEditable && node.isEnabled && node.isVisibleToUser) out.add(node)
                    for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
                }
            }
            out
        } ?: return null

        fun labelOf(node: AccessibilityNodeInfo): String =
            (node.text ?: node.contentDescription)?.toString()?.lowercase().orEmpty()
        fun resIdOf(node: AccessibilityNodeInfo): String =
            node.viewIdResourceName?.lowercase().orEmpty()
        fun isOmni(node: AccessibilityNodeInfo): Boolean {
            val label = labelOf(node)
            val resId = resIdOf(node)
            return OMNI_SIGNALS.any { label.contains(it) || resId.contains(it) }
        }

        val decision = if (browserContext) {
            val hint = siteHint!!.lowercase()
            val hinted = edits.firstOrNull { labelOf(it).contains(hint) || resIdOf(it).contains(hint) }
                ?: edits.firstOrNull { !isOmni(it) }
            hinted
        } else {
            edits.firstOrNull()
        }
        Log.i(
            TAG,
            "findEditableField pkg=$appPkg siteHint=$siteHint candidates=${edits.size} " +
                "chose=${decision?.let { "${it.viewIdResourceName} / ${(it.text ?: it.contentDescription)}" }}"
        )
        return decision
    }

    /**
     * Polls until the target element is present and ready to be tapped, instead
     * of snapshotting once and tapping blind. Web pages in particular keep
     * shifting after first paint, so a target that existed in a stale snapshot
     * is never trusted: [resolve] is consulted fresh on every poll and the node
     * it returns is the one that gets tapped. Returns null after the (bounded)
     * timeout so callers can fail honestly rather than tap stale coordinates.
     */
    private suspend fun pollForHotElement(
        name: String,
        timeoutMs: Long = TAP_RESOLVE_TIMEOUT_MS,
        resolve: () -> AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val node = resolve()
            if (node != null && node.isEnabled && node.isVisibleToUser) {
                val b = Rect()
                withDeadline(1500L) { node.getBoundsInScreen(b) }
                if (b.width() > 4 && b.height() > 4) return node
            }
            delay(TAP_RESOLVE_POLL_MS)
        }
        Log.w(TAG, "tap target \"$name\" not present/ready within ${timeoutMs}ms; giving up cleanly")
        return null
    }

    /** Node whose label reads as a Search affordance (icon/tab/bar), in the active
     * app window. For a site opened in the browser the browser-chrome subtrees
     * are skipped so the page's own "Search" control is found, not the omnibox. */
    private fun findSearchAffordance(appPkg: String, siteHint: String? = null): AccessibilityNodeInfo? =
        withDeadline(SCAN_DEADLINE_MS) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            for (root in liveRoots()) {
                if (root.packageName != appPkg) continue
                if (!siteHint.isNullOrBlank() && isBrowserChromeContainer(root)) continue
                queue.add(root)
            }
            val visibleLabels = mutableListOf<String>()
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (!siteHint.isNullOrBlank() && isBrowserChromeContainer(node)) continue
                if (!node.isEditable) {
                    val label = (node.text ?: node.contentDescription)?.toString()?.trim()?.lowercase() ?: ""
                    if (label.isNotEmpty()) visibleLabels.add(label)
                    // Web page search controls (e.g. youtube.com's "Search YouTube"
                    // button) can report isVisibleToUser=false to general services
                    // even though they sit on screen; be tolerant of that flag and
                    // only require the node to actually be on the display.
                    if (label == "search" || (label.startsWith("search") && label.length <= 30)) {
                        val b = Rect()
                        node.getBoundsInScreen(b)
                        if (b.width() > 0 && b.height() > 0 && b.bottom <= rootScreenBounds().bottom) {
                            return@withDeadline node
                        }
                    }
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
            }
            Log.w(
                TAG,
                "search affordance scan in $appPkg found no search node; visible labels=[${visibleLabels.distinct().take(40).joinToString(", ")}]"
            )
            null
        }

    private fun executePressEnter(): String =
        withDeadline(6000L) { pressEnterImpl() }
            ?: "Failed: press_enter — window query blocked (no enter key found)"

    private fun pressEnterImpl(): String {
        // The keyboard animates in ~300-500ms after focus; retry briefly so the
        // IME search/send key (or the app's submit button) can appear.
        repeat(5) {
            // 1) Prefer the IME keyboard's enter/search key (separate accessibility window).
            val imeKey = service.windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .mapNotNull { it.root }
                .mapNotNull { findSubmitNode(it) }
                .firstOrNull()
            if (imeKey != null && imeKey.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "Pressed IME ${imeKey.contentDescription ?: "enter"} key"
            }

            // 2) Fall back to a submit/Go/Search button across every window; if
            // the node rejects the click (stale, mid-animation) tap it by coords.
            for (root in liveRoots()) {
                findSubmitNode(root)?.let { node ->
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return "Pressed ${node.contentDescription ?: "submit"} button"
                    }
                    val b = Rect()
                    node.getBoundsInScreen(b)
                    if (b.width() > 0 && b.height() > 0) {
                        gestureClick(b.centerX().toFloat(), b.centerY().toFloat())
                        return "Tapped submit button at (${b.centerX()}, ${b.centerY()})"
                    }
                }
            }
            SystemClock.sleep(300)
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

    /** On-screen bounds of the active window, used to sanity-check tap targets. */
    private fun rootScreenBounds(): Rect {
        val r = Rect(0, 0, 10000, 10000)
        withDeadline(1500L) {
            val root = service.rootInActiveWindow
            if (root != null) {
                root.getBoundsInScreen(r)
                if (r.width() <= 0 || r.height() <= 0) r.set(0, 0, 10000, 10000)
            }
        }
        return r
    }

    /** All window roots currently exposed to the a11y service. The active window's
     * root is ALWAYS included: on this device it can hold a fresher/fuller render
     * than the per-window roots (which may lag behind after chrome re-renders). */
    private fun liveRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        withDeadline(SCAN_DEADLINE_MS) {
            service.windows.forEach { win -> win.root?.let { roots.add(it) } }
            service.rootInActiveWindow?.let { roots.add(it) }
        }
        return roots
    }

    /** One-shot diagnostic: logs the shape of what the walk actually reaches. */
    private fun dumpReachableTree(appPkg: String) {
        withDeadline(SCAN_DEADLINE_MS) {
            val seen = ArrayDeque<AccessibilityNodeInfo>()
            for (root in liveRoots()) {
                if (root.packageName == appPkg) seen.add(root)
            }
            var count = 0
            while (seen.isNotEmpty() && count < 30) {
                val node = seen.removeFirst()
                val b = Rect()
                node.getBoundsInScreen(b)
                Log.i(
                    TAG,
                    "TREE#${count++} cls=${node.className} res=${node.viewIdResourceName} " +
                        "text=${(node.text ?: "?")} desc=${(node.contentDescription ?: "?")} " +
                        "editable=${node.isEditable} clickable=${node.isClickable} " +
                        "focusable=${node.isFocusable} visible=${node.isVisibleToUser} bounds=$b"
                )
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { seen.add(it) }
                }
            }
            Log.i(TAG, "TREE dump done (first 30 of reachable subtree)")
        }
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
     * A short downward swipe in the content area. Chrome often lags building the
     * annotated web a11y tree for a freshly-attained tab; one interaction forces
     * Blink to (re)emit the page's virtual nodes (search button placeholders
     * included). Bounded: the dispatch itself has a completion callback.
     */
    private fun pokeScroll() {
        val screen = rootScreenBounds()
        val cx = screen.centerX().toFloat()
        val yTop = screen.top + (screen.height() * 0.28f)
        val yBottom = yTop + 260f
        val path = Path().apply {
            moveTo(cx, yBottom)
            lineTo(cx, yTop)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 160)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val done = CountDownLatch(1)
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(result: GestureDescription) = done.countDown()
            override fun onCancelled(result: GestureDescription) = done.countDown()
        }, null)
        try {
            done.await(1500L, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // ignore
        }
        SystemClock.sleep(700L)
    }

    /**
     * Tap the center of a snapshot node's bounds. Used when a node exists only in
     * a snapshot (no live registry id), e.g. clicking a store result card.
     */
    fun tapNode(node: UiNode): String {
        val cx = (node.boundsLeft + node.boundsRight) / 2f
        val cy = (node.boundsTop + node.boundsBottom) / 2f
        gestureClick(cx, cy)
        Log.i(TAG, "tapNode ${node.text.ifBlank { node.contentDesc }} at (${cx.toInt()}, ${cy.toInt()})")
        return "Tapped (${cx.toInt()}, ${cy.toInt()})"
    }

    /**
     * Tap a raw screen coordinate (gesture only). Used as a fallback when the
     * target exists in the snapshot but has no live registry id or isn't
     * clickable.
     */
    fun tapPoint(cx: Int, cy: Int): String {
        gestureClick(cx.toFloat(), cy.toFloat())
        Log.i(TAG, "tapPoint at ($cx, $cy)")
        return "Tapped ($cx, $cy)"
    }

    /**
     * Wait for the UI to stabilize after an action.
     * Polls the UI tree hash until it's stable for [UI_SETTLE_STABLE_COUNT] consecutive polls.
     * Each poll runs on an unbounded deadline thread so a wedged a11y binder call
     * can never starve the shared coroutine dispatcher.
     */
    suspend fun waitForUiStable(
        timeoutMs: Long = UI_SETTLE_TIMEOUT_MS,
        getSnapshot: () -> UiSnapshot
    ): UiSnapshot? {
        val start = SystemClock.elapsedRealtime()
        var lastHash = -1
        var stableCount = 0
        var snapshot: UiSnapshot? = withDeadline(timeoutMs) { getSnapshot() }
        if (snapshot == null) return null

        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val remaining = timeoutMs - (SystemClock.elapsedRealtime() - start)
            if (remaining <= 0) break
            delay(UI_SETTLE_POLL_MS)
            val snap = withDeadline(remaining) { getSnapshot() } ?: break
            snapshot = snap
            val hash = snap.hash()
            if (hash == lastHash) {
                stableCount++
                if (stableCount >= UI_SETTLE_STABLE_COUNT) break
            } else {
                stableCount = 0
                lastHash = hash
            }
        }
        return snapshot
    }
}
