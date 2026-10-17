package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

data class ReputationReport(
    val source: String,
    val url: String,
    val hasReports: Boolean,
    val reportCount: Int,
    val callTypes: List<String>,
    val samples: List<String>,
    val error: String? = null,
)

object PhoneReputation {

    private const val MAX_SAMPLES = 4

    private val DIGITS_REGEX = Regex("[^0-9]")
    private val CALL_TYPE_REGEX = Regex("call type:\\s*([^\\n|]{2,40})", RegexOption.IGNORE_CASE)
    private val CALLER_REGEX = Regex("caller:\\s*([^\\n|]{2,40})", RegexOption.IGNORE_CASE)

    // Markers for Should I Answer UK (shouldianswer.co.uk)
    private val SIA_SCORE_REGEX = Regex(
        """(negative|neutral|positive)\s+rating|rated\s+as\s+(spam|scam|telemarketer|silent\s+call|debt\s+collector|harassment| nuisance|safe)""",
        RegexOption.IGNORE_CASE
    )
    private val SIA_CATEGORY_REGEX = Regex(
        """(telemarketer|silent\s+call|debt\s+collector|financial\s+service|scam|fraud|survey|non-profit|call\s+centre|unsolicited\s+call)""",
        RegexOption.IGNORE_CASE
    )
    private val SIA_REVIEWS_COUNT_REGEX = Regex("""(\d+)\s+(?:user\s+)?(?:reviews?|ratings?|reports?)""", RegexOption.IGNORE_CASE)

    private val SCRIPT_REGEX = Regex("<script.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val STYLE_REGEX = Regex("<style.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val COMMENT_REGEX = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val TAG_REGEX = Regex("<[^>]+>")
    private val WHITESPACE_REGEX = Regex("\\s+")

    suspend fun lookup(e164: String): List<ReputationReport> = withContext(Dispatchers.IO) {
        val digits = e164.replace(DIGITS_REGEX, "")
        val northAmerica = digits.length == 11 && digits.startsWith("1")
        val uk = digits.length in 11..12 && digits.startsWith("44")

        val jobs = ArrayList<Pair<String, () -> ReputationReport>>()
        if (northAmerica) {
            val national = digits.substring(1)
            val dashed = "1-${national.substring(0, 3)}-${national.substring(3, 6)}-${national.substring(6)}"
            jobs.add(
                "WhoCallsMe" to {
                    queryHtml(
                        source = "WhoCallsMe",
                        url = "https://whocallsme.com/Phone-Number.aspx/$national",
                    )
                }
            )
            jobs.add(
                "800notes" to {
                    queryHtml(
                        source = "800notes",
                        url = "https://800notes.com/Phone.aspx/$dashed",
                    )
                }
            )
        } else if (uk) {
            val ukLocal = "0" + digits.removePrefix("44")
            jobs.add(
                "Should I Answer UK" to {
                    queryShouldIAnswerUk(ukLocal)
                }
            )
            jobs.add(
                "WhoCallsMe" to {
                    queryHtml(
                        source = "WhoCallsMe",
                        url = "https://whocallsme.com/Phone-Number.aspx/$ukLocal",
                    )
                }
            )
        }

        jobs.map { (_, job) -> async { job() } }.awaitAll()
    }

    fun supportedSources(e164: String): List<String> {
        val digits = e164.replace(DIGITS_REGEX, "")
        return when {
            digits.length == 11 && digits.startsWith("1") -> listOf("WhoCallsMe", "800notes")
            digits.length in 11..12 && digits.startsWith("44") -> listOf("Should I Answer UK", "WhoCallsMe")
            else -> emptyList()
        }
    }

    private fun queryShouldIAnswerUk(ukLocal: String): ReputationReport {
        val url = "https://www.shouldianswer.co.uk/phone-number/$ukLocal"
        val response = ScanHttp.get(url, timeoutMs = 15_000)
        if (response.isNetworkError) {
            return ReputationReport("Should I Answer UK", url, false, 0, emptyList(), emptyList(), "Network error")
        }
        if (response.code == 404 || response.code == 410) {
            return ReputationReport("Should I Answer UK", url, false, 0, emptyList(), emptyList())
        }
        if (!response.isOk) {
            return ReputationReport("Should I Answer UK", url, false, 0, emptyList(), emptyList(), "HTTP ${response.code}")
        }

        val text = htmlToText(response.body)
        val categories = SIA_CATEGORY_REGEX.findAll(text)
            .map { it.value.trim().lowercase().replaceFirstChar { c -> c.uppercase() } }
            .distinct()
            .toList()

        val scoreMatches = SIA_SCORE_REGEX.findAll(text)
            .map { it.value.trim() }
            .distinct()
            .toList()

        val countFromText = SIA_REVIEWS_COUNT_REGEX.findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: 0

        val hasNegativeOrCategory = countFromText > 0 ||
            scoreMatches.any { it.contains("negative", true) || it.contains("spam", true) || it.contains("scam", true) }

        if (!hasNegativeOrCategory) {
            return ReputationReport("Should I Answer UK", url, false, 0, emptyList(), emptyList())
        }

        val reportCount = maxOf(countFromText, categories.size, scoreMatches.size)
        val samples = LinkedHashSet<String>()
        scoreMatches.forEach { if (samples.size < MAX_SAMPLES) samples.add("Rating: $it") }
        categories.forEach { if (samples.size < MAX_SAMPLES) samples.add("Category: $it") }

        return ReputationReport(
            source = "Should I Answer UK",
            url = url,
            hasReports = true,
            reportCount = reportCount,
            callTypes = categories.ifEmpty { scoreMatches },
            samples = samples.toList()
        )
    }

    private fun queryHtml(source: String, url: String): ReputationReport {
        val response = ScanHttp.get(url, timeoutMs = 20_000)
        if (response.isNetworkError) {
            return ReputationReport(source, url, false, 0, emptyList(), emptyList(), "Network error")
        }
        if (response.code == 404 || response.code == 410) {
            return ReputationReport(source, url, false, 0, emptyList(), emptyList())
        }
        if (!response.isOk) {
            return ReputationReport(
                source, url, false, 0, emptyList(), emptyList(),
                "HTTP ${response.code}",
            )
        }

        val text = htmlToText(response.body)
        val callTypes = CALL_TYPE_REGEX
            .findAll(text)
            .map { it.groupValues[1].trim().trimEnd('.', ',', ' ') }
            .filter { it.isNotEmpty() }
            .toList()

        val callers = CALLER_REGEX
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
            samples = samples.toList(),
        )
    }

    internal fun htmlToText(html: String): String {
        var text = SCRIPT_REGEX.replace(html, " ")
        text = STYLE_REGEX.replace(text, " ")
        text = COMMENT_REGEX.replace(text, " ")
        text = TAG_REGEX.replace(text, " ")
        text = text.replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&#39;", "'").replace("&quot;", "\"").replace("&apos;", "'")
        return WHITESPACE_REGEX.replace(text, " ")
    }
}
