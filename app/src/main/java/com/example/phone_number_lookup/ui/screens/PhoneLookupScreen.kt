package com.example.phone_number_lookup.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.phone_number_lookup.HealthState
import com.example.phone_number_lookup.HistoryEntry
import com.example.phone_number_lookup.OsintPdfExporter
import com.example.phone_number_lookup.OsintTools
import com.example.phone_number_lookup.PdfSection
import com.example.phone_number_lookup.PhoneInfogaStatus
import com.example.phone_number_lookup.PhoneNumberUtils
import com.example.phone_number_lookup.PhoneReputation
import com.example.phone_number_lookup.UnifiedEvidenceBuilder
import com.example.phone_number_lookup.ui.components.AppButton
import com.example.phone_number_lookup.ui.components.DeepPhoneFootprintSection
import com.example.phone_number_lookup.ui.components.DorkLauncherCard
import com.example.phone_number_lookup.ui.components.LoadingRow
import com.example.phone_number_lookup.ui.components.Notice
import com.example.phone_number_lookup.ui.components.NumberedLinksHubCard
import com.example.phone_number_lookup.ui.components.OsintToolSectionCard
import com.example.phone_number_lookup.ui.components.Pill
import com.example.phone_number_lookup.ui.components.PhoneProfileSummaryCard
import com.example.phone_number_lookup.ui.components.ResultRow
import com.example.phone_number_lookup.ui.components.ScreenHeader
import com.example.phone_number_lookup.ui.components.SearchField
import com.example.phone_number_lookup.ui.components.SectionCard
import com.example.phone_number_lookup.ui.components.Space
import com.example.phone_number_lookup.ui.components.Tone
import com.example.phone_number_lookup.ui.components.UnifiedEvidenceCard
import com.example.phone_number_lookup.ui.components.VerdictCard
import com.example.phone_number_lookup.ui.components.openUrl
import com.example.phone_number_lookup.ui.viewmodels.PhoneLookupViewModel

private data class TelecomTelemetry(
    val capabilityBadge: String,
    val capabilityTone: Tone,
    val tariffBadge: String,
    val tariffTone: Tone,
    val prefixBlockLabel: String,
    val cliAssessment: String,
    val callbackAdvice: String,
)

