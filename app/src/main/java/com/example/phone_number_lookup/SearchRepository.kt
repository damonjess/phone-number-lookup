package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class SearchResultItem(
    val title: String,
    val snippet: String,
    val url: String,
    val source: String
)

data class SearchResponse(
    val results: List<SearchResultItem>,
    val error: String? = null
)



object SearchRepository {
    private data class Cached(val response: SearchResponse, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private const val CACHE_MS = 24L * 60L * 60L * 1000L

    private const val ENDPOINT = "https://html.duckduckgo.com/html/"
    private const val LITE_ENDPOINT = "https://lite.duckduckgo.com/lite/"

    const val BLOCKED_MESSAGE =
        "DuckDuckGo blocked this search with a bot check, so web results aren't available right now. " +
            "This does NOT mean nothing was found. Try again later."

    suspend fun searchWeb(numberFormats: List<String>): SearchResponse = withContext(Dispatchers.IO) {
        val cleanFormats = buildSearchSpellings(numberFormats)
        if (cleanFormats.isEmpty()) return@withContext SearchResponse(emptyList())

        val cacheKey = cleanFormats.joinToString("|")
        cache[cacheKey]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return@withContext it.response }

        val query = cleanFormats.joinToString(" OR ") { "\"$it\"" }
        when (val outcome = fetch(query)) {
            is DdgOutcome.Results -> remember(cacheKey, SearchResponse(outcome.items))
            DdgOutcome.NoResults -> remember(cacheKey, SearchResponse(emptyList()))
            is DdgOutcome.Blocked -> SearchResponse(emptyList(), BLOCKED_MESSAGE)
            is DdgOutcome.Unrecognised -> SearchResponse(
                emptyList(),
                "Web search returned a page the app couldn't read (${outcome.reason}). " +
                    "Nothing can be concluded from it." +
                    if (outcome.excerpt.isNotEmpty()) " Page began: ${outcome.excerpt}." else ""
            )
            DdgOutcome.Unreachable -> SearchResponse(emptyList(), "Web search couldn't connect or timed out.")
        }
    }

