package com.androclaw.agent.llm

import kotlinx.coroutines.delay

/**
 * Shared handling for transient LLM quota / rate-limit failures (HTTP 429 and
 * provider "quota exceeded" / "rate limit" / "resource exhausted" bodies).
 *
 * Providers never retry on their own, so callers wrap completion with
 * [completeWithQuotaRetry] (back off briefly, then retry exactly once) and map
 * a persistent rate-limit failure to a plain-language message via [friendlyError]
 * instead of a raw HTTP body.
 */
object LlmResilience {

    /** Back-off before the single quota retry: 20s. */
    private const val QUOTA_RETRY_DELAY_MS = 20_000L

    /** Retry exactly once; repeated exhaustion means the tier is genuinely tapped out. */
    private const val MAX_QUOTA_RETRIES = 1

    private val rateLimitMarkers = listOf(
        "429", "rate limit", "rate_limit", "quota", "too many requests",
        "resource has been exhausted", "temporarily unavailable", "server busy",
        "overloaded", "insufficient_quota"
    )

    /** True when an error message smells like a rate limit / quota exhaustion. */
    fun isRateLimit(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val m = message.lowercase()
        return rateLimitMarkers.any { m.contains(it) }
    }

    /**
     * A user-facing task failure. Rate-limit errors become a plain explanation;
     * everything else stays a labeled LLM error so callers keep their cues.
     */
    fun friendlyError(providerName: String, message: String): String =
        if (isRateLimit(message)) {
            "${providerName}'s free tier is tapped out — try again in a bit."
        } else {
            "LLM error: $message"
        }

    /**
     * Complete one LLM call, retrying once after a short back-off when the
     * provider reports a transient rate-limit / quota error. Non-rate-limit
     * errors pass through unchanged, preserving each caller's existing path.
     */
    suspend fun completeWithQuotaRetry(
        provider: LlmProvider,
        messages: List<LlmMessage>,
        temperature: Float,
        maxTokens: Int
    ): LlmResponse {
        var attempts = 0
        while (true) {
            val response = provider.complete(messages, temperature, maxTokens)
            if (response !is LlmResponse.Error || !isRateLimit(response.message) ||
                attempts >= MAX_QUOTA_RETRIES
            ) {
                return response
            }
            attempts++
            delay(QUOTA_RETRY_DELAY_MS)
        }
    }
}