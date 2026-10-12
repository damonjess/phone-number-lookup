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
    val tabs = listOf("Phone Lookup", "Username & Email OSINT")

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
            text = "Integrated tools: PhoneInfoga, Phonia, IntelBase & DuckDuckGo",
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
                        searchResults = emptyList()

                        val normalized = PhoneNumberUtils.normalize(phoneNumberInput)
                        val localFormat = if (normalized.startsWith("+44")) "0" + normalized.removePrefix("+44") else normalized

                        coroutineScope.launch {
                            val items = SearchRepository.searchWeb(normalized, localFormat)
                            searchResults = items
                            isSearching = false
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
            text = "Integrated: Blackbird, Tookie-OSINT, Brib & IntelBase",
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
                onClick = { subTabState = 0; targetInput = ""; activeTarget = null },
                label = { Text("Username OSINT") }
            )
            FilterChip(
                selected = subTabState == 1,
                onClick = { subTabState = 1; targetInput = ""; activeTarget = null },
                label = { Text("Email OSINT") }
            )
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
                if (targetInput.isNotBlank()) {
                    activeTarget = targetInput.trim()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (subTabState == 0) "Run Blackbird & Tookie Username Scan" else "Run Blackbird & Epieos Email Scan")
        }

        Spacer(modifier = Modifier.height(16.dp))

        activeTarget?.let { target ->
            if (subTabState == 0) {
                // Username Scan
                val platforms = OsintTools.getBlackbirdUsernameScan(target)
                val tookieDorks = OsintTools.getTookieBribDorks(target)

                OsintToolSectionCard(
                    toolTitle = "Blackbird Username Scanner ($target)",
                    subtitle = "Checked across 15+ major platforms & social networks",
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
                    toolTitle = "Tookie-OSINT & Brib Target Scanner",
                    subtitle = "Alias footprinting across forums & paste dumps",
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
                    toolTitle = "Blackbird Email Scanner ($target)",
                    subtitle = "Leak databases, HaveIBeenPwned & Epieos reverse lookup",
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
