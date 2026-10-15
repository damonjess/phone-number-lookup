package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
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

    const val BLOCKED_MESSAGE =
        "DuckDuckGo blocked this search with a bot check, so web results aren't available right now. " +
            "This does NOT mean nothing was found. Try again later."

    suspend fun searchWeb(numberFormats: List<String>): SearchResponse = withContext(Dispatchers.IO) {
        val formats = numberFormats.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val cacheKey = formats.joinToString("|")
        cache[cacheKey]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return@withContext it.response }

        val query = formats.joinToString(" OR ") { "\"$it\"" }
        when (val outcome = fetch(query)) {
            is DdgOutcome.Results -> remember(cacheKey, SearchResponse(outcome.items))
            DdgOutcome.NoResults -> remember(cacheKey, SearchResponse(emptyList()))
            // Failures are never cached: a block must not turn into "no results" for a day.
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

    /** One uncached request with a common query, used by the source health check. */
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

        // Some responses to GET are bot checks while a form POST is answered normally, so try once.
        val post = ScanHttp.post(
            ENDPOINT,
            "q=$encoded&b=",
            contentType = "application/x-www-form-urlencoded",
            timeoutMs = 10_000
        )
        return DdgParser.classify(post.code, post.body)
    }
}
