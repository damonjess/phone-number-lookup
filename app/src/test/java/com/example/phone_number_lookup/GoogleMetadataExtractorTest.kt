package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleMetadataExtractorTest {
    private val email = "person@example.org"
    private val url = "https://calendar.google.com/calendar/ical/person%40example.org/public/basic.ics"

    @Test
    fun readableCalendarFeedIsReportedWithItsPublicCalendarName() {
        val report = GoogleMetadataExtractor.parseCalendarResponse(
            email,
            url,
            HttpResponse(200, "BEGIN:VCALENDAR\nX-WR-CALNAME:Public events\nEND:VCALENDAR", url)
        )

        assertEquals(true, report.publicCalendarFound)
        assertEquals("Public events", report.calendarDisplayName)
        assertEquals(url, report.sourceUrl)
        assertNull(report.error)
    }

    @Test
    fun ordinaryHttpSuccessPageDoesNotConfirmCalendarOrAccount() {
        val report = GoogleMetadataExtractor.parseCalendarResponse(
            email,
            url,
            HttpResponse(200, "<html>Sign in</html>", url)
        )

        assertNull(report.publicCalendarFound)
        assertNull(report.calendarDisplayName)
        assertTrue(report.error?.contains("status is unknown") == true)
    }

    @Test
    fun missingPublicCalendarIsNotReportedAsMissingGoogleAccount() {
        val report = GoogleMetadataExtractor.parseCalendarResponse(
            email,
            url,
            HttpResponse(404, "Not Found", url)
        )

        assertEquals(false, report.publicCalendarFound)
        assertNull(report.error)
    }

    @Test
    fun accessDeniedIsUnknownRatherThanNegativeOrPositive() {
        val report = GoogleMetadataExtractor.parseCalendarResponse(
            email,
            url,
            HttpResponse(403, "Forbidden", url)
        )

        assertNull(report.publicCalendarFound)
        assertTrue(report.error?.contains("status is unknown") == true)
    }
}
