package com.example.phone_number_lookup.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.phone_number_lookup.AttributedProfileLink
import com.example.phone_number_lookup.AttributedValue
import com.example.phone_number_lookup.BreachReport
import com.example.phone_number_lookup.CommentHit
import com.example.phone_number_lookup.EmailAttributionReport
import com.example.phone_number_lookup.EmailDomainReport
import com.example.phone_number_lookup.GoogleCommentReport
import com.example.phone_number_lookup.GoogleCommentScraper
import com.example.phone_number_lookup.GoogleMetadataReport
import com.example.phone_number_lookup.OsintDatasets
import com.example.phone_number_lookup.OsintDork
import com.example.phone_number_lookup.RegistrationHit
import com.example.phone_number_lookup.TimelineEvent
import com.example.phone_number_lookup.UsernameHit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmailIntelBaseReport(
    target: String,
    attribution: EmailAttributionReport?,
    registrations: List<RegistrationHit>,
    handleHits: List<UsernameHit>,
    breachReport: BreachReport?,
    domainReport: EmailDomainReport?,
    googleMetadata: GoogleMetadataReport?,
    isLoading: Boolean,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    googleCommentReport: GoogleCommentReport? = null,
    isCommentScraping: Boolean = false,
) {
    val names = remember(attribution, googleMetadata) {
        val list = mutableListOf<AttributedValue>()
        attribution?.namesFound?.let { list.addAll(it) }
        googleMetadata?.fullName?.let { list.add(AttributedValue(it, "Google")) }
        list.distinctBy { "${it.value.lowercase()}|${it.source.lowercase()}" }
    }

    val usernames = remember(attribution, googleMetadata, handleHits) {
        val list = mutableListOf<AttributedValue>()
        attribution?.usernamesFound?.let { list.addAll(it) }
        googleMetadata?.playGamesUsername?.let { list.add(AttributedValue(it, "Google Play Games")) }
        handleHits.forEach { hit ->
            if (hit.verificationStatus == "Matched") {
                list.add(AttributedValue(hit.queriedName.ifBlank { target.substringBefore('@') }, hit.site))
            }
        }
        list.distinctBy { "${it.value.lowercase()}|${it.source.lowercase()}" }
    }

    val photos = remember(attribution, googleMetadata) {
        buildList {
            attribution?.avatarUrls?.let { addAll(it) }
            googleMetadata?.avatarUrl?.let { add(it) }
        }.filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
    }

    val profileLinks = remember(attribution, registrations, handleHits, googleMetadata) {
        val list = mutableListOf<AttributedProfileLink>()
        attribution?.profileLinks?.let { list.addAll(it) }
        googleMetadata?.profileUrl?.let {
            list.add(AttributedProfileLink(googleMetadata.fullName ?: "Google Profile", "Google Maps", it))
        }
        registrations.forEach { hit ->
            if (hit.profileUrl.startsWith("http") && hit.verificationStatus == "Registered response") {
                list.add(
                    AttributedProfileLink(
                        handle = hit.site,
                        platform = hit.site,
                        url = OsintDatasets.cleanProfileUrl(hit.site, hit.profileUrl)
                    )
                )
            }
        }
        handleHits.forEach { hit ->
            if (hit.profileUrl.startsWith("http") && hit.verificationStatus == "Matched") {
                list.add(
                    AttributedProfileLink(
                        handle = hit.queriedName.ifBlank { target.substringBefore('@') },
                        platform = hit.site,
                        url = OsintDatasets.cleanProfileUrl(hit.site, hit.profileUrl)
                    )
                )
            }
        }
        list.distinctBy { it.url.lowercase().trimEnd('/') }
    }

    val locations = remember(attribution, googleMetadata) {
        val list = mutableListOf<AttributedValue>()
        attribution?.locationsFound?.let { list.addAll(it) }
        googleMetadata?.mappedLocations?.forEach { list.add(AttributedValue(it, "Google Maps")) }
        list.distinctBy { "${it.value.lowercase()}|${it.source.lowercase()}" }
    }

    val phones = remember(attribution) {
        attribution?.phonesFound.orEmpty()
            .distinctBy { "${it.value.lowercase()}|${it.source.lowercase()}" }
    }

    val timelineEvents = remember(attribution, breachReport) {
        val list = mutableListOf<TimelineEvent>()
        attribution?.timeline?.let { list.addAll(it) }
        breachReport?.details?.forEach { detail ->
            detail.breachDate?.let { date ->
                list.add(TimelineEvent(date, detail.name, "Data Breach"))
            }
        }
        breachReport?.breachNames?.forEach { breachName ->
            if (list.none { it.label.equals(breachName, ignoreCase = true) }) {
                val yearMatch = Regex("""\b(20\d{2}(?:-\d{2}-\d{2})?)\b""").find(breachName)?.groupValues?.get(1)
                list.add(TimelineEvent(yearMatch ?: "Recorded", breachName, "Data Breach"))
            }
        }
        list.distinctBy { "${it.date}|${it.label}|${it.source}" }
            .sortedByDescending { it.date }
    }

    val allRegistrations = remember(registrations) {
        registrations.distinctBy { "${it.site.lowercase()}|${it.verificationStatus}" }
    }

    val breachNames = remember(breachReport) {
        breachReport?.breachNames.orEmpty().distinct()
    }

    val comments = remember(googleCommentReport) {
        googleCommentReport?.comments.orEmpty()
    }

    val commentDorks = remember(target, googleCommentReport) {
        googleCommentReport?.dorkLinks?.ifEmpty {
            GoogleCommentScraper.buildCommentDorkLinks(target, target.substringBefore('@'))
        } ?: GoogleCommentScraper.buildCommentDorkLinks(target, target.substringBefore('@'))
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        IntelBaseHeaderCard(
            target = target,
            namesCount = names.size,
            usernamesCount = usernames.size,
            phonesCount = phones.size,
            commentsCount = comments.size,
            locationsCount = locations.size,
            timelineCount = timelineEvents.size,
            breachesCount = breachNames.size,
            isLoading = isLoading || isCommentScraping,
        )

        IntelBaseSection(
            title = "Names",
            count = names.size,
            isLoading = isLoading && names.isEmpty(),
            emptyMessage = "No public name records returned by checked sources.",
        ) {
            names.forEachIndexed { index, name ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = name.value,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = true,
                    )
                    Pill(text = name.source, tone = Tone.INFO)
                }
                if (index < names.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                }
            }
        }

        IntelBaseSection(
            title = "Usernames",
            count = usernames.size,
            isLoading = isLoading && usernames.isEmpty(),
            emptyMessage = "No username handles returned by checked sources.",
        ) {
            usernames.forEachIndexed { index, item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = item.value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = true,
                    )
                    Pill(text = item.source, tone = Tone.ACCENT)
                }
                if (index < usernames.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                }
            }
        }

        IntelBaseSection(
            title = "Phone Numbers",
            count = phones.size,
            isLoading = isLoading && phones.isEmpty(),
            emptyMessage = "No phone numbers or recovery phones returned by checked sources.",
        ) {
            phones.forEachIndexed { index, phone ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = phone.value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = true,
                    )
                    Pill(text = phone.source, tone = Tone.WARNING)
                }
                if (index < phones.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                }
            }
        }

        if (photos.isNotEmpty() || (isLoading && names.isEmpty())) {
            IntelBaseSection(
                title = "Profile Pictures",
                count = photos.size,
                isLoading = isLoading && photos.isEmpty(),
                emptyMessage = "No public profile pictures found.",
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    photos.forEachIndexed { idx, url ->
                        AsyncImage(
                            model = url,
                            contentDescription = "Discovered avatar ${idx + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpenUrl(url) },
                        )
                    }
                }
            }
        }

        if (profileLinks.isNotEmpty() || (isLoading && names.isEmpty())) {
            IntelBaseSection(
                title = "Profile Links",
                count = profileLinks.size,
                isLoading = isLoading && profileLinks.isEmpty(),
                emptyMessage = "No public profile links discovered.",
            ) {
                profileLinks.forEachIndexed { index, link ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpenUrl(link.url) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${link.handle} (${link.platform})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                softWrap = true,
                            )
                            Text(
                                text = link.url,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                softWrap = true,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "↗",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (index < profileLinks.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    }
                }
            }
        }

        GoogleEmailCommentsSection(
            comments = comments,
            statusText = googleCommentReport?.googleDirectStatus,
            dorkLinks = commentDorks,
            isLoading = isCommentScraping && comments.isEmpty(),
            onOpenUrl = onOpenUrl,
        )

        IntelBaseSection(
            title = "Locations",
            count = locations.size,
            isLoading = isLoading && locations.isEmpty(),
            emptyMessage = "No public location signals or review locations returned.",
        ) {
            var expanded by rememberSaveable { mutableStateOf(true) }
            val displayedLocations = if (expanded) locations else locations.take(4)

            displayedLocations.forEachIndexed { index, loc ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = "📍",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 6.dp, top = 2.dp)
                        )
                        Text(
                            text = loc.value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            softWrap = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Pill(text = loc.source, tone = Tone.INFO)
                }
                if (index < displayedLocations.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                }
            }

            if (locations.size > 4) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { expanded = !expanded }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (expanded) "Show less" else "Show ${locations.size - 4} more",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (expanded) "▲" else "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        IntelBaseSection(
            title = "Activity Timeline",
            count = timelineEvents.size,
            isLoading = isLoading && timelineEvents.isEmpty(),
            emptyMessage = "No dated activity signals returned.",
        ) {
            var timelineExpanded by rememberSaveable { mutableStateOf(true) }
            val visibleEvents = if (timelineExpanded) timelineEvents else timelineEvents.take(8)

            visibleEvents.forEachIndexed { index, event ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = formatIntelBaseDate(event.date),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        softWrap = true,
                        modifier = Modifier.width(96.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = event.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            softWrap = true,
                        )
                        Text(
                            text = event.source,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            softWrap = true,
                        )
                    }
                }
                if (index < visibleEvents.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                }
            }

            if (timelineEvents.size > 8) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { timelineExpanded = !timelineExpanded }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (timelineExpanded) "Show less timeline" else "View timeline (${timelineEvents.size} events)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        IntelBaseSection(
            title = "Registrations",
            count = allRegistrations.size,
            isLoading = isLoading && allRegistrations.isEmpty(),
            emptyMessage = "No account registrations detected on checked service endpoints.",
        ) {
            var regFilter by remember { mutableStateOf("") }
            if (allRegistrations.size > 8) {
                OutlinedTextField(
                    value = regFilter,
                    onValueChange = { regFilter = it },
                    placeholder = { Text("Filter registrations…") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                )
            }
            val filtered = allRegistrations.filter {
                regFilter.isBlank() || it.site.contains(regFilter, ignoreCase = true) ||
                    it.evidence.contains(regFilter, ignoreCase = true)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                filtered.forEach { hit ->
                    val isConfirmed = hit.verificationStatus == "Registered response"
                    val cleanUrl = OsintDatasets.cleanProfileUrl(hit.site, hit.profileUrl)
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (cleanUrl.startsWith("http")) Modifier.clickable { onOpenUrl(cleanUrl) }
                                else Modifier
                            ),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        border = BorderStroke(
                            1.dp,
                            if (isConfirmed) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            else Tone.WARNING.color().copy(alpha = 0.35f)
                        ),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (isConfirmed) "✓" else "~",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isConfirmed) MaterialTheme.colorScheme.primary else Tone.WARNING.color(),
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = hit.site,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    softWrap = true,
                                )
                            }
                            if ("Recovery " in hit.evidence) {
                                Text(
                                    text = hit.evidence.substringAfter("• ").trim(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    softWrap = true,
                                )
                            }
                        }
                    }
                }
            }
        }

        IntelBaseSection(
            title = "Data Breaches",
            count = breachNames.size,
            tone = if (breachNames.isNotEmpty() || attribution?.stealerProfile != null) Tone.DANGER else Tone.NEUTRAL,
            isLoading = isLoading && breachNames.isEmpty(),
            emptyMessage = "No exposure records returned by checked breach databases.",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatTile(
                    value = breachNames.size.toString(),
                    label = "AMOUNT",
                    tone = if (breachNames.isNotEmpty()) Tone.DANGER else Tone.NEUTRAL,
                    modifier = Modifier.weight(1f),
                )
                val sourceCount = (if (breachNames.isNotEmpty()) 1 else 0) + (if (attribution?.stealerProfile != null) 1 else 0)
                StatTile(
                    value = sourceCount.toString(),
                    label = "SOURCES",
                    tone = if (sourceCount > 0) Tone.WARNING else Tone.NEUTRAL,
                    modifier = Modifier.weight(1f),
                )
            }

            attribution?.stealerProfile?.let { sp ->
                Spacer(Modifier.height(10.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = Tone.DANGER.color().copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, Tone.DANGER.color().copy(alpha = 0.35f)),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "HudsonRock Infostealer Telemetry",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Tone.DANGER.color(),
                        )
                        val usersLabel = sp.allUsernames.ifEmpty { listOfNotNull(sp.computerUsername) }.joinToString(", ")
                        val ipsLabel = sp.allMaskedIps.ifEmpty { listOfNotNull(sp.maskedIp) }.joinToString(", ")
                        val datesLabel = sp.allDates.ifEmpty { listOfNotNull(sp.dateCompromised) }.joinToString(", ")
                        if (usersLabel.isNotBlank()) IntelBaseDetailRow("Username / Host", usersLabel)
                        if (ipsLabel.isNotBlank()) IntelBaseDetailRow("IP Address(es)", ipsLabel)
                        sp.operatingSystem?.let { IntelBaseDetailRow("Operating System", it) }
                        if (datesLabel.isNotBlank()) IntelBaseDetailRow("Compromise Date(s)", datesLabel)
                        if (sp.antiviruses.isNotEmpty()) IntelBaseDetailRow("Antivirus", sp.antiviruses.joinToString(", "))
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        text = "Breach records are masked here",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Names and usernames are shown in full. Passwords, emails, IP addresses, phone numbers and hashes are masked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        softWrap = true,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            breachNames.forEachIndexed { index, breach ->
                val baseName = breach.substringBefore(" (").trim()
                val detail = breachReport?.details?.firstOrNull {
                    it.name.equals(baseName, ignoreCase = true) || it.name.equals(breach, ignoreCase = true)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = breach,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Tone.DANGER.color(),
                            modifier = Modifier.weight(1f),
                            softWrap = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        Pill(text = detail?.breachDate ?: "Exposed", tone = Tone.DANGER)
                    }
                    if (detail != null && detail.dataClasses.isNotEmpty()) {
                        Text(
                            text = "Exposed fields: ${detail.dataClasses.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            softWrap = true,
                        )
                    }
                }
                if (index < breachNames.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                }
            }
        }

        domainReport?.let { domain ->
            var domainExpanded by rememberSaveable { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
            ) {
                Column(Modifier.padding(Space.lg)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { domainExpanded = !domainExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Domain & Mail Gateway",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "MX, SPF, DMARC configuration and quality indicators",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                softWrap = true,
                            )
                        }
                        Text(
                            text = if (domainExpanded) "▲" else "▼",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    AnimatedVisibility(visible = domainExpanded) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            IntelBaseDetailRow("Domain accepts mail", if (domain.acceptsMail == true) "Yes (MX verified)" else "No / Unverified")
                            IntelBaseDetailRow("MX Provider", domain.mxProvider ?: "Standard MX Gateway")
                            IntelBaseDetailRow("Domain Quality Score", "${domain.qualityScore ?: 50} / 100")
                            IntelBaseDetailRow("SPF Record", if (domain.spf == true) "Configured" else "Missing")
                            IntelBaseDetailRow("DMARC Record", if (domain.dmarc == true) "Configured" else "Missing")
                            if (domain.disposable) {
                                Text(
                                    text = "⚠️ Warning: Disposable or temporary email provider.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    softWrap = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoogleEmailCommentsSection(
    comments: List<CommentHit>,
    statusText: String?,
    dorkLinks: List<OsintDork>,
    isLoading: Boolean,
    onOpenUrl: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val visibleComments = if (expanded) comments else comments.take(6)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Tone.ACCENT.color().copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.padding(Space.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Google & Web Comments",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = statusText ?: "Scrapes Google Search, Google Maps Reviews, Reddit, HackerNews & GitHub for comments made by this email or its handles",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        softWrap = true,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Pill(
                    text = if (isLoading) "…" else comments.size.toString(),
                    tone = if (comments.isNotEmpty()) Tone.ACCENT else Tone.NEUTRAL,
                )
            }

            Spacer(Modifier.height(10.dp))

            when {
                isLoading -> LoadingRow("Scraping Google Search & public comment indexes for your email and handles…")
                comments.isEmpty() -> Notice(
                    "No directly indexed comments returned from unauthenticated scrape. Use the Google Comment Dork launchers below to view live Google comment results in your browser.",
                    tone = Tone.NEUTRAL,
                )
                else -> {
                    visibleComments.forEachIndexed { index, hit ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenUrl(hit.url) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Pill(text = hit.platform, tone = Tone.ACCENT)
                                    Pill(text = hit.authorMatch, tone = Tone.INFO)
                                    hit.date?.takeIf { it.isNotBlank() }?.let { d ->
                                        Pill(text = d, tone = Tone.NEUTRAL)
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "↗",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                text = hit.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                softWrap = true,
                            )
                            if (hit.commentSnippet.isNotBlank()) {
                                Text(
                                    text = "\"${hit.commentSnippet}\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    softWrap = true,
                                )
                            }
                            Text(
                                text = "${hit.sourceEngine} · ${hit.url}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                softWrap = true,
                            )
                        }
                        if (index < visibleComments.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        }
                    }

                    if (comments.size > 6) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { expanded = !expanded }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (expanded) "Show fewer comments" else "Show all ${comments.size} comments (${comments.size - 6} more)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            if (dorkLinks.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "LIVE GOOGLE COMMENT SEARCH DORKS",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                dorkLinks.forEach { dork ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpenUrl(dork.url) },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = dork.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    softWrap = true,
                                )
                                Text(
                                    text = dork.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    softWrap = true,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Google ↗",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntelBaseHeaderCard(
    target: String,
    namesCount: Int,
    usernamesCount: Int,
    phonesCount: Int,
    commentsCount: Int,
    locationsCount: Int,
    timelineCount: Int,
    breachesCount: Int,
    isLoading: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(Space.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "IntelBase",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Pill(
                    text = if (isLoading) "SCANNING…" else "DOSSIER",
                    tone = if (isLoading) Tone.WARNING else Tone.ACCENT,
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = target,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = true,
            )

            Spacer(Modifier.height(8.dp))

            val stats = buildList {
                if (namesCount > 0) add("$namesCount Names")
                if (usernamesCount > 0) add("$usernamesCount Usernames")
                if (phonesCount > 0) add("$phonesCount Phones")
                if (commentsCount > 0) add("$commentsCount Comments")
                if (locationsCount > 0) add("$locationsCount Locations")
                if (timelineCount > 0) add("$timelineCount Timeline events")
                if (breachesCount > 0) add("$breachesCount Breaches")
            }
            if (stats.isNotEmpty()) {
                Text(
                    text = stats.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = true,
                )
            }
        }
    }
}

@Composable
private fun IntelBaseSection(
    title: String,
    count: Int,
    tone: Tone = Tone.ACCENT,
    isLoading: Boolean = false,
    emptyMessage: String = "No records returned.",
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, tone.color().copy(alpha = 0.25f)),
    ) {
        Column(modifier = Modifier.padding(Space.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = true,
                    modifier = Modifier.weight(1f),
                )
                if (count > 0) {
                    Spacer(Modifier.width(8.dp))
                    Pill(text = count.toString(), tone = tone)
                }
            }

            Spacer(Modifier.height(10.dp))

            when {
                isLoading -> LoadingRow("Querying intelligence sources for $title…")
                count == 0 -> Notice(emptyMessage, tone = Tone.NEUTRAL)
                else -> content()
            }
        }
    }
}

@Composable
private fun IntelBaseDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = true,
            modifier = Modifier.weight(0.9f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            softWrap = true,
            modifier = Modifier.weight(1.3f),
        )
    }
}

private fun formatIntelBaseDate(raw: String): String {
    val clean = raw.trim().take(10)
    return runCatching {
        val sdfIn = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sdfOut = SimpleDateFormat("d MMM yyyy", Locale.US)
        sdfOut.format(sdfIn.parse(clean) as Date)
    }.getOrNull() ?: raw
}
