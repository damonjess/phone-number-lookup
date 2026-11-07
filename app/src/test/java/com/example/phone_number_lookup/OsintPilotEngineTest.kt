package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsintPilotEngineTest {

    @Test
    fun testDetectTargetType() {
        assertEquals(PilotTargetType.PHONE, OsintPilotEngine.detectTargetType("+447700900123"))
        assertEquals(PilotTargetType.PHONE, OsintPilotEngine.detectTargetType("07700900123"))
        assertEquals(PilotTargetType.PHONE, OsintPilotEngine.detectTargetType("1-555-123-4567"))
        assertEquals(PilotTargetType.EMAIL, OsintPilotEngine.detectTargetType("investigator@example.com"))
        assertEquals(PilotTargetType.NAME, OsintPilotEngine.detectTargetType("Ryan Montgomery"))
        assertEquals(PilotTargetType.USERNAME, OsintPilotEngine.detectTargetType("0day"))
        assertEquals(PilotTargetType.USERNAME, OsintPilotEngine.detectTargetType("@secops"))
    }

    @Test
    fun testReportStructure() {
        val initialStatuses = listOf(
            PilotAgentStatus("agent_id", "Identity Resolution Agent", "Who is this, really?", "Resolved persona", countBadge = "1 name", isComplete = true, findingsCount = 1),
            PilotAgentStatus("agent_footprint", "Digital Footprint Agent", "Where are they active?", "Probed platforms", countBadge = "3 accounts", isComplete = true, findingsCount = 3)
        )
        val findings = listOf(
            SourcedFinding(category = "Identity", claim = "Name: John Doe", sourceName = "Test Directory", confidence = ConfidenceLevel.HIGH),
            SourcedFinding(category = "Registration", claim = "Active account on Microsoft", sourceName = "Microsoft", confidence = ConfidenceLevel.HIGH)
        )
        val report = PilotInvestigationReport(
            investigationId = "TEST1234",
            target = "+447700900123",
            detectedType = PilotTargetType.PHONE,
            agentStatuses = initialStatuses,
            findings = findings
        )

        assertNotNull(report.investigationId)
        assertEquals(2, report.agentStatuses.size)
        assertEquals(2, report.findings.size)
        assertTrue(report.findings.any { it.sourceName == "Microsoft" })
    }

    @Test
    fun testOsintProfileValidator_UsernamePlatformRules() {
        // Strava rules
        org.junit.Assert.assertFalse(OsintProfileValidator.isValidPlatformUsername("Strava", "john.doe", null))
        org.junit.Assert.assertFalse(OsintProfileValidator.isValidPlatformUsername("Strava", "john doe", null))
        assertTrue(OsintProfileValidator.isValidPlatformUsername("Strava", "johndoe", null))

        // Flightradar24 rules (alphanumeric or underscore, 3-20 chars)
        org.junit.Assert.assertFalse(OsintProfileValidator.isValidPlatformUsername("Flightradar24", "ab", null))
        org.junit.Assert.assertFalse(OsintProfileValidator.isValidPlatformUsername("Flightradar24", "pilot.user", null))
        assertTrue(OsintProfileValidator.isValidPlatformUsername("Flightradar24", "pilot_user123", null))

        // Calendly rules (alphanumeric, hyphens, underscores, 3-60 chars, no dot)
        org.junit.Assert.assertFalse(OsintProfileValidator.isValidPlatformUsername("Calendly", "user.name", null))
        assertTrue(OsintProfileValidator.isValidPlatformUsername("Calendly", "user-name_123", null))
    }

    @Test
    fun testOsintProfileValidator_ValidProfilePath() {
        // Root domain links rejected
        org.junit.Assert.assertFalse(OsintProfileValidator.hasValidProfilePath("https://my.flightradar24.com"))
        org.junit.Assert.assertFalse(OsintProfileValidator.hasValidProfilePath("https://my.flightradar24.com/"))
        org.junit.Assert.assertFalse(OsintProfileValidator.hasValidProfilePath(null))
        org.junit.Assert.assertFalse(OsintProfileValidator.hasValidProfilePath(""))

        // Strava /athletes/ with dot rejected
        org.junit.Assert.assertFalse(OsintProfileValidator.hasValidProfilePath("https://www.strava.com/athletes/john.doe"))

        // Valid URLs
        assertTrue(OsintProfileValidator.hasValidProfilePath("https://my.flightradar24.com/pilot_123"))
        assertTrue(OsintProfileValidator.hasValidProfilePath("https://www.strava.com/athletes/98765432"))
    }

    @Test
    fun testOsintProfileValidator_BoilerplateAndMismatchedDossier() {
        // Boilerplate rejection
        assertTrue(
            OsintProfileValidator.isBoilerplateOrMismatchedDossier(
                siteOrTitle = "Calendly",
                handle = "testuser",
                scrapedName = "Calendly",
                scrapedBio = "Calendly connects and simplifies scheduling",
                details = null
            )
        )
        assertTrue(
            OsintProfileValidator.isBoilerplateOrMismatchedDossier(
                siteOrTitle = "Flightradar24",
                handle = "pilot1",
                scrapedName = "404 Page Not Found",
                scrapedBio = null,
                details = "Could not find this page"
            )
        )

        // Strava dot handle rejection
        assertTrue(
            OsintProfileValidator.isBoilerplateOrMismatchedDossier(
                siteOrTitle = "Strava",
                handle = "user.name",
                scrapedName = "User Name",
                scrapedBio = null,
                details = null
            )
        )

        // Strava mismatched athlete name vs handle token
        assertTrue(
            OsintProfileValidator.isBoilerplateOrMismatchedDossier(
                siteOrTitle = "Strava",
                handle = "andrew.hoffman",
                scrapedName = "Andrew Hanna | Runner",
                scrapedBio = "Marathoner",
                details = null
            )
        )

        // Legitimate Strava profile
        org.junit.Assert.assertFalse(
            OsintProfileValidator.isBoilerplateOrMismatchedDossier(
                siteOrTitle = "Strava",
                handle = "andrewhanna",
                scrapedName = "Andrew Hanna | Runner",
                scrapedBio = "Marathoner in Boston",
                details = null
            )
        )
    }
}
