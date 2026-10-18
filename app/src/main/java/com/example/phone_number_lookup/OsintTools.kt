package com.example.phone_number_lookup

import android.net.Uri

data class OsintDork(
    val title: String,
    val description: String,
    val url: String,
    val toolSource: String = "Google Dorks"
)

data class SocialPlatformResult(
    val platformName: String,
    val category: String,
    val profileUrl: String,
    val searchUrl: String
)

object OsintTools {

    // --- PhoneInfoga OSINT Engine ---
    fun getPhoneInfogaDorks(normalizedNumber: String, localNumber: String): List<OsintDork> {
        val qSocial = Uri.encode("(\"$normalizedNumber\" OR \"$localNumber\") (site:facebook.com OR site:linkedin.com OR site:instagram.com OR site:twitter.com OR site:x.com OR site:tiktok.com)")
        val qPastes = Uri.encode("(\"$normalizedNumber\" OR \"$localNumber\") (site:pastebin.com OR site:ghostbin.com OR site:justpaste.it OR site:github.com OR site:gitlab.com)")
        val qDocuments = Uri.encode("(\"$normalizedNumber\" OR \"$localNumber\") (filetype:pdf OR filetype:doc OR filetype:docx OR filetype:xls OR filetype:xlsx)")
        val qSpam = Uri.encode("(\"$normalizedNumber\" OR \"$localNumber\") (site:whocalled.co.uk OR site:shouldicall.co.uk OR site:tellows.co.uk OR site:who-called.co.uk OR site:spamcalls.net)")
        val qCompanies = Uri.encode("(\"$normalizedNumber\" OR \"$localNumber\") (site:gov.uk OR site:company-information.service.gov.uk OR site:yell.com OR site:companieshouse.gov.uk)")

        return listOf(
            OsintDork(
                title = "PhoneInfoga: Social Media Scanner",
                description = "Scans Facebook, LinkedIn, Instagram, X/Twitter, and TikTok for profiles or posts.",
                url = "https://www.google.com/search?q=$qSocial",
                toolSource = "PhoneInfoga"
            ),
            OsintDork(
                title = "PhoneInfoga: Data Leaks & Pastes",
                description = "Searches Pastebin, Ghostbin, GitHub, and text dumps for leaked logs or pastes.",
                url = "https://www.google.com/search?q=$qPastes",
                toolSource = "PhoneInfoga"
            ),
            OsintDork(
                title = "PhoneInfoga: Documents & Files",
                description = "Locates indexed PDF, Word, and Excel documents containing this phone number.",
                url = "https://www.google.com/search?q=$qDocuments",
                toolSource = "PhoneInfoga"
            ),
            OsintDork(
                title = "PhoneInfoga: Spam & Caller DBs",
                description = "Checks WhoCalled, Tellows, ShouldICall, and SpamCalls reporting databases.",
                url = "https://www.google.com/search?q=$qSpam",
                toolSource = "PhoneInfoga"
            ),
            OsintDork(
                title = "PhoneInfoga: Business & Official Records",
                description = "Searches UK Companies House, Gov.uk, and corporate registers.",
                url = "https://www.google.com/search?q=$qCompanies",
                toolSource = "PhoneInfoga"
            )
        )
    }

    fun getPhoneInfogaExternalTools(normalizedNumber: String, localNumber: String): List<OsintDork> {
        val digits = normalizedNumber.replace("+", "").replace(Regex("[^0-9]"), "")
        val localDigits = localNumber.replace(Regex("[^0-9]"), "")

        return listOf(
            OsintDork(
                title = "NumLookup (Free Reverse Lookup)",
                description = "Open an external lookup page; coverage and identity results are not guaranteed.",
                url = "https://www.numlookup.com/number/$digits",
                toolSource = "PhoneInfoga"
            ),
            OsintDork(
                title = "SpyDialer Caller Search",
                description = "Open an external reverse-lookup page; it may be region-limited or unavailable.",
                url = "https://www.spydialer.com/search.aspx?s=$digits",
                toolSource = "PhoneInfoga"
            ),
            OsintDork(
                title = "Sync.ME Directory Search",
                description = "Open an external caller-ID search; any result is crowd-sourced and unverified.",
                url = "https://sync.me/search/?number=%2B$digits",
                toolSource = "PhoneInfoga"
            ),
            OsintDork(
                title = "WhoCalls.me Report Check",
                description = "Community caller feedback and nuisance call report search.",
                url = "https://whocallsme.com/Phone-Number.aspx/$localDigits",
                toolSource = "PhoneInfoga"
            )
        )
    }

    // --- Phonia OSINT Engine ---
    fun getPhoniaTools(normalizedNumber: String): List<OsintDork> {
        val digits = normalizedNumber.replace("+", "").replace(Regex("[^0-9]"), "")
        val countryCode = when {
            normalizedNumber.startsWith("+44") -> "uk"
            normalizedNumber.startsWith("+1") -> "us"
            normalizedNumber.startsWith("+33") -> "fr"
            normalizedNumber.startsWith("+49") -> "de"
            normalizedNumber.startsWith("+91") -> "in"
            else -> "global"
        }

        return listOf(
            OsintDork(
                title = "Phonia: WhatsApp Direct (wa.me)",
                description = "Opens a WhatsApp contact link; an account match does not prove identity.",
                url = "https://wa.me/$digits",
                toolSource = "Phonia"
            ),
            OsintDork(
                title = "Phonia: Telegram Direct (t.me)",
                description = "Opens a Telegram link; availability and identity are not guaranteed.",
                url = "https://t.me/+$digits",
                toolSource = "Phonia"
            ),
            OsintDork(
                title = "Phonia: Truecaller Web Lookup",
                description = "Open Truecaller web search; sign-in, region limits, or no result may apply.",
                url = "https://www.truecaller.com/search/$countryCode/$digits",
                toolSource = "Phonia"
            ),
            OsintDork(
                title = "Phonia: Viber Direct Trigger",
                description = "Open Viber contact lookup if the app supports this link on your device.",
                url = "viber://chat?number=%2B$digits",
                toolSource = "Phonia"
            )
        )
    }

