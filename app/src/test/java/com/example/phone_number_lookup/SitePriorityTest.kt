package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SitePriorityTest {

    private fun probe(name: String, category: String) = SiteProbe(
        name = name, category = category, source = "Test", method = "GET",
        urlTemplate = "https://example.com/{account}", accountMode = AccountMode.RAW,
        profileTemplate = "https://example.com/{account}", headers = emptyMap(), bodyTemplate = null,
        foundCodes = emptySet(), foundStrings = emptyList(), missingCodes = emptySet(),
        missingStrings = emptyList(), missingUrls = emptyList(),
    )

    @Test
    fun popularSiteBeatsAlphabeticalCategoryOrder() {
        // The merged database is sorted by category, so "archived" used to come first.
        val sites = (1..200).map { probe("Old$it", "archived") } + probe("Instagram", "social")
        val firstTwo = SitePriority.prioritise(sites).take(2).map { it.name }
        assertTrue("Instagram" in firstTwo)
    }

    @Test
    fun tookieStyleHostNamesStillRankAsPopular() {
        assertEquals(SitePriority.topRank("github"), SitePriority.topRank("github.com"))
        assertTrue(SitePriority.topRank("reddit.com") != null)
    }

    @Test
    fun shortKeysOnlyMatchExactly() {
        // "x" must not match every name starting with x.
        assertEquals(null, SitePriority.topRank("xyzforum"))
        assertTrue(SitePriority.topRank("X") != null)
    }

    @Test
    fun unknownSitesAreOrderedByCategory() {
        val sites = listOf(
            probe("Zeta", "archived"), probe("Beta", "xx NSFW"), probe("Gamma", "misc"),
            probe("Alpha", "social"), probe("Delta", "gaming"),
        )
        assertEquals(
            listOf("Alpha", "Delta", "Gamma", "Beta", "Zeta"),
            SitePriority.prioritise(sites).map { it.name },
        )
    }

    @Test
    fun orderIsStableWithinACategory() {
        val sites = listOf(probe("b", "misc"), probe("a", "misc"), probe("C", "misc"))
        assertEquals(listOf("a", "b", "C"), SitePriority.prioritise(sites).map { it.name })
    }
}
