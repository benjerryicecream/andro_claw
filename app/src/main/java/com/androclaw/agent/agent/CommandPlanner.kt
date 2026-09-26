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
 * that must be carried out on-device via the perceive-act loop. [waitFor] is
 * the structured plan's screen-state condition the executor waits for before
 * judging the step's visible outcome (best-effort, bounded, never fatal).
 */
sealed class PlanStep {
    /** Screen state the structured plan waits for before judging this step's outcome. */
    abstract val waitFor: String?

    data class OpenApp(val target: String, override val waitFor: String? = null) : PlanStep()
    data class OpenUrl(val url: String, override val waitFor: String? = null) : PlanStep()
    data class WebSearch(val query: String, val searchUrl: String, override val waitFor: String? = null) : PlanStep()
    data class ScopedSearch(
        val app: String,
        val query: String,
        val installGoal: Boolean = false,
        override val waitFor: String? = null
    ) : PlanStep()
    data class SubTask(val instruction: String, override val waitFor: String? = null) : PlanStep()

    val label: String
        get() = when (this) {
            is OpenApp -> "open $target"
            is OpenUrl -> "open $url"
            is WebSearch -> "search '$query'"
            is ScopedSearch -> if (installGoal) "install '$query' in $app" else "search '$query' in $app"
            is SubTask -> instruction
        }

    fun toJson(): String = when (this) {
        is OpenApp -> JSONObject().put("action", "open_app").put("target", target).toString()
        is OpenUrl -> JSONObject().put("action", "open_url").put("target", url).toString()
        is WebSearch -> JSONObject().put("action", "search").put("target", query).toString()
        is ScopedSearch -> JSONObject()
            .put("action", "search_in_app").put("target", app).put("query", query).put("install", installGoal)
            .toString()
        is SubTask -> JSONObject().put("action", "task").put("target", instruction).toString()
    }
}

/**
 * Machine-checkable goal the structured planner returns so exit verification can
 * run deterministically (mapped onto [com.androclaw.agent.verify.TaskVerifier.Goal]
 * once the on-device label→package resolution is available).
 */
data class PlanGoal(val kind: String, val target: String) {
    /** The four kinds the planner may emit (mirror TaskVerifier's semantics). */
    companion object {
        const val APP_INSTALLED = "app_installed"
        const val APP_REMOVED = "app_removed"
        const val APP_IN_FOREGROUND = "app_in_foreground"
        const val SCREEN_SHOWS = "screen_shows"
    }
}

/**
 * Result of planning. [Single] keeps the zero-LLM-token single-command fast path;
 * [Ordered] carries the sequential plan to execute. Both may also carry the
 * planner's verification goal and sensitive-action confirmation flag.
 */
sealed class CommandPlan {
    abstract val verifierGoal: PlanGoal?
    abstract val needsConfirmation: Boolean
    abstract val goalText: String

    data class Single(
        val step: PlanStep,
        override val verifierGoal: PlanGoal? = null,
        override val needsConfirmation: Boolean = false,
        override val goalText: String = ""
    ) : CommandPlan()

    data class Ordered(
        val steps: List<PlanStep>,
        override val verifierGoal: PlanGoal? = null,
        override val needsConfirmation: Boolean = false,
        override val goalText: String = ""
    ) : CommandPlan()

    fun describe(): String {
        val body = when (this) {
            is Single -> step.label
            is Ordered -> steps.joinToString(" → ") { it.label }
        }
        val confirm = if (needsConfirmation) " [needs_confirmation]" else ""
        val goal = if (goalText.isNotBlank()) " [goal: $goalText]" else ""
        return "$body$confirm$goal"
    }
}

/** Outcome of executing one plan step — the plan halts on the first failure. */
sealed class StepOutcome {
    data class Done(val note: String) : StepOutcome()
    data class Failed(val reason: String) : StepOutcome()
}

/** Plan plus the tokens consumed while producing it (LLM structured parse). */
data class PlanResult(val plan: CommandPlan, val usage: TokenUsage)

/**
 * Structured command planner.
 *
 * [parseCommand] is the single entry point: the deterministic regex survives ONLY
 * as a pre-filter for the exact pattern "^open <single app>$" (zero tokens); every
 * other command is decomposed by ONE Gemini structured-output (JSON) call that
 * returns steps[] (action, app, args, wait_for), a TaskVerifier goal, and a
 * sensitive-action confirmation flag. When the provider is unavailable or the
 * JSON cannot be parsed, the planner falls back to the previous deterministic
 * decomposition so a sentence is never misread as a single app name.
 *
 * Every parse (fast path, LLM, or fallback) is logged via the optional
 * [TrainingLogger] into laya_training.jsonl — Phase 2 fine-tuning data for
 * distilling the planner into the local Laya model.
 */
