package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteKeyTest {

    private fun probe(name: String, profile: String, source: String, category: String = "social") = SiteProbe(
        name = name, category = category, source = source, method = "GET",
        urlTemplate = profile, accountMode = AccountMode.RAW, profileTemplate = profile,
        headers = emptyMap(), bodyTemplate = null, foundCodes = emptySet(), foundStrings = emptyList(),
        missingCodes = emptySet(), missingStrings = emptyList(), missingUrls = emptyList(),
    )

    @Test
    fun sameProfileFromDifferentListsHasTheSameKey() {
        val keys = listOf(
            "https://github.com/{account}",
            "https://www.github.com/{account}/",
            "http://github.com/{}",
            "HTTPS://GitHub.com/{username}",
        ).map { SiteKey.of(it) }
        assertEquals(1, keys.toSet().size)
    }

    @Test
    fun differentPathsOrHostsStayDistinct() {
        assertTrue(SiteKey.of("https://x.com/{account}") != SiteKey.of("https://twitter.com/{account}"))
        assertTrue(SiteKey.of("https://site.com/u/{account}") != SiteKey.of("https://site.com/user/{account}"))
        assertTrue(SiteKey.of("https://{account}.tumblr.com") != SiteKey.of("https://{account}.blogspot.com"))
    }

    @Test
    fun queryStringsAreKeptSoDifferentLookupsAreNotMerged() {
        assertTrue(SiteKey.of("https://s.com/p?id={account}") != SiteKey.of("https://s.com/p?name={account}"))
    }

    @Test
    fun blankTemplateGivesNoKey() {
        assertEquals("", SiteKey.of("  "))
    }

    @Test
    fun theSameSiteFromThreeListsIsProbedOnce_andTheBestListWins() {
        val merged = SiteKey.dedupe(
            listOf(
                probe("GitHub", "https://github.com/{account}", "WhatsMyName"),
                probe("GitHub", "https://github.com/{account}", "Sherlock"),
                probe("github.com", "https://github.com/{account}", "Tookie-OSINT"),
            ),
            byProfile = true,
        )
        assertEquals(1, merged.size)
        assertEquals("WhatsMyName", merged.single().source)
    }

    @Test
    fun aSiteOnlyOneListKnowsSurvives() {
        val merged = SiteKey.dedupe(
            listOf(
                probe("GitHub", "https://github.com/{account}", "WhatsMyName"),
                probe("munzee.com", "https://munzee.com/m/{account}", "Tookie-OSINT"),
            ),
            byProfile = true,
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun nameOnlyModeStillMergesByName() {
        val merged = SiteKey.dedupe(
            listOf(
                probe("Gravatar", "https://a.com/{account}", "Verified"),
                probe("gravatar", "https://b.com/{account}", "Blackbird"),
            ),
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun withoutProfileModeTwoNamesForOnePageBothSurvive() {
        // Email checks keep both rows: different endpoints can share a profile page.
        val merged = SiteKey.dedupe(
            listOf(
                probe("A", "https://a.com/{account}", "Verified"),
                probe("B", "https://a.com/{account}", "Blackbird"),
            ),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun resultIsSortedByCategoryThenName() {
        val merged = SiteKey.dedupe(
            listOf(
                probe("Zed", "https://z.com/{account}", "WhatsMyName", "social"),
                probe("Alpha", "https://a.com/{account}", "WhatsMyName", "gaming"),
                probe("Beta", "https://b.com/{account}", "WhatsMyName", "gaming"),
            ),
            byProfile = true,
        )
        assertEquals(listOf("Alpha", "Beta", "Zed"), merged.map { it.name })
    }
}
