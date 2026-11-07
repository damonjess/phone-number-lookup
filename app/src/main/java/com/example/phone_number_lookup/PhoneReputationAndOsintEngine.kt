package com.example.phone_number_lookup

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class SpamSeverity(val label: String) {
    SAFE("Safe"),
    NEUTRAL("Neutral"),
    TELEMARKETER("Telemarketer"),
    SILENT_CALL("Silent Call"),
    DEBT_COLLECTOR("Debt Collector"),
    SCAM_FRAUD("Scam/Fraud")
}

data class CommunityUserComment(
    val source: String,
    val authorOrCategory: String,
    val comment: String,
    val date: String? = null,
    val ratingTag: String? = null
)

data class SpamReputationSummary(
    val severity: SpamSeverity = SpamSeverity.NEUTRAL,
    val totalLookups: Int = 0,
    val totalUserReports: Int = 0,
    val interestTrend: String = "Normal",
    val communityCallerNames: List<AttributedValue> = emptyList(),
    val userComments: List<CommunityUserComment> = emptyList()
)

data class TelecomProfile(
    val e164Format: String,
    val nationalFormat: String,
    val compactNational: String,
    val internationalSpaced: String,
    val countryCode: String,
    val lineType: String,
    val isPossibleVoipOrVirtual: Boolean,
    val searchVariations: List<String>
)

data class MessagingAppLinkage(
    val platform: String,
    val status: String,
    val displayName: String? = null,
    val bioOrStatus: String? = null,
    val checkUrl: String,
    val verifiedPresence: Boolean,
    val avatarBase64: String? = null
)

data class SourceHealthItem(
    val sourceName: String,
    val status: String,
    val confidence: String,
    val detail: String,
    val url: String? = null
)

data class UnifiedPhoneIntelligenceReport(
    val telecom: TelecomProfile,
    val riskScore: Int,
    val riskGrade: String,
    val reputation: SpamReputationSummary,
    val webPages: List<SearchResultItem>,
    val corporateFilings: List<PublicRecordFiling>,
    val discoveredEmails: List<AttributedValue>,
    val discoveredNames: List<AttributedValue>,
    val discoveredAddresses: List<AttributedValue>,
    val discoveredSocials: List<AttributedProfileLink>,
    val messagingApps: List<MessagingAppLinkage>,
    val sourceHealth: List<SourceHealthItem>,
    val maskedPivotReport: MaskedPivotReport = MaskedPivotReport(),
    val fastApiResult: FastApiPhoneResult? = null,
    val telegramPhotoBase64: String? = null
)

object PhoneReputationAndOsintEngine {

    private val TAG_STRIP = Regex("<[^>]*>")
    private val WS_CLEAN = Regex("\\s+")
    private val EMAIL_REGEX = Regex("""[a-zA-Z0-9._%+-]{2,64}@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,12}""")
    private val UK_POSTCODE_REGEX = Regex("""\b([A-Z]{1,2}\d[A-Z\d]?\s+\d[A-Z]{2})\b""", RegexOption.IGNORE_CASE)
    private val SOCIAL_URL_REGEX = Regex(
        """https?://(?:www\.)?(?:x\.com|twitter\.com|linkedin\.com/in|facebook\.com|instagram\.com|github\.com|t\.me|tiktok\.com/@)[a-zA-Z0-9_./@-]+""",
        RegexOption.IGNORE_CASE
    )

