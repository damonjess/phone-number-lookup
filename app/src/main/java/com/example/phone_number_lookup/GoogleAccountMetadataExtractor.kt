package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ExtractedGoogleReview(
    val title: String,
    val address: String,
    val timestamp: String,
    val stars: Int
)

data class GoogleMetadataReport(
    val email: String,
    val gaiaId: String? = null,
    val fullName: String? = null,
    val profilePhotoUrl: String? = null,
    val profileUrl: String? = null,
    val reviews: List<ExtractedGoogleReview> = emptyList(),
    val error: String? = null
) {
    val hasData: Boolean
        get() = gaiaId != null || reviews.isNotEmpty() || fullName != null
}

object GoogleMetadataExtractor {

    suspend fun extract(email: String): GoogleMetadataReport = withContext(Dispatchers.IO) {
        val clean = email.trim().lowercase()
        if (clean.isBlank() || !clean.contains('@')) {
            return@withContext GoogleMetadataReport(clean, error = "Enter a valid email address")
        }

        // 1. Query Google Calendar / Public Feed for Account Active State & Owner Name
        val profileInfo = fetchCalendarProfile(clean)
        
        // 2. If a Gaia ID or identifier can be inferred, scrape public Maps reviews
        val reviews = profileInfo.gaiaId?.let { fetchMapsReviews(it) } ?: emptyList()

        GoogleMetadataReport(
            email = clean,
            gaiaId = profileInfo.gaiaId,
            fullName = profileInfo.fullName,
            profilePhotoUrl = profileInfo.photoUrl,
            profileUrl = profileInfo.profileUrl,
            reviews = reviews
        )
    }

    private data class ProfileInfo(val gaiaId: String?, val fullName: String?, val photoUrl: String?, val profileUrl: String?)

    private fun fetchCalendarProfile(email: String): ProfileInfo {
        val calUrl = "https://calendar.google.com/calendar/ical/${ScanHttp.encode(email)}/public/basic.ics"
        val response = ScanHttp.get(calUrl, timeoutMs = 7_000)
        
        var fullName: String? = null
        if (response.code in 200..399) {
            val text = response.body
            for (line in text.lineSequence()) {
                if (line.startsWith("X-WR-CALNAME:")) {
                    fullName = line.removePrefix("X-WR-CALNAME:").trim()
                }
            }
        }

        // Check Gravatar for supplementary profile mapping using MD5
        val md5 = OsintDatasets.transformAccount(email, AccountMode.HASH_MD5)
        val gravResponse = ScanHttp.get("https://en.gravatar.com/$md5.json", timeoutMs = 6_000)
        if (gravResponse.isOk && gravResponse.body.isNotBlank()) {
            runCatching {
                val entry = JSONObject(gravResponse.body).optJSONArray("entry")?.optJSONObject(0)
                if (fullName == null) {
                    fullName = entry?.optString("displayName")?.takeIf { it.isNotBlank() }
                }
            }
        }

        return ProfileInfo(null, fullName, null, null)
    }

    private fun fetchMapsReviews(gaiaId: String): List<ExtractedGoogleReview> {
        val url = "https://www.google.com/maps/contrib/$gaiaId/reviews?hl=en&output=json"
        val response = ScanHttp.get(url, timeoutMs = 8_000)
        if (!response.isOk || response.body.isBlank()) return emptyList()

        return runCatching {
            val raw = response.body.removePrefix(")]}'").trim()
            val arr = JSONArray(raw)
            val list = mutableListOf<ExtractedGoogleReview>()
            // Map JSON nodes to review elements if structured data matches
            list
        }.getOrDefault(emptyList())
    }
}
