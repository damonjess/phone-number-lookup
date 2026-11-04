package com.example.phone_number_lookup

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class PilotTargetType {
    AUTO,
    PHONE,
    EMAIL,
    USERNAME,
    NAME
}

data class PilotContext(
    val locationHint: String? = null,
    val knownAliases: List<String> = emptyList(),
    val appContext: Context? = null
)

enum class ConfidenceLevel {
    HIGH,
    MEDIUM,
    LOW
}

data class SourcedFinding(
    val id: String = UUID.randomUUID().toString().take(8),
    val category: String,
    val claim: String,
    val sourceName: String,
    val sourceUrl: String? = null,
    val confidence: ConfidenceLevel = ConfidenceLevel.HIGH,
    val timestamp: Long = System.currentTimeMillis()
)

data class PilotAgentStatus(
    val agentId: String,
    val agentName: String,
    val question: String,
    val statusText: String,
    val countBadge: String? = null,
    val isRunning: Boolean = false,
    val isComplete: Boolean = false,
    val timeAgo: String = "now",
    val findingsCount: Int = 0
)

data class PilotMediaPhoto(
    val url: String,
    val source: String,
    val label: String
)

data class PilotInvestigationReport(
    val investigationId: String = UUID.randomUUID().toString().take(8).uppercase(Locale.US),
    val target: String = "",
    val detectedType: PilotTargetType = PilotTargetType.AUTO,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val isRunning: Boolean = false,
    val agentStatuses: List<PilotAgentStatus> = emptyList(),
    // Agent 1: Identity Resolution
    val resolvedNames: List<AttributedValue> = emptyList(),
    val resolvedAliases: List<AttributedValue> = emptyList(),
    val linkedEmails: List<String> = emptyList(),
    val linkedPhones: List<String> = emptyList(),
    // Agent 2: Digital Footprint & Registrations
    val linkedAccounts: List<PhoneAccountHit> = emptyList(),
    // Agent 3: Breach Intelligence
    val breachRecords: List<BreachDetail> = emptyList(),
    val totalBreachCount: Int = 0,
    // Agent 4: Public Records & Filings
    val publicRecords: List<AttributedValue> = emptyList(),
    // Agent 5: Location & Telecom Telemetry
    val locations: List<AttributedValue> = emptyList(),
    val telecomCarrier: String? = null,
    val telecomLineType: String? = null,
    val riskVerdict: RiskVerdict? = null,
    // Agent 6: Media & Visual Recovery
    val recoveredPhotos: List<PilotMediaPhoto> = emptyList(),
    // Unified Sourced Findings
    val findings: List<SourcedFinding> = emptyList(),
    val rawMarkdownReport: String = ""
)

object OsintPilotEngine {

    fun detectTargetType(input: String): PilotTargetType {
        val trimmed = input.trim()
        if (trimmed.contains("@") && trimmed.contains(".")) return PilotTargetType.EMAIL
        val digitCount = trimmed.count { it.isDigit() }
        if (digitCount >= 7 && (trimmed.startsWith("+") || trimmed.startsWith("0") || digitCount > trimmed.length / 2)) {
            return PilotTargetType.PHONE
        }
        if (trimmed.contains(" ") && !trimmed.startsWith("@")) {
            return PilotTargetType.NAME
        }
        return PilotTargetType.USERNAME
    }

    suspend fun investigate(
        query: String,
        explicitType: PilotTargetType = PilotTargetType.AUTO,
        context: PilotContext = PilotContext(),
        onUpdate: (PilotInvestigationReport) -> Unit
    ): PilotInvestigationReport = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        val detected = if (explicitType == PilotTargetType.AUTO) detectTargetType(cleanQuery) else explicitType
        val investigationId = UUID.randomUUID().toString().take(8).uppercase(Locale.US)
        val startedAt = System.currentTimeMillis()

        val initialAgents = listOf(
            PilotAgentStatus("agent_id", "Identity Resolution Agent", "Who is this, really?", "Analyzing core identity markers & aliases…", isRunning = true),
            PilotAgentStatus("agent_footprint", "Digital Footprint Agent", "Where are they active?", "Probing accounts & platform registrations…", isRunning = true),
            PilotAgentStatus("agent_breach", "Breach Intelligence Agent", "What has already leaked?", "Querying breach catalogues & credential leaks…", isRunning = true),
            PilotAgentStatus("agent_records", "Public Records Agent", "What do the public records say?", "Scanning comments, reviews, registries & DNS…", isRunning = true),
            PilotAgentStatus("agent_telecom", "Telecom & Telemetry Agent", "Where can they be reached?", "Mapping address history, network carrier & telemetry…", isRunning = true),
            PilotAgentStatus("agent_media", "Media & Visual Agent", "What do they look like?", "Recovering profile avatars, photos & visual presence…", isRunning = true)
        )

        val currentReport = PilotInvestigationReport(
            investigationId = investigationId,
            target = cleanQuery,
            detectedType = detected,
            startedAt = startedAt,
            isRunning = true,
            agentStatuses = initialAgents
        )
        onUpdate(currentReport)

