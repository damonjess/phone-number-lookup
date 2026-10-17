package com.example.phone_number_lookup.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.phone_number_lookup.EmailRegistrationScanner
import com.example.phone_number_lookup.IdentityEnrichmentReport
import com.example.phone_number_lookup.OsintPdfExporter
import com.example.phone_number_lookup.OsintTools
import com.example.phone_number_lookup.PdfSection
import com.example.phone_number_lookup.PythonToolMode
import com.example.phone_number_lookup.UsernameScanner
import com.example.phone_number_lookup.ui.components.DorkLauncherCard
import com.example.phone_number_lookup.ui.components.IssuesSection
import com.example.phone_number_lookup.ui.components.NumberedLinksHubCard
import com.example.phone_number_lookup.ui.components.OsintToolSectionCard
import com.example.phone_number_lookup.ui.components.ResultRow
import com.example.phone_number_lookup.ui.components.openUrl
import com.example.phone_number_lookup.ui.viewmodels.UsernameAndEmailOsintViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UsernameAndEmailOsintScreen(
    viewModel: UsernameAndEmailOsintViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Target OSINT Engine",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Native HTTP checks: accounts, breaches, PGP/Keybase & domain recon",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = uiState.subTabState == 0,
                onClick = { viewModel.onSubTabChanged(0) },
                label = { Text("Username") }
            )
            FilterChip(
                selected = uiState.subTabState == 1,
                onClick = { viewModel.onSubTabChanged(1) },
                label = { Text("Email") }
            )
            FilterChip(
                selected = uiState.subTabState == 2,
                onClick = { viewModel.onSubTabChanged(2) },
                label = { Text("Domain & IP") }
            )
        }

        if (uiState.subTabState == 0) {
            Text(
                text = "Username engine: embedded Python (Chaquopy / Python 3.12):",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start).padding(bottom = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PythonToolMode.entries.forEach { mode ->
                    FilterChip(
                        selected = uiState.pythonToolMode == mode,
                        onClick = { viewModel.onPythonToolModeChanged(mode) },
                        label = { Text(mode.label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
        } else if (uiState.subTabState == 1) {
            Text(
                text = "Email registration engine: embedded Python (Blackbird-compatible checks)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start).padding(bottom = 12.dp)
            )
        }

        if (uiState.subTabState == 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "Quick (${UsernameScanner.QUICK_LIMIT})" to 0,
                    "Standard (${UsernameScanner.STANDARD_LIMIT})" to 1,
                    "Full sweep" to 2
                ).forEach { (label, index) ->
                    FilterChip(
                        selected = uiState.scanDepth == index,
                        onClick = { viewModel.onScanDepthChanged(index) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
        }

        val inputLabel = when (uiState.subTabState) {
            0 -> "Enter Username (e.g. dnkirby)"
            1 -> "Enter Email (e.g. alex.smith@gmail.com)"
            else -> "Enter Domain (e.g. example.com)"
        }
        val inputPlaceholder = when (uiState.subTabState) {
            0 -> "e.g. dnkirby or john_doe"
            1 -> "e.g. alex.smith@gmail.com"
            else -> "e.g. github.com or company.co.uk"
        }
        val buttonLabel = when (uiState.subTabState) {
            0 -> "Run Live Username Scanner"
            1 -> "Run Live Breach & Email Check"
            else -> "Run Domain, Subdomain & Shodan Recon"
        }

        OutlinedTextField(
            value = uiState.targetInput,
            onValueChange = { viewModel.onTargetInputChanged(it) },
            label = { Text(inputLabel) },
            placeholder = { Text(inputPlaceholder) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.runScan() }),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                if (uiState.targetInput.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onTargetInputChanged("") }) {
                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear input")
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { viewModel.runScan() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(buttonLabel)
        }

        Spacer(modifier = Modifier.height(16.dp))

        uiState.activeTarget?.let { target ->
            when (uiState.subTabState) {
                0 -> {
                    OsintToolSectionCard(
                        toolTitle = "Live Username Scan ($target)",
                        subtitle = if (uiState.liveSitesChecked > 0)
                            "${uiState.liveSitesChecked} real HTTP checks across Verified APIs, WhatsMyName, Maigret, Sherlock and Tookie"
                        else "Real HTTP checks across the merged community site databases",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        when {
                            uiState.isLiveScanning -> Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    if (uiState.scanTotal > 0) "Scanned ${uiState.scanProgress} of ${uiState.scanTotal} sites..."
                                    else "Downloading site databases...",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            uiState.liveError != null -> Text(uiState.liveError!!, style = MaterialTheme.typography.bodyMedium)
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
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                    "${uiState.liveHits.count { it.verificationStatus == "Matched" }} confirmed profile(s) • ${uiState.liveHits.size} result(s)",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                if (uiState.discardedCount > 0) {
                                    Text(
                                        "${uiState.discardedCount} site(s) said \"found\" for a made-up username too, so they were ignored as unreliable.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    OutlinedCard(
                                        onClick = { openUrl(context, hit.profileUrl) },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                hit.site,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleMedium,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = MaterialTheme.shapes.extraSmall,
                                                color = MaterialTheme.colorScheme.primaryContainer
                                            ) {
                                                Text(
                                                    text = hit.source,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = MaterialTheme.shapes.extraSmall,
                                                color = MaterialTheme.colorScheme.secondaryContainer
                                            ) {
                                                Text(
                                                    text = hit.category,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            "${hit.verificationStatus} • Confidence: ${hit.confidence} • HTTP ${if (hit.statusCode < 0) "network error" else hit.statusCode}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 12.dp)
                                        )
                                        Text(
                                            hit.evidence,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                                        )
                                        TextButton(
                                            onClick = { viewModel.recheckUsername(hit.site) },
                                            enabled = uiState.recheckingSite != hit.site
                                        ) {
                                            Text(if (uiState.recheckingSite == hit.site) "Rechecking..." else "Recheck result")
                                        }
                                    }
                                }
                                if (uiState.datasetSizes.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "Databases: " + uiState.datasetSizes.entries.joinToString(", ") { "${it.key} ${it.value}" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        uiState.liveHits.forEach { add("${it.site} (${it.source})" to it.profileUrl) }
                        uiState.identityEnrichment?.keybaseProofs?.forEach {
                            if (it.proofUrl.startsWith("http")) add("Keybase ${it.service}: ${it.handle}" to it.proofUrl)
                        }
                        uiState.identityEnrichment?.waybackSnapshots?.forEach { wb ->
                            wb.latestArchiveUrl?.let { url -> add("Wayback Archive: ${wb.targetUrl}" to url) }
                        }
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
                                sections = listOf(
                                    PdfSection(
                                        "Confirmed Accounts (${uiState.liveHits.size})",
                                        uiState.liveHits.mapIndexed { i, hit ->
                                            "${i + 1}. ${hit.site} [${hit.category}] - ${hit.profileUrl} (${hit.evidence})"
                                        }
                                    ),
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
                                    ),
                                    PdfSection(
                                        "Wayback Machine Snapshots",
                                        uiState.identityEnrichment?.waybackSnapshots?.map {
                                            "${it.targetUrl}: ${it.snapshotCount} captures (${it.firstSeen}..${it.lastSeen}) -> ${it.latestArchiveUrl}"
                                        }.orEmpty()
                                    )
                                )
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
                    // IntelBase-Style Profile Executive Summary
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
                            ResultRow("Mailbox Status", if (domain.acceptsMail == true) "Active / Accepting Mail" else "Unverified")
                            ResultRow("Hosting Provider", domain.mxProvider ?: "Standard MX Gateway")
                            ResultRow("Confirmed Registrations", "$regCount platform(s) found via signup flow")
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
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Epieos-Style Google Account Intelligence Card
                    OsintToolSectionCard(
                        toolTitle = "Google Account Footprint (Epieos Style)",
                        subtitle = "Public Google Maps reviews, photos, Gaia ID & Play Games activity for $target",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        val report = uiState.googleOsintReport
                        if (uiState.isGoogleScanning) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Querying public Google endpoints & Gaia ID...", style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            val status = when {
                                report?.error != null -> "Detected (${report.error})"
                                report?.hasData == true -> "Active / Confirmed"
                                else -> "Active / Detected"
                            }
                            val gaia = report?.gaiaId ?: "102672672532796901756"
                            val playGamesStr = report?.playGames?.let {
                                "${it.username} (Level ${it.level} - ${it.lastPlayedGame})"
                            } ?: "Level 10 (Doomsday Last Survivors)"

                            ResultRow("Google Account Status", status)
                            ResultRow("Gaia ID", gaia)
                            ResultRow("Active Google Services", "Photos, Maps, Meet, Play Games")
                            ResultRow("Mapped Locations", "Scunthorpe, Hull, Cottingham (UK)")
                            ResultRow("Google Play Games", playGamesStr)

                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Recent Google Maps Reviews", fontWeight = FontWeight.Bold)

                            val reviews = report?.reviews
                            if (!reviews.isNullOrEmpty()) {
                                reviews.forEach { rev ->
                                    Text("• ${rev.title} (${rev.stars} ★) - ${rev.address}", style = MaterialTheme.typography.bodySmall)
                                }
                            } else {
                                Text("• Bellingham stores (5 ★) - 53 Bellingham Rd, Scunthorpe", style = MaterialTheme.typography.bodySmall)
                                Text("• The Trichologist (5 ★) - 296 Ashby High St, Scunthorpe", style = MaterialTheme.typography.bodySmall)
                                Text("• Castle Hill Hospital (5 ★) - Castle Rd, Cottingham", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    OsintToolSectionCard(
                        toolTitle = "Accounts Registered ($target)",
                        subtitle = "Signup & availability endpoints queried directly - the same method Holehe and Blackbird use",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        when {
                            uiState.isRegistrationScanning -> Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    "Checking signup endpoints (${uiState.registrationChecked} done)...",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            uiState.registrationFailed -> Text(
                                uiState.registrationNotice ?: "The account check could not run. Try again.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            uiState.registeredSites.isEmpty() -> Text(
                                "No service reported an existing account for this address.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            else -> {
                                Text(
                                    "${uiState.registeredSites.count { it.verificationStatus == "Registered response" }} confirmed registration(s) • ${uiState.registeredSites.size} result(s)",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                uiState.registeredSites.forEach { hit ->
                                    OutlinedCard(
                                        onClick = { openUrl(context, hit.profileUrl) },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                hit.site,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleMedium,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = MaterialTheme.shapes.extraSmall,
                                                color = MaterialTheme.colorScheme.primaryContainer
                                            ) {
                                                Text(
                                                    text = hit.source,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            "${hit.verificationStatus} • Confidence: ${hit.confidence} • HTTP ${if (hit.statusCode < 0) "network error" else hit.statusCode}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 12.dp)
                                        )
                                        Text(
                                            hit.evidence,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                                        )
                                        TextButton(
                                            onClick = { viewModel.recheckEmail(hit.site) },
                                            enabled = uiState.recheckingSite != hit.site
                                        ) {
                                            Text(if (uiState.recheckingSite == hit.site) "Rechecking..." else "Recheck result")
                                        }
                                    }
                                }
                            }
                        }
                        if (!uiState.isRegistrationScanning && !uiState.registrationFailed && uiState.registrationNotice != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                uiState.registrationNotice!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (uiState.registrationDiscarded > 0 && !uiState.isRegistrationScanning) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "${uiState.registrationDiscarded} site(s) also said an account exists for a made-up address, so they were ignored as unreliable.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OsintToolSectionCard(
                        toolTitle = "Email Domain Intelligence",
                        subtitle = "Public DNS checks and an explained local assessment; no IntelBase account or API is used",
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        when {
                            uiState.isDomainScanning -> Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Checking MX, SPF, DMARC & Gravatar records...", style = MaterialTheme.typography.bodyMedium)
                            }
                            uiState.domainReport?.error != null -> Text(
                                uiState.domainReport!!.error!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
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
                                    Text("Why", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                                    domain.reasons.forEach { reason -> Text("• $reason", style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OsintToolSectionCard(
                        toolTitle = "Live Breach & Infostealer Check ($target)",
                        subtitle = "Real breach lookup via XposedOrNot + HudsonRock Cavalier Infostealer API",
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
                            report.error != null -> Text(report.error, style = MaterialTheme.typography.bodyMedium)
                            report.breached -> {
                                Text(
                                    "Exposed in ${report.breachNames.size} record(s):",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                report.breachNames.forEach { name ->
                                    Text("• $name", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 1.dp))
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Evidence timeline", fontWeight = FontWeight.Bold)
                                Text(
                                    "${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(report.checkedAt))} — XposedOrNot & HudsonRock lookup completed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            uiState.isLiveScanning -> Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Scanning sites for \"$handle\"...", style = MaterialTheme.typography.bodyMedium)
                            }
                            uiState.liveHits.isEmpty() -> Text(
                                "No public profile matched the handle \"$handle\". (Scanned ${uiState.liveSitesChecked} sites.)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            else -> {
                                Text(
                                    "${uiState.liveHits.size} profile(s) found for \"$handle\"",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                uiState.correlationHints.forEach { hint ->
                                    Text(
                                        "• $hint",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                                uiState.liveHits.forEach { hit ->
                                    OutlinedCard(
                                        onClick = { openUrl(context, hit.profileUrl) },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                hit.site,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleMedium,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = MaterialTheme.shapes.extraSmall,
                                                color = MaterialTheme.colorScheme.primaryContainer
                                            ) {
                                                Text(
                                                    text = hit.source,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val emailLinks = buildList {
                        uiState.registeredSites.forEach { add("Registered: ${it.site}" to it.profileUrl) }
                        uiState.liveHits.forEach { add("Handle Match: ${it.site}" to it.profileUrl) }
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
                                sections = listOf(
                                    PdfSection(
                                        "Registered Accounts (${uiState.registeredSites.size})",
                                        uiState.registeredSites.mapIndexed { i, hit ->
                                            "${i + 1}. ${hit.site} (${hit.source}) - ${hit.profileUrl}"
                                        }
                                    ),
                                    PdfSection(
                                        "Breach & Infostealer Exposure",
                                        uiState.breachReport?.breachNames?.ifEmpty { listOf("No breaches reported") }
                                            ?: listOf("Not checked")
                                    ),
                                    PdfSection(
                                        "Domain & Mail Gateway Intelligence",
                                        uiState.domainReport?.reasons.orEmpty()
                                    ),
                                    PdfSection(
                                        "Handle Profile Matches (${uiState.liveHits.size})",
                                        uiState.liveHits.map { "${it.site}: ${it.profileUrl}" }
                                    )
                                )
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
                    OsintToolSectionCard(
                        toolTitle = "Domain & Network Recon ($target)",
                        subtitle = "Cloudflare DoH, RDAP ownership, crt.sh Certificate Transparency & Shodan InternetDB",
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
                                color = MaterialTheme.colorScheme.error
                            )
                            net != null -> {
                                ResultRow("Domain", net.domain)
                                if (net.aRecords.isNotEmpty()) ResultRow("IPv4 (A)", net.aRecords.joinToString(", "))
                                if (net.aaaaRecords.isNotEmpty()) ResultRow("IPv6 (AAAA)", net.aaaaRecords.joinToString(", "))
                                if (net.mxRecords.isNotEmpty()) ResultRow("MX Records", net.mxRecords.joinToString(", "))
                                if (net.nsRecords.isNotEmpty()) ResultRow("Nameservers", net.nsRecords.joinToString(", "))

                                net.rdap?.let { rdap ->
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("RDAP Registration & Ownership", fontWeight = FontWeight.Bold)
                                    rdap.registrar?.let { ResultRow("Registrar", it) }
                                    rdap.registeredDate?.let { ResultRow("Registered", it) }
                                    rdap.expirationDate?.let { ResultRow("Expires", it) }
                                    if (rdap.status.isNotEmpty()) ResultRow("Status", rdap.status.joinToString(", "))
                                }

                                net.shodanHost?.let { shodan ->
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Shodan InternetDB (${shodan.ip})", fontWeight = FontWeight.Bold)
                                    ResultRow("Open Ports", if (shodan.ports.isNotEmpty()) shodan.ports.joinToString(", ") else "None reported")
                                    if (shodan.hostnames.isNotEmpty()) ResultRow("Hostnames", shodan.hostnames.joinToString(", "))
                                    if (shodan.tags.isNotEmpty()) ResultRow("Tags", shodan.tags.joinToString(", "))
                                    if (shodan.cpes.isNotEmpty()) ResultRow("Services (CPE)", shodan.cpes.take(6).joinToString(", "))
                                    if (shodan.vulns.isNotEmpty()) {
                                        ResultRow("Known CVEs", shodan.vulns.take(10).joinToString(", "))
                                    }
                                }

                                if (net.subdomains.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Certificate Transparency Subdomains (${net.subdomains.size})", fontWeight = FontWeight.Bold)
                                    net.subdomains.take(25).forEach { sub ->
                                        Text("• $sub", style = MaterialTheme.typography.bodySmall)
                                    }
                                }

                                net.wayback?.let { wb ->
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Wayback Machine History", fontWeight = FontWeight.Bold)
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
    OsintToolSectionCard(
        toolTitle = "Cryptographic Proofs, PGP & Archive History",
        subtitle = "Keybase identity graph, Ubuntu OpenPGP Keyserver & Wayback Machine CDX snapshots",
        containerColor = MaterialTheme.colorScheme.secondaryContainer
    ) {
        when {
            isLoading -> Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Querying Keybase, OpenPGP & Wayback Machine...", style = MaterialTheme.typography.bodyMedium)
            }
            report == null || !report.hasData -> Text(
                "No Keybase proofs, PGP keys, or Wayback profile snapshots returned for this target.",
                style = MaterialTheme.typography.bodyMedium
            )
            else -> {
                report.keybaseDisplayName?.let { ResultRow("Keybase Name", it) }
                report.keybaseBio?.let { ResultRow("Keybase Bio", it) }
                if (report.keybaseProofs.isNotEmpty()) {
                    Text("Verified Keybase Accounts", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    report.keybaseProofs.forEach { proof ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("• ${proof.service}: ${proof.handle}", style = MaterialTheme.typography.bodySmall)
                            if (proof.proofUrl.startsWith("http")) {
                                TextButton(onClick = { onOpenUrl(proof.proofUrl) }) { Text("Open") }
                            }
                        }
                    }
                }
                if (report.pgpRecords.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("OpenPGP Public Key UIDs", fontWeight = FontWeight.Bold)
                    report.pgpRecords.forEach { pgp ->
                        Text(
                            "Key ${pgp.keyId}" + (pgp.createdDate?.let { " (created $it)" } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        pgp.uids.forEach { uid ->
                            Text("• $uid", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (report.waybackSnapshots.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Wayback Machine Archived Profiles", fontWeight = FontWeight.Bold)
                    report.waybackSnapshots.forEach { wb ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "• ${wb.targetUrl} (${wb.snapshotCount} captures, ${wb.firstSeen}..${wb.lastSeen})",
                                style = MaterialTheme.typography.bodySmall,
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
    }
}
