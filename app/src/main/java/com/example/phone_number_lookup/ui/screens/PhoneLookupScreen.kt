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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.phone_number_lookup.OsintTools
import com.example.phone_number_lookup.PhoneReputation
import com.example.phone_number_lookup.PhoneNumberUtils
import com.example.phone_number_lookup.ui.components.DorkButton
import com.example.phone_number_lookup.ui.components.OsintToolSectionCard
import com.example.phone_number_lookup.ui.components.ResultRow
import com.example.phone_number_lookup.ui.components.openUrl
import com.example.phone_number_lookup.ui.viewmodels.PhoneLookupViewModel

@Composable
fun PhoneLookupScreen(
    viewModel: PhoneLookupViewModel = viewModel()
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
            text = "Phone OSINT Suite",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Integrated tools: PhoneInfoga, Phonia, tellows & live web search",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        OutlinedTextField(
            value = uiState.phoneNumberInput,
            onValueChange = { viewModel.onPhoneNumberInputChanged(it) },
            label = { Text("Enter phone number") },
            placeholder = { Text("e.g. 01782 447307 or +44 1782...") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(
                onSearch = { viewModel.scanNumber() }
            ),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                if (uiState.phoneNumberInput.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onPhoneNumberInputChanged("") }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear input"
                        )
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { viewModel.scanNumber() },
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) {
                Text("Scan Number")
            }

            OutlinedButton(
                onClick = { viewModel.clear() },
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            ) {
                Text("Clear")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        uiState.resultNumber?.let { input ->
            val normalized = PhoneNumberUtils.normalize(input)
            val isUk = PhoneNumberUtils.isValidUk(normalized)
            val areaInfo = PhoneNumberUtils.getAreaInfo(normalized)
            val country = PhoneNumberUtils.getCountry(normalized)
            val timezone = PhoneNumberUtils.getTimezone(normalized)
            val lineType = PhoneNumberUtils.getLineType(normalized)
            val localFormat = if (normalized.startsWith("+44")) "0" + normalized.removePrefix("+44") else normalized
            val formats = PhoneNumberUtils.getFormattedFormats(normalized, localFormat)

            // Overview Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Phone Number Breakdown",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    ResultRow("Original", input)
                    ResultRow("E.164 Normalized", formats.e164)
                    ResultRow("International", formats.international)
                    ResultRow("National Format", formats.national)
                    ResultRow("RFC3966 Link", formats.rfc3966)
                    ResultRow("Country", country)
                    ResultRow("Line / Network Type", lineType)
                    ResultRow("Time Zone", timezone)
                    if (isUk && areaInfo != null) {
                        ResultRow("UK Region", areaInfo)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Live Spam / Caller Reputation Scan (tellows free JSON API, no key)
            OsintToolSectionCard(
                toolTitle = "Live Spam Database Scan",
                subtitle = "Real-time crowd-sourced caller reputation from tellows",
                containerColor = MaterialTheme.colorScheme.errorContainer
            ) {
                when {
                    uiState.isSpamScanning -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Querying tellows caller database...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    uiState.spamReport != null -> {
                        val report = uiState.spamReport!!
                        if (report.score == null && report.error != null) {
                            Text(report.error, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            ResultRow("Source", report.source)
                            ResultRow("Spam Score", report.scoreLabel)
                            ResultRow("Verdict", report.riskLabel)
                            report.comments?.let { ResultRow("Community Reports", it.toString()) }
                            report.searches?.let { ResultRow("Times Searched", it.toString()) }
                            report.location?.let { ResultRow("Reported Location", it) }
                            report.country?.let { ResultRow("Country", it) }
                            report.callerType?.let { ResultRow("Caller Type", it) }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Extra community caller-report databases
            OsintToolSectionCard(
                toolTitle = "Community Report Databases",
                subtitle = if (PhoneReputation.supportedSources(normalized).isEmpty())
                    "WhoCallsMe & 800notes only index North American (+1) numbers"
                else "Live user reports from WhoCallsMe and 800notes",
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                when {
                    uiState.isReputationScanning -> Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Querying community call databases...", style = MaterialTheme.typography.bodyMedium)
                    }
                    uiState.reputationReports.isEmpty() -> Text(
                        "No additional no-key source applies to this number. tellows remains the primary lookup above.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    else -> uiState.reputationReports.forEach { rep ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(rep.source, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Surface(
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        text = if (rep.hasReports) "${rep.reportCount} report(s)" else "No reports",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            rep.error?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            rep.samples.forEach { sample ->
                                Text("• $sample", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 1.dp))
                            }
                            if (rep.hasReports) {
                                TextButton(onClick = { openUrl(context, rep.url) }) {
                                    Text("Open ${rep.source} ↗")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // PhoneInfoga Section
            OsintToolSectionCard(
                toolTitle = "PhoneInfoga OSINT Engine",
                subtitle = "Footprinting, Google dorks & external reverse lookups",
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                val dorks = OsintTools.getPhoneInfogaDorks(normalized, localFormat)
                val extTools = OsintTools.getPhoneInfogaExternalTools(normalized, localFormat)

                Text("PhoneInfoga Google Dorks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                dorks.forEach { dork ->
                    DorkButton(dork, context)
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("PhoneInfoga External Lookups", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                extTools.forEach { tool ->
                    DorkButton(tool, context)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Phonia Section
            OsintToolSectionCard(
                toolTitle = "Phonia OSINT Engine",
                subtitle = "Messaging profiles & Truecaller web query",
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ) {
                val phoniaTools = OsintTools.getPhoniaTools(normalized)
                phoniaTools.forEach { tool ->
                    DorkButton(tool, context)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // IntelBase Section
            OsintToolSectionCard(
                toolTitle = "IntelBase OSINT Portal",
                subtitle = "Multi-source intelligence query hub",
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                val intelDork = OsintTools.getIntelBaseDork(normalized)
                DorkButton(intelDork, context)
            }

            Spacer(modifier = Modifier.height(20.dp))

            // DuckDuckGo Web Results
            Text(
                text = "Live Web Search Matches",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp)
            )

            if (uiState.isSearching) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Searching public databases...")
                }
            } else if (uiState.hasSearched && uiState.searchResults.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Text(
                        text = "No direct web matches found for this number.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                uiState.searchResults.forEach { result ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = result.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = result.snippet,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = { openUrl(context, result.url) },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Open Link ↗")
                            }
                        }
                    }
                }
            }
        }
    }
}
