package com.example.phone_number_lookup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleMetadataExtractorTest {

    @Test
    fun targetEmailReturnsCompleteProfileData() = runBlocking {
        val report = GoogleMetadataExtractor.extract("alex.smith@gmail.com")

        assertEquals("alex.smith@gmail.com", report.email)
        assertTrue(report.accountActive)
        assertEquals("Active / Detected", report.accountStatus)
        assertEquals("100000000000000000000", report.gaiaId)
        assertEquals("Alex Smith", report.fullName)
        assertEquals("https://www.google.com/maps/contrib/100000000000000000000", report.profileUrl)
        assertEquals(4, report.activeServices.size)
        assertEquals(4, report.mappedLocations.size)
        assertTrue(report.playGamesSummary?.contains("Level 10") == true)
        assertEquals(4, report.reviews.size)
        assertTrue(report.hasData)
    }

    @Test
    fun standardGmailReturnsBasicActiveReport() = runBlocking {
        val report = GoogleMetadataExtractor.extract("someone@gmail.com")

        assertEquals("someone@gmail.com", report.email)
        assertTrue(report.accountActive)
        assertEquals("Active / Detected (Gmail)", report.accountStatus)
        assertEquals(listOf("Gmail", "Maps", "Photos"), report.activeServices)
    }
}
