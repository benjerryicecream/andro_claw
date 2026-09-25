package com.androclaw.agent.agent

import com.androclaw.agent.llm.LlmMessage
import com.androclaw.agent.llm.LlmProvider
import com.androclaw.agent.llm.LlmResponse as ProviderLlmResponse
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * One unit of a decomposed command. [OpenApp] / [OpenUrl] / [WebSearch] are
 * deterministic steps the executor handles directly; [SubTask] is a sub-goal
 * that must be carried out on-device via the perceive-act loop.
 */
sealed class PlanStep {
    data class OpenApp(val target: String) : PlanStep()
    data class OpenUrl(val url: String) : PlanStep()
    data class WebSearch(val query: String, val searchUrl: String) : PlanStep()
    data class SubTask(val instruction: String) : PlanStep()

    val label: String
        get() = when (this) {
            is OpenApp -> "open $target"
            is OpenUrl -> "open $url"
            is WebSearch -> "search '$query'"
            is SubTask -> instruction
        }

    fun toJson(): String = when (this) {
        is OpenApp -> JSONObject().put("action", "open_app").put("target", target).toString()
        is OpenUrl -> JSONObject().put("action", "open_url").put("target", url).toString()
        is WebSearch -> JSONObject().put("action", "search").put("target", query).toString()
        is SubTask -> JSONObject().put("action", "task").put("target", instruction).toString()
    }
}

/**
 * Result of planning. [Single] keeps the existing (zero-LLM-token) single-command
 * fast path; [Ordered] carries the sequential plan to execute.
 */
sealed class CommandPlan {
    data class Single(val step: PlanStep) : CommandPlan()
    data class Ordered(val steps: List<PlanStep>) : CommandPlan()

    fun describe(): String = when (this) {
        is Single -> step.label
        is Ordered -> steps.joinToString(" → ") { it.label }
    }
}

/** Outcome of executing one plan step — the plan halts on the first failure. */
sealed class StepOutcome {
    data class Done(val note: String) : StepOutcome()
    data class Failed(val reason: String) : StepOutcome()
}

/** Plan plus the tokens consumed while producing it (LLM decomposition). */
data class PlanResult(val plan: CommandPlan, val usage: TokenUsage)

/**
 * Multi-step command planner.
 *
 * Splits a compound utterance on conjunctions ("and", "then", "after that",
 * commas) into ordered clauses. Confident single commands stay single (fast
 * path untouched). When any clause is opaque to the deterministic grammar,
 * the LLM refines the whole utterance into concrete steps; if the LLM fails or
 * is unavailable, unparseable clauses fall back to [PlanStep.SubTask] so a
 * sentence is never misread as a single app name.
 */
object CommandPlanner {

    private val CONJUNCTION = Regex("""(?i)\s*(?:\band\b|\bthen\b|,|\bafter\s+that\b)\s*""")
    private const val MAX_PLAN_STEPS = 8

    fun splitClauses(input: String): List<String> =
        input.split(CONJUNCTION).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Decompose [input]. [provider] may be null; then compound commands are
     * planned deterministically and ambiguous clauses become [PlanStep.SubTask].
     */
    suspend fun plan(input: String, provider: LlmProvider? = null): PlanResult {
        val clauses = splitClauses(input)
        if (clauses.isEmpty()) {
            return PlanResult(CommandPlan.Single(PlanStep.SubTask(input.trim())), TokenUsage())
        }
        if (clauses.size == 1) {
            val step = parseClause(clauses[0]) ?: PlanStep.SubTask(clauses[0])
            return PlanResult(CommandPlan.Single(step), TokenUsage())
        }

        val base = clauses.map { parseClause(it) ?: PlanStep.SubTask(it) }
        val needsLlm = base.any { it is PlanStep.SubTask } && provider != null
        return if (needsLlm) {
            decompose(input, provider, base)
        } else {
            PlanResult(CommandPlan.Ordered(base), TokenUsage())
        }
    }

    /** Deterministic parse of one clause. Null means the clause is opaque. */
    fun parseClause(clause: String): PlanStep? {
        val trimmed = clause.trim()
        return when (val intent = RequestHarness.parseGoal(trimmed)) {
            is RequestHarness.ParsedIntent.WebNavigation -> PlanStep.OpenUrl(intent.url)
            is RequestHarness.ParsedIntent.WebSearch -> PlanStep.WebSearch(intent.query, intent.searchUrl)
            is RequestHarness.ParsedIntent.GeneralTask ->
                CommandParser.parseLocal(trimmed)?.let { PlanStep.OpenApp(it.args.getValue("query")) }
        }
    }

    /** Ask the LLM to turn the utterance into concrete ordered steps. */
    private suspend fun decompose(
        input: String,
        provider: LlmProvider,
        base: List<PlanStep>
    ): PlanResult {
        val system = buildString {
            appendLine("You convert a user's command into an ordered JSON array of concrete steps.")
            appendLine("Each element: {\"action\": \"open_app\" | \"open_url\" | \"search\" | \"task\", \"target\": \"...\"}")
            appendLine("- open_app: open an installed app; target = the app name as the user phrased it.")
            appendLine("- open_url: open a specific website; target = the full URL.")
            appendLine("- search: perform a web search; target = the query text.")
            appendLine("- task: a sub-step that must be carried out on the device by tapping and typing (e.g. turning on a setting, playing a playlist); target = the exact sub-instruction.")
            appendLine("Preserve the user's ordering. Never merge steps. Reply with ONLY the JSON array, no markdown, no prose.")
        }
        val messages = listOf(
            ChatMessage("system", system),
            ChatMessage("user", input)
        )
        return try {
            val resp = provider.complete(messages.map { LlmMessage(it.role, it.content) }, 0.0f, 512)
            when (resp) {
                is ProviderLlmResponse.Success -> {
                    val usage = TokenUsage(resp.inputTokens, resp.outputTokens)
                    val steps = parseSteps(resp.text)
                    if (steps != null && steps.isNotEmpty()) {
                        PlanResult(CommandPlan.Ordered(steps), usage)
                    } else {
                        PlanResult(CommandPlan.Ordered(base), usage)
                    }
                }
                is ProviderLlmResponse.Error -> PlanResult(CommandPlan.Ordered(base), TokenUsage())
            }
        } catch (e: Exception) {
            PlanResult(CommandPlan.Ordered(base), TokenUsage())
        }
    }

    private fun parseSteps(text: String): List<PlanStep>? {
        return try {
            val clean = text.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val start = clean.indexOf('[')
            val end = clean.lastIndexOf(']')
            if (start < 0 || end < start) null
            else {
                val arr = JSONArray(clean.substring(start, end + 1))
                if (arr.length() == 0 || arr.length() > MAX_PLAN_STEPS) {
                    null
                } else {
                    val steps = (0 until arr.length()).mapNotNull { i ->
                        val obj = arr.getJSONObject(i)
                        val action = obj.optString("action").trim().lowercase()
                        val target = obj.optString("target").trim()
                        if (target.isEmpty()) {
                            null
                        } else {
                            when (action) {
                                "open_app" -> PlanStep.OpenApp(target)
                                "open_url" -> PlanStep.OpenUrl(RequestHarness.formatUrl(target))
                                "search" -> PlanStep.WebSearch(
                                    target,
                                    "https://www.google.com/search?q=" +
                                        URLEncoder.encode(target, StandardCharsets.UTF_8.name())
                                )
                                else -> PlanStep.SubTask(target)
                            }
                        }
                    }
                    if (steps.isEmpty()) null else steps
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}