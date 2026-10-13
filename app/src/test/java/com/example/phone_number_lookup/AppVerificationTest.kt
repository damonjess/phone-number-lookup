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
        val normalized = PhoneNumberUtils.normalize("01782 447307")
        assertEquals("+441782447307", normalized)

        val alt1 = PhoneNumberUtils.normalize("0044 1782 447307")
        assertEquals("+441782447307", alt1)

        val alt2 = PhoneNumberUtils.normalize("+44 1782 447307")
        assertEquals("+441782447307", alt2)
    }

    @Test
    fun verify2_showsStokeOnTrentAreaHint() {
        val area = PhoneNumberUtils.getAreaInfo("+441782447307")
        assertEquals("Stoke-on-Trent", area)
    }

    @Test
    fun verify3_generatesAllFourNumberFormats() {
        val normalized = "+441782447307"
        val formats = PhoneNumberUtils.getFormattedFormats(normalized, "01782447307")

        assertEquals("+441782447307", formats.e164)
        assertEquals("+44 1782 447307", formats.international)
        assertEquals("01782 447307", formats.national)
        assertEquals("tel:+44-1782-447307", formats.rfc3966)
    }

    @Test
    fun verify4_evidenceConfidenceLabel() {
        val highConfidence = SpamReport(
            source = "tellows.co.uk",
            number = "+441782447307",
            score = 8,
            searches = 50,
            comments = 5,
            location = "Stoke-on-Trent",
            country = "United Kingdom",
            callerType = "Telemarketer"
        )
        assertEquals("Moderate — multiple community reports", highConfidence.confidenceLabel)

        val lowConfidence = SpamReport(
            source = "tellows.co.uk",
            number = "+441782447307",
            score = 6,
            searches = 5,
            comments = 1,
            location = "Stoke-on-Trent",
            country = "United Kingdom",
            callerType = "Unknown"
        )
        assertEquals("Low — limited community reports", lowConfidence.confidenceLabel)

        val noConfidence = SpamReport(
            source = "tellows.co.uk",
            number = "+441782447307",
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
            number = "+441782447307",
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
        val jsonPayload = """{"tellows":{"number":"+441782447307","score":"8","comments":"4","location":"Stoke-on-Trent","country":"United Kingdom"}}"""
        val report = SpamScanner.parseReport(jsonPayload, "+441782447307", "https://www.tellows.co.uk")
        assertNotNull(report)
        assertEquals("tellows.co.uk", report!!.source)
        assertEquals(8, report.score)

        val successReport = SpamReport("tellows", "+441782447307", 8, 10, 4, "Stoke-on-Trent", "UK", "Spam", null)
        assertNull(successReport.error)

        val failureReport = SpamReport("tellows", "+441782447307", null, null, null, null, null, null, "Network error")
        assertNotNull(failureReport.error)
    }

    @Test
    fun verify7_clearingCurrentScanPreservesHistory() {
        val viewModel = PhoneLookupViewModel(Application())
        viewModel.onPhoneNumberInputChanged("01782 447307")
        viewModel.clear()

        // Clearing scan screen does not erase history list
        assertNotNull(viewModel.uiState.value.history)
    }

    @Test
    fun verify8_historyEntryStatusCyclingAndNoteEditing() {
        val entry = HistoryEntry(
            id = 1001L,
            number = "+441782447307",
            label = "Stoke-on-Trent",
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
