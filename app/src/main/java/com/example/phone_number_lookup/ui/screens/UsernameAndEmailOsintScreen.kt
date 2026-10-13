package com.example.phone_number_lookup.ui.screens

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
import com.example.phone_number_lookup.UsernameScanner
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
            text = "Native HTTP checks with results displayed in this app",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FilterChip(
                selected = uiState.subTabState == 0,
                onClick = { viewModel.onSubTabChanged(0) },
                label = { Text("Username OSINT") }
            )
            FilterChip(
                selected = uiState.subTabState == 1,
                onClick = { viewModel.onSubTabChanged(1) },
                label = { Text("Email OSINT") }
            )
        }

        if (uiState.subTabState == 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Quick (150)" to 0, "Standard (300)" to 1, "Full sweep" to 2).forEach { (label, index) ->
                    FilterChip(
                        selected = uiState.scanDepth == index,
                        onClick = { viewModel.onScanDepthChanged(index) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
        }

        OutlinedTextField(
            value = uiState.targetInput,
            onValueChange = { viewModel.onTargetInputChanged(it) },
            label = { Text(if (uiState.subTabState == 0) "Enter Username (e.g. dnkirby)" else "Enter Email (e.g. alex.smith@gmail.com)") },
            placeholder = { Text(if (uiState.subTabState == 0) "e.g. dnkirby or john_doe" else "e.g. alex.smith@gmail.com") },
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
            Text(if (uiState.subTabState == 0) "Run Live Username Scanner" else "Run Live Breach Check")
        }

        Spacer(modifier = Modifier.height(16.dp))

        uiState.activeTarget?.let { target ->
            if (uiState.subTabState == 0) {
                // Username Scan
                OsintToolSectionCard(
                    toolTitle = "Live Username Scan ($target)",
                    subtitle = if (uiState.liveSitesChecked > 0)
                        "${uiState.liveSitesChecked} real HTTP checks across the merged WhatsMyName, Sherlock and Tookie databases"
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
                                    if (uiState.unreachableCount > 0) ", ${uiState.unreachableCount} unreachable." else ".",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        else -> {
                            Text(
                                "${uiState.liveHits.count { it.verificationStatus == "Matched" }} confirmed profile(s) • ${uiState.liveHits.size} result(s)",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            if (uiState.unreachableCount > 0) {
                                Text(
                                    "${uiState.unreachableCount} site(s) blocked the request and were not counted.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp)
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

            } else {
                // Email Scan
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
                    if (uiState.emailUnreachable > 0 && !uiState.isRegistrationScanning) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "${uiState.emailUnreachable} site(s) blocked the request and were not counted.",
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
                            Text("Checking MX, SPF and DMARC records...", style = MaterialTheme.typography.bodyMedium)
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
                            Text(
                                "This is an email/domain quality assessment, not proof that the mailbox belongs to anyone or that it is safe.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OsintToolSectionCard(
                    toolTitle = "Live Breach Check ($target)",
                    subtitle = "Real breach lookup via the free XposedOrNot API",
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
                            Text("Checking breach databases...", style = MaterialTheme.typography.bodyMedium)
                        }
                        report == null -> Text(
                            "Run a scan to check this email against known data breaches.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        report.error != null -> Text(report.error, style = MaterialTheme.typography.bodyMedium)
                        report.breached -> {
                            Text(
                                "Exposed in ${report.breachNames.size} breach(es):",
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
                                "${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(report.checkedAt))} — XposedOrNot lookup returned these breach names",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Breach publication dates and exposed fields were not returned by this endpoint, so the app does not invent them.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        else -> Text(
                            "No breaches found in the XposedOrNot database.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

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
                            if (uiState.publicSuggestions.isNotEmpty()) {
                                Text("Public signals to review", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                                uiState.publicSuggestions.forEach { suggestion ->
                                    Text("• $suggestion", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 1.dp))
                                }
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

            }
        }
    }
}
