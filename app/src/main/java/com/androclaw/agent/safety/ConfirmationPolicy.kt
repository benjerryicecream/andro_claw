package com.androclaw.agent.safety

import com.androclaw.agent.agent.AgentAction
import com.androclaw.agent.data.SecurePreferences

/**
 * Categories of sensitive actions that require user confirmation.
 */
enum class SensitiveCategory(val displayName: String, val description: String) {
    SEND_MESSAGE("Send Message", "Sending SMS, chat messages, emails"),
    MAKE_CALL("Make Call", "Initiating phone or video calls"),
    PAYMENT("Payment", "Purchases, transfers, payment apps"),
    APP_INSTALL("App Install", "Installing, updating, or downloading apps"),
    DELETION("Deletion", "Deleting files, messages, contacts, data"),
    SYSTEM_SETTINGS("System Settings", "Changing device settings, toggling system toggles"),
    PERMISSION_GRANT("Permission Grant", "Granting app permissions"),
    APP_FIRST_USE("App First Use", "First time operating in this app")
}

/**
 * Determines which actions need confirmation based on user policy settings.
 */
class ConfirmationPolicy(private val prefs: SecurePreferences) {

    /**
     * Returns the SensitiveCategory if this action requires confirmation, or null if it can proceed.
     */
    fun requiresConfirmation(action: AgentAction, context: ActionContext): SensitiveCategory? {
        // Check text-based heuristics from the action context
        val label = context.nodeLabel.lowercase()
        val pkg = context.packageName.lowercase()

        return when {
            // Send button in messaging apps
            prefs.confirmMessages && isSendAction(action, label, pkg) ->
                SensitiveCategory.SEND_MESSAGE

            // Call button
            prefs.confirmCalls && isCallAction(label, pkg) ->
                SensitiveCategory.MAKE_CALL

            // Payment apps
            prefs.confirmPayments && isPaymentAction(label, pkg) ->
                SensitiveCategory.PAYMENT

            // App install / download / update
            prefs.confirmAppInstall && isInstallAction(label, pkg) ->
                SensitiveCategory.APP_INSTALL

            // Delete / remove / clear
            prefs.confirmDeletions && isDeletionAction(label) ->
                SensitiveCategory.DELETION

            // System settings
            prefs.confirmSystemSettings && isSystemSettingsAction(pkg) ->
                SensitiveCategory.SYSTEM_SETTINGS

            // Permission dialogs
            prefs.confirmPermissions && isPermissionAction(label) ->
                SensitiveCategory.PERMISSION_GRANT

            else -> null
        }
    }

    private fun isSendAction(action: AgentAction, label: String, pkg: String): Boolean {
        if (action !is AgentAction.Click) return false
        return label in listOf("send", "send message", "send now") ||
                pkg.contains("messaging") || pkg.contains("sms") ||
                pkg.contains("whatsapp") || pkg.contains("telegram") ||
                pkg == "com.google.android.gm" // Gmail
    }

    private fun isCallAction(label: String, pkg: String): Boolean {
        return label in listOf("call", "dial", "video call", "voice call") ||
                pkg == "com.android.dialer" ||
                pkg == "com.google.android.dialer"
    }

    private fun isPaymentAction(label: String, pkg: String): Boolean {
        val paymentPkgs = listOf(
            "com.google.android.apps.walletnfcrel", // Google Wallet
            "com.paypal.android.p2pmobile",
            "com.venmo",
            "com.squareup.cash",
            "com.robinhood.android",
            "com.coinbase.android"
        )
        return label in listOf("pay", "send money", "transfer", "buy", "purchase", "confirm payment") ||
                pkg in paymentPkgs
    }

    private fun isInstallAction(label: String, pkg: String): Boolean {
        if (pkg in INSTALLER_PKGS) return true
        if (label in INSTALL_LABELS) return true
        // Softer match: an app-shaped label that also asks to install/download/update/get.
        return label.contains("app") &&
            (label.contains("install") || label.contains("download") ||
                label.contains("update") || label.contains("get"))
    }

    private fun isDeletionAction(label: String): Boolean {
        return label in listOf("delete", "remove", "clear", "erase", "discard", "trash",
            "delete all", "clear all", "factory reset")
    }

    private fun isSystemSettingsAction(pkg: String): Boolean {
        return pkg == "com.android.settings" ||
                pkg == "com.google.android.settings.intelligence"
    }

    private fun isPermissionAction(label: String): Boolean {
        return label in listOf(
            "allow", "allow always", "allow only while using the app",
            "while using the app", "only this time", "grant"
        )
    }

    companion object {
        private val INSTALL_LABELS = setOf(
            "install", "install now", "install app", "update", "update now",
            "download", "download now", "get", "get app", "get it",
            "accept & download", "accept and download", "install from unknown sources"
        )
        private val INSTALLER_PKGS = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.google.android.permissioncontroller"
        )
    }
}

data class ActionContext(
    val packageName: String,
    val nodeLabel: String,
    val nodeId: Int
)
