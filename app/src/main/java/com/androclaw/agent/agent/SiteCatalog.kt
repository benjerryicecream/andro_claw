package com.androclaw.agent.agent

/**
 * App-name targets that are really websites: when the user asks to open one of
 * these, the browser is the right home — "open youtube" means youtube.com in
 * Chrome, not whatever installed app happens to share the name. The follow-up
 * search clause then runs scoped inside the opened site instead of tearing off
 * to a generic web search.
 */
object SiteCatalog {

    /** Canonical lowercase name -> site home page. */
    private val SITES = mapOf(
        "youtube" to "https://www.youtube.com",
        "facebook" to "https://www.facebook.com",
        "twitter" to "https://twitter.com",
        "x" to "https://x.com",
        "instagram" to "https://www.instagram.com",
        "tiktok" to "https://www.tiktok.com",
        "whatsapp" to "https://web.whatsapp.com",
        "gmail" to "https://mail.google.com",
        "maps" to "https://www.google.com/maps",
        "google maps" to "https://www.google.com/maps",
        "wikipedia" to "https://www.wikipedia.org",
        "reddit" to "https://www.reddit.com",
        "linkedin" to "https://www.linkedin.com",
        "pinterest" to "https://www.pinterest.com",
        "netflix" to "https://www.netflix.com",
        "spotify" to "https://open.spotify.com",
        "amazon" to "https://www.amazon.com",
        "ebay" to "https://www.ebay.com",
        "walmart" to "https://www.walmart.com",
        "target" to "https://www.target.com",
        "bing" to "https://www.bing.com",
        "duckduckgo" to "https://duckduckgo.com"
    )

    /** The site URL for an app-open target, or null when the target is an app. */
    fun urlFor(target: String): String? {
        val t = normalize(target)
        return SITES[t]
    }

    /** The site name for a target, or null when the target is not a site. */
    fun nameOf(target: String): String? {
        val t = normalize(target)
        return if (SITES.containsKey(t)) t else null
    }

    /**
     * Fractional on-screen region (of the full display) where the site's header
     * search control lives on mobile-web. Used only as a documented last resort:
     * if the a11y tree does not expose the search affordance (Chrome does not
     * build annotated web nodes for this device's service without touch
     * exploration), the region center is tapped and the RESULTING input is still
     * resolved+verified over the tree before anything is typed.
     */
    fun searchRegionFraction(target: String): android.graphics.RectF? {
        val t = normalize(target)
        return when (t) {
            "youtube" -> android.graphics.RectF(0.85f, 0.13f, 1.0f, 0.19f)
            // Mobile-web sites keep a search icon in the top-right header
            // under the browser toolbar; a sane default for the others.
            else -> android.graphics.RectF(0.85f, 0.13f, 1.0f, 0.19f)
        }
    }

    /** Whether the target names a site instead of an installed app. */
    fun isSiteTarget(target: String): Boolean = urlFor(target) != null

    private fun normalize(target: String): String =
        target.trim().lowercase().replace(Regex("\\s+"), " ").removeSuffix(" app").trim()
}