package com.androclaw.agent.safety

import com.androclaw.agent.data.SecurePreferences

/**
 * Controls which apps the agent is allowed to operate in.
 * Supports allowlist mode (only listed apps) and blocklist mode (all except listed).
 */
class AppFilter(private val prefs: SecurePreferences) {

    /**
     * Returns true if the agent is allowed to operate in the given package.
     */
    fun isAllowed(packageName: String): Boolean {
        // Always block our own app to prevent self-modification loops
        if (packageName == "com.androclaw.agent" ||
            packageName == "com.androclaw.agent.debug") return false

        if (prefs.unrestrictedMode) return true

        // Check blocklist first (always respected)
        if (packageName in prefs.blocklistPackages) return false

        // If allowlist mode is on, only allow listed packages
        return if (prefs.useAllowlist) {
            packageName in prefs.allowlistPackages
        } else {
            true
        }
    }

    /**
     * Returns a human-readable reason why the package is blocked, or null if allowed.
     */
    fun blockReason(packageName: String): String? {
        if (packageName == "com.androclaw.agent" ||
            packageName == "com.androclaw.agent.debug") {
            return "Cannot operate within AndroClaw itself"
        }
        if (prefs.unrestrictedMode) return null

        if (packageName in prefs.blocklistPackages) {
            return "App is in the blocklist"
        }
        if (prefs.useAllowlist && packageName !in prefs.allowlistPackages) {
            return "App is not in the allowlist"
        }
        return null
    }
}
