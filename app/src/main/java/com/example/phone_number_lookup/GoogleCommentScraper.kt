package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CommentHit(
    val platform: String,
    val title: String,
    val commentSnippet: String,
    val url: String,
    val authorMatch: String,
    val date: String? = null,
    val sourceEngine: String = "Google Search"
)

data class GoogleCommentReport(
    val email: String,
    val handle: String,
    val comments: List<CommentHit> = emptyList(),
    val googleDirectStatus: String = "Completed",
    val queriesRun: List<String> = emptyList(),
    val dorkLinks: List<OsintDork> = emptyList()
)

/**
 * Dedicated Google & Open-Web Comment Scraper for Email OSINT.
 *
 * When a user searches an email address, this scraper:
 * 1. Queries Google Search HTML (using non-JS basic HTML parameters and resilient multi-layout
 *    parsing) for pages where the email or its handle has left comments, replies, forum posts,
 *    or reviews.
 * 2. Falls back to Startpage (Google Search syndication), DuckDuckGo, and Bing if Google serves
 *    a CAPTCHA / bot-check challenge to the device IP.
 * 3. Queries public comment APIs directly (Reddit user comment history & search, HackerNews
 *    Algolia comments, GitHub issue/PR comments) for both the email address and its handle.
 */
object GoogleCommentScraper {

    // Text-mode / basic-HTML user agents that prompt Google to return server-rendered HTML
    // (`gbv=1`) instead of the client-side JS redirect shell (`httpservice/retry/enablejs`).
    private val GOOGLE_BASIC_USER_AGENTS = listOf(
        "Mozilla/5.0 (Linux; U; Android 4.4.2; en-gb; Nexus 5 Build/KOT49H) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30",
        "Opera/9.80 (Android; Opera Mini/36.2.2254/119.132; U; en) Presto/2.12.423 Version/12.16",
        "Lynx/2.8.9rel.1 libwww-FM/2.14 SSL-MM/1.4.1 OpenSSL/1.1.1k"
    )

