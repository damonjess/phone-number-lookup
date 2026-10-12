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
import com.example.phone_number_lookup.OsintTools
import com.example.phone_number_lookup.UsernameScanner
import com.example.phone_number_lookup.ui.components.DorkButton
import com.example.phone_number_lookup.ui.components.OsintToolSectionCard
import com.example.phone_number_lookup.ui.components.openUrl
import com.example.phone_number_lookup.ui.viewmodels.UsernameAndEmailOsintViewModel

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
            text = "Site databases ported from Sherlock, Blackbird, Tookie-OSINT & WhatsMyName",
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
                val platforms = OsintTools.getBlackbirdUsernameScan(target)
                val tookieDorks = OsintTools.getTookieBribDorks(target)

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
                                "${uiState.liveHits.size} profile(s) found",
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

                OsintToolSectionCard(
                    toolTitle = "Manual Profile Links ($target)",
                    subtitle = "Opens the platform profile pages in a browser - the live scan above already checked these",
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    platforms.forEach { platform ->
                        OutlinedCard(
                            onClick = { openUrl(context, platform.profileUrl) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(platform.platformName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Surface(
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        text = platform.category,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OsintToolSectionCard(
                    toolTitle = "Saved Alias Searches (Tookie / Brib)",
                    subtitle = "Runs a web query for the alias - these open a browser, they do not scan",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    tookieDorks.forEach { dork ->
                        DorkButton(dork, context)
                    }
                }
            } else {
                // Email Scan
                val emailDorks = OsintTools.getBlackbirdEmailScan(target)

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
                                "${uiState.registeredSites.size} service(s) already have an account for this email",
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

                Spacer(modifier = Modifier.height(16.dp))

                OsintToolSectionCard(
                    toolTitle = "Manual Third-Party Lookups ($target)",
                    subtitle = "Opens outside lookup services in a browser - no data is fetched in-app",
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    emailDorks.forEach { dork ->
                        DorkButton(dork, context)
                    }
                }
            }
        }
    }
}
