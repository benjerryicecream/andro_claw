package com.androclaw.agent.llm

/**
 * Common interface for all LLM backends.
 * Each call should be a complete, stateless request/response.
 */
interface LlmProvider {
    /**
     * Send a list of messages and get a text response.
     * @param messages List of (role, content) pairs. Role is "system", "user", or "assistant".
     * @param temperature Sampling temperature (0.0 – 1.0).
     * @param maxTokens Maximum tokens in the response.
     */
    suspend fun complete(
        messages: List<LlmMessage>,
        temperature: Float = 0.1f,
        maxTokens: Int = 1024
    ): LlmResponse

    /**
     * Structured-output (JSON) completion. Providers that support native JSON
     * mode (e.g. Gemini responseMimeType=application/json) override this; the
     * default returns the plain text response, so callers must be prepared to
     * parse JSON out of free text when a provider lacks a dedicated mode.
     */
    suspend fun completeJson(
        messages: List<LlmMessage>,
        temperature: Float = 0.0f,
        maxTokens: Int = 1024
    ): LlmResponse = complete(messages, temperature, maxTokens)

    /** Provider display name */
    val displayName: String

    /** Stable machine identifier for training-data provenance (e.g. "openai", "gemini"). */
    val name: String
}

data class LlmMessage(
    val role: String, // "system", "user", "assistant"
    val content: String
)

sealed class LlmResponse {
    data class Success(val text: String, val inputTokens: Int = 0, val outputTokens: Int = 0) : LlmResponse()
    data class Error(val message: String, val cause: Throwable? = null) : LlmResponse()
}