    suspend fun analyzePhoneNumber(
        rawNumber: String,
        defaultRegion: String = "GB"
    ): UnifiedPhoneIntelligenceReport = withContext(Dispatchers.IO) {
        val telecom = buildTelecomProfile(rawNumber, defaultRegion)
        val sourceHealth = java.util.Collections.synchronizedList(mutableListOf<SourceHealthItem>())

        coroutineScope {
            // 1. Community Spam & Caller ID Directories
            val whoCalledDef = async {
                withTimeoutOrNull(8_000L) { scrapeWhoCalledUk(telecom.compactNational, sourceHealth) }
                    ?: SpamReputationSummary()
            }
            val shouldIAnswerDef = async {
                withTimeoutOrNull(8_000L) { scrapeShouldIAnswerUk(telecom.compactNational, sourceHealth) }
                    ?: SpamReputationSummary()
            }
            val tellowsDef = async {
                withTimeoutOrNull(8_000L) { scrapeTellowsUk(telecom.compactNational, sourceHealth) }
                    ?: SpamReputationSummary()
            }

            // 2. Open Web Intelligence & Companies House Corporate Dorking
            val webHitsDef = async {
                withTimeoutOrNull(10_000L) { scrapeOpenWebVariations(telecom, sourceHealth) }.orEmpty()
            }
            val corporateDef = async {
                withTimeoutOrNull(8_000L) { searchCompaniesHouseForPhone(telecom, sourceHealth) }.orEmpty()
            }

            // 3. Messaging App Probes (WhatsApp & Telegram)
            val messagingDef = async {
                withTimeoutOrNull(7_000L) { probeMessagingPlatforms(telecom, sourceHealth) }.orEmpty()
            }

            // 4. Local FastAPI workstation bridge (Telegram MTProto + Truecaller, over LAN/Tailscale)
            val fastApiDef = async {
                withTimeoutOrNull(6_000L) {
                    LocalFastApiPhoneBridge.queryWorkstation(telecom.e164Format, telecom.countryCode)
                } ?: FastApiPhoneResult(serverReachable = false)
            }

            val repParts = listOf(whoCalledDef.await(), shouldIAnswerDef.await(), tellowsDef.await())
            val mergedReputation = mergeReputationSummaries(repParts)
            val webPages = webHitsDef.await()
            val corporateFilings = corporateDef.await()
            val messagingApps = messagingDef.await()
            val fastApi = fastApiDef.await()

            // 4. Extract Entities with Strict Proximity Validation (Eliminates Screwfix/Gov.uk spam noise)
            val emails = mutableListOf<AttributedValue>()
            val names = mutableListOf<AttributedValue>()
            val addresses = mutableListOf<AttributedValue>()
            val socials = mutableListOf<AttributedProfileLink>()

            names.addAll(mergedReputation.communityCallerNames)

            corporateFilings.forEach { filing ->
                names += AttributedValue(filing.title, filing.category)
                filing.addressOrLocation?.let { addresses += AttributedValue(it, filing.category) }
            }

            for (page in webPages) {
                val blob = "${page.title} ${page.snippet}"

                EMAIL_REGEX.findAll(blob).forEach { m ->
                    val em = m.value.lowercase().trimEnd('.')
                    if (!em.endsWith(".png") && !em.endsWith(".jpg")) {
                        emails += AttributedValue(em, page.title.take(45).ifBlank { page.url })
                    }
                }

                UK_POSTCODE_REGEX.findAll(blob).forEach { m ->
                    addresses += AttributedValue(m.groupValues[1].uppercase(), "Web Listing")
                }

                SOCIAL_URL_REGEX.findAll("$blob ${page.url}").forEach { m ->
                    val url = m.value.trimEnd('/', '"', '\'', ')')
                    val platform = SocialMediaEmailScraper.detectSocialPlatform(url) ?: "Social Profile"
                    val handle = url.substringAfterLast('/').removePrefix("@")
                    if (handle.length >= 3) {
                        socials += AttributedProfileLink(handle = handle, platform = platform, url = url)
                    }
                }

                extractEntityNameFromTitle(page.title, telecom)?.let { extractedName ->
                    names += AttributedValue(extractedName, "Web Footprint")
                }
            }

            // Merge workstation findings before the masked-email pivot so any name or @handle it
            // uncovers is fed into the unmasker too.
            if (fastApi.serverReachable) {
                sourceHealth += SourceHealthItem(
                    sourceName = "Workstation FastAPI Bridge",
                    status = "OK",
                    confidence = "High",
                    detail = "Connected to Linux Mint workstation (Telegram: ${if (fastApi.tgRegistered) "Registered" else "Not Registered"})"
                )

                // Truecaller identity, email, city & carrier
                fastApi.tcName?.let { names += AttributedValue(it, "Truecaller Address Book") }
                fastApi.tcEmail?.let { emails += AttributedValue(it, "Truecaller Profile") }
                fastApi.tcCity?.let { addresses += AttributedValue(it, "Truecaller Location") }

                // Telegram MTProto identity & handle
                fastApi.tgFullName?.let { names += AttributedValue(it, "Telegram MTProto") }
                fastApi.tgUsername?.let { handle ->
                    socials += AttributedProfileLink(
                        handle = handle,
                        platform = "Telegram",
                        url = "https://t.me/$handle"
                    )
                }
            } else {
                sourceHealth += SourceHealthItem(
                    sourceName = "Workstation FastAPI Bridge",
                    status = "OFFLINE",
                    confidence = "Unverified",
                    detail = "Could not reach http://192.168.1.190:8099"
                )
            }

            val finalMessagingApps = messagingApps.toMutableList()
            if (fastApi.tgRegistered) {
                val existingTgIndex = finalMessagingApps.indexOfFirst { it.platform == "Telegram" }
                val tgDetails = buildList {
                    fastApi.tgFullName?.let { add("Profile: $it") }
                    fastApi.tgUsername?.let { add("@$it") }
                    add("Seen: ${fastApi.tgLastSeen ?: "Unknown"}")
                    fastApi.tgUserId?.let { add("ID: $it") }
                    fastApi.tgEstimatedCreated?.let { add("Est. Created: $it") }
                    fastApi.tgDcRegion?.let { add("DC: $it") }
                    if (fastApi.tgHasPhoto) {
                        val photoMeta = buildString {
                            append("Photos: ${fastApi.tgPhotoCount.coerceAtLeast(1)}")
                            fastApi.tgLatestPhotoDate?.let { append(" (Latest: $it)") }
                        }
                        add(photoMeta)
                    }
                    fastApi.tgBio?.let { add("Bio: $it") }
                    fastApi.tgBirthday?.let { add("DOB: $it") }
                    fastApi.tgBusinessLocation?.let { add("Business Loc: $it") }
                    if (fastApi.tgCommonChatsCount > 0) add("Shared Groups: ${fastApi.tgCommonChatsCount}")
                    if (fastApi.tgHasPinnedStories) add("Stories: Active/Pinned")
                    if (fastApi.tgIsPremium) add("Badge: Telegram Premium")
                    if (fastApi.tgIsScam || fastApi.tgIsFake) add("ALERT: Flagged Scam/Fake")
                }.joinToString(" • ")

                val digitsOnlyPhone = telecom.e164Format.removePrefix("+")
                val telegramDeepLink = when {
                    !fastApi.tgUsername.isNullOrBlank() -> "tg://resolve?domain=${fastApi.tgUsername}"
                    fastApi.tgUserId != null -> "tg://openmessage?user_id=${fastApi.tgUserId}"
                    else -> "tg://resolve?phone=$digitsOnlyPhone"
                }

                val defaultTgApp = MessagingAppLinkage(
                    platform = "Telegram",
                    status = "Pending",
                    checkUrl = telegramDeepLink,
                    verifiedPresence = false
                )

                val tgAppToUpdate = if (existingTgIndex >= 0) finalMessagingApps[existingTgIndex] else defaultTgApp

                val updatedTgApp = tgAppToUpdate.copy(
                    status = "Verified via MTProto Contact Sync",
                    displayName = tgDetails.ifBlank { "Registered Telegram User" },
                    bioOrStatus = fastApi.tgBio ?: tgAppToUpdate.bioOrStatus,
                    checkUrl = telegramDeepLink,
                    verifiedPresence = true,
                    avatarBase64 = fastApi.tgPhotoBase64
                )

                if (existingTgIndex >= 0) {
                    finalMessagingApps[existingTgIndex] = updatedTgApp
                } else {
                    finalMessagingApps.add(updatedTgApp)
                }
            }

            val (riskScore, riskGrade) = computeThreatScore(telecom, mergedReputation)

            val pivotReport = MaskedEmailPivotEngine.runPivot(
                e164Phone = telecom.e164Format,
                compactNationalPhone = telecom.compactNational,
                discoveredNames = names.distinctBy { it.value.lowercase() },
                discoveredHandles = socials.map { it.handle }.distinct(),
                discoveredEmails = emails.distinctBy { it.value.lowercase() },
                webSnippets = webPages.map { "${it.title} ${it.snippet}" }
            )

            // Promote high-confidence unmasked emails directly into discoveredEmails
            pivotReport.unmaskedCandidates
                .filter { it.confidenceScore >= 80 }
                .forEach { unmasked ->
                    emails += AttributedValue(
                        value = unmasked.resolvedEmail,
                        source = "Unmasked from ${unmasked.maskedHint} (${unmasked.confidenceScore}%)"
                    )
                }

            UnifiedPhoneIntelligenceReport(
                telecom = telecom,
                riskScore = riskScore,
                riskGrade = riskGrade,
                reputation = mergedReputation,
                webPages = webPages,
                corporateFilings = corporateFilings,
                discoveredEmails = emails.distinctBy { it.value.lowercase() },
                discoveredNames = names.distinctBy { it.value.lowercase() },
                discoveredAddresses = addresses.distinctBy { it.value.lowercase() },
                discoveredSocials = socials.distinctBy { it.url.lowercase() },
                messagingApps = finalMessagingApps,
                sourceHealth = sourceHealth.toList(),
                maskedPivotReport = pivotReport,
                fastApiResult = fastApi,
                telegramPhotoBase64 = fastApi.tgPhotoBase64
            )
        }
    }

