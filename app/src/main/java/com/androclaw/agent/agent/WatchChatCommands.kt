package com.androclaw.agent.agent

enum class WatchCommand { START, STOP }

/**
 * Phrase parser for the watch-mode commands typed into the chat input.
 * Command-shaped utterances are intercepted in submitTask and never reach the LLM.
 */
object WatchChatCommands {

    private val START_PHRASES = listOf(
        "watch the chat",
        "watch the muse chat",
        "watch muse",
        "watch chat",
        "start watching",
        "start auto-reply",
        "start auto reply",
        "start auto-replying",
        "begin auto-reply",
        "turn on auto-reply",
        "auto-reply on"
    )

    private val STOP_PHRASES = listOf(
        "stop watching",
        "stop watch",
        "stop auto-reply",
        "stop auto reply",
        "stop auto-replying",
        "end auto-reply",
        "disable auto-reply",
        "turn off auto-reply",
        "turn off watch"
    )

    fun parse(input: String): WatchCommand? {
        val t = input.trim().lowercase()
        if (STOP_PHRASES.any { t == it || t.startsWith("$it ") }) return WatchCommand.STOP
        if (START_PHRASES.any { t == it || t.startsWith("$it ") }) return WatchCommand.START
        return null
    }
}