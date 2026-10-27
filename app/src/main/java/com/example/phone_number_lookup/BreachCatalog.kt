package com.example.phone_number_lookup

/**
 * One breach record with the metadata a report shows for it: when it happened, which domain it
 * belonged to, how many accounts it exposed and what kinds of data it contained.
 */
data class BreachDetail(
    val name: String,
    val domain: String? = null,
    /** ISO date (yyyy-MM-dd) as published by the catalogue. */
    val breachDate: String? = null,
    val pwnCount: Long = 0L,
    val description: String = "",
    val dataClasses: List<String> = emptyList(),
    val logoUrl: String? = null,
    val verified: Boolean = false
)

/**
 * Enriches the breach names returned for an address with the free, unauthenticated HIBP breach
 * catalogue (the same catalogue browsers use for breach descriptions). The email lookup itself is
 * not free there, so this only annotates breaches that the app's own checks already returned.
 */
object BreachCatalog {

    private const val CATALOGUE_URL = "https://haveibeenpwned.com/api/v3/breaches"

    @Volatile
    private var cachedBody: String? = null

    fun lookup(breachNames: List<String>): List<BreachDetail> {
        if (breachNames.isEmpty()) return emptyList()
        val body = catalogue() ?: return emptyList()
        return parse(body, breachNames)
    }

    private fun catalogue(): String? {
        cachedBody?.let { return it }
        synchronized(this) {
            cachedBody?.let { return it }
            val response = ScanHttp.get(
                CATALOGUE_URL,
                timeoutMs = 20_000,
                headers = mapOf("Accept" to "application/json"),
                maxBodyChars = 3_000_000
            )
            if (!response.isOk || response.body.isBlank()) return null
            cachedBody = response.body
            return response.body
        }
    }

    /**
     * Matches the names returned by the breach checks against the catalogue. Reported names arrive
     * as "Name (2026 • Email addresses)" from the analytics feed, so the label before the bracket is
     * used for matching.
     */
    internal fun parse(body: String, breachNames: List<String>): List<BreachDetail> {
        val wanted = breachNames
            .filterNot { it.startsWith("Infostealer Log", ignoreCase = true) }
            .map { normalize(it.substringBefore('(')) }
            .filter { it.isNotBlank() }
            .toSet()
        if (wanted.isEmpty()) return emptyList()

        val matches = mutableListOf<BreachDetail>()
        runCatching {
            val entries = org.json.JSONArray(body)
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val name = entry.optString("Title").ifBlank { entry.optString("Name") }
                val domain = entry.optString("Domain").takeIf { it.isNotBlank() }
                if (!isWanted(wanted, name, domain, entry.optString("Name"))) continue
                matches += BreachDetail(
                    name = name.ifBlank { domain.orEmpty() },
                    domain = domain,
                    breachDate = entry.optString("BreachDate").takeIf { it.isNotBlank() },
                    pwnCount = entry.optLong("PwnCount", 0L),
                    description = plainText(entry.optString("Description")),
                    dataClasses = entry.optJSONArray("DataClasses")?.let { classes ->
                        (0 until classes.length()).mapNotNull { c ->
                            classes.optString(c).takeIf { it.isNotBlank() }
                        }
                    }.orEmpty(),
                    logoUrl = entry.optString("LogoPath").takeIf { it.startsWith("http") },
                    verified = entry.optBoolean("IsVerified", false)
                )
            }
        }
        return matches
            .distinctBy { it.name.lowercase() }
            .sortedByDescending { it.breachDate.orEmpty() }
    }

    private fun isWanted(wanted: Set<String>, title: String, domain: String?, name: String): Boolean {
        val candidates = listOf(title, name, domain.orEmpty())
            .map { normalize(it) }
            .filter { it.isNotBlank() }
        return candidates.any { candidate -> candidate in wanted || wanted.any { it == candidate } }
    }

    private fun normalize(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    /** Breach descriptions are HTML with links; the app shows them as plain sentences. */
    internal fun plainText(html: String): String {
        if (html.isBlank()) return ""
        val withoutTags = html
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?s)<[^>]+>"), " ")
        val unescaped = withoutTags
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        return unescaped.replace(Regex("\\s+"), " ").trim()
    }
}