private fun buildTelecomTelemetry(normalized: String, localFormat: String): TelecomTelemetry {
    val cleanLocal = localFormat.filter { it.isDigit() }
    val isUk = normalized.startsWith("+44")
    val isUkPager = isUk && cleanLocal.startsWith("076") && !cleanLocal.startsWith("07624")
    val isUkDramaOrUnalloc = isUk && (
        cleanLocal.startsWith("01632") ||
            cleanLocal.startsWith("07700900") ||
            cleanLocal.startsWith("08081570") ||
            cleanLocal.startsWith("09098790") ||
            cleanLocal.startsWith("03069990") ||
            cleanLocal.startsWith("071") ||
            cleanLocal.startsWith("072")
        )
    val isUkPersonal = isUk && cleanLocal.startsWith("070")
    val isUkPremiumOrSpecial = isUk && (
        cleanLocal.startsWith("09") || cleanLocal.startsWith("084") || cleanLocal.startsWith("087")
        )
    val isUkFreephone = isUk && (cleanLocal.startsWith("0800") || cleanLocal.startsWith("0808"))
    val isUkVoip = isUk && cleanLocal.startsWith("056")
    val isUkGeographicDdi = isUk && (cleanLocal.startsWith("01") || cleanLocal.startsWith("02") || cleanLocal.startsWith("03"))

    val (capBadge, capTone) = when {
        isUkPager -> "RECEIVE-ONLY PAGER · CANNOT CALL OUT" to Tone.DANGER
        isUkDramaOrUnalloc -> "UNALLOCATED / DRAMA RANGE · SPOOFED" to Tone.DANGER
        isUkPersonal -> "070 PERSONAL FORWARDING TRAP" to Tone.DANGER
        isUkVoip -> "LOCATION-INDEPENDENT VOIP" to Tone.WARNING
        isUkGeographicDdi -> "GEOGRAPHIC / PBX DDI BLOCK" to Tone.NEUTRAL
        else -> "VOICE & SMS CAPABLE" to Tone.ACCENT
    }

    val (tariffBadge, tariffTone) = when {
        isUkPager || isUkPersonal || isUkPremiumOrSpecial -> "OUT-OF-BUNDLE CALLBACK RISK" to Tone.DANGER
        isUkFreephone -> "FREEPHONE" to Tone.ACCENT
        else -> "STANDARD RATE" to Tone.NEUTRAL
    }

    val prefixBlock = when {
        isUk && cleanLocal.startsWith("020") && cleanLocal.length >= 7 ->
            "020 ${cleanLocal.substring(3, 7)} xxxx (10,000-line DDI block)"
        isUk && isUkGeographicDdi && cleanLocal.length >= 7 ->
            "${cleanLocal.take(4)} ${cleanLocal.substring(4, 7)} xxxx (DDI / exchange block)"
        isUk && cleanLocal.startsWith("07") && cleanLocal.length >= 6 ->
            "${cleanLocal.take(5)} / ${cleanLocal.take(6)}xxxxx (Ofcom 07 block)"
        else -> formatsPrefixFallback(normalized)
    }

    val cliAssessment = when {
        isUkPager ->
            "Radiopagers are strictly one-way, receive-only devices. A genuine ${cleanLocal.take(5)} pager cannot place outbound voice calls or send texts — an incoming call from this number proves Caller ID spoofing or a Wangiri callback trap."
        isUkDramaOrUnalloc ->
            "Reserved by Ofcom for TV/drama or currently unallocated. No real subscriber line can originate calls from this number; the Caller ID is guaranteed to be spoofed."
        isUkPersonal ->
            "070 personal numbers forward calls to hidden destinations and are heavily abused for parcel, vehicle-ad, and missed-call scams."
        isUkGeographicDdi ->
            "Geographic/non-geographic blocks (especially 020 3 and 03) are used by NHS trusts, councils, and businesses as Direct-Dial-In (DDI) SIP extensions, where individual desk extensions are unlisted. However, they are also frequently spoofed by outbound call centres."
        else ->
            "Standard two-way numbering range. Can legitimately originate calls, though any number can still be spoofed by automated dialers."
    }

    val callbackAdvice = when {
        isUkPager || isUkPersonal || isUkPremiumOrSpecial ->
            "Do NOT return missed calls to this range. 076, 070, 084, 087, and 09 prefixes are frequently excluded from free monthly mobile minutes and can incur high per-minute or connection charges."
        isUkGeographicDdi ->
            "If you are expecting an appointment (such as an NHS or GP referral), check your NHS App or call the clinic's main published switchboard rather than redialing an unrecognised DDI extension."
        else ->
            "If you do not recognise the caller and no voicemail was left, avoid calling back directly."
    }

    return TelecomTelemetry(
        capabilityBadge = capBadge,
        capabilityTone = capTone,
        tariffBadge = tariffBadge,
        tariffTone = tariffTone,
        prefixBlockLabel = prefixBlock,
        cliAssessment = cliAssessment,
        callbackAdvice = callbackAdvice
    )
}

private fun formatsPrefixFallback(normalized: String): String =
    if (normalized.length > 6) "${normalized.take(6)}…" else normalized

