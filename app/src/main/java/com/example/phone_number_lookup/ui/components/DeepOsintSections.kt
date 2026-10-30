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
import com.example.phone_number_lookup.ScrapedWebReport
import com.example.phone_number_lookup.SearchResultItem

@Composable
fun DeepPhoneFootprintSection(
    report: DeepPhoneOsintReport?,
    modifier: Modifier = Modifier
) {
    if (report == null) return
    val hasContent = report.directoryReports.isNotEmpty() ||
        report.businessMatches.isNotEmpty() ||
        report.discoveredEmails.isNotEmpty() ||
        report.discoveredHandles.isNotEmpty()

    if (!hasContent) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        if (report.businessMatches.isNotEmpty()) {
            OsintCardContainer(
                title = "REGISTERED BUSINESS / VENUE MATCHES",
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
                title = "SCRAPED CALLER DIRECTORY REPORTS",
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

        if (report.discoveredEmails.isNotEmpty() || report.discoveredHandles.isNotEmpty()) {
            OsintCardContainer(
                title = "ENTITIES EXTRACTED FROM WEB MENTIONS",
                badge = "${report.discoveredEmails.size + report.discoveredHandles.size}",
                tone = Tone.ACCENT
            ) {
                report.discoveredEmails.forEach { email ->
                    AttributedRow(label = "Email (${email.source})", value = email.value)
                }
                report.discoveredHandles.forEach { handle ->
                    AttributedRow(label = "Handle (${handle.source})", value = handle.value)
                }
            }
        }
    }
}

@Composable
private fun DirectoryReportBlock(dir: DirectoryReportSummary) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                dir.verdictLabel?.let { Pill(it, tone = Tone.ACCENT) }
                dir.callerCategory?.let { Pill(it, tone = Tone.ACCENT) }
                dir.reportCount?.let { Pill(it, tone = Tone.ACCENT) }
            }
        }

        dir.userComments.forEach { comment ->
            Text(
                text = "• \"$comment\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
        report.extractedHandles.isNotEmpty()

    if (!hasData) return
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        if (report.extractedEmails.isNotEmpty() ||
            report.extractedPhones.isNotEmpty() ||
            report.extractedHandles.isNotEmpty()
        ) {
            OsintCardContainer(
                title = "MINED CONTACTS & HANDLES FROM WEB RESULTS",
                badge = "${report.extractedEmails.size + report.extractedPhones.size + report.extractedHandles.size}",
                tone = Tone.ACCENT
            ) {
                report.extractedEmails.forEach {
                    AttributedRow(label = "Email (${it.source})", value = it.value)
                }
                report.extractedPhones.forEach {
                    AttributedRow(label = "Phone (${it.source})", value = it.value)
                }
                report.extractedHandles.forEach {
                    AttributedRow(label = "Handle (${it.source})", value = it.value)
                }
            }
        }

        if (report.results.isNotEmpty()) {
            OsintCardContainer(
                title = "PUBLIC WEB, FORUM & CODE MENTIONS",
                badge = "${report.results.size}",
                tone = Tone.ACCENT
            ) {
                report.results.take(25).forEachIndexed { idx, item ->
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
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
                    color = MaterialTheme.colorScheme.primary
                )
                Pill(badge, tone = tone)
            }
            content()
        }
    }
}
