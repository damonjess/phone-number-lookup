package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeFailureTest {

    private fun classify(code: Int, body: String = "", network: Boolean = false) =
        ProbeFailures.classify(code, body, network)

    @Test
    fun networkProblemsAreNetwork() {
        assertEquals(ProbeFailure.NETWORK, classify(-1, network = true))
        assertEquals(ProbeFailure.NETWORK, classify(-1))
    }

    @Test
    fun plainRefusalsAreNotBotChecks() {
        assertEquals(ProbeFailure.FORBIDDEN, classify(403, "Forbidden"))
        assertEquals(ProbeFailure.FORBIDDEN, classify(401, ""))
        assertEquals(ProbeFailure.RATE_LIMITED, classify(429, "slow down"))
    }

    @Test
    fun cloudflareAndCaptchaPagesAreChallenges() {
        assertEquals(ProbeFailure.CHALLENGE, classify(403, "<title>Just a moment...</title>"))
        assertEquals(ProbeFailure.CHALLENGE, classify(503, "<div id=\"cf-chl-widget\"></div>"))
        assertEquals(ProbeFailure.CHALLENGE, classify(403, "Please solve this CAPTCHA"))
        assertEquals(ProbeFailure.CHALLENGE, classify(200, "window._cf_chl_opt = {}; cf-chl"))
    }

    @Test
    fun theWordCaptchaOnAnOrdinaryPageIsNotAChallenge() {
        // Login pages mention captcha constantly; a 200 with no found marker is just inconclusive.
        assertEquals(ProbeFailure.INCONCLUSIVE, classify(200, "<script src=recaptcha.js></script> captcha"))
    }

    @Test
    fun serverAndOtherErrors() {
        assertEquals(ProbeFailure.SERVER_ERROR, classify(500))
        assertEquals(ProbeFailure.SERVER_ERROR, classify(502))
        assertEquals(ProbeFailure.INCONCLUSIVE, classify(200, "Welcome"))
        assertEquals(ProbeFailure.HTTP_ERROR, classify(418))
        assertEquals(ProbeFailure.HTTP_ERROR, classify(451))
    }

    @Test
    fun onlyTemporaryFailuresAreRetryable() {
        fun issue(r: ProbeFailure) = SiteIssue("s", "https://s/x", r)
        assertTrue(issue(ProbeFailure.NETWORK).retryable)
        assertTrue(issue(ProbeFailure.RATE_LIMITED).retryable)
        assertTrue(issue(ProbeFailure.SERVER_ERROR).retryable)
        assertTrue(issue(ProbeFailure.FORBIDDEN).retryable)
        assertFalse(issue(ProbeFailure.CHALLENGE).retryable)
        assertFalse(issue(ProbeFailure.INCONCLUSIVE).retryable)
        assertFalse(issue(ProbeFailure.HTTP_ERROR).retryable)
    }

    @Test
    fun summaryGroupsReasonsInPlainWords() {
        val issues = List(18) { SiteIssue("a$it", "", if (it % 2 == 0) ProbeFailure.CHALLENGE else ProbeFailure.FORBIDDEN) } +
            List(12) { SiteIssue("b$it", "", ProbeFailure.NETWORK) } +
            List(5) { SiteIssue("c$it", "", ProbeFailure.INCONCLUSIVE) }
        assertEquals(
            "35 sites couldn't be checked automatically: 18 blocked by bot protection, " +
                "12 timed out or couldn't connect, 5 gave no clear answer.",
            ProbeFailures.summarize(issues)
        )
    }

    @Test
    fun summaryHandlesOneAndNone() {
        assertEquals("", ProbeFailures.summarize(emptyList()))
        assertEquals(
            "1 site couldn't be checked automatically: 1 rate-limited.",
            ProbeFailures.summarize(listOf(SiteIssue("x", "", ProbeFailure.RATE_LIMITED)))
        )
    }
}