    private val TAG_REGEX = Regex("<[^>]*>")
    private val SCRIPT_STYLE_REGEX = Regex(
        """<(script|style|noscript)\b.*?</\1>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val WHITESPACE_REGEX = Regex("""\s+""")

    // Matches leading date in Google snippets, e.g. "14 Mar 2025 — " or "Oct 3, 2024 - "
    private val SNIPPET_DATE_PREFIX = Regex(
        """^(\d{1,2}\s+[A-Za-z]{3,9}\s+\d{4}|[A-Za-z]{3,9}\s+\d{1,2},\s*\d{4}|\d{1,2}\s+(?:hours?|days?|weeks?|months?|years?)\s+ago)\s*[—–\-·]\s*(.*)$"""
    )

    private val IGNORED_GOOGLE_HOSTS = listOf(
        "google.com", "google.co.uk", "google.ca", "google.com.au",
        "accounts.google", "support.google", "policies.google", "maps.google",
        "translate.google", "webcache.googleusercontent.com", "gstatic.com"
    )

    private val GENERIC_HANDLES = setOf(
        "info", "admin", "support", "contact", "sales", "help", "office",
        "mail", "user", "test", "hello", "team", "enquiries", "service"
    )

    suspend fun scrapeForEmail(
        email: String,
        knownDisplayNames: List<String> = emptyList()
    ): GoogleCommentReport = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        val rawHandle = cleanEmail.substringBefore('@').trim()
        val alnumHandle = rawHandle.replace(Regex("[^a-zA-Z0-9_-]"), "")
        if (cleanEmail.isBlank() || !cleanEmail.contains('@')) {
            return@withContext GoogleCommentReport(email = cleanEmail, handle = rawHandle)
        }

        val includeHandle = rawHandle.length >= 4 && rawHandle !in GENERIC_HANDLES
        val googleQueries = buildGoogleCommentQueries(cleanEmail, rawHandle, includeHandle, knownDisplayNames)
        val dorks = buildCommentDorkLinks(cleanEmail, rawHandle)

        coroutineScope {
            // 0. Direct Google Maps Contributor Reviews & Comments
            val googleMapsDef = async {
                runCatching {
                    val meta = GoogleMetadataExtractor.extract(cleanEmail, hintNames = knownDisplayNames)
                    meta.reviews.map { rev ->
                        val fallbackUrl = meta.profileUrl
                            ?: meta.gaiaId?.let { "https://www.google.com/maps/contrib/$it/reviews" }
                            ?: "https://www.google.com/maps/search/${ScanHttp.encode("${rev.title} ${rev.address}")}"
                        val urlWithAnchor = if (rev.reviewUrl.isNotBlank()) rev.reviewUrl else "$fallbackUrl#place=${ScanHttp.encode(rev.title)}"
                        val snippet = buildString {
                            if (rev.commentText.isNotBlank()) {
                                append(rev.commentText)
                            } else {
                                append("Rated ${rev.ratingLabel} on Google Maps at ${rev.title} (${rev.address})")
                            }
                            if (rev.ownerReply.isNotBlank()) {
                                append(" — Owner reply: \"${rev.ownerReply.take(140)}\"")
                            }
                        }
                        CommentHit(
                            platform = "Google Maps",
                            title = "${rev.title} (${rev.ratingLabel}) — ${rev.address}",
                            commentSnippet = snippet,
                            url = urlWithAnchor,
                            authorMatch = meta.fullName ?: cleanEmail,
                            date = rev.dateLabel.ifBlank { rev.timestampIso },
                            sourceEngine = "Google Maps Reviews"
                        )
                    }
                }.getOrDefault(emptyList())
            }

            // 1. Google Search HTML queries
            val googleDeferred = googleQueries.map { q ->
                async { q to scrapeGoogleSearchWithStatus(q) }
            }

            // 2. Startpage (Google index syndication) & DuckDuckGo comment queries
            val fallbackQueries = googleQueries.take(3)
            val startpageDeferred = fallbackQueries.map { q ->
                async { q to scrapeStartpageGoogleHtml(q) }
            }
            val ddgDeferred = fallbackQueries.map { q ->
                async { q to scrapeDuckDuckGoComments(q) }
            }
            val bingDeferred = fallbackQueries.take(2).map { q ->
                async { q to OpenWebOsintScraper.scrapeBingHtml(q) }
            }

            // 3. Direct Comment APIs (Reddit comments, HackerNews comments, GitHub issue comments)
            val redditEmailDef = async { scrapeRedditCommentsByQuery("\"$cleanEmail\"", cleanEmail) }
            val redditUserDef = async {
                if (includeHandle) {
                    val userComments = scrapeRedditUserComments(rawHandle)
                    val alnumComments = if (alnumHandle != rawHandle && alnumHandle.length >= 4) {
                        scrapeRedditUserComments(alnumHandle)
                    } else emptyList()
                    val searchComments = scrapeRedditCommentsByQuery("\"$rawHandle\"", rawHandle)
                    userComments + alnumComments + searchComments
                } else emptyList()
            }

            val hnEmailDef = async { scrapeHackerNewsComments("\"$cleanEmail\"", cleanEmail, byAuthor = false) }
            val hnUserDef = async {
                if (includeHandle) {
                    val byAuthor = scrapeHackerNewsComments(rawHandle, rawHandle, byAuthor = true)
                    val byMention = scrapeHackerNewsComments("\"$rawHandle\"", rawHandle, byAuthor = false)
                    byAuthor + byMention
                } else emptyList()
            }

            val ghEmailDef = async { scrapeGitHubCommentActivity("\"$cleanEmail\"", cleanEmail, isAuthorHandle = false) }
            val ghUserDef = async {
                if (includeHandle) {
                    scrapeGitHubCommentActivity(rawHandle, rawHandle, isAuthorHandle = true)
                } else emptyList()
            }

            val googleOutcomes = googleDeferred.awaitAll()
            val anyGoogleOk = googleOutcomes.any { it.second.status == GoogleScrapeStatus.OK }
            val anyGoogleBlocked = googleOutcomes.any { it.second.status == GoogleScrapeStatus.BLOCKED }

            val allHits = mutableListOf<CommentHit>()

            // Collect Google Search HTML hits
            for ((query, outcome) in googleOutcomes) {
                val matchLabel = if (query.contains(cleanEmail)) cleanEmail else "@$rawHandle"
                outcome.items.forEach { item ->
                    allHits += toCommentHit(item, matchLabel, "Google Search")
                }
            }

            // Collect Startpage (Google Syndicated) hits
            for ((query, items) in startpageDeferred.awaitAll()) {
                val matchLabel = if (query.contains(cleanEmail)) cleanEmail else "@$rawHandle"
                items.forEach { item ->
                    allHits += toCommentHit(item, matchLabel, "Google (via Startpage)")
                }
            }

            // Collect DuckDuckGo & Bing comment dork hits
            for ((query, items) in ddgDeferred.awaitAll()) {
                val matchLabel = if (query.contains(cleanEmail)) cleanEmail else "@$rawHandle"
                items.forEach { item ->
                    allHits += toCommentHit(item, matchLabel, "DuckDuckGo Search")
                }
            }
            for ((query, items) in bingDeferred.awaitAll()) {
                val matchLabel = if (query.contains(cleanEmail)) cleanEmail else "@$rawHandle"
                items.forEach { item ->
                    allHits += toCommentHit(item, matchLabel, "Bing Search")
                }
            }

            // Collect direct Google Maps contributor reviews & comments first
            val mapsHits = googleMapsDef.await()
            allHits += mapsHits

            // Collect direct platform comment hits
            allHits += redditEmailDef.await()
            allHits += redditUserDef.await()
            allHits += hnEmailDef.await()
            allHits += hnUserDef.await()
            allHits += ghEmailDef.await()
            allHits += ghUserDef.await()

            val deduplicated = allHits
                .filter { it.url.startsWith("http") && it.title.isNotBlank() }
                .distinctBy { "${normalizeUrlForDedup(it.url)}|${it.title.lowercase()}" }
                .sortedWith(
                    compareByDescending<CommentHit> { it.sourceEngine == "Google Maps Reviews" && !it.commentSnippet.startsWith("Rated ") && !it.commentSnippet.startsWith("Uploaded ") }
                        .thenByDescending { it.sourceEngine == "Google Maps Reviews" }
                        .thenByDescending { it.authorMatch == cleanEmail }
                        .thenByDescending { it.sourceEngine.startsWith("Google") }
                        .thenByDescending { it.date != null }
                )

            val statusText = when {
                mapsHits.isNotEmpty() ->
                    "Google Maps Reviews & public comment indexes queried (${deduplicated.size} found, including ${mapsHits.size} Google Maps contribution(s))"
                anyGoogleOk && deduplicated.isNotEmpty() ->
                    "Google Search & public comment indexes queried (${deduplicated.size} found)"
                anyGoogleBlocked && deduplicated.isNotEmpty() ->
                    "Google rate-limited direct HTML; recovered ${deduplicated.size} comment(s) via Google syndication & platform APIs"
                anyGoogleBlocked ->
                    "Google challenged automated requests from this IP — use the 1-tap Google Dork launchers below to view live in browser"
                deduplicated.isNotEmpty() ->
                    "Found ${deduplicated.size} public comment(s) and discussion mentions"
                else ->
                    "No indexed public comments found for $cleanEmail"
            }

            GoogleCommentReport(
                email = cleanEmail,
                handle = rawHandle,
                comments = deduplicated,
                googleDirectStatus = statusText,
                queriesRun = googleQueries,
                dorkLinks = dorks
            )
        }
    }

    internal fun buildGoogleCommentQueries(
        email: String,
        handle: String,
        includeHandle: Boolean,
        knownDisplayNames: List<String> = emptyList()
    ): List<String> = buildList {
        // 1. Direct email in comments, forums, discussions, reviews, or mailing lists
        add("\"$email\" (comment OR reply OR posted OR review OR forum OR discussion)")
        // 2. Exact email anywhere on the public web
        add("\"$email\"")
        if (includeHandle) {
            // 3. Handle across major comment platforms indexed by Google
            add("\"$handle\" (site:reddit.com OR site:youtube.com OR site:disqus.com OR site:news.ycombinator.com OR site:stackoverflow.com OR site:github.com OR site:medium.com OR site:quora.com OR site:trustpilot.com)")
            // 4. Handle with explicit comment/reply markers on blogs & forums
            add("\"$handle\" (\"commented\" OR \"replied\" OR \"posted by\" OR \"comment by\" OR \"says:\" OR \"wrote:\")")
        }
        knownDisplayNames.firstOrNull { it.contains(' ') && it.length >= 5 }?.let { name ->
            add("\"$name\" \"$handle\" (comment OR review OR forum)")
        }
    }.distinct()

    fun buildCommentDorkLinks(email: String, handle: String): List<OsintDork> {
        val encEmail = ScanHttp.encode("\"$email\"")
        val encHandle = ScanHttp.encode("\"$handle\"")
        return listOf(
            OsintDork(
                title = "Google: All Comments & Forum Replies ($email)",
                description = "Opens Google Search filtered for comments, replies, forum threads, and reviews containing your email.",
                url = "https://www.google.com/search?q=$encEmail+(comment+OR+reply+OR+posted+OR+review+OR+forum+OR+discussion)",
                toolSource = "Google Comment Dork"
            ),
            OsintDork(
                title = "Google: Handle Comments (@$handle on Reddit, YouTube, Disqus, HN)",
                description = "Searches Google for comments left under the email handle @$handle across Reddit, YouTube, Disqus, StackOverflow, Quora, and Medium.",
                url = "https://www.google.com/search?q=$encHandle+(site:reddit.com+OR+site:youtube.com+OR+site:disqus.com+OR+site:news.ycombinator.com+OR+site:stackoverflow.com+OR+site:medium.com+OR+site:quora.com)",
                toolSource = "Google Comment Dork"
            ),
            OsintDork(
                title = "Google: Blog & WordPress Comments (\"says\" / \"commented\")",
                description = "Finds WordPress, Blogger, and forum comment sections where @$handle or $email left a reply.",
                url = "https://www.google.com/search?q=($encEmail+OR+$encHandle)+(\"commented\"+OR+\"replied\"+OR+\"says:\"+OR+\"posted+by\"+OR+inurl:comment)",
                toolSource = "Google Comment Dork"
            ),
            OsintDork(
                title = "Google: Public Reviews (Google Maps, Trustpilot, TripAdvisor)",
                description = "Searches Google for public product or local business reviews associated with @$handle or $email.",
                url = "https://www.google.com/search?q=($encEmail+OR+$encHandle)+(site:trustpilot.com+OR+site:tripadvisor.com+OR+site:google.com/maps/contrib+OR+\"wrote+a+review\")",
                toolSource = "Google Comment Dork"
            )
        )
    }

    internal enum class GoogleScrapeStatus {
        OK,
        NO_RESULTS,
        BLOCKED,
        ERROR
    }

    internal data class GoogleScrapeOutcome(
        val status: GoogleScrapeStatus,
        val items: List<SearchResultItem>
    )

    /**
     * Scrapes Google Search HTML using basic-HTML endpoints and multi-layout parsing.
     */
    fun scrapeGoogleSearch(query: String): List<SearchResultItem> =
        scrapeGoogleSearchWithStatus(query).items

    internal fun scrapeGoogleSearchWithStatus(query: String): GoogleScrapeOutcome {
        val encoded = ScanHttp.encode(query)
        val urls = listOf(
            "https://www.google.com/search?q=$encoded&gbv=1&hl=en&num=20",
            "https://www.google.co.uk/search?q=$encoded&gbv=1&hl=en&num=20"
        )

        var sawBlock = false
        for ((idx, url) in urls.withIndex()) {
            val ua = GOOGLE_BASIC_USER_AGENTS[idx % GOOGLE_BASIC_USER_AGENTS.size]
            val resp = ScanHttp.get(
                url = url,
                timeoutMs = 10_000,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8"
                )
            )
            if (resp.isNetworkError) continue
            if (resp.code == 429 || resp.code == 403 || resp.code == 503 || isGoogleBlockedHtml(resp.body)) {
                sawBlock = true
                continue
            }
            if (!resp.isOk || resp.body.isBlank()) continue

            val parsed = parseGoogleSearchHtml(resp.body)
            if (parsed.isNotEmpty()) {
                return GoogleScrapeOutcome(GoogleScrapeStatus.OK, parsed)
            }
            if (isGoogleNoResultsHtml(resp.body)) {
                return GoogleScrapeOutcome(GoogleScrapeStatus.NO_RESULTS, emptyList())
            }
        }

        return if (sawBlock) {
            GoogleScrapeOutcome(GoogleScrapeStatus.BLOCKED, emptyList())
        } else {
            GoogleScrapeOutcome(GoogleScrapeStatus.ERROR, emptyList())
        }
    }

    internal fun isGoogleBlockedHtml(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("sorry/index") ||
            lower.contains("g-recaptcha") ||
            lower.contains("unusual traffic from your computer") ||
            lower.contains("httpservice/retry/enablejs") ||
            (lower.contains("enablejs") && !lower.contains("/url?q="))
    }

    internal fun isGoogleNoResultsHtml(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("did not match any documents") ||
            lower.contains("no results found for") ||
            lower.contains("it looks like there aren't many great matches")
    }

    /**
     * Multi-layout Google HTML SERP parser.
     * Supports:
     * 1. Basic HTML (`gbv=1`) `/url?q=https://...` result anchors with sibling/following snippet divs.
     * 2. Standard desktop/mobile `<a href="https://...">...<h3>Title</h3>...</a>` blocks.
     */
    internal fun parseGoogleSearchHtml(html: String): List<SearchResultItem> {
        if (html.isBlank()) return emptyList()
        val cleanedHtml = html.replace(SCRIPT_STYLE_REGEX, " ")
        val results = mutableListOf<SearchResultItem>()

        // Strategy 1: Basic HTML `/url?q=` links
        val redirectAnchorRegex = Regex(
            """<a\b[^>]*\bhref\s*=\s*["']/url\?(?:q|url)=(https?[^"'&]+)[^"']*["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        val matches = redirectAnchorRegex.findAll(cleanedHtml).toList()
        for (i in matches.indices) {
            val match = matches[i]
            val rawUrl = decodeUrlSafely(unescapeHtml(match.groupValues[1]))
            if (!isExternalResultUrl(rawUrl)) continue

            val innerHtml = match.groupValues[2]
            // Prefer text inside <h3> or <div class="BNeawe vvjwJb..."> if present; otherwise full anchor text
            val h3Match = Regex("""<h3[^>]*>(.*?)</h3>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(innerHtml)?.groupValues?.get(1)
            val rawTitle = cleanText(h3Match ?: innerHtml)
            if (rawTitle.length < 3 || rawTitle.startsWith("http://") || rawTitle.startsWith("https://")) continue

            // Extract snippet from the HTML slice between this </a> and the next result <a>
            val sliceStart = match.range.last + 1
            val sliceEnd = if (i + 1 < matches.size) {
                minOf(matches[i + 1].range.first, sliceStart + 2500)
            } else {
                minOf(cleanedHtml.length, sliceStart + 2500)
            }
            val afterSlice = if (sliceStart < sliceEnd) cleanedHtml.substring(sliceStart, sliceEnd) else ""
            val snippet = extractSnippetFromGoogleSlice(afterSlice, rawTitle)

            results += SearchResultItem(
                title = rawTitle,
                snippet = snippet.ifBlank { "Indexed public mention via Google Search" },
                url = rawUrl,
                source = "Google Search"
            )
            if (results.size >= 20) break
        }

        // Strategy 2: Direct href="https://..." anchors wrapping an <h3>
        if (results.isEmpty()) {
            val directH3Regex = Regex(
                """<a\b[^>]*\bhref\s*=\s*["'](https?://[^"']+)["'][^>]*>(?:(?!</a\b).)*?<h3[^>]*>(.*?)</h3>.*?</a>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
            val directMatches = directH3Regex.findAll(cleanedHtml).toList()
            for (i in directMatches.indices) {
                val match = directMatches[i]
                val rawUrl = decodeUrlSafely(unescapeHtml(match.groupValues[1]))
                if (!isExternalResultUrl(rawUrl)) continue

                val rawTitle = cleanText(match.groupValues[2])
                if (rawTitle.isBlank()) continue

                val sliceStart = match.range.last + 1
                val sliceEnd = if (i + 1 < directMatches.size) {
                    minOf(directMatches[i + 1].range.first, sliceStart + 2500)
                } else {
                    minOf(cleanedHtml.length, sliceStart + 2500)
                }
                val afterSlice = if (sliceStart < sliceEnd) cleanedHtml.substring(sliceStart, sliceEnd) else ""
                val snippet = extractSnippetFromGoogleSlice(afterSlice, rawTitle)

                results += SearchResultItem(
                    title = rawTitle,
                    snippet = snippet.ifBlank { "Indexed public mention via Google Search" },
                    url = rawUrl,
                    source = "Google Search"
                )
                if (results.size >= 20) break
            }
        }

        return results.distinctBy { normalizeUrlForDedup(it.url) }
    }

    private fun extractSnippetFromGoogleSlice(slice: String, title: String): String {
        if (slice.isBlank()) return ""

        // 1. Check known Google snippet containers (`BNeawe s3v9rd`, `VwiC3b`, `aCOpRe`, `st`, `j`)
        val containerRegex = Regex(
            """<(?:div|span|td)\b[^>]*\bclass\s*=\s*["'][^"']*(?:s3v9rd|VwiC3b|aCOpRe|yXK7lf|st\b)[^"']*["'][^>]*>(.*?)</(?:div|span|td)>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        for (m in containerRegex.findAll(slice)) {
            val candidate = cleanText(m.groupValues[1])
            if (candidate.length >= 25 && !candidate.equals(title, ignoreCase = true) && !candidate.contains(" › ")) {
                return candidate.take(320)
            }
        }

        // 2. Fallback: strip tags from the slice, drop breadcrumb lines ("site.com › ..."), and take readable prose
        val plain = cleanText(slice)
        val cleaned = plain
            .replace(Regex("""https?://\S+"""), " ")
            .replace(Regex("""[a-zA-Z0-9.-]+\.[a-z]{2,}\s*›[^\n.]*"""), " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        return if (cleaned.length >= 20 && !cleaned.equals(title, ignoreCase = true)) {
            cleaned.take(320)
        } else {
            ""
        }
    }

    /**
     * Scrapes Startpage HTML (which syndicates Google Search results) when direct Google HTML
     * is rate-limited or CAPTCHA-gated.
     */
    internal fun scrapeStartpageGoogleHtml(query: String): List<SearchResultItem> {
        val url = "https://www.startpage.com/sp/search?query=${ScanHttp.encode(query)}"
        val resp = ScanHttp.get(
            url = url,
            timeoutMs = 10_000,
            headers = mapOf("Accept" to "text/html,application/xhtml+xml")
        )
        if (!resp.isOk || resp.body.isBlank()) return emptyList()
        return parseStartpageHtml(resp.body)
    }

    internal fun parseStartpageHtml(html: String): List<SearchResultItem> {
        if (html.isBlank()) return emptyList()
        val cleaned = html.replace(SCRIPT_STYLE_REGEX, " ")
        val results = mutableListOf<SearchResultItem>()

        val blockRegex = Regex(
            """<a\b[^>]*class\s*=\s*["'][^"']*result-(?:title|link)[^"']*["'][^>]*href\s*=\s*["'](https?://[^"']+)["'][^>]*>(.*?)</a>(?:.*?<p\b[^>]*class\s*=\s*["'][^"']*(?:w-gl__description|result-description)[^"']*["'][^>]*>(.*?)</p>)?""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        for (m in blockRegex.findAll(cleaned)) {
            val href = unescapeHtml(m.groupValues[1])
            if (href.contains("startpage.com")) continue
            val title = cleanText(m.groupValues[2])
            val snippet = cleanText(m.groupValues.getOrNull(3).orEmpty())
            if (title.isNotBlank() && href.startsWith("http")) {
                results += SearchResultItem(
                    title = title,
                    snippet = snippet.ifBlank { "Indexed Google result via Startpage" },
                    url = href,
                    source = "Google (via Startpage)"
                )
            }
            if (results.size >= 15) break
        }
        return results
    }

    private fun scrapeDuckDuckGoComments(query: String): List<SearchResultItem> {
        val url = "https://html.duckduckgo.com/html/?q=${ScanHttp.encode(query)}"
        val resp = ScanHttp.get(url, timeoutMs = 10_000)
        return when (val out = DdgParser.classify(resp.code, resp.body)) {
            is DdgOutcome.Results -> out.items
            else -> emptyList()
        }
    }

    /**
     * Fetches public Reddit comments posted by `u/<handle>` via Reddit's `/user/<handle>/comments.json`.
     */
    internal fun scrapeRedditUserComments(handle: String): List<CommentHit> {
        val clean = handle.trim().removePrefix("@")
        if (clean.length < 3) return emptyList()
        val url = "https://www.reddit.com/user/${ScanHttp.encode(clean)}/comments.json?limit=15"
        val resp = ScanHttp.get(url, timeoutMs = 10_000, headers = mapOf("Accept" to "application/json"))
        if (!resp.isOk || resp.body.isBlank()) return emptyList()
        return parseRedditCommentsJson(resp.body, "@$clean")
    }

    /**
     * Searches Reddit for posts/discussions mentioning the query.
     */
    internal fun scrapeRedditCommentsByQuery(query: String, authorMatch: String): List<CommentHit> {
        val url = "https://www.reddit.com/search.json?q=${ScanHttp.encode(query)}&limit=12&sort=new"
        val resp = ScanHttp.get(url, timeoutMs = 10_000, headers = mapOf("Accept" to "application/json"))
        if (!resp.isOk || resp.body.isBlank()) return emptyList()
        return parseRedditCommentsJson(resp.body, authorMatch)
    }

    internal fun parseRedditCommentsJson(body: String, authorMatch: String): List<CommentHit> {
        return runCatching {
            val children = JSONObject(body)
                .optJSONObject("data")
                ?.optJSONArray("children") ?: return emptyList()
            (0 until children.length()).mapNotNull { i ->
                val child = children.optJSONObject(i) ?: return@mapNotNull null
                val kind = child.optString("kind") // "t1" = comment, "t3" = post
                val data = child.optJSONObject("data") ?: return@mapNotNull null
                val subreddit = data.optString("subreddit_name_prefixed").ifBlank { "r/${data.optString("subreddit")}" }
                val author = data.optString("author")
                val permalink = data.optString("permalink")
                if (permalink.isBlank()) return@mapNotNull null

                val createdUtc = data.optLong("created_utc", 0L)
                val dateStr = if (createdUtc > 0L) {
                    SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(createdUtc * 1000L))
                } else null

                val threadTitle = data.optString("link_title").ifBlank { data.optString("title") }
                    .ifBlank { "Comment in $subreddit" }
                val commentBody = data.optString("body").ifBlank { data.optString("selftext") }
                    .replace(WHITESPACE_REGEX, " ")
                    .trim()
                    .take(280)

                val kindLabel = if (kind == "t1" || data.has("body")) "Comment" else "Post"
                CommentHit(
                    platform = "Reddit",
                    title = "$subreddit • u/$author ($kindLabel): $threadTitle",
                    commentSnippet = commentBody.ifBlank { "Public Reddit $kindLabel by u/$author in $subreddit" },
                    url = "https://www.reddit.com$permalink",
                    authorMatch = authorMatch,
                    date = dateStr,
                    sourceEngine = "Reddit Comments API"
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Queries HackerNews Algolia API specifically for `tags=comment` (either by author or text query).
     */
    internal fun scrapeHackerNewsComments(
        query: String,
        authorMatch: String,
        byAuthor: Boolean
    ): List<CommentHit> {
        val url = if (byAuthor) {
            "https://hn.algolia.com/api/v1/search_by_date?tags=comment,author_${ScanHttp.encode(query)}&hitsPerPage=12"
        } else {
            "https://hn.algolia.com/api/v1/search?tags=comment&query=${ScanHttp.encode(query)}&hitsPerPage=12"
        }
        val resp = ScanHttp.get(url, timeoutMs = 10_000, headers = mapOf("Accept" to "application/json"))
        if (!resp.isOk || resp.body.isBlank()) return emptyList()
        return parseHackerNewsCommentsJson(resp.body, authorMatch)
    }

    internal fun parseHackerNewsCommentsJson(body: String, authorMatch: String): List<CommentHit> {
        return runCatching {
            val hits = JSONObject(body).optJSONArray("hits") ?: return emptyList()
            (0 until hits.length()).mapNotNull { i ->
                val h = hits.optJSONObject(i) ?: return@mapNotNull null
                val objectId = h.optString("objectID")
                if (objectId.isBlank()) return@mapNotNull null
                val author = h.optString("author")
                val storyTitle = h.optString("story_title").ifBlank { h.optString("title") }
                    .ifBlank { "HackerNews Thread #$objectId" }
                val rawComment = h.optString("comment_text").ifBlank { h.optString("story_text") }
                val comment = cleanText(rawComment).take(280)
                val createdAt = h.optString("created_at").take(10).takeIf { it.length == 10 }

                CommentHit(
                    platform = "HackerNews",
                    title = "HN Comment by $author on: $storyTitle",
                    commentSnippet = comment.ifBlank { "Comment by $author on HackerNews" },
                    url = "https://news.ycombinator.com/item?id=$objectId",
                    authorMatch = authorMatch,
                    date = createdAt,
                    sourceEngine = "HackerNews Algolia"
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Queries GitHub's search API for issues/PRs where the user commented (`commenter:<handle>`)
     * or where the email was mentioned.
     */
    internal fun scrapeGitHubCommentActivity(
        query: String,
        authorMatch: String,
        isAuthorHandle: Boolean
    ): List<CommentHit> {
        val searchQuery = if (isAuthorHandle) "commenter:$query" else query
        val url = "https://api.github.com/search/issues?per_page=10&sort=updated&q=${ScanHttp.encode(searchQuery)}"
        val resp = ScanHttp.get(
            url,
            timeoutMs = 10_000,
            headers = mapOf("Accept" to "application/vnd.github+json")
        )
        if (!resp.isOk || resp.body.isBlank()) return emptyList()
        return parseGitHubIssuesJson(resp.body, authorMatch, isAuthorHandle)
    }

    internal fun parseGitHubIssuesJson(
        body: String,
        authorMatch: String,
        isAuthorHandle: Boolean
    ): List<CommentHit> {
        return runCatching {
            val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
            (0 until items.length()).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                val title = item.optString("title")
                val htmlUrl = item.optString("html_url")
                if (title.isBlank() || htmlUrl.isBlank()) return@mapNotNull null
                val user = item.optJSONObject("user")?.optString("login").orEmpty()
                val updatedAt = item.optString("updated_at").take(10).takeIf { it.length == 10 }
                val issueBody = item.optString("body").replace(WHITESPACE_REGEX, " ").trim().take(260)
                val repoName = htmlUrl.removePrefix("https://github.com/").substringBefore("/issues/").substringBefore("/pull/")

                CommentHit(
                    platform = "GitHub",
                    title = if (isAuthorHandle) "$repoName: Commented on \"$title\"" else "$repoName (@$user): $title",
                    commentSnippet = issueBody.ifBlank { "Public GitHub discussion comment in $repoName" },
                    url = htmlUrl,
                    authorMatch = authorMatch,
                    date = updatedAt,
                    sourceEngine = "GitHub Search API"
                )
            }
        }.getOrDefault(emptyList())
    }

    internal fun toCommentHit(
        item: SearchResultItem,
        authorMatch: String,
        sourceEngine: String
    ): CommentHit {
        val platform = classifyPlatform(item.url, item.title)
        val dateMatch = SNIPPET_DATE_PREFIX.find(item.snippet.trim())
        val extractedDate = dateMatch?.groupValues?.get(1)
        val cleanedSnippet = dateMatch?.groupValues?.get(2)?.ifBlank { item.snippet } ?: item.snippet

        return CommentHit(
            platform = platform,
            title = item.title,
            commentSnippet = cleanedSnippet,
            url = item.url,
            authorMatch = authorMatch,
            date = extractedDate,
            sourceEngine = sourceEngine
        )
    }

    internal fun classifyPlatform(url: String, title: String): String {
        val u = url.lowercase()
        val t = title.lowercase()
        return when {
            "reddit.com" in u -> "Reddit"
            "youtube.com" in u || "youtu.be" in u -> "YouTube"
            "disqus.com" in u -> "Disqus"
            "news.ycombinator.com" in u -> "HackerNews"
            "stackoverflow.com" in u || "stackexchange.com" in u || "superuser.com" in u || "serverfault.com" in u -> "StackExchange"
            "github.com" in u || "gist.github.com" in u -> "GitHub"
            "medium.com" in u -> "Medium"
            "quora.com" in u -> "Quora"
            "trustpilot.com" in u -> "Trustpilot"
            "tripadvisor." in u -> "TripAdvisor"
            "google.com/maps" in u || "goo.gl/maps" in u -> "Google Maps"
            "x.com" in u || "twitter.com" in u -> "X / Twitter"
            "facebook.com" in u -> "Facebook"
            "instagram.com" in u -> "Instagram"
            "tiktok.com" in u -> "TikTok"
            "linkedin.com" in u -> "LinkedIn"
            "wordpress.com" in u || "blogspot.com" in u || "substack.com" in u -> "Blog Comment"
            "forum" in u || "community." in u || "discourse" in u || "threads" in u || "forum" in t -> "Forum"
            "review" in u || "review" in t -> "Review"
            else -> "Web Comment / Mention"
        }
    }

    internal fun isExternalResultUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val lower = url.lowercase()
        if (lower.contains("/maps/contrib/") || lower.contains("/maps/reviews/") || lower.contains("/maps/place/")) {
            return true
        }
        return IGNORED_GOOGLE_HOSTS.none { host ->
            lower.contains("://$host") || lower.contains(".$host/")
        }
    }

    private fun normalizeUrlForDedup(url: String): String =
        url.lowercase().trimEnd('/').removePrefix("https://").removePrefix("http://").removePrefix("www.")

    private fun decodeUrlSafely(raw: String): String {
        val trimmed = raw.substringBefore("&sa=").substringBefore("&ved=").substringBefore("&usg=")
        return try {
            URLDecoder.decode(trimmed, "UTF-8")
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun cleanText(htmlFragment: String): String =
        unescapeHtml(htmlFragment.replace(TAG_REGEX, " "))
            .replace(WHITESPACE_REGEX, " ")
            .trim()

    private fun unescapeHtml(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&#x2F;", "/")
        .replace("&nbsp;", " ")
}
