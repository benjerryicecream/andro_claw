package com.androclaw.agent.agent

import android.util.Log
import org.json.JSONObject
import java.io.File

data class ChatMessage(val role: String, val content: String)
data class LlmResponse(val text: String, val promptTokens: Int, val completionTokens: Int)
data class ToolCall(val name: String, val args: Map<String, String>)

data class TokenUsage(val promptTokens: Int = 0, val completionTokens: Int = 0) {
    val total get() = promptTokens + completionTokens
    operator fun plus(o: TokenUsage) =
        TokenUsage(promptTokens + o.promptTokens, completionTokens + o.completionTokens)
    fun estCostUsd(promptPer1M: Double = 0.59, completionPer1M: Double = 0.79) =
        promptTokens / 1e6 * promptPer1M + completionTokens / 1e6 * completionPer1M
}

interface AgentTool {
    val name: String
    val description: String
    fun execute(args: Map<String, String>): String

    /** Package brought to the foreground by the most recent [execute], or null. */
    fun lastOpenedPackage(): String? = null
}

object CommandParser {
    private val openApp = Regex("""(?i)^\s*(open|launch|start)\s+(.+?)\s*$""")

    /**
     * Fast path only for a bare app name: the text after open/launch/start must be
     * 3 words or fewer and contain no "and"/"then". Anything compound falls through
     * to the LLM loop.
     */
    fun parseLocal(input: String): ToolCall? {
        val name = openApp.matchEntire(input.trim())?.groupValues?.get(2)?.trim() ?: return null
        val words = name.split(Regex("\\s+"))
        if (words.size > 3) return null
        if (words.any { it.equals("and", ignoreCase = true) || it.equals("then", ignoreCase = true) }) {
            return null
        }
        return ToolCall("open_app", mapOf("query" to name))
    }
}

class TokenTracker(private val dir: File) {
    private val logFile get() = File(dir, "token_usage.jsonl")
    @Volatile var sessionUsage = TokenUsage(); private set

    @Synchronized
    fun log(taskId: String, input: String, usage: TokenUsage, outcome: String) {
        sessionUsage += usage
        val entry = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("taskId", taskId)
            .put("input", input.take(120))
            .put("promptTokens", usage.promptTokens)
            .put("completionTokens", usage.completionTokens)
            .put("estCostUsd", usage.estCostUsd())
            .put("sessionTotal", sessionUsage.total)
            .put("outcome", outcome)
        logFile.appendText(entry.toString() + "\n")
    }

    /** Log the decomposition plan for a task plus the tokens it consumed. */
    @Synchronized
    fun logPlan(taskId: String, input: String, plan: String, usage: TokenUsage) {
        sessionUsage += usage
        val entry = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("kind", "plan")
            .put("taskId", taskId)
            .put("input", input.take(120))
            .put("plan", plan)
            .put("promptTokens", usage.promptTokens)
            .put("completionTokens", usage.completionTokens)
            .put("estCostUsd", usage.estCostUsd())
            .put("sessionTotal", sessionUsage.total)
        logFile.appendText(entry.toString() + "\n")
    }

    /** Log the tokens consumed by one plan/loop step. */
    @Synchronized
    fun logStepUsage(taskId: String, step: Int, input: String, usage: TokenUsage, outcome: String) {
        sessionUsage += usage
        val entry = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("kind", "step")
            .put("taskId", taskId)
            .put("step", step)
            .put("input", input.take(120))
            .put("outcome", outcome)
            .put("promptTokens", usage.promptTokens)
            .put("completionTokens", usage.completionTokens)
            .put("estCostUsd", usage.estCostUsd())
            .put("sessionTotal", sessionUsage.total)
        logFile.appendText(entry.toString() + "\n")
    }
}

