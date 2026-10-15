package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Tests every phone-lookup source once and says what state it is in, so a silent failure
 * (blocked, site changed, server down) can't pass as "nothing found".
 *
 * "OK" means the source answered in the format the app expects. It does not mean the test number
 * had any reports.
 */
enum class HealthState { OK, BLOCKED, CHANGED, UNREACHABLE, NOT_CONFIGURED }

data class SourceHealthResult(
    val source: String,
    val state: HealthState,
    val detail: String,
    val millis: Long,
)

object SourceHealth {

    // Harmless test numbers: a London number from Ofcom's reserved drama range and a US 555 number.
    private const val UK_TEST = "+442079460958"
    private const val US_TEST_DIGITS = "2125550123"

    internal fun classifyHttp(code: Int, formatOk: Boolean): HealthState = when {
        code == -1 -> HealthState.UNREACHABLE
        code == 202 || code == 401 || code == 403 || code == 429 -> HealthState.BLOCKED
        code in 200..299 -> if (formatOk) HealthState.OK else HealthState.CHANGED
        code in 500..599 -> HealthState.UNREACHABLE
        else -> HealthState.CHANGED // 404, 410, redirects: the endpoint moved or was removed
    }

    internal fun describe(state: HealthState, code: Int): String = when (state) {
        HealthState.OK -> "Answered in the expected format."
        HealthState.BLOCKED -> "Blocked or rate-limited (HTTP $code)."
        HealthState.CHANGED -> "Answered, but not in the expected format (HTTP $code). The site may have changed."
        HealthState.UNREACHABLE -> if (code == -1) "No response (network error or timeout)." else "Server error (HTTP $code)."
        HealthState.NOT_CONFIGURED -> "Not set up."
    }

    suspend fun checkAll(phoneInfogaUrl: String, numverifyKey: String): List<SourceHealthResult> = coroutineScope {
        listOf(
            async { timed("tellows") { checkTellows() } },
            async { timed("WhoCallsMe (US/CA)") { checkHtml("https://whocallsme.com/Phone-Number.aspx/$US_TEST_DIGITS") } },
            async { timed("800notes (US/CA)") { checkHtml("https://800notes.com/Phone.aspx/1-212-555-0123") } },
            async { timed("DuckDuckGo web search") { checkDuckDuckGo() } },
            async { timed("PhoneInfoga server") { checkPhoneInfoga(phoneInfogaUrl) } },
            async { timed("OVH Telecom API (built-in scanner)") { checkOvh() } },
            async { timed("Numverify") { checkNumverify(numverifyKey) } },
        ).awaitAll()
    }

    private suspend fun timed(source: String, block: suspend () -> Pair<HealthState, String>): SourceHealthResult =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val (state, detail) = try {
                block()
            } catch (e: Exception) {
                HealthState.UNREACHABLE to "Check failed: ${e.message ?: e::class.java.simpleName}"
            }
            SourceHealthResult(source, state, detail, System.currentTimeMillis() - start)
        }

    private fun checkTellows(): Pair<HealthState, String> {
        val response = ScanHttp.get("https://www.tellows.co.uk/basic/num/%2B442079460958?json=1", timeoutMs = 12_000)
        val parsed = SpamScanner.parseReport(response.body, UK_TEST, "www.tellows.co.uk")
        val state = classifyHttp(response.code, formatOk = parsed?.score != null)
        return state to describe(state, response.code)
    }

    /** WhoCallsMe / 800notes: a 404 simply means "no page for this number", which is normal. */
    private fun checkHtml(url: String): Pair<HealthState, String> {
        val response = ScanHttp.get(url, timeoutMs = 15_000)
        if (response.code == 404 || response.code == 410) {
            return HealthState.OK to "Reachable (no report page for the test number, which is normal)."
        }
        val pageHasNumber = response.body.filter { it.isDigit() }.contains(US_TEST_DIGITS)
        val state = classifyHttp(response.code, formatOk = pageHasNumber)
        return state to describe(state, response.code)
    }

    private suspend fun checkDuckDuckGo(): Pair<HealthState, String> = when (val outcome = SearchRepository.probe()) {
        is DdgOutcome.Results -> HealthState.OK to "Returned results."
        DdgOutcome.NoResults -> HealthState.CHANGED to "Said \"no results\" for a very common query, which is unexpected."
        is DdgOutcome.Blocked -> HealthState.BLOCKED to "Bot check (${outcome.reason}). Web results are unavailable until it clears."
        is DdgOutcome.Unrecognised -> HealthState.CHANGED to
            "Page not recognised (${outcome.reason})." +
            if (outcome.excerpt.isNotEmpty()) " Page began: ${outcome.excerpt}." else ""
        DdgOutcome.Unreachable -> HealthState.UNREACHABLE to "No response (network error or timeout)."
    }

    private suspend fun checkPhoneInfoga(rawUrl: String): Pair<HealthState, String> {
        if (rawUrl.isBlank()) return HealthState.NOT_CONFIGURED to "No server address entered."
        val base = PhoneInfogaClient.normaliseBaseUrl(rawUrl)
            ?: return HealthState.NOT_CONFIGURED to "The server address doesn't look valid."
        val result = PhoneInfogaClient.listScanners(base)
        if (result.error == null) {
            return HealthState.OK to "${result.scanners.size} scanners: ${result.scanners.joinToString { it.name }}."
        }
        // Nothing listening is normal when no server has been set up: the built-in scanners take over.
        if (result.httpCode == -1) {
            return HealthState.NOT_CONFIGURED to
                "No PhoneInfoga server reachable at $base. The built-in scanners are used instead."
        }
        val state = classifyHttp(result.httpCode, formatOk = false)
        return state to result.error
    }

    /** Reads only the start of OVH's range list, enough to confirm it answers in the expected format. */
    private fun checkOvh(): Pair<HealthState, String> {
        val response = ScanHttp.get(
            "https://api.ovh.com/1.0/telephony/number/detailedZones?country=gb",
            timeoutMs = 20_000,
            maxBodyChars = 200_000
        )
        val state = classifyHttp(response.code, formatOk = response.body.trimStart().startsWith("["))
        return state to describe(state, response.code)
    }

    private suspend fun checkNumverify(key: String): Pair<HealthState, String> {
        if (key.isBlank()) return HealthState.NOT_CONFIGURED to "No API key entered."
        val result = PhoneInfogaNative.queryNumverify(UK_TEST, key)
        val error = result.error ?: return HealthState.OK to "Accepted the key and answered."
        val lower = error.lowercase()
        val state = when {
            "rejected" in lower || "rate" in lower || "quota" in lower || "limit" in lower -> HealthState.BLOCKED
            "network" in lower || "timed out" in lower || "connect" in lower || "unavailable" in lower -> HealthState.UNREACHABLE
            else -> HealthState.CHANGED
        }
        return state to error
    }
}
