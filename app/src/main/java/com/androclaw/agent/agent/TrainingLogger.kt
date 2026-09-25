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

    companion object {
        const val LOG_FILE_NAME = "laya_training.jsonl"

        /** Reserved tags for backend-driven decisions — excluded from training. */
        const val SOURCE_LAYA = "laya"
        const val SOURCE_DEV = "jev"

        const val QUESTION_TOOL_CHOICE = "tool_choice"
        const val QUESTION_APP_PICK = "app_pick"
    }
}