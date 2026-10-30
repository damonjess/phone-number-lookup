package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

data class DirectoryReportSummary(
    val source: String,
    val url: String,
    val verdictLabel: String? = null,
    val reportCount: String? = null,
    val callerCategory: String? = null,
    val userComments: List<String> = emptyList()
)

data class DeepPhoneOsintReport(
    val e164: String,
    val directoryReports: List<DirectoryReportSummary> = emptyList(),
    val businessMatches: List<AttributedValue> = emptyList(),
    val discoveredEmails: List<AttributedValue> = emptyList(),
    val discoveredHandles: List<AttributedValue> = emptyList(),
    val extraWebResults: List<SearchResultItem> = emptyList()
)

object PhoneDirectoryScraper {

    private val TAG_STRIP = Regex("<[^>]*>")
    private val WS_CLEAN = Regex("\\s+")

    suspend fun scrapePhoneFootprint(normalized: String): DeepPhoneOsintReport = withContext(Dispatchers.IO) {
        val parsed = DorkNumbers.from(normalized)
        val e164 = parsed?.number?.e164 ?: normalized
        val rawLocal = parsed?.number?.rawLocal ?: normalized.filter { it.isDigit() }
        val spellings = parsed?.number?.spellings ?: listOf(normalized, rawLocal).distinct()

        coroutineScope {
            val whoCalledDef = async { scrapeWhoCalledUk(rawLocal) }
            val shouldIAnswerDef = async { scrapeShouldIAnswerUk(rawLocal) }
            val whoCallsMeDef = async { scrapeWhoCallsMe(rawLocal) }
            val webDef = async {
                val queries = listOf(
                    spellings.joinToString(" OR ") { "\"$it\"" },
                    "\"$rawLocal\" OR \"$e164\" (scam OR spam OR caller OR contact OR email)"
                )
                OpenWebOsintScraper.scrapeAll(queries, targetPhoneE164 = e164)
            }

            val directories = listOfNotNull(
                whoCalledDef.await(),
                shouldIAnswerDef.await(),
                whoCallsMeDef.await()
            )
            val webReport = webDef.await()

            DeepPhoneOsintReport(
                e164 = e164,
                directoryReports = directories,
                businessMatches = webReport.businessMatches,
                discoveredEmails = webReport.extractedEmails,
                discoveredHandles = webReport.extractedHandles,
                extraWebResults = webReport.results
            )
        }
    }

    internal fun scrapeWhoCalledUk(rawLocal: String): DirectoryReportSummary? {
        val digits = rawLocal.filter { it.isDigit() }
        if (digits.length < 9) return null
        val url = "https://who-called.co.uk/Number/$digits"
        val resp = ScanHttp.get(url, timeoutMs = 10_000)
        if (!resp.isOk || resp.body.isBlank()) return null
        val html = resp.body

        val rateRegex = Regex("""(?is)overall\s+rating\s+is\s*<[^>]*>([^<]+)</""")
        val searchesRegex = Regex("""(?is)Number\s+of\s+searches[:\s]*</[^>]+>\s*<[^>]+>(\d+)""")
        val commentRegex = Regex("""(?is)<div[^>]*class=["'][^"']*commentText[^"']*["'][^>]*>(.*?)</div>""")

        val verdict = rateRegex.find(html)?.groupValues?.get(1)?.cleanHtml()
        val searches = searchesRegex.find(html)?.groupValues?.get(1)?.cleanHtml()
        val comments = commentRegex.findAll(html)
            .map { it.groupValues[1].cleanHtml() }
            .filter { it.length > 12 }
            .take(5)
            .toList()

        if (verdict == null && comments.isEmpty()) return null
        return DirectoryReportSummary(
            source = "Who-Called.co.uk",
            url = url,
            verdictLabel = verdict,
            reportCount = searches?.let { "$it searches" },
            userComments = comments
        )
    }

    internal fun scrapeShouldIAnswerUk(rawLocal: String): DirectoryReportSummary? {
        val digits = rawLocal.filter { it.isDigit() }
        if (digits.length < 9) return null
        val url = "https://www.shouldianswer.co.uk/phone-number/$digits"
        val resp = ScanHttp.get(url, timeoutMs = 10_000)
        if (!resp.isOk || resp.body.isBlank()) return null
        val html = resp.body

        val scoreRegex = Regex("""(?is)<div[^>]*class=["'][^"']*score\s+(?:negative|positive|neutral)[^"']*["'][^>]*>(.*?)</div>""")
        val categoryRegex = Regex("""(?is)<span[^>]*class=["'][^"']*category[^"']*["'][^>]*>(.*?)</span>""")
        val reviewRegex = Regex("""(?is)<p[^>]*itemprop=["']reviewBody["'][^>]*>(.*?)</p>""")

        val verdict = scoreRegex.find(html)?.groupValues?.get(1)?.cleanHtml()
        val cat = categoryRegex.find(html)?.groupValues?.get(1)?.cleanHtml()
        val reviews = reviewRegex.findAll(html)
            .map { it.groupValues[1].cleanHtml() }
            .filter { it.length > 10 }
            .take(5)
            .toList()

        if (verdict == null && reviews.isEmpty() && cat == null) return null
        return DirectoryReportSummary(
            source = "Should I Answer UK",
            url = url,
            verdictLabel = verdict,
            callerCategory = cat,
            userComments = reviews
        )
    }

    internal fun scrapeWhoCallsMe(rawLocal: String): DirectoryReportSummary? {
        val digits = rawLocal.filter { it.isDigit() }
        if (digits.length < 9) return null
        val url = "https://whocallsme.com/Phone-Number.aspx/$digits"
        val resp = ScanHttp.get(url, timeoutMs = 10_000)
        if (!resp.isOk || resp.body.isBlank()) return null
        val html = resp.body

        val postRegex = Regex("""(?is)<div[^>]*class=["'][^"']*oos_pBody[^"']*["'][^>]*>(.*?)</div>""")
        val comments = postRegex.findAll(html)
            .map { it.groupValues[1].cleanHtml() }
            .filter { it.length > 15 }
            .take(5)
            .toList()

        if (comments.isEmpty()) return null
        return DirectoryReportSummary(
            source = "WhoCallsMe",
            url = url,
            reportCount = "${comments.size}+ community posts",
            userComments = comments
        )
    }

    private fun String.cleanHtml(): String =
        BreachCatalog.plainText(replace(TAG_STRIP, " ")).replace(WS_CLEAN, " ").trim()
}