    // --- IntelBase OSINT Hub ---
    fun getIntelBaseUrl(): String {
        return "https://intelbase.is/"
    }

    fun getIntelBaseDork(query: String): OsintDork {
        val encoded = Uri.encode(query)
        return OsintDork(
            title = "IntelBase OSINT Intelligence Hub",
            description = "Access multi-source OSINT database portal (Intelbase.is) for phone, email, and username tracking.",
            url = "https://intelbase.is/?q=$encoded",
            toolSource = "IntelBase"
        )
    }

    // --- Blackbird OSINT Engine (Username / Email OSINT) ---
    fun getBlackbirdUsernameScan(username: String): List<SocialPlatformResult> {
        val u = username.trim().removePrefix("@")
        if (u.isEmpty()) return emptyList()

        return listOf(
            SocialPlatformResult("GitHub", "Development", "https://github.com/$u", "https://github.com/search?q=$u&type=users"),
            SocialPlatformResult("X / Twitter", "Social Media", "https://x.com/$u", "https://x.com/search?q=$u"),
            SocialPlatformResult("Instagram", "Social Media", "https://instagram.com/$u", "https://www.google.com/search?q=site:instagram.com+\"$u\""),
            SocialPlatformResult("Reddit", "Community", "https://www.reddit.com/user/$u", "https://www.reddit.com/search/?q=$u"),
            SocialPlatformResult("TikTok", "Video & Social", "https://www.tiktok.com/@$u", "https://www.google.com/search?q=site:tiktok.com+\"@$u\""),
            SocialPlatformResult("LinkedIn", "Professional", "https://www.linkedin.com/in/$u", "https://www.google.com/search?q=site:linkedin.com/in/+\"$u\""),
            SocialPlatformResult("YouTube", "Video", "https://www.youtube.com/@$u", "https://www.youtube.com/results?search_query=$u"),
            SocialPlatformResult("Pinterest", "Social Media", "https://www.pinterest.com/$u", "https://www.pinterest.com/search/users/?q=$u"),
            SocialPlatformResult("Twitch", "Streaming", "https://www.twitch.tv/$u", "https://www.google.com/search?q=site:twitch.tv+\"$u\""),
            SocialPlatformResult("Telegram", "Messaging", "https://t.me/$u", "https://www.google.com/search?q=site:t.me+\"$u\""),
            SocialPlatformResult("Spotify", "Music", "https://open.spotify.com/user/$u", "https://www.google.com/search?q=site:open.spotify.com/user/+\"$u\""),
            SocialPlatformResult("Steam", "Gaming", "https://steamcommunity.com/id/$u", "https://steamcommunity.com/actions/Search?K=$u"),
            SocialPlatformResult("Medium", "Blogging", "https://medium.com/@$u", "https://medium.com/search?q=$u"),
            SocialPlatformResult("GitLab", "Development", "https://gitlab.com/$u", "https://gitlab.com/search?search=$u"),
            SocialPlatformResult("Keybase", "Identity & Crypto", "https://keybase.io/$u", "https://keybase.io/$u")
        )
    }

    fun getBlackbirdEmailScan(email: String): List<OsintDork> {
        val e = email.trim()
        val encodedEmail = Uri.encode("\"$e\"")

        return listOf(
            OsintDork(
                title = "Blackbird: Google Breach & Leak Search",
                description = "Searches public databases, leaks, and dump files containing this email address.",
                url = "https://www.google.com/search?q=$encodedEmail",
                toolSource = "Blackbird OSINT"
            ),
            OsintDork(
                title = "HaveIBeenPwned Breach Dork",
                description = "Searches HaveIBeenPwned index and public breach logs for exposed records.",
                url = "https://www.google.com/search?q=site:haveibeenpwned.com+$encodedEmail",
                toolSource = "Blackbird OSINT"
            ),
            OsintDork(
                title = "Epieos Email OSINT",
                description = "Reverse lookup for Google account info, profile photo, and registered services.",
                url = "https://epieos.com/?q=$e&t=email",
                toolSource = "Blackbird OSINT"
            ),
            OsintDork(
                title = "IntelBase Email Intelligence",
                description = "Query IntelBase portal for domain records and email linkages.",
                url = "https://intelbase.is/",
                toolSource = "IntelBase"
            )
        )
    }

    // --- Tookie OSINT / Brib Engine ---
    fun getTookieBribDorks(target: String): List<OsintDork> {
        val t = Uri.encode("\"$target\"")
        return listOf(
            OsintDork(
                title = "Tookie-OSINT: Broad Alias Footprint",
                description = "Executes multi-platform search across forum archives, breach logs, and paste sites.",
                url = "https://www.google.com/search?q=$t+(site:pastebin.com+OR+site:github.com+OR+site:reddit.com+OR+site:hackforums.net+OR+site:cracked.io+OR+site:nulled.to)",
                toolSource = "Tookie OSINT / Brib"
            ),
            OsintDork(
                title = "Tookie-OSINT: Keybase & Identity Trace",
                description = "Searches cryptographic identities and verified account mappings.",
                url = "https://www.google.com/search?q=site:keybase.io+$t",
                toolSource = "Tookie OSINT / Brib"
            )
        )
    }
}
