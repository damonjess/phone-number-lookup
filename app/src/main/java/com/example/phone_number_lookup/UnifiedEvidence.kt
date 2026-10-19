package com.example.phone_number_lookup

/** A scanner-neutral confidence band. It describes evidence quality, not identity certainty. */
enum class EvidenceConfidence { HIGH, MODERATE, LOW, UNVERIFIED, UNKNOWN }

enum class EvidenceKind {
    ACCOUNT, PROFILE, NAME, USERNAME, LOCATION, AVATAR, LINK,
    BREACH, ACTIVITY, PHONE_REPUTATION, DOMAIN, NETWORK
}

enum class EvidenceSourceState {
    RESULTS, NO_RESULTS, PARTIAL, BLOCKED, ERROR, SKIPPED, CHECKING, NOT_CHECKED, UNKNOWN
}

data class UnifiedEvidenceItem(
    val id: String,
    val kind: EvidenceKind,
    val title: String,
    val value: String,
    val source: String,
    val confidence: EvidenceConfidence = EvidenceConfidence.UNKNOWN,
    val detail: String = "",
    val url: String? = null,
    val observedAt: Long? = null,
)

data class UnifiedSourceStatus(
    val source: String,
    val state: EvidenceSourceState,
    val detail: String,
    val resultCount: Int? = null,
)

data class UnifiedScanEvidence(
    val target: String,
    val scanType: String,
    val startedAt: Long,
    val items: List<UnifiedEvidenceItem>,
    val sources: List<UnifiedSourceStatus>,
)

object UnifiedEvidenceBuilder {
    fun confidence(label: String?): EvidenceConfidence {
        val value = label.orEmpty().lowercase()
        return when {
            "high" in value -> EvidenceConfidence.HIGH
            "moderate" in value || "medium" in value -> EvidenceConfidence.MODERATE
            "low" in value -> EvidenceConfidence.LOW
            "unverified" in value || "upstream" in value || "possible" in value -> EvidenceConfidence.UNVERIFIED
            "none" in value || "uncertain" in value || value.isBlank() -> EvidenceConfidence.UNKNOWN
            else -> EvidenceConfidence.UNKNOWN
        }
    }

    fun username(
        target: String,
        startedAt: Long,
        hits: List<UsernameHit>,
        sitesChecked: Int,
        datasetSizes: Map<String, Int>,
        issues: List<SiteIssue>,
        discarded: Int,
        isScanning: Boolean,
        identity: IdentityEnrichmentReport?,
        isIdentityScanning: Boolean,
    ): UnifiedScanEvidence {
        val items = hits.map { hit ->
            item(
                kind = EvidenceKind.PROFILE,
                title = hit.site,
                value = hit.queriedName.ifBlank { target },
                source = hit.source,
                confidence = confidence(hit.confidence),
                detail = "${hit.verificationStatus} · ${hit.evidence}",
                url = hit.profileUrl,
                id = "profile|${hit.site}|${hit.profileUrl}",
            )
        }.toMutableList()
        identity?.keybaseDisplayName?.let { items += item(EvidenceKind.NAME, it, it, "Keybase", EvidenceConfidence.MODERATE, "Public identity metadata") }
        identity?.keybaseProofs.orEmpty().forEach { proof -> items += item(EvidenceKind.LINK, "${proof.service} proof", proof.handle, "Keybase", EvidenceConfidence.HIGH, "Published identity proof link", proof.proofUrl) }
        identity?.pgpRecords.orEmpty().forEach { pgp -> items += item(EvidenceKind.LINK, "OpenPGP key ${pgp.keyId}", pgp.uids.joinToString(", "), "OpenPGP", EvidenceConfidence.MODERATE, pgp.createdDate ?: "Public key record") }
        identity?.waybackSnapshots.orEmpty().forEach { wb -> items += item(EvidenceKind.ACTIVITY, "Archive snapshots", "${wb.snapshotCount} captures", "Internet Archive", EvidenceConfidence.MODERATE, "${wb.firstSeen ?: "?"} to ${wb.lastSeen ?: "?"}", wb.latestArchiveUrl) }
        val statuses = datasetSizes.map { (name, count) ->
            UnifiedSourceStatus(name, EvidenceSourceState.RESULTS, "$count site definitions loaded", count)
        }.toMutableList()
        statuses += UnifiedSourceStatus(
            "Live profile checks",
            when {
                isScanning -> EvidenceSourceState.CHECKING
                sitesChecked == 0 -> EvidenceSourceState.UNKNOWN
                issues.isNotEmpty() -> EvidenceSourceState.PARTIAL
                hits.isNotEmpty() -> EvidenceSourceState.RESULTS
                else -> EvidenceSourceState.NO_RESULTS
            },
            when {
                isScanning -> "Checking public profiles; positive hits are control-tested against a made-up username."
                sitesChecked == 0 -> "No site checks completed; this is not a negative result."
                else -> "$sitesChecked sites checked; ${hits.size} control-tested profile matches, ${issues.size} inconclusive, $discarded false-positive controls discarded."
            },
            hits.size,
        )
        if (issues.isNotEmpty()) statuses += UnifiedSourceStatus(
            "Retryable / inconclusive sites", EvidenceSourceState.PARTIAL,
            "${issues.size} sites need attention; retryable failures are available in the source issue list.", issues.size
        )
        statuses += UnifiedSourceStatus("Keybase / OpenPGP / archive enrichment", when { isIdentityScanning -> EvidenceSourceState.CHECKING; identity == null -> EvidenceSourceState.NOT_CHECKED; identity.hasData -> EvidenceSourceState.RESULTS; else -> EvidenceSourceState.UNKNOWN }, when { isIdentityScanning -> "Checking public proofs and archive metadata."; identity == null -> "Not checked."; identity.hasData -> "${identity.keybaseProofs.size} Keybase proof(s), ${identity.pgpRecords.size} key(s), ${identity.waybackSnapshots.size} archive summary/summaries."; else -> "No enrichment records returned; provider no-match responses and transport failures are not separately retained." })
        return UnifiedScanEvidence(target, "Username", startedAt, items, statuses)
    }

