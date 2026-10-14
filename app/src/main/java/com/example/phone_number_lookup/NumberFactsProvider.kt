package com.example.phone_number_lookup

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType

/** Turns a normalised number into [NumberFacts] using libphonenumber (offline, no network). */
object NumberFactsProvider {
    private val util = PhoneNumberUtil.getInstance()

    fun from(normalized: String): NumberFacts {
        val parsed = try {
            // Same rule as PhoneNumberUtils.normalize: assume GB only when no country code is given.
            util.parse(normalized, if (normalized.startsWith("+")) null else "GB")
        } catch (_: Exception) {
            return NumberFacts(
                e164 = normalized,
                parsed = false,
                possible = false,
                valid = false,
                regionCode = null,
                countryCallingCode = null,
                kind = NumberKind.UNKNOWN,
            )
        }

        val valid = util.isValidNumber(parsed)
        return NumberFacts(
            e164 = normalized,
            parsed = true,
            possible = util.isPossibleNumber(parsed),
            valid = valid,
            regionCode = if (valid) util.getRegionCodeForNumber(parsed) else null,
            countryCallingCode = parsed.countryCode,
            kind = when (util.getNumberType(parsed)) {
                PhoneNumberType.FIXED_LINE -> NumberKind.FIXED_LINE
                PhoneNumberType.MOBILE -> NumberKind.MOBILE
                PhoneNumberType.FIXED_LINE_OR_MOBILE -> NumberKind.FIXED_LINE_OR_MOBILE
                PhoneNumberType.TOLL_FREE -> NumberKind.TOLL_FREE
                PhoneNumberType.PREMIUM_RATE -> NumberKind.PREMIUM_RATE
                PhoneNumberType.SHARED_COST -> NumberKind.SHARED_COST
                PhoneNumberType.VOIP -> NumberKind.VOIP
                PhoneNumberType.PERSONAL_NUMBER -> NumberKind.PERSONAL_NUMBER
                PhoneNumberType.PAGER -> NumberKind.PAGER
                PhoneNumberType.UAN -> NumberKind.UAN
                PhoneNumberType.VOICEMAIL -> NumberKind.VOICEMAIL
                else -> NumberKind.UNKNOWN
            },
        )
    }
}
