package com.example.phone_number_lookup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GoogleMetadataExtractorTest {

    @Test
    fun validEmailReturnsTruthfulNoIndexReport() = runBlocking {
        val report = GoogleMetadataExtractor.extract("user@example.com")

        assertEquals("user@example.com", report.email)
        assertFalse(report.accountActive)
        assertEquals("No public index found without direct Gaia ID", report.accountStatus)
        assertFalse(report.hasData)
    }

    @Test
    fun gmailAddressReturnsTruthfulNoIndexReport() = runBlocking {
        val report = GoogleMetadataExtractor.extract("alex.smith@gmail.com")

        assertEquals("alex.smith@gmail.com", report.email)
        assertFalse(report.accountActive)
        assertEquals("No public index found without direct Gaia ID", report.accountStatus)
        assertFalse(report.hasData)
    }

    @Test
    fun invalidEmailReturnsInvalidStatus() = runBlocking {
        val report = GoogleMetadataExtractor.extract("notanemail")

        assertEquals("notanemail", report.email)
        assertFalse(report.accountActive)
        assertEquals("Invalid email format", report.accountStatus)
    }
}
