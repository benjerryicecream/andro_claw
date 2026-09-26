package com.androclaw.agent.verify

import android.content.Context
import android.content.pm.PackageManager

/**
 * Deterministic task-outcome verification for the AndroClaw harness.
 *
 * The rule: the agent loop must never declare a task done on the basis of
 * "I took an action". After the loop believes it is finished, the harness
 * calls [verify] against a [Goal] inferred from the original command.
 * Only a verified outcome counts as done — otherwise the loop keeps working
 * (up to maxSteps) instead of reporting success.
 *
 * Integration sketch (AgentLoop.runWithHarness):
 *
 *   val goal = TaskVerifier.inferGoal(command, context)   // once, at task start
 *   ...
 *   // where the loop currently returns success:
 *   val check = goal?.let {
 *       TaskVerifier.verify(context, it, foregroundPackage, lastSnapshotText)
 *   }
 *   if (check == null || check.ok) return Success(...)     // null goal = nothing deterministic to check
 *   else if (steps < maxSteps) continue                    // NOT done: keep working
 *   else return Failed("gave up: ${check.detail}")
 */
object TaskVerifier {

    /** Outcomes the harness can verify without asking an LLM. */
    sealed interface Goal {
        /** Package must be installed (e.g. after "download Temu"). */
        data class AppInstalled(val packageName: String) : Goal
        /** Package must be gone (e.g. after "uninstall Temu"). */
        data class AppRemoved(val packageName: String) : Goal
        /** Given package must be the current foreground app. */
        data class AppInForeground(val packageName: String) : Goal
        /** Latest UI snapshot text must contain [text] (case-insensitive). */
        data class ScreenShows(val text: String) : Goal
    }

    data class Result(val ok: Boolean, val detail: String)

    fun verify(
        context: Context,
        goal: Goal,
        foregroundPackage: String?,
        snapshotText: String,
    ): Result = when (goal) {
        is Goal.AppInstalled -> {
            val ok = isInstalled(context, goal.packageName)
            Result(ok, if (ok) "${goal.packageName} is installed"
            else "${goal.packageName} is NOT installed")
        }
        is Goal.AppRemoved -> {
            val ok = !isInstalled(context, goal.packageName)
            Result(ok, if (ok) "${goal.packageName} is absent"
            else "${goal.packageName} is still installed")
        }
        is Goal.AppInForeground -> {
            val ok = foregroundPackage == goal.packageName
            Result(ok, "foreground=$foregroundPackage, want=${goal.packageName}")
        }
        is Goal.ScreenShows -> {
            val ok = snapshotText.contains(goal.text, ignoreCase = true)
            Result(ok, if (ok) "screen shows \"${goal.text}\""
            else "screen does not show \"${goal.text}\"")
        }
    }

    fun isInstalled(context: Context, packageName: String): Boolean =
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /**
     * Fuzzy app label -> package name. Mirrors the open_app tool's matching
     * so the verifier and the actor agree on what "Temu" means.
     *
     * Matching is token-based, never raw substring: "temu" must match the
     * label as a WORD ("Temu"), otherwise every installed app whose name
     * merely contains those letters (e.g. "SystemUI Overlay" ⊃ "temu") would
     * be a false positive. A short prefix fallback keeps shorthand useful.
     */
    fun resolvePackage(context: Context, appLabel: String): String? {
        val pm = context.packageManager
        val want = tokens(appLabel)
        if (want.isEmpty()) return null
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        // Every query word must be a word in the installed app's label.
        val containment = apps.firstOrNull { app ->
            want.all { it in tokens(requireLabel(pm, app)) }
        }
        if (containment != null) return containment.packageName

        // "snap" -> "Snapchat" style shorthand, only for long-enough prefixes.
        if (want.size == 1 && want.first().length >= 4) {
            val q = want.first()
            val prefix = apps.firstOrNull { app ->
                tokens(requireLabel(pm, app)).any { it.startsWith(q) }
            }
            if (prefix != null) return prefix.packageName
        }
        return null
    }

    private fun tokens(text: String): List<String> =
        text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
            .split(" ").filter { it.isNotBlank() }

    private fun requireLabel(pm: PackageManager, app: android.content.pm.ApplicationInfo): String =
        try { pm.getApplicationLabel(app)?.toString() ?: "" } catch (e: Exception) { "" }

    /**
     * Best-effort goal inference from a raw command. Returns null when no
     * deterministic goal applies — the harness then falls back to the
     * existing behavior (a future LLM judge can cover those).
     *
     * Supported patterns:
     *   "download X" / "install X" -> AppInstalled(pkg)
     *   "uninstall X" / "remove X" -> AppRemoved(pkg)
     *   "open X"                   -> AppInForeground(pkg)
     */
    fun inferGoal(command: String, context: Context): Goal? {
        val c = command.lowercase()
        // Order matters: check install/uninstall before the bare "open".
        Regex("(?:download|install)\\s+([a-z0-9][a-z0-9 ]*)").find(c)?.let { m ->
            val pkg = resolvePackage(context, m.groupValues[1]) ?: return null
            return Goal.AppInstalled(pkg)
        }
        Regex("(?:uninstall|remove)\\s+([a-z0-9][a-z0-9 ]*)").find(c)?.let { m ->
            val pkg = resolvePackage(context, m.groupValues[1]) ?: return null
            return Goal.AppRemoved(pkg)
        }
        Regex("^open\\s+([a-z0-9][a-z0-9 ]*)").find(c)?.let { m ->
            val pkg = resolvePackage(context, m.groupValues[1]) ?: return null
            return Goal.AppInForeground(pkg)
        }
        return null
    }
}