class AgentHarness(
    private val llm: suspend (List<ChatMessage>) -> LlmResponse,
    private val tools: List<AgentTool>,
    private val tracker: TokenTracker,
    private val maxSteps: Int = 12,
    private val decisionClient: DecisionClient? = null,
    private val trainingLogger: TrainingLogger? = null,
    private val llmSource: String = "unknown"
) {

    /**
     * Package brought to the foreground by this task's most recent tool call.
     */
    private var currentOpenedPackage: String? = null

    /** Look up an executor tool by name (used by the multi-step planner). */
    fun toolFor(name: String): AgentTool? = tools.find { it.name == name }

    /**
     * Result of a harness task. [Completed] results provably finished the goal
     * (CommandParser fast path or confirmed by the decision backend); [Continue]
     * means the harness returned an indeterminate answer (LLM plain text) and the
     * goal should be re-fed into the perceive-act loop before concluding.
     */
    sealed interface Outcome {
        data class Completed(val summary: String) : Outcome
        data class Continue(val summary: String, val openedPackage: String? = null) : Outcome
        data class Failed(val summary: String) : Outcome
    }

    private fun systemPrompt() = buildString {
        appendLine("You control an Android phone. Reply with exactly one JSON tool call per turn,")
        appendLine("or plain text when the task is done. Format: {\"tool\": \"<name>\", \"args\": {\"k\": \"v\"}}")
        appendLine("Available tools:")
        tools.forEach { appendLine("- ${it.name}: ${it.description} (args: query)") }
    }

    private fun parseToolCall(text: String): ToolCall? = runCatching {
        val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val json = JSONObject(clean)
        val args = json.getJSONObject("args")
        ToolCall(json.getString("tool"), args.keys().asSequence().associateWith { args.getString(it) })
    }.getOrNull()

    suspend fun runTask(userInput: String): Outcome {
        val taskId = "task-${System.currentTimeMillis()}"
        currentOpenedPackage = null

        CommandParser.parseLocal(userInput)?.let { call ->
            val result = tools.find { it.name == call.name }?.execute(call.args)
                ?: "error: unknown tool '${call.name}'"
            tracker.log(taskId, userInput, TokenUsage(), "fast-path:${call.name}")
            return outcomeOf(result)
        }

        val messages = mutableListOf(
            ChatMessage("system", systemPrompt()),
            ChatMessage("user", userInput),
        )
        var usage = TokenUsage()
        repeat(maxSteps) { step ->
            val state = buildDecisionState(userInput, messages)

            // Decision backend seam: if a backend is enabled and answers for this
            // step, honor the confidence policy; otherwise this step is LLM-driven.
            var decidedByBackend = false
            if (decisionClient != null) {
                val answers = try {
                    decisionClient.evaluate(state, decisionQuestions())
                } catch (e: Exception) {
                    Log.w(TAG, "Decision backend failed unexpectedly on step ${step + 1}; falling back to LLM", e)
                    null
                }
                if (answers != null) {
                    val doneProbability =
                        (answers["task_done"] as? DecisionAnswer.Noul)?.probability ?: 0.0
                    if (doneProbability >= DECISION_TASK_DONE_THRESHOLD) {
                        tracker.log(taskId, userInput, usage, "decided-done")
                        return Outcome.Completed("Task complete.")
                    }
                    val toolChoice = answers["tool_choice"] as? DecisionAnswer.Choice
                    if (toolChoice != null) {
                        val canExecute = when {
                            toolChoice.confidence >= DECISION_EXECUTE_CONFIDENCE ->
                                toolChoice.choice != "none"
                            toolChoice.confidence >= DECISION_FALLBACK_CONFIDENCE ->
                                toolChoice.choice in LOW_RISK_TOOLS
                            else -> false
                        }
                        if (canExecute) {
                            decidedByBackend = executeToolStep(
                                taskId, step, toolChoice.choice, internalArgs(taskId, step), messages
                            )
                        }
                    }
                }
            }
            if (decidedByBackend) {
                tracker.log(taskId, userInput, usage, "decided-tool")
                return@repeat
            }

            // Groq turn for this step
            val resp = llm(messages)
            usage += TokenUsage(resp.promptTokens, resp.completionTokens)
            val call = parseToolCall(resp.text)
            trainingLogger?.logDecision(
                taskId = taskId,
                step = step,
                questionId = TrainingLogger.QUESTION_TOOL_CHOICE,
                state = state,
                options = toolOptions(),
                label = call?.name ?: "none",
                source = llmSource
            )
if (call == null) {
                tracker.log(taskId, userInput, usage, "llm-done")
                val harnessSteps = messages.filter { it.role == "tool" }
                    .takeLast(3)
                    .joinToString("\n") { it.content.substringAfter("returned: ", it.content) }
                return Outcome.Continue(
                    summary = harnessSteps.ifBlank { resp.text },
                    openedPackage = currentOpenedPackage
                )
            }
            val execArgs = call.args + internalArgs(taskId, step)
            executeToolStep(taskId, step, call.name, execArgs, messages)
        }
        tracker.log(taskId, userInput, usage, "llm-max-steps")
        return Outcome.Failed("Stopped after $maxSteps steps without finishing.")
    }

    private fun decisionQuestions(): Map<String, DecisionQuestion> = mapOf(
        "task_done" to DecisionQuestion(
            type = "noul",
            instructions = "Is the user's task complete given the goal and the steps taken so far?"
        ),
        "tool_choice" to DecisionQuestion(
            type = "choice",
            instructions = "Which tool should run next to make progress on the goal?",
            options = toolOptions()
        )
    )

    private fun toolOptions(): Map<String, String> =
        tools.associate { it.name to it.description } +
            ("none" to "No tool; respond with text or announce the task is done")

    private fun buildDecisionState(goal: String, messages: List<ChatMessage>): String = buildString {
        append("Goal: $goal")
        val toolMessages = messages.filter { it.role == "tool" }
        if (toolMessages.isNotEmpty()) {
            append("\n\nSteps so far:")
            toolMessages.takeLast(3).forEach { append("\n- ").append(it.content) }
        }
    }.take(DECISION_STATE_CAP)

    private fun internalArgs(taskId: String, step: Int): Map<String, String> = mapOf(
        INTERNAL_TASK_ID to taskId,
        INTERNAL_STEP to step.toString()
    )

    /** Execute a tool and record its result in [messages]; tracks any app it foregrounded. */
    private fun executeToolStep(
        taskId: String,
        step: Int,
        name: String,
        args: Map<String, String>,
        messages: MutableList<ChatMessage>
    ): Boolean {
        val tool = tools.find { it.name == name } ?: run {
            messages += ChatMessage("tool", "Step ${step + 1} — tool '$name' returned: error: unknown tool '$name'")
            return false
        }
        val result = tool.execute(args)
        val opened = tool.lastOpenedPackage()
        if (opened != null) currentOpenedPackage = opened
        messages += ChatMessage("tool", "Step ${step + 1} — tool '$name' returned: $result")
        return true
    }

    companion object {
        internal const val INTERNAL_TASK_ID = "@taskId"
        internal const val INTERNAL_STEP = "@step"
        private const val TAG = "AgentHarness"

        private fun outcomeOf(result: String): Outcome = when {
            result.startsWith("error:", ignoreCase = true) ||
                result.startsWith("stopped after", ignoreCase = true) ||
                result.startsWith("cancelled:", ignoreCase = true) -> Outcome.Failed(result)
            else -> Outcome.Completed(result)
        }

        private const val DECISION_STATE_CAP = 1500
        private const val DECISION_EXECUTE_CONFIDENCE = 0.85
        private const val DECISION_FALLBACK_CONFIDENCE = 0.60
        private const val DECISION_TASK_DONE_THRESHOLD = 0.85
        private val LOW_RISK_TOOLS = setOf("open_app")
    }
}