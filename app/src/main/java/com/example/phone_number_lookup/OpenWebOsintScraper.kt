package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ScrapedWebReport(
    val query: String,
    val results: List<SearchResultItem> = emptyList(),
    val extractedEmails: List<AttributedValue> = emptyList(),
    val extractedPhones: List<AttributedValue> = emptyList(),
    val extractedHandles: List<AttributedValue> = emptyList(),
    val businessMatches: List<AttributedValue> = emptyList()
)

object OpenWebOsintScraper {

    private val EMAIL_REGEX = Regex("""[a-zA-Z0-9._%+-]{2,64}@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,12}""")
    private val PHONE_REGEX = Regex("""(?:\+\d{1,3}[-.\s]?)?(?:\(?\d{2,5}\)?[-.\s]?)?\d{3,4}[-.\s]?\d{3,4}""")
    private val HANDLE_REGEX = Regex("""(?:@|https?://(?:www\.)?(?:x\.com|twitter\.com|instagram\.com|t\.me|github\.com|tiktok\.com/@|reddit\.com/user/))([a-zA-Z0-9_.-]{3,30})""", RegexOption.IGNORE_CASE)

    /**
     * Runs parallel public scrapes across DuckDuckGo, Brave Search, Reddit JSON,
     * HackerNews Algolia, and GitHub Issues/Commits, then mines the text for entities.
     */
    suspend fun scrapeAll(
        queries: List<String>,
        targetPhoneE164: String? = null
    ): ScrapedWebReport = withContext(Dispatchers.IO) {
        val cleanQueries = queries.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (cleanQueries.isEmpty()) return@withContext ScrapedWebReport("")

        val primary = cleanQueries.first()

        coroutineScope {
            val ddgDeferred = cleanQueries.take(4).map { q ->
                async { scrapeDuckDuckGo(q) }
            }
            val braveDeferred = cleanQueries.take(2).map { q ->
                async { scrapeBraveHtml(q) }
            }
            val redditDef = async { scrapeRedditSearch(primary) }
            val hnDef = async { scrapeHackerNewsAlgolia(primary) }
            val ghIssuesDef = async { scrapeGitHubIssuesAndCommits(primary) }
            val osmDef = async {
                if (targetPhoneE164 != null) scrapeOsmOverpassPhone(targetPhoneE164) else emptyList()
            }

            val allItems = (ddgDeferred.awaitAll().flatten() +
                braveDeferred.awaitAll().flatten() +
                redditDef.await() +
                hnDef.await() +
                ghIssuesDef.await())
                .distinctBy { it.url.lowercase().trimEnd('/') }

            val emails = mutableListOf<AttributedValue>()
            val phones = mutableListOf<AttributedValue>()
            val handles = mutableListOf<AttributedValue>()

            for (item in allItems) {
                val blob = "${item.title} ${item.snippet}"
                EMAIL_REGEX.findAll(blob).forEach { m ->
                    val email = m.value.lowercase().trimEnd('.')
                    if (!email.endsWith(".png") && !email.endsWith(".jpg") && !email.contains("example.")) {
                        emails += AttributedValue(email, item.source)
                    }
                }
                EmailAttributionEngine.extractPhoneNumbersFromText(blob, item.source).forEach {
                    phones += it
                }
                HANDLE_REGEX.findAll(blob).forEach { m ->
                    val h = m.groupValues[1].trim('.', '_', '-')
                    if (h.length >= 3) handles += AttributedValue("@$h", item.source)
                }
            }

            ScrapedWebReport(
                query = primary,
                results = allItems,
                extractedEmails = emails.distinctBy { it.value.lowercase() },
                extractedPhones = phones.distinctBy { it.value },
                extractedHandles = handles.distinctBy { it.value.lowercase() },
                businessMatches = osmDef.await()
            )
        }
    }

    private fun scrapeDuckDuckGo(query: String): List<SearchResultItem> {
        val url = "https://html.duckduckgo.com/html/?q=${ScanHttp.encode(query)}"
        val resp = ScanHttp.get(url, timeoutMs = 12_000)
        return when (val out = DdgParser.classify(resp.code, resp.body)) {
            is DdgOutcome.Results -> out.items
            else -> emptyList()
        }
    }

