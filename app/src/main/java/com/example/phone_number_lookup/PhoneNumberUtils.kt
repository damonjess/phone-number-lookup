package com.example.phone_number_lookup

data class NumberFormats(
    val e164: String,
    val international: String,
    val national: String,
    val rfc3966: String
)

object PhoneNumberUtils {

    private val CLEAN_REGEX = Regex("[\\s()\\-.]")
    private val UK_VALID_REGEX = Regex("^\\+44\\d{10}$")
    private val NON_DIGITS_REGEX = Regex("[^0-9]")

    private val UK_AREA_CODES = mapOf(
        "01782" to "Stoke-on-Trent",
        "020" to "London",
        "0161" to "Manchester",
        "0121" to "Birmingham",
        "0113" to "Leeds",
        "0114" to "Sheffield",
        "0151" to "Liverpool",
        "0131" to "Edinburgh",
        "0141" to "Glasgow",
        "029" to "Cardiff",
        "028" to "Northern Ireland",
        "0117" to "Bristol",
        "0115" to "Nottingham",
        "0191" to "Newcastle upon Tyne",
        "01865" to "Oxford",
        "01223" to "Cambridge"
    )

    fun normalize(input: String): String {
        // Remove spaces, dashes, parentheses, dots
        var cleaned = input.replace(CLEAN_REGEX, "")
        
        // If it starts with 0 and is 10-11 digits, assume UK default (+44)
        if (cleaned.startsWith("0") && cleaned.length >= 10) {
            cleaned = "+44" + cleaned.substring(1)
        } else if (!cleaned.startsWith("+") && cleaned.isNotEmpty()) {
            cleaned = "+$cleaned"
        }
        
        return cleaned
    }

    fun isValidUk(normalized: String): Boolean {
        return UK_VALID_REGEX.matches(normalized)
    }

    fun getCountry(normalized: String): String {
        return when {
            normalized.startsWith("+44") -> "United Kingdom (+44)"
            normalized.startsWith("+1") -> "United States / Canada (+1)"
            normalized.startsWith("+33") -> "France (+33)"
            normalized.startsWith("+49") -> "Germany (+49)"
            normalized.startsWith("+34") -> "Spain (+34)"
            normalized.startsWith("+39") -> "Italy (+39)"
            normalized.startsWith("+61") -> "Australia (+61)"
            normalized.startsWith("+91") -> "India (+91)"
            normalized.startsWith("+81") -> "Japan (+81)"
            normalized.startsWith("+86") -> "China (+86)"
            normalized.startsWith("+7") -> "Russia / Kazakhstan (+7)"
            normalized.startsWith("+55") -> "Brazil (+55)"
            else -> "International / Unknown"
        }
    }

    fun getTimezone(normalized: String): String {
        return when {
            normalized.startsWith("+44") -> "Europe/London (GMT/BST)"
            normalized.startsWith("+1") -> "US Timezones (EST/CST/MST/PST)"
            normalized.startsWith("+33") || normalized.startsWith("+49") || normalized.startsWith("+34") || normalized.startsWith("+39") -> "Europe/Paris (CET/CEST)"
            normalized.startsWith("+91") -> "Asia/Kolkata (IST)"
            normalized.startsWith("+61") -> "Australia (AEST/ACST/AWST)"
            normalized.startsWith("+81") -> "Asia/Tokyo (JST)"
            else -> "UTC / Unknown Timezone"
        }
    }

    fun getLineType(normalized: String): String {
        return when {
            normalized.startsWith("+44") -> {
                val localPart = "0" + normalized.removePrefix("+44")
                when {
                    localPart.startsWith("07") -> "Mobile Network"
                    localPart.startsWith("0800") || localPart.startsWith("0808") -> "Freephone / Toll Free"
                    localPart.startsWith("084") || localPart.startsWith("087") -> "Special Rate / Business"
                    localPart.startsWith("03") -> "Non-Geographic / Business"
                    localPart.startsWith("01") || localPart.startsWith("02") -> "Landline / Geographic"
                    else -> "Landline / Mobile"
                }
            }
            normalized.startsWith("+1") -> "US / CA Mobile or Landline"
            else -> "Mobile / Landline / VoIP"
        }
    }

    fun getAreaInfo(normalized: String): String? {
        if (!normalized.startsWith("+44")) return null
        
        val localPart = "0" + normalized.removePrefix("+44")
        
        for (i in 5 downTo 3) {
            if (localPart.length >= i) {
                val prefix = localPart.substring(0, i)
                UK_AREA_CODES[prefix]?.let { return it }
            }
        }
        
        if (localPart.startsWith("07")) {
            return "UK Mobile Network"
        }
        if (localPart.startsWith("0800") || localPart.startsWith("0808")) {
            return "Freephone"
        }
        
        return "UK Geographic Region"
    }

    fun getFormattedFormats(normalized: String, localNumber: String): NumberFormats {
        val digitsOnly = normalized.replace("+", "").replace(NON_DIGITS_REGEX, "")
        val e164 = "+$digitsOnly"
        
        val intlFormatted = if (normalized.startsWith("+44") && digitsOnly.length == 12) {
            "+44 " + digitsOnly.substring(2, 6) + " " + digitsOnly.substring(6)
        } else {
            "+$digitsOnly"
        }

        val rfc3966 = "tel:$e164"

        return NumberFormats(
            e164 = e164,
            international = intlFormatted,
            national = localNumber,
            rfc3966 = rfc3966
        )
    }
}
