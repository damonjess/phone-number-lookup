package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

data class ScrapedWebReport(
    val query: String,
    val results: List<SearchResultItem> = emptyList(),
    val extractedEmails: List<AttributedValue> = emptyList(),
    val extractedPhones: List<AttributedValue> = emptyList(),
    val extractedHandles: List<AttributedValue> = emptyList(),
    val extractedLocations: List<AttributedValue> = emptyList(),
    val businessMatches: List<AttributedValue> = emptyList()
)

object OpenWebOsintScraper {

    private val EMAIL_REGEX = Regex("""[a-zA-Z0-9._%+-]{2,64}@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,12}""")
    private val PHONE_REGEX = Regex("""(?:\+\d{1,3}[-.\s]?)?(?:\(?\d{2,5}\)?[-.\s]?)?\d{3,4}[-.\s]?\d{3,4}""")
    // Require that '@' is NOT preceded by an email local-part character, and don't match dots in bare @handles
    private val HANDLE_REGEX = Regex(
        """(?:(?<![a-zA-Z0-9._%+-])@([a-zA-Z0-9_]{3,30})|https?://(?:www\.)?(?:x\.com|twitter\.com|instagram\.com|t\.me|github\.com|tiktok\.com/@|reddit\.com/user/)([a-zA-Z0-9_.-]{3,30}))""",
        RegexOption.IGNORE_CASE
    )

    private val IGNORED_HANDLES = setOf(
        "gmail", "googlemail", "yahoo", "hotmail", "outlook", "icloud",
        "protonmail", "proton", "aol", "msn", "live", "mail", "support",
        "share", "intent", "search", "explore", "home", "login", "signup"
    )

