package com.example.phone_number_lookup.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.phone_number_lookup.AttributedValue
import com.example.phone_number_lookup.DeepPhoneOsintReport
import com.example.phone_number_lookup.DirectoryReportSummary
import com.example.phone_number_lookup.EnrichedProfileCard
import com.example.phone_number_lookup.PhoneAccountHit
import com.example.phone_number_lookup.ScrapedWebReport
import com.example.phone_number_lookup.SearchResultItem

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DeepPhoneFootprintSection(
    report: DeepPhoneOsintReport?,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (report == null && !isLoading) return
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        val confirmedAccounts = report?.accountRegistrations?.filter { it.confirmed } ?: emptyList()
        val allAccounts = report?.accountRegistrations ?: emptyList()

        OsintCardContainer(
            title = "WHAT THIS NUMBER IS SIGNED UP TO (LINKED ACCOUNTS & SERVICES)",
            badge = if (isLoading) "PROBING..." else "${allAccounts.size} DISCOVERED",
            tone = if (confirmedAccounts.isNotEmpty()) Tone.ACCENT else Tone.INFO
        ) {
            if (isLoading && allAccounts.isEmpty()) {
                LoadingRow("Probing registrations across Microsoft, Telegram, WhatsApp, Instagram, Snapchat, Amazon & online platforms…")
            } else if (allAccounts.isEmpty()) {
                Notice("No public registrations or active profiles were detected for this phone number across probed services.", tone = Tone.NEUTRAL)
            } else {
                if (confirmedAccounts.isNotEmpty()) {
                    SubHeading("Confirmed Accounts & Platforms")
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        confirmedAccounts.forEach { Pill(it.platform, Tone.ACCENT) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                allAccounts.forEachIndexed { idx, hit ->
                    if (idx > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (hit.url != null) {
                                    Modifier.clickable {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(hit.url)))
                                        }
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
                                text = hit.platform,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                softWrap = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Pill(hit.status, tone = if (hit.confirmed) Tone.ACCENT else Tone.INFO)
                        }
                        Text(
                            text = hit.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            softWrap = true
                        )
                    }
                }
            }
        }

        if (report != null) {
            if (report.carrierAllocationHint != null || report.areaLocationHint != null || report.numberSpellings.isNotEmpty()) {
            OsintCardContainer(
                title = "NETWORK, AREA & FORMAT INTELLIGENCE",
                badge = "${report.numberSpellings.size} FORMATS",
                tone = Tone.ACCENT
            ) {
                report.carrierAllocationHint?.let {
                    AttributedRow(label = "UK Network Block", value = it)
                }
                report.areaLocationHint?.let {
                    AttributedRow(label = "Geographic Area", value = it)
                }
                if (report.numberSpellings.isNotEmpty()) {
                    AttributedRow(
                        label = "Searched Formats",
                        value = report.numberSpellings.joinToString(" • ")
                    )
                }
            }
        }

        if (report.businessMatches.isNotEmpty()) {
            OsintCardContainer(
                title = "REGISTERED BUSINESS & DIRECTORY MATCHES",
                badge = "${report.businessMatches.size}",
                tone = Tone.ACCENT
            ) {
                report.businessMatches.forEach { biz ->
                    AttributedRow(label = biz.source, value = biz.value)
                }
            }
        }

        if (report.directoryReports.isNotEmpty()) {
            OsintCardContainer(
                title = "SCRAPED CALLER & REPUTATION DIRECTORIES",
                badge = "${report.directoryReports.size} SOURCES",
                tone = Tone.ACCENT
            ) {
                report.directoryReports.forEachIndexed { idx, dir ->
                    if (idx > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                    DirectoryReportBlock(dir)
                }
            }
        }

        if (report.discoveredNames.isNotEmpty() ||
            report.discoveredLocations.isNotEmpty() ||
            report.discoveredEmails.isNotEmpty() ||
            report.discoveredHandles.isNotEmpty()
        ) {
            val totalEntities = report.discoveredNames.size +
                report.discoveredLocations.size +
                report.discoveredEmails.size +
                report.discoveredHandles.size
            OsintCardContainer(
                title = "NAMES, LOCATIONS, EMAILS & SOCIALS FOUND",
                badge = "$totalEntities",
                tone = Tone.ACCENT
            ) {
                report.discoveredNames.forEach { n ->
                    AttributedRow(label = "Name (${n.source})", value = n.value)
                }
                report.discoveredLocations.forEach { loc ->
                    AttributedRow(label = "Location (${loc.source})", value = loc.value)
                }
                report.discoveredEmails.forEach { email ->
                    AttributedRow(label = "Email (${email.source})", value = email.value)
                }
                report.discoveredHandles.forEach { handle ->
                    AttributedRow(label = "Social / Handle (${handle.source})", value = handle.value)
                }
            }
        }

        if (report.crawledPages.isNotEmpty()) {
            OsintCardContainer(
                title = "DEEP CRAWLED WEB PAGES MENTIONING NUMBER",
                badge = "${report.crawledPages.size} PAGES",
                tone = Tone.ACCENT
            ) {
                report.crawledPages.forEachIndexed { idx, page ->
                    if (idx > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page.url)))
                                }
                            },
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = page.pageTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            softWrap = true
                        )
                        Text(
                            text = "\"…${page.contextSnippet}…\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            softWrap = true
                        )
                        if (page.emailsFound.isNotEmpty()) {
                            Text(
                                text = "Emails on page: ${page.emailsFound.joinToString(", ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                softWrap = true
                            )
                        }
                        if (page.postcodesFound.isNotEmpty()) {
                            Text(
                                text = "Postcodes on page: ${page.postcodesFound.joinToString(", ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                softWrap = true
                            )
                        }
                        if (page.socialLinksFound.isNotEmpty()) {
                            Text(
                                text = "Social links: ${page.socialLinksFound.joinToString(", ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                softWrap = true
                            )
                        }
                        Text(
                            text = page.url,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            softWrap = true
                        )
                    }
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DirectoryReportBlock(dir: DirectoryReportSummary) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = dir.source,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dir.url)))
                    }
                }
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                dir.verdictLabel?.let { Pill(it, tone = Tone.ACCENT) }
                dir.callerCategory?.let { Pill(it, tone = Tone.ACCENT) }
                dir.reportCount?.let { Pill(it, tone = Tone.ACCENT) }
            }
        }

        dir.callerName?.let {
            Text(
                text = "Reported Caller Name: $it",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = true
            )
        }

        dir.userComments.forEach { comment ->
            Text(
                text = "• \"$comment\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                softWrap = true
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnrichedProfilesSection(
    profiles: List<EnrichedProfileCard>,
    modifier: Modifier = Modifier
) {
    if (profiles.isEmpty()) return
    val context = LocalContext.current

    OsintCardContainer(
        title = "DEEP PROFILE DOSSIERS (SCRAPED METADATA)",
        badge = "${profiles.size}",
        tone = Tone.ACCENT,
        modifier = modifier
    ) {
        profiles.forEachIndexed { index, card ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(card.profileUrl)))
                            }
                        }
                ) {
                    if (!card.avatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = card.avatarUrl,
                            contentDescription = card.platform,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = buildString {
                                append(card.platform)
                                append(" • @")
                                append(card.handle)
                                card.displayName?.let { append(" ($it)") }
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        card.statsSummary?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    card.createdDate?.let {
                        Pill("Joined $it", tone = Tone.ACCENT)
                    }
                }

                card.bio?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                card.location?.let {
                    AttributedRow(label = "Location", value = it)
                }

                if (card.discoveredEmails.isNotEmpty()) {
                    AttributedRow(
                        label = "Scraped Emails",
                        value = card.discoveredEmails.joinToString(", ")
                    )
                }

                if (card.discoveredPhones.isNotEmpty()) {
                    AttributedRow(
                        label = "Scraped Phones",
                        value = card.discoveredPhones.joinToString(", ")
                    )
                }

                if (card.externalLinks.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        card.externalLinks.forEach { link ->
                            Text(
                                text = link,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable {
                                    if (link.startsWith("http")) {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OpenWebMentionsSection(
    report: ScrapedWebReport?,
    modifier: Modifier = Modifier
) {
    if (report == null) return
    val hasData = report.results.isNotEmpty() ||
        report.extractedEmails.isNotEmpty() ||
        report.extractedPhones.isNotEmpty() ||
        report.extractedHandles.isNotEmpty() ||
        report.extractedLocations.isNotEmpty()

    if (!hasData) return
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        if (report.extractedLocations.isNotEmpty() ||
            report.extractedPhones.isNotEmpty() ||
            report.extractedEmails.isNotEmpty() ||
            report.extractedHandles.isNotEmpty()
        ) {
            OsintCardContainer(
                title = "MINED LOCATIONS, CONTACTS & HANDLES",
                badge = "${report.extractedLocations.size + report.extractedPhones.size + report.extractedEmails.size + report.extractedHandles.size}",
                tone = Tone.ACCENT
            ) {
                report.extractedLocations.forEach {
                    AttributedRow(label = "Location (${it.source})", value = it.value)
                }
                report.extractedPhones.forEach {
                    AttributedRow(label = "Phone / Mobile (${it.source})", value = it.value)
                }
                report.extractedEmails.forEach {
                    AttributedRow(label = "Email (${it.source})", value = it.value)
                }
                report.extractedHandles.forEach {
                    AttributedRow(label = "Handle (${it.source})", value = it.value)
                }
            }
        }

        if (report.results.isNotEmpty()) {
            val commentsAndPosts = report.results.filter {
                it.source.contains("Reddit", ignoreCase = true) ||
                    it.source.contains("HackerNews", ignoreCase = true) ||
                    it.source.contains("GitHub", ignoreCase = true) ||
                    it.title.contains("comment", ignoreCase = true) ||
                    it.snippet.contains("comment", ignoreCase = true) ||
                    it.title.contains("review", ignoreCase = true) ||
                    it.snippet.contains("review", ignoreCase = true) ||
                    it.title.contains("post", ignoreCase = true) ||
                    it.snippet.contains("post", ignoreCase = true) ||
                    it.title.contains("forum", ignoreCase = true) ||
                    it.snippet.contains("forum", ignoreCase = true) ||
                    it.snippet.contains("discussion", ignoreCase = true)
            }
            val otherMentions = report.results.filterNot { it in commentsAndPosts }

            if (commentsAndPosts.isNotEmpty()) {
                OsintCardContainer(
                    title = "COMMENTS, REVIEWS & FORUM POSTS",
                    badge = "${commentsAndPosts.size}",
                    tone = Tone.ACCENT
                ) {
                    commentsAndPosts.forEachIndexed { idx, item ->
                        if (idx > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                        WebMentionItemRow(item) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                            }
                        }
                    }
                }
            }

            if (otherMentions.isNotEmpty()) {
                OsintCardContainer(
                    title = "PUBLIC WEB & SEARCH ENGINE MENTIONS",
                    badge = "${otherMentions.size}",
                    tone = Tone.INFO
                ) {
                    otherMentions.forEachIndexed { idx, item ->
                        if (idx > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                        WebMentionItemRow(item) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WebMentionItemRow(item: SearchResultItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            Pill(item.source, tone = Tone.ACCENT)
        }
        if (item.snippet.isNotBlank()) {
            Text(
                text = item.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = item.url,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AttributedRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = true
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = true
        )
    }
}

@Composable
private fun OsintCardContainer(
    title: String,
    badge: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    softWrap = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Pill(badge, tone = tone)
            }
            content()
        }
    }
}
