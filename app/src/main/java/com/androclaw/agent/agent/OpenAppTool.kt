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
 *
 * When a decision backend is configured, the launch target is also asked as an
 * app_pick question over the top [MAX_CANDIDATES] fuzzy matches. LLM-driven
 * launches (backend off, unreachable, or low-confidence answer) are logged to
 * the [TrainingLogger] post-SafetyGuard so they become training data; picks made
 * by the backend itself are never logged.
 */
class OpenAppTool(
    private val context: Context,
    private val safetyGuard: SafetyGuard,
    private val decision: DecisionClient? = null,
    private val trainingLogger: TrainingLogger? = null,
    private val llmSource: String = "unknown"
) : AgentTool {

    override val name: String = "open_app"
    override val description: String =
        "Open an installed app by its name, e.g. \"chrome\", \"maps\", \"calculator\""

    @Volatile
    private var lastOpened: String? = null

    override fun lastOpenedPackage(): String? = lastOpened

    override fun execute(args: Map<String, String>): String {
        lastOpened = null
        val rawQuery = args["query"]?.trim().orEmpty()
        if (rawQuery.isEmpty()) return "error: no app query provided"

        val query = normalize(rawQuery)
        val candidates = resolveCandidates(query)
        if (candidates.isEmpty()) return "error: no app matching '$rawQuery'"

        val topCandidates = candidates.take(MAX_CANDIDATES)
        var decidedByBackend = false
        var packageName = topCandidates.first().packageName
        if (decision != null) {
            val picked = queryBackendForApp(query, topCandidates)
            if (picked != null) {
                packageName = picked.packageName
                decidedByBackend = true
            }
        }

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

        val result = try {
            context.startActivity(launchIntent)
            lastOpened = packageName
            "Opened $label ($packageName)"
        } catch (e: Exception) {
            "error: could not open $label: ${e.message}"
        }

        // Post-SafetyGuard training record: log only real launches, and only when
        // the pick was LLM-driven (backend-driven picks would be feedback loops).
        if (!decidedByBackend && result.startsWith("Opened")) {
            trainingLogger?.logDecision(
                taskId = args[AgentHarness.INTERNAL_TASK_ID] ?: "",
                step = args[AgentHarness.INTERNAL_STEP]?.toIntOrNull() ?: 0,
                questionId = TrainingLogger.QUESTION_APP_PICK,
                state = "Open app: $query",
                options = topCandidates.associate { candidate ->
                    "${candidate.label} (${candidate.packageName})" to candidate.packageName
                },
                label = packageName,
                source = llmSource
            )
        }
        return result
    }

    /** Ask the decision backend which of the top candidates to open. */
    private fun queryBackendForApp(
        query: String,
        topCandidates: List<AppCandidate>
    ): AppCandidate? {
        val decision = this.decision ?: return null
        val state = "Open app: $query"
        val optionMap = topCandidates.associateBy { "${it.label} (${it.packageName})" }
        val questions = mapOf(
            TrainingLogger.QUESTION_APP_PICK to DecisionQuestion(
                type = "choice",
                instructions = "Which installed app best matches the user's request?",
                options = optionMap.mapValues { it.value.packageName }
            )
        )
        val answers = try {
            runBlocking { decision.evaluate(state, questions) }
        } catch (e: Exception) {
            null
        }
        val choice = answers?.get(TrainingLogger.QUESTION_APP_PICK) as? DecisionAnswer.Choice
            ?: return null
        return if (choice.confidence >= DECISION_EXECUTE_CONFIDENCE) {
            optionMap[choice.choice]
        } else {
            null
        }
    }

    /** One fuzzy-matching candidate. */
    private data class AppCandidate(val packageName: String, val label: String, val score: Int)

    /** Installed launcher apps fuzzy-scored against the query, best first. */
    private fun resolveCandidates(query: String): List<AppCandidate> {
        val pm = context.packageManager
        if (pm.getLaunchIntentForPackage(query) != null) {
            return listOf(AppCandidate(query, appLabel(query), PERFECT_SCORE))
        }

        val launcherIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed = try {
            pm.queryIntentActivities(launcherIntent, 0)
        } catch (e: Exception) {
            return emptyList()
        }

        val seen = mutableSetOf<String>()
        val scored = mutableListOf<AppCandidate>()
        for (activity in installed) {
            val pkg = activity.activityInfo.packageName
            if (!seen.add(pkg)) continue
            val label = (activity.loadLabel(pm)?.toString() ?: pkg).lowercase()
            val score = matchScore(query, pkg.lowercase(), label)
            if (score >= MATCH_THRESHOLD) {
                scored.add(AppCandidate(pkg, label, score))
            }
        }
        scored.sortWith(compareByDescending<AppCandidate> { it.score }.thenBy { it.packageName })
        return scored
    }

    private fun matchScore(query: String, pkg: String, label: String): Int {
        if (label == query || pkg == query) return 10
        if (label.startsWith(query)) return 8
        if (label.contains(query)) return 6
        val tokens = query.split(" ").filter { it.length > 2 }
        if (tokens.isNotEmpty() && tokens.all { label.contains(it) || pkg.contains(it) }) return 6
        return 0
    }

    /** Lowercase, collapse whitespace, strip filler words like "app" and "the". */
    private fun normalize(raw: String): String = raw.lowercase()
        .replace(Regex("\\s+"), " ")
        .removeSuffix(" app")
        .removePrefix("the ")
        .trim()

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
        private const val PERFECT_SCORE = 10
        private const val MAX_CANDIDATES = 5
        private const val DECISION_EXECUTE_CONFIDENCE = 0.85
    }
}