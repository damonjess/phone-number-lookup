package com.example.phone_number_lookup

object SitePriority {

    private val TOP_SITES = listOf(
        "instagram", "facebook", "x", "xcom", "twitter", "tiktok", "youtube", "reddit", "github", "linkedin",
        "snapchat", "pinterest", "twitch", "steam", "steamcommunity", "spotify", "discord", "telegram",
        "soundcloud", "medium", "tumblr", "threads", "bluesky", "mastodon", "vk", "flickr", "vimeo",
        "patreon", "quora", "gitlab", "stackoverflow", "deviantart", "behance", "dribbble", "wordpress",
        "ebay", "etsy", "paypal", "cashapp", "venmo", "roblox", "xbox", "playstation", "epicgames",
        "chesscom", "lichess", "strava", "goodreads", "duolingo", "kaggle", "bandcamp", "lastfm", "imgur",
        "keybase", "hackernews", "devcommunity", "dockerhub", "minecraft", "scratch", "letterboxd", "linktree"
    )

    private val TOP_RANK: Map<String, Int> = TOP_SITES.withIndex().associate { (i, name) -> name to i }

    private val DEMOTED_MARKERS = listOf("archiv", "imginn", "wayback", "scraper", "mirror")

    private const val PREFIX_MIN_LENGTH = 6
    private const val CATEGORY_BASE = 1_000

    internal fun normalise(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() }

    internal fun topRank(name: String): Int? {
        val lower = name.lowercase()
        if (DEMOTED_MARKERS.any { it in lower }) return null
        val n = normalise(name)
        TOP_RANK[n]?.let { return it }
        return TOP_SITES.withIndex()
            .firstOrNull { (_, key) -> key.length >= PREFIX_MIN_LENGTH && n.startsWith(key) }
            ?.index
    }

    internal fun categoryWeight(category: String): Int {
        val c = category.lowercase()
        return when {
            "archiv" in c -> 30
            "nsfw" in c || c.startsWith("xx") || "adult" in c -> 20
            "social" in c -> 0
            "video" in c || "stream" in c -> 1
            "gaming" in c || "game" in c -> 2
            "coding" in c || "tech" in c || "dev" in c -> 3
            "music" in c -> 4
            "image" in c || "photo" in c || "art" in c -> 5
            "shop" in c || "financ" in c || "crypto" in c -> 6
            "blog" in c || "forum" in c || "hobby" in c || "business" in c || "job" in c -> 7
            else -> 8
        }
    }

    fun rank(site: SiteProbe): Int {
        val tr = topRank(site.name)
        if (tr != null) {
            return if (site.source == OsintDatasets.SRC_CURATED) tr - 100 else tr
        }
        return CATEGORY_BASE + categoryWeight(site.category)
    }

    fun prioritise(sites: List<SiteProbe>): List<SiteProbe> =
        sites.sortedWith(compareBy({ rank(it) }, { it.name.lowercase() }))
}
