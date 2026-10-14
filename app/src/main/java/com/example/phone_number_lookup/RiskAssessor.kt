package com.example.phone_number_lookup

/**
 * Combines every signal the app has about a number into ONE verdict.
 *
 * Pure Kotlin on purpose: no Android or libphonenumber types, so it is fast and easy to unit test.
 * [NumberFactsProvider] (separate file) turns a phone number into [NumberFacts].
 *
 * Design rules:
 *  - Offline numbering-rule hints alone can never reach HIGH. They only say "be careful".
 *    HIGH needs community evidence (tellows / WhoCallsMe / 800notes) or the user's own flag.
 *  - "No reports" never becomes "safe". The best a clean number gets is NO_WARNING_SIGNS.
 *  - While sources are still loading, a clean result shows CHECKING instead of an early all-clear.
 */

enum class RiskLevel { CHECKING, HIGH, ELEVATED, NO_WARNING_SIGNS }

/** Mirrors libphonenumber's PhoneNumberType without depending on it. */
enum class NumberKind {
    FIXED_LINE, MOBILE, FIXED_LINE_OR_MOBILE, TOLL_FREE, PREMIUM_RATE,
    SHARED_COST, VOIP, PERSONAL_NUMBER, PAGER, UAN, VOICEMAIL, UNKNOWN
}

data class NumberFacts(
    val e164: String,
    /** False when the text could not be parsed as a phone number at all. */
    val parsed: Boolean,
    val possible: Boolean,
    val valid: Boolean,
    val regionCode: String?,
    val countryCallingCode: Int?,
    val kind: NumberKind,
)

/** One line of evidence. [points] > 0 raises concern, < 0 is reassuring. */
data class RiskReason(
    val points: Int,
    /** Short phrase used in the one-line summary. */
    val short: String,
    /** Full explanation shown in the "Why" list. */
    val detail: String,
)

data class RiskVerdict(
    val level: RiskLevel,
    val score: Int,
    val headline: String,
    val summary: String,
    val reasons: List<RiskReason>,
    val nextSteps: List<String>,
    val coverage: String,
    val caveat: String,
    /** True while online sources are still loading; the verdict may change. */
    val stillChecking: Boolean,
)

object RiskAssessor {

    const val HIGH_THRESHOLD = 65
    const val ELEVATED_THRESHOLD = 30

    /** Offline hints are capped so they can never produce HIGH on their own. */
    private const val OFFLINE_CAP = 55
    private const val REPUTATION_CAP = 70

    const val CAVEAT =
        "This is an estimate from public numbering rules and community reports, not proof of who is calling. " +
            "Brand-new scam numbers often have no reports yet."

    /**
     * Places often named in one-ring ("Wangiri") / callback-scam warnings. This is a short, editable
     * heuristic list with a low weight; plenty of genuine calls come from these places too.
     * Review it against current Ofcom / Which? guidance now and then.
     */
    private val CALLBACK_SCAM_REGIONS = setOf(
        // Caribbean +1 area codes
        "JM", "DO", "GD", "AG", "AI", "BB", "BM", "BS", "DM", "KY", "KN", "LC", "MS", "SX", "TC", "TT", "VC", "VG",
        // Others commonly listed
        "TN", "GN", "CI", "SL", "SO", "BY",
    )

    private val SCAM_CALL_TYPE = Regex(
        "scam|fraud|phish|robo|telemarket|spam|harass|nuisance|silent|prank|wangiri|one.?ring|lottery|prize|impersonat",
        RegexOption.IGNORE_CASE,
    )

    // ---------------------------------------------------------------- offline

