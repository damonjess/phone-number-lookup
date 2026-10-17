package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLinksTest {

    @Test
    fun whatsAppUsesTheDocumentedFormatWithDigitsOnly() {
        val link = PhoneLinks.generate("+447795504158").first { it.label.startsWith("WhatsApp") }
        assertEquals("https://wa.me/447795504158", link.url)
        assertTrue("no plus sign", '+' !in link.url)
    }

    @Test
    fun tellowsPointsAtThePageForThatNumber() {
        val link = PhoneLinks.generate("+442079460958").first { it.label.startsWith("tellows") }
        assertEquals("https://www.tellows.co.uk/num/%2B442079460958", link.url)
    }

    @Test
    fun spacesAndPunctuationAreIgnored() {
        val urls = PhoneLinks.generate("+44 (0)20 7946-0958").map { it.url }
        assertTrue(urls.contains("https://wa.me/4402079460958"))
    }

    @Test
    fun aTooShortNumberGivesNoLinks() {
        assertTrue(PhoneLinks.generate("+44").isEmpty())
        assertTrue(PhoneLinks.generate("abc").isEmpty())
    }

    @Test
    fun noGuessedLinksForSitesWithoutADocumentedFormat() {
        val urls = PhoneLinks.generate("+442079460958").joinToString(" ") { it.url }
        listOf("facebook", "instagram", "twitter", "linkedin", "tiktok", "t.me").forEach {
            assertTrue("unexpected $it link", it !in urls)
        }
    }
}