@Composable
fun PhoneLookupScreen(
    viewModel: PhoneLookupViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var note by rememberSaveable { mutableStateOf("") }
    var numverifyKey by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.Start
    ) {
        ScreenHeader(
            eyebrow = "Phone",
            title = "Who's calling?",
            subtitle = "Numbering data, public caller reports and web matches."
        )

        SearchField(
            value = uiState.phoneNumberInput,
            onValueChange = { viewModel.onPhoneNumberInputChanged(it) },
            label = "Phone number",
            placeholder = "020 7946 0958 or +44 20 7946 0958",
            keyboardType = KeyboardType.Phone,
            onSearch = { viewModel.scanNumber() },
            trailing = {
                if (uiState.phoneNumberInput.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onPhoneNumberInputChanged("") }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear input"
                        )
                    }
                }
            },
        )

        Spacer(Modifier.height(Space.md))

        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            AppButton(
                text = "Scan number",
                onClick = { viewModel.scanNumber() },
                modifier = Modifier.weight(1f),
            )
            AppButton(
                text = "Clear",
                onClick = { viewModel.clear() },
                subdued = true,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(Space.xl))

        if (uiState.resultNumber == null && !uiState.isSearching) {
            Notice(
                "Enter a number to check its format, numbering region, estimated line type, public caller reports and indexed web matches. Online checks send the number to the listed third-party sources. Results cannot identify the caller or reveal their live location or private account registrations; caller ID can also be spoofed.",
                tone = Tone.NEUTRAL,
            )
            Spacer(Modifier.height(Space.xl))
        }

        uiState.resultNumber?.let { input ->
            val normalized = PhoneNumberUtils.normalize(input)
            val isUk = PhoneNumberUtils.isValidUk(normalized)
            val areaInfo = PhoneNumberUtils.getAreaInfo(normalized)
            val country = PhoneNumberUtils.getCountry(normalized)
            val timezone = PhoneNumberUtils.getTimezone(normalized)
            val lineType = PhoneNumberUtils.getLineType(normalized)
            val localFormat = if (normalized.startsWith("+44")) "0" + normalized.removePrefix("+44") else normalized
            val formats = PhoneNumberUtils.getFormattedFormats(normalized, localFormat)
            val telemetry = remember(normalized, localFormat) {
                buildTelecomTelemetry(normalized, localFormat)
            }
            val carrierHint = PhoneNumberUtils.getUkCarrierHint(normalized)
            val isValidNumber = PhoneNumberUtils.isValid(normalized)

            uiState.verdict?.let { verdict ->
                VerdictCard(verdict)
                Spacer(modifier = Modifier.height(16.dp))
            }

            PhoneProfileSummaryCard(
                e164 = formats.e164,
                national = formats.national,
                country = country,
                area = areaInfo,
                lineType = lineType,
                timezone = timezone,
                carrier = carrierHint ?: uiState.phoneInfogaResult?.carrier,
                valid = isValidNumber,
                riskLabel = uiState.spamReport?.riskLabel,
            )
            Spacer(modifier = Modifier.height(16.dp))

            SectionCard(
                title = "Ofcom Telecoms & Spoofing Telemetry",
                subtitle = "Outbound call capability, DDI block structure and callback tariff risk",
                tone = telemetry.capabilityTone,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Pill(telemetry.capabilityBadge, tone = telemetry.capabilityTone)
                    Pill(telemetry.tariffBadge, tone = telemetry.tariffTone)
                }
                ResultRow("Estimated Line Class", lineType)
                ResultRow("Prefix / DDI Block", telemetry.prefixBlockLabel)
                carrierHint?.let { ResultRow("Ofcom Range Holder", it) }
                if (isUk && areaInfo != null) {
                    ResultRow("UK Numbering Area", areaInfo)
                }
                Text(
                    text = telemetry.cliAssessment,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = telemetry.callbackAdvice,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (telemetry.tariffTone == Tone.DANGER) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            DeepPhoneFootprintSection(
                report = uiState.deepFootprint,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            val phoniaDorks = OsintTools.getPhoniaTools(normalized)
            val phoneInfogaDorks = OsintTools.getPhoneInfogaDorks(normalized, localFormat)
            val externalTools = OsintTools.getPhoneInfogaExternalTools(normalized, localFormat) +
                OsintTools.getIntelBaseDork(normalized)

            val allDiscoveredPhoneLinks = buildList {
                uiState.searchResults.forEach { add("Web Match: ${it.title}" to it.url) }
                uiState.reputationReports.filter { it.hasReports }.forEach { add("Caller Report: ${it.source}" to it.url) }
                phoniaDorks.forEach { add(it.title to it.url) }
                phoneInfogaDorks.forEach { add(it.title to it.url) }
            }

            UnifiedEvidenceCard(
                report = UnifiedEvidenceBuilder.phone(
                    target = formats.e164,
                    startedAt = uiState.scanStartedAt,
                    searchResults = uiState.searchResults,
                    searchError = uiState.searchError,
                    isSearching = uiState.isSearching,
                    spam = uiState.spamReport,
                    isSpamScanning = uiState.isSpamScanning,
                    reputations = uiState.reputationReports,
                    supportedReputationSources = PhoneReputation.supportedSources(normalized),
                    isReputationScanning = uiState.isReputationScanning,
                    phoneInfoga = uiState.phoneInfogaReport,
                    isPhoneInfogaScanning = uiState.isPhoneInfogaScanning || uiState.isPhoneInfogaServerRunning,
                    numverify = uiState.phoneInfogaResult,
                ),
                onOpenUrl = { openUrl(context, it) },
            )
            Spacer(Modifier.height(16.dp))

            NumberedLinksHubCard(
                title = "Quick Links Queue ($normalized)",
                links = allDiscoveredPhoneLinks,
                onExportPdf = {
                    val sections = buildList {
                        add(
                            PdfSection(
                                "Number Metadata & Telecom Telemetry",
                                listOfNotNull(
                                    "Original Input: $input",
                                    "E.164: ${formats.e164}",
                                    "National: ${formats.national}",
                                    "Country: $country",
                                    "Region: ${areaInfo ?: "N/A"}",
                                    "Line Type: $lineType",
                                    "Prefix / DDI Block: ${telemetry.prefixBlockLabel}",
                                    carrierHint?.let { "Ofcom Range Holder: $it" },
                                    "Capability Verdict: ${telemetry.capabilityBadge}",
                                    "Tariff Verdict: ${telemetry.tariffBadge}",
                                    "CLI Assessment: ${telemetry.cliAssessment}",
                                    "Verdict: ${uiState.verdict?.headline ?: "N/A"} (${uiState.verdict?.summary ?: ""})"
                                )
                            )
                        )
                        uiState.deepFootprint?.let { deep ->
                            if (deep.businessMatches.isNotEmpty()) {
                                add(
                                    PdfSection(
                                        heading = "OpenStreetMap Registered Business Matches",
                                        lines = deep.businessMatches.map { "${it.source}: ${it.value}" }
                                    )
                                )
                            }
                            if (deep.directoryReports.isNotEmpty()) {
                                add(
                                    PdfSection(
                                        heading = "Caller Directory Reports",
                                        lines = deep.directoryReports.flatMap { d ->
                                            listOf("${d.source} (${d.verdictLabel ?: "Listed"}) - ${d.url}") +
                                                d.userComments.map { "  • $it" }
                                        }
                                    )
                                )
                            }
                        }
                        add(
                            PdfSection(
                                "Spam & Caller Reputation",
                                buildList {
                                    uiState.spamReport?.let {
                                        add("${it.source}: Score ${it.scoreLabel} (${it.riskLabel}), ${it.comments ?: 0} comments, ${it.searches ?: 0} searches")
                                        it.callerName?.let { name -> add("Reported Caller Name: $name") }
                                        it.callerType?.let { type -> add("Reported Caller Type: $type") }
                                    }
                                    uiState.reputationReports.forEach { rep ->
                                        add("${rep.source}: ${rep.reportCount} report(s) - ${rep.url}")
                                        rep.samples.forEach { sample -> add("  • $sample") }
                                    }
                                }
                            )
                        )
                        add(
                            PdfSection(
                                "Discovered Links (links.txt)",
                                allDiscoveredPhoneLinks.mapIndexed { idx, (lbl, url) -> "${idx + 1}. $lbl -> $url" }
                            )
                        )
                    }
                    OsintPdfExporter.exportReport(
                        context = context,
                        title = "Phone OSINT Report",
                        target = formats.e164,
                        sections = sections
                    )
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            SectionCard(
                title = "Number breakdown",
                subtitle = "Formats, region and line type from public numbering data",
                tone = Tone.NEUTRAL,
            ) {
                ResultRow("Original", input)
                ResultRow("E.164 Normalized", formats.e164)
                ResultRow("International", formats.international)
                ResultRow("National Format", formats.national)
                ResultRow("RFC3966 Link", formats.rfc3966)
                ResultRow("Country", country)
                ResultRow("Estimated number type", lineType)
                ResultRow("Country time-zone group", timezone)
                if (isUk && areaInfo != null) {
                    ResultRow("UK Region", areaInfo)
                }
                carrierHint?.let {
                    ResultRow("Ofcom Range Block", it)
                }
                Text(
                    "Type, range block and time zone are estimates from public numbering data; they do not prove the current carrier or caller location.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(Modifier.height(Space.lg))

            DorkLauncherCard(
                title = "Phonia & Direct Messaging Triggers",
                subtitle = "Direct WhatsApp, Telegram, Truecaller and Viber checks",
                dorks = phoniaDorks
            )

            Spacer(modifier = Modifier.height(16.dp))

            DorkLauncherCard(
                title = "PhoneInfoga, IntelBase & Reverse Directories",
                subtitle = "Targeted Google dorks, IntelBase hub & reverse lookup directories",
                dorks = phoneInfogaDorks + externalTools
            )

            Spacer(modifier = Modifier.height(16.dp))

            OsintToolSectionCard(
                toolTitle = "PhoneInfoga Native Scan",
                subtitle = "Offline numbering data, plus an optional Numverify carrier lookup with your apilayer key",
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
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Spacer(Modifier.height(Space.sm))
                AppButton(
                    text = "Run native Numverify",
                    onClick = { viewModel.runPhoneInfogaNumverify(numverifyKey) },
                    enabled = numverifyKey.isNotBlank() && !uiState.isPhoneInfogaScanning,
                )
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
                toolTitle = "PhoneInfoga",
                subtitle = "Use your own PhoneInfoga server, or the built-in dork and OVH scanners",
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ) {
                OutlinedTextField(
                    value = uiState.phoneInfogaUrl,
                    onValueChange = viewModel::onPhoneInfogaUrlChanged,
                    label = { Text("PhoneInfoga server (optional)") },
                    placeholder = { Text("http://127.0.0.1:5000") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Space.sm))
                AppButton(
                    text = "Run PhoneInfoga",
                    onClick = { viewModel.runPhoneInfogaServer() },
                    enabled = !uiState.isPhoneInfogaServerRunning,
                )

                val piReport = uiState.phoneInfogaReport
                piReport?.note?.let { noteText ->
                    Text(
                        noteText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
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
                            if (scan.labeledLinks.isNotEmpty()) {
                                scan.labeledLinks.take(30).forEach { (label, link) ->
                                    TextButton(onClick = { openUrl(context, link) }) {
                                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            } else {
                                scan.links.take(12).forEach { link ->
                                    TextButton(onClick = { openUrl(context, link) }) {
                                        Text(link, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (scan.labeledLinks.isEmpty() && scan.links.size > 12) {
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
            SectionCard(
                title = "Save to private history",
                subtitle = "Stored only on this phone. No contacts, call log or upload is used.",
                tone = Tone.NEUTRAL,
            ) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Personal note (optional)") },
                    placeholder = { Text("What did the caller say?") },
                    minLines = 2,
                    maxLines = 4,
                    shape = MaterialTheme.shapes.medium,
                )
                Spacer(Modifier.height(Space.sm))
                AppButton(
                    text = "Save scan",
                    onClick = {
                        viewModel.saveCurrentToHistory(note)
                        note = ""
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

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
                            report.callerName?.let { ResultRow("Reported Caller Name", it) }
                            report.callerType?.let { ResultRow("Caller Type", it) }
                            report.comments?.let { ResultRow("Community Reports", it.toString()) }
                            report.searches?.let { ResultRow("Times Searched", it.toString()) }
                            report.operatorHint?.let { ResultRow("Reported Operator", it) }
                            report.latestActivity?.let { ResultRow("Latest Activity", it) }
                            report.location?.let { ResultRow("Reported Location", it) }
                            report.country?.let { ResultRow("Country", it) }
                            report.searchVelocityNote?.let { velocityWarning ->
                                Spacer(Modifier.height(6.dp))
                                Notice(velocityWarning, tone = Tone.WARNING)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val supportedRepSources = PhoneReputation.supportedSources(normalized)
            OsintToolSectionCard(
                toolTitle = "Community Report Databases",
                subtitle = if (supportedRepSources.isEmpty())
                    "Additional community sources cover UK (+44) and North American (+1) numbers"
                else "Live user reports from ${supportedRepSources.joinToString(", ")}",
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

            Text(
                text = "WEB SEARCH",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.4.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = Space.sm)
            )

            if (uiState.isSearching) {
                LoadingRow("Searching public databases…")
            } else if (uiState.searchError != null) {
                Notice(uiState.searchError!!, tone = Tone.DANGER)
            } else if (uiState.hasSearched && uiState.searchResults.isEmpty()) {
                Notice("No indexed web matches found for this number or its prefix block.", tone = Tone.NEUTRAL)
            } else {
                if (uiState.isPrefixWebFallback && uiState.prefixSearched != null) {
                    Notice(
                        "No exact web matches for $localFormat. Showing prefix-block matches for \"${uiState.prefixSearched}\" (identifies hospital/corporate PBX switchboards and active prefix spoofing waves).",
                        tone = Tone.WARNING
                    )
                    Spacer(Modifier.height(Space.sm))
                }
                uiState.searchResults.forEach { result ->
                    Surface(
                        onClick = { openUrl(context, result.url) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = result.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = result.snippet,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "↗",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
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
            AppButton(
                text = "Check all sources",
                onClick = { viewModel.runSourceHealth(numverifyKey) },
                enabled = !uiState.isCheckingHealth,
            )

            if (uiState.isCheckingHealth) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 10.dp).size(20.dp), strokeWidth = 2.dp
                )
            }
            uiState.sourceHealth.forEach { result ->
                val tone = when (result.state) {
                    HealthState.OK -> Tone.ACCENT
                    HealthState.NOT_CONFIGURED -> Tone.NEUTRAL
                    HealthState.CHANGED -> Tone.WARNING
                    else -> Tone.DANGER
                }
                val label = when (result.state) {
                    HealthState.OK -> "OK"
                    HealthState.BLOCKED, HealthState.UNREACHABLE -> "FAIL"
                    HealthState.CHANGED -> "CHANGED"
                    HealthState.NOT_CONFIGURED -> "NOT SET"
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Pill(label, tone = tone)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = result.source,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${result.millis} ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = result.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
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

private fun historyStatusTone(status: String): Tone = when (status) {
    "Suspected spam" -> Tone.DANGER
    "Trusted" -> Tone.ACCENT
    else -> Tone.NEUTRAL
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
    Spacer(Modifier.height(Space.xl))
    SectionCard(
        title = "Saved history",
        subtitle = "Local only · up to 50 entries",
        tone = Tone.NEUTRAL,
    ) {
        entries.forEach { entry ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.number,
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Pill(entry.status, tone = historyStatusTone(entry.status))
                    }
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        text = formatter.format(java.util.Date(entry.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (entry.note.isNotBlank()) {
                        Text(
                            text = entry.note,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(
                            onClick = { onRescan(entry.number) },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) { Text("Rescan", style = MaterialTheme.typography.labelMedium) }
                        TextButton(
                            onClick = {
                                val next = when (entry.status) {
                                    "Unknown" -> "Suspected spam"
                                    "Suspected spam" -> "Trusted"
                                    else -> "Unknown"
                                }
                                onStatusChange(entry.id, next)
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) { Text("Status", style = MaterialTheme.typography.labelMedium) }
                        TextButton(
                            onClick = {
                                editedNote = entry.note
                                editingEntry = entry
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) { Text("Note", style = MaterialTheme.typography.labelMedium) }
                        TextButton(
                            onClick = { onDelete(entry.id) },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) { Text("Delete", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
        }
        if (entries.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showClearDialog = true }) { Text("Clear all") }
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
