package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Real, live spam / caller reputation lookup against the tellows crowd-sourced
 * call database. tellows exposes a JSON endpoint that needs no API key:
 *
 *   https://www.tellows.co.uk/basic/num/{E164}?json=1   (UK)
 *   https://www.tellows.de/basic/num/{E164}?json=1      (rest of world)
 *
 * When the JSON feed has missing metadata (such as caller name/company or operator),
 * we supplement it with a lightweight parse of the public tellows number page.
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
    val error: String? = null,
    val checkedAt: Long = System.currentTimeMillis(),
    val callerName: String? = null,
    val operatorHint: String? = null,
    val latestActivity: String? = null,
) {
    /** True when the community has actually submitted reports for this number. */
    val hasCommunityReports: Boolean
        get() = (comments ?: 0) > 0

    /** True when many people are searching the number even if no one has left a comment yet. */
    val hasSearchSpike: Boolean
        get() = (searches ?: 0) >= 15

    /**
     * Human readable verdict derived from the tellows score (1..9).
     *
     * tellows reports a score of 5 as its default when nothing has been submitted yet,
     * so a 5 with zero community comments is an *unrated* number, not a suspicious one.
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

    /** Score for display, flagged when it is only the tellows placeholder value. */
    val scoreLabel: String
        get() = when {
            score == null -> "-"
            hasCommunityReports -> "$score / 9"
            else -> "$score / 9 (tellows default - not yet rated)"
        }

    val confidenceLabel: String
        get() = when {
            hasCommunityReports && (comments ?: 0) >= 3 -> "Moderate — multiple community reports"
            hasCommunityReports -> "Low — limited community reports"
            hasSearchSpike -> "Emerging — high lookup velocity (${searches} searches)"
            else -> "None — no report evidence"
        }

    val searchVelocityNote: String?
        get() {
            val count = searches ?: return null
            return when {
                !hasCommunityReports && count >= 15 ->
                    "Searched $count times with 0 written comments — often indicates a live spoofing wave or unlisted outbound switchboard."
                count >= 50 ->
                    "High lookup volume ($count searches)"
                else -> null
            }
        }
}

object SpamScanner {
    private data class Cached(val report: SpamReport, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private const val CACHE_MS = 24L * 60L * 60L * 1000L

    private val HTML_TAG_REGEX = Regex("<[^>]+>")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val HTML_CALLER_TYPE_REGEX = Regex(
        """(?:Types?\s+of\s+call|Caller\s+type)\s*:\s*([^<\n\r|]{2,60})""",
        RegexOption.IGNORE_CASE
    )
    private val HTML_CALLER_NAME_REGEX = Regex(
        """(?:Caller\s+name|Reported\s+name|Caller\s+ID)\s*:\s*([^<\n\r|]{2,60})""",
        RegexOption.IGNORE_CASE
    )
    private val HTML_SEARCHES_REGEX = Regex(
        """(?:Search\s+requests|Searches)\s*:\s*(\d+)""",
        RegexOption.IGNORE_CASE
    )
    private val HTML_OPERATOR_REGEX = Regex(
        """(?:Operator|Network|Carrier)\s*:\s*([^<\n\r|]{2,60})""",
        RegexOption.IGNORE_CASE
    )
    private val HTML_LAST_ACTIVITY_REGEX = Regex(
        """(?:Last\s+comment|Latest\s+search|Last\s+activity)\s*:\s*([^<\n\r|]{3,40})""",
        RegexOption.IGNORE_CASE
    )

    suspend fun lookup(normalizedNumber: String): SpamReport = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache[normalizedNumber]?.takeIf { it.expiresAt > now }?.let { return@withContext it.report }
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
            if (parsed != null) {
                val enriched = if (parsed.callerType == null || parsed.callerName == null) {
                    val htmlResp = ScanHttp.get("$host/num/$encoded", timeoutMs = 10_000)
                    if (htmlResp.isOk && htmlResp.body.isNotBlank()) {
                        enrichFromHtml(parsed, htmlResp.body)
                    } else {
                        parsed
                    }
                } else {
                    parsed
                }
                cache[normalizedNumber] = Cached(enriched, now + CACHE_MS)
                return@withContext enriched
            }
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
        ).also { report ->
            // A network/service error must be retried next time, not hidden for 24 hours.
            if (report.error == null) cache[normalizedNumber] = Cached(report, now + CACHE_MS)
        }
    }

    internal fun parseReport(body: String, normalizedNumber: String, host: String): SpamReport? {
        return try {
            val root = JSONObject(body)
            val tellows = root.optJSONObject("tellows") ?: return null
            val callerTypesObj = tellows.optJSONObject("callerTypes")?.optJSONArray("caller")
            val firstJsonType = callerTypesObj?.optJSONObject(0)?.optString("name")
                ?.takeIf { it.isNotBlank() && it != "null" }

            SpamReport(
                source = if (host.endsWith("co.uk")) "tellows.co.uk" else "tellows.de",
                number = tellows.optString("number", normalizedNumber),
                score = tellows.optString("score").takeIf { it.isNotEmpty() }?.toIntOrNull(),
                searches = tellows.optString("searches").takeIf { it.isNotEmpty() }?.toIntOrNull(),
                comments = tellows.optString("comments").takeIf { it.isNotEmpty() }?.toIntOrNull(),
                location = tellows.optString("location").takeIf { it.isNotBlank() && it != "null" },
                country = tellows.optString("country").takeIf { it.isNotBlank() && it != "null" },
                callerType = tellows.optString("callerType").takeIf { it.isNotBlank() && it != "null" }
                    ?: firstJsonType,
                callerName = tellows.optString("callerName").takeIf { it.isNotBlank() && it != "null" }
            )
        } catch (e: Exception) {
            null
        }
    }

    internal fun enrichFromHtml(base: SpamReport, html: String): SpamReport {
        val text = html.replace(HTML_TAG_REGEX, " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace(WHITESPACE_REGEX, " ")

        val htmlType = HTML_CALLER_TYPE_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
        val htmlName = HTML_CALLER_NAME_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
        val htmlSearches = HTML_SEARCHES_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val htmlOperator = HTML_OPERATOR_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() }
        val htmlActivity = HTML_LAST_ACTIVITY_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() }

        return base.copy(
            callerType = base.callerType ?: htmlType,
            callerName = base.callerName ?: htmlName,
            searches = base.searches ?: htmlSearches,
            operatorHint = base.operatorHint ?: htmlOperator,
            latestActivity = base.latestActivity ?: htmlActivity
        )
    }
}
