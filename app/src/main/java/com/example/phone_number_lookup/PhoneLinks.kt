package com.example.phone_number_lookup

/**
 * Links that go straight to a service's own page for a phone number. Only formats that are
 * documented or confirmed to exist are included.
 *
 * Not included, on purpose: Facebook, Instagram, X, LinkedIn and TikTok have no public link that
 * goes from a phone number to a profile, and sources disagree on whether Telegram supports one.
 * Google searches like `site:facebook.com "number"` almost never match, because those sites don't
 * publish phone numbers.
 */
data class DirectLink(val label: String, val url: String)

object PhoneLinks {

    /** @param e164 the number in international format, for example "+442079460958" */
    fun generate(e164: String): List<DirectLink> {
        val digits = e164.filter { it.isDigit() }
        if (digits.length < 7) return emptyList()
        return listOf(
            // WhatsApp's documented click-to-chat link: country code first, digits only, no "+".
            DirectLink("WhatsApp: open a chat with this number", "https://wa.me/$digits"),
            // tellows page for the number: rating and community reports.
            DirectLink("tellows: reports and rating", "https://www.tellows.co.uk/num/%2B$digits"),
        )
    }
}
