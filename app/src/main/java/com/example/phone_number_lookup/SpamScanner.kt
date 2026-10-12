package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Real, live spam / caller reputation lookup against the tellows crowd-sourced
 * call database. tellows exposes a JSON endpoint that needs no API key:
 *
 *   https://www.tellows.co.uk/basic/num/{E164}?json=1   (UK)
 *   https://www.tellows.de/basic/num/{E164}?json=1      (rest of world)
 *
 * tellows scores run 1 (trusted) to 9 (dangerous spam). We also surface the
 * number of community comments and the reported location when available.
 */
data class SpamReport(
    val source: String,
    val number: String,
    val score: Int?,
    val searches: Int?,
    val comments: Int?,
    val location: String?,
    val country: String?,
    val callerType: String?,
    val error: String? = null
) {
    /**
     * Human readable verdict derived from the tellows score (1..9).
     *
     * tellows reports a score of 5 as its default when nothing has been submitted yet,
     * so a 5 with zero community comments is an *unrated* number, not a suspicious one.
     * Banding the raw score in that case would turn "no data" into a warning, so the
     * unrated case is called out explicitly.
     */
    val riskLabel: String
        get() = when {
            score == null -> "No rating yet"
            hasCommunityReports.not() -> "Unrated - no community reports yet"
            score in 1..2 -> "Trusted (low risk)"
            score in 3..4 -> "Probably legitimate"
            score in 5..6 -> "Suspicious / possible spam"
            score in 7..8 -> "Likely spam"
            score == 9 -> "Dangerous spam / high risk"
            else -> "Unknown"
        }

    /** True when the community has actually submitted reports for this number. */
    val hasCommunityReports: Boolean
        get() = (comments ?: 0) > 0

    /** Score for display, flagged when it is only the tellows placeholder value. */
    val scoreLabel: String
        get() = when {
            score == null -> "-"
            hasCommunityReports -> "$score / 9"
            else -> "$score / 9 (tellows default - not yet rated)"
        }
}

object SpamScanner {

    suspend fun lookup(normalizedNumber: String): SpamReport = withContext(Dispatchers.IO) {
        val digits = normalizedNumber.replace(Regex("[^0-9]"), "")
        val e164 = "+$digits"
        val encoded = java.net.URLEncoder.encode(e164, "UTF-8")

        // UK numbers are best served by tellows.co.uk; everything else falls back to .de
        val hosts = if (normalizedNumber.startsWith("+44")) {
            listOf("https://www.tellows.co.uk", "https://www.tellows.de")
        } else {
            listOf("https://www.tellows.de")
        }

        var lastError: String? = "No response from tellows"
        for (host in hosts) {
            val url = "$host/basic/num/$encoded?json=1"
            val response = ScanHttp.get(url, timeoutMs = 12_000)
            if (response.isNetworkError) {
                lastError = "Network error contacting tellows"
                continue
            }
            if (response.body.isBlank()) {
                lastError = "Empty response from tellows"
                continue
            }

            val parsed = parseReport(response.body, normalizedNumber, host)
            if (parsed != null) return@withContext parsed
            lastError = "No rating found in tellows response"
        }

        SpamReport(
            source = "tellows",
            number = e164,
            score = null,
            searches = null,
            comments = null,
            location = null,
            country = null,
            callerType = null,
            error = lastError
        )
    }

    internal fun parseReport(body: String, normalizedNumber: String, host: String): SpamReport? {
        return try {
            val root = JSONObject(body)
            val tellows = root.optJSONObject("tellows") ?: return null
            SpamReport(
                source = if (host.endsWith("co.uk")) "tellows.co.uk" else "tellows.de",
                number = tellows.optString("number", normalizedNumber),
                score = tellows.optString("score").takeIf { it.isNotEmpty() }?.toIntOrNull(),
                searches = tellows.optString("searches").takeIf { it.isNotEmpty() }?.toIntOrNull(),
                comments = tellows.optString("comments").takeIf { it.isNotEmpty() }?.toIntOrNull(),
                location = tellows.optString("location").takeIf { it.isNotBlank() && it != "null" },
                country = tellows.optString("country").takeIf { it.isNotBlank() && it != "null" },
                callerType = tellows.optString("callerType").takeIf { it.isNotBlank() && it != "null" }
            )
        } catch (e: Exception) {
            null
        }
    }
}
