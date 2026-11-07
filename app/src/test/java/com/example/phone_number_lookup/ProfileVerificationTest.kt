package com.example.phone_number_lookup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileVerificationTest {

    @Test
    fun isValidSiteUsername_stravaRejectsDotsAndSpaces() {
        assertFalse(isValidSiteUsername("Strava", "john.doe"))
        assertFalse(isValidSiteUsername("strava", "john doe"))
        assertFalse(isValidSiteUsername("Strava Athletes", "user.name"))
        assertTrue(isValidSiteUsername("Strava", "johndoe"))
        assertTrue(isValidSiteUsername("Strava", "john_doe"))
        assertTrue(isValidSiteUsername("Strava", "runner123"))
    }

    @Test
    fun isValidSiteUsername_flightradarRequires3to20AlphanumericOrUnderscore() {
        assertFalse(isValidSiteUsername("Flightradar24", "ab")) // too short (<3)
        assertFalse(isValidSiteUsername("flightradar", "a".repeat(21))) // too long (>20)
        assertFalse(isValidSiteUsername("Flightradar24", "pilot.dan")) // has dot
        assertFalse(isValidSiteUsername("Flightradar24", "pilot-dan")) // has hyphen
        assertFalse(isValidSiteUsername("Flightradar24", "pilot dan")) // has space

        assertTrue(isValidSiteUsername("Flightradar24", "pilot_dan"))
        assertTrue(isValidSiteUsername("Flightradar24", "sky123"))
        assertTrue(isValidSiteUsername("my.flightradar24.com", "abc"))
        assertTrue(isValidSiteUsername("Flightradar24", "a".repeat(20)))
    }

    @Test
    fun isValidSiteUsername_otherSitesPermitStandardFormats() {
        assertTrue(isValidSiteUsername("GitHub", "john.doe"))
        assertTrue(isValidSiteUsername("Twitter", "john_doe"))
        assertTrue(isValidSiteUsername("Reddit", "john-doe"))
    }

    @Test
    fun isFalsePositiveProfilePage_rejectsRootHomepageRedirects() {
        assertTrue(isFalsePositiveProfilePage("Flightradar24", "skyuser", "https://my.flightradar24.com/", null, null))
        assertTrue(isFalsePositiveProfilePage("Flightradar24", "skyuser", "https://my.flightradar24.com", null, null))
        assertTrue(isFalsePositiveProfilePage("Strava", "runner", "https://www.strava.com/", null, null))
        assertTrue(isFalsePositiveProfilePage("Unknown", "user", "http://example.com/", null, null))
    }

    @Test
    fun isFalsePositiveProfilePage_flightradarChecks() {
        // Soft-404 boilerplates
        assertTrue(
            isFalsePositiveProfilePage(
                siteName = "Flightradar24",
                requestedUsername = "pilot_dan",
                finalUrl = "https://my.flightradar24.com/pilot_dan",
                pageTitle = "myFlightradar24 — Your personal flight logbook",
                pageBio = null
            )
        )
        assertTrue(
            isFalsePositiveProfilePage(
                siteName = "Flightradar24",
                requestedUsername = "pilot_dan",
                finalUrl = "https://my.flightradar24.com/pilot_dan",
                pageTitle = "Flightradar24",
                pageBio = "Track and log flights in your personal flight logbook"
            )
        )
        // Redirect to a different username/profile URL
        assertTrue(
            isFalsePositiveProfilePage(
                siteName = "Flightradar24",
                requestedUsername = "pilot_dan",
                finalUrl = "https://my.flightradar24.com/other_pilot",
                pageTitle = "other_pilot",
                pageBio = "Pilot profile"
            )
        )
        // Genuine profile
        assertFalse(
            isFalsePositiveProfilePage(
                siteName = "Flightradar24",
                requestedUsername = "pilot_dan",
                finalUrl = "https://my.flightradar24.com/pilot_dan",
                pageTitle = "pilot_dan's flight diary",
                pageBio = "Aviation enthusiast with 50 flights logged"
            )
        )
    }

    @Test
    fun isFalsePositiveProfilePage_stravaChecks() {
        // Login wall
        assertTrue(
            isFalsePositiveProfilePage(
                siteName = "Strava",
                requestedUsername = "runner123",
                finalUrl = "https://www.strava.com/athletes/runner123",
                pageTitle = "Log In | Strava",
                pageBio = null
            )
        )
        assertTrue(
            isFalsePositiveProfilePage(
                siteName = "Strava",
                requestedUsername = "runner123",
                finalUrl = "https://www.strava.com/athletes/runner123",
                pageTitle = "Strava Athlete",
                pageBio = "Log in to see runner123's activities and stats"
            )
        )
        // Dot suffix stripped to another user
        assertTrue(
            isFalsePositiveProfilePage(
                siteName = "Strava",
                requestedUsername = "runner.xyz",
                finalUrl = "https://www.strava.com/athletes/runner",
                pageTitle = "Runner",
                pageBio = "Marathoner"
            )
        )
        // Genuine profile
        assertFalse(
            isFalsePositiveProfilePage(
                siteName = "Strava",
                requestedUsername = "runner123",
                finalUrl = "https://www.strava.com/athletes/runner123",
                pageTitle = "Runner 123",
                pageBio = "Marathon training in Berlin"
            )
        )
    }

    @Test
    fun isFalsePositiveProfilePage_calendlyChecks() {
        // Soft-404 placeholder
        assertTrue(
            isFalsePositiveProfilePage(
                siteName = "Calendly",
                requestedUsername = "meet_john",
                finalUrl = "https://calendly.com/meet_john",
                pageTitle = "Calendly",
                pageBio = "Calendly connects and simplifies scheduling across teams."
            )
        )
        // Genuine Calendly profile
        assertFalse(
            isFalsePositiveProfilePage(
                siteName = "Calendly",
                requestedUsername = "meet_john",
                finalUrl = "https://calendly.com/meet_john",
                pageTitle = "John Doe - Calendly",
                pageBio = "Schedule a 30 minute chat with John Doe"
            )
        )
    }
}
