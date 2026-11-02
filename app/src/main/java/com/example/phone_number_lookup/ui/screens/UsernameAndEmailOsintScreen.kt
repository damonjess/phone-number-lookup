package com.example.phone_number_lookup.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.phone_number_lookup.AttributedProfileLink
import com.example.phone_number_lookup.AttributedValue
import com.example.phone_number_lookup.EmailLinkedAccounts
import com.example.phone_number_lookup.EmailProfileSummaryBuilder
import com.example.phone_number_lookup.EmailRegistrationScanner
import com.example.phone_number_lookup.IdentityEnrichmentReport
import com.example.phone_number_lookup.OsintPdfExporter
import com.example.phone_number_lookup.OsintTools
import com.example.phone_number_lookup.PdfSection
import com.example.phone_number_lookup.PythonToolMode
import com.example.phone_number_lookup.UnifiedEvidenceBuilder
import com.example.phone_number_lookup.UsernameScanner
import com.example.phone_number_lookup.ui.components.AppButton
import com.example.phone_number_lookup.ui.components.DorkLauncherCard
import com.example.phone_number_lookup.ui.components.EmailActivityTimelineSection
import com.example.phone_number_lookup.ui.components.EmailBreachDetailsSection
import com.example.phone_number_lookup.ui.components.EmailIdentitySummarySection
import com.example.phone_number_lookup.ui.components.EmailIntelBaseReport
import com.example.phone_number_lookup.ui.components.EmailIntelDashboard
import com.example.phone_number_lookup.ui.components.EmailLinkedAccountsSection
import com.example.phone_number_lookup.ui.components.EmailProfileStatsRow
import com.example.phone_number_lookup.ui.components.EmailProfileSummaryCard
import com.example.phone_number_lookup.ui.components.EnrichedProfilesSection
import com.example.phone_number_lookup.ui.components.GoogleCommentsSection
import com.example.phone_number_lookup.ui.components.IssuesSection
import com.example.phone_number_lookup.ui.components.LoadingRow
import com.example.phone_number_lookup.ui.components.Notice
import com.example.phone_number_lookup.ui.components.NumberedLinksHubCard
import com.example.phone_number_lookup.ui.components.OpenWebMentionsSection
import com.example.phone_number_lookup.ui.components.OsintResultCard
import com.example.phone_number_lookup.ui.components.OsintToolSectionCard
import com.example.phone_number_lookup.ui.components.Pill
import com.example.phone_number_lookup.ui.components.ResultRow
import com.example.phone_number_lookup.ui.components.ScreenHeader
import com.example.phone_number_lookup.ui.components.SearchField
import com.example.phone_number_lookup.ui.components.SegmentedTabs
import com.example.phone_number_lookup.ui.components.Space
import com.example.phone_number_lookup.ui.components.SubHeading
import com.example.phone_number_lookup.ui.components.Tone
import com.example.phone_number_lookup.ui.components.UnifiedEvidenceCard
import com.example.phone_number_lookup.ui.components.UsernameProfileSummaryCard
import com.example.phone_number_lookup.ui.components.openUrl
import com.example.phone_number_lookup.ui.viewmodels.UsernameAndEmailOsintViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UsernameAndEmailOsintScreen(
    viewModel: UsernameAndEmailOsintViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.Start
    ) {
        ScreenHeader(
            eyebrow = "Profiles",
            title = "Follow the handle",
            subtitle = "Username, email and domain checks against public sources, run from this device."
        )

        SegmentedTabs(
            options = listOf("Username", "Email", "Domain & IP"),
            selectedIndex = uiState.subTabState,
            onSelect = { viewModel.onSubTabChanged(it) },
            modifier = Modifier.padding(bottom = Space.md),
        )

        if (uiState.subTabState == 0) {
            Text(
                text = "PYTHON ENGINE",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = Space.sm, bottom = 6.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = Space.md),
                horizontalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                PythonToolMode.entries.forEach { mode ->
                    FilterChip(
                        selected = uiState.pythonToolMode == mode,
                        onClick = { viewModel.onPythonToolModeChanged(mode) },
                        label = { Text(mode.label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
            SegmentedTabs(
                options = listOf(
                    "Quick ${UsernameScanner.QUICK_LIMIT}",
                    "Standard ${UsernameScanner.STANDARD_LIMIT}",
                    "Full sweep"
                ),
                selectedIndex = uiState.scanDepth,
                onSelect = { viewModel.onScanDepthChanged(it) },
                modifier = Modifier.padding(bottom = Space.md),
            )
        } else if (uiState.subTabState == 1) {
            Text(
                text = "EMAIL ENGINE · BLACKBIRD & MULTI-SOURCE RECON",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = Space.sm, bottom = Space.md)
            )
        }

        val inputLabel = when (uiState.subTabState) {
            0 -> "Enter Username (e.g. alex_smith)"
            1 -> "Enter Email (e.g. name@example.com)"
            else -> "Enter Domain (e.g. example.com)"
        }
        val inputPlaceholder = when (uiState.subTabState) {
            0 -> "e.g. alex_smith or john_doe"
            1 -> "e.g. name@example.com"
            else -> "e.g. github.com or company.co.uk"
        }
        val buttonLabel = when (uiState.subTabState) {
            0 -> "Run Live Username Scanner"
            1 -> "Run Live Breach & Email Check"
            else -> "Run Domain, Subdomain & Shodan Recon"
        }

        SearchField(
            value = uiState.targetInput,
            onValueChange = { viewModel.onTargetInputChanged(it) },
            label = inputLabel,
            placeholder = inputPlaceholder,
            onSearch = { viewModel.runScan() },
            trailing = {
                if (uiState.targetInput.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onTargetInputChanged("") }) {
                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear input")
                    }
                }
            },
        )

        Spacer(Modifier.height(Space.md))

        AppButton(
            text = buttonLabel,
            onClick = { viewModel.runScan() },
            modifier = Modifier.fillMaxWidth(),
        )

        if (uiState.subTabState == 1) {
            Spacer(Modifier.height(Space.md))
            Notice(
                "Email scans query public third-party services (Google, DuckDuckGo, Brave, Bing, Yahoo, Reddit, GitHub, XposedOrNot, HudsonRock, Cloudflare DoH, Gravatar, Keybase) directly over HTTPS.",
                tone = Tone.NEUTRAL
            )
        }

        Spacer(Modifier.height(Space.xl))

        uiState.activeTarget?.let { target ->
            when (uiState.subTabState) {
                0 -> {
                    UsernameProfileSummaryCard(
                        handle = target,
                        hits = uiState.liveHits,
                        sitesChecked = uiState.liveSitesChecked,
                        isLoading = uiState.isLiveScanning,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    UnifiedEvidenceCard(
                        report = UnifiedEvidenceBuilder.username(
                            target = target,
                            startedAt = uiState.scanStartedAt,
                            hits = uiState.liveHits,
                            sitesChecked = uiState.liveSitesChecked,
                            datasetSizes = uiState.datasetSizes,
                            issues = uiState.usernameIssues,
                            discarded = uiState.discardedCount,
                            isScanning = uiState.isLiveScanning,
                            identity = uiState.identityEnrichment,
                            isIdentityScanning = uiState.isEnrichingIdentity,
                        ),
                        onOpenUrl = { openUrl(context, it) },
                    )
                    Spacer(Modifier.height(16.dp))

                    if (uiState.enrichedProfiles.isNotEmpty()) {
                        EnrichedProfilesSection(
                            profiles = uiState.enrichedProfiles,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    if (uiState.openWebReport != null) {
                        OpenWebMentionsSection(
                            report = uiState.openWebReport,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    OsintToolSectionCard(
                        toolTitle = "Live Username Scan ($target)",
                        subtitle = if (uiState.liveSitesChecked > 0)
                            "Checked ${uiState.liveSitesChecked} sites across WhatsMyName, Sherlock and Tookie"
                        else "Real HTTP checks across the merged site databases",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        when {
                            uiState.isLiveScanning -> LoadingRow(
                                if (uiState.scanTotal > 0) "Scanned ${uiState.scanProgress} of ${uiState.scanTotal} sites…"
                                else "Downloading site databases…"
                            )
                            uiState.liveError != null -> Notice(uiState.liveError!!, tone = Tone.DANGER)
                            uiState.liveHits.isEmpty() -> Column {
                                Text(
                                    "No public profiles matched this username.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "Scanned ${uiState.liveSitesChecked} sites" +
                                        (if (uiState.unreachableCount > 0) ", ${uiState.unreachableCount} couldn't be checked" else "") +
                                        (if (uiState.discardedCount > 0) ", ${uiState.discardedCount} ignored as unreliable" else "") + ".",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    softWrap = true
                                )
                                IssuesSection(
                                    issues = uiState.usernameIssues,
                                    isRetrying = uiState.isRetryingIssues,
                                    onRetry = viewModel::retryBlockedUsernameSites,
                                    onOpen = { url -> openUrl(context, url) }
                                )
                            }
                            else -> {
                                Text(
                                    "${countLabel(uiState.liveHits.count { it.verificationStatus == "Matched" }, "matched profile", "matched profiles")} · ${countLabel(uiState.liveHits.size, "result", "results")}",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    softWrap = true,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                if (uiState.discardedCount > 0) {
                                    Text(
                                        "${countLabel(uiState.discardedCount, "site", "sites")} said \"found\" for a made-up username too, so they were ignored as unreliable.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        softWrap = true,
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    )
                                }
                                IssuesSection(
                                    issues = uiState.usernameIssues,
                                    isRetrying = uiState.isRetryingIssues,
                                    onRetry = viewModel::retryBlockedUsernameSites,
                                    onOpen = { url -> openUrl(context, url) }
                                )
                                uiState.liveHits.forEach { hit ->
                                    OsintResultCard(
                                        site = hit.site,
                                        source = hit.source,
                                        verificationStatus = hit.verificationStatus,
                                        confidence = hit.confidence,
                                        evidence = hit.evidence,
                                        isRechecking = uiState.recheckingSite == hit.site,
                                        onClick = { openUrl(context, hit.profileUrl) },
                                        onRecheck = { viewModel.recheckUsername(hit.site, hit.profileUrl) },
                                        modifier = Modifier.padding(vertical = 3.dp),
                                    )
                                }
                                val sourceCounts = uiState.liveHits.groupingBy { it.source }.eachCount()
                                if (sourceCounts.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "Found via: " + sourceCounts.entries.joinToString(", ") { "${it.key} ${it.value}" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        softWrap = true
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    IdentityEnrichmentSection(
                        report = uiState.identityEnrichment,
                        isLoading = uiState.isEnrichingIdentity,
                        onOpenUrl = { openUrl(context, it) }
                    )

                    val usernameLinks = buildList {
                        uiState.enrichedProfiles.forEach { add("Dossier: ${it.platform} (@${it.handle})" to it.profileUrl) }
                        uiState.liveHits.forEach { add("${it.site} (${it.source})" to it.profileUrl) }
                        uiState.identityEnrichment?.keybaseProofs?.forEach {
                            if (it.proofUrl.startsWith("http")) add("Keybase ${it.service}: ${it.handle}" to it.proofUrl)
                        }
                        uiState.identityEnrichment?.waybackSnapshots?.forEach { wb ->
                            wb.latestArchiveUrl?.let { url -> add("Wayback Archive: ${wb.targetUrl}" to url) }
                        }
                        uiState.openWebReport?.results?.forEach { add("Web Mention (${it.source}): ${it.title}" to it.url) }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    NumberedLinksHubCard(
                        title = "Matched Profile Links ($target)",
                        links = usernameLinks,
                        onExportPdf = {
                            OsintPdfExporter.exportReport(
                                context = context,
                                title = "Username OSINT Report",
                                target = target,
                                sections = buildList {
                                    if (uiState.enrichedProfiles.isNotEmpty()) {
                                        add(
                                            PdfSection(
                                                "Deep Profile Dossiers (${uiState.enrichedProfiles.size})",
                                                uiState.enrichedProfiles.map { c ->
                                                    "${c.platform} (@${c.handle}): ${listOfNotNull(c.displayName, c.location, c.statsSummary, c.bio).joinToString(" • ")} (${c.profileUrl})"
                                                }
                                            )
                                        )
                                    }
                                    add(
                                        PdfSection(
                                            "Confirmed Accounts (${uiState.liveHits.size})",
                                            uiState.liveHits.mapIndexed { i, hit ->
                                                "${i + 1}. ${hit.site} [${hit.category}] - ${hit.profileUrl} (${hit.evidence})"
                                            }
                                        )
                                    )
                                    add(
                                        PdfSection(
                                            "Keybase & OpenPGP Proofs",
                                            buildList {
                                                uiState.identityEnrichment?.keybaseProofs?.forEach {
                                                    add("Keybase ${it.service}: ${it.handle} (${it.proofUrl})")
                                                }
                                                uiState.identityEnrichment?.pgpRecords?.forEach {
                                                    add("PGP Key ${it.keyId}: ${it.uids.joinToString(", ")}")
                                                }
                                            }
                                        )
                                    )
                                    add(
                                        PdfSection(
                                            "Wayback Machine Snapshots",
                                            uiState.identityEnrichment?.waybackSnapshots?.map {
                                                "${it.targetUrl}: ${it.snapshotCount} captures (${it.firstSeen}..${it.lastSeen}) -> ${it.latestArchiveUrl}"
                                            }.orEmpty()
                                        )
                                    )
                                }
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    DorkLauncherCard(
                        title = "Tookie-OSINT (brib.py) & IntelBase Launchers",
                        subtitle = "Broad alias footprinting across paste sites, forums & IntelBase",
                        dorks = OsintTools.getTookieBribDorks(target) + OsintTools.getIntelBaseDork(target)
                    )
                }

                1 -> {
                    val emailLoading = uiState.isRegistrationScanning || uiState.isBreachScanning ||
                        uiState.isEnrichingIdentity || uiState.isAttributionScanning || uiState.isGoogleMetadataScanning
                    val profileSummary = remember(
                        target,
                        uiState.attributionReport,
                        uiState.registeredSites,
                        uiState.liveHits,
                        uiState.breachReport,
                    ) {
                        EmailProfileSummaryBuilder.email(
                            email = target,
                            attribution = uiState.attributionReport,
                            registrations = uiState.registeredSites,
                            handleHits = uiState.liveHits,
                            breachReport = uiState.breachReport,
                        )
                    }
                    EmailProfileSummaryCard(
                        summary = profileSummary,
                        registrationCount = uiState.registeredSites.count { it.verificationStatus == "Registered response" },
                        breachCount = uiState.breachReport?.breachNames.orEmpty().distinct().size,
                        isLoading = emailLoading,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    EmailProfileStatsRow(
                        registrations = uiState.registeredSites,
                        handleHits = uiState.liveHits,
                        breachReport = uiState.breachReport,
                        attribution = uiState.attributionReport,
                        isLoading = emailLoading,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    EmailIdentitySummarySection(
                        attribution = uiState.attributionReport,
                        isLoading = emailLoading,
                        onOpenUrl = { openUrl(context, it) },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    EmailActivityTimelineSection(
                        attribution = uiState.attributionReport,
                        breachDetails = uiState.breachReport?.details.orEmpty(),
                        isLoading = emailLoading,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    val linkedAccounts = remember(target, uiState.registeredSites, uiState.liveHits, uiState.attributionReport) {
                        EmailLinkedAccounts.build(
                            email = target,
                            registrations = uiState.registeredSites,
                            handleHits = uiState.liveHits,
                            attribution = uiState.attributionReport,
                        )
                    }
                    EmailLinkedAccountsSection(
                        accounts = linkedAccounts,
                        email = target,
                        isLoading = emailLoading,
                        onOpenUrl = { openUrl(context, it) },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    EmailBreachDetailsSection(
                        details = uiState.breachReport?.details.orEmpty(),
                        onOpenUrl = { openUrl(context, it) },
                    )
                    if (uiState.breachReport?.details.orEmpty().isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    EmailIntelDashboard(
                        target = target,
                        attribution = uiState.attributionReport,
                        registrations = uiState.registeredSites,
                        handleHits = uiState.liveHits,
                        breachReport = uiState.breachReport,
                        isLoading = emailLoading,
                        onOpenUrl = { openUrl(context, it) },
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    GoogleCommentsSection(
                        report = uiState.googleCommentReport,
                        isLoading = uiState.isCommentScanning,
                        onOpenUrl = { openUrl(context, it) },
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    EmailIntelBaseReport(
                        target = target,
                        attribution = uiState.attributionReport,
                        registrations = uiState.registeredSites,
                        handleHits = uiState.liveHits,
                        breachReport = uiState.breachReport,
                        domainReport = uiState.domainReport,
                        googleMetadata = uiState.googleMetadata,
                        isLoading = emailLoading,
                        onOpenUrl = { openUrl(context, it) },
                        googleCommentReport = uiState.googleCommentReport,
                        isCommentScraping = uiState.isCommentScanning,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    UnifiedEvidenceCard(
                        report = UnifiedEvidenceBuilder.email(
                            target = target,
                            startedAt = uiState.scanStartedAt,
                            registrations = uiState.registeredSites,
                            registrationChecked = uiState.registrationChecked,
                            registrationTotal = uiState.registrationSitesTotal,
                            registrationIssues = uiState.registrationIssues,
                            registrationDiscarded = uiState.registrationDiscarded,
                            isRegistrationScanning = uiState.isRegistrationScanning,
                            registrationFailed = uiState.registrationFailed,
                            breach = uiState.breachReport,
                            isBreachScanning = uiState.isBreachScanning,
                            domain = uiState.domainReport,
                            isDomainScanning = uiState.isDomainScanning,
                            attribution = uiState.attributionReport,
                            isAttributionScanning = uiState.isAttributionScanning,
                            handleHits = uiState.liveHits,
                            handleSitesChecked = uiState.liveSitesChecked,
                            isHandleScanning = uiState.isLiveScanning,
                            identity = uiState.identityEnrichment,
                            isIdentityScanning = uiState.isEnrichingIdentity,
                        ),
                        onOpenUrl = { openUrl(context, it) },
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (uiState.enrichedProfiles.isNotEmpty()) {
                        EnrichedProfilesSection(
                            profiles = uiState.enrichedProfiles,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    if (uiState.openWebReport != null) {
                        OpenWebMentionsSection(
                            report = uiState.openWebReport,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    uiState.domainReport?.let { domain ->
                        val breachCount = uiState.breachReport?.breachNames?.size ?: 0
                        val regCount = uiState.registeredSites.size
                        val handleHitsCount = uiState.liveHits.size
                        val proofCount = (uiState.identityEnrichment?.keybaseProofs?.size ?: 0) +
                            (uiState.identityEnrichment?.pgpRecords?.size ?: 0)

                        OsintToolSectionCard(
                            toolTitle = "Digital Footprint Intelligence Summary",
                            subtitle = "Aggregated identity metrics and exposure overview for $target",
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            ResultRow("Domain accepts mail", if (domain.acceptsMail == true) "Yes — MX records found" else "Unverified")
                            ResultRow("Mail Provider (MX)", domain.mxProvider ?: "Standard MX Gateway")
                            ResultRow("Reported Registrations", "$regCount platform(s) reported via signup flow")
                            ResultRow("Handle Cross-Matches", "$handleHitsCount profile(s) found via username reuse")
                            ResultRow("Breach & Infostealer Logs", "$breachCount exposure record(s)")
                            ResultRow("Verified Cryptographic Proofs", "$proofCount Keybase/PGP proof(s)")
                            ResultRow("Domain Quality Score", "${domain.qualityScore ?: 50} / 100")

                            if (domain.disposable) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "⚠️ Warning: This email address is hosted on a disposable/burner domain blocklist.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    softWrap = true
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    if (uiState.isGoogleMetadataScanning) {
                        OsintToolSectionCard(
                            toolTitle = "Google Account Status",
                            subtitle = "Evaluating public Google account indicators for $target",
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Evaluating account status...", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    uiState.googleMetadata?.let { meta ->
                        OsintToolSectionCard(
                            toolTitle = "Google Account Status",
                            subtitle = "Public status check for $target",
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            ResultRow("Google Account Status", meta.accountStatus)
                            meta.fullName?.let { ResultRow("Full Name", it) }
                            meta.gaiaId?.let { ResultRow("Gaia ID", it) }
                            if (meta.activeServices.isNotEmpty()) {
                                ResultRow("Active Google Services", meta.activeServices.joinToString(", "))
                            }
                            if (meta.mappedLocations.isNotEmpty()) {
                                ResultRow("Mapped Locations", meta.mappedLocations.joinToString("\n"))
                            }
                            meta.playGamesSummary?.let {
                                ResultRow("Google Play Games", it)
                            }

                            if (meta.reviews.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Recent Google Maps Reviews",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                meta.reviews.forEach { rev ->
                                    val dateSuffix = if (rev.dateLabel.isNotBlank()) " • ${rev.dateLabel}" else ""
                                    Text(
                                        text = "• ${rev.title} (${rev.ratingLabel}$dateSuffix) - ${rev.address}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (rev.commentText.isNotBlank()) FontWeight.SemiBold else FontWeight.Normal,
                                        softWrap = true,
                                        modifier = Modifier.padding(vertical = 1.dp)
                                    )
                                    if (rev.commentText.isNotBlank()) {
                                        Text(
                                            text = "  \"${rev.commentText}\"",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            softWrap = true,
                                            modifier = Modifier.padding(start = 12.dp, bottom = 3.dp)
                                        )
                                    }
                                }
                            }

                            meta.profileUrl?.let { url ->
                                Spacer(modifier = Modifier.height(6.dp))
                                TextButton(onClick = { openUrl(context, url) }) {
                                    Text("Open Google Maps Contributor Profile ↗")
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    uiState.attributionReport?.let { attr ->
                        OsintToolSectionCard(
                            toolTitle = "Summary & Attribution ($target)",
                            subtitle = buildString {
                                append("Cross-platform identity attribution")
                                attr.firstSeen?.let { append(" • First seen: $it") }
                                attr.lastSeen?.let { append(" • Last seen: $it") }
                            },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            val confirmedRegistered = uiState.registeredSites.filter { it.verificationStatus == "Registered response" }
                            val allNames = attr.namesFound
                            val allUsernames = buildList {
                                addAll(attr.usernamesFound)
                                uiState.liveHits.forEach { hit ->
                                    add(AttributedValue(hit.queriedName.ifBlank { target.substringBefore('@') }, hit.site))
                                }
                            }.distinctBy { "${it.value.lowercase()}|${it.source.lowercase()}" }

                            val combinedLinks = buildList {
                                addAll(attr.profileLinks)
                                uiState.liveHits.forEach { hit ->
                                    add(
                                        AttributedProfileLink(
                                            handle = hit.queriedName.ifBlank { target.substringBefore('@') },
                                            platform = hit.site,
                                            url = hit.profileUrl
                                        )
                                    )
                                }
                            }.distinctBy { it.url.lowercase() }

                            val hasAnyAttributionData = confirmedRegistered.isNotEmpty() ||
                                allNames.isNotEmpty() ||
                                allUsernames.isNotEmpty() ||
                                attr.phonesFound.isNotEmpty() ||
                                attr.locationsFound.isNotEmpty() ||
                                combinedLinks.isNotEmpty() ||
                                attr.timeline.isNotEmpty() ||
                                attr.stealerProfile != null

                            if (!hasAnyAttributionData) {
                                Notice(
                                    "No public identity attribution records, social handles, or verified account registrations were found for $target.",
                                    tone = Tone.NEUTRAL
                                )
                            } else {
                                if (confirmedRegistered.isNotEmpty()) {
                                    SubHeading("Confirmed Accounts & Services")
                                    confirmedRegistered.forEach { reg ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "• ${reg.site} (${reg.category.replaceFirstChar { it.uppercase() }})",
                                                style = MaterialTheme.typography.bodySmall,
                                                softWrap = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (reg.profileUrl.isNotBlank() && reg.profileUrl.startsWith("http")) {
                                                TextButton(onClick = { openUrl(context, reg.profileUrl) }) {
                                                    Text("Open ↗")
                                                }
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                if (allNames.isNotEmpty()) {
                                    SubHeading("Names found")
                                    allNames.forEach { n ->
                                        Text(
                                            text = "• ${n.value} (${n.source})",
                                            style = MaterialTheme.typography.bodySmall,
                                            softWrap = true
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                if (allUsernames.isNotEmpty()) {
                                    SubHeading("Usernames")
                                    allUsernames.forEach { u ->
                                        Text(
                                            text = "• ${u.value} (${u.source})",
                                            style = MaterialTheme.typography.bodySmall,
                                            softWrap = true
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                if (attr.phonesFound.isNotEmpty()) {
                                    SubHeading("Phone numbers")
                                    attr.phonesFound.forEach { p ->
                                        Text(
                                            text = "• ${p.value} (${p.source})",
                                            style = MaterialTheme.typography.bodySmall,
                                            softWrap = true
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                if (attr.locationsFound.isNotEmpty()) {
                                    SubHeading("Locations")
                                    attr.locationsFound.forEach { loc ->
                                        Text(
                                            text = "• ${loc.value} (${loc.source})",
                                            style = MaterialTheme.typography.bodySmall,
                                            softWrap = true
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                if (combinedLinks.isNotEmpty()) {
                                    SubHeading("Profile links")
                                    combinedLinks.forEach { link ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "• ${link.handle} (${link.platform})",
                                                style = MaterialTheme.typography.bodySmall,
                                                softWrap = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(onClick = { openUrl(context, link.url) }) {
                                                Text("Open ↗")
                                            }
                                        }
                                    }
                                }

                                if (attr.timeline.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    SubHeading("Activity timeline")
                                    attr.timeline.forEach { ev ->
                                        Text(
                                            text = "• ${ev.date} — ${ev.label} · ${ev.source}",
                                            style = MaterialTheme.typography.bodySmall,
                                            softWrap = true
                                        )
                                    }
                                }

                                attr.stealerProfile?.let { sp ->
                                    SubHeading("Infostealer profile · HudsonRock")
                                    val usersLabel = sp.allUsernames.ifEmpty { listOfNotNull(sp.computerUsername) }.joinToString(", ")
                                    val ipsLabel = sp.allMaskedIps.ifEmpty { listOfNotNull(sp.maskedIp) }.joinToString(", ")
                                    val datesLabel = sp.allDates.ifEmpty { listOfNotNull(sp.dateCompromised) }.joinToString(", ")
                                    if (usersLabel.isNotBlank()) ResultRow("Username / Host", usersLabel)
                                    if (ipsLabel.isNotBlank()) ResultRow("IP Address(es)", ipsLabel)
                                    sp.operatingSystem?.let { ResultRow("Operating System", it) }
                                    if (datesLabel.isNotBlank()) ResultRow("Compromise Date(s)", datesLabel)
                                    if (sp.antiviruses.isNotEmpty()) ResultRow("Antivirus", sp.antiviruses.joinToString(", "))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    OsintToolSectionCard(
                        toolTitle = "Accounts Registered ($target)",
                        subtitle = "Signup endpoints queried directly for this address",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        when {
                            uiState.isRegistrationScanning -> LoadingRow(
                                "Checking signup endpoints · ${uiState.registrationChecked} done"
                            )
                            uiState.registrationFailed -> Notice(
                                uiState.registrationNotice ?: "The account check could not run. Try again.",
                                tone = Tone.DANGER,
                            )
                            uiState.registeredSites.isEmpty() -> Notice(
                                "No service reported an existing account for this address.",
                                tone = Tone.NEUTRAL,
                            )
                            else -> {
                                Text(
                                    "${countLabel(uiState.registeredSites.count { it.verificationStatus == "Registered response" }, "reported registration", "reported registrations")} · ${countLabel(uiState.registeredSites.size, "result", "results")}",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    softWrap = true,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                uiState.registeredSites.forEach { hit ->
                                    OsintResultCard(
                                        site = hit.site,
                                        source = hit.source,
                                        verificationStatus = hit.verificationStatus,
                                        confidence = hit.confidence,
                                        evidence = hit.evidence,
                                        isRechecking = uiState.recheckingSite == hit.site,
                                        onClick = { openUrl(context, hit.profileUrl) },
                                        onRecheck = { viewModel.recheckEmail(hit.site) },
                                        modifier = Modifier.padding(vertical = 3.dp),
                                    )
                                }
                            }
                        }
                        if (!uiState.isRegistrationScanning && !uiState.registrationFailed && uiState.registrationNotice != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                uiState.registrationNotice!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                softWrap = true
                            )
                        }
                        if (uiState.registrationDiscarded > 0 && !uiState.isRegistrationScanning) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "${countLabel(uiState.registrationDiscarded, "site", "sites")} also said an account exists for a made-up address, so they were ignored as unreliable.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                softWrap = true
                            )
                        }
                        IssuesSection(
                            issues = uiState.registrationIssues,
                            isRetrying = uiState.isRetryingRegistrationIssues,
                            onRetry = viewModel::retryEmailIssues,
                            onOpen = { url -> openUrl(context, url) },
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OsintToolSectionCard(
                        toolTitle = "Email Domain Intelligence",
                        subtitle = "DNS, mail authentication and a local reputation check",
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        when {
                            uiState.isDomainScanning -> LoadingRow("Checking MX, SPF, DMARC & Gravatar records…")
                            uiState.domainReport?.error != null -> Notice(
                                uiState.domainReport!!.error!!,
                                tone = Tone.DANGER,
                            )
                            uiState.domainReport != null -> {
                                val domain = uiState.domainReport!!
                                ResultRow("Domain", domain.domain)
                                ResultRow("Accepts mail", domain.acceptsMail?.toString() ?: "Unknown")
                                ResultRow("MX provider", domain.mxProvider ?: "Not returned")
                                ResultRow("SPF", domain.spf?.toString() ?: "Unknown")
                                ResultRow("DMARC", domain.dmarc?.toString() ?: "Unknown")
                                ResultRow("Free provider", domain.freeProvider.toString())
                                ResultRow("Disposable-like", domain.disposable.toString())
                                ResultRow("Role mailbox", domain.roleAddress.toString())
                                domain.qualityScore?.let { ResultRow("Local quality assessment", "$it / 100") }
                                if (domain.reasons.isNotEmpty()) {
                                    SubHeading("Why")
                                    domain.reasons.forEach { reason ->
                                        Text(
                                            "• $reason",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            softWrap = true,
                                            modifier = Modifier.padding(vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OsintToolSectionCard(
                        toolTitle = "Live Breach & Infostealer Check ($target)",
                        subtitle = "Real breach lookup via XposedOrNot, HudsonRock, COMB & Pastebin",
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ) {
                        val report = uiState.breachReport
                        when {
                            uiState.isBreachScanning -> Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Checking breach & infostealer databases...", style = MaterialTheme.typography.bodyMedium)
                            }
                            report == null -> Text(
                                "Run a scan to check this email against known data breaches.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            report.error != null -> Text(report.error, style = MaterialTheme.typography.bodyMedium, softWrap = true)
                            report.breached -> {
                                Text(
                                    "Exposed in ${report.breachNames.size} record(s):",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                report.breachNames.forEach { name ->
                                    Text(
                                        text = "• $name",
                                        style = MaterialTheme.typography.bodySmall,
                                        softWrap = true,
                                        modifier = Modifier.padding(vertical = 1.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                SubHeading("Evidence timeline")
                                Text(
                                    "${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(report.checkedAt))} — XposedOrNot & HudsonRock lookup completed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    softWrap = true
                                )
                            }
                            else -> Text(
                                "No breaches or infostealer logs found in XposedOrNot or HudsonRock.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    IdentityEnrichmentSection(
                        report = uiState.identityEnrichment,
                        isLoading = uiState.isEnrichingIdentity,
                        onOpenUrl = { openUrl(context, it) }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OsintToolSectionCard(
                        toolTitle = "Handle Footprint",
                        subtitle = "The part before the @ scanned as a username across every site database",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        val handle = EmailRegistrationScanner.localPart(target)
                        when {
                            handle == null -> Text("Could not derive a handle from this address.", style = MaterialTheme.typography.bodyMedium)
                            uiState.isLiveScanning -> LoadingRow("Scanning sites for \"$handle\"…")
                            uiState.liveHits.isEmpty() -> Notice(
                                "No public profile matched the handle \"$handle\". Scanned ${uiState.liveSitesChecked} sites.",
                                tone = Tone.NEUTRAL,
                            )
                            else -> {
                                val cleanHandleHits = uiState.liveHits.filterNot {
                                    it.site.contains("bongacams", ignoreCase = true) ||
                                        it.profileUrl.contains("bongacams", ignoreCase = true) ||
                                        it.category.equals("nsfw", ignoreCase = true)
                                }
                                Text(
                                    "${countLabel(cleanHandleHits.size, "profile", "profiles")} found for \"$handle\"",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                uiState.correlationHints.forEach { hint ->
                                    Text(
                                        "• $hint",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        softWrap = true,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                                cleanHandleHits.forEach { hit ->
                                    val cleanSiteLabel = hit.site
                                        .removePrefix("https://")
                                        .removePrefix("http://")
                                        .removePrefix("www.")
                                        .substringBefore('/')
                                        .ifBlank { hit.site }

                                    Surface(
                                        onClick = { openUrl(context, hit.profileUrl) },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = cleanSiteLabel,
                                                style = MaterialTheme.typography.titleSmall,
                                                softWrap = true,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Spacer(Modifier.width(Space.sm))
                                            Pill(hit.source, tone = Tone.INFO)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val emailLinks = buildList {
                        uiState.googleCommentReport?.comments?.forEach { hit ->
                            add("Comment (${hit.platform}): ${hit.title}" to hit.url)
                        }
                        uiState.enrichedProfiles.forEach { add("Dossier: ${it.platform} (@${it.handle})" to it.profileUrl) }
                        uiState.registeredSites.forEach { add("Registered: ${it.site}" to it.profileUrl) }
                        uiState.liveHits.forEach { add("Handle Match: ${it.site}" to it.profileUrl) }
                        uiState.openWebReport?.results?.forEach { add("Web Mention (${it.source}): ${it.title}" to it.url) }
                        OsintTools.getBlackbirdEmailScan(target).forEach { add(it.title to it.url) }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    NumberedLinksHubCard(
                        title = "Email & Handle Links Queue ($target)",
                        links = emailLinks,
                        onExportPdf = {
                            OsintPdfExporter.exportReport(
                                context = context,
                                title = "Email OSINT Report",
                                target = target,
                                sections = buildList {
                                    uiState.attributionReport?.let { attr ->
                                        add(
                                            PdfSection(
                                                heading = "Attributed Names, Usernames, Phones & Locations",
                                                lines = attr.namesFound.map { "Name (${it.source}): ${it.value}" } +
                                                    attr.usernamesFound.map { "Username (${it.source}): ${it.value}" } +
                                                    attr.phonesFound.map { "Phone (${it.source}): ${it.value}" } +
                                                    attr.locationsFound.map { "Location (${it.source}): ${it.value}" }
                                            )
                                        )
                                    }
                                    if (uiState.enrichedProfiles.isNotEmpty()) {
                                        add(
                                            PdfSection(
                                                heading = "Deep Profile Dossiers (${uiState.enrichedProfiles.size})",
                                                lines = uiState.enrichedProfiles.map { c ->
                                                    "${c.platform} (@${c.handle}): ${listOfNotNull(c.displayName, c.location, c.statsSummary, c.bio).joinToString(" • ")} (${c.profileUrl})"
                                                }
                                            )
                                        )
                                    }
                                    uiState.googleCommentReport?.let { commentRep ->
                                        if (commentRep.comments.isNotEmpty()) {
                                            add(
                                                PdfSection(
                                                    heading = "Scraped Google & Public Web Comments (${commentRep.comments.size})",
                                                    lines = commentRep.comments.flatMap { c ->
                                                        listOf(
                                                            "[${c.platform}] ${c.title} (Matched: ${c.authorMatch}${c.date?.let { " • $it" }.orEmpty()})",
                                                            "  Comment: ${c.commentSnippet}",
                                                            "  URL: ${c.url}"
                                                        )
                                                    }
                                                )
                                            )
                                        }
                                    }
                                    add(
                                        PdfSection(
                                            "Registered Accounts (${uiState.registeredSites.size})",
                                            uiState.registeredSites.mapIndexed { i, hit ->
                                                "${i + 1}. ${hit.site} (${hit.source}) - ${hit.profileUrl}"
                                            }
                                        )
                                    )
                                    add(
                                        PdfSection(
                                            "Breach & Infostealer Exposure",
                                            uiState.breachReport?.breachNames?.ifEmpty { listOf("No breaches reported") }
                                                ?: listOf("Not checked")
                                        )
                                    )
                                    add(
                                        PdfSection(
                                            "Domain & Mail Gateway Intelligence",
                                            uiState.domainReport?.reasons.orEmpty()
                                        )
                                    )
                                    add(
                                        PdfSection(
                                            "Handle Profile Matches (${uiState.liveHits.size})",
                                            uiState.liveHits.map { "${it.site}: ${it.profileUrl}" }
                                        )
                                    )
                                }
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    DorkLauncherCard(
                        title = "Blackbird, Epieos & IntelBase Email Tools",
                        subtitle = "External breach dorks, Epieos reverse lookup & IntelBase portal",
                        dorks = OsintTools.getBlackbirdEmailScan(target)
                    )
                }

                else -> {
                    val net = uiState.networkOsintReport
                    UnifiedEvidenceCard(
                        report = UnifiedEvidenceBuilder.domain(
                            target = target,
                            startedAt = uiState.scanStartedAt,
                            report = net,
                            isScanning = uiState.isNetworkScanning,
                        ),
                        onOpenUrl = { openUrl(context, it) },
                    )
                    Spacer(Modifier.height(16.dp))
                    OsintToolSectionCard(
                        toolTitle = "Domain & Network Recon ($target)",
                        subtitle = "DNS, ownership records, subdomains and exposed services",
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        when {
                            uiState.isNetworkScanning -> Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Querying DNS, RDAP, crt.sh & Shodan InternetDB...", style = MaterialTheme.typography.bodyMedium)
                            }
                            net?.error != null -> Text(
                                net.error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                softWrap = true
                            )
                            net != null -> {
                                ResultRow("Domain", net.domain)
                                if (net.aRecords.isNotEmpty()) ResultRow("IPv4 (A)", net.aRecords.joinToString(", "))
                                if (net.aaaaRecords.isNotEmpty()) ResultRow("IPv6 (AAAA)", net.aaaaRecords.joinToString(", "))
                                if (net.mxRecords.isNotEmpty()) ResultRow("MX Records", net.mxRecords.joinToString(", "))
                                if (net.nsRecords.isNotEmpty()) ResultRow("Nameservers", net.nsRecords.joinToString(", "))

                                net.rdap?.let { rdap ->
                                    SubHeading("RDAP registration & ownership")
                                    rdap.registrar?.let { ResultRow("Registrar", it) }
                                    rdap.registeredDate?.let { ResultRow("Registered", it) }
                                    rdap.expirationDate?.let { ResultRow("Expires", it) }
                                    if (rdap.status.isNotEmpty()) ResultRow("Status", rdap.status.joinToString(", "))
                                }

                                net.shodanHost?.let { shodan ->
                                    SubHeading("Shodan InternetDB · ${shodan.ip}")
                                    ResultRow("Open Ports", if (shodan.ports.isNotEmpty()) shodan.ports.joinToString(", ") else "None reported")
                                    if (shodan.hostnames.isNotEmpty()) ResultRow("Hostnames", shodan.hostnames.joinToString(", "))
                                    if (shodan.tags.isNotEmpty()) ResultRow("Tags", shodan.tags.joinToString(", "))
                                    if (shodan.cpes.isNotEmpty()) ResultRow("Services (CPE)", shodan.cpes.take(6).joinToString(", "))
                                    if (shodan.vulns.isNotEmpty()) {
                                        ResultRow("Known CVEs", shodan.vulns.take(10).joinToString(", "))
                                    }
                                }

                                if (net.subdomains.isNotEmpty()) {
                                    SubHeading("Certificate transparency · ${net.subdomains.size} subdomains")
                                    net.subdomains.take(25).forEach { sub ->
                                        Text(
                                            sub,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            softWrap = true,
                                            modifier = Modifier.padding(vertical = 1.dp),
                                        )
                                    }
                                }

                                net.wayback?.let { wb ->
                                    SubHeading("Wayback machine")
                                    ResultRow("Snapshots", "${wb.snapshotCount}+ captures (${wb.firstSeen ?: "?"} to ${wb.lastSeen ?: "?"})")
                                    wb.latestArchiveUrl?.let { url ->
                                        TextButton(onClick = { openUrl(context, url) }) {
                                            Text("Open Latest Archive Snapshot ↗")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IdentityEnrichmentSection(
    report: IdentityEnrichmentReport?,
    isLoading: Boolean,
    onOpenUrl: (String) -> Unit
) {
    val context = LocalContext.current
    var licenseText by remember { mutableStateOf<String?>(null) }

    OsintToolSectionCard(
        toolTitle = "Cryptographic Proofs, PGP & Archive History",
        subtitle = "Keybase proofs, public PGP keys and archived captures",
        containerColor = MaterialTheme.colorScheme.secondaryContainer
    ) {
        when {
            isLoading -> LoadingRow("Querying Keybase, OpenPGP & Wayback Machine…")
            report == null || !report.hasData -> Notice(
                "No Keybase proofs, PGP keys or Wayback snapshots returned for this target.",
                tone = Tone.NEUTRAL,
            )
            else -> {
                report.keybaseDisplayName?.let { ResultRow("Keybase Name", it) }
                report.keybaseBio?.let { ResultRow("Keybase Bio", it) }
                if (report.keybaseProofs.isNotEmpty()) {
                    SubHeading("Verified Keybase accounts")
                    report.keybaseProofs.forEach { proof ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "• ${proof.service}: ${proof.handle}",
                                style = MaterialTheme.typography.bodySmall,
                                softWrap = true,
                                modifier = Modifier.weight(1f)
                            )
                            if (proof.proofUrl.startsWith("http")) {
                                TextButton(onClick = { onOpenUrl(proof.proofUrl) }) { Text("Open") }
                            }
                        }
                    }
                }
                if (report.pgpRecords.isNotEmpty()) {
                    SubHeading("OpenPGP public key UIDs")
                    report.pgpRecords.forEach { pgp ->
                        Text(
                            "Key ${pgp.keyId}" + (pgp.createdDate?.let { " (created $it)" } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            softWrap = true
                        )
                        pgp.uids.forEach { uid ->
                            Text("• $uid", style = MaterialTheme.typography.bodySmall, softWrap = true)
                        }
                    }
                }
                if (report.waybackSnapshots.isNotEmpty()) {
                    SubHeading("Wayback archived profiles")
                    report.waybackSnapshots.forEach { wb ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "• ${wb.targetUrl} (${wb.snapshotCount} captures, ${wb.firstSeen}..${wb.lastSeen})",
                                style = MaterialTheme.typography.bodySmall,
                                softWrap = true,
                                modifier = Modifier.weight(1f)
                            )
                            wb.latestArchiveUrl?.let { url ->
                                TextButton(onClick = { onOpenUrl(url) }) { Text("Archive") }
                            }
                        }
                    }
                }
            }
        }
        TextButton(onClick = {
            licenseText = context.assets.open("licenses/GPL-3.0.txt").bufferedReader().use { it.readText() }
        }) {
            Text("Open-source licenses")
        }
        licenseText?.let { text ->
            AlertDialog(
                onDismissRequest = { licenseText = null },
                title = { Text("Open-source licenses") },
                text = {
                    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                        Text("This app includes upstream Sherlock (MIT), Blackbird (GPLv3), Tookie-OSINT (MIT), and WhatsMyName data (CC BY-SA 4.0). Full third-party notices are included with the source repository.")
                        Spacer(Modifier.height(8.dp))
                        Text("Blackbird is provided without warranty. You may obtain, modify, and redistribute the corresponding source under GPLv3.")
                        Spacer(Modifier.height(8.dp))
                        Text(text)
                    }
                },
                confirmButton = { TextButton(onClick = { licenseText = null }) { Text("Close") } }
            )
        }
    }
}

private fun countLabel(count: Int, singular: String, plural: String): String =
    if (count == 1) "1 $singular" else "$count $plural"