    fun email(
        target: String,
        startedAt: Long,
        registrations: List<RegistrationHit>,
        registrationChecked: Int,
        registrationTotal: Int,
        registrationIssues: List<SiteIssue>,
        registrationDiscarded: Int,
        isRegistrationScanning: Boolean,
        registrationFailed: Boolean,
        breach: BreachReport?,
        isBreachScanning: Boolean,
        domain: EmailDomainReport?,
        isDomainScanning: Boolean,
        attribution: EmailAttributionReport?,
        isAttributionScanning: Boolean,
        handleHits: List<UsernameHit>,
        handleSitesChecked: Int,
        isHandleScanning: Boolean,
        identity: IdentityEnrichmentReport?,
        isIdentityScanning: Boolean,
    ): UnifiedScanEvidence {
        val items = mutableListOf<UnifiedEvidenceItem>()
        registrations.forEach { hit ->
            items += item(
                EvidenceKind.ACCOUNT, hit.site, target, hit.source,
                confidence(hit.confidence), "${hit.verificationStatus} · ${hit.evidence}", hit.profileUrl,
                id = "account|${hit.site}|${hit.source}",
            )
        }
        attribution?.namesFound.orEmpty().forEach { items += item(EvidenceKind.NAME, it.value, it.value, it.source, EvidenceConfidence.MODERATE, "Public profile metadata") }
        attribution?.usernamesFound.orEmpty().forEach { items += item(EvidenceKind.USERNAME, it.value, it.value, it.source, EvidenceConfidence.MODERATE, "Public profile metadata") }
        attribution?.locationsFound.orEmpty().forEach { items += item(EvidenceKind.LOCATION, it.value, it.value, it.source, EvidenceConfidence.MODERATE, "Public source location") }
        attribution?.avatarUrls.orEmpty().forEach { url -> items += item(EvidenceKind.AVATAR, "Profile image", url, "Public profile metadata", EvidenceConfidence.MODERATE, "Image URL returned by a public profile source", url) }
        attribution?.profileLinks.orEmpty().forEach { link -> items += item(EvidenceKind.LINK, link.platform, link.handle, link.platform, EvidenceConfidence.MODERATE, "Public profile link", link.url) }
        identity?.keybaseDisplayName?.let { items += item(EvidenceKind.NAME, it, it, "Keybase", EvidenceConfidence.MODERATE, "Public identity metadata") }
        identity?.keybaseProofs.orEmpty().forEach { proof -> items += item(EvidenceKind.LINK, "${proof.service} proof", proof.handle, "Keybase", EvidenceConfidence.HIGH, "Published identity proof link", proof.proofUrl) }
        identity?.pgpRecords.orEmpty().forEach { pgp -> items += item(EvidenceKind.LINK, "OpenPGP key ${pgp.keyId}", pgp.uids.joinToString(", "), "OpenPGP", EvidenceConfidence.MODERATE, pgp.createdDate ?: "Public key record") }
        identity?.waybackSnapshots.orEmpty().forEach { wb -> items += item(EvidenceKind.ACTIVITY, "Archive snapshots", "${wb.snapshotCount} captures", "Internet Archive", EvidenceConfidence.MODERATE, "${wb.firstSeen ?: "?"} to ${wb.lastSeen ?: "?"}", wb.latestArchiveUrl) }
        handleHits.forEach { hit -> items += item(EvidenceKind.PROFILE, hit.site, hit.queriedName, hit.source, confidence(hit.confidence), "Handle match; it does not by itself prove this email owns the profile.", hit.profileUrl) }
        attribution?.timeline.orEmpty().forEach { event -> items += item(EvidenceKind.ACTIVITY, event.label, event.date, event.source, EvidenceConfidence.MODERATE, "Publicly reported activity/date") }
        breach?.breachNames.orEmpty().forEach { name -> items += item(EvidenceKind.BREACH, name, name, "Breach lookup", EvidenceConfidence.MODERATE, "Reported breach record; exposed fields are not inferred", observedAt = breach?.checkedAt) }
        domain?.let { report ->
            report.mxProvider?.let { items += item(EvidenceKind.DOMAIN, "Mail provider", it, "Email-domain DNS", EvidenceConfidence.HIGH, "Inferred from returned MX records") }
            if (report.spf != null) items += item(EvidenceKind.DOMAIN, "SPF", report.spf.toString(), "Email-domain DNS", EvidenceConfidence.HIGH, "DNS TXT check")
            if (report.dmarc != null) items += item(EvidenceKind.DOMAIN, "DMARC", report.dmarc.toString(), "Email-domain DNS", EvidenceConfidence.HIGH, "DNS TXT check")
        }
        val sources = mutableListOf<UnifiedSourceStatus>()
        sources += UnifiedSourceStatus(
            "Email registration checks",
            when { isRegistrationScanning -> EvidenceSourceState.CHECKING; registrationFailed -> EvidenceSourceState.ERROR; registrationTotal == 0 -> EvidenceSourceState.UNKNOWN; registrationIssues.isNotEmpty() -> EvidenceSourceState.PARTIAL; registrations.any { it.verificationStatus == "Registered response" } -> EvidenceSourceState.RESULTS; else -> EvidenceSourceState.NO_RESULTS },
            when { isRegistrationScanning -> "Checked $registrationChecked of $registrationTotal endpoints so far."; registrationFailed -> "The endpoint scan failed before it could complete."; registrationTotal == 0 -> "No endpoint checks completed; no conclusion can be drawn."; else -> "$registrationTotal endpoints checked; ${registrations.count { it.verificationStatus == "Registered response" }} independently control-checked; ${registrations.count { it.verificationStatus != "Registered response" }} uncertain/upstream; $registrationDiscarded control false positives discarded." },
            registrations.size,
        )
        if (registrationIssues.isNotEmpty()) sources += UnifiedSourceStatus("Email endpoints with issues", EvidenceSourceState.PARTIAL, "${registrationIssues.size} endpoint(s) need retry or manual review.", registrationIssues.size)
        sources += UnifiedSourceStatus("Breach lookup", when { isBreachScanning -> EvidenceSourceState.CHECKING; breach == null -> EvidenceSourceState.NOT_CHECKED; breach.error != null -> EvidenceSourceState.ERROR; breach.breached -> EvidenceSourceState.RESULTS; else -> EvidenceSourceState.NO_RESULTS }, when { isBreachScanning -> "Checking breach sources."; breach == null -> "Not checked."; breach.error != null -> breach.error; breach.breached -> "${breach.breachNames.size} record(s) returned."; else -> "No records returned by the queried sources; this is not proof that no breach exists." }, breach?.breachNames?.size)
        sources += UnifiedSourceStatus("Email domain signals", when { isDomainScanning -> EvidenceSourceState.CHECKING; domain == null -> EvidenceSourceState.NOT_CHECKED; domain.error != null -> EvidenceSourceState.PARTIAL; else -> EvidenceSourceState.RESULTS }, when { isDomainScanning -> "Checking mail/DNS signals."; domain == null -> "Not checked."; domain.error != null -> "Partial or unavailable: ${domain.error}"; else -> "Mail routing, authentication and reputation signals checked." })
        sources += UnifiedSourceStatus("Public identity attribution", when { isAttributionScanning -> EvidenceSourceState.CHECKING; attribution == null -> EvidenceSourceState.NOT_CHECKED; attribution.hasData -> EvidenceSourceState.RESULTS; else -> EvidenceSourceState.UNKNOWN }, when { isAttributionScanning -> "Checking public profile metadata."; attribution == null -> "Not checked."; attribution.hasData -> "${attribution.profileLinks.size} public links, ${attribution.namesFound.size} names, ${attribution.usernamesFound.size} usernames, ${attribution.locationsFound.size} locations."; else -> "No attribution record returned; provider no-match responses and transport failures are not separately retained." })
        sources += UnifiedSourceStatus("Email local-part username checks", when { isHandleScanning -> EvidenceSourceState.CHECKING; handleSitesChecked == 0 -> EvidenceSourceState.UNKNOWN; handleHits.isNotEmpty() -> EvidenceSourceState.RESULTS; else -> EvidenceSourceState.NO_RESULTS }, when { isHandleScanning -> "Checking the part before @ as a possible username."; handleSitesChecked == 0 -> "No username checks completed."; else -> "$handleSitesChecked sites checked; matches are handle correlations, not proof of email ownership." }, handleHits.size)
        sources += UnifiedSourceStatus("Keybase / OpenPGP identity enrichment", when { isIdentityScanning -> EvidenceSourceState.CHECKING; identity == null -> EvidenceSourceState.NOT_CHECKED; identity.hasData -> EvidenceSourceState.RESULTS; else -> EvidenceSourceState.UNKNOWN }, when { isIdentityScanning -> "Checking public proofs and key records."; identity == null -> "Not checked."; identity.hasData -> "${identity.keybaseProofs.size} Keybase proof(s), ${identity.pgpRecords.size} PGP key(s)."; else -> "No enrichment record returned; provider no-match responses and transport failures are not separately retained." })
        return UnifiedScanEvidence(target, "Email", startedAt, items.distinctBy { it.id }, sources)
    }