    /** Hints derived only from the number itself. Works instantly with no network. */
    fun offlineReasons(facts: NumberFacts, homeCallingCode: Int = 44): List<RiskReason> {
        val out = mutableListOf<RiskReason>()
        val uk = facts.countryCallingCode == 44

        if (!facts.parsed || !facts.possible || !facts.valid) {
            out += RiskReason(
                30,
                "not a valid phone number",
                "This isn't a complete, valid phone number. Check you typed it correctly. If it is exactly what " +
                    "your phone showed, an invalid number is a sign of caller-ID spoofing.",
            )
        }

        when (facts.kind) {
            NumberKind.PREMIUM_RATE -> out += RiskReason(
                30,
                "premium-rate number",
                (if (uk) "Premium-rate range (UK 09…). " else "Premium-rate number. ") +
                    "Ringing it back can cost a lot, and callback traps use these ranges. Don't call back.",
            )
            NumberKind.PERSONAL_NUMBER -> out += RiskReason(
                35,
                "personal-number range",
                (if (uk) "UK 070 personal-number range. " else "Personal-number range. ") +
                    "These forward to another phone, hide the real location, and are often used by scammers. " +
                    "Calling them can also be expensive.",
            )
            NumberKind.VOIP -> out += RiskReason(
                10,
                "internet (VoIP) number",
                "Internet-based number. These are cheap to get and easy to spoof, though real businesses use them too.",
            )
            NumberKind.SHARED_COST -> out += RiskReason(
                10,
                "special-rate number",
                "Special-rate range. Calling back costs more than a normal call.",
            )
            else -> Unit
        }

        val cc = facts.countryCallingCode
        if (facts.valid && cc != null && cc != homeCallingCode) {
            if (facts.regionCode in CALLBACK_SCAM_REGIONS) {
                out += RiskReason(
                    20,
                    "from a place named in callback-scam warnings",
                    "This number is registered abroad (+$cc) in a place often named in one-ring and callback-scam " +
                        "warnings. A missed call from it is not a reason to ring back.",
                )
            } else {
                out += RiskReason(
                    10,
                    "registered outside the UK",
                    "This number is registered outside the UK (+$cc). Unexpected calls from abroad deserve extra care.",
                )
            }
        }

        return capPositive(out, OFFLINE_CAP)
    }

    // ----------------------------------------------------------------- online

    private fun tellowsReason(spam: SpamReport): RiskReason? {
        val score = spam.score ?: return null
        if (!spam.hasCommunityReports) return null // tellows' default 5 with no comments = unrated, not suspicious
        val comments = spam.comments ?: 0
        // One or two reports are weak evidence, so they count for less.
        val weight = if (comments >= 3) 1.0 else 0.6
        val base = when (score) {
            9 -> 80
            8 -> 70
            7 -> 55
            6 -> 30
            5 -> 15
            3, 4 -> -5
            1, 2 -> -20
            else -> 0
        }
        val points = Math.round(base * weight).toInt()
        if (points == 0) return null
        val type = spam.callerType?.takeIf { it.isNotBlank() && !it.equals("Unknown", true) }
        val reports = "$comments report" + if (comments == 1) "" else "s"
        return if (points > 0) {
            RiskReason(
                points,
                "tellows rates it $score/9 ($reports)",
                "tellows users rate this number $score out of 9 from $reports" +
                    (type?.let { ", describing it as: $it" } ?: "") + ".",
            )
        } else {
            RiskReason(
                points,
                "tellows rates it $score/9 ($reports)",
                "tellows users rate this number $score out of 9 from $reports, which looks legitimate.",
            )
        }
    }

    private fun reputationReasons(reports: List<ReputationReport>): List<RiskReason> {
        val out = mutableListOf<RiskReason>()
        for (rep in reports) {
            if (rep.error != null || !rep.hasReports) continue
            var pts = 25
            val scamType = rep.callTypes.firstOrNull { SCAM_CALL_TYPE.containsMatchIn(it) }
            if (scamType != null) pts += 15
            if (rep.reportCount >= 5) pts += 10
            val types = rep.callTypes.filter { it.isNotBlank() }.distinct().take(3)
            out += RiskReason(
                pts,
                "${rep.source}: ${rep.reportCount} report" + if (rep.reportCount == 1) "" else "s",
                "${rep.source} has ${rep.reportCount} user report" + (if (rep.reportCount == 1) "" else "s") +
                    (if (types.isNotEmpty()) ", labelled: ${types.joinToString(", ")}" else "") + ".",
            )
        }
        return capPositive(out, REPUTATION_CAP)
    }

    private fun userReason(status: String?): RiskReason? = when (status) {
        "Suspected spam" -> RiskReason(
            40,
            "you marked it suspected spam",
            "You previously marked this number as suspected spam in your history.",
        )
        "Trusted" -> RiskReason(
            -30,
            "you marked it trusted",
            "You previously marked this number as trusted in your history.",
        )
        else -> null
    }

    // ------------------------------------------------------------------ main