        when (detected) {
            PilotTargetType.PHONE -> investigatePhone(cleanQuery, context, currentReport, onUpdate)
            PilotTargetType.EMAIL -> investigateEmail(cleanQuery, context, currentReport, onUpdate)
            PilotTargetType.USERNAME -> investigateUsername(cleanQuery, context, currentReport, onUpdate)
            PilotTargetType.NAME -> investigateName(cleanQuery, context, currentReport, onUpdate)
            PilotTargetType.AUTO -> investigatePhone(cleanQuery, context, currentReport, onUpdate)
        }
    }

    private suspend fun investigatePhone(
        phone: String,
        context: PilotContext,
        initialReport: PilotInvestigationReport,
        onUpdate: (PilotInvestigationReport) -> Unit
    ): PilotInvestigationReport = coroutineScope {
        val normalized = PhoneNumberUtils.normalize(phone)
        val localFormat = if (normalized.startsWith("+44")) "0" + normalized.removePrefix("+44") else normalized
        val formats = PhoneNumberUtils.getFormattedFormats(normalized, localFormat)
        val e164 = formats.e164

        val findings = mutableListOf<SourcedFinding>()
        val photos = mutableListOf<PilotMediaPhoto>()
        val accounts = mutableListOf<PhoneAccountHit>()
        val names = mutableListOf<AttributedValue>()
        val aliases = mutableListOf<AttributedValue>()
        val discoveredEmails = mutableListOf<String>()
        val locations = mutableListOf<AttributedValue>()
        val publicRecords = mutableListOf<AttributedValue>()
        val breaches = mutableListOf<BreachDetail>()

        val carrierHint = PhoneDirectoryScraper.lookupUkOriginalCarrier(localFormat)
        val areaHint = PhoneNumberUtils.getAreaInfo(normalized)
        val lineType = PhoneNumberUtils.getLineType(normalized)
        val facts = NumberFactsProvider.from(normalized)

        val footprintDef = async {
            runCatching { PhoneDirectoryScraper.scrapePhoneFootprint(normalized) }.getOrNull()
        }
        val spamDef = async {
            runCatching { SpamScanner.lookup(normalized) }.getOrNull()
        }
        val reputationDef = async {
            runCatching { PhoneReputation.lookup(normalized) }.getOrDefault(emptyList())
        }
        val builtInDef = async {
            runCatching { PhoneInfogaBuiltIn.run(normalized) }.getOrNull()
        }

        val spamReport = spamDef.await()
        val reputationReports = reputationDef.await()
        val verdict = RiskAssessor.assess(facts, spam = spamReport, reputation = reputationReports, userStatus = null, stillChecking = false)

        carrierHint?.let {
            findings += SourcedFinding(category = "Telemetry", claim = "Telecom Carrier Allocation: $it", sourceName = "Ofcom UK Allocation Plan", confidence = ConfidenceLevel.HIGH)
        }
        areaHint?.let {
            findings += SourcedFinding(category = "Location", claim = "Numbering Geography: $it", sourceName = "Telecom Routing Exchange", confidence = ConfidenceLevel.HIGH)
            locations += AttributedValue(it, "Area Code Exchange")
        }
        findings += SourcedFinding(category = "Telemetry", claim = "Line Classification: $lineType (${verdict.headline})", sourceName = "Numbering Assessment", confidence = ConfidenceLevel.HIGH)

        spamReport?.let { sp ->
            if (sp.score != null) {
                publicRecords += AttributedValue("Tellows Score ${sp.scoreLabel} (${sp.riskLabel}) • ${sp.searches ?: 0} searches", sp.source)
                findings += SourcedFinding(category = "Public Record", claim = "Tellows Score ${sp.scoreLabel} (${sp.riskLabel})", sourceName = sp.source, confidence = ConfidenceLevel.HIGH)
            }
            sp.callerName?.let { cn ->
                names += AttributedValue(cn, sp.source)
                findings += SourcedFinding(category = "Identity", claim = "Reported Caller Name: $cn", sourceName = sp.source, confidence = ConfidenceLevel.HIGH)
            }
            sp.location?.let { loc ->
                locations += AttributedValue(loc, sp.source)
            }
        }

        builtInDef.await()?.scans?.firstOrNull { it.scanner == "ovh" }?.let { ovh ->
            ovh.message?.let { msg ->
                findings += SourcedFinding(category = "Telemetry", claim = msg, sourceName = "OVH Telecom API", confidence = ConfidenceLevel.HIGH)
            }
        }

        val footprint = footprintDef.await()
        footprint?.let { fp ->
            accounts.addAll(fp.accountRegistrations)
            fp.accountRegistrations.forEach { hit ->
                findings += SourcedFinding(
                    category = "Registration",
                    claim = "${hit.platform}: ${hit.detail}",
                    sourceName = hit.platform,
                    sourceUrl = hit.url,
                    confidence = if (hit.confirmed) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM
                )
            }

            names.addAll(fp.discoveredNames)
            locations.addAll(fp.discoveredLocations)
            aliases.addAll(fp.discoveredHandles)
            discoveredEmails.addAll(fp.discoveredEmails.map { it.value })

            fp.discoveredNames.forEach {
                findings += SourcedFinding(category = "Identity", claim = "Discovered Name: ${it.value}", sourceName = it.source, confidence = ConfidenceLevel.HIGH)
            }
            fp.discoveredHandles.forEach {
                accounts += PhoneAccountHit(it.source, "Discovered Social Link", it.value, it.value.takeIf { u -> u.startsWith("http") }, confirmed = true)
                findings += SourcedFinding(category = "Registration", claim = "Discovered Social / Handle: ${it.value}", sourceName = it.source, sourceUrl = it.value.takeIf { u -> u.startsWith("http") }, confidence = ConfidenceLevel.HIGH)
            }
            fp.discoveredLocations.forEach {
                findings += SourcedFinding(category = "Location", claim = "Discovered Location: ${it.value}", sourceName = it.source, confidence = ConfidenceLevel.MEDIUM)
            }
            fp.businessMatches.forEach {
                publicRecords += it
                findings += SourcedFinding(category = "Public Record", claim = "OpenStreetMap Registered Entity: ${it.value}", sourceName = it.source, confidence = ConfidenceLevel.HIGH)
            }
            fp.directoryReports.forEach { dir ->
                val commentsSummary = if (dir.userComments.isNotEmpty()) " — \"${dir.userComments.first().take(120)}\"" else ""
                publicRecords += AttributedValue("${dir.source}: ${dir.verdictLabel ?: "Listed"} (${dir.reportCount ?: "${dir.userComments.size} comments"})$commentsSummary", dir.source)
                findings += SourcedFinding(category = "Public Record", claim = "${dir.source}: ${dir.callerName ?: dir.callerCategory ?: dir.verdictLabel ?: "Listed"}$commentsSummary", sourceName = dir.source, sourceUrl = dir.url, confidence = ConfidenceLevel.HIGH)
            }
            fp.crawledPages.forEach { page ->
                publicRecords += AttributedValue("${page.pageTitle}: \"…${page.contextSnippet.take(140)}…\"", page.url)
                findings += SourcedFinding(category = "Public Record", claim = "Web Page Mention (${page.pageTitle}): \"${page.contextSnippet.take(140)}\"", sourceName = hostOf(page.url), sourceUrl = page.url, confidence = ConfidenceLevel.HIGH)
            }
            fp.extraWebResults.take(15).forEach { web ->
                if (fp.crawledPages.none { it.url == web.url }) {
                    publicRecords += AttributedValue("${web.title} — ${web.snippet.take(140)}", web.url)
                    findings += SourcedFinding(category = "Public Record", claim = "${web.title}: ${web.snippet.take(140)}", sourceName = web.source, sourceUrl = web.url, confidence = ConfidenceLevel.MEDIUM)
                }
            }

            // Pivot on discovered email address if real (not masked)
            val topEmail = fp.discoveredEmails.map { it.value }.firstOrNull { !it.contains("*") }
            if (!topEmail.isNullOrBlank()) {
                findings += SourcedFinding(category = "Identity", claim = "Linked Email Address: $topEmail", sourceName = "Web / Directory Extraction", confidence = ConfidenceLevel.HIGH)
                val emailOutcome = runCatching { EmailRegistrationScanner.scan(topEmail) }.getOrNull()
                emailOutcome?.registered?.forEach { reg ->
                    accounts += PhoneAccountHit(
                        platform = "${reg.site} (via $topEmail)",
                        status = "Registered response",
                        detail = reg.evidence,
                        url = reg.profileUrl,
                        confirmed = true
                    )
                    findings += SourcedFinding(category = "Registration", claim = "Active Account on ${reg.site} via $topEmail", sourceName = reg.site, sourceUrl = reg.profileUrl, confidence = ConfidenceLevel.HIGH)
                }
                val realBreachReport = runCatching { EmailScanner.check(topEmail) }.getOrNull()
                if (realBreachReport != null) {
                    breaches.addAll(synthesizeBreachDetails(realBreachReport))
                }
            }

            val tgHit = fp.accountRegistrations.firstOrNull { it.platform.equals("Telegram", ignoreCase = true) && it.confirmed }
            if (tgHit?.url != null) {
                photos += PilotMediaPhoto(tgHit.url, "Telegram", "Telegram Profile Link & Avatar")
            }
        }

        reputationReports.filter { it.hasReports }.forEach { rep ->
            val sampleText = rep.samples.firstOrNull()?.let { " — \"$it\"" }.orEmpty()
            publicRecords += AttributedValue("${rep.source}: ${rep.reportCount} report(s)$sampleText", rep.url)
            findings += SourcedFinding(
                category = "Public Record",
                claim = "${rep.source}: ${rep.reportCount} community report(s)$sampleText",
                sourceName = rep.source,
                sourceUrl = rep.url,
                confidence = ConfidenceLevel.HIGH
            )
        }

        val dedupAccounts = deduplicateAccounts(accounts)
        val finalStatuses = listOf(
            PilotAgentStatus("agent_id", "Identity Resolution Agent", "Who is this, really?", "Resolved names, handles and linked emails from directories & web pages", countBadge = "${names.size + aliases.size} identities", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = names.size + aliases.size + discoveredEmails.size),
            PilotAgentStatus("agent_footprint", "Digital Footprint Agent", "Where are they active?", "Probed Microsoft, Instagram, Telegram, GitHub, OpenPGP, WhatsApp & web socials", countBadge = "${dedupAccounts.size} accounts", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = dedupAccounts.size),
            PilotAgentStatus("agent_breach", "Breach Intelligence Agent", "What has already leaked?", if (breaches.isNotEmpty()) "Identified ${breaches.size} breach exposures via linked email" else "No linked email credential exposures detected", countBadge = "${breaches.size} breaches", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = breaches.size),
            PilotAgentStatus("agent_records", "Public Records Agent", "What do the public records say?", "Analyzed OpenStreetMap businesses, 6 caller directories & crawled web pages", countBadge = "${publicRecords.size} records", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = publicRecords.size),
            PilotAgentStatus("agent_telecom", "Telecom & Telemetry Agent", "Where can they be reached?", "Carrier block, Ofcom allocation, OVH VoIP & area code mapped", countBadge = "${locations.distinctBy { it.value.lowercase() }.size} locations", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = locations.size + 2),
            PilotAgentStatus("agent_media", "Media & Visual Agent", "What do they look like?", if (photos.isNotEmpty()) "Recovered ${photos.size} visual profiles" else "No direct public avatar exposed", countBadge = "${photos.size} photos", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = photos.size)
        )

        val finalReport = initialReport.copy(
            finishedAt = System.currentTimeMillis(),
            isRunning = false,
            agentStatuses = finalStatuses,
            resolvedNames = names.distinctBy { it.value.lowercase() },
            resolvedAliases = aliases.distinctBy { it.value.lowercase() },
            linkedEmails = discoveredEmails.distinct(),
            linkedPhones = listOf(e164, localFormat).distinct(),
            linkedAccounts = dedupAccounts,
            breachRecords = breaches,
            totalBreachCount = breaches.size,
            publicRecords = publicRecords.distinctBy { it.value.lowercase() },
            locations = locations.distinctBy { it.value.lowercase() },
            telecomCarrier = carrierHint,
            telecomLineType = lineType,
            riskVerdict = verdict,
            recoveredPhotos = photos.distinctBy { it.url },
            findings = findings,
            rawMarkdownReport = buildMarkdown(initialReport.investigationId, phone, "PHONE", finalStatuses, findings)
        )
        onUpdate(finalReport)
        finalReport
    }

    private suspend fun investigateEmail(
        email: String,
        context: PilotContext,
        initialReport: PilotInvestigationReport,
        onUpdate: (PilotInvestigationReport) -> Unit
    ): PilotInvestigationReport = coroutineScope {
        val cleanEmail = email.trim().lowercase()
        val localPart = cleanEmail.substringBefore('@')
        val domain = cleanEmail.substringAfter('@')

        val findings = mutableListOf<SourcedFinding>()
        val photos = mutableListOf<PilotMediaPhoto>()
        val accounts = mutableListOf<PhoneAccountHit>()
        val names = mutableListOf<AttributedValue>()
        val aliases = mutableListOf<AttributedValue>()
        val locations = mutableListOf<AttributedValue>()
        val publicRecords = mutableListOf<AttributedValue>()
        val breaches = mutableListOf<BreachDetail>()
        val phones = mutableListOf<String>()
        val discoveredEmails = mutableListOf(cleanEmail)

        context.locationHint?.let {
            locations += AttributedValue(it, "User Context Hint")
        }
        context.knownAliases.forEach {
            aliases += AttributedValue("@$it", "User Context Hint")
        }

        // 1. Deep Multi-source Email Attribution
        val attrDef = async {
            runCatching { EmailAttributionEngine.analyze(cleanEmail) }.getOrNull()
        }

        // 2. Real-time Breach & Infostealer Database Queries (XposedOrNot, HudsonRock, COMB, Pastebin)
        val breachDef = async {
            runCatching { EmailScanner.check(cleanEmail) }.getOrNull()
        }

        // 3. Identity Enrichment (Keybase, Ubuntu PGP, Wayback Machine)
        val enrichDef = async {
            runCatching { IdentityEnricher.enrich(cleanEmail, isEmail = true) }.getOrNull()
        }

        // 4. Direct Email Registrations (Curated + Blackbird + optional Python Holehe)
        val regDef = async {
            val native = runCatching { EmailRegistrationScanner.scan(cleanEmail) }.getOrNull()
            val holehe = context.appContext?.let { app ->
                runCatching { PythonOsintBridge.scanEmailHolehePython(app, cleanEmail) }.getOrNull()
            }
            val combined = buildList {
                native?.registered?.let { addAll(it) }
                holehe?.registered?.let { addAll(it) }
            }.distinctBy { "${it.site.lowercase()}|${it.verificationStatus}" }
            combined
        }

        // 5. Handle-to-Username Scan across 300+ sites (WhatsMyName, Sherlock, Maigret, SocialAnalyzer, Blackbird, PublicProfileApis)
        val handleScanDef = async {
            if (localPart.length >= 4) {
                val ktScan = runCatching {
                    UsernameScanner.scanWithVariants(localPart, limit = UsernameScanner.STANDARD_LIMIT).confirmed
                }.getOrDefault(emptyList())
                val pyScan = context.appContext?.let { app ->
                    runCatching {
                        PythonOsintBridge.scanUsername(app, localPart, PythonToolMode.ALL_PYTHON, UsernameScanner.STANDARD_LIMIT).confirmed
                    }.getOrDefault(emptyList())
                }.orEmpty()
                val apiScan = runCatching { PublicProfileApis.lookup(localPart) }.getOrDefault(emptyList())
                (ktScan + pyScan + apiScan)
                    .map { hit -> hit.copy(profileUrl = OsintDatasets.cleanProfileUrl(hit.site, hit.profileUrl)) }
                    .distinctBy { it.profileUrl.lowercase().trimEnd('/') }
            } else emptyList()
        }

        // 6. Email Domain Quality + Network OSINT
        val emailDomainDef = async {
            runCatching { EmailDomainScanner.check(cleanEmail) }.getOrNull()
        }
        val domainDef = async {
            runCatching { DomainNetworkOsint.scan(domain) }.getOrNull()
        }

        // 7. Gravatar visual recovery
        val md5Email = md5(cleanEmail)
        val gravatarUrl = "https://www.gravatar.com/avatar/$md5Email?s=400&d=404"
        val gravatarCheck = async {
            val resp = ScanHttp.get(gravatarUrl, timeoutMs = 6_000)
            if (resp.code == 200) {
                PilotMediaPhoto(gravatarUrl, "Gravatar", "Public Gravatar Profile Image")
            } else null
        }

        val socialMediaDef = async {
            val att = attrDef.await()
            val discoveredNames = att?.namesFound?.map { it.value }?.distinct().orEmpty()
            val discoveredHandles = att?.usernamesFound?.map { it.value.removePrefix("@") }?.distinct().orEmpty()
            runCatching {
                SocialMediaEmailScraper.scanForEmail(
                    email = cleanEmail,
                    exactHandles = listOf(localPart) + discoveredHandles,
                    fullNames = discoveredNames,
                    locationHint = context.locationHint
                )
            }.getOrDefault(emptyList())
        }

        // Await Attribution + Handle Hits
        val attribution = attrDef.await()
        val handleUsernameHits = handleScanDef.await()

        // 8. Run DeepProfileScraper across the handle + discovered usernames + confirmed handle hits!
        val allHandlesToEnrich = buildList {
            if (localPart.length >= 4) add(localPart)
            context.knownAliases.forEach { if (it.length >= 4) add(it) }
            attribution?.usernamesFound?.forEach {
                val cleanH = it.value.removePrefix("@").trim()
                if (cleanH.length >= 4 && !cleanH.contains(' ')) add(cleanH)
            }
        }.distinctBy { it.lowercase() }.take(5)

        // Run Free Public Records, Breach PII/Password Patterns, Alias Correlation & Address Timeline
        val deepPublicRecords = FreePublicRecordsAndBreachIntel.enrichTarget(
            email = cleanEmail,
            username = localPart,
            fullName = attribution?.namesFound?.firstOrNull()?.value,
            discoveredHandles = allHandlesToEnrich,
            existingLocations = attribution?.locationsFound.orEmpty(),
            existingTimeline = attribution?.timeline.orEmpty()
        )

        // 1. Add Exposed PII & Password Storage Patterns to Breach Agent
        if (deepPublicRecords.piiSummary.exposedFields.isNotEmpty()) {
            val piiClaim = "Exposed PII Classes across breaches: ${deepPublicRecords.piiSummary.exposedFields.joinToString(", ")}"
            publicRecords += AttributedValue(piiClaim, "LeakCheck & XposedOrNot")
            findings += SourcedFinding(category = "Breach", claim = piiClaim, sourceName = "Breach PII Analytics", confidence = ConfidenceLevel.HIGH)
        }
        deepPublicRecords.piiSummary.passwordPatterns.forEach { pattern ->
            findings += SourcedFinding(category = "Breach", claim = "Password Storage Pattern: $pattern", sourceName = "XposedOrNot Metrics", confidence = ConfidenceLevel.HIGH)
        }
        deepPublicRecords.piiSummary.stealerUsernameHits.forEach { sh ->
            findings += SourcedFinding(category = "Breach", claim = sh, sourceName = "HudsonRock Username Cavalier", confidence = ConfidenceLevel.HIGH)
        }

        // 2. Add Corporate Registries, Court Filings, Official Gazette & Postcode Records
        deepPublicRecords.filings.forEach { filing ->
            publicRecords += AttributedValue("[${filing.category}] ${filing.title} — ${filing.detail}", filing.url)
            filing.addressOrLocation?.let { addr ->
                locations += AttributedValue(addr, filing.category)
            }
            findings += SourcedFinding(
                category = "Public Record",
                claim = "${filing.title}: ${filing.detail}",
                sourceName = filing.category,
                sourceUrl = filing.url,
                confidence = ConfidenceLevel.HIGH
            )
        }

        // 3. Add Alias Correlations & Associates
        deepPublicRecords.aliasCorrelations.forEach { corr ->
            findings += SourcedFinding(
                category = "Identity",
                claim = "Alias Correlation (@${corr.handleA} ↔ @${corr.handleB}): ${corr.reason} (${corr.confidenceScore}% match)",
                sourceName = "Alias Graph Engine",
                confidence = ConfidenceLevel.HIGH
            )
        }
        deepPublicRecords.associates.forEach { assoc ->
            names += assoc
            findings += SourcedFinding(category = "Identity", claim = "Associated Officer / Entity: ${assoc.value}", sourceName = assoc.source, confidence = ConfidenceLevel.MEDIUM)
        }

        val deepDossiersDef = async {
            allHandlesToEnrich.mapIndexed { index, h ->
                async {
                    val hitsForHandle = if (index == 0) handleUsernameHits else emptyList()
                    runCatching { DeepProfileScraper.scrapeUsernameDossier(h, hitsForHandle) }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { "${it.platform.lowercase()}|${it.profileUrl.lowercase().trimEnd('/')}" }
        }

        // 9. Google & Web Comment Scraper (using all discovered names, handles, and locations)
        val commentsDef = async {
            val discoveredNames = attribution?.namesFound?.map { it.value }?.distinct().orEmpty()
            val discoveredLocations = (attribution?.locationsFound?.map { it.value }.orEmpty() + listOfNotNull(context.locationHint)).distinct()
            runCatching {
                GoogleCommentScraper.scrapeForEmail(
                    email = cleanEmail,
                    knownDisplayNames = discoveredNames,
                    knownHandles = allHandlesToEnrich,
                    knownLocations = discoveredLocations
                )
            }.getOrNull()
        }

        // Process Attribution
        attribution?.let { att ->
            names.addAll(att.namesFound)
            att.namesFound.forEach {
                findings += SourcedFinding(category = "Identity", claim = "Discovered Name: ${it.value}", sourceName = it.source, confidence = ConfidenceLevel.HIGH)
            }
            aliases.addAll(att.usernamesFound)
            att.usernamesFound.forEach {
                findings += SourcedFinding(category = "Identity", claim = "Associated Username: ${it.value}", sourceName = it.source, confidence = ConfidenceLevel.HIGH)
            }
            att.profileLinks.forEach { link ->
                val cleanUrl = OsintDatasets.cleanProfileUrl(link.platform, link.url)
                accounts += PhoneAccountHit(link.platform, "Public Profile", "Handle: ${link.handle}", cleanUrl, confirmed = true)
                findings += SourcedFinding(category = "Registration", claim = "Public ${link.platform} Profile: ${link.handle}", sourceName = link.platform, sourceUrl = cleanUrl, confidence = ConfidenceLevel.HIGH)
            }
            locations.addAll(att.locationsFound)
            att.locationsFound.forEach { loc ->
                findings += SourcedFinding(category = "Location", claim = "Location / Telemetry: ${loc.value}", sourceName = loc.source, confidence = ConfidenceLevel.HIGH)
            }
            phones.addAll(att.phonesFound.map { it.value })
            att.phonesFound.forEach { p ->
                findings += SourcedFinding(category = "Identity", claim = "Discovered Recovery / Contact Phone: ${p.value}", sourceName = p.source, confidence = ConfidenceLevel.HIGH)
            }
            att.avatarUrls.forEach { av ->
                photos += PilotMediaPhoto(av, "Profile Photo", "Public Profile Picture")
                findings += SourcedFinding(category = "Media", claim = "Profile Photo Recovered", sourceName = "Public Avatar", sourceUrl = av, confidence = ConfidenceLevel.HIGH)
            }
            att.stealerProfile?.let { st ->
                val stealerClaim = buildString {
                    append("Infostealer capture on ${st.operatingSystem ?: "Windows"}")
                    st.computerUsername?.let { append(" • Machine User: \"$it\"") }
                    st.maskedIp?.let { append(" • Masked IP: $it") }
                    st.dateCompromised?.let { append(" • Compromised: $it") }
                    if (st.antiviruses.isNotEmpty()) append(" • AV: ${st.antiviruses.joinToString(", ")}")
                }
                publicRecords += AttributedValue(stealerClaim, "HudsonRock Cavalier")
                findings += SourcedFinding(category = "Breach", claim = stealerClaim, sourceName = "HudsonRock Infostealer Logs", confidence = ConfidenceLevel.HIGH)
            }
        }

        // Process Direct Email Registrations (Curated + Blackbird + Holehe)
        regDef.await().forEach { hit ->
            val cleanUrl = OsintDatasets.cleanProfileUrl(hit.site, hit.profileUrl)
            val isConfirmed = hit.verificationStatus == "Registered response"
            accounts += PhoneAccountHit(
                platform = hit.site,
                status = hit.verificationStatus,
                detail = "${hit.source} • ${hit.evidence}",
                url = cleanUrl,
                confirmed = isConfirmed
            )
            // Extract any Holehe recovery phone from evidence
            if ("Recovery phone: " in hit.evidence) {
                val rPhone = hit.evidence.substringAfter("Recovery phone: ").substringBefore(" |").substringBefore(" •").trim()
                if (rPhone.isNotBlank()) phones += rPhone
            }
            findings += SourcedFinding(
                category = "Registration",
                claim = "${hit.site} (${hit.verificationStatus}): ${hit.evidence}",
                sourceName = "${hit.site} (${hit.source})",
                sourceUrl = cleanUrl,
                confidence = if (isConfirmed) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM
            )
        }

        // Process 300+ Site Username Handle Correlations
        handleUsernameHits.forEach { hit ->
            val cleanUrl = OsintDatasets.cleanProfileUrl(hit.site, hit.profileUrl)
            val isConfirmed = hit.verificationStatus == "Matched"
            accounts += PhoneAccountHit(
                platform = "${hit.site} (@${hit.queriedName.ifBlank { localPart }})",
                status = hit.verificationStatus,
                detail = "Category: ${hit.category} • Source: ${hit.source} • ${hit.evidence}",
                url = cleanUrl,
                confirmed = isConfirmed
            )
            findings += SourcedFinding(
                category = "Registration",
                claim = "Handle @${hit.queriedName.ifBlank { localPart }} matched on ${hit.site} (${hit.category})",
                sourceName = "${hit.site} (${hit.source})",
                sourceUrl = cleanUrl,
                confidence = if (isConfirmed) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM
            )
        }

        // Process Deep Profile Dossiers (TikTok, YouTube, Reddit, Steam, Telegram, SoundCloud, GitHub, etc.)
        deepDossiersDef.await().forEach { card ->
            val cleanUrl = OsintDatasets.cleanProfileUrl(card.platform, card.profileUrl)
            val detailParts = buildList {
                card.displayName?.let { add("Name: $it") }
                card.statsSummary?.let { add(it) }
                card.createdDate?.let { add("Joined: $it") }
                card.location?.let { add("Location: $it") }
                card.bio?.let { add("Bio: \"${it.take(160)}\"") }
                if (card.externalLinks.isNotEmpty()) add("Links: ${card.externalLinks.joinToString(", ")}")
            }.joinToString(" • ").ifBlank { "Public profile for @${card.handle}" }

            accounts += PhoneAccountHit(
                platform = "${card.platform} (@${card.handle})",
                status = "Enriched Dossier",
                detail = detailParts,
                url = cleanUrl,
                confirmed = true
            )
            card.displayName?.let { names += AttributedValue(it, "${card.platform} (@${card.handle})") }
            card.location?.let { locations += AttributedValue(it, "${card.platform} (@${card.handle})") }
            card.discoveredEmails.forEach { discoveredEmails += it }
            card.discoveredPhones.forEach { phones += it }
            card.avatarUrl?.let { av ->
                photos += PilotMediaPhoto(av, card.platform, "${card.platform} (@${card.handle})")
            }
            findings += SourcedFinding(
                category = "Registration",
                claim = "${card.platform} (@${card.handle}): $detailParts",
                sourceName = card.platform,
                sourceUrl = cleanUrl,
                confidence = ConfidenceLevel.HIGH
            )
        }

        socialMediaDef.await().forEach { social ->
            accounts += PhoneAccountHit(
                platform = "${social.platform} (${social.matchedIdentifier})",
                status = if (social.verifiedByEmail) "Email Match" else "Handle Match",
                detail = "${social.handleOrTitle} — ${social.snippet}",
                url = social.url,
                confirmed = true
            )
            findings += SourcedFinding(
                category = "Registration",
                claim = "${social.platform} (${social.matchedIdentifier}): ${social.handleOrTitle} — ${social.snippet}",
                sourceName = social.platform,
                sourceUrl = social.url,
                confidence = if (social.verifiedByEmail) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM
            )
        }

        // Process Breaches (Synthesizing BreachDetail for ALL breachNames including Naz.API, AlienStealerLogs, HudsonRock, COMB, Pastebin)
        val breachOutcome = breachDef.await()
        breachOutcome?.let { bo ->
            val allSynthesized = synthesizeBreachDetails(bo)
            breaches.addAll(allSynthesized)
            bo.breachNames.forEach { bName ->
                findings += SourcedFinding(
                    category = "Breach",
                    claim = "Security Breach / Leak Exposure: $bName",
                    sourceName = "Breach & Stealer Intelligence",
                    confidence = ConfidenceLevel.HIGH
                )
            }
        }

        // Process Identity Enrichment (Keybase, PGP, Wayback Machine)
        val enrichment = enrichDef.await()
        enrichment?.let { en ->
            en.keybaseDisplayName?.let {
                names += AttributedValue(it, "Keybase Profile")
                findings += SourcedFinding(category = "Identity", claim = "Keybase Display Name: $it", sourceName = "Keybase", sourceUrl = "https://keybase.io/", confidence = ConfidenceLevel.HIGH)
            }
            en.keybaseBio?.let {
                findings += SourcedFinding(category = "Identity", claim = "Keybase Bio: $it", sourceName = "Keybase", confidence = ConfidenceLevel.HIGH)
            }
            en.keybaseProofs.forEach { proof ->
                accounts += PhoneAccountHit(proof.service, "Cryptographically Verified", "@${proof.handle}", proof.proofUrl, confirmed = true)
                findings += SourcedFinding(category = "Registration", claim = "Verified ${proof.service} handle: @${proof.handle}", sourceName = "Keybase Proofs", sourceUrl = proof.proofUrl, confidence = ConfidenceLevel.HIGH)
            }
            en.pgpRecords.forEach { pgp ->
                publicRecords += AttributedValue("PGP Key ${pgp.keyId} (${pgp.uids.joinToString(", ")})", "OpenPGP Keyserver")
                findings += SourcedFinding(
                    category = "Public Record",
                    claim = "PGP Public Key ID: ${pgp.keyId} • UIDs: ${pgp.uids.joinToString(", ")}",
                    sourceName = "Ubuntu PGP Keyserver",
                    sourceUrl = "https://keyserver.ubuntu.com/pks/lookup?search=$cleanEmail&op=index",
                    confidence = ConfidenceLevel.HIGH
                )
            }
            en.waybackSnapshots.forEach { wb ->
                publicRecords += AttributedValue("Wayback Archive: ${wb.targetUrl} (${wb.snapshotCount} snapshots, ${wb.firstSeen ?: "?"} → ${wb.lastSeen ?: "?"})", wb.latestArchiveUrl ?: "Internet Archive")
                findings += SourcedFinding(
                    category = "Public Record",
                    claim = "Wayback Machine Archive for ${wb.targetUrl}: ${wb.snapshotCount} captures (${wb.firstSeen ?: "?"} to ${wb.lastSeen ?: "?"})",
                    sourceName = "Internet Archive CDX",
                    sourceUrl = wb.latestArchiveUrl,
                    confidence = ConfidenceLevel.HIGH
                )
            }
        }

        // Process Email Domain & Network OSINT
        emailDomainDef.await()?.let { ed ->
            val summary = "Mail Provider: ${ed.mxProvider ?: domain} • Quality Score: ${ed.qualityScore ?: "N/A"}/100 • SPF: ${ed.spf} • DMARC: ${ed.dmarc}"
            publicRecords += AttributedValue(summary, "Email DNS Security")
            ed.reasons.forEach { r ->
                findings += SourcedFinding(category = "Telemetry", claim = r, sourceName = "Email Domain Scanner", confidence = ConfidenceLevel.HIGH)
            }
        }
        domainDef.await()?.let { dom ->
            if (dom.mxRecords.isNotEmpty()) {
                publicRecords += AttributedValue("MX Records: ${dom.mxRecords.joinToString(", ")}", "DNS")
            }
            dom.rdap?.registrar?.let { reg ->
                publicRecords += AttributedValue("Domain Registrar: $reg (Registered: ${dom.rdap.registeredDate ?: "Unknown"})", "RDAP")
            }
        }

        // Process Gravatar Photo
        gravatarCheck.await()?.let { gravatarPhoto ->
            photos += gravatarPhoto
            findings += SourcedFinding(category = "Media", claim = "Gravatar Face Profile Picture Recovered", sourceName = "Gravatar", sourceUrl = gravatarUrl, confidence = ConfidenceLevel.HIGH)
        }

        // Process Google & Web Comments
        val commentReport = commentsDef.await()
        commentReport?.comments?.forEach { hit ->
            publicRecords += AttributedValue("[${hit.platform}] ${hit.title} (${hit.authorMatch}): \"${hit.commentSnippet.take(180)}\"", hit.url)
            findings += SourcedFinding(
                category = "Public Record",
                claim = "Comment on ${hit.platform} (${hit.authorMatch}): ${hit.title} — \"${hit.commentSnippet.take(180)}\"",
                sourceName = hit.sourceEngine,
                sourceUrl = hit.url,
                confidence = if (hit.authorMatch.equals(cleanEmail, ignoreCase = true)) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM
            )
        }

        val dedupAccounts = deduplicateAccounts(accounts)
        val dedupNames = names.distinctBy { it.value.lowercase() }
        val dedupAliases = aliases.distinctBy { it.value.removePrefix("@").lowercase() }
        val dedupLocations = locations.distinctBy { it.value.lowercase() }
        val dedupPhotos = photos.distinctBy { it.url }

        val finalStatuses = listOf(
            PilotAgentStatus("agent_id", "Identity Resolution Agent", "Who is this, really?", "Resolved ${dedupNames.size} name(s), ${dedupAliases.size} username alias(es) & ${phones.distinct().size} phone(s)", countBadge = "${dedupNames.size + dedupAliases.size} identities", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = dedupNames.size + dedupAliases.size + phones.distinct().size),
            PilotAgentStatus("agent_footprint", "Digital Footprint Agent", "Where are they active?", "Probed email registrations, 300+ username sites & 24+ deep profile APIs", countBadge = "${dedupAccounts.size} accounts", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = dedupAccounts.size),
            PilotAgentStatus("agent_breach", "Breach Intelligence Agent", "What has already leaked?", if (breaches.isNotEmpty()) "Identified ${breaches.size} breach & infostealer exposures" else "Zero direct credential exposures detected", countBadge = "${breaches.size} breaches", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = breaches.size),
            PilotAgentStatus("agent_records", "Public Records Agent", "What do the public records say?", "Analyzed Google comments, Maps reviews, Wayback archives, DNS & PGP keys", countBadge = "${publicRecords.size} records", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = publicRecords.size),
            PilotAgentStatus("agent_telecom", "Telecom & Telemetry Agent", "Where can they be reached?", "Mapped locations, stealer IP geolocation, timezones & mail routing", countBadge = "${dedupLocations.size} locations", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = dedupLocations.size),
            PilotAgentStatus("agent_media", "Media & Visual Agent", "What do they look like?", if (dedupPhotos.isNotEmpty()) "Recovered ${dedupPhotos.size} visual profile avatars" else "No public avatar returned", countBadge = "${dedupPhotos.size} photos", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = dedupPhotos.size)
        )

        val finalReport = initialReport.copy(
            finishedAt = System.currentTimeMillis(),
            isRunning = false,
            agentStatuses = finalStatuses,
            resolvedNames = dedupNames,
            resolvedAliases = dedupAliases,
            linkedEmails = discoveredEmails.distinct(),
            linkedPhones = phones.distinct(),
            linkedAccounts = dedupAccounts,
            breachRecords = breaches,
            totalBreachCount = breaches.size,
            publicRecords = publicRecords.distinctBy { it.value.lowercase() },
            locations = dedupLocations,
            recoveredPhotos = dedupPhotos,
            findings = findings,
            rawMarkdownReport = buildMarkdown(initialReport.investigationId, cleanEmail, "EMAIL", finalStatuses, findings)
        )
        onUpdate(finalReport)
        finalReport
    }

    private suspend fun investigateUsername(
        username: String,
        context: PilotContext,
        initialReport: PilotInvestigationReport,
        onUpdate: (PilotInvestigationReport) -> Unit
    ): PilotInvestigationReport = coroutineScope {
        val clean = username.trim().removePrefix("@")
        val findings = mutableListOf<SourcedFinding>()
        val photos = mutableListOf<PilotMediaPhoto>()
        val accounts = mutableListOf<PhoneAccountHit>()
        val names = mutableListOf<AttributedValue>()
        val aliases = mutableListOf(AttributedValue("@$clean", "Target Handle"))
        val discoveredEmails = mutableListOf<String>()
        val discoveredPhones = mutableListOf<String>()
        val locations = mutableListOf<AttributedValue>()
        val publicRecords = mutableListOf<AttributedValue>()
        val breaches = mutableListOf<BreachDetail>()

        context.locationHint?.let { locations += AttributedValue(it, "User Context Hint") }
        context.knownAliases.forEach { aliases += AttributedValue("@$it", "User Context Hint") }

        // 1. Identity & Keybase / PGP / Wayback
        val enrichDef = async {
            runCatching { IdentityEnricher.enrich(clean, isEmail = false) }.getOrNull()
        }

        // 2. Username Scanning across 300+ databases + embedded Python engines + PublicProfileApis
        val userScanDef = async {
            val ktHits = runCatching {
                UsernameScanner.scanWithVariants(clean, limit = UsernameScanner.STANDARD_LIMIT).confirmed
            }.getOrDefault(emptyList())
            val pyHits = context.appContext?.let { app ->
                runCatching {
                    PythonOsintBridge.scanUsername(app, clean, PythonToolMode.ALL_PYTHON, UsernameScanner.STANDARD_LIMIT).confirmed
                }.getOrDefault(emptyList())
            }.orEmpty()
            val apiHits = runCatching { PublicProfileApis.lookup(clean) }.getOrDefault(emptyList())
            (ktHits + pyHits + apiHits)
                .map { hit -> hit.copy(profileUrl = OsintDatasets.cleanProfileUrl(hit.site, hit.profileUrl)) }
                .distinctBy { it.profileUrl.lowercase().trimEnd('/') }
        }

        // 3. Multi-Engine Open Web & Forum Mentions
        val webDef = async {
            runCatching { OpenWebOsintScraper.scrapeAll(listOf(clean)) }.getOrNull()
        }

        val confirmedHits = userScanDef.await()

        // 4. Deep Profile Dossiers across 24+ APIs and OpenGraph HTML
        val dossiersDef = async {
            runCatching { DeepProfileScraper.scrapeUsernameDossier(clean, confirmedHits) }.getOrDefault(emptyList())
        }

        val enrichment = enrichDef.await()
        enrichment?.let { en ->
            en.keybaseDisplayName?.let {
                names += AttributedValue(it, "Keybase Profile")
                findings += SourcedFinding(category = "Identity", claim = "Display Name: $it", sourceName = "Keybase", sourceUrl = "https://keybase.io/$clean", confidence = ConfidenceLevel.HIGH)
            }
            en.keybaseProofs.forEach { proof ->
                accounts += PhoneAccountHit(proof.service, "Cryptographically Verified", "@${proof.handle}", proof.proofUrl, confirmed = true)
                aliases += AttributedValue("@${proof.handle}", proof.service)
                findings += SourcedFinding(category = "Registration", claim = "Verified ${proof.service} Account: @${proof.handle}", sourceName = "Keybase Proofs", sourceUrl = proof.proofUrl, confidence = ConfidenceLevel.HIGH)
            }
            en.waybackSnapshots.forEach { wb ->
                publicRecords += AttributedValue("Wayback Archive: ${wb.targetUrl} (${wb.snapshotCount} snapshots, ${wb.firstSeen ?: "?"} → ${wb.lastSeen ?: "?"})", wb.latestArchiveUrl ?: "Internet Archive")
                findings += SourcedFinding(category = "Public Record", claim = "Wayback Machine Archive: ${wb.targetUrl} (${wb.snapshotCount} snapshots)", sourceName = "Internet Archive", sourceUrl = wb.latestArchiveUrl, confidence = ConfidenceLevel.HIGH)
            }
        }

        confirmedHits.forEach { hit ->
            val cleanUrl = OsintDatasets.cleanProfileUrl(hit.site, hit.profileUrl)
            accounts += PhoneAccountHit(
                platform = hit.site,
                status = hit.verificationStatus,
                detail = "Category: ${hit.category} • Source: ${hit.source} • ${hit.evidence}",
                url = cleanUrl,
                confirmed = hit.verificationStatus == "Matched"
            )
            findings += SourcedFinding(
                category = "Registration",
                claim = "Active Account on ${hit.site} (${hit.category})",
                sourceName = "${hit.site} (${hit.source})",
                sourceUrl = cleanUrl,
                confidence = ConfidenceLevel.HIGH
            )
        }

        dossiersDef.await().forEach { card ->
            val cleanUrl = OsintDatasets.cleanProfileUrl(card.platform, card.profileUrl)
            val detailParts = buildList {
                card.displayName?.let { add("Name: $it") }
                card.statsSummary?.let { add(it) }
                card.createdDate?.let { add("Joined: $it") }
                card.location?.let { add("Location: $it") }
                card.bio?.let { add("Bio: \"${it.take(160)}\"") }
                if (card.externalLinks.isNotEmpty()) add("Links: ${card.externalLinks.joinToString(", ")}")
            }.joinToString(" • ").ifBlank { "Public profile for @${card.handle}" }

            accounts += PhoneAccountHit(
                platform = "${card.platform} (@${card.handle})",
                status = "Enriched Dossier",
                detail = detailParts,
                url = cleanUrl,
                confirmed = true
            )
            card.displayName?.let {
                names += AttributedValue(it, card.platform)
                findings += SourcedFinding(category = "Identity", claim = "Display Name on ${card.platform}: $it", sourceName = card.platform, sourceUrl = cleanUrl, confidence = ConfidenceLevel.HIGH)
            }
            card.location?.let {
                locations += AttributedValue(it, card.platform)
                findings += SourcedFinding(category = "Location", claim = "Location on ${card.platform}: $it", sourceName = card.platform, sourceUrl = cleanUrl, confidence = ConfidenceLevel.HIGH)
            }
            card.discoveredEmails.forEach { e ->
                discoveredEmails += e
                findings += SourcedFinding(category = "Identity", claim = "Discovered Email on ${card.platform}: $e", sourceName = card.platform, sourceUrl = cleanUrl, confidence = ConfidenceLevel.HIGH)
            }
            card.discoveredPhones.forEach { p ->
                discoveredPhones += p
                findings += SourcedFinding(category = "Identity", claim = "Discovered Phone on ${card.platform}: $p", sourceName = card.platform, sourceUrl = cleanUrl, confidence = ConfidenceLevel.HIGH)
            }
            card.avatarUrl?.let { av ->
                photos += PilotMediaPhoto(av, card.platform, "${card.platform} (@${card.handle})")
            }
            findings += SourcedFinding(
                category = "Registration",
                claim = "${card.platform} Dossier: $detailParts",
                sourceName = card.platform,
                sourceUrl = cleanUrl,
                confidence = ConfidenceLevel.HIGH
            )
        }

        webDef.await()?.let { web ->
            discoveredEmails.addAll(web.extractedEmails.map { it.value })
            discoveredPhones.addAll(web.extractedPhones.map { it.value })
            aliases.addAll(web.extractedHandles)
            locations.addAll(web.extractedLocations)
            web.results.take(20).forEach { r ->
                publicRecords += AttributedValue("[${r.source}] ${r.title} — ${r.snippet.take(140)}", r.url)
                findings += SourcedFinding(category = "Public Record", claim = "${r.title}: ${r.snippet.take(140)}", sourceName = r.source, sourceUrl = r.url, confidence = ConfidenceLevel.MEDIUM)
            }
        }

        // 5. Run Free Public Records, Breach PII, HudsonRock Username Stealer & Alias Correlation
        val deepPublicRecords = FreePublicRecordsAndBreachIntel.enrichTarget(
            email = discoveredEmails.firstOrNull(),
            username = clean,
            fullName = names.firstOrNull { ' ' in it.value }?.value,
            discoveredHandles = aliases.map { it.value.removePrefix("@") },
            existingLocations = locations
        )

        if (deepPublicRecords.piiSummary.exposedFields.isNotEmpty()) {
            val piiClaim = "Exposed PII Classes for @$clean: ${deepPublicRecords.piiSummary.exposedFields.joinToString(", ")}"
            publicRecords += AttributedValue(piiClaim, "LeakCheck Public API")
            findings += SourcedFinding(category = "Breach", claim = piiClaim, sourceName = "LeakCheck Public API", confidence = ConfidenceLevel.HIGH)
        }
        deepPublicRecords.piiSummary.leakCheckSources.forEach { src ->
            breaches += BreachDetail(
                name = src.substringBefore(" (").trim(),
                breachDate = src.substringAfter("(", "").removeSuffix(")").takeIf { it.isNotBlank() },
                description = "LeakCheck breach record for @$clean: $src",
                dataClasses = deepPublicRecords.piiSummary.exposedFields.ifEmpty { listOf("Username", "Credentials") },
                verified = true
            )
            findings += SourcedFinding(category = "Breach", claim = "Username @$clean exposed in $src", sourceName = "LeakCheck Public API", confidence = ConfidenceLevel.HIGH)
        }
        deepPublicRecords.piiSummary.stealerUsernameHits.forEach { sh ->
            breaches += BreachDetail(
                name = "HudsonRock Infostealer Log",
                description = sh,
                dataClasses = listOf("Username", "Saved Credentials", "System Telemetry"),
                verified = true
            )
            findings += SourcedFinding(category = "Breach", claim = sh, sourceName = "HudsonRock Username Cavalier", confidence = ConfidenceLevel.HIGH)
        }
        deepPublicRecords.filings.forEach { filing ->
            publicRecords += AttributedValue("[${filing.category}] ${filing.title} — ${filing.detail}", filing.url)
            filing.addressOrLocation?.let { addr -> locations += AttributedValue(addr, filing.category) }
            findings += SourcedFinding(
                category = "Public Record",
                claim = "${filing.title}: ${filing.detail}",
                sourceName = filing.category,
                sourceUrl = filing.url,
                confidence = ConfidenceLevel.HIGH
            )
        }
        deepPublicRecords.aliasCorrelations.forEach { corr ->
            findings += SourcedFinding(
                category = "Identity",
                claim = "Alias Correlation (@${corr.handleA} ↔ @${corr.handleB}): ${corr.reason} (${corr.confidenceScore}% match)",
                sourceName = "Alias Graph Engine",
                confidence = ConfidenceLevel.HIGH
            )
        }
        deepPublicRecords.associates.forEach { assoc ->
            names += assoc
            findings += SourcedFinding(category = "Identity", claim = "Associated Officer / Entity: ${assoc.value}", sourceName = assoc.source, confidence = ConfidenceLevel.MEDIUM)
        }

        // Cross-pivot: If an email was discovered for this username, check full email breaches
        discoveredEmails.distinct().take(2).forEach { emailHit ->
            val br = runCatching { EmailScanner.check(emailHit) }.getOrNull()
            if (br != null) {
                breaches.addAll(synthesizeBreachDetails(br))
                br.breachNames.forEach { bName ->
                    findings += SourcedFinding(category = "Breach", claim = "Breach via linked email ($emailHit): $bName", sourceName = "Breach Intelligence", confidence = ConfidenceLevel.HIGH)
                }
            }
        }

        val dedupAccounts = deduplicateAccounts(accounts)
        val dedupBreaches = breaches.distinctBy { it.name.lowercase() }
        val dedupPhotos = photos.distinctBy { it.url }

        val finalStatuses = listOf(
            PilotAgentStatus("agent_id", "Identity Resolution Agent", "Who is this, really?", "Resolved ${names.distinctBy { it.value.lowercase() }.size} name(s), ${aliases.distinctBy { it.value.lowercase() }.size} alias(es) & ${discoveredEmails.distinct().size} email(s)", countBadge = "${names.distinctBy { it.value.lowercase() }.size} names", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = names.size + aliases.size + discoveredEmails.size),
            PilotAgentStatus("agent_footprint", "Digital Footprint Agent", "Where are they active?", "Scanned 300+ platforms & 27+ deep profile APIs", countBadge = "${dedupAccounts.size} accounts", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = dedupAccounts.size),
            PilotAgentStatus("agent_breach", "Breach Intelligence Agent", "What has already leaked?", if (dedupBreaches.isNotEmpty()) "Identified ${dedupBreaches.size} breach & stealer exposures" else "No breach exposures found", countBadge = "${dedupBreaches.size} breaches", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = dedupBreaches.size),
            PilotAgentStatus("agent_records", "Public Records Agent", "What do the public records say?", "Cross-referenced Companies House, Gazette, Case Law, Wayback & web records", countBadge = "${publicRecords.size} records", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = publicRecords.size),
            PilotAgentStatus("agent_telecom", "Telecom & Telemetry Agent", "Where can they be reached?", "Mapped profile locations, postcodes & contact signals", countBadge = "${locations.distinctBy { it.value.lowercase() }.size} locations", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = locations.size + discoveredPhones.size),
            PilotAgentStatus("agent_media", "Media & Visual Agent", "What do they look like?", if (dedupPhotos.isNotEmpty()) "Recovered ${dedupPhotos.size} profile avatars" else "No avatar found", countBadge = "${dedupPhotos.size} photos", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = dedupPhotos.size)
        )

        val finalReport = initialReport.copy(
            finishedAt = System.currentTimeMillis(),
            isRunning = false,
            agentStatuses = finalStatuses,
            resolvedNames = names.distinctBy { it.value.lowercase() },
            resolvedAliases = aliases.distinctBy { it.value.removePrefix("@").lowercase() },
            linkedEmails = discoveredEmails.distinct(),
            linkedPhones = discoveredPhones.distinct(),
            linkedAccounts = dedupAccounts,
            breachRecords = dedupBreaches,
            totalBreachCount = dedupBreaches.size,
            publicRecords = publicRecords.distinctBy { it.value.lowercase() },
            locations = locations.distinctBy { it.value.lowercase() },
            recoveredPhotos = dedupPhotos,
            findings = findings,
            rawMarkdownReport = buildMarkdown(initialReport.investigationId, username, "USERNAME", finalStatuses, findings)
        )
        onUpdate(finalReport)
        finalReport
    }

    private suspend fun investigateName(
        name: String,
        context: PilotContext,
        initialReport: PilotInvestigationReport,
        onUpdate: (PilotInvestigationReport) -> Unit
    ): PilotInvestigationReport = coroutineScope {
        val findings = mutableListOf<SourcedFinding>()
        val photos = mutableListOf<PilotMediaPhoto>()
        val accounts = mutableListOf<PhoneAccountHit>()
        val names = mutableListOf(AttributedValue(name, "Target Query"))
        val aliases = mutableListOf<AttributedValue>()
        val locations = mutableListOf<AttributedValue>()
        val discoveredEmails = mutableListOf<String>()
        val discoveredPhones = mutableListOf<String>()
        val publicRecords = mutableListOf<AttributedValue>()
        val breaches = mutableListOf<BreachDetail>()

        context.locationHint?.let { locations += AttributedValue(it, "User Context Hint") }
        context.knownAliases.forEach { aliases += AttributedValue("@$it", "User Context Hint") }

        val query = "\"$name\" ${context.locationHint.orEmpty()}".trim()
        val webDef = async {
            runCatching {
                OpenWebOsintScraper.scrapeAll(
                    listOf(
                        query,
                        "\"$name\" (site:linkedin.com/in OR site:company-information.service.gov.uk OR site:thegazette.co.uk OR site:github.com)"
                    )
                )
            }.getOrNull()
        }
        val ghUsersDef = async {
            runCatching { GitHubOsint.searchUsers(name, limit = 5) }.getOrDefault(emptyList())
        }

        // Run Real-Time Public Records (UK Companies House, Official Gazette, UK National Archives Case Law, GLEIF)
        val deepRecordsDef = async {
            FreePublicRecordsAndBreachIntel.enrichTarget(
                fullName = name,
                discoveredHandles = context.knownAliases,
                existingLocations = locations
            )
        }

        ghUsersDef.await().forEach { u ->
            accounts += PhoneAccountHit("GitHub (@${u.login})", "Matched Name Search", "GitHub user matching \"$name\"", u.htmlUrl, confirmed = true)
            aliases += AttributedValue("@${u.login}", "GitHub")
            if (u.avatarUrl.startsWith("http")) {
                photos += PilotMediaPhoto(u.avatarUrl, "GitHub", "@${u.login}")
            }
            findings += SourcedFinding(category = "Registration", claim = "GitHub Profile matching \"$name\": @${u.login}", sourceName = "GitHub Search", sourceUrl = u.htmlUrl, confidence = ConfidenceLevel.MEDIUM)
        }

        webDef.await()?.let { web ->
            discoveredEmails.addAll(web.extractedEmails.map { it.value })
            discoveredPhones.addAll(web.extractedPhones.map { it.value })
            aliases.addAll(web.extractedHandles)
            locations.addAll(web.extractedLocations)
            web.results.forEach { item ->
                publicRecords += AttributedValue("[${item.source}] ${item.title} — ${item.snippet.take(140)}", item.url)
                findings += SourcedFinding(
                    category = "Public Record",
                    claim = item.title + (if (item.snippet.isNotBlank()) " — ${item.snippet.take(140)}" else ""),
                    sourceName = item.source,
                    sourceUrl = item.url,
                    confidence = ConfidenceLevel.HIGH
                )
            }
        }

        val deepPublicRecords = deepRecordsDef.await()
        deepPublicRecords.filings.forEach { filing ->
            publicRecords += AttributedValue("[${filing.category}] ${filing.title} — ${filing.detail}", filing.url)
            filing.addressOrLocation?.let { addr ->
                locations += AttributedValue(addr, filing.category)
                findings += SourcedFinding(category = "Location", claim = "Registered Filing Address: $addr", sourceName = filing.category, sourceUrl = filing.url, confidence = ConfidenceLevel.HIGH)
            }
            findings += SourcedFinding(
                category = "Public Record",
                claim = "${filing.title}: ${filing.detail}",
                sourceName = filing.category,
                sourceUrl = filing.url,
                confidence = ConfidenceLevel.HIGH
            )
        }
        deepPublicRecords.associates.forEach { assoc ->
            names += assoc
            findings += SourcedFinding(category = "Identity", claim = "Associated Officer / Entity: ${assoc.value}", sourceName = assoc.source, confidence = ConfidenceLevel.MEDIUM)
        }
        deepPublicRecords.aliasCorrelations.forEach { corr ->
            findings += SourcedFinding(
                category = "Identity",
                claim = "Alias Correlation (@${corr.handleA} ↔ @${corr.handleB}): ${corr.reason} (${corr.confidenceScore}% match)",
                sourceName = "Alias Graph Engine",
                confidence = ConfidenceLevel.HIGH
            )
        }

        // If web or public records uncovered an email address for this person, check breaches automatically
        discoveredEmails.distinct().take(2).forEach { emailHit ->
            val br = runCatching { EmailScanner.check(emailHit) }.getOrNull()
            if (br != null) {
                breaches.addAll(synthesizeBreachDetails(br))
                br.breachNames.forEach { bName ->
                    findings += SourcedFinding(category = "Breach", claim = "Breach via discovered email ($emailHit): $bName", sourceName = "Breach Intelligence", confidence = ConfidenceLevel.HIGH)
                }
            }
        }

        val dedupAccounts = deduplicateAccounts(accounts)
        val dedupBreaches = breaches.distinctBy { it.name.lowercase() }
        val dedupLocations = locations.distinctBy { it.value.lowercase() }

        val finalStatuses = listOf(
            PilotAgentStatus("agent_id", "Identity Resolution Agent", "Who is this, really?", "Searched corporate officers, associates, GitHub & public web", countBadge = "${names.size + aliases.size} identities", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = names.size + aliases.size),
            PilotAgentStatus("agent_footprint", "Digital Footprint Agent", "Where are they active?", "Checked LinkedIn, GitHub & public social indexes", countBadge = "${dedupAccounts.size} profiles", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = dedupAccounts.size),
            PilotAgentStatus("agent_breach", "Breach Intelligence Agent", "What has already leaked?", if (dedupBreaches.isNotEmpty()) "Identified ${dedupBreaches.size} breach records via discovered email" else "No direct email breach records exposed", countBadge = "${dedupBreaches.size} breaches", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = dedupBreaches.size),
            PilotAgentStatus("agent_records", "Public Records Agent", "What do the public records say?", "Queried UK Companies House, Official Gazette, Case Law & GLEIF", countBadge = "${publicRecords.size} filings", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = publicRecords.size),
            PilotAgentStatus("agent_telecom", "Telecom & Telemetry Agent", "Where can they be reached?", "Extracted registered correspondence addresses, postcodes & phones", countBadge = "${dedupLocations.size} locations", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = dedupLocations.size + discoveredPhones.size),
            PilotAgentStatus("agent_media", "Media & Visual Agent", "What do they look like?", "Recovered ${photos.size} profile avatars", countBadge = "${photos.size} photos", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = photos.size)
        )

        val finalReport = initialReport.copy(
            finishedAt = System.currentTimeMillis(),
            isRunning = false,
            agentStatuses = finalStatuses,
            resolvedNames = names.distinctBy { it.value.lowercase() },
            resolvedAliases = aliases.distinctBy { it.value.lowercase() },
            linkedEmails = discoveredEmails.distinct(),
            linkedPhones = discoveredPhones.distinct(),
            linkedAccounts = dedupAccounts,
            breachRecords = dedupBreaches,
            totalBreachCount = dedupBreaches.size,
            locations = dedupLocations,
            recoveredPhotos = photos.distinctBy { it.url },
            publicRecords = publicRecords.distinctBy { it.value.lowercase() },
            findings = findings,
            rawMarkdownReport = buildMarkdown(initialReport.investigationId, name, "NAME", finalStatuses, findings)
        )
        onUpdate(finalReport)
        finalReport
    }

    /**
     * Ensures every breach in `BreachReport.breachNames` (including Naz.API, AlienStealerLogs,
     * HudsonRock Infostealer Logs, COMB, and Pastebin Dumps) is represented as a `BreachDetail`
     * card even if it is not listed in the HIBP `BreachCatalog`.
     */
    private fun synthesizeBreachDetails(bo: BreachReport): List<BreachDetail> {
        val out = bo.details.toMutableList()
        bo.breachNames.forEach { rawName ->
            val baseName = rawName.substringBefore(" (").trim()
            val alreadyInCatalog = out.any {
                it.name.equals(baseName, ignoreCase = true) ||
                    rawName.contains(it.name, ignoreCase = true)
            }
            if (!alreadyInCatalog) {
                val dateHint = Regex("""\b(20\d{2}(?:-\d{2}-\d{2})?)\b""").find(rawName)?.groupValues?.get(1)
                val insideParens = rawName.substringAfter("(", "").removeSuffix(")").trim()
                val dataClasses = when {
                    rawName.startsWith("Infostealer Log", ignoreCase = true) ->
                        listOf("Infostealer Malware Log", "Saved Credentials", "System Telemetry")
                    rawName.startsWith("COMB", ignoreCase = true) ->
                        listOf("Email addresses", "Historical Credential Compilation")
                    rawName.startsWith("Public Pastebin", ignoreCase = true) ->
                        listOf("Public Pastebin Dump Mention")
                    insideParens.contains("•") ->
                        insideParens.substringAfter("•").split(",").map { it.trim() }.filter { it.isNotBlank() }
                    else -> listOf("Email addresses", "Exposed breach record")
                }
                out += BreachDetail(
                    name = baseName,
                    breachDate = dateHint,
                    description = rawName,
                    dataClasses = dataClasses,
                    verified = true
                )
            }
        }
        return out.distinctBy { it.name.lowercase() }
    }

    /**
     * Merges accounts by platform + URL while preferring entries with richer details (e.g. Enriched Dossier).
     */
    private fun deduplicateAccounts(accounts: List<PhoneAccountHit>): List<PhoneAccountHit> {
        val map = LinkedHashMap<String, PhoneAccountHit>()
        for (acc in accounts) {
            val basePlatform = acc.platform.substringBefore(" (@").trim().lowercase()
            val urlKey = acc.url?.lowercase()?.trimEnd('/').orEmpty()
            val key = if (urlKey.isNotBlank() && urlKey != "https://$basePlatform.com") "$basePlatform|$urlKey" else basePlatform
            val existing = map[key]
            if (existing == null || acc.detail.length > existing.detail.length) {
                map[key] = acc
            }
        }
        return map.values.sortedWith(
            compareByDescending<PhoneAccountHit> { it.status == "Enriched Dossier" }
                .thenByDescending { it.confirmed }
                .thenBy { it.platform.lowercase() }
        )
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: "Web Source"

    private fun buildMarkdown(
        id: String,
        target: String,
        type: String,
        agents: List<PilotAgentStatus>,
        findings: List<SourcedFinding>
    ): String = buildString {
        appendLine("# OSINT Pilot — Automated Intelligence Investigation Report")
        appendLine("**Investigation ID:** #$id")
        appendLine("**Target:** `$target` ($type)")
        appendLine("**Generated:** ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("**Platform:** OSINT Pilot On-Device Autonomous Engine")
        appendLine()
        appendLine("---")
        appendLine()
        appendLine("## Agent Status & Investigation Timeline")
        agents.forEach { agent ->
            appendLine("- **${agent.agentName}** [${agent.question}]: ${agent.statusText} (${agent.countBadge ?: "Completed"})")
        }
        appendLine()
        appendLine("---")
        appendLine()
        appendLine("## Sourced Findings (${findings.size} Total)")
        findings.groupBy { it.category }.forEach { (cat, items) ->
            appendLine("### $cat (${items.size})")
            items.forEach { f ->
                val link = if (f.sourceUrl != null) " [Source Link](${f.sourceUrl})" else ""
                appendLine("- [${f.confidence}] **${f.sourceName}**: ${f.claim}$link")
            }
            appendLine()
        }
        appendLine("---")
        appendLine("*Report certified and compiled by OSINT Pilot*")
    }
}
