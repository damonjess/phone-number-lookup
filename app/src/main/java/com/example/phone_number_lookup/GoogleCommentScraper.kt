package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

object GoogleCommentScraper {

    private const val SERP_MAX_CHARS = 2_000_000

    private val GOOGLE_USER_AGENTS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0"
    )

    private const val GOOGLE_CONSENT_COOKIE =
        "CONSENT=YES+cb.20240116-07-p0.en-GB+FX+999; SOCS=CAISHAgBEhJnd3NfMjAyNDAxMTYtMF9SQzIaAmVuIAEaBgiA_LyuBg"

    private val TAG_REGEX = Regex("<[^>]*>")
    private val SCRIPT_STYLE_REGEX = Regex(
        """<(script|style|noscript|nav|header|footer)\b.*?</\1>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val WHITESPACE_REGEX = Regex("""\s+""")

    private val SNIPPET_DATE_PREFIX = Regex(
        """^(\d{1,2}\s+[A-Za-z]{3,9}\s+\d{4}|[A-Za-z]{3,9}\s+\d{1,2},\s*\d{4}|\d{1,2}\s+(?:hours?|days?|weeks?|months?|years?)\s+ago)\s*[—–\-·]\s*(.*)$"""
    )

    private val IGNORED_GOOGLE_HOSTS = listOf(
        "google.com", "google.co.uk", "google.ca", "google.com.au",
        "accounts.google", "support.google", "policies.google", "maps.google",
        "translate.google", "webcache.googleusercontent.com", "gstatic.com",
        "consent.google"
    )

    private val GENERIC_HANDLES = setOf(
        "info", "admin", "support", "contact", "sales", "help", "office",
        "mail", "user", "test", "hello", "team", "enquiries", "service",
        "gmail", "googlemail", "outlook", "hotmail", "yahoo", "icloud"
    )

    suspend fun scrapeForEmail(
        email: String,
        knownDisplayNames: List<String> = emptyList(),
        knownHandles: List<String> = emptyList(),
        knownLocations: List<String> = emptyList()
    ): GoogleCommentReport = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        val rawHandle = cleanEmail.substringBefore('@').trim()
        if (cleanEmail.isBlank() || !cleanEmail.contains('@')) {
            return@withContext GoogleCommentReport(email = cleanEmail, handle = rawHandle)
        }

        val emailMd5 = OsintDatasets.transformAccount(cleanEmail, AccountMode.HASH_MD5)

        val allHandles = buildList {
            if (rawHandle.length >= 4 && rawHandle !in GENERIC_HANDLES) {
                add(rawHandle)
                val alnum = rawHandle.replace(Regex("[^a-zA-Z0-9_-]"), "")
                if (alnum.length >= 4) add(alnum)
                val noTrailingDigits = rawHandle.replace(Regex("\\d+$"), "").trim('.', '_', '-')
                if (noTrailingDigits.length >= 5) add(noTrailingDigits)
            }
            knownHandles.forEach { h ->
                val cleanH = h.trim().removePrefix("@")
                if (cleanH.length >= 4 && cleanH.lowercase() !in GENERIC_HANDLES && !cleanH.contains('.')) {
                    add(cleanH)
                }
            }
        }.distinctBy { it.lowercase() }.take(5)

        val effectiveDisplayNames = buildList {
            addAll(knownDisplayNames.filter { ' ' in it && it.length >= 5 })
            allHandles.forEach { h ->
                val tokens = EmailAttributionEngine.extractNameTokensFromIdentifier(h)
                if (tokens.size >= 2) {
                    add("${tokens[0]} ${tokens[1]}")
                }
            }
        }.distinctBy { it.lowercase() }

        val googleQueries = buildGoogleCommentQueries(
            email = cleanEmail,
            emailMd5 = emailMd5,
            handles = allHandles,
            knownDisplayNames = effectiveDisplayNames
        )
        val dorks = buildCommentDorkLinks(
            cleanEmail,
            allHandles.ifEmpty { listOf(rawHandle) },
            effectiveDisplayNames
        )

        // Concise queries that search engines won't reject or CAPTCHA-block
        val liveSearchQueries = buildList {
            add("\"$cleanEmail\"")
            allHandles.take(3).forEach { h ->
                add("\"$h\" (comment OR review OR reply OR forum)")
                add("\"$h\"")
            }
            effectiveDisplayNames.take(2).forEach { name ->
                add("site:google.com/maps/contrib \"$name\"")
                allHandles.firstOrNull()?.let { h -> add("\"$name\" \"$h\"") }
            }
        }.distinct().take(5)

        coroutineScope {
            // 0. Direct Google Maps Contributor Reviews & Comments
            val googleMapsDef = async {
                runCatching {
                    val meta = GoogleMetadataExtractor.extract(
                        email = cleanEmail,
                        hintNames = effectiveDisplayNames,
                        hintLocations = knownLocations
                    )
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

            // 1. Google Search HTML queries (spaced per-host via HostGate so we don't trigger burst 429s)
            val googleDeferred = liveSearchQueries.take(4).map { q ->
                async {
                    HostGate.run("google.co.uk", minGapMs = 400) {
                        q to scrapeGoogleSearchWithStatus(q)
                    }
                }
            }

            // 2. Multi-engine search scrapers (each engine gated independently so they run in parallel across hosts)
            val braveDeferred = liveSearchQueries.take(4).map { q ->
                async {
                    HostGate.run("search.brave.com", minGapMs = 350) {
                        q to runCatching { OpenWebOsintScraper.scrapeBraveHtml(q) }.getOrDefault(emptyList())
                    }
                }
            }
            val startpageDeferred = liveSearchQueries.take(3).map { q ->
                async {
                    HostGate.run("startpage.com", minGapMs = 350) {
                        q to scrapeStartpageGoogleHtml(q)
                    }
                }
            }
            val ddgDeferred = liveSearchQueries.take(4).map { q ->
                async {
                    HostGate.run("duckduckgo.com", minGapMs = 400) {
                        q to scrapeDuckDuckGoComments(q)
                    }
                }
            }
            val bingDeferred = liveSearchQueries.take(4).map { q ->
                async {
                    HostGate.run("bing.com", minGapMs = 350) {
                        q to OpenWebOsintScraper.scrapeBingHtml(q)
                    }
                }
            }
            val yahooDeferred = liveSearchQueries.take(4).map { q ->
                async {
                    HostGate.run("yahoo.com", minGapMs = 350) {
                        q to scrapeYahooSearchHtml(q)
                    }
                }
            }

            // 3. Direct Comment APIs across ALL discovered handles (NO commit messages!)
            val redditEmailDef = async { scrapeRedditCommentsByQuery("\"$cleanEmail\"", cleanEmail) }
            val redditHandlesDef = async {
                allHandles.map { h ->
                    async {
                        scrapeRedditUserComments(h) + scrapeRedditCommentsByQuery("\"$h\"", "@$h")
                    }
                }.awaitAll().flatten()
            }

            val hnEmailDef = async { scrapeHackerNewsComments("\"$cleanEmail\"", cleanEmail, byAuthor = false) }
            val hnHandlesDef = async {
                allHandles.map { h ->
                    async {
                        scrapeHackerNewsComments(h, "@$h", byAuthor = true) +
                            scrapeHackerNewsComments("\"$h\"", "@$h", byAuthor = false)
                    }
                }.awaitAll().flatten()
            }

            val ghEmailDef = async {
                scrapeGitHubCommentActivity("\"$cleanEmail\"", cleanEmail, isAuthorHandle = false)
            }
            val ghHandlesDef = async {
                allHandles.map { h ->
                    async {
                        scrapeGitHubCommentActivity(h, "@$h", isAuthorHandle = true) +
                            scrapeGitHubIssueCommentsOnly(h)
                    }
                }.awaitAll().flatten()
            }

            val stackOverflowDef = async {
                allHandles.map { h ->
                    async { scrapeStackOverflowUserActivity(h) }
                }.awaitAll().flatten()
            }

            val googleOutcomes = googleDeferred.awaitAll()
            val anyGoogleOk = googleOutcomes.any { it.second.status == GoogleScrapeStatus.OK }
            val anyGoogleBlocked = googleOutcomes.any { it.second.status == GoogleScrapeStatus.BLOCKED }

            val allHits = mutableListOf<CommentHit>()

            fun matchLabelForQuery(query: String): String {
                if (query.contains(cleanEmail, ignoreCase = true)) return cleanEmail
                if (query.contains(emailMd5, ignoreCase = true)) return "Gravatar ($cleanEmail)"
                for (h in allHandles) {
                    if (query.contains(h, ignoreCase = true)) return "@$h"
                }
                for (n in effectiveDisplayNames) {
                    if (query.contains(n, ignoreCase = true)) return n
                }
                return "@$rawHandle"
            }

            for ((query, outcome) in googleOutcomes) {
                val label = matchLabelForQuery(query)
                outcome.items.forEach { item ->
                    allHits += toCommentHit(item, label, "Google Search")
                }
            }

            for ((query, items) in braveDeferred.awaitAll()) {
                val label = matchLabelForQuery(query)
                items.forEach { item ->
                    allHits += toCommentHit(item, label, "Brave Search")
                }
            }

            for ((query, items) in startpageDeferred.awaitAll()) {
                val label = matchLabelForQuery(query)
                items.forEach { item ->
                    allHits += toCommentHit(item, label, "Google (via Startpage)")
                }
            }

            for ((query, items) in ddgDeferred.awaitAll()) {
                val label = matchLabelForQuery(query)
                items.forEach { item ->
                    allHits += toCommentHit(item, label, "DuckDuckGo Search")
                }
            }

            for ((query, items) in bingDeferred.awaitAll()) {
                val label = matchLabelForQuery(query)
                items.forEach { item ->
                    allHits += toCommentHit(item, label, "Bing Search")
                }
            }

            for ((query, items) in yahooDeferred.awaitAll()) {
                val label = matchLabelForQuery(query)
                items.forEach { item ->
                    allHits += toCommentHit(item, label, "Yahoo Search")
                }
            }

            val mapsHits = googleMapsDef.await()
            allHits += mapsHits
            allHits += redditEmailDef.await()
            allHits += redditHandlesDef.await()
            allHits += hnEmailDef.await()
            allHits += hnHandlesDef.await()
            allHits += ghEmailDef.await()
            allHits += ghHandlesDef.await()
            allHits += stackOverflowDef.await()

            val deduplicated = allHits
                .filter { it.url.startsWith("http") && it.title.isNotBlank() }
                .distinctBy { "${normalizeUrlForDedup(it.url)}|${it.title.lowercase()}" }

            val searchTerms = (listOf(cleanEmail, rawHandle) + allHandles + effectiveDisplayNames)
                .filter { it.length >= 3 }
                .distinct()
            val enriched = enrichCommentsWithPageCrawl(deduplicated, searchTerms)
                .sortedWith(
                    compareByDescending<CommentHit> {
                        it.sourceEngine == "Google Maps Reviews" &&
                            !it.commentSnippet.startsWith("Rated ") &&
                            !it.commentSnippet.startsWith("Uploaded ")
                    }
                        .thenByDescending { it.sourceEngine == "Google Maps Reviews" }
                        .thenByDescending { it.sourceEngine.startsWith("Google") }
                        .thenByDescending { it.platform != "GitHub" }
                        .thenByDescending { it.date != null }
                )

            val statusText = when {
                mapsHits.isNotEmpty() ->
                    "Google Maps Reviews & comment indexes queried across ${allHandles.size} handle(s) (${enriched.size} found, including ${mapsHits.size} Google Maps contribution(s))"
                anyGoogleOk && enriched.isNotEmpty() ->
                    "Google Search & comment indexes queried across ${allHandles.size} handle(s) (${enriched.size} found)"
                anyGoogleBlocked && enriched.isNotEmpty() ->
                    "Google rate-limited direct HTML; recovered ${enriched.size} comment(s) & mentions via Brave, Yahoo, Bing & platform APIs"
                anyGoogleBlocked ->
                    "Google challenged automated requests from this IP — tap the Google Comment Dorks below to view live in browser"
                enriched.isNotEmpty() ->
                    "Found ${enriched.size} public comment(s), forum posts, and web mentions"
                else ->
                    "No indexed public comments found for $cleanEmail or handles (${allHandles.joinToString()})"
            }

            GoogleCommentReport(
                email = cleanEmail,
                handle = rawHandle,
                comments = enriched,
                googleDirectStatus = statusText,
                queriesRun = googleQueries,
                dorkLinks = dorks
            )
        }
    }

    internal fun buildGoogleCommentQueries(
        email: String,
        emailMd5: String,
        handles: List<String>,
        knownDisplayNames: List<String> = emptyList()
    ): List<String> = buildList {
        add("\"$email\" (comment OR reply OR posted OR review OR forum OR discussion)")
        add("\"$email\"")

        for (h in handles.take(4)) {
            add("\"$h\" (comment OR reply OR review OR forum)")
            add("\"$h\"")
            add("site:google.com/maps/contrib \"$h\"")
        }

        knownDisplayNames.filter { ' ' in it && it.length >= 5 }.take(2).forEach { name ->
            add("site:google.com/maps/contrib \"$name\"")
            handles.firstOrNull()?.let { h ->
                add("\"$name\" \"$h\" (comment OR review OR forum)")
            }
        }

        for (h in handles.take(4)) {
            add("\"$h\" (site:reddit.com OR site:youtube.com OR site:disqus.com OR site:news.ycombinator.com OR site:stackoverflow.com OR site:github.com OR site:medium.com OR site:quora.com OR site:trustpilot.com)")
            add("\"$h\" (\"commented\" OR \"replied\" OR \"posted by\" OR \"comment by\" OR \"says:\" OR \"wrote:\")")
        }

        if (emailMd5.length == 32) {
            add("\"$emailMd5\" OR \"gravatar.com/avatar/$emailMd5\"")
        }
    }.distinct()

    internal fun buildGoogleCommentQueries(
        email: String,
        handle: String,
        includeHandle: Boolean,
        knownDisplayNames: List<String> = emptyList()
    ): List<String> = buildList {
        add("\"$email\" (comment OR reply OR posted OR review OR forum OR discussion)")
        add("\"$email\"")
        if (includeHandle && handle.isNotBlank()) {
            add("\"$handle\" (site:reddit.com OR site:youtube.com OR site:disqus.com OR site:news.ycombinator.com OR site:stackoverflow.com OR site:github.com OR site:medium.com OR site:quora.com OR site:trustpilot.com)")
            add("\"$handle\" (\"commented\" OR \"replied\" OR \"posted by\" OR \"comment by\" OR \"says:\" OR \"wrote:\")")
        }
        knownDisplayNames.firstOrNull { it.contains(' ') && it.length >= 5 }?.let { name ->
            add("\"$name\" \"$handle\" (comment OR review OR forum)")
        }
    }.distinct()

    fun buildCommentDorkLinks(
        email: String,
        handles: List<String>,
        knownDisplayNames: List<String> = emptyList()
    ): List<OsintDork> {
        val cleanHandles = handles.filter { it.isNotBlank() }.ifEmpty { listOf(email.substringBefore('@')) }
        val primaryHandle = cleanHandles.first()
        if (cleanHandles.size == 1 && knownDisplayNames.isEmpty()) {
            return buildCommentDorkLinks(email, primaryHandle)
        }

        val encEmail = ScanHttp.encode("\"$email\"")
        val handleOrGroup = cleanHandles.take(4).joinToString("+OR+") { ScanHttp.encode("\"$it\"") }
        val handleLabel = cleanHandles.take(3).joinToString(", ") { "@$it" }
        val primaryName = knownDisplayNames.firstOrNull { ' ' in it }

        return listOf(
            OsintDork(
                title = "Google: All Comments & Forum Replies ($email & $handleLabel)",
                description = "Searches Google for comments, replies, forum threads, and discussions mentioning your email or linked usernames.",
                url = "https://www.google.com/search?q=($encEmail+OR+$handleOrGroup)+(comment+OR+reply+OR+posted+OR+review+OR+forum+OR+discussion)",
                toolSource = "Google Comment Dork"
            ),
            OsintDork(
                title = "Google: Handle Comments ($handleLabel on Reddit, YouTube, Disqus, HN)",
                description = "Finds comments left under $handleLabel on Reddit, YouTube, Disqus, HackerNews, StackOverflow, Quora, and Medium.",
                url = "https://www.google.com/search?q=($handleOrGroup)+(site:reddit.com+OR+site:youtube.com+OR+site:disqus.com+OR+site:news.ycombinator.com+OR+site:stackoverflow.com+OR+site:medium.com+OR+site:quora.com)",
                toolSource = "Google Comment Dork"
            ),
            OsintDork(
                title = "Google: Blog & WordPress Comments (\"says\" / \"commented\")",
                description = "Locates blog comment sections and forum threads where $handleLabel or $email posted a comment.",
                url = "https://www.google.com/search?q=($encEmail+OR+$handleOrGroup)+(\"commented\"+OR+\"replied\"+OR+\"says:\"+OR+\"posted+by\"+OR+inurl:comment)",
                toolSource = "Google Comment Dork"
            ),
            OsintDork(
                title = "Google: Public Reviews (Google Maps, Trustpilot, TripAdvisor)",
                description = "Searches Google for public business and product reviews left by $handleLabel${primaryName?.let { " or $it" }.orEmpty()}.",
                url = "https://www.google.com/search?q=($encEmail+OR+$handleOrGroup${primaryName?.let { "+OR+${ScanHttp.encode("\"$it\"")}" }.orEmpty()})+(site:trustpilot.com+OR+site:tripadvisor.com+OR+site:google.com/maps/contrib+OR+\"wrote+a+review\")",
                toolSource = "Google Comment Dork"
            )
        )
    }

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

    private suspend fun enrichCommentsWithPageCrawl(
        hits: List<CommentHit>,
        searchTerms: List<String>
    ): List<CommentHit> = coroutineScope {
        val semaphore = Semaphore(3)
        var crawlCount = 0
        hits.map { hit ->
            val shouldCrawl = hit.sourceEngine.contains("Search") &&
                (hit.commentSnippet.startsWith("Indexed ") || hit.commentSnippet.length < 90) &&
                crawlCount < 6
            if (shouldCrawl) {
                crawlCount++
                async {
                    semaphore.withPermit {
                        val extracted = crawlPageForCommentWindow(hit.url, searchTerms)
                        if (!extracted.isNullOrBlank()) {
                            hit.copy(commentSnippet = extracted)
                        } else {
                            hit
                        }
                    }
                }
            } else {
                async { hit }
            }
        }.awaitAll()
    }

    private fun crawlPageForCommentWindow(url: String, searchTerms: List<String>): String? {
        val resp = ScanHttp.get(url, timeoutMs = 7_000, maxBodyChars = 500_000)
        if (!resp.isOk || resp.body.isBlank()) return null
        val text = cleanText(resp.body.replace(SCRIPT_STYLE_REGEX, " "))
        for (term in searchTerms) {
            val idx = text.indexOf(term, ignoreCase = true)
            if (idx >= 0) {
                val start = (idx - 80).coerceAtLeast(0)
                val end = (idx + term.length + 200).coerceAtMost(text.length)
                return "…${text.substring(start, end).trim()}…"
            }
        }
        return null
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

    fun scrapeGoogleSearch(query: String): List<SearchResultItem> =
        scrapeGoogleSearchWithStatus(query).items

    internal fun scrapeGoogleSearchWithStatus(query: String): GoogleScrapeOutcome {
        val encoded = ScanHttp.encode(query)
        // Do NOT pass gbv=1 first: Google redirects gbv=1 to /httpservice/retry/enablejs
        val urls = listOf(
            "https://www.google.co.uk/search?q=$encoded&hl=en&gl=uk&num=15",
            "https://www.google.com/search?q=$encoded&hl=en&num=15"
        )

        var sawBlock = false
        for ((idx, url) in urls.withIndex()) {
            val ua = GOOGLE_USER_AGENTS[idx % GOOGLE_USER_AGENTS.size]
            val resp = ScanHttp.get(
                url = url,
                timeoutMs = 10_000,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8",
                    "Cookie" to GOOGLE_CONSENT_COOKIE
                ),
                maxBodyChars = SERP_MAX_CHARS
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

    internal fun scrapeYahooSearchHtml(query: String): List<SearchResultItem> {
        val url = "https://uk.search.yahoo.com/search?p=${ScanHttp.encode(query)}&n=15"
        val resp = ScanHttp.get(
            url = url,
            timeoutMs = 10_000,
            headers = mapOf("Accept" to "text/html,application/xhtml+xml"),
            maxBodyChars = SERP_MAX_CHARS
        )
        if (!resp.isOk || resp.body.isBlank()) return emptyList()
        val cleaned = resp.body.replace(SCRIPT_STYLE_REGEX, " ")
        val results = mutableListOf<SearchResultItem>()

        val linkRegex = Regex(
            """<a\b[^>]*href\s*=\s*["'](https?://[^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        for (m in linkRegex.findAll(cleaned)) {
            val fullTag = m.value
            if (!fullTag.contains("ac-algo", ignoreCase = true) &&
                !fullTag.contains("d-ib", ignoreCase = true) &&
                !fullTag.contains("fz-ms", ignoreCase = true) &&
                !fullTag.contains("RU=", ignoreCase = false)
            ) continue

            var href = unescapeHtml(m.groupValues[1])
            if (href.contains("RU=")) {
                href = decodeUrlSafely(href.substringAfter("RU=").substringBefore("/RK=").substringBefore("/RS="))
            }
            if (href.contains("yahoo.com") || href.contains("bing.com")) continue
            val title = cleanText(m.groupValues[2])
            if (title.length >= 4 && href.startsWith("http") && !title.startsWith("http")) {
                val afterIdx = (m.range.last + 1).coerceAtMost(cleaned.length)
                val slice = cleaned.substring(afterIdx, (afterIdx + 1200).coerceAtMost(cleaned.length))
                val snippetMatch = Regex(
                    """<(?:p|span|div)\b[^>]*class\s*=\s*["'][^"']*(?:lh-16|fc-falcon|compText|s-desc)[^"']*["'][^>]*>(.*?)</(?:p|span|div)>""",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                ).find(slice)?.groupValues?.get(1)?.let { cleanText(it) }.orEmpty()

                results += SearchResultItem(
                    title = title,
                    snippet = snippetMatch.ifBlank { "Indexed web mention via Yahoo Search" },
                    url = href,
                    source = "Yahoo Search"
                )
            }
            if (results.size >= 15) break
        }
        return results.distinctBy { normalizeUrlForDedup(it.url) }
    }

    internal fun scrapeStackOverflowUserActivity(handle: String): List<CommentHit> {
        val clean = handle.trim().removePrefix("@")
        if (clean.length < 4) return emptyList()
        val userUrl = "https://api.stackexchange.com/2.3/users?inname=${ScanHttp.encode(clean)}&site=stackoverflow&pagesize=3"
        val userResp = ScanHttp.get(userUrl, timeoutMs = 8_000, headers = mapOf("Accept" to "application/json"))
        if (!userResp.isOk || userResp.body.isBlank()) return emptyList()

        return runCatching {
            val items = JSONObject(userResp.body).optJSONArray("items") ?: return emptyList()
            val exactUser = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                .firstOrNull { it.optString("display_name").equals(clean, ignoreCase = true) } ?: return emptyList()
            val userId = exactUser.optLong("user_id", -1L)
            if (userId <= 0L) return emptyList()

            val commentsUrl = "https://api.stackexchange.com/2.3/users/$userId/comments?site=stackoverflow&pagesize=10&order=desc&sort=creation&filter=!9_bDE0E4s"
            val cResp = ScanHttp.get(commentsUrl, timeoutMs = 8_000, headers = mapOf("Accept" to "application/json"))
            if (!cResp.isOk || cResp.body.isBlank()) return emptyList()

            val cItems = JSONObject(cResp.body).optJSONArray("items") ?: return emptyList()
            (0 until cItems.length()).mapNotNull { i ->
                val c = cItems.optJSONObject(i) ?: return@mapNotNull null
                val commentId = c.optLong("comment_id", -1L)
                val postId = c.optLong("post_id", -1L)
                if (commentId <= 0L || postId <= 0L) return@mapNotNull null
                val body = cleanText(c.optString("body")).take(260)
                val created = c.optLong("creation_date", 0L)
                val dateStr = if (created > 0L) SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(created * 1000L)) else null
                val link = c.optString("link").ifBlank { "https://stackoverflow.com/posts/comments/$commentId" }

                CommentHit(
                    platform = "StackExchange",
                    title = "StackOverflow Comment by @$clean on Post #$postId",
                    commentSnippet = body.ifBlank { "Public comment by @$clean on StackOverflow" },
                    url = link,
                    authorMatch = "@$clean",
                    date = dateStr,
                    sourceEngine = "StackExchange API"
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Extracts ONLY actual issue/PR/commit comments from GitHub events (never raw PushEvent commit messages).
     */
    internal fun scrapeGitHubIssueCommentsOnly(handle: String): List<CommentHit> {
        val clean = handle.trim().removePrefix("@")
        if (clean.length < 3) return emptyList()
        val headers = mapOf("Accept" to "application/vnd.github+json")
        val hits = mutableListOf<CommentHit>()

        val eventsUrl = "https://api.github.com/users/${ScanHttp.encode(clean)}/events/public?per_page=25"
        val eventsResp = ScanHttp.get(eventsUrl, timeoutMs = 8_000, headers = headers)
        if (eventsResp.isOk && eventsResp.body.isNotBlank()) {
            runCatching {
                val arr = JSONArray(eventsResp.body)
                for (i in 0 until arr.length()) {
                    val ev = arr.optJSONObject(i) ?: continue
                    val type = ev.optString("type")
                    val repoName = ev.optJSONObject("repo")?.optString("name").orEmpty()
                    val createdAt = ev.optString("created_at").take(10).takeIf { it.length == 10 }
                    val payload = ev.optJSONObject("payload") ?: continue

                    if (type == "IssueCommentEvent" || type == "CommitCommentEvent" || type == "PullRequestReviewCommentEvent") {
                        val commentObj = payload.optJSONObject("comment")
                        val body = commentObj?.optString("body")?.replace(WHITESPACE_REGEX, " ")?.trim()?.take(260).orEmpty()
                        val htmlUrl = commentObj?.optString("html_url").orEmpty()
                        if (body.isNotBlank() && htmlUrl.startsWith("http")) {
                            hits += CommentHit(
                                platform = "GitHub",
                                title = "$repoName: Comment by @$clean",
                                commentSnippet = body,
                                url = htmlUrl,
                                authorMatch = "@$clean",
                                date = createdAt,
                                sourceEngine = "GitHub Events API"
                            )
                        }
                    }
                    if (hits.size >= 8) break
                }
            }
        }
        return hits
    }

    internal fun isGoogleBlockedHtml(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("sorry/index") ||
            lower.contains("g-recaptcha") ||
            lower.contains("unusual traffic from your computer") ||
            lower.contains("consent.google.com") ||
            lower.contains("before you continue to google") ||
            (lower.contains("httpservice/retry/enablejs") && !lower.contains("<h3") && !lower.contains("/url?q="))
    }

    internal fun isGoogleNoResultsHtml(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("did not match any documents") ||
            lower.contains("no results found for") ||
            lower.contains("it looks like there aren't many great matches")
    }

    internal fun parseGoogleSearchHtml(html: String): List<SearchResultItem> {
        if (html.isBlank()) return emptyList()
        val cleanedHtml = html.replace(SCRIPT_STYLE_REGEX, " ")
        val results = mutableListOf<SearchResultItem>()

        // Strategy 1: `/url?q=` or `/url?url=` links
        val redirectAnchorRegex = Regex(
            """<a\b[^>]*\bhref\s*=\s*["'](?:https?://www\.google\.[^/"']+/url\?|/url\?)(?:[^"']*?&)?(?:q|url)=(https?[^"'&]+)[^"']*["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        val matches = redirectAnchorRegex.findAll(cleanedHtml).toList()
        for (i in matches.indices) {
            val match = matches[i]
            val rawUrl = decodeUrlSafely(unescapeHtml(match.groupValues[1]))
            if (!isExternalResultUrl(rawUrl)) continue

            val innerHtml = match.groupValues[2]
            val h3Match = Regex("""<h3[^>]*>(.*?)</h3>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(innerHtml)?.groupValues?.get(1)
            val bneaweMatch = Regex("""<div[^>]*class\s*=\s*["'][^"']*vvjwJb[^"']*["'][^>]*>(.*?)</div>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(innerHtml)?.groupValues?.get(1)
            val rawTitle = cleanText(h3Match ?: bneaweMatch ?: innerHtml)
            if (rawTitle.length < 3 || rawTitle.startsWith("http://") || rawTitle.startsWith("https://")) continue

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

        // Strategy 2: Modern desktop/mobile `<a href="https://...">...<h3>Title</h3>...</a>` blocks
        if (results.isEmpty()) {
            val directH3Regex = Regex(
                """<a\b[^>]*\bhref\s*=\s*["'](https?://[^"']+)["'][^>]*>(?:(?!</a\b).)*?<(?:h3|div\b[^>]*class\s*=\s*["'][^"']*(?:vvjwJb|CVA68e|MBeuO)[^"']*["'])[^>]*>(.*?)</(?:h3|div)>.*?</a>""",
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
        val containerRegex = Regex(
            """<(?:div|span|td)\b[^>]*\bclass\s*=\s*["'][^"']*(?:s3v9rd|VwiC3b|aCOpRe|yXK7lf|ITZIwc|st\b)[^"']*["'][^>]*>(.*?)</(?:div|span|td)>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        for (m in containerRegex.findAll(slice)) {
            val candidate = cleanText(m.groupValues[1])
            if (candidate.length >= 25 && !candidate.equals(title, ignoreCase = true) && !candidate.contains(" › ")) {
                return candidate.take(320)
            }
        }

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

    internal fun scrapeStartpageGoogleHtml(query: String): List<SearchResultItem> {
        val url = "https://www.startpage.com/sp/search?query=${ScanHttp.encode(query)}"
        val resp = ScanHttp.get(
            url = url,
            timeoutMs = 10_000,
            headers = mapOf("Accept" to "text/html,application/xhtml+xml"),
            maxBodyChars = SERP_MAX_CHARS
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
        val resp = ScanHttp.get(url, timeoutMs = 10_000, maxBodyChars = SERP_MAX_CHARS)
        return when (val out = DdgParser.classify(resp.code, resp.body)) {
            is DdgOutcome.Results -> out.items
            else -> emptyList()
        }
    }

    internal fun scrapeRedditUserComments(handle: String): List<CommentHit> {
        val clean = handle.trim().removePrefix("@")
        if (clean.length < 3) return emptyList()
        val commentsUrl = "https://www.reddit.com/user/${ScanHttp.encode(clean)}/comments.json?limit=15"
        val overviewUrl = "https://www.reddit.com/user/${ScanHttp.encode(clean)}/overview.json?limit=15"
        val out = mutableListOf<CommentHit>()
        for (url in listOf(commentsUrl, overviewUrl)) {
            val resp = ScanHttp.get(url, timeoutMs = 8_000, headers = mapOf("Accept" to "application/json"))
            if (resp.isOk && resp.body.isNotBlank()) {
                out += parseRedditCommentsJson(resp.body, "@$clean")
            }
        }
        return out.distinctBy { it.url }
    }

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
                val kind = child.optString("kind")
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

    internal fun scrapeGitHubCommentActivity(
        query: String,
        authorMatch: String,
        isAuthorHandle: Boolean
    ): List<CommentHit> {
        // Use commenter:<handle> instead of involves:<handle> so only issues where the user actually commented are returned
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
                    commentSnippet = issueBody.ifBlank { "Public GitHub issue/PR comment in $repoName" },
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
