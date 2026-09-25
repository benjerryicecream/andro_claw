package com.androclaw.agent.agent

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Harness to parse user natural language requests into structured intents
 * (e.g. web navigation, search queries, app launches) for deterministic execution.
 */
object RequestHarness {

    sealed class ParsedIntent {
        data class WebNavigation(val url: String) : ParsedIntent()
        data class WebSearch(val query: String, val searchUrl: String) : ParsedIntent()
        data class GeneralTask(val rawGoal: String) : ParsedIntent()
    }

    private val domainRegex = Regex(
        "(?:https?://)?(?:[a-zA-Z0-9-]+\\.)+(?:com|org|net|io|gov|edu|co|app|dev|ai|xyz|info|us|uk|ca|de|fr|tech|me)(?:/[^\\s]*)?",
        RegexOption.IGNORE_CASE
    )

    private val openWebpageRegex = Regex(
        "(?:open|go\\s+to|visit|navigate\\s+to|show|load)\\s+(?:chrome\\s+and\\s+go\\s+to\\s+|chrome\\s+to\\s+|browser\\s+and\\s+go\\s+to\\s+|browser\\s+to\\s+)?([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(?:/[^\\s]*)?)",
        RegexOption.IGNORE_CASE
    )

    private val searchRegex = Regex(
        "(?:search|google|lookup|find)\\s+(?:for\\s+|on\\s+google\\s+for\\s+|google\\s+for\\s+)?(.+)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Parses the user's raw goal into a structured intent.
     */
    fun parseGoal(goal: String): ParsedIntent {
        val trimmed = goal.trim()

        // 1. Check for explicit "open / go to webpage" pattern
        val webpageMatch = openWebpageRegex.find(trimmed)
        if (webpageMatch != null) {
            val matchedUrl = webpageMatch.groupValues[1]
            return ParsedIntent.WebNavigation(formatUrl(matchedUrl))
        }

        // 2. Check for standalone URL anywhere in prompt
        val domainMatch = domainRegex.find(trimmed)
        if (domainMatch != null) {
            val matchedUrl = domainMatch.value
            return ParsedIntent.WebNavigation(formatUrl(matchedUrl))
        }

        // 3. Check for search request
        if (trimmed.startsWith("search ", ignoreCase = true) ||
            trimmed.startsWith("google ", ignoreCase = true) ||
            trimmed.startsWith("look up ", ignoreCase = true)) {
            val searchMatch = searchRegex.find(trimmed)
            if (searchMatch != null) {
                val query = searchMatch.groupValues[1].trim()
                if (query.isNotBlank()) {
                    val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
                    val searchUrl = "https://www.google.com/search?q=$encoded"
                    return ParsedIntent.WebSearch(query = query, searchUrl = searchUrl)
                }
            }
        }

        return ParsedIntent.GeneralTask(trimmed)
    }

    internal fun formatUrl(raw: String): String {
        var url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        return url
    }
}
