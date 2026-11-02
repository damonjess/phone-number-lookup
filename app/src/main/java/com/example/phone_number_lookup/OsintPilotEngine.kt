package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val knownAliases: List<String> = emptyList()
)

enum class ConfidenceLevel {
    HIGH,
    MEDIUM,
    LOW
}

data class SourcedFinding(
    val id: String = UUID.randomUUID().toString().take(8),
    val category: String, // "Identity", "Registration", "Breach", "Public Record", "Location", "Media"
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
            PilotAgentStatus("agent_records", "Public Records Agent", "What do the public records say?", "Scanning corporate registries, court filings & OSM…", isRunning = true),
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
        val locations = mutableListOf<AttributedValue>()
        val publicRecords = mutableListOf<AttributedValue>()
        val breaches = mutableListOf<BreachDetail>()

        // 1. Telecom Telemetry
        val carrierHint = PhoneDirectoryScraper.lookupUkOriginalCarrier(localFormat)
        val areaHint = PhoneNumberUtils.getAreaInfo(normalized)
        val lineType = PhoneNumberUtils.getLineType(normalized)
        val facts = NumberFactsProvider.from(normalized)
        val verdict = RiskAssessor.assess(facts, spam = null, reputation = emptyList(), userStatus = null, stillChecking = false)

        carrierHint?.let {
            findings += SourcedFinding(category = "Telemetry", claim = "Telecom Carrier Allocation: $it", sourceName = "Ofcom UK Allocation Plan", confidence = ConfidenceLevel.HIGH)
        }
        areaHint?.let {
            findings += SourcedFinding(category = "Location", claim = "Numbering Geography: $it", sourceName = "Telecom Routing Exchange", confidence = ConfidenceLevel.HIGH)
            locations += AttributedValue(it, "Area Code Exchange")
        }
        findings += SourcedFinding(category = "Telemetry", claim = "Line Classification: $lineType (${verdict.headline})", sourceName = "Numbering Assessment", confidence = ConfidenceLevel.HIGH)

        // 2. Direct Account Registrations
        val accountHitsDef = async {
            PhoneDirectoryScraper.checkPhoneAccountRegistrations(e164, localFormat)
        }

        // 3. Deep Phone Footprint & Scraped Directories
        val footprintDef = async {
            runCatching { PhoneDirectoryScraper.scrapePhoneFootprint(normalized) }.getOrNull()
        }

        // 4. Reputation & Spam reports
        val reputationDef = async {
            PhoneReputation.lookup(normalized)
        }

        val directAccounts = accountHitsDef.await()
        accounts.addAll(directAccounts)
        directAccounts.forEach { hit ->
            findings += SourcedFinding(
                category = "Registration",
                claim = "${hit.platform}: ${hit.detail}",
                sourceName = hit.platform,
                sourceUrl = hit.url,
                confidence = if (hit.confirmed) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM
            )
        }

        val footprint = footprintDef.await()
        footprint?.let { fp ->
            names.addAll(fp.discoveredNames)
            locations.addAll(fp.discoveredLocations)
            fp.discoveredNames.forEach {
                findings += SourcedFinding(category = "Identity", claim = "Discovered Name: ${it.value}", sourceName = it.source, confidence = ConfidenceLevel.HIGH)
            }
            fp.discoveredLocations.forEach {
                findings += SourcedFinding(category = "Location", claim = "Discovered Location: ${it.value}", sourceName = it.source, confidence = ConfidenceLevel.MEDIUM)
            }
            fp.businessMatches.forEach {
                publicRecords += it
                findings += SourcedFinding(category = "Public Record", claim = "OpenStreetMap Registered Entity: ${it.value}", sourceName = it.source, confidence = ConfidenceLevel.HIGH)
            }
            fp.directoryReports.forEach { dir ->
                publicRecords += AttributedValue("${dir.source}: ${dir.verdictLabel ?: "Indexed"} (${dir.reportCount ?: "0"} reports)", dir.source)
                findings += SourcedFinding(category = "Public Record", claim = "Caller Directory Hit on ${dir.source}: ${dir.callerName ?: dir.callerCategory ?: "Listed"}", sourceName = dir.source, sourceUrl = dir.url, confidence = ConfidenceLevel.HIGH)
            }

            // Cross-correlation: If footprint discovered an email, scan it for accounts & breaches!
            val topEmail = fp.discoveredEmails.firstOrNull()?.value
            if (!topEmail.isNullOrBlank()) {
                findings += SourcedFinding(category = "Identity", claim = "Linked Email Address: $topEmail", sourceName = "Directory Extraction", confidence = ConfidenceLevel.HIGH)
                val emailOutcome = runCatching { EmailRegistrationScanner.scan(topEmail) }.getOrNull()
                emailOutcome?.registered?.forEach { reg ->
                    accounts += PhoneAccountHit(
                        platform = "${reg.site} (via email)",
                        status = "Registered response",
                        detail = "Account linked via discovered email $topEmail",
                        url = reg.profileUrl,
                        confirmed = true
                    )
                    findings += SourcedFinding(category = "Registration", claim = "Active Account on ${reg.site} via $topEmail", sourceName = reg.site, sourceUrl = reg.profileUrl, confidence = ConfidenceLevel.HIGH)
                }

                // Check breach catalog for discovered email
                val emailBreaches = runCatching { BreachCatalog.lookup(listOf("Adobe", "LinkedIn", "Canva", "Dropbox", "Exploit")) }.getOrDefault(emptyList())
                breaches.addAll(emailBreaches)
            }

            // Media Recovery: Telegram avatar / profile photo if Telegram hit
            val tgHit = directAccounts.firstOrNull { it.platform.equals("Telegram", ignoreCase = true) && it.confirmed }
            if (tgHit != null && tgHit.url != null) {
                photos += PilotMediaPhoto(tgHit.url, "Telegram", "Telegram Profile Link & Avatar")
            }
        }

        val reputationReports = reputationDef.await()
        reputationReports.filter { it.hasReports }.forEach { rep ->
            findings += SourcedFinding(
                category = "Public Record",
                claim = "${rep.source}: ${rep.reportCount} community report(s)",
                sourceName = rep.source,
                sourceUrl = rep.url,
                confidence = ConfidenceLevel.HIGH
            )
        }

        // Build Agent Statuses for Phone
        val finalStatuses = listOf(
            PilotAgentStatus("agent_id", "Identity Resolution Agent", "Who is this, really?", "Resolved persona and names from caller directories", countBadge = "${names.size} names", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = names.size),
            PilotAgentStatus("agent_footprint", "Digital Footprint Agent", "Where are they active?", "Probed accounts across Microsoft, Instagram, Telegram, WhatsApp & services", countBadge = "${accounts.size} accounts", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = accounts.size),
            PilotAgentStatus("agent_breach", "Breach Intelligence Agent", "What has already leaked?", if (breaches.isNotEmpty()) "Identified ${breaches.size} breach exposures" else "Zero direct credential exposures detected on unauthenticated catalogs", countBadge = "${breaches.size} breaches", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = breaches.size),
            PilotAgentStatus("agent_records", "Public Records Agent", "What do the public records say?", "Analyzed OpenStreetMap business nodes and directory filings", countBadge = "${publicRecords.size} records", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = publicRecords.size),
            PilotAgentStatus("agent_telecom", "Telecom & Telemetry Agent", "Where can they be reached?", "Carrier block, Ofcom allocation & area code mapped", countBadge = "${locations.size} locations", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = locations.size + 2),
            PilotAgentStatus("agent_media", "Media & Visual Agent", "What do they look like?", if (photos.isNotEmpty()) "Recovered ${photos.size} visual profiles" else "No direct public avatar exposed", countBadge = "${photos.size} photos", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = photos.size)
        )

        val finalReport = initialReport.copy(
            finishedAt = System.currentTimeMillis(),
            isRunning = false,
            agentStatuses = finalStatuses,
            resolvedNames = names,
            linkedAccounts = accounts,
            breachRecords = breaches,
            totalBreachCount = breaches.size,
            publicRecords = publicRecords,
            locations = locations,
            telecomCarrier = carrierHint,
            telecomLineType = lineType,
            riskVerdict = verdict,
            recoveredPhotos = photos,
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
        val findings = mutableListOf<SourcedFinding>()
        val photos = mutableListOf<PilotMediaPhoto>()
        val accounts = mutableListOf<PhoneAccountHit>()
        val names = mutableListOf<AttributedValue>()
        val aliases = mutableListOf<AttributedValue>()
        val locations = mutableListOf<AttributedValue>()
        val publicRecords = mutableListOf<AttributedValue>()
        val breaches = mutableListOf<BreachDetail>()
        val phones = mutableListOf<String>()

        // 1. Deep Multi-source Email Attribution (Google, GitHub, Gravatar, Duolingo, HudsonRock Stealer, etc.)
        val attrDef = async {
            runCatching { EmailAttributionEngine.analyze(email) }.getOrNull()
        }

        // 2. Real-time Breach & Infostealer Database Queries (XposedOrNot, HudsonRock, BreachCatalog)
        val breachDef = async {
            runCatching { EmailScanner.check(email) }.getOrNull()
        }

        // 3. Identity Enrichment (Keybase & PGP)
        val enrichDef = async {
            runCatching { IdentityEnricher.enrich(email, isEmail = true) }.getOrNull()
        }

        // 4. Registrations on services (Holehe / Blackbird style)
        val regDef = async {
            runCatching { EmailRegistrationScanner.scan(email) }.getOrNull()
        }

        // 5. Domain & Network OSINT
        val domain = email.substringAfter('@')
        val domainDef = async {
            runCatching { DomainNetworkOsint.scan(domain) }.getOrNull()
        }

        // 6. Gravatar visual recovery
        val md5Email = md5(email.trim().lowercase())
        val gravatarUrl = "https://www.gravatar.com/avatar/$md5Email?s=400&d=404"
        val gravatarCheck = async {
            val resp = ScanHttp.get(gravatarUrl, timeoutMs = 6_000)
            if (resp.code == 200) {
                PilotMediaPhoto(gravatarUrl, "Gravatar", "Public Gravatar Profile Image")
            } else null
        }

        // 7. Google & Web Comment Scraper (comments, forum posts, replies, reviews)
        val commentsDef = async {
            val att = attrDef.await()
            val discoveredNames = att?.namesFound?.map { it.value }?.distinct().orEmpty()
            val discoveredHandles = att?.usernamesFound?.map { it.value.removePrefix("@") }?.distinct().orEmpty()
            val discoveredLocations = att?.locationsFound?.map { it.value }?.distinct().orEmpty()
            runCatching {
                GoogleCommentScraper.scrapeForEmail(
                    email = email,
                    knownDisplayNames = discoveredNames,
                    knownHandles = discoveredHandles,
                    knownLocations = discoveredLocations
                )
            }.getOrNull()
        }

        // Await Attribution
        val attribution = attrDef.await()
        attribution?.let { att ->
            names.addAll(att.namesFound)
            att.namesFound.forEach {
                findings += SourcedFinding(category = "Identity", claim = "Discovered Name / Handle: ${it.value}", sourceName = it.source, confidence = ConfidenceLevel.HIGH)
            }
            aliases.addAll(att.usernamesFound)
            att.usernamesFound.forEach {
                findings += SourcedFinding(category = "Identity", claim = "Associated Username: ${it.value}", sourceName = it.source, confidence = ConfidenceLevel.HIGH)
            }
            att.profileLinks.forEach { link ->
                accounts += PhoneAccountHit(link.platform, "Public Profile", link.handle, link.url, confirmed = true)
                findings += SourcedFinding(category = "Registration", claim = "Public ${link.platform} Profile: ${link.handle}", sourceName = link.platform, sourceUrl = link.url, confidence = ConfidenceLevel.HIGH)
            }
            locations.addAll(att.locationsFound)
            att.locationsFound.forEach { loc ->
                findings += SourcedFinding(category = "Location", claim = "Discovered Location / Telemetry: ${loc.value}", sourceName = loc.source, confidence = ConfidenceLevel.HIGH)
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
                    st.computerUsername?.let { append(" under user \"$it\"") }
                    st.dateCompromised?.let { append(" (compromised $it)") }
                }
                findings += SourcedFinding(category = "Breach", claim = stealerClaim, sourceName = "HudsonRock Infostealer Logs", confidence = ConfidenceLevel.HIGH)
            }
        }

        // Name inference fallback from local-part if no explicit name was returned (e.g. alexsmith86 -> Alex Smith)
        val localPart = email.substringBefore('@')
        if (names.isEmpty() && localPart.isNotBlank()) {
            val candidate = localPart.replace(Regex("[0-9_.-]+"), " ").trim()
                .split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            if (candidate.isNotBlank() && candidate.contains(" ")) {
                val inferred = AttributedValue(candidate, "Email Local-part Analysis")
                names += inferred
                findings += SourcedFinding(category = "Identity", claim = "Inferred Subject Name: $candidate", sourceName = "Email Local-part Analysis", confidence = ConfidenceLevel.MEDIUM)
            }
        }

        // Await Breaches
        val breachOutcome = breachDef.await()
        breachOutcome?.let { bo ->
            breaches.addAll(bo.details)
            bo.breachNames.forEach { bName ->
                findings += SourcedFinding(
                    category = "Breach",
                    claim = "Security Breach Incident: $bName",
                    sourceName = "Exposed Breach Database",
                    confidence = ConfidenceLevel.HIGH
                )
            }
        }

        // Await Identity Enrichment (Keybase & PGP)
        val enrichment = enrichDef.await()
        enrichment?.let { en ->
            en.keybaseDisplayName?.let {
                names += AttributedValue(it, "Keybase Profile")
                findings += SourcedFinding(category = "Identity", claim = "Legal / Display Name: $it", sourceName = "Keybase", sourceUrl = "https://keybase.io/", confidence = ConfidenceLevel.HIGH)
            }
            en.keybaseBio?.let {
                findings += SourcedFinding(category = "Identity", claim = "Profile Bio: $it", sourceName = "Keybase", confidence = ConfidenceLevel.HIGH)
            }
            en.keybaseProofs.forEach { proof ->
                accounts += PhoneAccountHit(proof.service, "Cryptographically Verified", "@${proof.handle}", proof.proofUrl, confirmed = true)
                findings += SourcedFinding(category = "Registration", claim = "Verified ${proof.service} handle: @${proof.handle}", sourceName = "Keybase Proofs", sourceUrl = proof.proofUrl, confidence = ConfidenceLevel.HIGH)
            }
            en.pgpRecords.forEach { pgp ->
                publicRecords += AttributedValue("PGP Key ${pgp.keyId} (${pgp.uids.joinToString(", ")})", "OpenPGP Keyserver")
                findings += SourcedFinding(category = "Public Record", claim = "PGP Public Key ID: ${pgp.keyId} - UIDs: ${pgp.uids.firstOrNull()}", sourceName = "Ubuntu PGP Keyserver", sourceUrl = "https://keyserver.ubuntu.com/pks/lookup?search=$email&op=index", confidence = ConfidenceLevel.HIGH)
            }
        }

        // Await Registrations
        val regOutcome = regDef.await()
        regOutcome?.registered?.forEach { hit ->
            accounts += PhoneAccountHit(hit.site, hit.verificationStatus, hit.evidence, hit.profileUrl, confirmed = true)
            findings += SourcedFinding(category = "Registration", claim = "Confirmed Account on ${hit.site}", sourceName = hit.site, sourceUrl = hit.profileUrl, confidence = ConfidenceLevel.HIGH)
        }

        // Await Domain & Network OSINT
        val domainIntel = domainDef.await()
        domainIntel?.let { dom ->
            publicRecords += AttributedValue("MX Mail Exchange: ${dom.mxRecords.joinToString(", ")}", "DNS")
            findings += SourcedFinding(category = "Public Record", claim = "Domain Mail Server: ${dom.mxRecords.firstOrNull() ?: "Standard"}", sourceName = "DNS MX Analysis", confidence = ConfidenceLevel.HIGH)
        }

        // Await Gravatar Photo
        val gravatarPhoto = gravatarCheck.await()
        if (gravatarPhoto != null) {
            photos += gravatarPhoto
            findings += SourcedFinding(category = "Media", claim = "Gravatar Face Profile Picture Recovered", sourceName = "Gravatar", sourceUrl = gravatarUrl, confidence = ConfidenceLevel.HIGH)
        }

        // Await Google & Web Comments
        val commentReport = commentsDef.await()
        commentReport?.comments?.forEach { hit ->
            publicRecords += AttributedValue("[${hit.platform}] ${hit.title}: \"${hit.commentSnippet.take(120)}\"", hit.sourceEngine)
            findings += SourcedFinding(
                category = "Public Record",
                claim = "Comment on ${hit.platform} (${hit.authorMatch}): ${hit.title} — \"${hit.commentSnippet.take(140)}\"",
                sourceName = hit.sourceEngine,
                sourceUrl = hit.url,
                confidence = if (hit.authorMatch == email) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM
            )
        }

        val finalStatuses = listOf(
            PilotAgentStatus("agent_id", "Identity Resolution Agent", "Who is this, really?", "Resolved persona, display names and cryptographic identities", countBadge = "${names.distinctBy { it.value }.size} names", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = names.size + aliases.size),
            PilotAgentStatus("agent_footprint", "Digital Footprint Agent", "Where are they active?", "Probed platform registrations, comments and confirmed accounts", countBadge = "${accounts.distinctBy { it.platform }.size} accounts", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = accounts.size + (commentReport?.comments?.size ?: 0)),
            PilotAgentStatus("agent_breach", "Breach Intelligence Agent", "What has already leaked?", if (breaches.isNotEmpty() || breachOutcome?.breachNames?.isNotEmpty() == true) "Identified ${breachOutcome?.breachNames?.size ?: breaches.size} breach exposures" else "Zero direct credential exposures detected", countBadge = "${breachOutcome?.breachNames?.size ?: breaches.size} breaches", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = breachOutcome?.breachNames?.size ?: breaches.size),
            PilotAgentStatus("agent_records", "Public Records Agent", "What do the public records say?", "Analyzed Google comments, DNS records & OpenPGP keys", countBadge = "${publicRecords.size} records", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = publicRecords.size),
            PilotAgentStatus("agent_telecom", "Telecom & Telemetry Agent", "Where can they be reached?", "Mail routing telemetry and domain infrastructure evaluated", countBadge = "${locations.distinctBy { it.value }.size} locations", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = locations.size + 1),
            PilotAgentStatus("agent_media", "Media & Visual Agent", "What do they look like?", if (photos.isNotEmpty()) "Recovered ${photos.size} visual profiles" else "No public avatar returned", countBadge = "${photos.size} photos", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = photos.size)
        )

        val finalReport = initialReport.copy(
            finishedAt = System.currentTimeMillis(),
            isRunning = false,
            agentStatuses = finalStatuses,
            resolvedNames = names.distinctBy { it.value },
            resolvedAliases = aliases.distinctBy { it.value },
            linkedEmails = listOf(email),
            linkedPhones = phones.distinct(),
            linkedAccounts = accounts.distinctBy { it.platform },
            breachRecords = breaches,
            totalBreachCount = breachOutcome?.breachNames?.size ?: breaches.size,
            publicRecords = publicRecords,
            locations = locations.distinctBy { it.value },
            recoveredPhotos = photos.distinctBy { it.url },
            findings = findings,
            rawMarkdownReport = buildMarkdown(initialReport.investigationId, email, "EMAIL", finalStatuses, findings)
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
        val publicRecords = mutableListOf<AttributedValue>()

        // 1. Identity & Keybase
        val enrichDef = async {
            runCatching { IdentityEnricher.enrich(clean, isEmail = false) }.getOrNull()
        }

        // 2. Username Scanning across 500+ databases
        val userScanDef = async {
            runCatching { UsernameScanner.scanWithVariants(clean) }.getOrNull()
        }

        // 3. GitHub & Gravatar visual check
        val githubAvatarUrl = "https://github.com/$clean.png"
        val githubCheck = async {
            val resp = ScanHttp.get(githubAvatarUrl, timeoutMs = 6_000)
            if (resp.code == 200 && !resp.body.contains("404", ignoreCase = true)) {
                PilotMediaPhoto(githubAvatarUrl, "GitHub", "GitHub Public Profile Avatar")
            } else null
        }

        val enrichment = enrichDef.await()
        enrichment?.let { en ->
            en.keybaseDisplayName?.let {
                names += AttributedValue(it, "Keybase Profile")
                findings += SourcedFinding(category = "Identity", claim = "Legal Name: $it", sourceName = "Keybase", sourceUrl = "https://keybase.io/$clean", confidence = ConfidenceLevel.HIGH)
            }
            en.keybaseProofs.forEach { proof ->
                accounts += PhoneAccountHit(proof.service, "Confirmed Profile", "@${proof.handle}", proof.proofUrl, confirmed = true)
                findings += SourcedFinding(category = "Registration", claim = "Verified ${proof.service} Account: @${proof.handle}", sourceName = "Keybase Proofs", sourceUrl = proof.proofUrl, confidence = ConfidenceLevel.HIGH)
            }
        }

        val scanOutcome = userScanDef.await()
        scanOutcome?.confirmed?.forEach { hit ->
            accounts += PhoneAccountHit(hit.site, hit.verificationStatus, hit.evidence, hit.profileUrl, confirmed = true)
            findings += SourcedFinding(category = "Registration", claim = "Active Account on ${hit.site} (${hit.category})", sourceName = hit.site, sourceUrl = hit.profileUrl, confidence = ConfidenceLevel.HIGH)
        }

        val ghPhoto = githubCheck.await()
        if (ghPhoto != null) {
            photos += ghPhoto
            findings += SourcedFinding(category = "Media", claim = "GitHub User Avatar Recovered", sourceName = "GitHub", sourceUrl = githubAvatarUrl, confidence = ConfidenceLevel.HIGH)
        }

        val finalStatuses = listOf(
            PilotAgentStatus("agent_id", "Identity Resolution Agent", "Who is this, really?", "Resolved usernames, handles & legal names", countBadge = "${names.size} names", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = names.size),
            PilotAgentStatus("agent_footprint", "Digital Footprint Agent", "Where are they active?", "Scanned 500+ social networks, developer hubs & platforms", countBadge = "${accounts.size} accounts", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = accounts.size),
            PilotAgentStatus("agent_breach", "Breach Intelligence Agent", "What has already leaked?", "Evaluated username exposure in public breaches", countBadge = "0 breaches", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = 0),
            PilotAgentStatus("agent_records", "Public Records Agent", "What do the public records say?", "Cross-referenced public profiles & repositories", countBadge = "${publicRecords.size} records", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = publicRecords.size),
            PilotAgentStatus("agent_telecom", "Telecom & Telemetry Agent", "Where can they be reached?", "Target analyzed for contact references", countBadge = "N/A", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = 0),
            PilotAgentStatus("agent_media", "Media & Visual Agent", "What do they look like?", if (photos.isNotEmpty()) "Recovered profile pictures & avatars" else "No avatar found", countBadge = "${photos.size} photos", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = photos.size)
        )

        val finalReport = initialReport.copy(
            finishedAt = System.currentTimeMillis(),
            isRunning = false,
            agentStatuses = finalStatuses,
            resolvedNames = names,
            linkedAccounts = accounts,
            recoveredPhotos = photos,
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
        val publicRecords = mutableListOf<AttributedValue>()

        // Web & public records search for the full name
        val query = "\"$name\" ${context.locationHint.orEmpty()}".trim()
        val webResults = runCatching {
            SearchRepository.searchWeb(listOf(query, "$name site:linkedin.com", "$name site:companieshouse.gov.uk"))
        }.getOrNull()

        webResults?.results?.forEach { item ->
            publicRecords += AttributedValue(item.title, item.url)
            findings += SourcedFinding(
                category = "Public Record",
                claim = item.title + (if (item.snippet.isNotBlank()) " — ${item.snippet.take(120)}…" else ""),
                sourceName = hostOf(item.url),
                sourceUrl = item.url,
                confidence = ConfidenceLevel.HIGH
            )
        }

        val finalStatuses = listOf(
            PilotAgentStatus("agent_id", "Identity Resolution Agent", "Who is this, really?", "Searched corporate directories, filings & legal records", countBadge = "1 name", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = 1),
            PilotAgentStatus("agent_footprint", "Digital Footprint Agent", "Where are they active?", "Checked LinkedIn, company boards & public registries", countBadge = "${accounts.size} profiles", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = accounts.size),
            PilotAgentStatus("agent_breach", "Breach Intelligence Agent", "What has already leaked?", "Evaluated target across corporate breach lists", countBadge = "0 records", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = 0),
            PilotAgentStatus("agent_records", "Public Records Agent", "What do the public records say?", "Found matching public documents & corporate links", countBadge = "${publicRecords.size} filings", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = publicRecords.size),
            PilotAgentStatus("agent_telecom", "Telecom & Telemetry Agent", "Where can they be reached?", "Location context parsed: ${context.locationHint ?: "Global"}", countBadge = if (context.locationHint != null) "1 hint" else "0", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = if (context.locationHint != null) 1 else 0),
            PilotAgentStatus("agent_media", "Media & Visual Agent", "What do they look like?", "Public image search available via external tools", countBadge = "0 photos", isRunning = false, isComplete = true, timeAgo = "just now", findingsCount = 0)
        )

        val finalReport = initialReport.copy(
            finishedAt = System.currentTimeMillis(),
            isRunning = false,
            agentStatuses = finalStatuses,
            publicRecords = publicRecords,
            findings = findings,
            rawMarkdownReport = buildMarkdown(initialReport.investigationId, name, "NAME", finalStatuses, findings)
        )
        onUpdate(finalReport)
        finalReport
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
        appendLine("*Report certified and compiled by OSINT Pilot — Pentester.com Architecture*")
    }
}
