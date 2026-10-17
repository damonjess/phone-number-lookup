package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class GoogleMapsReview(
    val title: String,
    val address: String,
    val timestamp: String,
    val stars: Int
)

data class GooglePlayGamesProfile(
    val username: String,
    val playerId: String,
    val level: Int,
    val xp: Long,
    val lastPlayedGame: String,
    val lastActive: String,
    val gamesPlayed: Int
)

data class GoogleAccountOsintReport(
    val email: String,
    val gaiaId: String? = null,
    val fullName: String? = null,
    val profilePhotoUrl: String? = null,
    val profileUrl: String? = null,
    val reviews: List<GoogleMapsReview> = emptyList(),
    val playGames: GooglePlayGamesProfile? = null,
    val error: String? = null
) {
    val hasData: Boolean
        get() = gaiaId != null || reviews.isNotEmpty() || playGames != null
}

object GoogleAccountOsint {

    suspend fun scan(email: String): GoogleAccountOsintReport = withContext(Dispatchers.IO) {
        val clean = email.trim().lowercase()
        if (clean.isBlank() || !clean.contains("@")) {
            return@withContext GoogleAccountOsintReport(clean, error = "Enter a valid email address")
        }

        // 1. Query Google People / Calendar / OEmbed profile endpoints publicly exposed for the email
        val profileData = fetchGooglePublicProfile(clean)
        
        // 2. Query Google Maps public contributor feed if a Gaia ID or email hash is resolved
        val reviews = profileData.gaiaId?.let { fetchGoogleMapsReviews(it) } ?: emptyList()

        // 3. Query Google Play Games public profile lookup
        val playGames = fetchGooglePlayGames(clean)

        GoogleAccountOsintReport(
            email = clean,
            gaiaId = profileData.gaiaId,
            fullName = profileData.fullName,
            profilePhotoUrl = profileData.photoUrl,
            profileUrl = profileData.profileUrl,
            reviews = reviews,
            playGames = playGames
        )
    }

    private data class BasicProfile(val gaiaId: String?, val fullName: String?, val photoUrl: String?, val profileUrl: String?)

    private fun fetchGooglePublicProfile(email: String): BasicProfile {
        // Public Google calendar / oembed / sync endpoints expose account existence and Gaia IDs
        val url = "https://contacts.google.com/so/ab/c/u/0/?q=${ScanHttp.encode(email)}"
        val response = ScanHttp.get(url, timeoutMs = 8_000)
        
        // Fallback: Check Google Calendar public availability endpoint
        val calUrl = "https://calendar.google.com/calendar/ical/${ScanHttp.encode(email)}/public/basic.ics"
        val calResp = ScanHttp.get(calUrl, timeoutMs = 6_000)

        var gaiaId: String? = null
        var fullName: String? = null
        val photoUrl: String? = null

        if (response.isOk && response.body.isNotBlank()) {
            val text = response.body
            val idMatch = Regex("""\b1\d{20}\b""").find(text)
            if (idMatch != null) {
                gaiaId = idMatch.value
            }
        }

        // If calendar returns 200 or redirects, the account is confirmed active on Google
        if (calResp.code in 200..399) {
            val text = calResp.body
            // Extract any exposed owner name or ID if present in ICS headers
            if ("BEGIN:VCALENDAR" in text) {
                val ownerLine = text.lineSequence().firstOrNull { it.startsWith("X-WR-CALNAME:") }
                if (ownerLine != null) {
                    fullName = ownerLine.removePrefix("X-WR-CALNAME:").trim()
                }
            }
        }

        return BasicProfile(gaiaId, fullName, photoUrl, gaiaId?.let { "https://www.google.com/maps/contrib/$it" })
    }

    private fun fetchGoogleMapsReviews(gaiaId: String): List<GoogleMapsReview> {
        val url = "https://www.google.com/maps/contrib/$gaiaId/reviews?hl=en&output=json"
        val response = ScanHttp.get(url, timeoutMs = 10_000)
        if (!response.isOk || response.body.isBlank()) return emptyList()

        return runCatching {
            // Parse Google Maps public JSON contributor payload
            val raw = response.body.removePrefix(")]}'").trim()
            val list = mutableListOf<GoogleMapsReview>()
            if (raw.startsWith("[")) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val title = obj.optString("title", "Review")
                    val address = obj.optString("address", "")
                    val time = obj.optString("timestamp", "")
                    val stars = obj.optInt("stars", 5)
                    list.add(GoogleMapsReview(title, address, time, stars))
                }
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun fetchGooglePlayGames(email: String): GooglePlayGamesProfile? {
        // Public Play Games lookup endpoint
        val url = "https://play.google.com/log?format=json&email=${ScanHttp.encode(email)}"
        val response = ScanHttp.get(url, timeoutMs = 8_000)
        if (!response.isOk || response.body.isBlank()) return null

        return runCatching {
            val root = JSONObject(response.body)
            GooglePlayGamesProfile(
                username = root.optString("username", "Unknown"),
                playerId = root.optString("player_id", ""),
                level = root.optInt("level", 1),
                xp = root.optLong("xp", 0L),
                lastPlayedGame = root.optString("last_played", "Unknown"),
                lastActive = root.optString("last_active", "Unknown"),
                gamesPlayed = root.optInt("games_played", 0)
            )
        }.getOrNull()
    }
}
