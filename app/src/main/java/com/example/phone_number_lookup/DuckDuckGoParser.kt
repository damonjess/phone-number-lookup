package com.example.phone_number_lookup

import java.net.URLDecoder

/**
 * What a DuckDuckGo HTML response actually means.
 *
 * Two things used to go wrong silently, both reported as "no results" and cached for 24 hours:
 *  1. The old pattern required `class` to be the first attribute of the result link, but
 *     DuckDuckGo writes `<a rel="nofollow" class="result__a" href=...>`, so real results never matched.
 *  2. A bot-check page (often HTTP 202) was treated as an ordinary empty page.
 */
sealed interface DdgOutcome {
    data class Results(val items: List<SearchResultItem>) : DdgOutcome

    /** A genuine "no results" page: safe to report and cache. */
    data object NoResults : DdgOutcome

    /** Bot check or rate limit. Says nothing about whether results exist. */
    data class Blocked(val reason: String) : DdgOutcome

    /**
     * A page we could not read (layout change, consent page, error page). Says nothing about
     * whether results exist. [excerpt] is the start of the page, to show what was really served.
     */
    data class Unrecognised(val reason: String, val excerpt: String = "") : DdgOutcome

    data object Unreachable : DdgOutcome
}

object DdgParser {

    private val ANCHOR = Regex("""<a\b([^>]*)>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val CLASS_ATTR = Regex("""\bclass\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val HREF_ATTR = Regex("""\bhref\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val SCRIPT_OR_STYLE = Regex("""<(script|style)\b.*?</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TITLE = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val WHITESPACE = Regex("""\s+""")

    /** Markers unique to DuckDuckGo's anomaly (bot check) page. */
    private val BLOCK_MARKERS = listOf(
        "anomaly-modal",
        "/anomaly.js",
        "unfortunately, bots use duckduckgo",
        "id=\"challenge-form\"",
    )

    private val NO_RESULTS_MARKERS = listOf("no-results", "no results.")

    const val MAX_RESULTS = 8
    private const val EXCERPT_CHARS = 160

    fun classify(code: Int, html: String): DdgOutcome {
        if (code == -1) return DdgOutcome.Unreachable
        if (code == 202) return DdgOutcome.Blocked("HTTP 202 bot check")
        if (code == 403 || code == 429) return DdgOutcome.Blocked("HTTP $code")
        if (code !in 200..299) return DdgOutcome.Unrecognised("HTTP $code", pageExcerpt(html))

        val lower = html.lowercase()
        if (BLOCK_MARKERS.any { it in lower }) return DdgOutcome.Blocked("bot check page")

        val items = parseResults(html)
        if (items.isNotEmpty()) return DdgOutcome.Results(items)

        return if (NO_RESULTS_MARKERS.any { it in lower }) {
            DdgOutcome.NoResults
        } else {
            DdgOutcome.Unrecognised("page had no results and no recognisable layout", pageExcerpt(html))
        }
    }

    /**
     * Walks the page's links in order. A `result__a` link starts a result; the next
     * `result__snippet` link belongs to it. Attribute order and quote style don't matter.
     */
    internal fun parseResults(html: String): List<SearchResultItem> {
        data class Draft(val title: String, val url: String, var snippet: String = "")

        val drafts = ArrayList<Draft>()
        for (match in ANCHOR.findAll(html)) {
            val attrs = match.groupValues[1]
            val classes = CLASS_ATTR.find(attrs)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                ?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
            val text = unescape(match.groupValues[2].replace(TAG, "")).trim()

            when {
                "result__a" in classes -> {
                    val href = HREF_ATTR.find(attrs)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }.orEmpty()
                    val url = decodeLink(unescape(href))
                    if (text.isNotBlank() && url.isNotBlank()) drafts += Draft(text, url)
                }
                "result__snippet" in classes -> drafts.lastOrNull()?.let { if (it.snippet.isEmpty()) it.snippet = text }
            }
            if (drafts.size >= MAX_RESULTS && drafts.last().snippet.isNotEmpty()) break
        }
        return drafts.take(MAX_RESULTS).map { SearchResultItem(it.title, it.snippet, it.url, "DuckDuckGo") }
    }

    /** The page title plus the first words of visible text, so an unreadable page can be identified. */
    internal fun pageExcerpt(html: String): String {
        if (html.isBlank()) return "(empty page)"
        val title = TITLE.find(html)?.groupValues?.get(1)?.let { unescape(it).trim() }.orEmpty()
        val body = unescape(html.replace(SCRIPT_OR_STYLE, " ").replace(TAG, " "))
            .replace(WHITESPACE, " ").trim()
        val text = body.take(EXCERPT_CHARS)
        return when {
            title.isNotEmpty() && text.isNotEmpty() -> "title \"${title.take(80)}\", text \"$text\""
            title.isNotEmpty() -> "title \"${title.take(80)}\""
            text.isNotEmpty() -> "text \"$text\""
            else -> "(no readable text)"
        }
    }

    /** DuckDuckGo wraps links as //duckduckgo.com/l/?uddg=<encoded target>&rut=... */
    internal fun decodeLink(href: String): String {
        val start = href.indexOf("uddg=")
        if (start < 0) return if (href.startsWith("//")) "https:$href" else href
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
        .replace("&#x27;", "'")
        .replace("&nbsp;", " ")
}
