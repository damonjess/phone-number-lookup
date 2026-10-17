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

sealed class DdgOutcome {
    data class Results(val items: List<SearchResultItem>) : DdgOutcome()
    object NoResults : DdgOutcome()
    object Blocked : DdgOutcome()
    data class Unrecognised(val reason: String, val excerpt: String) : DdgOutcome()
    object Unreachable : DdgOutcome()
}

object DdgParser {
    const val MAX_RESULTS = 8

    fun classify(code: Int, body: String): DdgOutcome {
        if (code in setOf(403, 202, 429) || body.contains("anomaly-detected") || body.contains("bot check") || body.contains("If you have seen this page many times")) {
            return DdgOutcome.Blocked
        }
        if (code == -1) return DdgOutcome.Unreachable
        if (code !in 200..299) return DdgOutcome.Unrecognised("HTTP $code", body.take(100))

        val resultPattern = Regex(
            """<a\b[^>]*class\s*=\s*['"][^'"]*result__a[^'"]*['"][^>]*href\s*=\s*['"]([^'"]+)['"][^>]*>(.*?)</a>.*?<a\b[^>]*class\s*=\s*['"][^'"]*result__snippet[^'"]*['"][^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val tag = Regex("<[^>]*>")
        val matches = resultPattern.findAll(body).toList()
        if (matches.isEmpty()) {
            if (body.contains("No results", ignoreCase = true) || body.contains("no results found", ignoreCase = true) || body.contains("No good match", ignoreCase = true)) {
                return DdgOutcome.NoResults
            }
            if (body.isBlank()) return DdgOutcome.NoResults
            return DdgOutcome.Unrecognised("No search result elements found", body.replace(tag, " ").take(100).trim())
        }

        val items = matches.take(MAX_RESULTS).mapNotNull { match ->
            val link = decodeLink(match.groupValues[1].trim())
            val title = unescapeHtml(match.groupValues[2].replace(tag, "").trim())
            val snippet = unescapeHtml(match.groupValues[3].replace(tag, "").trim())
            if (title.isNotBlank() && link.isNotBlank()) {
                SearchResultItem(title, snippet, link, "DuckDuckGo")
            } else null
        }
        return if (items.isNotEmpty()) DdgOutcome.Results(items) else DdgOutcome.NoResults
    }

    fun decodeLink(raw: String): String {
        return if (raw.contains("uddg=")) {
            runCatching {
                val query = raw.substringAfter("uddg=").substringBefore("&")
                java.net.URLDecoder.decode(query, "UTF-8")
            }.getOrDefault(raw)
        } else raw
    }

    private fun unescapeHtml(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
}

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
            // If a national format has spaces (e.g. "01782 447307"), also include the compact form ("01782447307")
            if (!trimmed.startsWith("+") && trimmed.contains(' ')) {
                val compact = trimmed.filter { it.isDigit() }
                if (compact.length >= 9) out += compact
            }
        }
        return out.take(4)
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
