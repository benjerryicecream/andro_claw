package com.androclaw.agent.agent

import org.json.JSONObject
import java.io.File

/**
 * Appends one JSONL line per LLM-driven decision to <filesDir>/laya_training.jsonl.
 * The [source] field records which configured LLM provider drove the decision
 * ("openai", "gemini", "anthropic", or "ollama"); those lines are the fine-tuning
 * dataset for the future local Laya decision model. Backend-driven decisions
 * (reserved tags "laya"/"jev") must never be logged here — training on them would
 * create feedback loops.
 *
 * Follows the same synchronized-append pattern as [TokenTracker]; safe from any thread.
 */
class TrainingLogger(private val dir: File) {
    private val logFile get() = File(dir, LOG_FILE_NAME)
    private val stateCap = 1500 // Laya's 512-token window

    @Synchronized
    fun logDecision(
        taskId: String,
        step: Int,
        questionId: String,
        state: String,
        options: Map<String, String>,
        label: String,
        source: String
    ) {
        val entry = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("taskId", taskId)
            .put("step", step)
            .put("questionId", questionId)
            .put("state", state.take(stateCap))
            .put("options", JSONObject(options))
            .put("label", label)
            .put("source", source)
        logFile.appendText(entry.toString() + "\n")
    }

    /**
     * Record one structured command parse (command -> plan JSON) with its token
     * usage and the planner path that produced it. These lines are the Phase 2
     * fine-tuning set for distilling the planner into the local Laya model.
     */
    @Synchronized
    fun logParse(
        taskId: String,
        command: String,
        planJson: String,
        verifierGoal: com.androclaw.agent.agent.PlanGoal?,
        needsConfirmation: Boolean,
        promptTokens: Int,
        completionTokens: Int,
        source: String,
        path: String
    ) {
        val entry = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("taskId", taskId)
            .put("kind", "command_parse")
            .put("command", command.take(300))
            .put("plan", planJson.take(2000))
            .put("path", path)
            .put("needsConfirmation", needsConfirmation)
            .put("promptTokenCount", promptTokens)
            .put("candidatesTokenCount", completionTokens)
            .put("source", source)
        planGoalJson(verifierGoal)?.let { entry.put("verifierGoal", it) }
        logFile.appendText(entry.toString() + "\n")
    }

    private fun planGoalJson(goal: com.androclaw.agent.agent.PlanGoal?): JSONObject? =
        goal?.let { JSONObject().put("kind", it.kind).put("target", it.target) }

    /**
     * Tag a whole task trajectory as a failure (thrash stop, max-steps cap,
     * quota exhaustion) so the future model doesn't learn to imitate the pacing
     * that led there. Kept alongside [logDecision] in the same training file.
     */
    @Synchronized
    fun logFailure(
        taskId: String,
        outcome: String,
        goal: String,
        tried: String,
        source: String
    ) {
        val entry = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("taskId", taskId)
            .put("kind", "trajectory_failure")
            .put("outcome", outcome)
            .put("goal", goal.take(200))
            .put("tried", tried.take(stateCap))
            .put("source", source)
        logFile.appendText(entry.toString() + "\n")
    }

    companion object {
        const val LOG_FILE_NAME = "laya_training.jsonl"

        /** Reserved tags for backend-driven decisions — excluded from training. */
        const val SOURCE_LAYA = "laya"
        const val SOURCE_DEV = "jev"

        const val QUESTION_TOOL_CHOICE = "tool_choice"
        const val QUESTION_APP_PICK = "app_pick"
    }
}