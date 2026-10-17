package com.example.phone_number_lookup

import kotlinx.coroutines.delay

/**
 * One site check with a single sensible retry for failures that are usually temporary.
 *
 *  - timeouts / connection errors: try once more with a longer timeout
 *  - 429 and 5xx: wait a moment, try once more
 *  - a plain 401/403: try once more with a desktop-browser User-Agent
 *
 * Bot-check pages (Cloudflare, CAPTCHA) are NOT worked around: they are reported as such so the
 * profile can be opened in a normal browser instead.
 */
object ResilientProbe {

    private const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

    suspend fun evaluate(
        probe: SiteProbe,
        account: String,
        timeoutMs: Int = 10_000,
        call: (SiteProbe, String, Int) -> ProbeResult = { p, a, t -> OsintDatasets.evaluate(p, a, t) },
        pause: suspend (Long) -> Unit = { delay(it) },
    ): ProbeResult {
        val first = call(probe, account, timeoutMs)
        if (first.verdict != ProbeVerdict.UNKNOWN) return first

        return when (first.failure) {
            ProbeFailure.NETWORK -> {
                pause(500)
                call(probe, account, timeoutMs + timeoutMs / 2)
            }
            ProbeFailure.RATE_LIMITED -> {
                pause(2_000)
                call(probe, account, timeoutMs)
            }
            ProbeFailure.SERVER_ERROR -> {
                pause(1_000)
                call(probe, account, timeoutMs)
            }
            ProbeFailure.FORBIDDEN -> {
                val browserLike = probe.copy(headers = probe.headers + ("User-Agent" to DESKTOP_UA))
                call(browserLike, account, timeoutMs)
            }
            else -> first
        }
    }
}
