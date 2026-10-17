package com.example.phone_number_lookup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResilientProbeTest {

    private val probe = SiteProbe(
        name = "Site", category = "social", source = "Test", method = "GET",
        urlTemplate = "https://site.test/{account}", accountMode = AccountMode.RAW,
        profileTemplate = "https://site.test/{account}", headers = emptyMap(), bodyTemplate = null,
        foundCodes = emptySet(), foundStrings = emptyList(), missingCodes = emptySet(),
        missingStrings = emptyList(), missingUrls = emptyList(),
    )

    private fun result(verdict: ProbeVerdict, failure: ProbeFailure? = null) =
        ProbeResult(probe, verdict, "https://site.test/alice", 200, failure = failure)

    private class Script(private vararg val answers: ProbeResult) {
        val seenTimeouts = mutableListOf<Int>()
        val seenAgents = mutableListOf<String?>()
        val call: (SiteProbe, String, Int) -> ProbeResult = { p, _, t ->
            seenTimeouts += t
            seenAgents += p.headers["User-Agent"]
            answers[minOf(seenTimeouts.size, answers.size) - 1]
        }
    }

    private val pauses = mutableListOf<Long>()
    private suspend fun run(script: Script) =
        ResilientProbe.evaluate(probe, "alice", timeoutMs = 10_000, call = script.call, pause = { pauses += it })

    @Test
    fun aClearAnswerIsNeverRetried() = runBlocking {
        val s = Script(result(ProbeVerdict.FOUND))
        assertEquals(ProbeVerdict.FOUND, run(s).verdict)
        assertEquals(1, s.seenTimeouts.size)
    }

    @Test
    fun timeoutsAreRetriedOnceWithALongerTimeout() = runBlocking {
        pauses.clear()
        val s = Script(result(ProbeVerdict.UNKNOWN, ProbeFailure.NETWORK), result(ProbeVerdict.FOUND))
        assertEquals(ProbeVerdict.FOUND, run(s).verdict)
        assertEquals(listOf(10_000, 15_000), s.seenTimeouts)
        assertEquals(listOf(500L), pauses)
    }

    @Test
    fun rateLimitsWaitBeforeTheRetry() = runBlocking {
        pauses.clear()
        val s = Script(result(ProbeVerdict.UNKNOWN, ProbeFailure.RATE_LIMITED), result(ProbeVerdict.FOUND))
        assertEquals(ProbeVerdict.FOUND, run(s).verdict)
        assertEquals(listOf(2_000L), pauses)
    }

    @Test
    fun aPlainRefusalIsRetriedWithABrowserLikeUserAgent() = runBlocking {
        val s = Script(result(ProbeVerdict.UNKNOWN, ProbeFailure.FORBIDDEN), result(ProbeVerdict.FOUND))
        assertEquals(ProbeVerdict.FOUND, run(s).verdict)
        assertEquals(null, s.seenAgents[0])
        assertTrue(s.seenAgents[1]!!.contains("Chrome"))
    }

    @Test
    fun botCheckPagesAreReportedNotWorkedAround() = runBlocking {
        val s = Script(result(ProbeVerdict.UNKNOWN, ProbeFailure.CHALLENGE))
        val r = run(s)
        assertEquals(ProbeFailure.CHALLENGE, r.failure)
        assertEquals(1, s.seenTimeouts.size)
    }

    @Test
    fun inconclusivePagesAreNotRetried() = runBlocking {
        val s = Script(result(ProbeVerdict.UNKNOWN, ProbeFailure.INCONCLUSIVE))
        run(s)
        assertEquals(1, s.seenTimeouts.size)
    }

    @Test
    fun aSecondFailureIsReturnedAndNothingRetriesTwice() = runBlocking {
        val s = Script(
            result(ProbeVerdict.UNKNOWN, ProbeFailure.SERVER_ERROR),
            result(ProbeVerdict.UNKNOWN, ProbeFailure.SERVER_ERROR),
            result(ProbeVerdict.FOUND),
        )
        val r = run(s)
        assertEquals(ProbeVerdict.UNKNOWN, r.verdict)
        assertEquals(2, s.seenTimeouts.size)
        assertFalse(r.verdict == ProbeVerdict.FOUND)
    }
}
