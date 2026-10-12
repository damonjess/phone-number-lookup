package com.example.phone_number_lookup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.phone_number_lookup.ui.theme.PhonenumberlookupTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhonenumberlookupTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NumberFinderApp(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun NumberFinderApp(modifier: Modifier = Modifier) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Phone Lookup", "Username & Email OSINT", "GitHub OSINT")

    Column(modifier = modifier.fillMaxSize()) {
        SecondaryTabRow(selectedTabIndex = selectedTabIndex) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title, fontWeight = FontWeight.Bold) }
                )
            }
        }

        when (selectedTabIndex) {
            0 -> PhoneLookupTab()
            1 -> UsernameAndEmailOsintTab()
            else -> GitHubOsintTab()
        }
    }
}

@Composable
fun PhoneLookupTab() {
    var phoneNumberInput by remember { mutableStateOf("") }
    var resultNumber by remember { mutableStateOf<String?>(null) }
    
    var searchResults by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var spamReport by remember { mutableStateOf<SpamReport?>(null) }
    var isSpamScanning by remember { mutableStateOf(false) }
    var reputationReports by remember { mutableStateOf<List<ReputationReport>>(emptyList()) }
    var isReputationScanning by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
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
            value = phoneNumberInput,
            onValueChange = { phoneNumberInput = it },
            label = { Text("Enter phone number") },
            placeholder = { Text("e.g. 01782 447307 or +44 1782...") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                if (phoneNumberInput.isNotEmpty()) {
                    TextButton(onClick = { phoneNumberInput = "" }) {
                        Text("✕")
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
                onClick = {
                    if (phoneNumberInput.isNotBlank()) {
                        resultNumber = phoneNumberInput
                        hasSearched = true
                        isSearching = true
                        isSpamScanning = true
                        searchResults = emptyList()
                        spamReport = null
                        reputationReports = emptyList()
                        isReputationScanning = true

                        val normalized = PhoneNumberUtils.normalize(phoneNumberInput)
                        val localFormat = if (normalized.startsWith("+44")) "0" + normalized.removePrefix("+44") else normalized

                        coroutineScope.launch {
                            val items = SearchRepository.searchWeb(normalized, localFormat)
                            searchResults = items
                            isSearching = false
                        }

                        coroutineScope.launch {
                            spamReport = SpamScanner.lookup(normalized)
                            isSpamScanning = false
                        }

                        coroutineScope.launch {
                            reputationReports = PhoneReputation.lookup(normalized)
                            isReputationScanning = false
                        }
                    }
                },
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) {
                Text("Scan Number")
            }

            OutlinedButton(
                onClick = {
                    phoneNumberInput = ""
                    resultNumber = null
                    hasSearched = false
                    searchResults = emptyList()
                    isSearching = false
                    spamReport = null
                    isSpamScanning = false
                    reputationReports = emptyList()
                    isReputationScanning = false
                },
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            ) {
                Text("Clear")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        resultNumber?.let { input ->
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
                    isSpamScanning -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Querying tellows caller database...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    spamReport != null -> {
                        val report = spamReport!!
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

            // Extra community caller-report databases (each verified live, no API key)
            OsintToolSectionCard(
                toolTitle = "Community Report Databases",
                subtitle = if (PhoneReputation.supportedSources(normalized).isEmpty())
                    "WhoCallsMe & 800notes only index North American (+1) numbers"
                else "Live user reports from WhoCallsMe and 800notes",
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                when {
                    isReputationScanning -> Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Querying community call databases...", style = MaterialTheme.typography.bodyMedium)
                    }
                    reputationReports.isEmpty() -> Text(
                        "No additional no-key source applies to this number. tellows remains the primary lookup above.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    else -> reputationReports.forEach { rep ->
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

            if (isSearching) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Searching public databases...")
                }
            } else if (hasSearched && searchResults.isEmpty()) {
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
                searchResults.forEach { result ->
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

@Composable
fun UsernameAndEmailOsintTab() {
    var subTabState by remember { mutableIntStateOf(0) } // 0: Username (Blackbird / Tookie), 1: Email (Blackbird / Epieos)
    var targetInput by remember { mutableStateOf("") }
    var activeTarget by remember { mutableStateOf<String?>(null) }

    var liveHits by remember { mutableStateOf<List<UsernameHit>>(emptyList()) }
    var liveSitesChecked by remember { mutableIntStateOf(0) }
    var isLiveScanning by remember { mutableStateOf(false) }
    var liveError by remember { mutableStateOf<String?>(null) }
    var breachReport by remember { mutableStateOf<BreachReport?>(null) }
    var isBreachScanning by remember { mutableStateOf(false) }

    // Username scan state
    var scanDepth by remember { mutableIntStateOf(0) }
    var scanProgress by remember { mutableIntStateOf(0) }
    var scanTotal by remember { mutableIntStateOf(0) }
    var datasetSizes by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var unreachableCount by remember { mutableIntStateOf(0) }

    // Email registration (accounts found for the address) state
    var registeredSites by remember { mutableStateOf<List<RegistrationHit>>(emptyList()) }
    var isRegistrationScanning by remember { mutableStateOf(false) }
    var registrationChecked by remember { mutableIntStateOf(0) }
    var emailUnreachable by remember { mutableIntStateOf(0) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
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
                selected = subTabState == 0,
                onClick = {
                    subTabState = 0; targetInput = ""; activeTarget = null
                    liveHits = emptyList(); liveError = null; liveSitesChecked = 0
                },
                label = { Text("Username OSINT") }
            )
            FilterChip(
                selected = subTabState == 1,
                onClick = {
                    subTabState = 1; targetInput = ""; activeTarget = null
                    breachReport = null; registeredSites = emptyList()
                    liveHits = emptyList(); liveSitesChecked = 0
                },
                label = { Text("Email OSINT") }
            )
        }

        if (subTabState == 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Quick (150)" to 0, "Standard (300)" to 1, "Full sweep" to 2).forEach { (label, index) ->
                    FilterChip(
                        selected = scanDepth == index,
                        onClick = { scanDepth = index },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
        }

        OutlinedTextField(
            value = targetInput,
            onValueChange = { targetInput = it },
            label = { Text(if (subTabState == 0) "Enter Username (e.g. dnkirby)" else "Enter Email (e.g. alex.smith@gmail.com)") },
            placeholder = { Text(if (subTabState == 0) "e.g. dnkirby or john_doe" else "e.g. alex.smith@gmail.com") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                if (targetInput.isNotEmpty()) {
                    TextButton(onClick = { targetInput = "" }) {
                        Text("✕")
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                val target = targetInput.trim()
                if (target.isNotBlank()) {
                    activeTarget = target
                    if (subTabState == 0) {
                        isLiveScanning = true
                        liveHits = emptyList()
                        liveSitesChecked = 0
                        liveError = null
                        scanProgress = 0
                        scanTotal = 0
                        coroutineScope.launch {
                            val limit = when (scanDepth) {
                                0 -> 150
                                1 -> UsernameScanner.DEFAULT_LIMIT
                                else -> UsernameScanner.FULL_SCAN
                            }
                            val outcome = UsernameScanner.scan(target, limit = limit) { checked, total ->
                                scanProgress = checked
                                scanTotal = total
                            }
                            liveHits = outcome.confirmed
                            liveSitesChecked = outcome.sitesChecked
                            datasetSizes = outcome.datasetSizes
                            unreachableCount = outcome.unreachable
                            if (outcome.sitesChecked == 0) {
                                liveError = "Could not load any site database. Check your connection and retry."
                            }
                            isLiveScanning = false
                        }
                    } else {
                        isBreachScanning = true
                        breachReport = null
                        isRegistrationScanning = true
                        registeredSites = emptyList()
                        registrationChecked = 0
                        emailUnreachable = 0
                        liveHits = emptyList()
                        liveSitesChecked = 0

                        coroutineScope.launch {
                            val outcome = EmailRegistrationScanner.scan(target) { checked, _ ->
                                registrationChecked = checked
                            }
                            registeredSites = outcome.registered
                            emailUnreachable = outcome.unreachable
                            if (outcome.error != null) liveError = outcome.error
                            isRegistrationScanning = false
                        }

                        coroutineScope.launch {
                            breachReport = EmailScanner.check(target)
                            isBreachScanning = false
                        }

                        // The handle before the @ is very often reused as a username elsewhere.
                        EmailRegistrationScanner.localPart(target)?.let { handle ->
                            coroutineScope.launch {
                                isLiveScanning = true
                                val outcome = UsernameScanner.scan(handle, limit = 150)
                                liveHits = outcome.confirmed
                                liveSitesChecked = outcome.sitesChecked
                                isLiveScanning = false
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (subTabState == 0) "Run Live Username Scanner" else "Run Live Breach Check")
        }

        Spacer(modifier = Modifier.height(16.dp))

        activeTarget?.let { target ->
            if (subTabState == 0) {
                // Username Scan
                val platforms = OsintTools.getBlackbirdUsernameScan(target)
                val tookieDorks = OsintTools.getTookieBribDorks(target)

                // Live enumeration across hundreds of sites (What's My Name? dataset)
                OsintToolSectionCard(
                    toolTitle = "Live Username Scan ($target)",
                    subtitle = if (liveSitesChecked > 0)
                        "$liveSitesChecked real HTTP checks across the merged WhatsMyName, Sherlock and Tookie databases"
                    else "Real HTTP checks across the merged community site databases",
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    when {
                        isLiveScanning -> Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                if (scanTotal > 0) "Scanned $scanProgress of $scanTotal sites..."
                                else "Downloading site databases...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        liveError != null -> Text(liveError!!, style = MaterialTheme.typography.bodyMedium)
                        liveHits.isEmpty() -> Column {
                            Text(
                                "No public profiles matched this username.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Scanned $liveSitesChecked sites" +
                                    if (unreachableCount > 0) ", $unreachableCount unreachable." else ".",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        else -> {
                            Text(
                                "${liveHits.size} profile(s) found",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            if (unreachableCount > 0) {
                                Text(
                                    "$unreachableCount site(s) blocked the request and were not counted.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            liveHits.forEach { hit ->
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
                            if (datasetSizes.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Databases: " + datasetSizes.entries.joinToString(", ") { "${it.key} ${it.value}" },
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
                        isRegistrationScanning -> Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Checking signup endpoints ($registrationChecked done)...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        registeredSites.isEmpty() -> Text(
                            "No service reported an existing account for this address.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        else -> {
                            Text(
                                "${registeredSites.size} service(s) already have an account for this email",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            registeredSites.forEach { hit ->
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
                    if (emailUnreachable > 0 && !isRegistrationScanning) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "$emailUnreachable site(s) blocked the request and were not counted.",
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
                    val report = breachReport
                    when {
                        isBreachScanning -> Row(
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

                // The handle before the @ is frequently reused, so scan it as a username too.
                OsintToolSectionCard(
                    toolTitle = "Handle Footprint",
                    subtitle = "The part before the @ scanned as a username across every site database",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    val handle = EmailRegistrationScanner.localPart(target)
                    when {
                        handle == null -> Text("Could not derive a handle from this address.", style = MaterialTheme.typography.bodyMedium)
                        isLiveScanning -> Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Scanning sites for \"$handle\"...", style = MaterialTheme.typography.bodyMedium)
                        }
                        liveHits.isEmpty() -> Text(
                            "No public profile matched the handle \"$handle\". (Scanned $liveSitesChecked sites.)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        else -> {
                            Text(
                                "${liveHits.size} profile(s) found for \"$handle\"",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            liveHits.forEach { hit ->
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

@Composable
fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.2f)
        )
    }
}

@Composable
fun OsintToolSectionCard(
    toolTitle: String,
    subtitle: String,
    containerColor: androidx.compose.ui.graphics.Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = toolTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            content()
        }
    }
}

@Composable
fun DorkButton(dork: OsintDork, context: Context) {
    OutlinedCard(
        onClick = { openUrl(context, dork.url) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dork.title,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = dork.toolSource,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = dork.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

fun openUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
