package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Captured from a live Bing SERP. Bing's current markup keeps the heading as plain text and
 * links the result from a sibling anchor, which the previous `<h2><a href=...>` regex missed
 * entirely, leaving every Bing-backed comment search empty.
 */
class BingParserTest {

    @Test
    fun parsesCurrentBingMarkupWithPlainHeadingAndSiblingLink() {
        val html = """
            <html><body><ol id="b_results">
            <li class="b_algo" data-id iid=SERP.5251><div class="b_tpcn">
              <a class="tilk" aria-label="instagram.com" href="https://www.instagram.com/damonkirby/">
                <div class="tptxt"><div class="tptt">instagram.com</div></div>
              </a>
            </div><h2>Kirby Damon (@damonkirby) &bull; Instagram photos and videos</h2>
              <div class="b_caption"><p class="b_lineclamp3">171 Followers &amp; 4 Posts - Kirby Damon on Instagram</p></div>
            </li>
            <li class="b_algo" data-id iid=SERP.5252><div class="b_tpcn">
              <a class="tilk" href="https://www.tiktok.com/@damonkirby0">tiktok.com</a>
            </div><h2>Damon Kirby (@damonkirby0) | TikTok</h2>
              <div class="b_caption"><p>Damon Kirby on TikTok | 416 Likes</p></div>
            </li>
            <li class="b_algo" data-id iid=SERP.5253><h2>No link here</h2><div class="b_caption"><p>orphan card</p></div></li>
            </ol></body></html>
        """.trimIndent()

        val items = OpenWebOsintScraper.parseBingHtml(html)

        assertEquals(2, items.size)
        assertEquals("Kirby Damon (@damonkirby) • Instagram photos and videos", items[0].title)
        assertEquals("https://www.instagram.com/damonkirby/", items[0].url)
        assertEquals("171 Followers & 4 Posts - Kirby Damon on Instagram", items[0].snippet)
        assertEquals("Bing Search", items[0].source)
        assertEquals("Damon Kirby (@damonkirby0) | TikTok", items[1].title)
    }

    @Test
    fun parsesLegacyHeadingAnchorMarkup() {
        val html = """
            <li class="b_algo"><h2><a href="https://news.ycombinator.com/item?id=998877">Comment by alex_dev99 on Hacker News</a></h2>
            <div class="b_caption"><p>Great write-up on coroutines.</p></div></li>
        """.trimIndent()

        val items = OpenWebOsintScraper.parseBingHtml(html)

        assertEquals(1, items.size)
        assertEquals("Comment by alex_dev99 on Hacker News", items[0].title)
        assertEquals("https://news.ycombinator.com/item?id=998877", items[0].url)
        assertEquals("Great write-up on coroutines.", items[0].snippet)
    }

    @Test
    fun rejectsSearchEnginePaddingThatDoesNotMentionTheTarget() {
        val gmailPadding = SearchResultItem(
            title = "Gmail - Google Accounts",
            snippet = "Sign in to your Google Account",
            url = "https://accounts.google.com/login",
            source = "Bing Search"
        )
        val unrelatedHandle = SearchResultItem(
            title = "Damon Kirby (@damonkirby89) | Instagram",
            snippet = "Public profile",
            url = "https://www.instagram.com/damonkirby89/",
            source = "Bing Search"
        )
        val realMatch = SearchResultItem(
            title = "Kirby Damon (@damonkirby) | Instagram",
            snippet = "Public profile",
            url = "https://www.instagram.com/damonkirby/",
            source = "Bing Search"
        )

        assertTrue(!GoogleCommentScraper.searchHitMatchesQuery("@damonkirby", gmailPadding))
        assertTrue(!GoogleCommentScraper.searchHitMatchesQuery("@damonkirby", unrelatedHandle))
        assertTrue(GoogleCommentScraper.searchHitMatchesQuery("@damonkirby", realMatch))
        assertTrue(
            GoogleCommentScraper.searchHitMatchesQuery(
                "damonkirby86@gmail.com",
                realMatch.copy(snippet = "You can reach me at damonkirby86@gmail.com")
            )
        )
    }

    @Test
    fun decodesBingRedirectLinksAndDropsBingInternalResults() {
        val target = "https://www.reddit.com/r/kotlin/comments/abc123/best_pattern/"
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(target.toByteArray(Charsets.UTF_8))
        val html = """
            <li class="b_algo"><h2><a href="https://www.bing.com/ck/a?!&amp;&amp;p=abc&amp;u=a1$encoded&amp;ntb=1">Reddit thread</a></h2>
            <div class="b_caption"><p>A public comment thread.</p></div></li>
            <li class="b_algo"><h2><a href="https://www.bing.com/images/search?q=x">Bing images</a></h2></li>
        """.trimIndent()

        val items = OpenWebOsintScraper.parseBingHtml(html)

        assertEquals(1, items.size)
        assertEquals(target, items[0].url)
        assertTrue(items.none { it.url.contains("bing.com") })
    }
}