object CommandPlanner {

    private val CONJUNCTION = Regex("""(?i)\s*(?:\band\b|\bthen\b|,|\bafter\s+that\b)\s*""")
    private const val MAX_PLAN_STEPS = 8

    /** Exact "^open <single app>$" pre-filter. Only non-ambiguous, declension-free app names pass. */
    private val OPEN_SINGLE_APP = Regex("""(?i)^\s*open\s+([\p{L}\p{N}][\p{L}\p{N} _.-]*?)\s*$""")

    /** Words that make the open-target ambiguous (a compound clause, not an app name). */
    private val AMBIGUOUS_APP_WORDS = setOf(
        "and", "then", "or", "for", "to", "with", "the", "a", "an", "in", "on", "after", "before"
    )

    /** Linking words that may leak into LLM-generated step targets and must be stripped. */
    private val LEADING_JOINERS = listOf(
        "and", "then", "also", "next", "afterwards", "meanwhile", "after that", "in addition"
    )

    /** App-open targets that count as the browser; searches after these stay web searches. */
    private val BROWSER_WORDS = listOf(
        "chrome", "chromium", "browser", "firefox", "internet", "web", "samsung internet", "google", "google chrome"
    )

    /** App-open targets that are app stores/installers; install clauses after these search inside the store. */
    private val STORE_WORDS = listOf(
        "play store", "google play", "play", "galaxy store", "samsung store", "samsung apps",
        "app store", "app gallery", "aptoide", "amazon appstore", "amazon app store"
    )

    /** Structured-plan JSON actions the LLM may emit. */
    private val ACTION_MAP = mapOf(
        "open_app" to "open_app",
        "open_url" to "open_url",
        "search" to "search",
        "web_search" to "search",
        "search_in_app" to "search_in_app",
        "in_app_search" to "search_in_app",
        "install" to "install",
        "install_in_app" to "install",
        "download" to "install",
        "remove" to "remove",
        "uninstall" to "remove",
        "task" to "task"
    )

