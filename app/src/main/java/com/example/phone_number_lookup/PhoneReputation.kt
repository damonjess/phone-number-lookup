package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * Extra caller-reputation sources that return real per-number data over plain HTTP,
 * no API key, no JavaScript — the community databases that sites like who-called.co.uk
 * and the PhoneInfoga "reputation" links point at.
 *
 * Verified live before being written here:
 *   - WhoCallsMe  HTTP 200 with user reports for a reported number, 404 when clean.
 *     Reports carry `Caller:` and `Call type:` lines.
 *   - 800notes    HTTP 200 with reports for a reported number, 404 when clean.
 *
 * Deliberately NOT included, after testing:
 *   - spamcalls.net answered HTTP 410 for every number tried (clean and known-spam alike),
 *     so it advertises no usable per-number data to a non-browser client.
 *   - who-called.co.uk returned 403 to automated requests from this environment.
 *
 * WhoCallsMe and 800notes only index North American numbers, so they are queried for
 * +1 numbers and reported as unsupported otherwise rather than silently returning nothing.
 */
data class ReputationReport(
    val source: String,
    val url: String,
    /** True when the source actually holds reports for this number. */
    val hasReports: Boolean,
    val reportCount: Int,
    val callTypes: List<String>,
    /** Short excerpts from the most recent user reports. */
    val samples: List<String>,
    val error: String? = null
)

object PhoneReputation {

    private const val MAX_SAMPLES = 4

    /** Queries every supported source in parallel and returns one row per source. */
    suspend fun lookup(e164: String): List<ReputationReport> = withContext(Dispatchers.IO) {
        val digits = e164.replace(Regex("[^0-9]"), "")
        val northAmerica = digits.length == 11 && digits.startsWith("1")

        val jobs = ArrayList<Pair<String, () -> ReputationReport>>()
        if (northAmerica) {
            val national = digits.substring(1)              // 8554208313
            val dashed = "1-${national.substring(0, 3)}-${national.substring(3, 6)}-${national.substring(6)}"
            jobs.add("WhoCallsMe" to {
                queryHtml(
                    source = "WhoCallsMe",
                    url = "https://whocallsme.com/Phone-Number.aspx/$national"
                )
            })
            jobs.add("800notes" to {
                queryHtml(
                    source = "800notes",
                    url = "https://800notes.com/Phone.aspx/$dashed"
                )
            })
        }

        jobs.map { (_, job) -> async { job() } }.awaitAll()
    }

    /** Sources that are applicable to [e164], for labelling the UI. */
    fun supportedSources(e164: String): List<String> {
        val digits = e164.replace(Regex("[^0-9]"), "")
        return if (digits.length == 11 && digits.startsWith("1")) {
            listOf("WhoCallsMe", "800notes")
        } else {
            emptyList()
        }
    }

    private fun queryHtml(source: String, url: String): ReputationReport {
        val response = ScanHttp.get(url, timeoutMs = 20_000)
        if (response.isNetworkError) {
            return ReputationReport(source, url, false, 0, emptyList(), emptyList(), "Network error")
        }
        if (response.code == 404 || response.code == 410) {
            // The site explicitly has no thread for this number.
            return ReputationReport(source, url, false, 0, emptyList(), emptyList())
        }
        if (!response.isOk) {
            return ReputationReport(
                source, url, false, 0, emptyList(), emptyList(),
                "HTTP ${response.code}"
            )
        }

        val text = htmlToText(response.body)
        val callTypes = Regex("call type:\\s*([^\\n|]{2,40})", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.groupValues[1].trim().trimEnd('.', ',', ' ') }
            .filter { it.isNotEmpty() }
            .toList()

        val callers = Regex("caller:\\s*([^\\n|]{2,40})", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() && !it.equals("unknown", true) }
            .toList()

        val samples = LinkedHashSet<String>()
        callers.forEach { caller -> 
            if (samples.size < MAX_SAMPLES) samples.add("Caller: $caller")
        }
        callTypes.forEach { type ->
            if (samples.size < MAX_SAMPLES) samples.add("Reported as: $type")
        }

        return ReputationReport(
            source = source,
            url = url,
            hasReports = callTypes.isNotEmpty() || callers.isNotEmpty(),
            reportCount = maxOf(callTypes.size, callers.size),
            callTypes = callTypes.distinct(),
            samples = samples.toList()
        )
    }

    /** Strips scripts/styles/tags so the report text can be pattern-matched. */
    internal fun htmlToText(html: String): String {
        var text = Regex("<script.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .replace(html, " ")
        text = Regex("<style.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .replace(text, " ")
        text = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(text, " ")
        text = Regex("<[^>]+>").replace(text, " ")
        text = text.replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&#39;", "'").replace("&quot;", "\"").replace("&apos;", "'")
        return Regex("\\s+").replace(text, " ")
    }
}
