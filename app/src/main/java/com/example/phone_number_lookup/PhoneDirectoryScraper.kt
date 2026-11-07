package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class PhoneAccountHit(
    val platform: String,
    val status: String,
    val detail: String,
    val url: String? = null,
    val confirmed: Boolean = true
) {
    val details: String get() = detail
    val username: String?
        get() = platform.substringAfter("(@", "").substringBefore(")", "").ifBlank { null }
}

data class DirectoryReportSummary(
    val source: String,
    val url: String,
    val verdictLabel: String? = null,
    val reportCount: String? = null,
    val callerCategory: String? = null,
    val callerName: String? = null,
    val userComments: List<String> = emptyList()
)

data class CrawledPageMatch(
    val url: String,
    val pageTitle: String,
    val contextSnippet: String,
    val emailsFound: List<String> = emptyList(),
    val postcodesFound: List<String> = emptyList(),
    val socialLinksFound: List<String> = emptyList()
)

data class DeepPhoneOsintReport(
    val e164: String,
    val numberSpellings: List<String> = emptyList(),
    val carrierAllocationHint: String? = null,
    val areaLocationHint: String? = null,
    val accountRegistrations: List<PhoneAccountHit> = emptyList(),
    val directoryReports: List<DirectoryReportSummary> = emptyList(),
    val businessMatches: List<AttributedValue> = emptyList(),
    val crawledPages: List<CrawledPageMatch> = emptyList(),
    val discoveredNames: List<AttributedValue> = emptyList(),
    val discoveredLocations: List<AttributedValue> = emptyList(),
    val discoveredEmails: List<AttributedValue> = emptyList(),
    val discoveredHandles: List<AttributedValue> = emptyList(),
    val extraWebResults: List<SearchResultItem> = emptyList()
)

object PhoneDirectoryScraper {

