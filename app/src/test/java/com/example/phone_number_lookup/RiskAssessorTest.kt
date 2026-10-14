package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskAssessorTest {

    private fun facts(
        kind: NumberKind = NumberKind.FIXED_LINE,
        cc: Int = 44,
        region: String? = "GB",
        valid: Boolean = true,
        e164: String = "+442079460958",
    ) = NumberFacts(e164, parsed = true, possible = true, valid = valid, regionCode = region, countryCallingCode = cc, kind = kind)

    private fun tellows(score: Int?, comments: Int?, type: String? = null, error: String? = null) = SpamReport(
        source = "tellows.co.uk", number = "+442079460958", score = score, searches = 10,
        comments = comments, location = null, country = null, callerType = type, error = error,
    )

    private fun rep(source: String, count: Int, vararg types: String, error: String? = null) = ReputationReport(
        source = source, url = "", hasReports = count > 0, reportCount = count,
        callTypes = types.toList(), samples = emptyList(), error = error,
    )

    private fun assess(
        f: NumberFacts = facts(),
        spam: SpamReport? = null,
        reputation: List<ReputationReport> = emptyList(),
        user: String? = null,
        checking: Boolean = false,
    ) = RiskAssessor.assess(f, spam, reputation, user, checking)

    @Test
    fun cleanNumberWithNoReports_isNoWarningSigns_notSafe() {
        val v = assess(spam = tellows(5, 0))
        assertEquals(RiskLevel.NO_WARNING_SIGNS, v.level)
        assertEquals(0, v.score)
        assertTrue(v.nextSteps.any { it.contains("doesn't prove") })
    }

    @Test
    fun tellowsDefaultFiveWithNoComments_addsNoPoints() {
        val v = assess(spam = tellows(5, 0))
        assertTrue(v.reasons.none { it.short.contains("tellows") })
    }

    @Test
    fun tellowsHighScoreWithSeveralReports_isHigh() {
        val v = assess(spam = tellows(8, 5, "Telemarketer"))
        assertEquals(RiskLevel.HIGH, v.level)
        assertTrue(v.summary.contains("tellows rates it 8/9"))
    }

    @Test
    fun tellowsHighScoreWithOneReport_isOnlyElevated() {
        val v = assess(spam = tellows(9, 1))
        assertEquals(RiskLevel.ELEVATED, v.level)
    }

    @Test
    fun tellowsLowScoreIsReassuring() {
        val v = assess(spam = tellows(2, 6))
        assertEquals(RiskLevel.NO_WARNING_SIGNS, v.level)
        assertEquals("Reports suggest this number is legitimate.", v.summary)
    }

    @Test
    fun offlineSignalsAloneNeverReachHigh() {
        // Premium rate + invalid + callback-scam region all stacked.
        val f = NumberFacts("+18765551234", parsed = true, possible = true, valid = false, regionCode = "JM", countryCallingCode = 1, kind = NumberKind.PREMIUM_RATE)
        val v = assess(f = f)
        assertEquals(RiskLevel.ELEVATED, v.level)
        assertTrue(v.score < RiskAssessor.HIGH_THRESHOLD)
    }

    @Test
    fun ukPersonalNumber_isElevatedWithoutAnyReports() {
        val v = assess(f = facts(kind = NumberKind.PERSONAL_NUMBER, e164 = "+447012345678"))
        assertEquals(RiskLevel.ELEVATED, v.level)
        assertTrue(v.reasons.first().detail.contains("070"))
    }

    @Test
    fun premiumRate_isElevated() {
        val v = assess(f = facts(kind = NumberKind.PREMIUM_RATE, e164 = "+449098790123"))
        assertEquals(RiskLevel.ELEVATED, v.level)
    }

    @Test
    fun voipAlone_isNotEnoughToWarn() {
        val v = assess(f = facts(kind = NumberKind.VOIP, e164 = "+445612345678"))
        assertEquals(RiskLevel.NO_WARNING_SIGNS, v.level)
    }

    @Test
    fun invalidNumber_isFlaggedWithTypoHint() {
        val v = assess(f = NumberFacts("0178", parsed = false, possible = false, valid = false, regionCode = null, countryCallingCode = null, kind = NumberKind.UNKNOWN))
        assertEquals(RiskLevel.ELEVATED, v.level)
        assertEquals(30, v.score)
        assertTrue(v.reasons.first().detail.contains("typed it correctly"))
    }

    @Test
    fun foreignNumber_getsSmallBump_ukChannelIslandsDoNot() {
        val de = NumberFacts("+4930123456", true, true, true, "DE", 49, NumberKind.FIXED_LINE)
        assertEquals(10, assess(f = de).score)
        // Channel Islands / Isle of Man share +44, so they are not "outside the UK".
        val gg = NumberFacts("+441481123456", true, true, true, "GG", 44, NumberKind.FIXED_LINE)
        assertEquals(0, assess(f = gg).score)
    }

    @Test
    fun callbackScamRegion_scoresHigherThanOrdinaryForeign() {
        val jm = NumberFacts("+18765551234", true, true, true, "JM", 1, NumberKind.MOBILE)
        assertEquals(20, assess(f = jm).score)
    }

    @Test
    fun stillChecking_withNothingFound_showsCheckingNotAllClear() {
        val v = assess(checking = true)
        assertEquals(RiskLevel.CHECKING, v.level)
        assertTrue(v.stillChecking)
        assertTrue(v.nextSteps.isEmpty())
    }

    @Test
    fun stillChecking_doesNotHideAnOfflineWarning() {
        val v = assess(f = facts(kind = NumberKind.PERSONAL_NUMBER), checking = true)
        assertEquals(RiskLevel.ELEVATED, v.level)
        assertTrue(v.stillChecking)
    }

    @Test
    fun twoCommunityDatabasesWithScamLabels_isHigh() {
        val f = NumberFacts("+12125550123", true, true, true, "US", 1, NumberKind.FIXED_LINE_OR_MOBILE)
        val v = assess(f = f, reputation = listOf(rep("WhoCallsMe", 3, "Scam"), rep("800notes", 6, "Telemarketer")))
        assertEquals(RiskLevel.HIGH, v.level)
    }

    @Test
    fun oneCommunityDatabase_isElevatedNotHigh() {
        val f = NumberFacts("+12125550123", true, true, true, "US", 1, NumberKind.FIXED_LINE_OR_MOBILE)
        val v = assess(f = f, reputation = listOf(rep("800notes", 2, "Scam")))
        assertEquals(RiskLevel.ELEVATED, v.level)
    }

    @Test
    fun failedSources_areReportedAsUnavailable_notAsClean() {
        val f = NumberFacts("+12125550123", true, true, true, "US", 1, NumberKind.FIXED_LINE_OR_MOBILE)
        val v = assess(
            f = f,
            spam = tellows(null, null, error = "timeout"),
            reputation = listOf(rep("WhoCallsMe", 0, error = "blocked"), rep("800notes", 0)),
        )
        assertTrue(v.coverage.contains("Unavailable: tellows, WhoCallsMe"))
        assertTrue(v.coverage.contains("800notes"))
    }

    @Test
    fun ukNumber_coverageExplainsWhyNorthAmericanSourcesWereSkipped() {
        val v = assess(spam = tellows(5, 0))
        assertTrue(v.coverage.contains("only cover US/Canada"))
    }

    @Test
    fun userMarkedSuspected_pushesAKnownBadNumberToHigh() {
        val v = assess(spam = tellows(7, 4), user = "Suspected spam")
        assertEquals(RiskLevel.HIGH, v.level)
    }

    @Test
    fun userMarkedTrusted_doesNotSilenceStrongEvidence() {
        val v = assess(spam = tellows(9, 8), user = "Trusted")
        assertEquals(RiskLevel.ELEVATED, v.level)
        assertTrue(v.summary.contains("you marked this number as trusted") || v.summary.contains("You marked this number as trusted"))
    }

    @Test
    fun userMarkedTrusted_aloneIsNoWarning() {
        val v = assess(user = "Trusted")
        assertEquals(RiskLevel.NO_WARNING_SIGNS, v.level)
        assertFalse(v.reasons.any { it.points > 0 })
    }

    @Test
    fun scoreIsClampedToZeroAndHundred() {
        val v = assess(
            f = facts(kind = NumberKind.PERSONAL_NUMBER),
            spam = tellows(9, 20),
            reputation = listOf(rep("WhoCallsMe", 9, "Scam"), rep("800notes", 9, "Scam")),
            user = "Suspected spam",
        )
        assertTrue(v.score in 0..100)
        assertEquals(RiskLevel.HIGH, v.level)
    }
}
