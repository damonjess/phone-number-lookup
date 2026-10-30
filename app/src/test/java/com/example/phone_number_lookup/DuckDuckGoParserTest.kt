package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckDuckGoParserTest {

    // Real DuckDuckGo markup: `rel` comes BEFORE `class` on the result link.
    private val realMarkup = """
        <html><head><title>test at DuckDuckGo</title></head><body>
        <div class="result results_links web-result">
          <h2 class="result__title">
            <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fx%3D1%26y%3D2&amp;rut=abc">
              Example <b>Test</b> Page
            </a>
          </h2>
          <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">A snippet with <b>bold</b> &amp; text.</a>
        </div>
        <div class="result results_links web-result">
          <h2 class="result__title">
            <a rel="nofollow" class="result__a" href="https://another.com/page">Another Page</a>
          </h2>
          <a class="result__snippet" href="https://another.com/page">Second snippet.</a>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun parsesRealMarkupWhereRelComesBeforeClass() {
        val outcome = DdgParser.classify(200, realMarkup)
        assertTrue(outcome is DdgOutcome.Results)
        val items = (outcome as DdgOutcome.Results).items
        assertEquals(2, items.size)
        assertEquals("Example Test Page", items[0].title)
        assertEquals("https://example.com/a?x=1&y=2", items[0].url)
        assertEquals("A snippet with bold & text.", items[0].snippet)
        assertEquals("https://another.com/page", items[1].url)
        assertEquals("DuckDuckGo", items[0].source)
    }

    @Test
    fun theOldPatternNeverMatchedRealMarkup() {
        // The pattern the app used before: it needs `class` as the first attribute.
        val old = Regex(
            """<a class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>.*?<a class="result__snippet"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        assertFalse(old.containsMatchIn(realMarkup))
    }

    @Test
    fun attributeOrderAndQuoteStyleDoNotMatter() {
        val html = """
            <a href='https://one.com/x' class='result__a'>One</a>
            <a href='https://one.com/x' class='result__snippet'>First</a>
            <a class="result__a" href="https://two.com/y">Two</a>
            <a class="result__snippet">Second</a>
        """.trimIndent()
        val items = (DdgParser.classify(200, html) as DdgOutcome.Results).items
        assertEquals(listOf("https://one.com/x", "https://two.com/y"), items.map { it.url })
        assertEquals(listOf("First", "Second"), items.map { it.snippet })
    }

    @Test
    fun aResultWithoutASnippetIsStillKept() {
        val html = """<a rel="nofollow" class="result__a" href="https://only.com">Only</a>"""
        val items = (DdgParser.classify(200, html) as DdgOutcome.Results).items
        assertEquals(1, items.size)
        assertEquals("", items[0].snippet)
    }

    @Test
    fun aSnippetIsAttachedToTheResultItFollows() {
        val html = """
            <a class="result__a" href="https://a.com">A</a>
            <a class="result__a" href="https://b.com">B</a>
            <a class="result__snippet">belongs to B</a>
        """.trimIndent()
        val items = (DdgParser.classify(200, html) as DdgOutcome.Results).items
        assertEquals("", items[0].snippet)
        assertEquals("belongs to B", items[1].snippet)
    }

    @Test
    fun resultsAreCapped() {
        val html = (1..30).joinToString("\n") {
            """<a class="result__a" href="https://s$it.com">T$it</a><a class="result__snippet">S$it</a>"""
        }
        assertEquals(DdgParser.MAX_RESULTS, (DdgParser.classify(200, html) as DdgOutcome.Results).items.size)
    }

    @Test
    fun botCheckPageIsBlockedNotEmpty() {
        val outcome = DdgParser.classify(200, "<div class=\"anomaly-modal__box\">Unfortunately, bots use DuckDuckGo too.</div>")
        assertTrue(outcome is DdgOutcome.Blocked)
        assertTrue(DdgParser.classify(202, "") is DdgOutcome.Blocked)
        assertTrue(DdgParser.classify(429, "") is DdgOutcome.Blocked)
    }

    @Test
    fun genuineNoResultsPageIsNoResults() {
        assertEquals(DdgOutcome.NoResults, DdgParser.classify(200, "<div class=\"no-results\">No results.</div>"))
    }

    @Test
    fun unreadablePageIsUnrecognisedAndShowsWhatWasServed() {
        val html = "<html><head><title>Before you continue</title><script>var x=1;</script></head>" +
            "<body><p>We use cookies. Please accept.</p></body></html>"
        val outcome = DdgParser.classify(200, html)
        assertTrue(outcome is DdgOutcome.Unrecognised)
        val excerpt = (outcome as DdgOutcome.Unrecognised).excerpt
        assertTrue(excerpt.contains("Before you continue"))
        assertTrue(excerpt.contains("We use cookies"))
        assertFalse(excerpt.contains("var x"))
    }

    @Test
    fun errorPagesAndNetworkFailuresAreNeverReportedAsNoResults() {
        assertTrue(DdgParser.classify(500, "oops") is DdgOutcome.Unrecognised)
        assertEquals(DdgOutcome.Unreachable, DdgParser.classify(-1, ""))
    }

    @Test
    fun emptyBodyHasAReadableExcerpt() {
        val outcome = DdgParser.classify(200, "") as DdgOutcome.Unrecognised
        assertEquals("(empty page)", outcome.excerpt)
    }

    @Test
    fun linksWithoutTheRedirectWrapperAreKept() {
        assertEquals("https://direct.com/p", DdgParser.decodeLink("https://direct.com/p"))
        assertEquals("https://cdn.example.com/r", DdgParser.decodeLink("//cdn.example.com/r"))
    }
}