    /**
     * Fallback scraper for Brave Search HTML when DuckDuckGo rate-limits or blocks.
     */
    internal fun scrapeBraveHtml(query: String): List<SearchResultItem> {
        val url = "https://search.brave.com/search?q=${ScanHttp.encode(query)}&source=web"
        val resp = ScanHttp.get(
            url,
            timeoutMs = 12_000,
            headers = mapOf("Accept" to "text/html,application/xhtml+xml")
        )
        if (!resp.isOk || resp.body.isBlank()) return emptyList()

        val results = mutableListOf<SearchResultItem>()
        val blockRegex = Regex(
            """<a[^>]+href="(https?://[^"]+)"[^>]*class="[^"]*svelte-[^"]*"[^>]*>.*?<div[^>]*class="[^"]*title[^"]*"[^>]*>(.*?)</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val tagStrip = Regex("<[^>]*>")

        for (match in blockRegex.findAll(resp.body)) {
            val href = match.groupValues[1]
            if (href.contains("brave.com")) continue
            val title = match.groupValues[2].replace(tagStrip, "").trim()
            if (title.isNotBlank()) {
                results += SearchResultItem(
                    title = title,
                    snippet = "Indexed web result via Brave Search",
                    url = href,
                    source = "Brave Search"
                )
            }
            if (results.size >= 15) break
        }
        return results
    }

    /**
     * Scrapes Reddit's unauthenticated JSON search API for public posts/comments mentioning the target.
     */
    internal fun scrapeRedditSearch(query: String): List<SearchResultItem> {
        val url = "https://www.reddit.com/search.json?q=${ScanHttp.encode("\"$query\"")}&limit=15&sort=relevance"
        val resp = ScanHttp.get(url, timeoutMs = 10_000, headers = mapOf("Accept" to "application/json"))
        if (!resp.isOk || resp.body.isBlank()) return emptyList()

        return runCatching {
            val children = JSONObject(resp.body)
                .optJSONObject("data")
                ?.optJSONArray("children") ?: return emptyList()
            (0 until children.length()).mapNotNull { i ->
                val data = children.optJSONObject(i)?.optJSONObject("data") ?: return@mapNotNull null
                val title = data.optString("title").ifBlank { data.optString("link_title") }
                val subreddit = data.optString("subreddit_name_prefixed")
                val author = data.optString("author")
                val selftext = data.optString("selftext").take(220).replace(Regex("\\s+"), " ")
                val permalink = data.optString("permalink")
                if (permalink.isBlank()) return@mapNotNull null
                SearchResultItem(
                    title = "$subreddit • u/$author: $title",
                    snippet = selftext.ifBlank { "Public Reddit mention by u/$author in $subreddit" },
                    url = "https://www.reddit.com$permalink",
                    source = "Reddit Search"
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Scrapes HackerNews Algolia public API for discussions/comments mentioning the target.
     */
    internal fun scrapeHackerNewsAlgolia(query: String): List<SearchResultItem> {
        val url = "https://hn.algolia.com/api/v1/search?query=${ScanHttp.encode("\"$query\"")}&hitsPerPage=10"
        val resp = ScanHttp.get(url, timeoutMs = 10_000, headers = mapOf("Accept" to "application/json"))
        if (!resp.isOk || resp.body.isBlank()) return emptyList()

        return runCatching {
            val hits = JSONObject(resp.body).optJSONArray("hits") ?: return emptyList()
            (0 until hits.length()).mapNotNull { i ->
                val h = hits.optJSONObject(i) ?: return@mapNotNull null
                val objectId = h.optString("objectID")
                val author = h.optString("author")
                val title = h.optString("title").ifBlank { h.optString("story_title") }.ifBlank { "HN Comment by $author" }
                val comment = h.optString("comment_text")
                    .replace(Regex("<[^>]*>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .take(220)
                val targetUrl = h.optString("url").ifBlank { "https://news.ycombinator.com/item?id=$objectId" }
                SearchResultItem(
                    title = "HN ($author): $title",
                    snippet = comment.ifBlank { "Mentioned on HackerNews by $author" },
                    url = targetUrl,
                    source = "HackerNews"
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Scrapes GitHub public issues, PRs, and commits mentioning a phone number, username, or email.
     */
    internal fun scrapeGitHubIssuesAndCommits(query: String): List<SearchResultItem> {
        val headers = mapOf("Accept" to "application/vnd.github+json")
        val url = "https://api.github.com/search/issues?per_page=10&q=${ScanHttp.encode("\"$query\"")}"
        val resp = ScanHttp.get(url, timeoutMs = 10_000, headers = headers)
        if (!resp.isOk || resp.body.isBlank()) return emptyList()

        return runCatching {
            val items = JSONObject(resp.body).optJSONArray("items") ?: return emptyList()
            (0 until items.length()).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                val title = item.optString("title")
                val htmlUrl = item.optString("html_url")
                val user = item.optJSONObject("user")?.optString("login").orEmpty()
                val body = item.optString("body").replace(Regex("\\s+"), " ").take(220)
                SearchResultItem(
                    title = "GitHub Issue (@$user): $title",
                    snippet = body,
                    url = htmlUrl,
                    source = "GitHub Issues"
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Queries OpenStreetMap's free Overpass API to see if a phone number is registered to a public
     * business, shop, office, or venue.
     */
    internal fun scrapeOsmOverpassPhone(e164: String): List<AttributedValue> {
        val digits = e164.filter { it.isDigit() }
        if (digits.length < 8) return emptyList()
        val tail = digits.takeLast(9)
        val overpassQuery = """[out:json][timeout:8];nwr["phone"~"$tail"];out tags 10;"""
        val url = "https://overpass-api.de/api/interpreter?data=${ScanHttp.encode(overpassQuery)}"
        val resp = ScanHttp.get(url, timeoutMs = 10_000, headers = mapOf("Accept" to "application/json"))
        if (!resp.isOk || resp.body.isBlank()) return emptyList()

        return runCatching {
            val elements = JSONObject(resp.body).optJSONArray("elements") ?: return emptyList()
            (0 until elements.length()).mapNotNull { i ->
                val tags = elements.optJSONObject(i)?.optJSONObject("tags") ?: return@mapNotNull null
                val name = tags.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val city = tags.optString("addr:city").ifBlank { tags.optString("addr:town") }
                val postcode = tags.optString("addr:postcode")
                val website = tags.optString("website").ifBlank { tags.optString("contact:website") }
                val label = buildString {
                    append(name)
                    if (city.isNotBlank() || postcode.isNotBlank()) append(" ($city $postcode)".trim())
                    if (website.isNotBlank()) append(" • $website")
                }
                AttributedValue(label, "OpenStreetMap Directory")
            }
        }.getOrDefault(emptyList())
    }
}
