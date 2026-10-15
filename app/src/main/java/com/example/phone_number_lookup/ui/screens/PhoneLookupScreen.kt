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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.phone_number_lookup.HealthState
import com.example.phone_number_lookup.HistoryEntry
import com.example.phone_number_lookup.PhoneInfogaStatus
import com.example.phone_number_lookup.PhoneReputation
import com.example.phone_number_lookup.PhoneNumberUtils
import com.example.phone_number_lookup.ui.components.OsintToolSectionCard
import com.example.phone_number_lookup.ui.components.ResultRow
import com.example.phone_number_lookup.ui.components.VerdictCard
import com.example.phone_number_lookup.ui.components.openUrl
import com.example.phone_number_lookup.ui.viewmodels.PhoneLookupViewModel

@Composable
fun PhoneLookupScreen(
    viewModel: PhoneLookupViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var note by rememberSaveable { mutableStateOf("") }
    var numverifyKey by rememberSaveable { mutableStateOf("") }

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
            text = "Native in-app checks: numbering data, caller reports & live web search",
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

            // One-glance verdict, with the detailed breakdown underneath
            uiState.verdict?.let { verdict ->
                VerdictCard(verdict)
                Spacer(modifier = Modifier.height(16.dp))
            }

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
                    ResultRow("Estimated number type", lineType)
                    ResultRow("Country time-zone group", timezone)
                    Text(
                        "Type and time zone are estimates from public numbering data; they do not prove the current carrier or caller location.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    if (isUk && areaInfo != null) {
                        ResultRow("UK Region", areaInfo)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OsintToolSectionCard(
                toolTitle = "PhoneInfoga Native Scan",
                subtitle = "Local PhoneInfoga data is automatic. Numverify carrier data requires your apilayer key and is fetched inside the app.",
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text("Local scanner", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                ResultRow("Country", country)
                ResultRow("Area / region", areaInfo ?: "Not available")
                ResultRow("Estimated line type", lineType)
                ResultRow("E.164", formats.e164)
                Text(
                    "PhoneInfoga's public Google scanner generates links rather than fetching results. This app uses its native public web-search request below instead, so returned matches are displayed here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                OutlinedTextField(
                    value = numverifyKey,
                    onValueChange = { numverifyKey = it },
                    label = { Text("Numverify / apilayer API key (optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Button(
                    onClick = { viewModel.runPhoneInfogaNumverify(numverifyKey) },
                    enabled = numverifyKey.isNotBlank() && !uiState.isPhoneInfogaScanning,
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("Run native Numverify") }
                when {
                    uiState.isPhoneInfogaScanning -> CircularProgressIndicator(
                        modifier = Modifier.padding(top = 10.dp).size(20.dp), strokeWidth = 2.dp
                    )
                    uiState.phoneInfogaResult?.error != null -> Text(
                        uiState.phoneInfogaResult!!.error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    uiState.phoneInfogaResult != null -> {
                        val result = uiState.phoneInfogaResult!!
                        ResultRow("Numverify valid", result.valid?.toString() ?: "Not returned")
                        result.carrier?.let { ResultRow("Carrier", it) }
                        result.lineType?.let { ResultRow("Line type", it) }
                        result.countryName?.let { ResultRow("Country name", it) }
                        result.location?.let { ResultRow("Location", it) }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OsintToolSectionCard(
                toolTitle = "PhoneInfoga (the real tool)",
                subtitle = "Runs the actual PhoneInfoga project on a server you control. Some of its scanners contact third parties such as OVH and Numverify.",
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ) {
                OutlinedTextField(
                    value = uiState.phoneInfogaUrl,
                    onValueChange = viewModel::onPhoneInfogaUrlChanged,
                    label = { Text("PhoneInfoga server address") },
                    placeholder = { Text("http://127.0.0.1:5000") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { viewModel.runPhoneInfogaServer() },
                    enabled = !uiState.isPhoneInfogaServerRunning,
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("Run PhoneInfoga") }

                val piReport = uiState.phoneInfogaReport
                when {
                    uiState.isPhoneInfogaServerRunning -> CircularProgressIndicator(
                        modifier = Modifier.padding(top = 10.dp).size(20.dp), strokeWidth = 2.dp
                    )
                    piReport?.error != null -> Text(
                        piReport.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    piReport != null -> piReport.scans.forEach { scan ->
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            Text(
                                "${scan.scanner}: " + when (scan.status) {
                                    PhoneInfogaStatus.OK -> "ran"
                                    PhoneInfogaStatus.SKIPPED -> "skipped"
                                    PhoneInfogaStatus.ERROR -> "failed"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (scan.description.isNotBlank()) {
                                Text(
                                    scan.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            scan.message?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (scan.status == PhoneInfogaStatus.ERROR) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            scan.rows.forEach { (label, value) -> ResultRow(label, value) }
                            scan.links.take(12).forEach { link ->
                                TextButton(onClick = { openUrl(context, link) }) {
                                    Text(link, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (scan.links.size > 12) {
                                Text(
                                    "+${scan.links.size - 12} more links",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Save to private history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Stored only on this phone. No contacts, call log, or upload is used.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Personal note (optional)") },
                        placeholder = { Text("What did the caller say?") },
                        minLines = 2,
                        maxLines = 4
                    )
                    Button(
                        onClick = {
                            viewModel.saveCurrentToHistory(note)
                            note = ""
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) { Text("Save scan") }
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
                            ResultRow("Evidence confidence", report.confidenceLabel)
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
            } else if (uiState.searchError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        text = uiState.searchError!!,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else if (uiState.hasSearched && uiState.searchResults.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Text(
                        text = "No indexed web matches found for this number in this search.",
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

        Spacer(modifier = Modifier.height(16.dp))

        OsintToolSectionCard(
            toolTitle = "Source health",
            subtitle = "Tests every lookup source with a harmless test number, so a blocked or changed site isn't mistaken for \"nothing found\".",
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Button(
                onClick = { viewModel.runSourceHealth(numverifyKey) },
                enabled = !uiState.isCheckingHealth
            ) { Text("Check all sources") }

            if (uiState.isCheckingHealth) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 10.dp).size(20.dp), strokeWidth = 2.dp
                )
            }
            uiState.sourceHealth.forEach { result ->
                val color = when (result.state) {
                    HealthState.OK -> MaterialTheme.colorScheme.primary
                    HealthState.NOT_CONFIGURED -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                }
                val symbol = when (result.state) {
                    HealthState.OK -> "✓"
                    HealthState.BLOCKED, HealthState.UNREACHABLE -> "✗"
                    HealthState.CHANGED -> "!"
                    HealthState.NOT_CONFIGURED -> "–"
                }
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        "$symbol ${result.source} (${result.millis} ms)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                    Text(
                        result.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (uiState.history.isNotEmpty()) {
            HistorySection(
                entries = uiState.history,
                onClear = viewModel::clearHistory,
                onDelete = viewModel::deleteHistoryEntry,
                onStatusChange = viewModel::updateHistoryStatus,
                onNoteChange = viewModel::updateHistoryNote,
                onRescan = viewModel::rescanHistoryEntry
            )
        }
    }
}

@Composable
private fun HistorySection(
    entries: List<HistoryEntry>,
    onClear: () -> Unit,
    onDelete: (Long) -> Unit,
    onStatusChange: (Long, String) -> Unit,
    onNoteChange: (Long, String) -> Unit,
    onRescan: (String) -> Unit
) {
    val formatter = remember {
        java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault())
    }
    var showClearDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<HistoryEntry?>(null) }
    var editedNote by remember { mutableStateOf("") }
    Spacer(modifier = Modifier.height(20.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Saved history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        TextButton(onClick = { showClearDialog = true }) { Text("Clear all") }
    }
    Text(
        "Local-only history, maximum 50 entries.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    entries.forEach { entry ->
        Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(entry.number, fontWeight = FontWeight.Bold)
                Text(entry.label, style = MaterialTheme.typography.bodySmall)
                Text("Status: ${entry.status}", style = MaterialTheme.typography.bodySmall)
                Text(formatter.format(java.util.Date(entry.createdAt)), style = MaterialTheme.typography.bodySmall)
                if (entry.note.isNotBlank()) {
                    Text("Note: ${entry.note}", modifier = Modifier.padding(top = 4.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onRescan(entry.number) }) { Text("Scan again") }
                    TextButton(onClick = {
                        val next = when (entry.status) {
                            "Unknown" -> "Suspected spam"
                            "Suspected spam" -> "Trusted"
                            else -> "Unknown"
                        }
                        onStatusChange(entry.id, next)
                    }) { Text("Status") }
                    TextButton(onClick = {
                        editedNote = entry.note
                        editingEntry = entry
                    }) { Text("Edit note") }
                    TextButton(onClick = { onDelete(entry.id) }) { Text("Delete") }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear saved history?") },
            text = { Text("This permanently removes all locally saved numbers and notes from this phone.") },
            confirmButton = {
                TextButton(onClick = { onClear(); showClearDialog = false }) { Text("Clear all") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    editingEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { editingEntry = null },
            title = { Text("Edit note") },
            text = {
                OutlinedTextField(
                    value = editedNote,
                    onValueChange = { editedNote = it },
                    label = { Text("Personal note") },
                    minLines = 3
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onNoteChange(entry.id, editedNote)
                    editingEntry = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingEntry = null }) { Text("Cancel") }
            }
        )
    }
}
