package com.example.phone_number_lookup

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat

internal data class ParsedForDorks(val number: DorkNumber, val region: String?, val countryCode: Int)

/** Turns a normalised number into the spellings the built-in PhoneInfoga scanners need (libphonenumber, offline). */
internal object DorkNumbers {
    private val util = PhoneNumberUtil.getInstance()

    fun from(normalized: String): ParsedForDorks? {
        val parsed = try {
            // Same rule as PhoneNumberUtils.normalize: assume GB only when no country code is given.
            util.parse(normalized, if (normalized.startsWith("+")) null else "GB")
        } catch (_: Exception) {
            return null
        }
        if (!util.isPossibleNumber(parsed)) return null

        val national = util.format(parsed, PhoneNumberFormat.NATIONAL)
        return ParsedForDorks(
            number = DorkNumber(
                e164 = util.format(parsed, PhoneNumberFormat.E164),
                international = util.format(parsed, PhoneNumberFormat.INTERNATIONAL),
                national = national,
                // PhoneInfoga's "raw local": the national format with only its digits (trunk 0 included).
                rawLocal = national.filter { it.isDigit() },
            ),
            region = util.getRegionCodeForNumber(parsed),
            countryCode = parsed.countryCode,
        )
    }
}