    private val BING_RESULT_OPEN = Regex(
        """<li\b[^>]*\bclass\s*=\s*["'][^"']*\bb_algo\b[^"']*["'][^>]*>""",
        RegexOption.IGNORE_CASE
    )
    private val BING_H2 = Regex(
        """<h2\b[^>]*>(.*?)</h2>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val BING_ANCHOR = Regex(
        """<a\b([^>]*)>(.*?)</a>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val BING_HREF = Regex(
        """\bhref\s*=\s*(?:"([^"]*)"|'([^']*)')""",
        RegexOption.IGNORE_CASE
    )
    private val BING_CLASS = Regex(
        """\bclass\s*=\s*(?:"([^"]*)"|'([^']*)')""",
        RegexOption.IGNORE_CASE
    )
    private val BING_CAPTION = Regex(
        """<div\b[^>]*\bclass\s*=\s*["'][^"']*\bb_caption\b[^"']*["'][^>]*>(.*?)</div>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val BING_PARAGRAPH = Regex(
        """<p\b[^>]*>(.*?)</p>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val BING_SCRIPT_STYLE = Regex(
        """<(script|style)\b.*?</\1>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val BING_TAG = Regex("<[^>]*>")
    private val BING_WHITESPACE = Regex("""\s+""")

    /**
     * Runs parallel public scrapes across Google Search, DuckDuckGo, Brave Search, Bing,
     * Reddit JSON, HackerNews Algolia, and GitHub Issues/Commits, then mines the text for entities.
     */
    suspend fun scrapeAll(
        queries: List<String>,
        targetPhoneE164: String? = null
    ): ScrapedWebReport = withContext(Dispatchers.IO) {
        val cleanQueries = queries.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (cleanQueries.isEmpty()) return@withContext ScrapedWebReport("")

        val primary = cleanQueries.first()

        coroutineScope {
            val googleDeferred = cleanQueries.take(5).map { q ->
                async { scrapeGoogleHtml(q) }
            }
            val ddgDeferred = cleanQueries.take(5).map { q ->
                async { scrapeDuckDuckGo(q) }
            }
            val braveDeferred = cleanQueries.take(3).map { q ->
                async { scrapeBraveHtml(q) }
            }
            val bingDeferred = cleanQueries.take(3).map { q ->
                async { scrapeBingHtml(q) }
            }
            val redditDef = async { scrapeRedditSearch(primary) }
            val hnDef = async { scrapeHackerNewsAlgolia(primary) }
            val ghIssuesDef = async { scrapeGitHubIssuesAndCommits(primary) }
            val osmDef = async {
                if (targetPhoneE164 != null) scrapeOsmOverpassPhone(targetPhoneE164) else emptyList()
            }

            val allItems = (googleDeferred.awaitAll().flatten() +
                ddgDeferred.awaitAll().flatten() +
                braveDeferred.awaitAll().flatten() +
                bingDeferred.awaitAll().flatten() +
                redditDef.await() +
                hnDef.await() +
                ghIssuesDef.await())
                .distinctBy { it.url.lowercase().trimEnd('/') }

            val emails = mutableListOf<AttributedValue>()
            val phones = mutableListOf<AttributedValue>()
            val handles = mutableListOf<AttributedValue>()
            val locations = mutableListOf<AttributedValue>()

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
                EmailAttributionEngine.extractLocationsFromText(blob, item.source).forEach {
                    locations += it
                }
                HANDLE_REGEX.findAll(blob).forEach { m ->
                    val rawHandle = (m.groupValues[1].ifEmpty { m.groupValues[2] })
                        .trim('.', '_', '-')
                    val lower = rawHandle.lowercase()
                    if (rawHandle.length >= 3 &&
                        !lower.contains('.') &&
                        lower !in IGNORED_HANDLES
                    ) {
                        handles += AttributedValue("@$rawHandle", item.source)
                    }
                }
            }

            ScrapedWebReport(
                query = primary,
                results = allItems,
                extractedEmails = emails.distinctBy { it.value.lowercase() },
                extractedPhones = phones.distinctBy { it.value },
                extractedHandles = handles.distinctBy { it.value.lowercase() },
                extractedLocations = locations.distinctBy { it.value.lowercase().trim() },
                businessMatches = osmDef.await()
            )
        }
    }

    /**
     * Scrapes Google Search via basic HTML view (gbv=1) for indexed public comments, mentions, and posts.
     */
    internal fun scrapeGoogleHtml(query: String): List<SearchResultItem> =
        GoogleCommentScraper.scrapeGoogleSearch(query)

    /**
     * Fallback public search scraper for Bing HTML.
     *
     * Bing no longer puts the result link inside `<h2>`: the heading is plain text and the URL
     * sits in a sibling anchor (class `tilk`, or the title anchor right after the heading).
     * The old single regex required `<h2><a href=...>` and therefore matched nothing at all,
     * so Bing silently contributed zero results. Each `b_algo` block is now parsed on its own.
     */
    internal fun scrapeBingHtml(query: String): List<SearchResultItem> {
        val url = "https://www.bing.com/search?q=${ScanHttp.encode(query)}&count=20&setlang=en-GB"
        val resp = ScanHttp.get(
            url,
            timeoutMs = 10_000,
            headers = mapOf("Accept" to "text/html,application/xhtml+xml"),
            maxBodyChars = 2_000_000
        )
        if (!resp.isOk || resp.body.isBlank()) return emptyList()
        return parseBingHtml(resp.body)
    }

    internal fun parseBingHtml(html: String): List<SearchResultItem> {
        if (html.isBlank()) return emptyList()
        val cleaned = html.replace(BING_SCRIPT_STYLE, " ")
        val results = mutableListOf<SearchResultItem>()

        // Split on the result wrapper, so markup changes inside one block cannot hide the rest.
        for (block in cleaned.split(BING_RESULT_OPEN).drop(1)) {
            if (results.size >= 15) break

            val anchors = BING_ANCHOR.findAll(block).mapNotNull { anchor ->
                val attrs = anchor.groupValues[1]
                val href = BING_HREF.find(attrs)
                    ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                    ?.let(::decodeBingRedirect)
                    .orEmpty()
                if (!href.startsWith("http") || href.contains("bing.com")) return@mapNotNull null
                val classes = BING_CLASS.find(attrs)
                    ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                    .orEmpty()
                BingAnchor(href = href, classes = classes, label = cleanBingText(anchor.groupValues[2]))
            }.toList()
            if (anchors.isEmpty()) continue

            val heading = BING_H2.find(block)
            val href = anchors.firstOrNull { "tilk" in it.classes }?.href
                ?: heading?.let { h2 -> anchors.firstOrNull { cleanBingText(h2.groupValues[1]).startsWith(it.label) } }?.href
                ?: anchors.first().href
            val title = heading?.let { cleanBingText(it.groupValues[1]) }.orEmpty()
                .ifBlank { anchors.firstOrNull { it.label.length >= 3 }?.label.orEmpty() }
            if (title.length < 3) continue

            val caption = BING_CAPTION.find(block)?.groupValues?.get(1).orEmpty()
            val snippet = BING_PARAGRAPH.find(caption)?.groupValues?.get(1)
                ?.let(::cleanBingText)
                ?.takeIf { it.isNotBlank() }
                ?: cleanBingText(caption)

            results += SearchResultItem(
                title = title,
                snippet = snippet.take(320).ifBlank { "Public indexed web result via Bing" },
                url = href,
                source = "Bing Search"
            )
        }

        return results.distinctBy { it.url.lowercase().trimEnd('/') }
    }

    private data class BingAnchor(val href: String, val classes: String, val label: String)

    /** Bing wraps some organic links as `bing.com/ck/a?...&u=a1<base64url target>`. */
    private fun decodeBingRedirect(raw: String): String {
        val href = raw.replace("&amp;", "&").trim()
        if (!href.contains("bing.com/ck/a")) return href
        val encoded = Regex("""[?&]u=a1([^&]+)""").find(href)?.groupValues?.get(1) ?: return href
        return runCatching {
            val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
            String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        }.getOrDefault(href).takeIf { it.startsWith("http") } ?: href
    }

    private fun cleanBingText(fragment: String): String =
        fragment.replace(BING_TAG, " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&bull;", "•")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace(BING_WHITESPACE, " ")
            .trim()

    private fun scrapeDuckDuckGo(query: String): List<SearchResultItem> {
        val url = "https://html.duckduckgo.com/html/?q=${ScanHttp.encode(query)}"
        val resp = ScanHttp.get(url, timeoutMs = 12_000, maxBodyChars = 2_000_000)
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
            headers = mapOf("Accept" to "text/html,application/xhtml+xml"),
            maxBodyChars = 2_000_000
        )
        if (!resp.isOk || resp.body.isBlank()) return emptyList()

        val tagStrip = Regex("<[^>]*>")
        val scriptStrip = Regex("""<(script|style)\b.*?</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val cleaned = resp.body.replace(scriptStrip, " ")
        val results = mutableListOf<SearchResultItem>()

        val anchorRegex = Regex(
            """<a\b[^>]*\bhref="(https?://[^"]+)"[^>]*>(?:(?!</a\b).)*?<div[^>]*class="[^"]*title[^"]*"[^>]*>(.*?)</div>.*?</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val matches = anchorRegex.findAll(cleaned).toList()

        for (i in matches.indices) {
            val match = matches[i]
            val href = match.groupValues[1]
            if (href.contains("brave.com")) continue
            val title = match.groupValues[2].replace(tagStrip, " ").replace(Regex("\\s+"), " ").trim()
            if (title.isBlank()) continue

            val sliceStart = match.range.last + 1
            val sliceEnd = if (i + 1 < matches.size) {
                minOf(matches[i + 1].range.first, sliceStart + 2000)
            } else {
                minOf(cleaned.length, sliceStart + 2000)
            }
            val afterSlice = if (sliceStart < sliceEnd) cleaned.substring(sliceStart, sliceEnd) else ""
            val descMatch = Regex(
                """<(?:p|div)\b[^>]*class="[^"]*(?:snippet-description|snippet-content)[^"]*"[^>]*>(.*?)</(?:p|div)>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            ).find(afterSlice)?.groupValues?.get(1)
            val snippet = descMatch?.replace(tagStrip, " ")?.replace(Regex("\\s+"), " ")?.trim()
                ?.takeIf { it.length >= 15 }
                ?: "Indexed web result via Brave Search"

            results += SearchResultItem(
                title = title,
                snippet = snippet.take(280),
                url = href,
                source = "Brave Search"
            )
            if (results.size >= 15) break
        }
        return results.distinctBy { it.url.lowercase().trimEnd('/') }
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