    /** True when [input] matches the exact "^open <single app>$" pre-filter. */
    fun isSingleOpenCommand(input: String): Boolean {
        val m = OPEN_SINGLE_APP.matchEntire(input.trim()) ?: return false
        val app = m.groupValues[1].trim()
        val words = app.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size !in 1..4) return false
        return words.none { it.lowercase() in AMBIGUOUS_APP_WORDS }
    }

    fun splitClauses(input: String): List<String> =
        input.split(CONJUNCTION).map { cleanTarget(it) }.filter { it.isNotEmpty() }

    /**
     * Remove leading conjunction/transition words ("and", "then", "also", …) and
     * stray punctuation from a phrase, so a decomposed step never starts with a
     * linking word (e.g. an LLM step target of "and download temu").
     */
    fun cleanTarget(target: String): String {
        var t = target.trim()
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            val lower = t.lowercase()
            for (joiner in LEADING_JOINERS) {
                if (lower == joiner || lower.startsWith("$joiner ") || lower.startsWith("$joiner,")) {
                    t = t.substring(joiner.length).removePrefix(",").removePrefix(";").trim()
                    changed = true
                    break
                }
            }
        }
        return t
    }

    /** True when the app-open target names a web browser in user terms. */
    fun isBrowserTarget(target: String): Boolean {
        val t = cleanTarget(target).lowercase()
        return BROWSER_WORDS.any { word -> t == word || t.startsWith("$word ") || t.contains(" $word") }
    }

    /** True when an app-open target names a store/installer in user terms. */
    fun isStoreTarget(target: String): Boolean {
        val t = cleanTarget(target).lowercase()
        return STORE_WORDS.any { word -> t == word || t.startsWith("$word ") || t.contains(" $word") }
    }

    /**
     * Searches and install clauses that FOLLOW an app-open clause stay inside
     * that app: a search becomes a [PlanStep.ScopedSearch] scoped to the opened
     * app instead of tearing control away to the browser, and an install/download
     * clause after an app store becomes a store-scoped search for the entity.
     * Searches after (or without) a browser open keep the existing web-search path.
     */
    fun scopeSearches(steps: List<PlanStep>): List<PlanStep> {
        val out = mutableListOf<PlanStep>()
        for (step in steps) {
            val prev = out.lastOrNull()
            when {
                step is PlanStep.WebSearch && prev is PlanStep.OpenApp && !isBrowserTarget(prev.target) ->
                    out += PlanStep.ScopedSearch(cleanTarget(prev.target), step.query, installGoal = false)

                step is PlanStep.SubTask && prev is PlanStep.OpenApp &&
                    !isBrowserTarget(prev.target) && isStoreTarget(prev.target) -> {
                    val entity = installEntity(step.instruction)
                    if (entity != null) out += PlanStep.ScopedSearch(cleanTarget(prev.target), entity, installGoal = true)
                    else out += step
                }

                else -> out += step
            }
        }
        return out
    }

    /** Entity after a leading install verb ("download temu" -> "temu"); null when not an install clause. */
    fun installEntity(instruction: String): String? {
        val verbs = listOf("download", "install", "get", "add", "fetch", "acquire")
        val t = cleanTarget(instruction)
        val lower = t.lowercase()
        for (verb in verbs) {
            if (lower == verb) return null
            if (lower.startsWith("$verb ")) {
                val entity = t.substring(verb.length).trim()
                    .removePrefix("the").removePrefix("app").trim()
                return cleanTarget(entity).ifEmpty { null }
            }
        }
        return null
    }

    /**
     * Parse [input] into a [CommandPlan]. Fast path (exact single-app open) needs
     * no LLM; everything else is ONE structured-output call against [provider].
     * [logger]/[source]/[taskId] record every parse into laya_training.jsonl.
     */
    suspend fun parseCommand(
        input: String,
        provider: LlmProvider?,
        logger: TrainingLogger? = null,
        source: String = "unknown",
        taskId: String = ""
    ): PlanResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return PlanResult(
                CommandPlan.Single(PlanStep.SubTask(input)),
                TokenUsage()
            ).also { logParse(logger, taskId, trimmed, source, "plan_single", it) }
        }

        // Fast path: EXACT "^open <single app>$". No LLM, no scope inference.
        if (isSingleOpenCommand(trimmed)) {
            val app = OPEN_SINGLE_APP.matchEntire(trimmed)!!.groupValues[1].trim()
            val plan = CommandPlan.Single(
                step = PlanStep.OpenApp(app),
                verifierGoal = PlanGoal(PlanGoal.APP_IN_FOREGROUND, app),
                goalText = "$app in the foreground"
            )
            return PlanResult(plan, TokenUsage()).also {
                logParse(logger, taskId, trimmed, "regex-fast", "fast_path", it)
            }
        }

        // Structured planner is the primary path when a provider is configured.
        if (provider != null) {
            val (plan, usage) = structuredPlan(trimmed, provider)
            var result = PlanResult(plan, usage)
            if (plan is CommandPlan.Ordered || plan is CommandPlan.Single) {
                logParse(logger, taskId, trimmed, provider.name, "llm_plan", result)
            }
            return result
        }

        // Fallback: no provider configured; deterministic decomposition only.
        return plan(trimmed, null).also {
            logParse(logger, taskId, trimmed, "deterministic", "fallback", it)
        }
    }

    private fun logParse(
        logger: TrainingLogger?,
        taskId: String,
        command: String,
        source: String,
        path: String,
        result: PlanResult
    ) {
        logger?.logParse(
            taskId = taskId,
            command = command,
            planJson = planToJson(result.plan),
            verifierGoal = result.plan.verifierGoal,
            needsConfirmation = result.plan.needsConfirmation,
            promptTokens = result.usage.promptTokens,
            completionTokens = result.usage.completionTokens,
            source = source,
            path = path
        )
    }

    private fun planToJson(plan: CommandPlan): String {
        val stepsArr = JSONArray()
        when (plan) {
            is CommandPlan.Single -> stepsArr.put(JSONObject(plan.step.toJson()))
            is CommandPlan.Ordered -> plan.steps.forEach { stepsArr.put(JSONObject(it.toJson())) }
        }
        val obj = JSONObject().put("steps", stepsArr)
        plan.verifierGoal?.let { obj.put("goal", JSONObject().put("kind", it.kind).put("target", it.target)) }
        obj.put("needsConfirmation", plan.needsConfirmation)
        return obj.toString()
    }

    /**
     * One structured-output call. The system prompt seeds the schema AND the
     * three real-failure trajectories that previously broke the regex parser;
     * Gemini is constrained to emit exactly one JSON object.
     */
    private suspend fun structuredPlan(input: String, provider: LlmProvider): Pair<CommandPlan, TokenUsage> {
        val system = structuredSystemPrompt()
        val messages = listOf(
            LlmMessage("system", system),
            LlmMessage("user", input)
        )
        return try {
            val resp = provider.completeJson(messages, 0.0f, 1024)
            when (resp) {
                is ProviderLlmResponse.Success -> {
                    val usage = TokenUsage(resp.inputTokens, resp.outputTokens)
                    val parsed = parseStructuredPlan(resp.text)
                    if (parsed != null) parsed to usage
                    else plan(input, provider).run { (this.plan to (usage + this.usage)) }
                }
                is ProviderLlmResponse.Error ->
                    plan(input, provider).run { (this.plan to this.usage) }
            }
        } catch (e: Exception) {
            plan(input, provider).run { (this.plan to this.usage) }
        }
    }

    private fun structuredSystemPrompt(): String = buildString {
        appendLine("You are the command planner of an Android phone assistant. You convert ONE user command into an ordered, machine-executable plan.")
        appendLine()
        appendLine("Reply with ONLY one JSON object, no markdown fences, no prose, following EXACTLY this shape:")
        appendLine("""{"steps":[{"action":"<action>","app":"<app/url/instruction>","args":{},"wait_for":"<screen state to wait for, plain English>"}],"goal":"<one sentence describing the final verified end-state>","verify":{"kind":"synth","target":"<concrete label/text that proves the end-state>"},"needs_confirmation":false}""")
        appendLine()
        appendLine("Actions:")
        appendLine("- \"open_app\": open an installed app. app = the app name as the user phrased it.")
        appendLine("- \"open_url\": open a website. app = full URL including scheme.")
        appendLine("- \"search\": a general web search (browsers and generic questions). args.search = the query.")
        appendLine("- \"search_in_app\": use an app's own in-app search box. app = the app, args.query = the search text.")
        appendLine("- \"install\": install a named app via a store. app = the store the user named (e.g. \"play store\"), args.query = the app to install.")
        appendLine("- \"remove\": uninstall an app. args.query = the app to remove.")
        appendLine("- \"task\": a sub-step done on-device by tapping and typing. app = the exact sub-instruction.")
        appendLine()
        appendLine("verify.kind is one of: app_installed | app_removed | app_in_foreground | screen_shows. verify.target must be the concrete label or text proving the end state.")
        appendLine("needs_confirmation must be true for sensitive actions: installing or removing apps, sending messages, payments, deletions, or changing system settings. Everything else false.")
        appendLine("Each wait_for describes a visible screen state (never \"wait N seconds\").")
        appendLine()
        appendLine("Real examples that previously failed a regex parser and MUST be planned correctly:")
        appendLine()
        appendLine("USER: open chrome and search for the weather in Hilo")
        appendLine("""{"steps":[{"action":"open_app","app":"chrome","args":{},"wait_for":"chrome is in the foreground"},{"action":"search","app":"","args":{"search":"weather in Hilo"},"wait_for":"search results for the weather in Hilo are visible"}],"goal":"the screen shows weather results for Hilo","verify":{"kind":"screen_shows","target":"Hilo"},"needs_confirmation":false}""")
        appendLine()
        appendLine("USER: open play store and download temu")
        appendLine("""{"steps":[{"action":"open_app","app":"play store","args":{},"wait_for":"play store is in the foreground"},{"action":"install","app":"play store","args":{"query":"temu"},"wait_for":"temu finishes installing"}],"goal":"temu is installed on the device","verify":{"kind":"app_installed","target":"temu"},"needs_confirmation":true}""")
        appendLine()
        appendLine("USER: open youtube and search for cat videos")
        appendLine("""{"steps":[{"action":"open_app","app":"youtube","args":{},"wait_for":"youtube is open"},{"action":"search_in_app","app":"youtube","args":{"query":"cat videos"},"wait_for":"youtube shows results for cat videos"}],"goal":"youtube shows search results for cat videos","verify":{"kind":"screen_shows","target":"cat videos"},"needs_confirmation":false}""")
        appendLine()
        appendLine("Rules:")
        appendLine("- Preserve the user's ordering and app phrasing exactly. Never merge or drop steps.")
        appendLine("- A search after opening a browser is \"search\" (web). A search after opening a normal app or website is \"search_in_app\".")
        appendLine("- \"install\"/\"download\" a named app: use \"install\"; the app store is the target store. Any command ending in the app being present must verify app_installed.")
        appendLine("- Bare uninstall/remove of an app is a \"task\" step plus verify app_removed and needs_confirmation true.")
        appendLine("- Open only what the user explicitly asked to open; infer a store only for install/download.")
        appendLine("- Reply with ONLY the JSON object.")
    }

    /**
     * Parse the structured JSON response into a [CommandPlan]. Null on any
     * malformed/incomplete response (caller falls back deterministically).
     */
    private fun parseStructuredPlan(text: String): CommandPlan? {
        return try {
            parseStructuredPlanOrNull(text)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseStructuredPlanOrNull(text: String): CommandPlan? {
        val clean = text.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = JSONObject(clean.substring(start, end + 1))

        val arr = obj.optJSONArray("steps") ?: return null
        if (arr.length() == 0 || arr.length() > MAX_PLAN_STEPS) return null

        val wantInstalls = mutableListOf<String>()
        val steps = (0 until arr.length()).mapNotNull { i ->
            val s = arr.getJSONObject(i)
            val action = ACTION_MAP[s.optString("action").trim().lowercase()] ?: return null
            val args = s.optJSONObject("args") ?: JSONObject()
            val waitFor = s.optString("wait_for").trim().ifBlank { null }
            val app = cleanTarget(s.optString("app").trim())

            fun query(): String = cleanTarget(
                args.optString("query").ifBlank { args.optString("search").ifBlank { args.optString("text") } }.trim()
            )

            val step: PlanStep? = when (action) {
                "open_app" -> if (app.isEmpty()) null else PlanStep.OpenApp(app, waitFor)
                "open_url" -> {
                    val url = args.optString("url").ifBlank { app }
                    if (url.isEmpty()) null else PlanStep.OpenUrl(RequestHarness.formatUrl(url), waitFor)
                }
                "search" -> {
                    val q = query()
                    if (q.isEmpty()) null else PlanStep.WebSearch(
                        q,
                        "https://www.google.com/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8.name()),
                        waitFor
                    )
                }
                "install" -> {
                    val q = query()
                    if (q.isEmpty()) null
                    else {
                        wantInstalls += q
                        val store = app.ifEmpty { "play store" }
                        PlanStep.ScopedSearch(store, q, installGoal = true, waitFor = waitFor)
                    }
                }
                "remove" -> {
                    val q = query()
                    if (q.isEmpty()) null else PlanStep.SubTask("uninstall $q", waitFor)
                }
                "search_in_app" -> {
                    val q = query()
                    if (q.isEmpty() || app.isEmpty()) null
                    else PlanStep.ScopedSearch(app, q, installGoal = false, waitFor = waitFor)
                }
                "task" -> if (app.isEmpty()) null else PlanStep.SubTask(app, waitFor)
                else -> null
            }
            step
        }
        if (steps.isEmpty() || steps.size != arr.length()) return null

        val verify = obj.optJSONObject("verify")
        val goal = verify?.let { v ->
            val kind = v.optString("kind").trim().lowercase()
            val target = v.optString("target").trim()
            when (kind) {
                PlanGoal.APP_INSTALLED, PlanGoal.APP_REMOVED, PlanGoal.APP_IN_FOREGROUND, PlanGoal.SCREEN_SHOWS ->
                    if (target.isEmpty()) null else PlanGoal(kind, target)
                else -> null
            }
        }
        val goalText = obj.optString("goal").trim()
        val confirmed = obj.optBoolean("needs_confirmation", false)
        val needsConfirmation = confirmed || wantInstalls.isNotEmpty() ||
            goal?.kind == PlanGoal.APP_REMOVED ||
            steps.any { it is PlanStep.SubTask && it.instruction.lowercase().startsWith("uninstall") }

        val scoped = scopeSearches(steps)
        if (scoped.size == 1) {
            return CommandPlan.Single(scoped[0], goal, needsConfirmation, goalText)
        }
        return CommandPlan.Ordered(scoped, goal, needsConfirmation, goalText)
    }

    /**
     * Decompose [input] deterministically. [provider] may be null; then compound
     * commands are planned deterministically and ambiguous clauses become [PlanStep.SubTask].
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
            PlanResult(CommandPlan.Ordered(scopeSearches(base)), TokenUsage())
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
            appendLine("Preserve the user's ordering. Never merge steps. Do NOT prefix or wrap any target with linking words like \"and\", \"then\", \"also\", or \"next\" — each target is a clean standalone instruction. Reply with ONLY the JSON array, no markdown, no prose.")
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
                        PlanResult(CommandPlan.Ordered(scopeSearches(steps)), usage)
                    } else {
                        PlanResult(CommandPlan.Ordered(scopeSearches(base)), usage)
                    }
                }
                is ProviderLlmResponse.Error -> PlanResult(CommandPlan.Ordered(scopeSearches(base)), TokenUsage())
            }
        } catch (e: Exception) {
            PlanResult(CommandPlan.Ordered(scopeSearches(base)), TokenUsage())
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
                        val target = cleanTarget(obj.optString("target").trim())
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