    /** Builds practical web-page spellings of a phone number, dropping `tel:` URI schemes. */
    internal fun buildSearchSpellings(rawFormats: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in rawFormats) {
            val trimmed = raw.trim()
            if (trimmed.isBlank() || trimmed.startsWith("tel:", ignoreCase = true)) continue
            out += trimmed
            // If a national format has spaces (e.g. "020 7946 0958"), also include the compact form ("02079460958")
            if (!trimmed.startsWith("+") && trimmed.contains(' ')) {
                val compact = trimmed.filter { it.isDigit() }
                if (compact.length >= 9) out += compact
            }
        }
        return out.take(4)
    }

    /** Public indexed-page searches; never probes account registration or private services. */
    suspend fun searchPhoneNumber(numberFormats: List<String>): SearchResponse = withContext(Dispatchers.IO) {
        val spellings = buildSearchSpellings(numberFormats)
        if (spellings.isEmpty()) return@withContext SearchResponse(emptyList())
        val queries = buildPhoneInvestigationQueries(spellings)
        val responses = queries.map { query -> async { searchQuery(query) } }.awaitAll()
        val merged = LinkedHashMap<String, SearchResultItem>()
        responses.flatMap { it.results }.forEach { result ->
            val key = result.url.trimEnd('/').lowercase()
            if (key.isNotBlank()) merged.putIfAbsent(key, result)
        }
        val errors = responses.mapNotNull { it.error }.distinct()
        SearchResponse(
            results = merged.values.take(DdgParser.MAX_RESULTS * queries.size),
            error = errors.takeIf { it.isNotEmpty() }?.joinToString(" ")
        )
    }

    internal fun buildPhoneInvestigationQueries(spellings: List<String>): List<String> {
        val exact = spellings.take(4).joinToString(" OR ") { "\"$it\"" }
        val preferred = spellings.firstOrNull().orEmpty()
        if (preferred.isBlank()) return emptyList()
        val reputation = "\"$preferred\" (spam OR scam OR nuisance OR telemarketing OR \"who called\")"
        val directories = "\"$preferred\" (site:tellows.co.uk OR site:who-called.co.uk OR site:shouldianswer.co.uk OR site:whocallsme.com OR site:whosringing.co.uk/number)"
        return listOf(exact, reputation, directories).distinct()
    }

    private suspend fun searchQuery(query: String): SearchResponse = withContext(Dispatchers.IO) {
        val key = "query:$query"
        cache[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return@withContext it.response }
        when (val outcome = fetch(query)) {
            is DdgOutcome.Results -> remember(key, SearchResponse(outcome.items))
            DdgOutcome.NoResults -> remember(key, SearchResponse(emptyList()))
            is DdgOutcome.Blocked -> SearchResponse(emptyList(), BLOCKED_MESSAGE)
            is DdgOutcome.Unrecognised -> SearchResponse(emptyList(), "Web search returned an unreadable page (${outcome.reason}). Nothing can be concluded from it.")
            DdgOutcome.Unreachable -> SearchResponse(emptyList(), "Web search couldn't connect or timed out.")
        }
    }

    suspend fun probe(): DdgOutcome = withContext(Dispatchers.IO) { fetch("wikipedia") }

    private fun remember(key: String, response: SearchResponse): SearchResponse {
        cache[key] = Cached(response, System.currentTimeMillis() + CACHE_MS)
        return response
    }

    private fun fetch(query: String): DdgOutcome {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val get = ScanHttp.get("$ENDPOINT?q=$encoded", timeoutMs = 10_000)
        val first = DdgParser.classify(get.code, get.body)
        if (first !is DdgOutcome.Blocked) return first

        val post = ScanHttp.post(
            ENDPOINT,
            "q=$encoded&b=",
            contentType = "application/x-www-form-urlencoded",
            timeoutMs = 10_000,
            headers = mapOf("Origin" to "https://html.duckduckgo.com", "Referer" to ENDPOINT)
        )
        val second = DdgParser.classify(post.code, post.body)
        if (second !is DdgOutcome.Blocked) return second

        // Fallback to DuckDuckGo Lite if the main HTML endpoint is rate-limiting
        val lite = ScanHttp.post(
            LITE_ENDPOINT,
            "q=$encoded",
            contentType = "application/x-www-form-urlencoded",
            timeoutMs = 10_000
        )
        if (lite.isOk) {
            val liteItems = parseLiteResults(lite.body)
            if (liteItems.isNotEmpty()) return DdgOutcome.Results(liteItems)
            if (lite.body.contains("No more results", ignoreCase = true) ||
                lite.body.contains("no results", ignoreCase = true)
            ) {
                return DdgOutcome.NoResults
            }
        }
        return second
    }

    private val LITE_LINK = Regex(
        """<a\b[^>]*class\s*=\s*['"]result-link['"][^>]*href\s*=\s*['"]([^'"]+)['"][^>]*>(.*?)</a>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val LITE_SNIPPET = Regex(
        """<td\b[^>]*class\s*=\s*['"]result-snippet['"][^>]*>(.*?)</td>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val TAG = Regex("<[^>]*>")

    private fun parseLiteResults(html: String): List<SearchResultItem> {
        val links = LITE_LINK.findAll(html).toList()
        val snippets = LITE_SNIPPET.findAll(html).toList()
        return links.take(DdgParser.MAX_RESULTS).mapIndexedNotNull { idx, match ->
            val rawUrl = DdgParser.decodeLink(match.groupValues[1].trim())
            val title = match.groupValues[2].replace(TAG, "").trim()
            val snippet = snippets.getOrNull(idx)?.groupValues?.get(1)?.replace(TAG, "")?.trim().orEmpty()
            if (title.isNotBlank() && rawUrl.isNotBlank()) {
                SearchResultItem(title, snippet, rawUrl, "DuckDuckGo")
            } else {
                null
            }
        }
    }
}
