package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ExtractedGoogleReview(
    val title: String,
    val address: String,
    val ratingLabel: String
)

data class GoogleMetadataReport(
    val email: String,
    val accountActive: Boolean = false,
    val accountStatus: String = "Unknown",
    val gaiaId: String? = null,
    val fullName: String? = null,
    val profileUrl: String? = null,
    val activeServices: List<String> = emptyList(),
    val mappedLocations: List<String> = emptyList(),
    val playGamesSummary: String? = null,
    val reviews: List<ExtractedGoogleReview> = emptyList(),
    val error: String? = null,
    val playGamesUsername: String? = null,
    val lastSeenDate: String? = null,
    val lastLevelUpDate: String? = null
) {
    val hasData: Boolean
        get() = accountActive || gaiaId != null || reviews.isNotEmpty() || mappedLocations.isNotEmpty() || fullName != null
}

object GoogleMetadataExtractor {

    private val TITLE_REGEX = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TIMEZONE_REGEX = Regex("""ctz=([^&"']+)""", RegexOption.IGNORE_CASE)

    suspend fun extract(email: String): GoogleMetadataReport = withContext(Dispatchers.IO) {
        val clean = email.trim().lowercase()
        if (clean.isBlank() || !clean.contains("@")) {
            return@withContext GoogleMetadataReport(
                email = clean,
                accountActive = false,
                accountStatus = "Invalid email format"
            )
        }

        var active = false
        var fullName: String? = null
        val locations = mutableListOf<String>()
        val reviews = mutableListOf<ExtractedGoogleReview>()

        // 1. Check Google Calendar Public Embed
        val calUrl = "https://calendar.google.com/calendar/htmlembed?src=${ScanHttp.encode(clean)}"
        val calResp = ScanHttp.get(calUrl, timeoutMs = 8_000)
        if (calResp.isOk && calResp.body.isNotBlank()) {
            if (!calResp.body.contains("Google Calendar - No Access", ignoreCase = true) &&
                !calResp.body.contains("URL not found", ignoreCase = true)
            ) {
                active = true
                val titleMatch = TITLE_REGEX.find(calResp.body)?.groupValues?.get(1)?.trim()
                if (!titleMatch.isNullOrBlank() && !titleMatch.equals("Google Calendar", ignoreCase = true)) {
                    fullName = titleMatch.removeSuffix("'s Calendar").removeSuffix(" Calendar").trim()
                }
                val tzMatch = TIMEZONE_REGEX.find(calResp.body)?.groupValues?.get(1)
                if (!tzMatch.isNullOrBlank()) {
                    val cleanTz = java.net.URLDecoder.decode(tzMatch, "UTF-8").replace('_', ' ')
                    locations += "Timezone: $cleanTz"
                }
            }
        }

        // 2. Query Google Maps public profile / reviews if indexed
        val mapsUrl = "https://www.google.com/maps/contrib/${ScanHttp.encode(clean)}"
        val mapsResp = ScanHttp.get(mapsUrl, timeoutMs = 8_000)
        if (mapsResp.isOk && mapsResp.body.isNotBlank()) {
            active = true
            val titleMatch = TITLE_REGEX.find(mapsResp.body)?.groupValues?.get(1)?.trim()
            if (!titleMatch.isNullOrBlank() && titleMatch.contains("- Google Maps")) {
                val extractedName = titleMatch.substringBefore("- Google Maps").trim()
                if (extractedName.isNotBlank() && fullName == null) {
                    fullName = extractedName
                }
            }
        }

        val hasGoogleData = fullName != null || locations.isNotEmpty() || reviews.isNotEmpty()
        GoogleMetadataReport(
            email = clean,
            accountActive = active && hasGoogleData,
            accountStatus = if (active && hasGoogleData) "Active Google Account" else "No public index found without direct Gaia ID",
            fullName = fullName,
            profileUrl = if (active && hasGoogleData) "https://www.google.com/maps/contrib/${ScanHttp.encode(clean)}" else null,
            mappedLocations = locations.distinct(),
            reviews = reviews
        )
    }
}
