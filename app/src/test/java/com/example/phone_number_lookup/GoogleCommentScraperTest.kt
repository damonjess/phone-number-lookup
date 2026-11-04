package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleCommentScraperTest {

    @Test
    fun parsesGoogleBasicHtmlRedirectLinksAndSnippets() {
        val html = """
            <html><body>
            <div class="Gx5Zad xpd EtOod pkphOe">
              <div class="egMi0 kCrYT">
                <a href="/url?q=https%3A%2F%2Fwww.reddit.com%2Fr%2Fandroiddev%2Fcomments%2Fabc123%2Fcompose_tips%2F&amp;sa=U&amp;ved=2ahUKE">
                  <h3 class="zBAuLc l97dzf"><div class="BNeawe vvjwJb AP7Wnd">r/androiddev - Comment by alex_dev99</div></h3>
                </a>
              </div>
              <div class="kCrYT">
                <div><div class="BNeawe s3v9rd AP7Wnd">14 Mar 2025 — I commented on this thread using alex_dev99@gmail.com to share the Compose performance fix.</div></div>
              </div>
            </div>
            <div class="Gx5Zad xpd EtOod pkphOe">
              <div class="egMi0 kCrYT">
                <a href="/url?q=https%3A%2F%2Fdisqus.com%2Fby%2Falex_dev99%2F&amp;sa=U">
                  <div class="BNeawe vvjwJb AP7Wnd">alex_dev99 — Disqus Profile &amp; Comments</div>
                </a>
              </div>
              <div class="kCrYT">
                <div class="BNeawe s3v9rd AP7Wnd">View 42 comments posted by alex_dev99 across tech blogs and forums.</div>
              </div>
            </div>
            </body></html>
        """.trimIndent()

        val items = GoogleCommentScraper.parseGoogleSearchHtml(html)
        assertEquals(2, items.size)
        assertEquals("r/androiddev - Comment by alex_dev99", items[0].title)
        assertEquals("https://www.reddit.com/r/androiddev/comments/abc123/compose_tips/", items[0].url)
        assertTrue(items[0].snippet.contains("I commented on this thread"))

        val hit0 = GoogleCommentScraper.toCommentHit(items[0], "alex_dev99@gmail.com", "Google Search")!!
        assertEquals("Reddit", hit0.platform)
        assertEquals("14 Mar 2025", hit0.date)
        assertEquals("I commented on this thread using alex_dev99@gmail.com to share the Compose performance fix.", hit0.commentSnippet)

        val hit1 = GoogleCommentScraper.toCommentHit(items[1], "@alex_dev99", "Google Search")!!
        assertEquals("Disqus", hit1.platform)
        assertEquals("https://disqus.com/by/alex_dev99/", hit1.url)
    }

    @Test
    fun parsesDirectGoogleH3ResultMarkup() {
        val html = """
            <html><body>
            <div class="g">
              <a href="https://news.ycombinator.com/item?id=998877" data-ved="123">
                <br><h3 class="LC20lb">Comment by alex_dev99 on Hacker News</h3>
              </a>
              <div class="VwiC3b yXK7lf">Oct 3, 2024 - Great write-up! You can reach me at alex_dev99@gmail.com if you want the benchmark code.</div>
            </div>
            </body></html>
        """.trimIndent()

        val items = GoogleCommentScraper.parseGoogleSearchHtml(html)
        assertEquals(1, items.size)
        assertEquals("Comment by alex_dev99 on Hacker News", items[0].title)
        assertEquals("https://news.ycombinator.com/item?id=998877", items[0].url)

        val hit = GoogleCommentScraper.toCommentHit(items[0], "alex_dev99@gmail.com", "Google Search")!!
        assertEquals("HackerNews", hit.platform)
        assertEquals("Oct 3, 2024", hit.date)
        assertTrue(hit.commentSnippet.contains("Great write-up!"))
    }

    @Test
    fun detectsGoogleBotCheckAndJsRedirectPages() {
        assertTrue(GoogleCommentScraper.isGoogleBlockedHtml("<html><body>/httpservice/retry/enablejs</body></html>"))
        assertTrue(GoogleCommentScraper.isGoogleBlockedHtml("<html><body>Our systems have detected unusual traffic from your computer network. g-recaptcha</body></html>"))
        assertFalse(GoogleCommentScraper.isGoogleBlockedHtml("<html><body><a href=\"/url?q=https://example.com\"><h3>Example</h3></a></body></html>"))
    }

    @Test
    fun parsesRedditHackerNewsAndGitHubCommentJson() {
        val redditJson = """
            {
              "data": {
                "children": [
                  {
                    "kind": "t1",
                    "data": {
                      "subreddit_name_prefixed": "r/kotlin",
                      "author": "alex_dev99",
                      "link_title": "Best coroutines pattern?",
                      "body": "Use structured concurrency with supervisorScope when scraping multiple sources.",
                      "permalink": "/r/kotlin/comments/111/best_coroutines_pattern/c222/",
                      "created_utc": 1741910400
                    }
                  }
                ]
              }
            }
        """.trimIndent()
        val redditHits = GoogleCommentScraper.parseRedditCommentsJson(redditJson, "@alex_dev99")
        assertEquals(1, redditHits.size)
        assertEquals("Reddit", redditHits[0].platform)
        assertTrue(redditHits[0].title.contains("r/kotlin • u/alex_dev99 (Comment): Best coroutines pattern?"))
        assertEquals("Use structured concurrency with supervisorScope when scraping multiple sources.", redditHits[0].commentSnippet)

        val hnJson = """
            {
              "hits": [
                {
                  "objectID": "42001122",
                  "author": "alex_dev99",
                  "story_title": "Show HN: Open-source OSINT tool",
                  "comment_text": "Tested this on Android 15 and it works great!",
                  "created_at": "2025-03-10T12:00:00.000Z"
                }
              ]
            }
        """.trimIndent()
        val hnHits = GoogleCommentScraper.parseHackerNewsCommentsJson(hnJson, "@alex_dev99")
        assertEquals(1, hnHits.size)
        assertEquals("HackerNews", hnHits[0].platform)
        assertEquals("2025-03-10", hnHits[0].date)
        assertEquals("Tested this on Android 15 and it works great!", hnHits[0].commentSnippet)

        val ghJson = """
            {
              "items": [
                {
                  "title": "Fix race condition in viewmodel",
                  "html_url": "https://github.com/example/repo/issues/42",
                  "user": { "login": "maintainer" },
                  "updated_at": "2025-02-20T09:15:00Z",
                  "body": "Left a review comment regarding ScanCoordinator token cancellation."
                }
              ]
            }
        """.trimIndent()
        val ghHits = GoogleCommentScraper.parseGitHubIssuesJson(ghJson, "@alex_dev99", isAuthorHandle = true)
        assertEquals(1, ghHits.size)
        assertEquals("GitHub", ghHits[0].platform)
        assertEquals("example/repo: Active on \"Fix race condition in viewmodel\"", ghHits[0].title)
    }

    @Test
    fun buildsTargetedGoogleCommentQueriesAndDorks() {
        val queries = GoogleCommentScraper.buildGoogleCommentQueries(
            email = "alex_dev99@gmail.com",
            handle = "alex_dev99",
            includeHandle = true,
            knownDisplayNames = listOf("Alex Smith")
        )
        assertTrue(queries.any { it.contains("\"alex_dev99@gmail.com\"") && it.contains("comment") })
        assertTrue(queries.any { it.contains("site:reddit.com") && it.contains("site:youtube.com") && it.contains("site:disqus.com") })

        val dorks = GoogleCommentScraper.buildCommentDorkLinks("alex_dev99@gmail.com", "alex_dev99")
        assertEquals(4, dorks.size)
        assertTrue(dorks.all { it.url.startsWith("https://www.google.com/search?q=") })
    }

    @Test
    fun buildsDedicatedFacebookEmailQueries() {
        val email = "alex_dev99@gmail.com"
        val handle = "alex_dev99"

        val googleFbQueries = GoogleCommentScraper.buildFacebookEmailQueries(email, handle)
        assertEquals(3, googleFbQueries.size)
        assertEquals("\"$email\" site:facebook.com", googleFbQueries[0])
        assertEquals("\"$email\" (site:facebook.com/public OR site:facebook.com/people)", googleFbQueries[1])
        assertEquals("\"$handle\" site:facebook.com/groups OR site:facebook.com/public", googleFbQueries[2])

        val openWebFbQueries = OpenWebOsintScraper.buildFacebookEmailQueries(email, handle)
        assertEquals(googleFbQueries, openWebFbQueries)
    }

    @Test
    fun classifiesFacebookUrlsAsFacebookPlatform() {
        val platform1 = GoogleCommentScraper.classifyPlatform("https://www.facebook.com/public/alex_dev99", "Alex Dev - Facebook")
        assertEquals("Facebook", platform1)

        val platform2 = GoogleCommentScraper.classifyPlatform("https://www.facebook.com/groups/androiddevs/posts/123", "Android Devs Group")
        assertEquals("Facebook", platform2)

        val item = SearchResultItem(
            title = "Alex Dev - Facebook Public Profile",
            snippet = "Contact alex_dev99@gmail.com for collaboration.",
            url = "https://www.facebook.com/people/Alex-Dev/100012345",
            source = "Google Search"
        )
        val hit = GoogleCommentScraper.toCommentHit(item, "alex_dev99@gmail.com", "Google Search")!!
        assertEquals("Facebook", hit.platform)
        assertEquals("Alex Dev - Facebook Public Profile", hit.title)
    }

    @Test
    fun filtersOutForeignQAndASpamAndHeavyCjkContent() {
        val zhihuItem = SearchResultItem(
            title = "如何在 知乎 上找到相关信息？",
            snippet = "知乎是一个中文互联网高质量问答社区和创作者聚集的原创内容平台",
            url = "https://www.zhihu.com/question/123456",
            source = "Google Search"
        )
        assertTrue(GoogleCommentScraper.isLikelySpamOrNoise(zhihuItem.title, zhihuItem.snippet, zhihuItem.url))
        assertNull(GoogleCommentScraper.toCommentHit(zhihuItem, "alex_dev99", "Google Search"))

        val baiduItem = SearchResultItem(
            title = "百度知道",
            snippet = "百度知道是全球最大的中文问答平台，致力于为用户提供解决问题的平台",
            url = "https://zhidao.baidu.com/question/987654.html",
            source = "Google Search"
        )
        assertTrue(GoogleCommentScraper.isLikelySpamOrNoise(baiduItem.title, baiduItem.snippet, baiduItem.url))
        assertNull(GoogleCommentScraper.toCommentHit(baiduItem, "alex_dev99", "Google Search"))

        val cjkSpamItem = SearchResultItem(
            title = "Random Spam Page",
            snippet = "这是一个充满了垃圾信息的非英语网页内容测试，含有大量中文字符",
            url = "https://example.com/spam",
            source = "Google Search"
        )
        assertTrue(GoogleCommentScraper.isLikelySpamOrNoise(cjkSpamItem.title, cjkSpamItem.snippet, cjkSpamItem.url))
        assertNull(GoogleCommentScraper.toCommentHit(cjkSpamItem, "alex_dev99", "Google Search"))

        val validItem = SearchResultItem(
            title = "Alex Dev on GitHub Discussions",
            snippet = "Here is my response about Android development best practices.",
            url = "https://github.com/org/repo/discussions/42",
            source = "Google Search"
        )
        assertFalse(GoogleCommentScraper.isLikelySpamOrNoise(validItem.title, validItem.snippet, validItem.url))
        val validHit = GoogleCommentScraper.toCommentHit(validItem, "alex_dev99", "Google Search")
        assertNotNull(validHit)
        assertEquals("GitHub", validHit!!.platform)
    }

    @Test
    fun verifiesFacebookDorksGeneratedCorrectly() {
        val dorks = OsintTools.getFacebookDorks("alex.dev@gmail.com", "alex_dev")
        assertEquals(2, dorks.size)
        assertEquals("Facebook Directory", dorks[0].toolSource)
        assertTrue(dorks[0].url.contains("facebook.com/public/"))
        assertEquals("Facebook Dork", dorks[1].toolSource)
        assertTrue(dorks[1].url.contains("site:facebook.com"))

        val emailDorks = OsintTools.getBlackbirdEmailScan("alex.dev@gmail.com")
        assertTrue(emailDorks.any { it.toolSource == "Facebook Directory" })
        assertTrue(emailDorks.any { it.toolSource == "Facebook Dork" })
    }
}
