package com.androclaw.agent.agent

import com.androclaw.agent.data.StepRecord

/**
 * The observable state of the agent loop, emitted as a Flow.
 */
sealed class AgentState {
    object Idle : AgentState()
    data class Planning(val goal: String) : AgentState()
    data class Executing(
        val goal: String,
        val steps: List<StepRecord>,
        val currentStep: Int,
        val maxSteps: Int
    ) : AgentState()
    data class WaitingForConfirmation(
        val goal: String,
        val steps: List<StepRecord>,
        val question: String
    ) : AgentState()
    data class Completed(
        val goal: String,
        val steps: List<StepRecord>,
        val summary: String
    ) : AgentState()
    data class Failed(
        val goal: String,
        val steps: List<StepRecord>,
        val reason: String
    ) : AgentState()
    data class Stopped(
        val goal: String,
        val steps: List<StepRecord>
    ) : AgentState()
}
