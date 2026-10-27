package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailProfileSummaryTest {

    private fun registration(site: String, status: String = "Registered response") = RegistrationHit(
        site = site,
        category = "General",
        source = "Curated",
        profileUrl = "https://example.com/$site",
        statusCode = 200,
        verificationStatus = status
    )

    private fun attribution(
        names: List<String> = emptyList(),
        usernames: List<String> = emptyList(),
        locations: List<String> = emptyList(),
        timeline: List<TimelineEvent> = emptyList(),
        stealer: StealerSubjectProfile? = null
    ) = EmailAttributionReport(
        email = "hillspropertymaintenance258@gmail.com",
        namesFound = names.map { AttributedValue(it, "Google") },
        usernamesFound = usernames.map { AttributedValue(it, "MyFitnessPal") },
        locationsFound = locations.map { AttributedValue(it, "Google") },
        timeline = timeline,
        stealerProfile = stealer
    )

    @Test
    fun returnsNothingWhenNoSourceReturnedAnything() {
        assertNull(
            EmailProfileSummaryBuilder.email(
                email = "nobody@example.com",
                attribution = attribution(),
                registrations = emptyList(),
                handleHits = emptyList(),
                breachReport = BreachReport("nobody@example.com", false, emptyList())
            )
        )
    }

    @Test
    fun summarisesOnlyValuesThatWereReturned() {
        val summary = EmailProfileSummaryBuilder.email(
            email = "hillspropertymaintenance258@gmail.com",
            attribution = attribution(
                names = listOf("Jamie Hill"),
                usernames = listOf("hillspropertymaintenance258"),
                locations = listOf("Unit 14, West One Plaza, Sheffield")
            ),
            registrations = listOf(registration("Hulu"), registration("ClassDojo")),
            handleHits = emptyList(),
            breachReport = BreachReport("x", true, listOf("Cargurus"))
        )!!

        assertEquals("Moderate", summary.risk)
        assertTrue(summary.headline.contains("2 linked services"))
        assertTrue(summary.headline.contains("1 breach record"))
        assertTrue(summary.bullets.any { it.contains("Hulu") && it.contains("ClassDojo") })
        assertTrue(summary.bullets.any { it.contains("Jamie Hill") })
        assertTrue(summary.bullets.any { it.contains("hillspropertymaintenance258") })
        assertTrue(summary.bullets.any { it.contains("Unit 14, West One Plaza, Sheffield") })
        assertTrue(summary.bullets.any { it.contains("Cargurus") })
        assertTrue(summary.riskNote.contains("breach"))
    }

    @Test
    fun anInfostealerCaptureRaisesTheRiskToHigh() {
        val summary = EmailProfileSummaryBuilder.email(
            email = "a@b.com",
            attribution = attribution(
                stealer = StealerSubjectProfile(
                    computerUsername = "damon-pc",
                    maskedIp = null,
                    operatingSystem = "Windows 11",
                    dateCompromised = "2024-03-02"
                )
            ),
            registrations = listOf(registration("Hulu")),
            handleHits = emptyList(),
            breachReport = null
        )!!

        assertEquals("High", summary.risk)
        assertTrue(summary.headline.contains("infostealer"))
        assertTrue(summary.bullets.any { it.contains("infostealer log names this email") })
        assertTrue(summary.bullets.any { it.contains("Windows 11") })
        assertTrue(summary.riskNote.startsWith("Credential exposure was found"))
    }

    @Test
    fun threeOrMoreBreachesAreHighAndCoverageDatesAreReported() {
        val summary = EmailProfileSummaryBuilder.email(
            email = "a@b.com",
            attribution = attribution(
                timeline = listOf(
                    TimelineEvent("2023-01-10", "Last Active", "MyFitnessPal"),
                    TimelineEvent("2026-10-24", "Last Seen Date", "Google")
                )
            ),
            registrations = emptyList(),
            handleHits = emptyList(),
            breachReport = BreachReport("a@b.com", true, listOf("One", "Two", "Three"))
        )!!

        assertEquals("High", summary.risk)
        assertTrue(summary.bullets.any { it.contains("2023-01-10") && it.contains("2026-10-24") })
    }

    @Test
    fun anEmptyResultIsExplainedRatherThanPresentedAsSafe() {
        val summary = EmailProfileSummaryBuilder.email(
            email = "a@b.com",
            attribution = attribution(),
            registrations = listOf(registration("Hulu")),
            handleHits = emptyList(),
            breachReport = BreachReport("a@b.com", false, emptyList())
        )!!

        assertEquals("Low", summary.risk)
        assertTrue(summary.riskNote.contains("not proof"))
    }

    @Test
    fun handleDefaultsToTheLocalPartOfTheAddress() {
        assertEquals("hillspropertymaintenance258", EmailProfileSummaryBuilder.handleOf("hillspropertymaintenance258@gmail.com"))
        assertEquals("plain", EmailProfileSummaryBuilder.handleOf("plain"))
    }
}
