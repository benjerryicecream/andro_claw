package com.androclaw.agent.safety

import android.util.Log
import com.androclaw.agent.agent.AgentAction
import com.androclaw.agent.data.SecurePreferences
import com.androclaw.agent.perception.UiSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter

/**
 * The safety gatekeeper. Checks actions against policies and suspends
 * execution pending user confirmation when required.
 */
class SafetyGuard(private val prefs: SecurePreferences) {

    private val confirmationPolicy = ConfirmationPolicy(prefs)
    val appFilter = AppFilter(prefs)

    // Emits when a confirmation is needed
    private val _pendingConfirmation = MutableStateFlow<ConfirmationRequest?>(null)
    val pendingConfirmation: StateFlow<ConfirmationRequest?> = _pendingConfirmation.asStateFlow()

    // Emit user decision: true = confirmed, false = cancelled
    private val confirmationResults = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)


    private val sessionApprovedApps = mutableSetOf<String>()

    /**
     * Called by the agent loop before executing each action.
     * Suspends until user confirms (if needed) or returns immediately if safe.
     * Returns false if the user cancelled or the action is blocked.
     */
    suspend fun checkAndConfirm(
        action: AgentAction,
        snapshot: UiSnapshot
    ): SafetyResult {
        // Check app filter
        val pkg = snapshot.packageName
        val blockReason = appFilter.blockReason(pkg)
        Log.i(TAG, "checkAndConfirm action=$action pkg=$pkg unrestricted=${prefs.unrestrictedMode} block=$blockReason")
        if (blockReason != null) {
            Log.i(TAG, "-> Blocked: $blockReason")
            return SafetyResult.Blocked(blockReason)
        }

        // If unrestricted mode is enabled, skip all confirmation gates
        if (prefs.unrestrictedMode) {
            Log.i(TAG, "-> Allowed (unrestricted)")
            return SafetyResult.Allowed
        }

        // First-use confirmation for non-allowlist apps
        if (!prefs.useAllowlist && pkg !in prefs.allowlistPackages && pkg !in sessionApprovedApps) {
            Log.i(TAG, "-> awaiting first-use confirmation for $pkg")
            _pendingConfirmation.value = ConfirmationRequest(
                action = AgentAction.Wait(0),
                category = SensitiveCategory.APP_FIRST_USE,
                nodeLabel = "Agent wants to operate in this app for the first time",
                packageName = pkg
            )
            val confirmed = confirmationResults.first()
            Log.i(TAG, "-> first-use confirmed=$confirmed")
            _pendingConfirmation.value = null
            if (confirmed) {
                sessionApprovedApps.add(pkg)
            } else {
                return SafetyResult.Cancelled
            }
        }


        // Determine node label for context
        val nodeId = when (action) {
            is AgentAction.Click -> action.nodeId
            is AgentAction.LongClick -> action.nodeId
            is AgentAction.SetText -> action.nodeId
            is AgentAction.Scroll -> action.nodeId
            else -> -1
        }
        val nodeLabel = if (nodeId >= 0) {
            snapshot.nodes.flatMap { it.flatten() }
                .find { it.id == nodeId }
                ?.let { it.text.ifBlank { it.contentDesc } } ?: ""
        } else ""

        val context = ActionContext(
            packageName = pkg,
            nodeLabel = nodeLabel,
            nodeId = nodeId
        )

        val category = confirmationPolicy.requiresConfirmation(action, context)
        if (category != null) {
            Log.i(TAG, "-> awaiting confirmation for category=$category")
            _pendingConfirmation.value = ConfirmationRequest(
                action = action,
                category = category,
                nodeLabel = nodeLabel,
                packageName = pkg
            )
            val confirmed = confirmationResults.first()
            Log.i(TAG, "-> confirmation confirmed=$confirmed")
            _pendingConfirmation.value = null
            return if (confirmed) SafetyResult.Allowed else SafetyResult.Cancelled
        }

        Log.i(TAG, "-> Allowed (no category)")
        return SafetyResult.Allowed
    }

    /** Called from UI when user taps Confirm in the dialog. */
    fun confirm() {
        confirmationResults.tryEmit(true)
    }

    /** Called from UI when user taps Cancel in the dialog. */
    fun cancel() {
        confirmationResults.tryEmit(false)
    }

    companion object {
        private const val TAG = "SafetyGuard"
    }
}

data class ConfirmationRequest(
    val action: AgentAction,
    val category: SensitiveCategory,
    val nodeLabel: String,
    val packageName: String
)

sealed class SafetyResult {
    object Allowed : SafetyResult()
    object Cancelled : SafetyResult()
    data class Blocked(val reason: String) : SafetyResult()
}
