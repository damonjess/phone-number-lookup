package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class PhoneDorksTest {

    private val uk = DorkNumber("+442079460958", "+44 20 7946 0958", "020 7946 0958", "02079460958")

    @Test
    fun spellingsAreDistinctAndNonBlank() {
        val n = DorkNumber("+4412", " ", "+4412", "012")
        assertEquals(listOf("+4412", "012"), n.spellings)
    }

    @Test
    fun theAnyOfClauseListsEverySpelling() {
        assertEquals(
            "(\"+442079460958\" OR \"+44 20 7946 0958\" OR \"020 7946 0958\" OR \"02079460958\")",
            PhoneDorks.anyOf(uk)
        )
    }

    @Test
    fun fiveGroupsInAFixedOrder() {
        val groups = PhoneDorks.generate(uk)
        assertEquals(
            listOf("Social media", "Scam and spam reports", "Temporary SMS numbers", "Documents and pages", "General"),
            groups.map { it.title }
        )
        assertEquals(listOf(1, 6, 1, 5, 2), groups.map { it.dorks.size })
    }

    @Test
    fun reportSiteDorksUseTheSiteOperator() {
        val dork = PhoneDorks.generate(uk)[1].dorks.first { it.label == "WhoCallsMe" }
        assertEquals("site:whocallsme.com ${PhoneDorks.anyOf(uk)}", dork.query)
    }

    @Test
    fun thereAreNoPerSocialSiteSearchesBecauseThoseSitesDontPublishNumbers() {
        val queries = PhoneDorks.generate(uk).flatMap { it.dorks }.map { it.query }
        listOf("site:facebook.com", "site:t.me", "site:instagram.com", "site:twitter.com", "site:linkedin.com", "site:tiktok.com")
            .forEach { site -> assertTrue("$site search is back", queries.none { site in it }) }
    }

    @Test
    fun everyDorkContainsTheNumber() {
        PhoneDorks.generate(uk).flatMap { it.dorks }.forEach {
            assertTrue(it.label, it.query.contains("\"02079460958\""))
        }
    }

    @Test
    fun urlsAreGoogleSearchesThatDecodeBackToTheQuery() {
        val dork = PhoneDorks.generate(uk).flatMap { it.dorks }.first { it.label == "PDF files" }
        assertTrue(dork.url.startsWith("https://www.google.com/search?q="))
        assertEquals(dork.query, URLDecoder.decode(dork.url.substringAfter("q="), "UTF-8"))
    }

    @Test
    fun aNumberWithNoSpellingsGivesNoDorks() {
        assertTrue(PhoneDorks.generate(DorkNumber("", " ", "", "")).isEmpty())
    }
}
