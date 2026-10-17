package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Publicly observable calendar data; this does not confirm that an email owns a Google account. */
data class GoogleMetadataReport(
    val email: String,
    val publicCalendarFound: Boolean? = null,
    val calendarDisplayName: String? = null,
    val sourceUrl: String? = null,
    val error: String? = null
) {
    val hasData: Boolean
        get() = publicCalendarFound == true || !calendarDisplayName.isNullOrBlank()
}

object GoogleMetadataExtractor {
    suspend fun extract(email: String): GoogleMetadataReport = withContext(Dispatchers.IO) {
        val clean = email.trim().lowercase()
        if (!EMAIL_PATTERN.matches(clean)) {
            return@withContext GoogleMetadataReport(clean, error = "Enter a valid email address")
        }

        val url = "https://calendar.google.com/calendar/ical/${ScanHttp.encode(clean)}/public/basic.ics"
        val response = ScanHttp.get(url, timeoutMs = 7_000)
        parseCalendarResponse(clean, url, response)
    }

    internal fun parseCalendarResponse(
        email: String,
        url: String,
        response: HttpResponse
    ): GoogleMetadataReport {
        val isCalendarFeed = response.code in 200..299 &&
            response.body.contains("BEGIN:VCALENDAR", ignoreCase = true)

        val publicCalendarFound = when {
            isCalendarFeed -> true
            response.code == 404 || response.code == 410 -> false
            else -> null
        }
        val calendarName = if (isCalendarFeed) {
            response.body.lineSequence()
                .firstOrNull { it.startsWith("X-WR-CALNAME:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val error = when {
            publicCalendarFound != null -> null
            response.isNetworkError -> "Google's public calendar endpoint could not be reached; status is unknown."
            response.code == 401 || response.code == 403 || response.code == 429 ->
                "Google did not allow this request (HTTP ${response.code}); status is unknown."
            response.code in 200..299 ->
                "The response was not a readable public calendar; status is unknown."
            else -> "Google returned HTTP ${response.code}; this does not establish whether an account exists."
        }

        return GoogleMetadataReport(
            email = email,
            publicCalendarFound = publicCalendarFound,
            calendarDisplayName = calendarName,
            sourceUrl = url.takeIf { publicCalendarFound == true },
            error = error
        )
    }

    private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
}
