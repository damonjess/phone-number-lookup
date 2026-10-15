package com.example.phone_number_lookup

import java.net.URLDecoder

/**
 * What a DuckDuckGo HTML response actually means.
 *
 * DuckDuckGo's HTML endpoint tends to answer automated requests with an HTTP 202 bot-check page
 * instead of results. The old code treated any 2xx as success, found zero results, showed
 * "no results" and cached that for 24 hours. Telling these cases apart stops a block from being
 * reported as "nothing found".
 */
sealed interface DdgOutcome {
    data class Results(val items: List<SearchResultItem>) : DdgOutcome

    /** A genuine "no results" page: safe to report and cache. */
    data object NoResults : DdgOutcome

    /** Bot check or rate limit. Says nothing about whether results exist. */
    data class Blocked(val reason: String) : DdgOutcome

    /** A page we could not read (layout change, error page). Also says nothing about results. */
    data class Unrecognised(val reason: String) : DdgOutcome

    data object Unreachable : DdgOutcome
}

object DdgParser {

    private val RESULT_PATTERN = Regex(
        """<a class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>.*?<a class="result__snippet"[^>]*>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val TAG = Regex("<[^>]*>")

    /** Markers unique to DuckDuckGo's anomaly (bot check) page. */
    private val BLOCK_MARKERS = listOf(
        "anomaly-modal",
        "/anomaly.js",
        "unfortunately, bots use duckduckgo",
        "id=\"challenge-form\"",
    )

    private val NO_RESULTS_MARKERS = listOf("no-results", "no results.")

    const val MAX_RESULTS = 8

    fun classify(code: Int, html: String): DdgOutcome {
        if (code == -1) return DdgOutcome.Unreachable
        if (code == 202) return DdgOutcome.Blocked("HTTP 202 bot check")
        if (code == 403 || code == 429) return DdgOutcome.Blocked("HTTP $code")
        if (code !in 200..299) return DdgOutcome.Unrecognised("HTTP $code")

        val lower = html.lowercase()
        if (BLOCK_MARKERS.any { it in lower }) return DdgOutcome.Blocked("bot check page")

        val items = RESULT_PATTERN.findAll(html).take(MAX_RESULTS).mapNotNull { match ->
            val title = unescape(match.groupValues[2].replace(TAG, "").trim())
            val snippet = unescape(match.groupValues[3].replace(TAG, "").trim())
            if (title.isBlank() || snippet.isBlank()) null
            else SearchResultItem(title, snippet, decodeLink(match.groupValues[1]), "DuckDuckGo")
        }.toList()
        if (items.isNotEmpty()) return DdgOutcome.Results(items)

        return if (NO_RESULTS_MARKERS.any { it in lower }) DdgOutcome.NoResults
        else DdgOutcome.Unrecognised("page had no results and no recognisable layout")
    }

    /** DuckDuckGo wraps links as //duckduckgo.com/l/?uddg=<encoded target>&rut=... */
    internal fun decodeLink(href: String): String {
        val start = href.indexOf("uddg=")
        if (start < 0) return href
        val value = href.substring(start + 5).substringBefore('&')
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            href
        }
    }

    private fun unescape(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
}
