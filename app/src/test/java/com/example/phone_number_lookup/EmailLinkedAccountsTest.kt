package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailLinkedAccountsTest {

    private val email = "hillspropertymaintenance258@gmail.com"

    private fun registration(
        site: String,
        verification: String = "Registered response",
        source: String = "Curated check"
    ) = RegistrationHit(
        site = site,
        category = "Entertainment",
        source = source,
        profileUrl = "https://www.$site.com/",
        statusCode = 200,
        verificationStatus = verification
    )

    private fun handleHit(site: String, handle: String = "hillspropertymaintenance258") = UsernameHit(
        site = site,
        category = "Social",
        source = "Sherlock",
        profileUrl = "https://www.$site.com/$handle",
        verificationStatus = "Matched",
        queriedName = handle
    )

    private fun attribution(links: List<AttributedProfileLink>) = EmailAttributionReport(
        email = email,
        profileLinks = links
    )

    @Test
    fun aRegistrationBecomesAConfirmedAccountWithItsOwnFields() {
        val accounts = EmailLinkedAccounts.build(
            email = email,
            registrations = listOf(registration("hulu", source = "Blackbird")),
            handleHits = emptyList(),
            attribution = null
        )

        assertEquals(1, accounts.size)
        val account = accounts.single()
        assertEquals("Hulu", account.name)
        assertEquals("hulu.com", account.domain)
        assertTrue(account.confirmed)
        assertEquals(email, account.fields.first { it.first == "Email" }.second)
        assertEquals("Blackbird", account.fields.first { it.first == "Source" }.second)
        assertEquals("https://www.hulu.com/", account.url)
    }

    @Test
    fun aHandleHitDoesNotDuplicateAnAlreadyFoundService() {
        val accounts = EmailLinkedAccounts.build(
            email = email,
            registrations = listOf(registration("eventbrite", verification = "Registered response")),
            handleHits = listOf(handleHit("eventbrite"), handleHit("github")),
            attribution = null
        )

        assertEquals(2, accounts.size)
        assertEquals(listOf("Eventbrite", "Github"), accounts.map { it.name })
        // The registration's registration fields win over the handle-hit fields.
        assertTrue(accounts.first { it.name == "Eventbrite" }.fields.any { it.first == "Email" })
        assertEquals("Github", accounts.first { it.name == "Github" }.name)
        assertTrue(accounts.first { it.name == "Github" }.confirmed)
    }

    @Test
    fun attributionLinksFillTheGapsWithPublicProfiles() {
        val accounts = EmailLinkedAccounts.build(
            email = email,
            registrations = emptyList(),
            handleHits = emptyList(),
            attribution = attribution(
                listOf(
                    AttributedProfileLink("hillspropertymaintenance258", "MyFitnessPal", "https://www.myfitnesspal.com/profile/hillspropertymaintenance258"),
                    AttributedProfileLink("Google", "Google Maps", "https://maps.google.com/contrib/123")
                )
            )
        )

        assertEquals(2, accounts.size)
        val mfp = accounts.first { it.name == "MyFitnessPal" }
        assertEquals("myfitnesspal.com", mfp.domain)
        assertEquals("Public profile link", mfp.fields.first { it.first == "Type" }.second)
        assertFalse(mfp.confirmed)
        assertEquals("https://maps.google.com/contrib/123", accounts.first { it.name == "Google Maps" }.url)
    }

    @Test
    fun unconfirmedReportsComeAfterConfirmedOnes() {
        val accounts = EmailLinkedAccounts.build(
            email = email,
            registrations = listOf(registration("office365", verification = "Reported response")),
            handleHits = listOf(handleHit("spotify")),
            attribution = null
        )

        assertEquals(listOf("Spotify", "Office365"), accounts.map { it.name })
        assertTrue(accounts.first().confirmed)
        assertFalse(accounts.last().confirmed)
    }

    @Test
    fun handleOfAnAddressIsItsLocalPart() {
        assertEquals("hillspropertymaintenance258", EmailLinkedAccounts.handle(email))
    }
}
