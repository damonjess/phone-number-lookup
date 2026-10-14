package com.example.phone_number_lookup

/**
 * Likely alternative spellings of one handle. People often use "john.smith" on one site and
 * "johnsmith" or "john_smith" on another, so scanning only the exact string misses accounts.
 */
object UsernameVariants {

    private const val MIN_LENGTH = 3
    private val SEPARATORS = charArrayOf('.', '_', '-')

    /**
     * @param raw a username, or the part of an email address before the @
     * @return the original first, then up to [max] - 1 distinct variants
     */
    fun fromHandle(raw: String, max: Int = 4): List<String> {
        var base = raw.trim().removePrefix("@")
        // "name+shopping@gmail.com" is the same mailbox as "name@gmail.com".
        if ('+' in base) {
            val stripped = base.substringBefore('+')
            if (stripped.length >= MIN_LENGTH) base = stripped
        }
        if (base.isEmpty()) return emptyList()

        val out = LinkedHashSet<String>()
        out += base

        val noSeparators = base.filterNot { it in SEPARATORS }
        if (base.any { it in SEPARATORS }) out += noSeparators

        if ('.' in base) out += base.replace('.', '_')
        if ('_' in base) out += base.replace('_', '.')
        if ('-' in base) out += base.replace('-', '_')

        // "johnsmith1985" -> "johnsmith" (only when enough letters remain to be meaningful)
        val withoutTrailingDigits = base.trimEnd { it.isDigit() }
        if (withoutTrailingDigits != base && withoutTrailingDigits.count { it.isLetter() } >= 4) {
            out += withoutTrailingDigits
        }

        return out.filter { it.length >= MIN_LENGTH }.take(max.coerceAtLeast(1))
    }
}
