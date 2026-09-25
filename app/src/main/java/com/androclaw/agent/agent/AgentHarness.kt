package com.androclaw.agent.agent

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
}

object CommandParser {
    private val openApp = Regex("""(?i)^\s*(open|launch|start)\s+(.+?)\s*$""")
    fun parseLocal(input: String): ToolCall? {
        openApp.matchEntire(input.trim())?.let {
            return ToolCall("open_app", mapOf("query" to it.groupValues[2].trim()))
        }
        return null
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
}

class AgentHarness(
    private val llm: suspend (List<ChatMessage>) -> LlmResponse,
    private val tools: List<AgentTool>,
    private val tracker: TokenTracker,
    private val maxSteps: Int = 12,
) {
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

    suspend fun runTask(userInput: String): String {
        val taskId = "task-${System.currentTimeMillis()}"

        CommandParser.parseLocal(userInput)?.let { call ->
            val result = tools.find { it.name == call.name }?.execute(call.args)
                ?: "error: unknown tool '${call.name}'"
            tracker.log(taskId, userInput, TokenUsage(), "fast-path:${call.name}")
            return result
        }

        val messages = mutableListOf(
            ChatMessage("system", systemPrompt()),
            ChatMessage("user", userInput),
        )
        var usage = TokenUsage()
        repeat(maxSteps) { step ->
            val resp = llm(messages)
            usage += TokenUsage(resp.promptTokens, resp.completionTokens)
            val call = parseToolCall(resp.text)
                ?: return resp.text.also { tracker.log(taskId, userInput, usage, "llm-done") }
            val result = tools.find { it.name == call.name }?.execute(call.args)
                ?: "error: unknown tool '${call.name}'"
            messages += ChatMessage("assistant", resp.text)
            messages += ChatMessage("tool", "Step ${step + 1} — tool '${call.name}' returned: $result")
        }
        tracker.log(taskId, userInput, usage, "llm-max-steps")
        return "Stopped after $maxSteps steps without finishing."
    }
}