    internal fun buildTelecomProfile(raw: String, defaultRegion: String): TelecomProfile {
        val util = PhoneNumberUtil.getInstance()
        val parsed: Phonenumber.PhoneNumber? = runCatching {
            util.parse(raw.trim(), defaultRegion)
        }.getOrNull()

        if (parsed == null) {
            val digits = raw.replace(Regex("[^0-9+]"), "")
            val compact = if (digits.startsWith("+44")) "0" + digits.removePrefix("+44") else digits
            return TelecomProfile(
                e164Format = if (digits.startsWith("+")) digits else "+44${digits.removePrefix("0")}",
                nationalFormat = compact,
                compactNational = compact,
                internationalSpaced = digits,
                countryCode = defaultRegion,
                lineType = "Unknown / Unparsed",
                isPossibleVoipOrVirtual = false,
                searchVariations = listOf(digits, compact).distinct()
            )
        }

        val e164 = util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
        val national = util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.NATIONAL)
        val intl = util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
        val compactNat = national.replace(" ", "").replace("-", "")
        val region = util.getRegionCodeForNumber(parsed) ?: defaultRegion
        val numType = util.getNumberType(parsed)

        val lineTypeLabel = when (numType) {
            PhoneNumberUtil.PhoneNumberType.MOBILE -> "Mobile"
            PhoneNumberUtil.PhoneNumberType.FIXED_LINE -> "Fixed Line / Landline"
            PhoneNumberUtil.PhoneNumberType.VOIP -> "VoIP / Virtual Line"
            PhoneNumberUtil.PhoneNumberType.TOLL_FREE -> "Toll-Free"
            PhoneNumberUtil.PhoneNumberType.PERSONAL_NUMBER -> "Personal / Virtual Forwarding (070)"
            else -> "Standard Line"
        }

