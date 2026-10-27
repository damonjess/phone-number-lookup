package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class BreachReport(
    val email: String,
    val breached: Boolean,
    val breachNames: List<String>,
    val error: String? = null,
    val checkedAt: Long = System.currentTimeMillis(),
    /** Catalogue metadata (date, domain, size, description) for the named breaches. */
    val details: List<BreachDetail> = emptyList()
)

object EmailScanner {

    suspend fun check(email: String): BreachReport = withContext(Dispatchers.IO) {
        val clean = email.trim()
        if (clean.isEmpty() || !clean.contains("@")) {
            return@withContext BreachReport(clean, false, emptyList(), "Enter a valid email address")
        }

        val encoded = URLEncoder.encode(clean, "UTF-8")

        val basicDeferred = async {
            ScanHttp.get("https://api.xposedornot.com/v1/check-email/$encoded", timeoutMs = 15_000)
        }
        val analyticsDeferred = async {
            ScanHttp.get("https://api.xposedornot.com/v1/breach-analytics?email=$encoded", timeoutMs = 15_000)
        }
        val hudsonRockDeferred = async {
            checkHudsonRockStealers(encoded)
        }

        val response = basicDeferred.await()
        val analyticsResponse = analyticsDeferred.await()
        val stealerFindings = hudsonRockDeferred.await()

        if (response.isNetworkError && stealerFindings.isEmpty()) {
            return@withContext BreachReport(clean, false, emptyList(), "Network error contacting breach databases")
        }
        if (response.code == 429 && stealerFindings.isEmpty()) {
            return@withContext BreachReport(clean, false, emptyList(), "XposedOrNot rate limit reached. Wait a minute and try again.")
        }
        if (response.code >= 500 && stealerFindings.isEmpty()) {
            return@withContext BreachReport(clean, false, emptyList(), "XposedOrNot is having problems (HTTP ${response.code}). Try again later.")
        }

        val baseReport = if (response.code == 404 || response.body.isBlank()) {
            BreachReport(clean, false, emptyList())
        } else {
            parseBreach(clean, response.body)
        }

        val detailedDetails = if (analyticsResponse.isOk && analyticsResponse.body.isNotBlank()) {
            parseDetailedAnalytics(analyticsResponse.body)
        } else {
            emptyMap()
        }

        val enrichedList = baseReport.breachNames.map { name ->
            detailedDetails[name.lowercase()] ?: name
        } + stealerFindings

        val finalNames = enrichedList.distinct()
        BreachReport(
            email = clean,
            breached = finalNames.isNotEmpty(),
            breachNames = finalNames,
            error = if (finalNames.isEmpty()) baseReport.error else null,
            details = BreachCatalog.lookup(finalNames)
        )
    }

    /** Extracts breach year and exposed data classes from XposedOrNot's breach-analytics response. */
    private fun parseDetailedAnalytics(body: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        runCatching {
            val root = JSONObject(body)
            val details = root.optJSONObject("ExposedBreaches")?.optJSONArray("breaches_details") ?: return emptyMap()
            for (i in 0 until details.length()) {
                val item = details.optJSONObject(i) ?: continue
                val breach = item.optString("breach").takeIf { it.isNotBlank() } ?: continue
                val year = item.optString("xposed_date").takeIf { it.isNotBlank() }
                val data = item.optString("xposed_data").replace(";", ", ").takeIf { it.isNotBlank() }
                val summary = buildString {
                    append(breach)
                    if (year != null || data != null) {
                        append(" (")
                        if (year != null) append(year)
                        if (year != null && data != null) append(" • ")
                        if (data != null) append(data)
                        append(")")
                    }
                }
                map[breach.lowercase()] = summary
            }
        }
        return map
    }

    /** Queries HudsonRock's free Cavalier OSINT endpoint for infostealer infection records. */
    private fun checkHudsonRockStealers(encodedEmail: String): List<String> {
        val response = ScanHttp.get(
            "https://cavalier.hudsonrock.com/api/json/v2/osint-tools/search-by-email?email=$encodedEmail",
            timeoutMs = 10_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!response.isOk || response.body.isBlank()) return emptyList()
        return runCatching {
            val stealers = JSONObject(response.body).optJSONArray("stealers") ?: return emptyList()
            (0 until stealers.length()).mapNotNull { i ->
                val s = stealers.optJSONObject(i) ?: return@mapNotNull null
                val date = s.optString("date_compromised").substringBefore('T').takeIf { it.isNotBlank() } ?: "unknown date"
                val os = s.optString("operating_system").takeIf { it.isNotBlank() }
                "Infostealer Log (HudsonRock • $date${if (os != null) " • $os" else ""})"
            }
        }.getOrDefault(emptyList())
    }

    internal fun parseBreach(email: String, body: String): BreachReport {
        return try {
            val root = JSONObject(body)
            if (root.has("Error")) {
                return BreachReport(email, false, emptyList())
            }
            val breaches = root.optJSONArray("breaches")
            val names = mutableListOf<String>()
            if (breaches != null) {
                for (i in 0 until breaches.length()) {
                    val inner: JSONArray? = breaches.optJSONArray(i)
                    if (inner != null) {
                        for (j in 0 until inner.length()) {
                            inner.optString(j).takeIf { it.isNotBlank() }?.let { names.add(it) }
                        }
                    } else {
                        breaches.optString(i).takeIf { it.isNotBlank() }?.let { names.add(it) }
                    }
                }
            }
            BreachReport(email, names.isNotEmpty(), names.distinct())
        } catch (_: Exception) {
            BreachReport(email, false, emptyList(), "Could not parse XposedOrNot response")
        }
    }
}
