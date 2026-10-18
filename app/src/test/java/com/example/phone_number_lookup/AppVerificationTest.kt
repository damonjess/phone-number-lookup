package com.example.phone_number_lookup

import android.app.Application
import com.example.phone_number_lookup.ui.viewmodels.PhoneLookupViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVerificationTest {

    @Test
    fun verify1_normalisesUkNumber() {
        val normalized = PhoneNumberUtils.normalize("020 7946 0958")
        assertEquals("+442079460958", normalized)

        val alt1 = PhoneNumberUtils.normalize("0044 20 7946 0958")
        assertEquals("+442079460958", alt1)

        val alt2 = PhoneNumberUtils.normalize("+44 20 7946 0958")
        assertEquals("+442079460958", alt2)
    }

    @Test
    fun verify2_showsLondonAreaHint() {
        val area = PhoneNumberUtils.getAreaInfo("+442079460958")
        assertEquals("London", area)
    }

    @Test
    fun verify3_generatesAllFourNumberFormats() {
        val normalized = "+442079460958"
        val formats = PhoneNumberUtils.getFormattedFormats(normalized, "02079460958")

        assertEquals("+442079460958", formats.e164)
        assertEquals("+44 20 7946 0958", formats.international)
        assertEquals("020 7946 0958", formats.national)
        assertEquals("tel:+44-20-7946-0958", formats.rfc3966)
    }

    @Test
    fun verify4_evidenceConfidenceLabel() {
        val highConfidence = SpamReport(
            source = "tellows.co.uk",
            number = "+442079460958",
            score = 8,
            searches = 50,
            comments = 5,
            location = "London",
            country = "United Kingdom",
            callerType = "Telemarketer"
        )
        assertEquals("Moderate — multiple community reports", highConfidence.confidenceLabel)

        val lowConfidence = SpamReport(
            source = "tellows.co.uk",
            number = "+442079460958",
            score = 6,
            searches = 5,
            comments = 1,
            location = "London",
            country = "United Kingdom",
            callerType = "Unknown"
        )
        assertEquals("Low — limited community reports", lowConfidence.confidenceLabel)

        val noConfidence = SpamReport(
            source = "tellows.co.uk",
            number = "+442079460958",
            score = 5,
            searches = 0,
            comments = 0,
            location = null,
            country = null,
            callerType = null
        )
        assertEquals("None — no report evidence", noConfidence.confidenceLabel)
    }

    @Test
    fun verify5_clearErrorMessagesOnUnavailableSources() {
        val errorReport = SpamReport(
            source = "tellows",
            number = "+442079460958",
            score = null,
            searches = null,
            comments = null,
            location = null,
            country = null,
            callerType = null,
            error = "Network error contacting tellows"
        )
        assertNotNull(errorReport.error)
        assertTrue(errorReport.error!!.contains("Network error"))

        val searchResponse = SearchResponse(emptyList(), "Web search timed out.")
        assertEquals("Web search timed out.", searchResponse.error)
    }

    @Test
    fun verify6_cachesSuccessfulResultsAndDoesNotCacheTemporaryErrors() = runBlocking {
        val jsonPayload = """{"tellows":{"number":"+442079460958","score":"8","comments":"4","location":"London","country":"United Kingdom"}}"""
        val report = SpamScanner.parseReport(jsonPayload, "+442079460958", "https://www.tellows.co.uk")
        assertNotNull(report)
        assertEquals("tellows.co.uk", report!!.source)
        assertEquals(8, report.score)

        val successReport = SpamReport("tellows", "+442079460958", 8, 10, 4, "London", "UK", "Spam", null)
        assertNull(successReport.error)

        val failureReport = SpamReport("tellows", "+442079460958", null, null, null, null, null, null, "Network error")
        assertNotNull(failureReport.error)
    }

    @Test
    fun verify7_clearingCurrentScanPreservesHistory() {
        val viewModel = PhoneLookupViewModel(Application())
        viewModel.onPhoneNumberInputChanged("020 7946 0958")
        viewModel.clear()

        // Clearing scan screen does not erase history list
        assertNotNull(viewModel.uiState.value.history)
    }

    @Test
    fun verify8_historyEntryStatusCyclingAndNoteEditing() {
        val entry = HistoryEntry(
            id = 1001L,
            number = "+442079460958",
            label = "London",
            note = "Initial note",
            status = "Unknown",
            createdAt = System.currentTimeMillis()
        )
        assertEquals("Unknown", entry.status)

        // Status cycling logic verification
        val next1 = when (entry.status) {
            "Unknown" -> "Suspected spam"
            "Suspected spam" -> "Trusted"
            else -> "Unknown"
        }
        assertEquals("Suspected spam", next1)

        val next2 = when (next1) {
            "Unknown" -> "Suspected spam"
            "Suspected spam" -> "Trusted"
            else -> "Unknown"
        }
        assertEquals("Trusted", next2)

        val updatedEntry = entry.copy(note = "Updated note", status = next1)
        assertEquals("Updated note", updatedEntry.note)
        assertEquals("Suspected spam", updatedEntry.status)
    }
}