    private val TAG_STRIP = Regex("<[^>]*>")
    private val SCRIPT_STYLE_STRIP = Regex("""<(script|style|nav|header|footer)\b.*?</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val WS_CLEAN = Regex("\\s+")
    private val TITLE_REGEX = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val EMAIL_REGEX = Regex("""[a-zA-Z0-9._%+-]{2,64}@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,12}""")
    private val UK_POSTCODE_REGEX = Regex("""\b([A-Z]{1,2}\d[A-Z\d]?\s+\d[A-Z]{2})\b""")
    private val SOCIAL_LINK_REGEX = Regex(
        """https?://(?:www\.)?(?:facebook\.com|instagram\.com|linkedin\.com/in|linkedin\.com/company|x\.com|twitter\.com|tiktok\.com/@|t\.me|youtube\.com/@)[a-zA-Z0-9_./@-]+""",
        RegexOption.IGNORE_CASE
    )

    // Generic lookup/SEO sites whose own footer links & office locations must NEVER be attributed to the phone number
    private val GENERIC_LOOKUP_HOSTS = setOf(
        "sync.me", "tellows.co.uk", "tellows.com", "scannero.com", "scannero.io",
        "whoiscallingme.uk", "whoiscallingyou.co.uk", "who-called.co.uk",
        "shouldianswer.co.uk", "shouldianswer.com", "whocallsme.com",
        "unknownphone.com", "800notes.com", "numlookup.com", "spydialer.com",
        "truecaller.com", "phonenumbers.org.uk", "ukphoneinfo.com", "area-codes.org.uk"
    )

    // Boilerplate site-owner social handles to ignore
    private val IGNORED_SOCIAL_HANDLES = setOf(
        "syncme", "sync-me", "tellows", "tellows_uk", "tellows_callerid_lookup",
        "shouldianswer", "truecaller", "scannero", "whocalledme", "unknownphone",
        "share", "intent", "sharer", "home", "login", "privacy", "terms"
    )

    suspend fun scrapePhoneFootprint(normalized: String): DeepPhoneOsintReport = withContext(Dispatchers.IO) {
        val parsed = DorkNumbers.from(normalized)
        val e164 = parsed?.number?.e164 ?: normalized
        val rawLocal = parsed?.number?.rawLocal ?: if (normalized.startsWith("+44")) {
            "0" + normalized.removePrefix("+44").filter { it.isDigit() }
        } else {
            normalized.filter { it.isDigit() }
        }
        val coreDigits = rawLocal.filter { it.isDigit() }.removePrefix("0").takeLast(9)

        val allSpellings = buildAllNumberSpellings(e164, rawLocal, parsed?.number)
        val carrierHint = lookupUkOriginalCarrier(rawLocal)
        val areaHint = PhoneNumberUtils.getAreaInfo(normalized)

        coroutineScope {
            val accountsDef = async {
                kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                    checkPhoneAccountRegistrations(e164, rawLocal)
                }.orEmpty()
            }
            val whoCalledDef = async { kotlinx.coroutines.withTimeoutOrNull(7_000L) { scrapeWhoCalledUk(rawLocal) } }
            val shouldIAnswerDef = async { kotlinx.coroutines.withTimeoutOrNull(7_000L) { scrapeShouldIAnswerUk(rawLocal) } }
            val tellowsDef = async { kotlinx.coroutines.withTimeoutOrNull(7_000L) { scrapeTellowsUkHtml(rawLocal) } }
            val whoCallsMeDef = async { kotlinx.coroutines.withTimeoutOrNull(7_000L) { scrapeWhoCallsMe(rawLocal) } }
            val unknownPhoneDef = async { kotlinx.coroutines.withTimeoutOrNull(7_000L) { scrapeUnknownPhone(rawLocal) } }
            val notes800Def = async { kotlinx.coroutines.withTimeoutOrNull(7_000L) { scrape800Notes(rawLocal) } }
            val osmDef = async {
                kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                    scrapeOsmOverpassDetailed(e164, rawLocal)
                }.orEmpty()
            }
            val webDef = async {
                kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                    val primaryOr = allSpellings.take(5).joinToString(" OR ") { "\"$it\"" }
                    val socialQuery = "(\"$rawLocal\" OR \"$e164\") (site:facebook.com OR site:instagram.com OR site:linkedin.com OR site:x.com OR site:tiktok.com OR site:gumtree.com OR site:yell.com)"
                    OpenWebOsintScraper.scrapeAll(
                        queries = listOf(primaryOr, socialQuery),
                        targetPhoneE164 = e164
                    )
                } ?: ScrapedWebReport(query = "", results = emptyList())
            }

            val accountHits = accountsDef.await()
            val directories = listOfNotNull(
                whoCalledDef.await(),
                shouldIAnswerDef.await(),
                tellowsDef.await(),
                whoCallsMeDef.await(),
                unknownPhoneDef.await(),
                notes800Def.await()
            )

            val webReport = webDef.await()
            val osmMatches = (osmDef.await() + webReport.businessMatches).distinctBy { it.value.lowercase() }

            // Filter out generic prefix-only SEO pages (like sync.me/?prefix=7795 or scannero.com homepages)
            val verifiedWebResults = webReport.results.filter { item ->
                val urlLower = item.url.lowercase()
                val isPrefixSpam = urlLower.contains("prefix=") ||
                    urlLower.contains("scannero.") ||
                    urlLower.contains("whoiscallingyou.co.uk") ||
                    urlLower.contains("whoiscallingme.uk")
                !isPrefixSpam
            }

            // Only crawl pages that are NOT generic caller-ID lookup sites
            val crawlSemaphore = Semaphore(3)
            val crawledPages = verifiedWebResults
                .filter { item ->
                    val host = hostOf(item.url)
                    item.url.startsWith("http") &&
                        !item.url.contains("duckduckgo.com") &&
                        GENERIC_LOOKUP_HOSTS.none { host == it || host.endsWith(".$it") }
                }
                .take(6)
                .map { item ->
                    async {
                        crawlSemaphore.withPermit {
                            crawlMatchedPage(item.url, item.title, allSpellings, coreDigits)
                        }
                    }
                }.awaitAll().filterNotNull()

            val names = mutableListOf<AttributedValue>()
            val locations = mutableListOf<AttributedValue>()
            val emails = mutableListOf<AttributedValue>()
            val handles = mutableListOf<AttributedValue>()

            if (areaHint != null && !areaHint.contains("mobile range", ignoreCase = true)) {
                locations += AttributedValue("$areaHint (UK Area Prefix)", "Numbering Plan")
            }

            // Pull any masked emails or handles returned by account registration probes (e.g. Instagram)
            accountHits.forEach { hit ->
                if (hit.detail.contains("@")) {
                    EMAIL_REGEX.findAll(hit.detail).forEach { m ->
                        emails += AttributedValue(m.value, hit.platform)
                    }
                    // Also capture masked emails like d***@gmail.com
                    Regex("""[a-zA-Z0-9]\*{2,10}[a-zA-Z0-9]*@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,10}""").findAll(hit.detail).forEach { m ->
                        emails += AttributedValue(m.value, "${hit.platform} (Masked Recovery)")
                    }
                }
            }

            directories.forEach { dir ->
                dir.callerName?.let { names += AttributedValue(it, dir.source) }
                dir.userComments.forEach { comment ->
                    locations += EmailAttributionEngine.extractLocationsFromText(comment, dir.source)
                }
            }

            // Only extract locations/emails/handles from non-directory web results
            verifiedWebResults.forEach { r ->
                val host = hostOf(r.url)
                if (GENERIC_LOOKUP_HOSTS.none { host == it || host.endsWith(".$it") }) {
                    val blob = "${r.title} ${r.snippet}"
                    locations += EmailAttributionEngine.extractLocationsFromText(blob, r.source)
                    EMAIL_REGEX.findAll(blob).forEach { m ->
                        emails += AttributedValue(m.value.lowercase(), r.source)
                    }
                }
            }

            crawledPages.forEach { page ->
                val host = hostOf(page.url).ifBlank { "Web Page" }
                page.emailsFound.forEach { emails += AttributedValue(it, host) }
                page.postcodesFound.forEach { locations += AttributedValue("Postcode: $it", host) }
                page.socialLinksFound.forEach { handles += AttributedValue(it, host) }
                locations += EmailAttributionEngine.extractLocationsFromText(page.contextSnippet, host)
            }

            DeepPhoneOsintReport(
                e164 = e164,
                numberSpellings = allSpellings,
                carrierAllocationHint = carrierHint,
                areaLocationHint = areaHint,
                accountRegistrations = accountHits,
                directoryReports = directories,
                businessMatches = osmMatches,
                crawledPages = crawledPages,
                discoveredNames = names.distinctBy { it.value.lowercase() },
                discoveredLocations = locations.distinctBy { it.value.lowercase() },
                discoveredEmails = emails.distinctBy { it.value.lowercase() },
                discoveredHandles = handles.distinctBy { it.value.lowercase() },
                extraWebResults = verifiedWebResults
            )
        }
    }

    /**
     * Directly probes account-registration and social endpoints for a phone number
     * (Ignorant / Holehe-for-Phones style).
     */
    internal suspend fun checkPhoneAccountRegistrations(
        e164: String,
        rawLocal: String
    ): List<PhoneAccountHit> = coroutineScope {
        val jobs = listOf(
            async { kotlinx.coroutines.withTimeoutOrNull(6_000L) { probeMicrosoftAccountByPhone(e164, rawLocal) } },
            async { kotlinx.coroutines.withTimeoutOrNull(6_000L) { probeInstagramByPhone(e164, rawLocal) } },
            async { kotlinx.coroutines.withTimeoutOrNull(6_000L) { probeTelegramByPhone(e164) } },
            async { kotlinx.coroutines.withTimeoutOrNull(6_000L) { probeGitHubByPhone(e164, rawLocal) } },
            async { kotlinx.coroutines.withTimeoutOrNull(6_000L) { probeOpenPgpByPhone(e164, rawLocal) } },
            async { probeWhatsAppBridge(e164) }
        )
        jobs.awaitAll().filterNotNull()
    }

    /**
     * Microsoft's public GetCredentialType endpoint accepts a phone number and returns
     * `"IfExistsResult": 0` when the phone number is registered to a Microsoft / Outlook / Xbox / Skype account.
     */
    private fun probeMicrosoftAccountByPhone(e164: String, rawLocal: String): PhoneAccountHit? {
        for (candidate in listOf(e164, rawLocal).distinct()) {
            val payload = """{"username":"$candidate","isOtherIdpSupported":true,"checkPhones":true,"isRemoteNGCSupported":true,"isCookieBannerShown":false,"isFidoSupported":true}"""
            val resp = ScanHttp.post(
                "https://login.live.com/GetCredentialType.srf",
                payload,
                contentType = "application/json",
                timeoutMs = 8_000,
                headers = mapOf("Accept" to "application/json")
            )
            if (resp.isOk && resp.body.isNotBlank()) {
                val existsResult = runCatching {
                    JSONObject(resp.body).optInt("IfExistsResult", -1)
                }.getOrDefault(-1)
                if (existsResult == 0 || existsResult == 5 || existsResult == 6) {
                    return PhoneAccountHit(
                        platform = "Microsoft / Xbox / Skype",
                        status = "Registered response",
                        detail = "Phone number ($candidate) is linked to an active Microsoft account (IfExistsResult=$existsResult).",
                        url = "https://login.live.com/",
                        confirmed = true
                    )
                }
            }
        }
        return null
    }

    /**
     * Queries Instagram's account lookup endpoint with the phone number. When matched, Instagram
     * often returns `obfuscated_email`, `has_valid_phone`, or the matched username.
     */
    private fun probeInstagramByPhone(e164: String, rawLocal: String): PhoneAccountHit? {
        for (candidate in listOf(e164, rawLocal).distinct()) {
            val resp = ScanHttp.post(
                "https://www.instagram.com/api/v1/users/lookup/",
                "q=${ScanHttp.encode(candidate)}",
                contentType = "application/x-www-form-urlencoded",
                timeoutMs = 8_000,
                headers = mapOf(
                    "X-CSRFToken" to "missing",
                    "X-IG-App-ID" to "936619743392459",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                )
            )
            if (resp.isOk && resp.body.isNotBlank()) {
                val hit = runCatching {
                    val json = JSONObject(resp.body)
                    val hasPhone = json.optBoolean("has_valid_phone", false)
                    val obfEmail = json.optString("obfuscated_email").takeIf { it.isNotBlank() && it != "null" }
                    val userObj = json.optJSONObject("user")
                    val username = userObj?.optString("username")?.takeIf { it.isNotBlank() }
                    val emailSent = json.optBoolean("email_sent", false)
                    if (hasPhone || obfEmail != null || username != null || emailSent) {
                        val details = buildList {
                            add("Instagram account matched $candidate")
                            username?.let { add("Username: @$it") }
                            obfEmail?.let { add("Recovery Email: $it") }
                        }.joinToString(" • ")
                        PhoneAccountHit(
                            platform = "Instagram",
                            status = "Registered response",
                            detail = details,
                            url = username?.let { "https://www.instagram.com/$it/" } ?: "https://www.instagram.com/",
                            confirmed = true
                        )
                    } else null
                }.getOrNull()
                if (hit != null) return hit
            }
        }
        return null
    }

    /**
     * Checks if `t.me/+44...` resolves to a public Telegram profile page with a display title.
     */
    private fun probeTelegramByPhone(e164: String): PhoneAccountHit? {
        val clean = if (e164.startsWith("+")) e164 else "+${e164.filter { it.isDigit() }}"
        val url = "https://t.me/$clean"
        val resp = ScanHttp.get(url, timeoutMs = 8_000)
        if (!resp.isOk || resp.body.isBlank()) return null
        val html = resp.body
        val titleMatch = Regex("""<div[^>]*class=["'][^"']*tgme_page_title[^"']*["'][^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.cleanHtml()
        val extraMatch = Regex("""<div[^>]*class=["'][^"']*tgme_page_extra[^"']*["'][^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.cleanHtml()

        return if (!titleMatch.isNullOrBlank()) {
            PhoneAccountHit(
                platform = "Telegram",
                status = "Registered response",
                detail = listOfNotNull("Public Telegram user: $titleMatch", extraMatch).joinToString(" • "),
                url = url,
                confirmed = true
            )
        } else null
    }

    private fun probeWhatsAppBridge(e164: String): PhoneAccountHit {
        val digits = e164.filter { it.isDigit() }
        val checkUrl = "https://api.whatsapp.com/send/?phone=$digits&text=&type=phone_number&app_absent=0"
        val resp = ScanHttp.get(checkUrl, timeoutMs = 7_000)
        val isConfirmed = resp.isOk && resp.body.contains("Chat on WhatsApp", ignoreCase = true) &&
            !resp.body.contains("Phone number shared via url is invalid", ignoreCase = true)
        return PhoneAccountHit(
            platform = "WhatsApp",
            status = if (isConfirmed) "Registered response" else "Direct Verification Link",
            detail = if (isConfirmed) "Active WhatsApp account confirmed for +$digits." else "Tap to open WhatsApp profile card & message for +$digits.",
            url = "https://wa.me/$digits",
            confirmed = isConfirmed
        )
    }

    private fun probeSnapchatByPhone(e164: String): PhoneAccountHit {
        val digits = e164.filter { it.isDigit() }
        val resp = ScanHttp.post(
            "https://accounts.snapchat.com/accounts/merlin/login",
            """{"phone_number":"+$digits"}""",
            contentType = "application/json",
            timeoutMs = 6_000
        )
        val isRegistered = resp.body.contains("status\":\"SUCCESS", ignoreCase = true) ||
            resp.body.contains("challenge", ignoreCase = true) ||
            resp.body.contains("requires_verification", ignoreCase = true)
        return PhoneAccountHit(
            platform = "Snapchat",
            status = if (isRegistered) "Registered response" else "Account Check Target",
            detail = if (isRegistered) "Snapchat account associated with +$digits." else "Snapchat account verification endpoint for +$digits.",
            url = "https://accounts.snapchat.com",
            confirmed = isRegistered
        )
    }

    private fun probeAmazonByPhone(e164: String): PhoneAccountHit {
        return PhoneAccountHit(
            platform = "Amazon",
            status = "Account Sign-In Target",
            detail = "Check linked Amazon / Prime customer account for $e164.",
            url = "https://www.amazon.com/ap/signin",
            confirmed = false
        )
    }

    private fun probeTwitterByPhone(e164: String): PhoneAccountHit {
        val digits = e164.filter { it.isDigit() }
        return PhoneAccountHit(
            platform = "Twitter / X",
            status = "Account Recovery Target",
            detail = "Twitter / X account recovery lookup for +$digits.",
            url = "https://twitter.com/account/begin_password_reset",
            confirmed = false
        )
    }

    private fun probeGoogleByPhone(e164: String): PhoneAccountHit {
        val digits = e164.filter { it.isDigit() }
        return PhoneAccountHit(
            platform = "Google Account",
            status = "Account Recovery Target",
            detail = "Google account recovery endpoint accepts +$digits to verify linked services.",
            url = "https://accounts.google.com/signin/recovery",
            confirmed = false
        )
    }

    private fun probeAppleByPhone(e164: String): PhoneAccountHit {
        val digits = e164.filter { it.isDigit() }
        return PhoneAccountHit(
            platform = "Apple ID / iMessage",
            status = "Apple Directory Target",
            detail = "Verify Apple ID registration and iMessage routing for +$digits.",
            url = "https://iforgot.apple.com/password/verify/appleid",
            confirmed = false
        )
    }

    private fun probeViberByPhone(e164: String): PhoneAccountHit {
        val digits = e164.filter { it.isDigit() }
        return PhoneAccountHit(
            platform = "Viber",
            status = "Messaging Profile Target",
            detail = "Direct chat profile lookup for +$digits on Viber.",
            url = "https://chats.viber.com/",
            confirmed = false
        )
    }

    /**
     * Searches GitHub issues, commits, and repositories for exact mentions of the phone number.
     */
    private fun probeGitHubByPhone(e164: String, rawLocal: String): PhoneAccountHit? {
        val headers = mapOf("Accept" to "application/vnd.github+json")
        for (q in listOf("\"$e164\"", "\"$rawLocal\"").distinct()) {
            val resp = ScanHttp.get(
                "https://api.github.com/search/issues?per_page=3&q=${ScanHttp.encode(q)}",
                timeoutMs = 8_000,
                headers = headers
            )
            if (resp.isOk && resp.body.isNotBlank()) {
                val hit = runCatching {
                    val root = JSONObject(resp.body)
                    val count = root.optInt("total_count", 0)
                    val first = root.optJSONArray("items")?.optJSONObject(0)
                    if (count > 0 && first != null) {
                        val user = first.optJSONObject("user")?.optString("login").orEmpty()
                        val htmlUrl = first.optString("html_url")
                        PhoneAccountHit(
                            platform = "GitHub",
                            status = "Found in Public GitHub Record",
                            detail = "$count public GitHub match(es) (Author: @$user)",
                            url = htmlUrl,
                            confirmed = true
                        )
                    } else null
                }.getOrNull()
                if (hit != null) return hit
            }
        }
        return null
    }

    private fun probeOpenPgpByPhone(e164: String, rawLocal: String): PhoneAccountHit? {
        for (q in listOf(e164, rawLocal).distinct()) {
            val records = IdentityEnricher.fetchPgpKeys(q)
            if (records.isNotEmpty()) {
                val uids = records.flatMap { it.uids }.distinct().joinToString(", ")
                return PhoneAccountHit(
                    platform = "OpenPGP Keyserver",
                    status = "PGP Identity Found",
                    detail = "UIDs: $uids",
                    url = "https://keyserver.ubuntu.com/pks/lookup?search=${ScanHttp.encode(q)}&op=index",
                    confirmed = true
                )
            }
        }
        return null
    }

    internal fun buildAllNumberSpellings(e164: String, rawLocal: String, dorkNumber: DorkNumber?): List<String> {
        val out = LinkedHashSet<String>()
        val digits = rawLocal.filter { it.isDigit() }
        if (digits.isNotBlank()) out += digits
        if (e164.isNotBlank()) out += e164
        dorkNumber?.spellings?.forEach { out += it }

        if (digits.length == 11 && digits.startsWith("0")) {
            val noZero = digits.removePrefix("0")
            out += "${digits.substring(0, 5)} ${digits.substring(5)}"
            out += "${digits.substring(0, 5)} ${digits.substring(5, 8)} ${digits.substring(8)}"
            if (digits.startsWith("02")) {
                out += "${digits.substring(0, 3)} ${digits.substring(3, 7)} ${digits.substring(7)}"
            } else if (digits.startsWith("011") || digits.matches(Regex("01[234569]1\\d{7}"))) {
                out += "${digits.substring(0, 4)} ${digits.substring(4, 7)} ${digits.substring(7)}"
            }
            out += "+44 ${noZero.substring(0, 4)} ${noZero.substring(4)}"
            out += "+44 (0) ${noZero.substring(0, 4)} ${noZero.substring(4)}"
            out += "+44(0)${noZero}"
            out += "0044$noZero"
            out += "${digits.substring(0, 5)}-${digits.substring(5)}"
            out += "(${digits.substring(0, 5)}) ${digits.substring(5)}"
        }
        return out.filter { it.isNotBlank() }.toList()
    }

    internal fun lookupUkOriginalCarrier(rawLocal: String): String? {
        val d = rawLocal.filter { it.isDigit() }
        if (!d.startsWith("07") || d.length != 11) return null
        val p4 = d.take(4)
        val p5 = d.take(5)
        val network = when {
            p5 in setOf("07400", "07401", "07402", "07403", "07411", "07442", "07443", "07444", "07450", "07453") -> "O2 (Telefónica UK)"
            p4 in setOf("0754", "0756", "0759", "0771", "0773", "0780", "0784", "0785", "0787", "0789", "0790", "0791", "0792", "0793") -> "O2 (Telefónica UK)"
            p4 in setOf("0746", "0747", "0755", "0758", "0776", "0777", "0778", "0779", "0782", "0783", "0786", "0788") -> "Vodafone UK"
            p4 in setOf("0741", "0742", "0743", "0745", "0748", "0749", "0750", "0752", "0753", "0757", "0770", "0772", "0794", "0795", "0796", "0797", "0798") -> "EE (T-Mobile / Orange UK)"
            p4 in setOf("0740", "0744", "0781") || p5.startsWith("073") -> "Three UK (Hutchison 3G) / MVNO block"
            p4 == "0799" -> "Vodafone / Telecoms Cloud range"
            d.startsWith("070") -> "UK Personal Numbering / Call-Forwarding Range (Ofcom 070)"
            d.startsWith("076") -> "UK Radiopaging Range (Ofcom 076)"
            else -> "UK Mobile Range (${d.take(5)}…)"
        }
        return "$network (Original Ofcom block allocation — may be ported to another network)"
    }

    internal fun scrapeWhoCalledUk(rawLocal: String): DirectoryReportSummary? {
        val digits = rawLocal.filter { it.isDigit() }
        if (digits.length < 9) return null
        val url = "https://who-called.co.uk/Number/$digits"
        val resp = ScanHttp.get(
            url,
            timeoutMs = 6_000,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-GB,en;q=0.9",
                "Referer" to "https://who-called.co.uk/"
            )
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        val html = resp.body

        val rateRegex = Regex("""(?is)overall\s+rating\s+is\s*<[^>]*>([^<]+)</""")
        val searchesRegex = Regex("""(?is)Number\s+of\s+searches[:\s]*</[^>]+>\s*<[^>]+>(\d+)""")
        val commentRegex = Regex("""(?is)<div[^>]*class=["'][^"']*commentText[^"']*["'][^>]*>(.*?)</div>""")

        val verdict = rateRegex.find(html)?.groupValues?.get(1)?.cleanHtml()
        val searches = searchesRegex.find(html)?.groupValues?.get(1)?.cleanHtml()
        val comments = commentRegex.findAll(html)
            .map { it.groupValues[1].cleanHtml() }
            .filter { it.length > 10 }
            .take(8)
            .toList()

        if (verdict == null && comments.isEmpty() && searches == null) return null
        return DirectoryReportSummary(
            source = "Who-Called.co.uk",
            url = url,
            verdictLabel = verdict,
            reportCount = searches?.let { "$it searches" },
            userComments = comments
        )
    }

    internal fun scrapeShouldIAnswerUk(rawLocal: String): DirectoryReportSummary? {
        val digits = rawLocal.filter { it.isDigit() }
        if (digits.length < 9) return null
        val url = "https://www.shouldianswer.co.uk/phone-number/$digits"
        val resp = ScanHttp.get(url, timeoutMs = 10_000)
        if (!resp.isOk || resp.body.isBlank()) return null
        val html = resp.body

        val scoreRegex = Regex("""(?is)<div[^>]*class=["'][^"']*score\s+(?:negative|positive|neutral)[^"']*["'][^>]*>(.*?)</div>""")
        val categoryRegex = Regex("""(?is)<span[^>]*class=["'][^"']*category[^"']*["'][^>]*>(.*?)</span>""")
        val reviewRegex = Regex("""(?is)<p[^>]*itemprop=["']reviewBody["'][^>]*>(.*?)</p>""")

        val verdict = scoreRegex.find(html)?.groupValues?.get(1)?.cleanHtml()
        val cat = categoryRegex.find(html)?.groupValues?.get(1)?.cleanHtml()
        val reviews = reviewRegex.findAll(html)
            .map { it.groupValues[1].cleanHtml() }
            .filter { it.length > 10 }
            .take(8)
            .toList()

        if (verdict == null && reviews.isEmpty() && cat == null) return null
        return DirectoryReportSummary(
            source = "Should I Answer UK",
            url = url,
            verdictLabel = verdict,
            callerCategory = cat,
            userComments = reviews
        )
    }

    internal fun scrapeTellowsUkHtml(rawLocal: String): DirectoryReportSummary? {
        val digits = rawLocal.filter { it.isDigit() }
        if (digits.length < 9) return null
        val url = "https://www.tellows.co.uk/num/$digits"
        val resp = ScanHttp.get(url, timeoutMs = 10_000)
        if (!resp.isOk || resp.body.isBlank()) return null
        val html = resp.body

        val scoreMatch = Regex("""(?is)tellows\s+score[^0-9]*([1-9])\b""").find(html)?.groupValues?.get(1)
        val callerType = Regex("""(?is)Types\s+of\s+call[:\s]*</[^>]+>\s*<[^>]+>([^<]+)</""").find(html)?.groupValues?.get(1)?.cleanHtml()
        val callerName = Regex("""(?is)Caller\s+name[:\s]*</[^>]+>\s*<[^>]+>([^<]+)</""").find(html)?.groupValues?.get(1)?.cleanHtml()
        val commentRegex = Regex("""(?is)<p[^>]*class=["'][^"']*comment-body[^"']*["'][^>]*>(.*?)</p>""")
        val comments = commentRegex.findAll(html)
            .map { it.groupValues[1].cleanHtml() }
            .filter { it.length > 10 }
            .take(6)
            .toList()

        // Tellows score 5 with 0 comments is just an empty default page
        if ((scoreMatch == null || scoreMatch == "5") && callerType == null && callerName == null && comments.isEmpty()) return null
        return DirectoryReportSummary(
            source = "Tellows UK",
            url = url,
            verdictLabel = scoreMatch?.let { "Score $it/9" },
            callerCategory = callerType,
            callerName = callerName,
            userComments = comments
        )
    }

    internal fun scrapeWhoCallsMe(rawLocal: String): DirectoryReportSummary? {
        val digits = rawLocal.filter { it.isDigit() }
        if (digits.length < 9) return null
        val url = "https://whocallsme.com/Phone-Number.aspx/$digits"
        val resp = ScanHttp.get(url, timeoutMs = 10_000)
        if (!resp.isOk || resp.body.isBlank()) return null
        val html = resp.body

        val postRegex = Regex("""(?is)<div[^>]*class=["'][^"']*oos_pBody[^"']*["'][^>]*>(.*?)</div>""")
        val comments = postRegex.findAll(html)
            .map { it.groupValues[1].cleanHtml() }
            .filter { it.length > 15 }
            .take(6)
            .toList()

        if (comments.isEmpty()) return null
        return DirectoryReportSummary(
            source = "WhoCallsMe",
            url = url,
            reportCount = "${comments.size}+ community posts",
            userComments = comments
        )
    }

    internal fun scrapeUnknownPhone(rawLocal: String): DirectoryReportSummary? {
        val digits = rawLocal.filter { it.isDigit() }
        if (digits.length < 9) return null
        val url = "https://www.unknownphone.com/phone/$digits"
        val resp = ScanHttp.get(url, timeoutMs = 10_000)
        if (!resp.isOk || resp.body.isBlank()) return null
        val html = resp.body

        val ratingRegex = Regex("""(?is)(Dangerous|Harassing|Untrustworthy|Neutral|Safe)\s+number""")
        val commentRegex = Regex("""(?is)<div[^>]*class=["'][^"']*comment_content[^"']*["'][^>]*>(.*?)</div>""")
        val verdict = ratingRegex.find(html)?.groupValues?.get(1)?.cleanHtml()
        val comments = commentRegex.findAll(html)
            .map { it.groupValues[1].cleanHtml() }
            .filter { it.length > 12 }
            .take(6)
            .toList()

        if (verdict == null && comments.isEmpty()) return null
        return DirectoryReportSummary(
            source = "UnknownPhone",
            url = url,
            verdictLabel = verdict,
            userComments = comments
        )
    }

    internal fun scrape800Notes(rawLocal: String): DirectoryReportSummary? {
        val digits = rawLocal.filter { it.isDigit() }
        if (digits.length < 9) return null
        val url = "https://800notes.com/Phone.aspx/$digits"
        val resp = ScanHttp.get(url, timeoutMs = 10_000)
        if (!resp.isOk || resp.body.isBlank()) return null
        val html = resp.body

        val postRegex = Regex("""(?is)<div[^>]*class=["'][^"']*oos_previewBody[^"']*["'][^>]*>(.*?)</div>""")
        val comments = postRegex.findAll(html)
            .map { it.groupValues[1].cleanHtml() }
            .filter { it.length > 15 }
            .take(6)
            .toList()

        if (comments.isEmpty()) return null
        return DirectoryReportSummary(
            source = "800notes",
            url = url,
            reportCount = "${comments.size}+ reports",
            userComments = comments
        )
    }

    internal fun scrapeOsmOverpassDetailed(e164: String, rawLocal: String): List<AttributedValue> {
        val digits = e164.filter { it.isDigit() }.ifEmpty { rawLocal.filter { it.isDigit() } }
        if (digits.length < 8) return emptyList()
        val tail = digits.takeLast(9)
        val overpassQuery = """[out:json][timeout:10];(nwr["phone"~"$tail"];nwr["contact:phone"~"$tail"];);out tags 15;"""
        val url = "https://overpass-api.de/api/interpreter?data=${ScanHttp.encode(overpassQuery)}"
        val resp = ScanHttp.get(url, timeoutMs = 12_000, headers = mapOf("Accept" to "application/json"))
        if (!resp.isOk || resp.body.isBlank()) return emptyList()

        return runCatching {
            val elements = JSONObject(resp.body).optJSONArray("elements") ?: return emptyList()
            (0 until elements.length()).mapNotNull { i ->
                val tags = elements.optJSONObject(i)?.optJSONObject("tags") ?: return@mapNotNull null
                val name = tags.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val street = listOfNotNull(
                    tags.optString("addr:housenumber").takeIf { it.isNotBlank() },
                    tags.optString("addr:street").takeIf { it.isNotBlank() }
                ).joinToString(" ")
                val city = tags.optString("addr:city").ifBlank { tags.optString("addr:town") }
                val postcode = tags.optString("addr:postcode")
                val website = tags.optString("website").ifBlank { tags.optString("contact:website") }
                val email = tags.optString("email").ifBlank { tags.optString("contact:email") }
                val hours = tags.optString("opening_hours")

                val details = buildList {
                    add(name)
                    val addr = listOf(street, city, postcode).filter { it.isNotBlank() }.joinToString(", ")
                    if (addr.isNotBlank()) add("Address: $addr")
                    if (website.isNotBlank()) add("Web: $website")
                    if (email.isNotBlank()) add("Email: $email")
                    if (hours.isNotBlank()) add("Hours: $hours")
                }.joinToString(" • ")

                AttributedValue(details, "OpenStreetMap Directory")
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Fetches a matched web page, verifies that the actual phone number (not just a 4-digit prefix)
     * appears on the page, strips `<nav>`/`<header>`/`<footer>`, and extracts genuine contacts.
     */
    internal fun crawlMatchedPage(
        url: String,
        fallbackTitle: String,
        spellings: List<String>,
        coreDigits: String
    ): CrawledPageMatch? {
        val resp = ScanHttp.get(url, timeoutMs = 8_000, maxBodyChars = 250_000)
        if (!resp.isOk || resp.body.isBlank()) return null
        val html = resp.body

        // Verify the page actually contains the phone number (either a formatted spelling or the 9 core digits)
        val digitsOnlyHtml = html.filter { it.isDigit() }
        val matchesSpelling = spellings.any { html.contains(it, ignoreCase = true) }
        val matchesCoreDigits = coreDigits.length >= 9 && digitsOnlyHtml.contains(coreDigits)
        if (!matchesSpelling && !matchesCoreDigits) return null

        val title = TITLE_REGEX.find(html)?.groupValues?.get(1)?.cleanHtml()?.takeIf { it.isNotBlank() }
            ?: fallbackTitle
        val visibleText = html.replace(SCRIPT_STYLE_STRIP, " ").cleanHtml()

        var snippet = ""
        for (sp in spellings) {
            val idx = visibleText.indexOf(sp, ignoreCase = true)
            if (idx >= 0) {
                val start = (idx - 100).coerceAtLeast(0)
                val end = (idx + sp.length + 140).coerceAtMost(visibleText.length)
                snippet = visibleText.substring(start, end).trim()
                break
            }
        }
        if (snippet.isBlank()) {
            snippet = visibleText.take(240)
        }

        val emails = EMAIL_REGEX.findAll(html)
            .map { it.value.lowercase().trimEnd('.') }
            .filterNot {
                it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".svg") ||
                    it.contains("example.") || it.contains("sentry.")
            }
            .distinct()
            .take(6)
            .toList()

        val postcodes = UK_POSTCODE_REGEX.findAll(visibleText)
            .map { it.value.trim() }
            .distinct()
            .take(5)
            .toList()

        val socials = SOCIAL_LINK_REGEX.findAll(html)
            .map { it.value.trimEnd('/', '"', '\'', ')', '>') }
            .filterNot { link ->
                val tail = link.substringAfterLast('/').substringBefore('?').lowercase()
                tail in IGNORED_SOCIAL_HANDLES
            }
            .distinct()
            .take(6)
            .toList()

        return CrawledPageMatch(
            url = url,
            pageTitle = title.take(120),
            contextSnippet = snippet,
            emailsFound = emails,
            postcodesFound = postcodes,
            socialLinksFound = socials
        )
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.lowercase()?.removePrefix("www.").orEmpty() }.getOrDefault("")

    private fun String.cleanHtml(): String =
        BreachCatalog.plainText(replace(TAG_STRIP, " ")).replace(WS_CLEAN, " ").trim()
}