        val isVoip = numType == PhoneNumberUtil.PhoneNumberType.VOIP || numType == PhoneNumberUtil.PhoneNumberType.PERSONAL_NUMBER
        val variations = listOf(e164, national, compactNat, intl).map { it.trim() }.filter { it.length >= 7 }.distinct()

        return TelecomProfile(
            e164Format = e164,
            nationalFormat = national,
            compactNational = compactNat,
            internationalSpaced = intl,
            countryCode = region,
            lineType = lineTypeLabel,
            isPossibleVoipOrVirtual = isVoip,
            searchVariations = variations
        )
    }

    private suspend fun searchCompaniesHouseForPhone(
        telecom: TelecomProfile,
        health: MutableList<SourceHealthItem>
    ): List<PublicRecordFiling> {
        val query = telecom.compactNational
        val url = "https://find-and-update.company-information.service.gov.uk/search?q=${ScanHttp.encode(query)}"
        val resp = ScanHttp.get(url, timeoutMs = 7_000, maxBodyChars = 1_000_000)
        if (!resp.isOk || !resp.body.contains("results-list", ignoreCase = true)) {
            health += SourceHealthItem("Companies House", "No Match", "High", "No corporate filing matches phone number", url)
            return emptyList()
        }

        val results = mutableListOf<PublicRecordFiling>()
        val itemRegex = Regex("""<li[^>]*class=["'][^"']*type-company[^"']*["'][^>]*>(.*?)</li>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val linkRegex = Regex("""<a[^>]*href=["'](/company/[^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        for (m in itemRegex.findAll(resp.body)) {
            val block = m.groupValues[1]
            val link = linkRegex.find(block) ?: continue
            val path = link.groupValues[1]
            val title = link.groupValues[2].cleanText()
            if (title.isBlank()) continue

            results += PublicRecordFiling(
                category = "Corporate Registry (Companies House)",
                title = title,
                detail = "Registered company mentioning phone number $query",
                url = "https://find-and-update.company-information.service.gov.uk$path"
            )
            if (results.size >= 3) break
        }

        health += SourceHealthItem(
            sourceName = "Companies House",
            status = if (results.isNotEmpty()) "Results" else "No Match",
            confidence = "High",
            detail = "${results.size} corporate filing match(es)",
            url = url
        )
        return results
    }

    private suspend fun scrapeOpenWebVariations(
        telecom: TelecomProfile,
        health: MutableList<SourceHealthItem>
    ): List<SearchResultItem> = coroutineScope {
        val numberQuery = telecom.searchVariations.firstOrNull() ?: telecom.compactNational
        val dork = "\"$numberQuery\" (contact OR phone OR tel OR mobile OR founder OR director)"

        val brave = HostGate.run("search.brave.com", minGapMs = 300) {
            runCatching { OpenWebOsintScraper.scrapeBraveHtml(dork) }.getOrDefault(emptyList())
        }
        val bing = HostGate.run("bing.com", minGapMs = 300) {
            runCatching { OpenWebOsintScraper.scrapeBingHtml(dork) }.getOrDefault(emptyList())
        }

        val rawHits = brave + bing
        val targetDigits = telecom.compactNational.removePrefix("0")

        // STRICT PROXIMITY VALIDATION: Discards Screwfix, Gov.uk surveys, and generic index spam
        val verifiedHits = rawHits.filter { item ->
            val blob = "${item.title} ${item.snippet}".lowercase()
            val blobDigits = blob.replace(Regex("[^0-9]"), "")
            val hasNumber = targetDigits.length >= 7 && blobDigits.contains(targetDigits)
            val isSpamDomain = item.url.contains("screwfix.com") ||
                item.url.contains("gov.uk/government/statistics") ||
                item.url.contains("cesurvey.dcms.gov.uk")

            hasNumber && !isSpamDomain
        }.distinctBy { it.url.lowercase().trimEnd('/') }

        health += SourceHealthItem(
            sourceName = "Open Web OSINT Dorking",
            status = if (verifiedHits.isNotEmpty()) "Results" else "No Match",
            confidence = if (verifiedHits.isNotEmpty()) "High" else "Moderate",
            detail = "${verifiedHits.size} verified web page(s) (spam filtered)"
        )

        verifiedHits
    }

    private fun probeMessagingPlatforms(
        telecom: TelecomProfile,
        health: MutableList<SourceHealthItem>
    ): List<MessagingAppLinkage> {
        val cleanDigits = telecom.e164Format.removePrefix("+")
        val waUrl = "https://api.whatsapp.com/send/?phone=$cleanDigits"
        val tgUrl = "https://t.me/+${cleanDigits}"

        val waResp = ScanHttp.get(waUrl, timeoutMs = 5_000)
        val waTitle = Regex("""<h3[^>]*>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL)
            .find(waResp.body)?.groupValues?.get(1)?.cleanText()
            ?.takeUnless { it.isBlank() || it.contains("Continue to Chat", true) }

        val tgResp = ScanHttp.get(tgUrl, timeoutMs = 5_000)
        val tgName = Regex("""<div[^>]*class=["'][^"']*tgme_page_title[^"']*["'][^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
            .find(tgResp.body)?.groupValues?.get(1)?.cleanText()
        val tgBio = Regex("""<div[^>]*class=["'][^"']*tgme_page_description[^"']*["'][^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
            .find(tgResp.body)?.groupValues?.get(1)?.cleanText()
        val tgHasProfile = !tgName.isNullOrBlank() && !tgResp.body.contains("tgme_page_icon_user")

        health += SourceHealthItem(
            sourceName = "Messaging Probes",
            status = if (tgHasProfile || waTitle != null) "Results" else "Checked",
            confidence = "High",
            detail = "WhatsApp & Telegram profiles evaluated"
        )

        return listOf(
            MessagingAppLinkage(
                platform = "WhatsApp",
                status = if (waTitle != null) "Active Profile Name" else "Direct Chat Endpoint Ready",
                displayName = waTitle,
                checkUrl = "https://wa.me/$cleanDigits",
                verifiedPresence = waTitle != null
            ),
            MessagingAppLinkage(
                platform = "Telegram",
                status = if (tgHasProfile) "Public Telegram Profile Found" else "Phone Lookup Endpoint Ready",
                displayName = tgName,
                bioOrStatus = tgBio,
                checkUrl = tgUrl,
                verifiedPresence = tgHasProfile
            )
        )
    }

    private fun scrapeWhoCalledUk(c: String, h: MutableList<SourceHealthItem>): SpamReputationSummary = SpamReputationSummary()
    private fun scrapeShouldIAnswerUk(c: String, h: MutableList<SourceHealthItem>): SpamReputationSummary = SpamReputationSummary()
    private fun scrapeTellowsUk(c: String, h: MutableList<SourceHealthItem>): SpamReputationSummary = SpamReputationSummary()

    private fun mergeReputationSummaries(parts: List<SpamReputationSummary>): SpamReputationSummary = parts.firstOrNull() ?: SpamReputationSummary()

    private fun computeThreatScore(t: TelecomProfile, r: SpamReputationSummary): Pair<Int, String> = 15 to "B"

    private fun extractEntityNameFromTitle(title: String, telecom: TelecomProfile): String? {
        val clean = title.substringBefore(" - ").substringBefore(" | ").trim()
        if (clean.length in 4..40 && !clean.contains("who called", true) && !clean.contains("phone", true)) return clean
        return null
    }

    private fun String.cleanText(): String = BreachCatalog.plainText(replace(TAG_STRIP, " ")).replace(WS_CLEAN, " ").trim()
}
