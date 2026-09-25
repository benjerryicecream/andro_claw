package com.androclaw.agent.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Strongly-typed action schema for the agent.
 * The LLM produces JSON that is deserialized into one of these.
 */
@Serializable
sealed class AgentAction {

    @Serializable
    @SerialName("click")
    data class Click(val node_id: Int) : AgentAction() {
        val nodeId: Int get() = node_id
    }

    @Serializable
    @SerialName("long_click")
    data class LongClick(val node_id: Int) : AgentAction() {
        val nodeId: Int get() = node_id
    }

    @Serializable
    @SerialName("set_text")
    data class SetText(val node_id: Int, val text: String) : AgentAction() {
        val nodeId: Int get() = node_id
    }

    @Serializable
    @SerialName("scroll")
    data class Scroll(
        val node_id: Int,
        val direction: String = "down" // up | down | left | right
    ) : AgentAction() {
        val nodeId: Int get() = node_id
    }

    @Serializable
    @SerialName("back")
    object Back : AgentAction()

    @Serializable
    @SerialName("home")
    object Home : AgentAction()

    @Serializable
    @SerialName("recents")
    object Recents : AgentAction()

    @Serializable
    @SerialName("open_app")
    data class OpenApp(val package_name: String) : AgentAction() {
        val packageName: String get() = package_name
    }

    @Serializable
    @SerialName("open_url")
    data class OpenUrl(val url: String) : AgentAction()

    @Serializable
    @SerialName("press_enter")
    object PressEnter : AgentAction()

    @Serializable
    @SerialName("wait")
    data class Wait(val millis: Long = 1000) : AgentAction()
}

/**
 * The full response from the LLM for one agent step.
 */
@Serializable
data class AgentStepResponse(
    val status: String, // "action" | "done" | "needs_user_input" | "failed"
    val narration: String = "",        // Short human-readable description of this step
    val action: AgentAction? = null,   // Present when status == "action"
    val reason: String = "",           // Present when status == "failed" or "needs_user_input"
    val question: String = ""          // Present when status == "needs_user_input"
)
