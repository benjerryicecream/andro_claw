package com.androclaw.agent.agent

import com.androclaw.agent.llm.LlmMessage
import com.androclaw.agent.llm.LlmProvider
import com.androclaw.agent.llm.LlmResponse as ProviderLlmResponse

/** Thrown when the underlying LLM provider returns an error during a harness call. */
class LlmCallException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Thin adapter exposing an existing LlmProvider as the AgentHarness llm lambda.
 * Maps provider token usage (prompt/completion) into the harness [LlmResponse].
 */
class LlmHarnessAdapter(
    private val provider: LlmProvider,
    private val temperature: Float = 0.1f,
    private val maxTokens: Int = 512
) {
    /** Provider identifier for training-data provenance (e.g. "openai", "gemini"). */
    val source: String get() = provider.name

    suspend fun complete(messages: List<ChatMessage>): LlmResponse {
        val providerMessages = messages.map { LlmMessage(it.role, it.content) }
        return when (val resp = provider.complete(providerMessages, temperature, maxTokens)) {
            is ProviderLlmResponse.Success ->
                LlmResponse(
                    text = resp.text,
                    promptTokens = resp.inputTokens,
                    completionTokens = resp.outputTokens
                )
            is ProviderLlmResponse.Error -> throw LlmCallException(resp.message, resp.cause)
        }
    }
}