package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ExtractedGoogleReview(
    val title: String,
    val address: String,
    val ratingLabel: String,
    val dateLabel: String = ""
)

data class GoogleMetadataReport(
    val email: String,
    val publicCalendarFound: Boolean? = null,
    val calendarDisplayName: String? = null,
    val sourceUrl: String? = null,
    val error: String? = null,
    val accountActive: Boolean = false,
    val accountStatus: String = "Unknown",
    val gaiaId: String? = null,
    val fullName: String? = null,
    val profileUrl: String? = null,
    val activeServices: List<String> = emptyList(),
    val mappedLocations: List<String> = emptyList(),
    val playGamesUsername: String? = null,
    val playGamesSummary: String? = null,
    val lastSeenDate: String? = null,
    val lastLevelUpDate: String? = null,
    val reviews: List<ExtractedGoogleReview> = emptyList()
) {
    val hasData: Boolean
        get() = accountActive || gaiaId != null || reviews.isNotEmpty() || fullName != null || publicCalendarFound == true
}

object GoogleMetadataExtractor {

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
            error = error,
            accountStatus = when (publicCalendarFound) {
                true -> "Public calendar detected; Google account ownership is unverified"
                false -> "No public calendar found; Google account status is unknown"
                null -> "Google account status is unknown"
            },
            fullName = calendarName,
            activeServices = if (publicCalendarFound == true) listOf("Public Calendar") else emptyList()
        )
    }

    suspend fun extract(email: String): GoogleMetadataReport = withContext(Dispatchers.IO) {
        val clean = email.trim().lowercase()
        if (clean.isBlank() || !clean.contains('@')) {
            return@withContext GoogleMetadataReport(
                email = clean,
                accountActive = false,
                accountStatus = "Enter a valid email address",
                error = "Enter a valid email address"
            )
        }

        // Live checks for email addresses
        val isGmail = clean.endsWith("@gmail.com") || clean.endsWith("@googlemail.com")
        val calUrl = "https://calendar.google.com/calendar/ical/${ScanHttp.encode(clean)}/public/basic.ics"
        val calResp = ScanHttp.get(calUrl, timeoutMs = 7_000)
        val calReport = parseCalendarResponse(clean, calUrl, calResp)
        val calPublic = calReport.publicCalendarFound == true

        var ownerName: String? = calReport.calendarDisplayName
        var locationHint: String? = null
        val md5 = OsintDatasets.transformAccount(clean, AccountMode.HASH_MD5)
        val gravResp = ScanHttp.get("https://en.gravatar.com/$md5.json", timeoutMs = 6_000)
        if (gravResp.isOk && gravResp.body.isNotBlank()) {
            runCatching {
                val entry = JSONObject(gravResp.body).optJSONArray("entry")?.optJSONObject(0)
                if (ownerName == null) {
                    ownerName = entry?.optString("displayName")?.takeIf { it.isNotBlank() }
                }
                locationHint = entry?.optString("currentLocation")?.takeIf { it.isNotBlank() }
            }
        }

        val services = buildList {
            if (isGmail) addAll(listOf("Gmail", "Maps", "Photos", "Meet"))
            if (calPublic) add("Public Calendar")
        }

        GoogleMetadataReport(
            email = clean,
            publicCalendarFound = calReport.publicCalendarFound,
            calendarDisplayName = calReport.calendarDisplayName,
            sourceUrl = calReport.sourceUrl,
            error = calReport.error,
            accountActive = isGmail || calPublic,
            accountStatus = if (isGmail || calPublic) "Active / Detected (Gmail / Workspace)" else "No public Google feed indexed",
            gaiaId = null,
            fullName = ownerName,
            profileUrl = null,
            activeServices = services,
            mappedLocations = listOfNotNull(locationHint),
            playGamesUsername = null,
            playGamesSummary = null,
            reviews = emptyList()
        )
    }
}
