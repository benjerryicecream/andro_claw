package com.androclaw.agent.agent

/**
 * A "System 1" decision backend (Laya local model, later optional TypeSafe Jev).
 *
 * Mirrors the TypeSafe Jev HTTP shape: a single batched call that takes the current
 * state plus a set of typed questions and returns typed answers. Returns null to
 * fall back to the LLM-driven path (fail closed). Implementations must never throw;
 * unreachable hosts, non-2xx responses, timeouts and malformed JSON all map to null.
 */
interface DecisionClient {
    suspend fun evaluate(
        state: String,
        questions: Map<String, DecisionQuestion>
    ): Map<String, DecisionAnswer>?
}

/** One typed question for the decision backend. */
data class DecisionQuestion(
    val type: String, // "choice", "noul", "score"
    val instructions: String,
    val options: Map<String, String> = emptyMap()
)

/** Typed answer for a [DecisionQuestion]. */
sealed interface DecisionAnswer {
    data class Choice(
        val choice: String,
        val confidence: Double,
        val probabilities: Map<String, Double>
    ) : DecisionAnswer

    data class Noul(val probability: Double) : DecisionAnswer

    data class Score(val score: Double, val confidence: Double) : DecisionAnswer
}