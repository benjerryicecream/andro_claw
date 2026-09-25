package com.androclaw.agent.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.androclaw.agent.perception.UiSnapshot
import com.androclaw.agent.safety.SafetyGuard
import com.androclaw.agent.safety.SafetyResult
import kotlinx.coroutines.runBlocking

/**
 * AgentTool that opens an installed app by name.
 * Safety: routed through SafetyGuard (blocklist, allowlist, first-use and
 * sensitive-action confirmation gates) before the launch intent is fired.
 */
class OpenAppTool(
    private val context: Context,
    private val safetyGuard: SafetyGuard
) : AgentTool {

    override val name: String = "open_app"
    override val description: String =
        "Open an installed app by its name, e.g. \"chrome\", \"maps\", \"calculator\""

    override fun execute(args: Map<String, String>): String {
        val rawQuery = args["query"]?.trim().orEmpty()
        if (rawQuery.isEmpty()) return "error: no app query provided"

        val packageName = resolvePackage(normalize(rawQuery))
            ?: return "error: no app matching '$rawQuery'"

        val label = appLabel(packageName)
        val snapshot = UiSnapshot(packageName = packageName, activityName = "", nodes = emptyList())
        when (val safety = runBlocking {
            safetyGuard.checkAndConfirm(AgentAction.OpenApp(packageName), snapshot)
        }) {
            is SafetyResult.Blocked -> return "error: blocked — ${safety.reason} ($label)"
            is SafetyResult.Cancelled -> return "cancelled: user declined to open $label"
            is SafetyResult.Allowed -> { /* proceed */ }
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return "error: no launch intent for $packageName"
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(launchIntent)
            "Opened $label ($packageName)"
        } catch (e: Exception) {
            "error: could not open $label: ${e.message}"
        }
    }

    /** Lowercase, collapse whitespace, strip filler words like "app" and "the". */
    private fun normalize(raw: String): String = raw.lowercase()
        .replace(Regex("\\s+"), " ")
        .removeSuffix(" app")
        .removePrefix("the ")
        .trim()

    /** Fuzzy-match the query against installed launcher apps by label/package, case-insensitive. */
    private fun resolvePackage(query: String): String? {
        val pm = context.packageManager
        if (pm.getLaunchIntentForPackage(query) != null) return query

        val launcherIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed = try {
            pm.queryIntentActivities(launcherIntent, 0)
        } catch (e: Exception) {
            return null
        }

        var bestScore = 0
        var bestPackage: String? = null
        for (activity in installed) {
            val pkg = activity.activityInfo.packageName
            val label = (activity.loadLabel(pm)?.toString() ?: pkg).lowercase()
            val score = matchScore(query, pkg.lowercase(), label)
            if (score > bestScore) {
                bestScore = score
                bestPackage = pkg
            }
        }
        return if (bestScore >= MATCH_THRESHOLD) bestPackage else null
    }

    private fun matchScore(query: String, pkg: String, label: String): Int {
        if (label == query || pkg == query) return 10
        if (label.startsWith(query)) return 8
        if (label.contains(query)) return 6
        val tokens = query.split(" ").filter { it.length > 2 }
        if (tokens.isNotEmpty() && tokens.all { label.contains(it) || pkg.contains(it) }) return 6
        return 0
    }

    private fun appLabel(pkg: String): String {
        val pm = context.packageManager
        val info = try {
            pm.getApplicationInfo(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return pkg
        }
        return pm.getApplicationLabel(info)?.toString() ?: pkg
    }

    companion object {
        private const val MATCH_THRESHOLD = 6
    }
}