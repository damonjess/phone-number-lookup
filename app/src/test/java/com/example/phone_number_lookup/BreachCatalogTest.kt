package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BreachCatalogTest {

    private val catalogue = """
        [
          {
            "Name": "CarGurus",
            "Title": "CarGurus",
            "Domain": "cargurus.com",
            "BreachDate": "2026-02-14",
            "PwnCount": 12461887,
            "Description": "In February 2026, <a href=\"https://example.com\">CarGurus</a> was breached &amp; the data published.",
            "DataClasses": ["Email addresses", "Names", "Phone numbers"],
            "LogoPath": "https://logos.haveibeenpwned.com/CarGurus.png",
            "IsVerified": true
          },
          {
            "Name": "OldSite",
            "Title": "Old Site",
            "Domain": "oldsite.example",
            "BreachDate": "2014-05-01",
            "PwnCount": 1200,
            "Description": "A small breach.",
            "DataClasses": ["Email addresses"],
            "IsVerified": false
          }
        ]
    """.trimIndent()

    @Test
    fun `matches the name carried in the reported label`() {
        val details = BreachCatalog.parse(
            catalogue,
            listOf("CarGurus (2026 • Email addresses, Phone numbers)")
        )

        assertEquals(1, details.size)
        val detail = details.first()
        assertEquals("CarGurus", detail.name)
        assertEquals("cargurus.com", detail.domain)
        assertEquals("2026-02-14", detail.breachDate)
        assertEquals(12_461_887L, detail.pwnCount)
        assertTrue(detail.verified)
        assertEquals(listOf("Email addresses", "Names", "Phone numbers"), detail.dataClasses)
    }

    @Test
    fun `strips html and entities from the description`() {
        val detail = BreachCatalog.parse(catalogue, listOf("CarGurus")).first()
        assertTrue(detail.description.contains("CarGurus was breached & the data published."))
        assertTrue(!detail.description.contains("<a"))
    }

    @Test
    fun `orders details newest first`() {
        val details = BreachCatalog.parse(catalogue, listOf("CarGurus", "OldSite"))
        assertEquals(listOf("CarGurus", "Old Site"), details.map { it.name })
    }

    @Test
    fun `ignores unrelated and infostealer labels`() {
        val details = BreachCatalog.parse(
            catalogue,
            listOf("Infostealer Log (HudsonRock • 2026-01-01)", "SomeOtherBreach (2019 • Emails)")
        )
        assertTrue(details.isEmpty())
    }

    @Test
    fun `matches on domain when the title differs`() {
        val details = BreachCatalog.parse(catalogue, listOf("oldsite.example"))
        assertEquals(1, details.size)
        assertEquals("Old Site", details.first().name)
    }
}
