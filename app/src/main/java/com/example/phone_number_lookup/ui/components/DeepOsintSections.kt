package com.example.phone_number_lookup.ui.components

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.phone_number_lookup.ApifyStyleEmailIntel
import com.example.phone_number_lookup.ApifyStyleSummary
import com.example.phone_number_lookup.AttributedValue
import com.example.phone_number_lookup.BreachReport
import com.example.phone_number_lookup.CommentHit
import com.example.phone_number_lookup.DeepPhoneOsintReport
import com.example.phone_number_lookup.DirectoryReportSummary
import com.example.phone_number_lookup.EmailAttributionReport
import com.example.phone_number_lookup.EmailDomainReport
import com.example.phone_number_lookup.EnrichedProfileCard
import com.example.phone_number_lookup.OsintProfileValidator
import com.example.phone_number_lookup.GoogleAccountMetadata
import com.example.phone_number_lookup.GoogleCommentReport
import com.example.phone_number_lookup.PhoneAccountHit
import com.example.phone_number_lookup.ScrapedWebReport
import com.example.phone_number_lookup.SearchResultItem
import com.example.phone_number_lookup.TimelineEvent

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
                    SubHeading("Confirmed Accounts & Platforms (Tap to view in-app)")
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        confirmedAccounts.forEach { acc ->
                            Pill(
                                text = acc.platform,
                                tone = Tone.ACCENT,
                                modifier = if (acc.url != null) {
                                    Modifier.clickable {
                                        openUrl(context, acc.url)
                                    }
                                } else Modifier
                            )
                        }
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
                                        openUrl(context, hit.url)
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
                                openUrl(context, page.url)
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
                    openUrl(context, dir.url)
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
    val validProfiles = remember(profiles) {
        profiles.filter { profile ->
            OsintProfileValidator.hasValidProfilePath(profile.url) &&
            OsintProfileValidator.isValidPlatformUsername(profile.platform, profile.username, profile.url) &&
            !OsintProfileValidator.isBoilerplateOrMismatchedDossier(
                siteOrTitle = profile.platform,
                handle = profile.username,
                scrapedName = profile.displayName,
                scrapedBio = profile.bio,
                details = null
            )
        }
    }

    if (validProfiles.isEmpty()) return
    val context = LocalContext.current

    OsintCardContainer(
        title = "DEEP PROFILE DOSSIERS (SCRAPED METADATA)",
        badge = "${validProfiles.size}",
        tone = Tone.ACCENT,
        modifier = modifier
    ) {
        validProfiles.forEachIndexed { index, card ->
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
                            openUrl(context, card.profileUrl)
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
                                        openUrl(context, link)
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
                            openUrl(context, item.url)
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
                            openUrl(context, item.url)
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
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoogleCommentsSection(
    report: GoogleCommentReport?,
    isLoading: Boolean,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (report == null && !isLoading) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        OsintCardContainer(
            title = "GOOGLE & PUBLIC WEB COMMENTS SCRAPED",
            badge = if (isLoading && report == null) "SCANNING…" else "${report?.comments?.size ?: 0} FOUND",
            tone = Tone.ACCENT
        ) {
            if (isLoading && report == null) {
                LoadingRow("Scraping Google Search, Google Maps Reviews, Reddit, GitHub, HackerNews & forums for comments…")
            } else if (report != null) {
                Notice(report.googleDirectStatus, tone = if (report.comments.isNotEmpty()) Tone.ACCENT else Tone.INFO)

                if (report.comments.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    report.comments.take(35).forEachIndexed { idx, hit ->
                        if (idx > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                        CommentHitBlock(hit = hit, onClick = { onOpenUrl(hit.url) })
                    }
                    if (report.comments.size > 35) {
                        Text(
                            text = "+ ${report.comments.size - 35} more comments included in PDF export",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            softWrap = true,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }

        if (report != null && report.dorkLinks.isNotEmpty()) {
            DorkLauncherCard(
                title = "Live Google Comment & Review Dorks",
                subtitle = "Open targeted Google comment searches directly in your browser",
                dorks = report.dorkLinks
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommentHitBlock(
    hit: CommentHit,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Pill(hit.platform, tone = Tone.ACCENT)
            Pill("Matched: ${hit.authorMatch}", tone = Tone.INFO)
            hit.date?.takeIf { it.isNotBlank() }?.let {
                Pill(it, tone = Tone.NEUTRAL)
            }
            Pill(hit.sourceEngine, tone = Tone.NEUTRAL)
        }

        Text(
            text = hit.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            softWrap = true
        )

        if (hit.commentSnippet.isNotBlank()) {
            Text(
                text = hit.commentSnippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = true
            )
        }

        Text(
            text = hit.url,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApifyStyleEmailSummaryCard(
    summary: ApifyStyleSummary,
    attribution: EmailAttributionReport?,
    breachReport: BreachReport?,
    domainReport: EmailDomainReport?,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val gradeTone = when (summary.riskGrade) {
        "A" -> Tone.ACCENT
        "B" -> Tone.INFO
        "C" -> Tone.WARNING
        else -> Tone.DANGER
    }

    OsintCardContainer(
        title = "EMAIL OSINT CHANNEL & RISK MATRIX",
        badge = "GRADE ${summary.riskGrade}",
        tone = gradeTone,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatTile(
                value = summary.riskGrade,
                label = "RISK GRADE",
                tone = gradeTone,
                modifier = Modifier.weight(1f)
            )
            StatTile(
                value = "${summary.breachRiskScore}%",
                label = "BREACH RISK",
                tone = if (summary.breachRiskScore >= 40) Tone.DANGER else Tone.INFO,
                modifier = Modifier.weight(1f)
            )
            StatTile(
                value = summary.accountsFound.toString(),
                label = "FOUND",
                tone = Tone.ACCENT,
                modifier = Modifier.weight(1f)
            )
            StatTile(
                value = summary.accountsUnresolved.toString(),
                label = "UNKNOWN",
                tone = Tone.NEUTRAL,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(6.dp))

        val directCount = summary.channelRows.count { it.emailLinkVerified }
        val handleCount = summary.channelRows.size - directCount
        Text(
            text = "Active mailbox: ${if (summary.isActiveEmail) "Yes" else "Unverified"} · " +
                "$directCount direct email account(s) · $handleCount handle follow-up candidate(s) · " +
                "${summary.accountsChecked} total checked",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = true
        )

        if (summary.channelRows.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            summary.channelRows.take(20).forEachIndexed { idx, row ->
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
                            if (row.profileUrl != null) {
                                Modifier.clickable { onOpenUrl(row.profileUrl) }
                            } else Modifier
                        ),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = row.channel + (row.username?.let { " (@$it)" } ?: ""),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            softWrap = true
                        )
                        Pill(
                            text = if (row.emailLinkVerified) "DIRECT EMAIL" else "HANDLE CANDIDATE",
                            tone = if (row.emailLinkVerified) Tone.ACCENT else Tone.INFO
                        )
                    }
                    row.handleOrigin?.let { origin ->
                        Text(
                            text = "Origin: $origin · Category: ${row.category}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            softWrap = true
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppButton(
                text = "Copy CSV Table",
                onClick = {
                    val csv = ApifyStyleEmailIntel.toCsv(summary)
                    copyToClipboard(context, "Email OSINT CSV", csv)
                },
                subdued = true,
                modifier = Modifier.weight(1f)
            )
            AppButton(
                text = "Share JSON",
                onClick = {
                    val json = ApifyStyleEmailIntel.toFullResultsJson(
                        summary = summary,
                        attribution = attribution,
                        breachReport = breachReport,
                        domainReport = domainReport
                    )
                    ApifyStyleEmailIntel.shareText(context, "FULL_RESULTS - ${summary.email}", json)
                },
                subdued = true,
                modifier = Modifier.weight(1f)
            )
        }
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

/**
 * 1. IntelBase-style Locations Card:
 * Displays all resolved cities + Google Maps review street addresses with right-aligned source badges.
 */
@Composable
fun IntelBaseLocationsCard(
    attribution: EmailAttributionReport?,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val allLocations = remember(attribution) {
        val list = mutableListOf<AttributedValue>()
        attribution?.locationsFound?.forEach { loc ->
            val cleanSource = when {
                loc.source.contains("LinkedIn", true) -> "LinkedIn"
                loc.source.contains("Strava", true) -> "Strava"
                loc.source.contains("GitHub", true) -> "GitHub"
                loc.source.contains("Google", true) -> "Google"
                else -> loc.source
            }
            list += AttributedValue(loc.value, cleanSource)
        }
        attribution?.googleMetadata?.reviews?.forEach { rev ->
            if (rev.address.isNotBlank() && rev.address != "Google Maps") {
                val fullAddr = if (rev.address.contains("UK", true) || rev.address.contains("United Kingdom", true)) {
                    rev.address
                } else {
                    "${rev.address}, United Kingdom"
                }
                list += AttributedValue(fullAddr, "Google")
            }
        }
        list.distinctBy { it.value.lowercase().trim() }
    }

    if (allLocations.isEmpty()) return

    var showAll by remember { mutableStateOf(false) }
    val visible = if (showAll) allLocations else allLocations.take(6)

    OsintCardContainer(
        title = "LOCATIONS",
        badge = "${allLocations.size} FOUND",
        tone = Tone.ACCENT,
        modifier = modifier
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visible.forEach { loc ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onOpenUrl("https://www.google.com/maps/search/?api=1&query=${android.net.Uri.encode(loc.value)}")
                        }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = loc.value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Pill(
                        text = loc.source,
                        tone = if (loc.source == "Google") Tone.ACCENT else Tone.INFO
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
            }

            if (allLocations.size > 6) {
                Text(
                    text = if (showAll) "Show less" else "Show ${allLocations.size - 6} more",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { showAll = !showAll }
                        .padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * 2. IntelBase-style Activity Timeline Card:
 * Displays chronological activity pills (Google Last Seen, GitHub Updated/Created, Strava, MyFitnessPal, Breaches).
 */
@Composable
fun IntelBaseActivityTimelineCard(
    attribution: EmailAttributionReport?,
    breachReport: BreachReport?,
    enrichedProfiles: List<EnrichedProfileCard> = emptyList(),
    modifier: Modifier = Modifier
) {
    val events = remember(attribution, breachReport, enrichedProfiles) {
        val list = mutableListOf<TimelineEvent>()
        attribution?.googleMetadata?.lastUpdated?.takeIf { it.isNotBlank() }?.let { date ->
            list += TimelineEvent(date, "Last Seen Date", "Google")
        }
        attribution?.timeline?.forEach { ev ->
            list += ev
        }
        enrichedProfiles.forEach { card ->
            card.createdDate?.takeIf { it.isNotBlank() }?.let { created ->
                list += TimelineEvent(created, "Created At", card.platform)
            }
        }
        breachReport?.details?.forEach { b ->
            b.breachDate?.takeIf { it.isNotBlank() }?.let { d ->
                list += TimelineEvent(d, b.name, "Data Breach")
            }
        }
        list.distinctBy { "${it.date}|${it.source.lowercase()}" }
            .sortedByDescending { it.date }
    }

    if (events.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) events else events.take(8)

    OsintCardContainer(
        title = "ACTIVITY TIMELINE",
        badge = "${events.size} EVENTS",
        tone = Tone.INFO,
        modifier = modifier
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { ev ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ev.date,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "${ev.label} · ${ev.source}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            }
            if (events.size > 8) {
                Text(
                    text = if (expanded) "Hide full timeline" else "View all (${events.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
            }
        }
    }
}

/**
 * 3. IntelBase-style Google Profile, Star-Rated Reviews, Photo Coordinates & Centroid Estimate Card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IntelBaseGoogleProfileCard(
    google: GoogleAccountMetadata?,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (google == null) return

    var showAllReviews by remember { mutableStateOf(false) }
    val reviewsToShow = if (showAllReviews) google.reviews else google.reviews.take(4)

    OsintCardContainer(
        title = "IntelBase • GOOGLE & MAPS DOSSIER",
        badge = if (google.gaiaId != null) "VERIFIED GAIA" else "GOOGLE OSINT",
        tone = Tone.ACCENT,
        modifier = modifier
    ) {
        // Profile Header (Full Name + Avatar + Profile URL + Gaia ID + Enterprise + Last Seen)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                google.fullName?.let { name ->
                    Text(
                        text = "Full name",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                val profileUrl = google.profileUrl
                    ?: google.gaiaId?.let { "https://www.google.com/maps/contrib/$it" }
                if (profileUrl != null) {
                    Text(
                        text = "Profile URL",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = profileUrl,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onOpenUrl(profileUrl) }
                    )
                }
            }

            if (!google.avatarUrl.isNullOrBlank()) {
                Spacer(Modifier.width(12.dp))
                AsyncImage(
                    model = google.avatarUrl,
                    contentDescription = google.fullName ?: "Google Avatar",
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOpenUrl(google.avatarUrl) },
                    contentScale = ContentScale.Crop
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            google.gaiaId?.let { id ->
                Column(modifier = Modifier.weight(1f)) {
                    Text("ID", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(id, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Enterprise User", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (google.enterpriseUser) "Yes" else "No", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
        }

        google.lastUpdated?.let { lastSeen ->
            Spacer(Modifier.height(4.dp))
            Text("Last Seen Date", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(lastSeen, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }

        if (google.activeGoogleApps.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Active Google Apps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                google.activeGoogleApps.forEach { appName ->
                    Pill(text = appName, tone = Tone.ACCENT)
                }
            }
        }

        // Maps Activity Stat Pills
        Spacer(Modifier.height(8.dp))
        Text("Maps Activity", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val revCount = google.totalReviewsCount ?: google.reviews.count { it.commentText.isNotBlank() }
            val ratCount = google.totalRatingsCount ?: google.reviews.size
            val phoCount = google.totalPhotosCount ?: google.photos.size
            Pill(text = "$revCount Reviews", tone = Tone.INFO)
            Pill(text = "$ratCount Ratings", tone = Tone.ACCENT)
            Pill(text = "$phoCount Photos", tone = Tone.INFO)
            google.totalAnswersCount?.let { Pill(text = "$it Answers", tone = Tone.NEUTRAL) }
            google.totalQaCount?.let { Pill(text = "$it Q&A", tone = Tone.NEUTRAL) }
        }

        // Star-Rated Reviews List
        if (google.reviews.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Reviews · showing ${reviewsToShow.size} of ${google.reviews.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (google.reviews.size > 4) {
                    Text(
                        text = if (showAllReviews) "Show less" else "View all (${google.reviews.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showAllReviews = !showAllReviews }
                    )
                }
            }

            reviewsToShow.forEach { rev ->
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val targetUrl = rev.reviewUrl.ifBlank {
                                "https://www.google.com/maps/search/?api=1&query=${android.net.Uri.encode("${rev.title} ${rev.address}")}"
                            }
                            onOpenUrl(targetUrl)
                        }
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = rev.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        val starsStr = "★".repeat(rev.stars.coerceIn(1, 5)) + "☆".repeat((5 - rev.stars).coerceIn(0, 4))
                        Text(
                            text = starsStr,
                            style = MaterialTheme.typography.titleSmall,
                            color = androidx.compose.ui.graphics.Color(0xFFF59E0B)
                        )
                    }

                    if (rev.address.isNotBlank()) {
                        Text(
                            text = rev.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (rev.commentText.isNotBlank()) {
                        Text(
                            text = rev.commentText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = rev.dateLabel.ifBlank { rev.timestampIso },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (rev.latitude != null && rev.longitude != null) {
                            Text(
                                text = "◎ ${rev.latitude}, ${rev.longitude}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }
        }

        // Contributed Photos with Coordinates
        if (google.photos.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Contributed Photos (${google.photos.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                google.photos.take(8).forEach { photo ->
                    Column(
                        modifier = Modifier
                            .width(105.dp)
                            .clickable { onOpenUrl(photo.pageUrl.ifBlank { photo.url }) }
                    ) {
                        AsyncImage(
                            model = photo.url,
                            contentDescription = photo.placeName,
                            modifier = Modifier
                                .size(105.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Text(
                            text = photo.placeName,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (photo.latitude != null && photo.longitude != null) {
                            Text(
                                text = "${photo.latitude}, ${photo.longitude}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        // Location Estimate (Centroid & Spread Radius in km)
        google.locationEstimate?.let { est ->
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Location Estimate · from ${est.totalCoordinates} coordinates",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Open in Maps ↗",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenUrl(est.mapsUrl) }
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "◎ Centroid: ${est.centroidLat}, ${est.centroidLng}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "✥ Spread: ${est.spreadKm}km radius",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "📍 ${est.reviewCoordCount} from reviews, ${est.photoCoordCount} from photos",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (google.reviews.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Notice(
                text = "Google Maps hid contributor reviews from unauthenticated background HTTP. Tap below to load live in the In-App Browser and auto-capture reviews & coordinates.",
                tone = Tone.INFO
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppButton(
                    text = "Load My Maps Contrib",
                    onClick = {
                        val url = when {
                            !google.gaiaId.isNullOrBlank() ->
                                "https://www.google.com/maps/contrib/${google.gaiaId}/reviews?hl=en-GB"
                            !google.profileUrl.isNullOrBlank() ->
                                google.profileUrl
                            else ->
                                "https://www.google.com/maps/contrib/?hl=en-GB"
                        }
                        onOpenUrl(url)
                    },
                    subdued = false,
                    modifier = Modifier.weight(1f)
                )
                AppButton(
                    text = "Open Epieos Lookup",
                    onClick = {
                        onOpenUrl("https://epieos.com/?q=${android.net.Uri.encode(google.email)}")
                    },
                    subdued = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnifiedPhoneIntelligenceDashboard(
    report: com.example.phone_number_lookup.UnifiedPhoneIntelligenceReport,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(
        "📱 Telecom",
        "🏢 Corporate (${report.corporateFilings.size})",
        "🌐 Web (${report.webPages.size})",
        "💬 Messaging (${report.messagingApps.size})",
        "👤 Identities (${report.discoveredNames.size})"
    )

    val riskTone = when (report.riskGrade) {
        "A" -> Tone.ACCENT
        "B" -> Tone.INFO
        "C" -> Tone.WARNING
        else -> Tone.DANGER
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OsintCardContainer(
            title = "PHONE THREAT METER & INTEL",
            badge = "GRADE ${report.riskGrade}",
            tone = riskTone
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatTile(value = "${report.riskScore}%", label = "THREAT SCORE", tone = riskTone, modifier = Modifier.weight(1f).clickable { selectedTab = 0 })
                StatTile(value = report.corporateFilings.size.toString(), label = "CO. FILINGS", tone = Tone.ACCENT, modifier = Modifier.weight(1f).clickable { selectedTab = 1 })
                StatTile(value = report.webPages.size.toString(), label = "WEB HITS", tone = Tone.INFO, modifier = Modifier.weight(1f).clickable { selectedTab = 2 })
                StatTile(value = report.messagingApps.count { it.verifiedPresence }.toString(), label = "MESSAGING", tone = Tone.ACCENT, modifier = Modifier.weight(1f).clickable { selectedTab = 3 })
            }

            Spacer(Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tabs.forEachIndexed { idx, title ->
                    Pill(
                        text = title,
                        tone = if (selectedTab == idx) Tone.ACCENT else Tone.NEUTRAL,
                        modifier = Modifier.clickable { selectedTab = idx }
                    )
                }
            }
        }

        when (selectedTab) {
            0 -> {
                OsintCardContainer(title = "📱 TELECOMMUNICATION & CARRIER", badge = report.telecom.countryCode, tone = Tone.INFO) {
                    AttributedRow("E.164 Format", report.telecom.e164Format)
                    AttributedRow("National Format", report.telecom.nationalFormat)
                    AttributedRow("Line Type", report.telecom.lineType)
                    AttributedRow("Carrier Allocation", "Three UK / Hutchison 3G MVNO Range")

                    val hlrLine = if (report.hlrChecked) {
                        "HLR Status: ${report.hlrLiveStatus ?: "Unknown"} • Network: ${report.hlrCurrentNetwork ?: "N/A"} • Ported: ${if (report.hlrIsPorted) "Yes" else "No"}"
                    } else {
                        "HLR Status: ${report.hlrLiveStatus ?: "Offline / Unconfigured"}"
                    }
                    AttributedRow("Live HLR", hlrLine)

                    val breachLine = if (report.breachCount > 0) {
                        "Breaches Found (${report.breachCount}): ${report.breaches.joinToString(", ")}"
                    } else if (report.breachChecked) {
                        "Breach Check: Clean (0 exposures found)"
                    } else {
                        "Breach Check: No local DB or HIBP key configured"
                    }
                    AttributedRow("Data Breaches", breachLine)
                }
            }
            1 -> {
                OsintCardContainer(title = "🏢 UK CORPORATE REGISTRY (COMPANIES HOUSE)", badge = "${report.corporateFilings.size} MATCHES", tone = Tone.ACCENT) {
                    if (report.corporateFilings.isEmpty()) {
                        Notice("No UK corporate filings or director contact records list this phone number.", tone = Tone.NEUTRAL)
                    } else {
                        report.corporateFilings.forEach { filing ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenUrl(filing.url) }
                                    .padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(text = filing.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(text = filing.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        }
                    }
                }
            }
            2 -> {
                OsintCardContainer(title = "🌐 OPEN WEB FOOTPRINT (SPAM FILTERED)", badge = "${report.webPages.size} PAGES", tone = Tone.ACCENT) {
                    if (report.webPages.isEmpty()) {
                        Notice("No verified web pages mention this number without spam noise.", tone = Tone.NEUTRAL)
                    } else {
                        report.webPages.forEach { page ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenUrl(page.url) }
                                    .padding(vertical = 5.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(text = page.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(text = page.snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                Text(text = page.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        }
                    }
                }
            }
            3 -> {
                OsintCardContainer(
                    title = "💬 MESSAGING & TELEGRAM INTEL",
                    badge = "${report.messagingApps.count { it.verifiedPresence }} VERIFIED",
                    tone = Tone.ACCENT
                ) {
                    if (report.messagingApps.isEmpty()) {
                        Notice("No messaging app profiles or linkages evaluated.", tone = Tone.NEUTRAL)
                    } else {
                        report.messagingApps.forEach { app ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = app.platform,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Pill(
                                            text = if (app.verifiedPresence) "VERIFIED" else "UNVERIFIED",
                                            tone = if (app.verifiedPresence) Tone.ACCENT else Tone.NEUTRAL
                                        )
                                    }
                                    if (app.checkUrl.isNotBlank()) {
                                        Pill(
                                            text = "OPEN ↗",
                                            tone = Tone.INFO,
                                            modifier = Modifier.clickable { onOpenUrl(app.checkUrl) }
                                        )
                                    }
                                }
                                Text(
                                    text = app.status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                app.displayName?.takeIf { it.isNotBlank() }?.let { name ->
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                app.bioOrStatus?.takeIf { it.isNotBlank() && it != app.displayName }?.let { bio ->
                                    Text(
                                        text = "Bio: $bio",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        }
                    }
                }
            }
            4 -> {
                OsintCardContainer(title = "👤 DISCOVERED IDENTITIES & MESSAGING", badge = "${report.discoveredNames.size} ENTITIES", tone = Tone.ACCENT) {
                    if (report.discoveredNames.isNotEmpty()) {
                        Text("Associated Names", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        report.discoveredNames.forEach { n -> Text("• ${n.value}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
                        Spacer(Modifier.height(6.dp))
                    }
                    Text("Messaging App Linkages", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    report.messagingApps.forEach { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenUrl(app.checkUrl) }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.platform, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(text = app.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Pill(text = "VERIFY ↗", tone = Tone.INFO)
                        }
                    }

                    val context = LocalContext.current
                    var manualMaskInput by remember { mutableStateOf("") }
                    val liveManualMatches = remember(manualMaskInput, report) {
                        val clean = manualMaskInput.trim().lowercase()
                        if ('*' in clean && '@' in clean) {
                            val pool = com.example.phone_number_lookup.MaskedEmailPivotEngine.generateCandidateLocalParts(
                                names = report.discoveredNames.map { it.value },
                                handles = report.discoveredSocials.map { it.handle },
                                knownEmails = report.discoveredEmails.map { it.value }
                            )
                            com.example.phone_number_lookup.MaskedEmailPivotEngine.unmaskHint(
                                hint = com.example.phone_number_lookup.MaskedEmailHint(clean, "Interactive Unmasker"),
                                candidateLocalParts = pool,
                                candidateDomains = listOf(
                                    "gmail.com", "googlemail.com", "outlook.com", "hotmail.com",
                                    "hotmail.co.uk", "icloud.com", "yahoo.com", "yahoo.co.uk", "btinternet.com"
                                )
                            )
                        } else emptyList()
                    }

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "🔓 Masked Email Unmasker & Recovery Pivot",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Tested ${report.maskedPivotReport.permutationsTested} name/handle permutations. Paste any masked recovery hint (e.g. d******s@gmail.com) to unmask:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(6.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = manualMaskInput,
                        onValueChange = { manualMaskInput = it },
                        placeholder = { Text("e.g. d*******s@gmail.com or da***86@g****.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    val allCandidates = (liveManualMatches + report.maskedPivotReport.unmaskedCandidates)
                        .distinctBy { "${it.maskedHint}|${it.resolvedEmail}" }

                    if (allCandidates.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        allCandidates.take(6).forEach { cand ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        copyToClipboard(context, "Unmasked Email", cand.resolvedEmail)
                                    }
                                    .padding(vertical = 5.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${cand.maskedHint}  ➔  ${cand.resolvedEmail}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Pill(
                                        text = "${cand.confidenceScore}% MATCH",
                                        tone = if (cand.confidenceScore >= 85) Tone.ACCENT else Tone.INFO
                                    )
                                }
                                Text(
                                    text = "Pivot Origin: ${cand.matchedFrom} · ${cand.reasoning}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                        }
                    }
                }
            }
        }
    }
}

