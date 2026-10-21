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

    private const val MAX_SAMPLES = 5

    private val DIGITS_REGEX = Regex("[^0-9]")
    private val CALL_TYPE_REGEX = Regex("call type:\\s*([^\\n|]{2,40})", RegexOption.IGNORE_CASE)
    private val CALLER_REGEX = Regex("caller:\\s*([^\\n|]{2,40})", RegexOption.IGNORE_CASE)

    // Markers for Should I Answer UK (shouldianswer.co.uk)
    private val SIA_SCORE_REGEX = Regex(
        """(negative|neutral|positive)\s+rating|rated\s+as\s+(spam|scam|telemarketer|silent\s+call|debt\s+collector|harassment|nuisance|safe)""",
        RegexOption.IGNORE_CASE
    )
    private val SIA_CATEGORY_REGEX = Regex(
        """(telemarketer|silent\s+call|debt\s+collector|financial\s+service|scam|fraud|survey|non-profit|call\s+centre|unsolicited\s+call)""",
        RegexOption.IGNORE_CASE
    )
    private val SIA_REVIEWS_COUNT_REGEX = Regex(
        """(\d+)\s+(?:user\s+)?(?:reviews?|ratings?|reports?)""",
        RegexOption.IGNORE_CASE
    )

    // Markers for Who Called UK (who-called.co.uk)
    private val WC_REPORTED_AS_REGEX = Regex(
        """most\s+commonly\s+reported\s+as\s+(?:a\s+|an\s+)?([^,.]{2,45})""",
        RegexOption.IGNORE_CASE
    )
    private val WC_REPUTATION_REGEX = Regex(
        """(?:has\s+|user\s+reputation:\s*)(negative|dangerous|harassing|neutral|positive|safe)(?:\s+community\s+rating)?""",
        RegexOption.IGNORE_CASE
    )
    private val WC_COMMENTS_COUNT_REGEX = Regex(
        """(?:based\s+on\s+|with\s+[\d,]+\s+lookups\s+and\s+|number\s+of\s+comments:\s*|comments:\s*)(\d+)\s*(?:user\s+)?(?:reports?|comments?)?""",
        RegexOption.IGNORE_CASE
    )
    private val WC_LOOKUPS_REGEX = Regex(
        """([\d,]+)\s+(?:lookups|searches)""",
        RegexOption.IGNORE_CASE
    )
    private val WC_BREAKDOWN_REGEX = Regex(
        """(Dangerous|Harassing)\s*(\d+)%\s*(\d+)\s*reports?""",
        RegexOption.IGNORE_CASE
    )
    private val WC_ASSIGNED_PROVIDER_REGEX = Regex(
        """(?:assigned\s+to|operator:)\s*([A-Za-z0-9 &.,'-]{3,45}?)(?:\.|First\s+activity|Share\s+Your)""",
        RegexOption.IGNORE_CASE
    )
    private val WC_SIMILAR_NUMBERS_REGEX = Regex(
        """(0\d{9,10})\s*(\d+)\s*reports?\s*·\s*(\d{1,2}\s+[A-Za-z]{3}\s+\d{4})""",
        RegexOption.IGNORE_CASE
    )

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
                "Who Called UK" to {
                    queryWhoCalledUk(ukLocal)
                }
            )
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
            digits.length in 11..12 && digits.startsWith("44") -> listOf(
                "Who Called UK",
                "Should I Answer UK",
                "WhoCallsMe"
            )
            else -> emptyList()
        }
    }

    internal fun queryWhoCalledUk(ukLocal: String): ReputationReport {
        val url = "https://who-called.co.uk/Number/$ukLocal"
        val response = ScanHttp.get(url, timeoutMs = 15_000)
        if (response.isNetworkError) {
            return ReputationReport("Who Called UK", url, false, 0, emptyList(), emptyList(), "Network error")
        }
        if (response.code == 404 || response.code == 410) {
            return ReputationReport("Who Called UK", url, false, 0, emptyList(), emptyList())
        }
        if (!response.isOk) {
            return ReputationReport("Who Called UK", url, false, 0, emptyList(), emptyList(), "HTTP ${response.code}")
        }

        val text = htmlToText(response.body)
        val callTypes = LinkedHashSet<String>()
        val samples = LinkedHashSet<String>()

        WC_REPORTED_AS_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()?.let { topic ->
            if (topic.isNotEmpty()) {
                callTypes.add(topic.replaceFirstChar { it.uppercase() })
                samples.add("Commonly reported as: $topic")
            }
        }

        val reputation = WC_REPUTATION_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()?.lowercase()
        if (reputation == "negative" || reputation == "dangerous" || reputation == "harassing") {
            callTypes.add("${reputation.replaceFirstChar { it.uppercase() }} rating")
        }

        var dangerousOrHarassingCount = 0
        WC_BREAKDOWN_REGEX.findAll(text).forEach { match ->
            val label = match.groupValues[1]
            val pct = match.groupValues[2]
            val cnt = match.groupValues[3].toIntOrNull() ?: 0
            dangerousOrHarassingCount += cnt
            callTypes.add(label.replaceFirstChar { it.uppercase() })
            if (samples.size < MAX_SAMPLES) {
                samples.add("$label: $pct% ($cnt reports)")
            }
        }

        val commentCount = WC_COMMENTS_COUNT_REGEX.findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: 0

        val lookupCount = WC_LOOKUPS_REGEX.find(text)?.groupValues?.getOrNull(1)
            ?.replace(",", "")?.toIntOrNull() ?: 0
        if (lookupCount > 0 && samples.size < MAX_SAMPLES) {
            samples.add("Lookups on Who Called UK: $lookupCount")
        }

        WC_ASSIGNED_PROVIDER_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()?.let { provider ->
            if (provider.isNotEmpty() && samples.size < MAX_SAMPLES) {
                samples.add("Allocated operator: $provider")
            }
        }

        // Detect prefix-neighbor spoofing waves (e.g., other 07666xxxxx numbers reported recently)
        val prefix5 = ukLocal.take(5)
        val similarMatches = WC_SIMILAR_NUMBERS_REGEX.findAll(text)
            .filter { it.groupValues[1] != ukLocal && it.groupValues[1].startsWith(prefix5) }
            .toList()
        if (similarMatches.isNotEmpty() && samples.size < MAX_SAMPLES) {
            val preview = similarMatches.take(2).joinToString(", ") {
                "${it.groupValues[1]} (${it.groupValues[2]} rpts)"
            }
            samples.add("Active prefix wave ($prefix5…): $preview")
            if (commentCount == 0 && similarMatches.size >= 2) {
                callTypes.add("Active prefix spoofing wave ($prefix5…)")
            }
        }

        val totalReports = maxOf(commentCount, dangerousOrHarassingCount)
        val hasReports = totalReports > 0 ||
            reputation in setOf("negative", "dangerous", "harassing") ||
            similarMatches.size >= 2

        return ReputationReport(
            source = "Who Called UK",
            url = url,
            hasReports = hasReports,
            reportCount = if (totalReports > 0) totalReports else if (hasReports) maxOf(1, similarMatches.size) else 0,
            callTypes = callTypes.toList(),
            samples = samples.toList()
        )
    }

    internal fun queryShouldIAnswerUk(ukLocal: String): ReputationReport {
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