    fun phone(
        target: String,
        startedAt: Long,
        searchResults: List<SearchResultItem>,
        searchError: String?,
        isSearching: Boolean,
        spam: SpamReport?,
        isSpamScanning: Boolean,
        reputations: List<ReputationReport>,
        supportedReputationSources: List<String>,
        isReputationScanning: Boolean,
        phoneInfoga: PhoneInfogaReport?,
        isPhoneInfogaScanning: Boolean,
        numverify: PhoneInfogaNumverifyResult?,
    ): UnifiedScanEvidence {
        val items = mutableListOf<UnifiedEvidenceItem>()
        searchResults.forEach { result -> items += item(EvidenceKind.PHONE_REPUTATION, result.title, result.snippet, result.source, EvidenceConfidence.LOW, "Search-index result; confirm the page directly.", result.url) }
        spam?.let { report ->
            if (report.score != null || report.comments != null) items += item(EvidenceKind.PHONE_REPUTATION, "tellows score ${report.scoreLabel}", report.riskLabel, report.source, confidence(report.confidenceLabel), "${report.comments ?: 0} community comments", observedAt = report.checkedAt)
        }
        reputations.filter { it.hasReports }.forEach { rep -> items += item(EvidenceKind.PHONE_REPUTATION, rep.source, "${rep.reportCount} community report(s)", rep.source, EvidenceConfidence.LOW, rep.samples.joinToString("; "), rep.url) }
        numverify?.let { result ->
            if (result.error == null) {
                result.carrier?.let { items += item(EvidenceKind.PHONE_REPUTATION, "Carrier", it, "Numverify", EvidenceConfidence.MODERATE, "API response for number-format/carrier metadata") }
                result.location?.let { items += item(EvidenceKind.LOCATION, it, it, "Numverify", EvidenceConfidence.LOW, "Numbering metadata, not live caller location") }
            }
        }
        phoneInfoga?.scans.orEmpty().filter { it.status == PhoneInfogaStatus.OK }.forEach { scan ->
            scan.rows.forEach { (label, value) -> items += item(EvidenceKind.PHONE_REPUTATION, label, value, "PhoneInfoga · ${scan.scanner}", EvidenceConfidence.LOW, "Third-party scanner output") }
            scan.labeledLinks.forEach { (label, url) -> items += item(EvidenceKind.LINK, label, url, "PhoneInfoga · ${scan.scanner}", EvidenceConfidence.LOW, "Open the source to verify", url) }
            scan.links.filterNot { link -> scan.labeledLinks.any { it.second == link } }.forEach { url -> items += item(EvidenceKind.LINK, "PhoneInfoga link", url, "PhoneInfoga · ${scan.scanner}", EvidenceConfidence.LOW, "Open the source to verify", url) }
        }
        val sources = mutableListOf<UnifiedSourceStatus>()
        sources += UnifiedSourceStatus("Public web search", when { isSearching -> EvidenceSourceState.CHECKING; searchError?.contains("block", true) == true || searchError?.contains("bot check", true) == true -> EvidenceSourceState.BLOCKED; searchError != null -> EvidenceSourceState.ERROR; searchResults.isNotEmpty() -> EvidenceSourceState.RESULTS; else -> EvidenceSourceState.NO_RESULTS }, when { isSearching -> "Search in progress."; searchError != null -> "$searchError No negative conclusion is possible."; searchResults.isNotEmpty() -> "${searchResults.size} indexed results returned; each needs manual source review."; else -> "No indexed results returned for this query." }, searchResults.size)
        if (supportedReputationSources.isEmpty()) sources += UnifiedSourceStatus("Regional crowd-report sources", EvidenceSourceState.SKIPPED, "No additional no-key source currently supports this number's region; this is not a no-report result.")
        sources += UnifiedSourceStatus("tellows", when { isSpamScanning -> EvidenceSourceState.CHECKING; spam == null -> EvidenceSourceState.NOT_CHECKED; spam.error != null -> EvidenceSourceState.ERROR; spam.hasCommunityReports -> EvidenceSourceState.RESULTS; else -> EvidenceSourceState.NO_RESULTS }, when { isSpamScanning -> "Checking caller reports."; spam == null -> "Not checked."; spam.error != null -> spam.error; spam.hasCommunityReports -> "${spam.comments ?: 0} community comment(s); crowd reports are not identity proof."; else -> "No community reports; tellows default score is not evidence." }, spam?.comments)
        supportedReputationSources.forEach { source ->
            val rep = reputations.firstOrNull { it.source.equals(source, true) }
            sources += UnifiedSourceStatus(source, when { isReputationScanning -> EvidenceSourceState.CHECKING; rep == null -> EvidenceSourceState.UNKNOWN; rep.error != null -> EvidenceSourceState.ERROR; rep.hasReports -> EvidenceSourceState.RESULTS; else -> EvidenceSourceState.NO_RESULTS }, when { isReputationScanning -> "Checking."; rep == null -> "Source did not return a result; no conclusion."; rep.error != null -> rep.error; rep.hasReports -> "${rep.reportCount} crowd report(s)."; else -> "No reports on this source." }, rep?.reportCount)
        }
        sources += UnifiedSourceStatus("PhoneInfoga", when { isPhoneInfogaScanning -> EvidenceSourceState.CHECKING; phoneInfoga == null -> EvidenceSourceState.NOT_CHECKED; phoneInfoga.error != null -> EvidenceSourceState.ERROR; phoneInfoga.scans.any { it.status == PhoneInfogaStatus.ERROR } -> EvidenceSourceState.PARTIAL; phoneInfoga.scans.any { it.status == PhoneInfogaStatus.OK && (it.rows.isNotEmpty() || it.links.isNotEmpty() || it.labeledLinks.isNotEmpty()) } -> EvidenceSourceState.RESULTS; phoneInfoga.scans.any { it.status == PhoneInfogaStatus.OK } -> EvidenceSourceState.NO_RESULTS; else -> EvidenceSourceState.SKIPPED }, when { isPhoneInfogaScanning -> "Running scanners."; phoneInfoga == null -> "Optional scan not run."; phoneInfoga.error != null -> phoneInfoga.error; else -> "${phoneInfoga.scans.count { it.status == PhoneInfogaStatus.OK }} ran, ${phoneInfoga.scans.count { it.status == PhoneInfogaStatus.SKIPPED }} skipped, ${phoneInfoga.scans.count { it.status == PhoneInfogaStatus.ERROR }} failed; a completed scanner may legitimately return no rows." }, phoneInfoga?.scans?.count { it.status == PhoneInfogaStatus.OK })
        sources += UnifiedSourceStatus("Numverify", when { numverify == null -> EvidenceSourceState.NOT_CHECKED; numverify.error != null -> EvidenceSourceState.ERROR; numverify.valid != null -> EvidenceSourceState.RESULTS; else -> EvidenceSourceState.UNKNOWN }, when { numverify == null -> "Optional API lookup not run."; numverify.error != null -> numverify.error; numverify.valid != null -> "API answered; valid-number flag: ${numverify.valid}. Carrier/location metadata are numbering data, not live caller location."; else -> "No usable API response." })
        return UnifiedScanEvidence(target, "Phone", startedAt, items.distinctBy { it.id }, sources)
    }

