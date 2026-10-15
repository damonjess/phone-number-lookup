package com.example.phone_number_lookup

/**
 * Why a site check gave no usable answer. The old code lumped all of these together and the screen
 * called every one of them "blocked", including plain timeouts and pages that simply had no
 * profile marker. Telling them apart shows what can be retried and what needs a browser.
 */
enum class ProbeFailure {
    /** Timed out, DNS failure, connection reset. Usually temporary. */
    NETWORK,

    /** HTTP 429. Slow down and try again. */
    RATE_LIMITED,

    /** HTTP 401/403 with no bot-check page. Sometimes clears with a different browser identity. */
    FORBIDDEN,

    /** A bot-check page (Cloudflare, CAPTCHA...). The app does not try to get past these. */
    CHALLENGE,

    /** HTTP 5xx. Usually temporary. */
    SERVER_ERROR,

    /** Any other unexpected HTTP answer. */
    HTTP_ERROR,

    /** The page loaded but held neither a "found" nor a "missing" marker. */
    INCONCLUSIVE,
}

/** One site the scan could not check automatically. [profileUrl] lets the user check it by hand. */
data class SiteIssue(
    val site: String,
    val profileUrl: String,
    val reason: ProbeFailure,
    val detail: String = "",
) {
    /** Worth another automatic attempt. Bot checks and "no clear answer" give the same result again. */
    val retryable: Boolean
        get() = reason == ProbeFailure.NETWORK || reason == ProbeFailure.RATE_LIMITED ||
            reason == ProbeFailure.SERVER_ERROR || reason == ProbeFailure.FORBIDDEN
}

object ProbeFailures {

    /** Page text that only appears on bot-check pages, whatever the HTTP status. */
    private val STRONG_MARKERS = listOf(
        "cf-chl", "challenge-platform", "just a moment", "attention required! | cloudflare",
        "px-captcha", "datadome",
    )

    /** Wording that can also appear on ordinary pages, so it only counts alongside a refusal status. */
    private val WEAK_MARKERS = listOf(
        "captcha", "are you a robot", "unusual traffic", "verify you are human", "access denied",
    )

    private const val SCAN_CHARS = 60_000

    fun classify(code: Int, body: String, networkError: Boolean): ProbeFailure {
        if (networkError || code == -1) return ProbeFailure.NETWORK

        val text = body.take(SCAN_CHARS).lowercase()
        val refusal = code == 401 || code == 403 || code == 429 || code == 503
        val challenge = STRONG_MARKERS.any { it in text } || (refusal && WEAK_MARKERS.any { it in text })
        return when {
            challenge -> ProbeFailure.CHALLENGE
            code == 429 -> ProbeFailure.RATE_LIMITED
            code == 401 || code == 403 -> ProbeFailure.FORBIDDEN
            code in 500..599 -> ProbeFailure.SERVER_ERROR
            code in 200..299 -> ProbeFailure.INCONCLUSIVE
            else -> ProbeFailure.HTTP_ERROR
        }
    }

    /** "41 sites couldn't be checked automatically: 18 blocked by bot protection, 12 timed out, ..." */
    fun summarize(issues: List<SiteIssue>): String {
        if (issues.isEmpty()) return ""
        fun count(vararg reasons: ProbeFailure) = issues.count { it.reason in reasons }

        val parts = listOf(
            count(ProbeFailure.FORBIDDEN, ProbeFailure.CHALLENGE) to "blocked by bot protection",
            count(ProbeFailure.RATE_LIMITED) to "rate-limited",
            count(ProbeFailure.NETWORK) to "timed out or couldn't connect",
            count(ProbeFailure.SERVER_ERROR) to "had server errors",
            count(ProbeFailure.HTTP_ERROR) to "gave unexpected errors",
            count(ProbeFailure.INCONCLUSIVE) to "gave no clear answer",
        ).filter { it.first > 0 }.joinToString(", ") { "${it.first} ${it.second}" }

        val n = issues.size
        return "$n ${if (n == 1) "site" else "sites"} couldn't be checked automatically: $parts."
    }
}
