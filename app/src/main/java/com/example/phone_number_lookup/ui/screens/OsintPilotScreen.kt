package com.example.phone_number_lookup.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.phone_number_lookup.ConfidenceLevel
import com.example.phone_number_lookup.PilotAgentStatus
import com.example.phone_number_lookup.PilotInvestigationReport
import com.example.phone_number_lookup.PilotMediaPhoto
import com.example.phone_number_lookup.PilotTargetType
import com.example.phone_number_lookup.SourcedFinding
import com.example.phone_number_lookup.ui.components.Notice
import com.example.phone_number_lookup.ui.components.Pill
import com.example.phone_number_lookup.ui.components.SectionCard
import com.example.phone_number_lookup.ui.components.Space
import com.example.phone_number_lookup.ui.components.SubHeading
import com.example.phone_number_lookup.ui.components.Tone
import com.example.phone_number_lookup.ui.viewmodels.OsintPilotViewModel

@Composable
fun OsintPilotScreen(
    modifier: Modifier = Modifier,
    viewModel: OsintPilotViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.lg)
    ) {
        item {
            PilotHeader()
        }

        item {
            PilotTargetInputCard(
                input = uiState.targetInput,
                selectedType = uiState.targetType,
                locationHint = uiState.locationHint,
                aliasesInput = uiState.aliasesInput,
                isContextExpanded = uiState.isContextExpanded,
                isInvestigating = uiState.isInvestigating,
                onInputChange = viewModel::onTargetInputChanged,
                onTypeSelect = viewModel::onTargetTypeSelected,
                onLocationChange = viewModel::onLocationHintChanged,
                onAliasesChange = viewModel::onAliasesInputChanged,
                onToggleContext = viewModel::toggleContextExpanded,
                onLaunch = {
                    keyboard?.hide()
                    viewModel.launchInvestigation()
                },
                onClear = viewModel::clear
            )
        }

        uiState.exportStatus?.let { msg ->
            item {
                Notice(msg, tone = Tone.INFO)
            }
        }

        uiState.report?.let { report ->
            item {
                PilotReportOverviewCard(
                    report = report,
                    onExportPdf = { viewModel.exportPdf(context) },
                    onShareMarkdown = { viewModel.shareMarkdown(context) }
                )
            }

            item {
                PilotAgentsFeedCard(agents = report.agentStatuses, isRunning = report.isRunning)
            }

            if (report.recoveredPhotos.isNotEmpty()) {
                item {
                    PilotMediaRecoverySection(photos = report.recoveredPhotos)
                }
            }

            if (report.linkedAccounts.isNotEmpty()) {
                item {
                    PilotLinkedAccountsCard(accounts = report.linkedAccounts)
                }
            }

            if (report.resolvedNames.isNotEmpty() ||
                report.resolvedAliases.isNotEmpty() ||
                report.linkedPhones.isNotEmpty() ||
                report.linkedEmails.isNotEmpty() ||
                report.locations.isNotEmpty() ||
                report.telecomCarrier != null
            ) {
                item {
                    PilotIdentityDossierCard(report = report)
                }
            }

            if (report.breachRecords.isNotEmpty()) {
                item {
                    PilotBreachIntelligenceCard(breaches = report.breachRecords)
                }
            }

            if (report.publicRecords.isNotEmpty()) {
                item {
                    PilotPublicRecordsCard(records = report.publicRecords)
                }
            }

            if (report.findings.isNotEmpty()) {
                item {
                    PilotSourcedFindingsCard(findings = report.findings)
                }
            }
        }

        item {
            Spacer(Modifier.height(Space.xxl))
        }
    }
}

