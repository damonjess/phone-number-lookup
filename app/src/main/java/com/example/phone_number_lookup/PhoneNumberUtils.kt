package com.example.phone_number_lookup

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat

 data class NumberFormats(
    val e164: String,
    val international: String,
    val national: String,
    val rfc3966: String
)

object PhoneNumberUtils {
    private val util = PhoneNumberUtil.getInstance()
    private val cleanRegex = Regex("[^0-9+]")
    private val ukAreaCodes = mapOf(
        "01782" to "Stoke-on-Trent", "020" to "London", "0161" to "Manchester",
        "0121" to "Birmingham", "0113" to "Leeds", "0114" to "Sheffield",
        "0151" to "Liverpool", "0131" to "Edinburgh", "0141" to "Glasgow",
        "029" to "Cardiff", "028" to "Northern Ireland", "0117" to "Bristol",
        "0115" to "Nottingham", "0191" to "Newcastle upon Tyne", "01865" to "Oxford",
        "01223" to "Cambridge"
    )

    /** Parses international numbers, or assumes GB only when no country is supplied. */
    fun normalize(input: String): String {
        val cleaned = input.trim().replace(cleanRegex, "")
        if (cleaned.isBlank()) return ""
        val international = if (cleaned.startsWith("00")) "+${cleaned.drop(2)}" else cleaned
        return try {
            val parsed = util.parse(international, if (international.startsWith("+")) null else "GB")
            if (util.isPossibleNumber(parsed)) util.format(parsed, PhoneNumberFormat.E164) else international
        } catch (_: Exception) {
            international
        }
    }

    fun isValidUk(normalized: String): Boolean = try {
        val parsed = util.parse(normalized, "GB")
        util.getRegionCodeForNumber(parsed) == "GB" && util.isValidNumber(parsed)
    } catch (_: Exception) {
        false
    }

    fun getCountry(normalized: String): String {
        val region = try {
            util.getRegionCodeForNumber(util.parse(normalized, null))
        } catch (_: Exception) { null }
        return when (region) {
            "GB" -> "United Kingdom (+44)"
            "US", "CA" -> "United States / Canada (+1)"
            "FR" -> "France (+33)"
            "DE" -> "Germany (+49)"
            "ES" -> "Spain (+34)"
            "IT" -> "Italy (+39)"
            "AU" -> "Australia (+61)"
            "IN" -> "India (+91)"
            "JP" -> "Japan (+81)"
            "CN" -> "China (+86)"
            "RU", "KZ" -> "Russia / Kazakhstan (+7)"
            "BR" -> "Brazil (+55)"
            else -> "International / Unknown"
        }
    }

    fun getTimezone(normalized: String): String = when {
        normalized.startsWith("+44") -> "Europe/London (GMT/BST)"
        normalized.startsWith("+1") -> "North American time zones"
        normalized.startsWith("+33") -> "Europe/Paris (CET/CEST)"
        normalized.startsWith("+49") -> "Europe/Berlin (CET/CEST)"
        normalized.startsWith("+34") -> "Europe/Madrid (CET/CEST)"
        normalized.startsWith("+39") -> "Europe/Rome (CET/CEST)"
        normalized.startsWith("+91") -> "Asia/Kolkata (IST)"
        normalized.startsWith("+61") -> "Australian time zones"
        normalized.startsWith("+81") -> "Asia/Tokyo (JST)"
        else -> "UTC / Unknown time zone"
    }

    /** Prefix estimate only; it is not a live carrier lookup. */
    fun getLineType(normalized: String): String {
        val localPart = if (normalized.startsWith("+44")) "0" + normalized.removePrefix("+44") else normalized
        return when {
            localPart.startsWith("07") -> "Estimated mobile number"
            localPart.startsWith("0800") || localPart.startsWith("0808") -> "Estimated freephone number"
            localPart.startsWith("084") || localPart.startsWith("087") -> "Estimated special-rate number"
            localPart.startsWith("03") -> "Estimated non-geographic number"
            localPart.startsWith("01") || localPart.startsWith("02") -> "Estimated geographic landline"
            else -> "Type not determined"
        }
    }

    fun getAreaInfo(normalized: String): String? {
        if (!normalized.startsWith("+44")) return null
        val localPart = "0" + normalized.removePrefix("+44")
        for (length in 5 downTo 3) {
            ukAreaCodes[localPart.take(length)]?.let { return it }
        }
        return when {
            localPart.startsWith("07") -> "UK mobile range"
            localPart.startsWith("0800") || localPart.startsWith("0808") -> "UK freephone range"
            else -> "UK geographic region not mapped"
        }
    }

    fun getFormattedFormats(normalized: String, localNumber: String): NumberFormats {
        return try {
            val parsed = util.parse(normalized, null)
            NumberFormats(
                e164 = util.format(parsed, PhoneNumberFormat.E164),
                international = util.format(parsed, PhoneNumberFormat.INTERNATIONAL),
                national = util.format(parsed, PhoneNumberFormat.NATIONAL),
                rfc3966 = util.format(parsed, PhoneNumberFormat.RFC3966)
            )
        } catch (_: Exception) {
            val digits = normalized.filter { it.isDigit() }
            NumberFormats("+$digits", "+$digits", localNumber, "tel:+$digits")
        }
    }
}