    fun assess(
        facts: NumberFacts,
        spam: SpamReport?,
        reputation: List<ReputationReport>,
        userStatus: String?,
        stillChecking: Boolean,
        homeCallingCode: Int = 44,
    ): RiskVerdict {
        val reasons = mutableListOf<RiskReason>()
        reasons += offlineReasons(facts, homeCallingCode)
        spam?.let { s -> tellowsReason(s)?.let { reasons += it } }
        reasons += reputationReasons(reputation)
        userReason(userStatus)?.let { reasons += it }

        val score = reasons.sumOf { it.points }.coerceIn(0, 100)
        val trusted = userStatus == "Trusted"

        var level = when {
            score >= HIGH_THRESHOLD -> RiskLevel.HIGH
            score >= ELEVATED_THRESHOLD -> RiskLevel.ELEVATED
            else -> RiskLevel.NO_WARNING_SIGNS
        }
        if (stillChecking && level == RiskLevel.NO_WARNING_SIGNS) level = RiskLevel.CHECKING

        val sorted = reasons.sortedByDescending { it.points }
        val concerns = sorted.filter { it.points > 0 }

        val headline = when (level) {
            RiskLevel.HIGH -> "Likely scam or nuisance caller"
            RiskLevel.ELEVATED -> "Suspicious: treat with caution"
            RiskLevel.NO_WARNING_SIGNS -> "No warning signs found"
            RiskLevel.CHECKING -> "Checking this number…"
        }

        val summary = when {
            level == RiskLevel.CHECKING -> "Numbering rules look fine so far. Waiting for the caller databases."
            concerns.isNotEmpty() ->
                "Main reasons: " + concerns.take(2).joinToString("; ") { it.short } + "." +
                    if (trusted && level != RiskLevel.NO_WARNING_SIGNS)
                        " You marked this number as trusted, but the evidence above disagrees." else ""
            reasons.any { it.points < 0 } -> "Reports suggest this number is legitimate."
            else -> "Nothing in the numbering rules or the databases we checked raised a concern."
        }

        return RiskVerdict(
            level = level,
            score = score,
            headline = headline,
            summary = summary,
            reasons = sorted,
            nextSteps = nextSteps(level),
            coverage = coverage(facts, spam, reputation, stillChecking),
            caveat = CAVEAT,
            stillChecking = stillChecking,
        )
    }

    private fun coverage(
        facts: NumberFacts,
        spam: SpamReport?,
        reputation: List<ReputationReport>,
        stillChecking: Boolean,
    ): String {
        val checked = mutableListOf("numbering rules")
        val unavailable = mutableListOf<String>()
        val pending = mutableListOf<String>()

        when {
            spam == null -> if (stillChecking) pending += "tellows"
            spam.score == null || spam.error != null -> unavailable += "tellows"
            else -> checked += "tellows"
        }

        val northAmerican = facts.countryCallingCode == 1
        if (northAmerican) {
            if (reputation.isEmpty()) {
                if (stillChecking) pending += "WhoCallsMe/800notes"
            } else {
                reputation.forEach { if (it.error != null) unavailable += it.source else checked += it.source }
            }
        }

        val parts = mutableListOf("Checked: ${checked.joinToString(", ")}.")
        if (unavailable.isNotEmpty()) parts += "Unavailable: ${unavailable.joinToString(", ")}."
        if (pending.isNotEmpty()) parts += "Still loading: ${pending.joinToString(", ")}."
        if (!northAmerican) parts += "WhoCallsMe and 800notes only cover US/Canada numbers, so they weren't used."
        return parts.joinToString(" ")
    }

    private fun nextSteps(level: RiskLevel): List<String> = when (level) {
        RiskLevel.HIGH -> listOf(
            "Don't call back or reply, and don't press any keys if it was a recording.",
            "Never share codes, PINs, passwords or card details on a call you didn't expect.",
            "If they claimed to be your bank, hang up and ring the number on your card, or 159.",
            "Report it at reportfraud.police.uk or 0300 123 2040 (Scotland: 101). Forward scam texts to 7726.",
            "Block the number in your phone's call settings.",
        )
        RiskLevel.ELEVATED -> listOf(
            "Don't ring back a number you don't recognise, especially a premium-rate one.",
            "If they named a company or the bank, contact it using the number on its official website or your card.",
            "Don't give personal or payment details on an unexpected call.",
            "If it turns out to be a scam, report it at reportfraud.police.uk.",
        )
        RiskLevel.NO_WARNING_SIGNS -> listOf(
            "No reports doesn't prove a number is safe. New scam numbers often have none yet.",
            "If the caller asks for money, codes or personal details, treat it as suspicious anyway.",
        )
        RiskLevel.CHECKING -> emptyList()
    }

    /** Keeps negative (reassuring) reasons intact and scales positives so their sum stays at or below [cap]. */
    private fun capPositive(reasons: List<RiskReason>, cap: Int): List<RiskReason> {
        val total = reasons.filter { it.points > 0 }.sumOf { it.points }
        if (total <= cap) return reasons
        val factor = cap.toDouble() / total
        return reasons.map { if (it.points > 0) it.copy(points = Math.max(1, (it.points * factor).toInt())) else it }
    }
}