@Composable
private fun PilotHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Space.md, bottom = Space.sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "OSINT",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "PILOT",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "AUTOMATED INTELLIGENCE INVESTIGATIONS",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.6.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Pill("AUTONOMOUS", tone = Tone.ACCENT)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Six specialized AI agents work in parallel across 30+ intelligence sources to deliver complete, sourced dossiers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PilotTargetInputCard(
    input: String,
    selectedType: PilotTargetType,
    locationHint: String,
    aliasesInput: String,
    isContextExpanded: Boolean,
    isInvestigating: Boolean,
    onInputChange: (String) -> Unit,
    onTypeSelect: (PilotTargetType) -> Unit,
    onLocationChange: (String) -> Unit,
    onAliasesChange: (String) -> Unit,
    onToggleContext: () -> Unit,
    onLaunch: () -> Unit,
    onClear: () -> Unit
) {
    SectionCard(
        title = "INVESTIGATION TARGET",
        subtitle = "Enter phone, email, username or full name to deploy agents",
        tone = Tone.ACCENT
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Target (Phone, Email, Username, or Name)") },
            placeholder = { Text("+44 7700 900000, target@domain.com, @handle") },
            singleLine = true,
            trailingIcon = {
                if (input.isNotBlank()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Go
            ),
            keyboardActions = KeyboardActions(onGo = { onLaunch() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = "TARGET TYPE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            PilotTargetType.entries.forEach { type ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = { onTypeSelect(type) },
                    label = { Text(type.name, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleContext() }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isContextExpanded) "- Hide Investigation Context" else "+ Add Location & Known Aliases Context",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }

        AnimatedVisibility(visible = isContextExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = locationHint,
                    onValueChange = onLocationChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Location / Region Hint (e.g. UK, London, California)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = aliasesInput,
                    onValueChange = onAliasesChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Known Aliases / Nicknames (comma separated)") },
                    singleLine = true
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Button(
            onClick = onLaunch,
            enabled = input.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            if (isInvestigating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text("AGENTS INVESTIGATING...")
            } else {
                Text("LAUNCH PILOT INVESTIGATION")
            }
        }
    }
}

@Composable
private fun PilotReportOverviewCard(
    report: PilotInvestigationReport,
    onExportPdf: () -> Unit,
    onShareMarkdown: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(Space.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "INVESTIGATION #${report.investigationId}",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (report.isRunning) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = Color(0xFFF59E0B).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .scale(pulseScale)
                                    .clip(CircleShape)
                                    .background(Color(0xFFF59E0B))
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "RUNNING",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFF59E0B)
                            )
                        }
                    }
                } else {
                    Pill("REPORT READY", tone = Tone.ACCENT)
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Target: ${report.target} [${report.detectedType.name}]",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "${report.findings.size} total findings verified & source-linked across 6 specialized agents.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onExportPdf,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text("Export PDF", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onShareMarkdown,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share .MD", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun PilotAgentsFeedCard(
    agents: List<PilotAgentStatus>,
    isRunning: Boolean
) {
    SectionCard(
        title = "AI AGENT WORKFLOW & DISCOVERY FEED",
        subtitle = "Six autonomous agents working in parallel",
        tone = Tone.ACCENT
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            agents.forEachIndexed { idx, agent ->
                if (idx > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Surface(
                        modifier = Modifier.size(24.dp),
                        shape = CircleShape,
                        color = if (agent.isComplete) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (agent.isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = agent.question,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = agent.timeAgo,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = agent.statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        agent.countBadge?.let { badge ->
                            Spacer(Modifier.height(4.dp))
                            Pill(badge, tone = Tone.INFO)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PilotMediaRecoverySection(photos: List<PilotMediaPhoto>) {
    val context = LocalContext.current
    SectionCard(
        title = "RECOVERED MEDIA & VISUAL PROFILES",
        subtitle = "${photos.size} images retrieved across accounts",
        tone = Tone.ACCENT
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(photos) { photo ->
                Column(
                    modifier = Modifier
                        .width(100.dp)
                        .clickable {
                            com.example.phone_number_lookup.ui.components.openUrl(context, photo.url)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AsyncImage(
                        model = photo.url,
                        contentDescription = photo.label,
                        modifier = Modifier
                            .size(90.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = photo.source,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        softWrap = true
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PilotLinkedAccountsCard(accounts: List<com.example.phone_number_lookup.PhoneAccountHit>) {
    val context = LocalContext.current
    val confirmed = accounts.filter { it.confirmed }

    SectionCard(
        title = "WHAT THEY ARE SIGNED UP TO (LINKED ACCOUNTS)",
        subtitle = "${accounts.size} platform profiles discovered · ${confirmed.size} confirmed",
        tone = Tone.ACCENT
    ) {
        if (confirmed.isNotEmpty()) {
            SubHeading("Confirmed Active Registrations (Tap to view in-app)")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                confirmed.forEach { acc ->
                    Pill(
                        text = acc.platform,
                        tone = Tone.ACCENT,
                        modifier = if (acc.url != null) {
                            Modifier.clickable {
                                com.example.phone_number_lookup.ui.components.openUrl(context, acc.url)
                            }
                        } else Modifier
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        accounts.forEachIndexed { idx, acc ->
            if (idx > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (acc.url != null) {
                            Modifier.clickable {
                                com.example.phone_number_lookup.ui.components.openUrl(context, acc.url)
                            }
                        } else Modifier
                    ),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = acc.platform,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        softWrap = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Pill(acc.status, tone = if (acc.confirmed) Tone.ACCENT else Tone.INFO)
                }
                Text(
                    text = acc.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = true
                )
            }
        }
    }
}

@Composable
private fun PilotIdentityDossierCard(report: PilotInvestigationReport) {
    SectionCard(
        title = "IDENTITY RESOLUTION DOSSIER",
        subtitle = "Correlated names, usernames, phones, emails & locations",
        tone = Tone.ACCENT
    ) {
        report.resolvedNames.forEach { name ->
            PilotDataRow("Resolved Name", name.value, name.source)
        }
        report.resolvedAliases.forEach { alias ->
            PilotDataRow("Username / Alias", alias.value, alias.source)
        }
        report.linkedEmails.forEach { email ->
            PilotDataRow("Linked Email", email, "Email Intelligence")
        }
        report.linkedPhones.forEach { phone ->
            PilotDataRow("Phone Number", phone, "Telecom / Recovery Intelligence")
        }
        report.telecomCarrier?.let {
            PilotDataRow("Telecom Carrier", it, "Network Allocation Plan")
        }
        report.telecomLineType?.let {
            PilotDataRow("Line Classification", it, "Telecommunications Exchange")
        }
        report.locations.forEach { loc ->
            PilotDataRow("Location", loc.value, loc.source)
        }
        report.riskVerdict?.let { v ->
            PilotDataRow("Fraud & Scam Verdict", "${v.headline} (${v.summary})", "Telecom Risk Engine")
        }
    }
}

@Composable
private fun PilotBreachIntelligenceCard(breaches: List<com.example.phone_number_lookup.BreachDetail>) {
    SectionCard(
        title = "BREACH & INFOSTEALER INTELLIGENCE",
        subtitle = "${breaches.size} known data breaches or stealer logs tied to target",
        tone = Tone.DANGER
    ) {
        breaches.forEachIndexed { idx, breach ->
            if (idx > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = breach.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        softWrap = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Pill(breach.breachDate ?: "Reported", tone = Tone.DANGER)
                }
                if (breach.pwnCount > 0) {
                    Text(
                        text = "Exposed Accounts: ${breach.pwnCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (breach.dataClasses.isNotEmpty()) {
                    Text(
                        text = "Compromised Data: ${breach.dataClasses.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = true
                    )
                }
                if (breach.description.isNotBlank() && breach.description != breach.name) {
                    Text(
                        text = breach.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        softWrap = true
                    )
                }
            }
        }
    }
}

@Composable
private fun PilotPublicRecordsCard(records: List<com.example.phone_number_lookup.AttributedValue>) {
    val context = LocalContext.current
    SectionCard(
        title = "PUBLIC RECORDS, COMMENTS & CORPORATE FILINGS",
        subtitle = "${records.size} verified public entries",
        tone = Tone.ACCENT
    ) {
        records.forEachIndexed { idx, rec ->
            if (idx > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (rec.source.startsWith("http")) {
                            com.example.phone_number_lookup.ui.components.openUrl(context, rec.source)
                        }
                    }
            ) {
                Text(
                    text = rec.value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = true
                )
                Text(
                    text = "Source: ${rec.source}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    softWrap = true
                )
            }
        }
    }
}

@Composable
private fun PilotSourcedFindingsCard(findings: List<SourcedFinding>) {
    val context = LocalContext.current
    SectionCard(
        title = "SOURCED INTELLIGENCE LOG",
        subtitle = "${findings.size} sourced facts with confidence scores",
        tone = Tone.ACCENT
    ) {
        findings.take(80).forEachIndexed { idx, f ->
            if (idx > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (f.sourceUrl != null) {
                            Modifier.clickable {
                                com.example.phone_number_lookup.ui.components.openUrl(context, f.sourceUrl)
                            }
                        } else Modifier
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = f.category.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Pill(
                        f.confidence.name,
                        tone = when (f.confidence) {
                            ConfidenceLevel.HIGH -> Tone.ACCENT
                            ConfidenceLevel.MEDIUM -> Tone.INFO
                            ConfidenceLevel.LOW -> Tone.WARNING
                        }
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = f.claim,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = true
                )
                Text(
                    text = "Source: ${f.sourceName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    softWrap = true
                )
            }
        }
    }
}

@Composable
private fun PilotDataRow(label: String, value: String, source: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = source,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