    fun domain(target: String, startedAt: Long, report: DomainOsintReport?, isScanning: Boolean): UnifiedScanEvidence {
        val items = mutableListOf<UnifiedEvidenceItem>()
        report?.let { data ->
            data.aRecords.forEach { items += item(EvidenceKind.NETWORK, "A record", it, "Cloudflare DNS", EvidenceConfidence.HIGH, "DNS response") }
            data.aaaaRecords.forEach { items += item(EvidenceKind.NETWORK, "AAAA record", it, "Cloudflare DNS", EvidenceConfidence.HIGH, "DNS response") }
            data.mxRecords.forEach { items += item(EvidenceKind.DOMAIN, "MX record", it, "Cloudflare DNS", EvidenceConfidence.HIGH, "DNS response") }
            data.nsRecords.forEach { items += item(EvidenceKind.DOMAIN, "Nameserver", it, "Cloudflare DNS", EvidenceConfidence.HIGH, "DNS response") }
            data.txtRecords.take(12).forEach { record -> items += item(EvidenceKind.DOMAIN, if ("spf" in record.lowercase()) "SPF TXT" else if ("dmarc" in record.lowercase()) "DMARC TXT" else "TXT record", record, "Cloudflare DNS", EvidenceConfidence.HIGH, "DNS TXT response") }
            data.rdap?.let { rdap ->
                rdap.registrar?.let { items += item(EvidenceKind.DOMAIN, "Registrar", it, "RDAP", EvidenceConfidence.HIGH, "Domain registration data") }
                rdap.registeredDate?.let { items += item(EvidenceKind.ACTIVITY, "Domain registered", it, "RDAP", EvidenceConfidence.HIGH, "Registry event date") }
                rdap.expirationDate?.let { items += item(EvidenceKind.ACTIVITY, "Domain expires", it, "RDAP", EvidenceConfidence.HIGH, "Registry event date") }
                rdap.nameservers.forEach { items += item(EvidenceKind.DOMAIN, "RDAP nameserver", it, "RDAP", EvidenceConfidence.HIGH, "Domain registration data") }
            }
            data.subdomains.forEach { items += item(EvidenceKind.NETWORK, "Subdomain", it, "Certificate Transparency", EvidenceConfidence.MODERATE, "Certificate log entry") }
            data.shodanHost?.let { host ->
                host.ports.forEach { items += item(EvidenceKind.NETWORK, "Open port", it.toString(), "Shodan InternetDB", EvidenceConfidence.MODERATE, "Indexed host data") }
                host.vulns.forEach { items += item(EvidenceKind.NETWORK, "Vulnerability reference", it, "Shodan InternetDB", EvidenceConfidence.MODERATE, "Indexed vulnerability tag") }
                host.hostnames.forEach { items += item(EvidenceKind.NETWORK, "Observed hostname", it, "Shodan InternetDB", EvidenceConfidence.MODERATE, "Indexed host data") }
                host.cpes.forEach { items += item(EvidenceKind.NETWORK, "Service fingerprint", it, "Shodan InternetDB", EvidenceConfidence.MODERATE, "Indexed CPE") }
                host.tags.forEach { items += item(EvidenceKind.NETWORK, "Host tag", it, "Shodan InternetDB", EvidenceConfidence.MODERATE, "Indexed host tag") }
            }
            data.wayback?.let { wayback -> items += item(EvidenceKind.ACTIVITY, "Archive snapshots", "${wayback.snapshotCount} captures", "Internet Archive", EvidenceConfidence.MODERATE, "${wayback.firstSeen ?: "?"} to ${wayback.lastSeen ?: "?"}", wayback.latestArchiveUrl) }
        }
        val sources = if (report?.error != null) listOf(UnifiedSourceStatus("Domain and network checks", EvidenceSourceState.ERROR, report.error)) else listOf(
            UnifiedSourceStatus("DNS records", when { isScanning -> EvidenceSourceState.CHECKING; report == null -> EvidenceSourceState.NOT_CHECKED; report.aRecords.isNotEmpty() || report.aaaaRecords.isNotEmpty() || report.mxRecords.isNotEmpty() || report.nsRecords.isNotEmpty() -> EvidenceSourceState.RESULTS; else -> EvidenceSourceState.UNKNOWN }, when { isScanning -> "Querying DNS."; report == null -> "Not checked."; else -> "Empty DNS fields mean no answer was captured; this build does not yet separate authoritative no-answer responses from DNS transport errors." }),
            UnifiedSourceStatus("RDAP / registration", when { isScanning -> EvidenceSourceState.CHECKING; report == null -> EvidenceSourceState.NOT_CHECKED; report.rdap != null -> EvidenceSourceState.RESULTS; else -> EvidenceSourceState.UNKNOWN }, if (report?.rdap != null) "Registration data returned." else "No RDAP record captured; absence is not confirmed."),
            UnifiedSourceStatus("Certificate Transparency", when { isScanning -> EvidenceSourceState.CHECKING; report == null -> EvidenceSourceState.NOT_CHECKED; report.subdomains.isNotEmpty() -> EvidenceSourceState.RESULTS; else -> EvidenceSourceState.UNKNOWN }, if (report?.subdomains?.isNotEmpty() == true) "${report.subdomains.size} subdomain(s) returned." else "No names captured or the source was unavailable; these cases are not yet separated."),
            UnifiedSourceStatus("Shodan InternetDB", when { isScanning -> EvidenceSourceState.CHECKING; report == null -> EvidenceSourceState.NOT_CHECKED; report.shodanHost != null -> EvidenceSourceState.RESULTS; report.aRecords.isEmpty() -> EvidenceSourceState.SKIPPED; else -> EvidenceSourceState.UNKNOWN }, when { report?.aRecords?.isEmpty() == true -> "Skipped because no IPv4 address was captured."; report?.shodanHost != null -> "Host data returned."; else -> "No host data captured; not a verified clean result." }),
            UnifiedSourceStatus("Internet Archive", when { isScanning -> EvidenceSourceState.CHECKING; report == null -> EvidenceSourceState.NOT_CHECKED; report.wayback != null -> EvidenceSourceState.RESULTS; else -> EvidenceSourceState.UNKNOWN }, if (report?.wayback != null) "Archive summary returned." else "No summary captured or source unavailable."),
        )
        return UnifiedScanEvidence(target, "Domain / network", startedAt, items.distinctBy { it.id }, sources)
    }

    private fun item(
        kind: EvidenceKind,
        title: String,
        value: String,
        source: String,
        confidence: EvidenceConfidence,
        detail: String,
        url: String? = null,
        observedAt: Long? = null,
        id: String = "${kind.name}|$title|$source|$value",
    ) = UnifiedEvidenceItem(id.lowercase(), kind, title, value, source, confidence, detail, url, observedAt)
}
