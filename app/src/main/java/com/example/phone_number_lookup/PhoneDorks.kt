package com.example.phone_number_lookup

import java.net.URLEncoder

/**
 * Google "dork" searches for a phone number, in the spirit of PhoneInfoga's googlesearch scanner.
 * The searches are plain links: nothing is sent to Google until the user taps one, and the results
 * open in the user's own browser. The site lists here are the app's own, not copied from PhoneInfoga.
 */
data class DorkNumber(
    val e164: String,
    val international: String,
    val national: String,
    val rawLocal: String,
) {
    /** The distinct, non-blank ways this number might be written on a web page. */
    val spellings: List<String>
        get() = listOf(e164, international, national, rawLocal).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

data class PhoneDork(val label: String, val query: String) {
    val url: String get() = "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")
}

data class DorkGroup(val title: String, val dorks: List<PhoneDork>)

object PhoneDorks {

    /** Report sites that publish numbers. tellows has its own direct link, see [PhoneLinks]. */
    private val REPUTATION = listOf(
        "Who Called" to "who-called.co.uk", "WhoCallsMe" to "whocallsme.com",
        "800notes" to "800notes.com", "Should I Answer" to "shouldianswer.co.uk",
    )

    /** `("+442079460958" OR "020 7946 0958" OR ...)` */
    internal fun anyOf(n: DorkNumber): String =
        n.spellings.joinToString(" OR ", prefix = "(", postfix = ")") { "\"$it\"" }

    fun generate(n: DorkNumber): List<DorkGroup> {
        if (n.spellings.isEmpty()) return emptyList()
        val any = anyOf(n)

        val rawDigits = n.rawLocal.filter { it.isDigit() }
        val prefixGroup = if (rawDigits.length >= 6) {
            val prefix5 = rawDigits.take(5)
            val prefix6 = rawDigits.take(6)
            listOf(
                DorkGroup(
                    "Prefix & spoofing wave ($prefix5…)",
                    listOf(
                        PhoneDork("Who Called UK prefix activity ($prefix6)", "site:who-called.co.uk \"$prefix6\" OR \"$prefix5\""),
                        PhoneDork("Prefix scam & pager warnings ($prefix5)", "\"$prefix5\" (scam OR spam OR pager OR radiopaging OR \"who called\" OR ofcom)"),
                    )
                )
            )
        } else emptyList()

        return listOf(
            // One search for any page that mentions the number next to a social network. Per-site searches
            // such as site:facebook.com almost never match, because those sites don't publish numbers.
            DorkGroup(
                "Social media",
                listOf(PhoneDork("Mentions near social networks", "$any (facebook OR instagram OR twitter OR linkedin OR tiktok OR telegram)")),
            ),
            DorkGroup(
                "Scam and spam reports",
                REPUTATION.map { (label, site) -> PhoneDork(label, "site:$site $any") } + listOf(
                    PhoneDork("Scam or spam reports", "$any (scam OR spam OR fraud OR \"cold call\" OR harassment OR robocall)"),
                    PhoneDork("Who called", "$any (\"who called\" OR \"who is calling\" OR \"reverse lookup\")"),
                ),
            ),
            DorkGroup(
                "Temporary SMS numbers",
                listOf(PhoneDork("Disposable SMS sites", "$any (\"receive sms\" OR \"temporary number\" OR \"free sms\" OR \"virtual number\")")),
            ),
            DorkGroup(
                "Documents and pages",
                listOf(
                    PhoneDork("PDF files", "$any ext:pdf"),
                    PhoneDork("Word files", "$any (ext:doc OR ext:docx)"),
                    PhoneDork("Spreadsheets", "$any (ext:xls OR ext:xlsx OR ext:csv)"),
                    PhoneDork("CV or resume", "$any (CV OR resume OR \"curriculum vitae\")"),
                    PhoneDork("Contact pages", "$any (inurl:contact OR intitle:contact)"),
                ),
            ),
            DorkGroup(
                "General",
                listOf(
                    PhoneDork("Any mention", any),
                    PhoneDork("With email or contact", "$any (email OR \"e-mail\" OR contact)"),
                ),
            ),
        ) + prefixGroup
    }
}
