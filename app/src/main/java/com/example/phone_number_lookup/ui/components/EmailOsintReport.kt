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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.phone_number_lookup.AttributedValue
import com.example.phone_number_lookup.BreachDetail
import com.example.phone_number_lookup.BreachReport
import com.example.phone_number_lookup.EmailAttributionReport
import com.example.phone_number_lookup.EmailLinkedAccount
import com.example.phone_number_lookup.EmailProfileSummary
import com.example.phone_number_lookup.RegistrationHit
import com.example.phone_number_lookup.TimelineEvent
import com.example.phone_number_lookup.UsernameHit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmailProfileSummaryCard(
    summary: EmailProfileSummary?,
    registrationCount: Int,
    breachCount: Int,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val tone = summary?.tone() ?: Tone.NEUTRAL
    SectionCard(
        title = "Profile summary",
        subtitle = "Synthesized in-app from the sources on this screen — every value below is one they returned",
        tone = tone,
    ) {
        if (summary == null) {
            if (isLoading) {
                LoadingRow("Correlating names, accounts, breaches and locations…")
            } else {
                Notice(
                    "No source returned a name, account, breach or location for this address yet. " +
                        "Run the email scan to query the free sources from this device.",
                    tone = Tone.NEUTRAL,
                )
            }
            return@SectionCard
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = summary.headline,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Pill("${summary.risk} risk", tone)
        }

        Spacer(Modifier.height(10.dp))
        summary.bullets.forEach { bullet ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tone.color(),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = bullet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Notice(summary.riskNote, tone = tone)

        if (registrationCount > 0 || breachCount > 0) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (registrationCount > 0) Pill("$registrationCount registration${if (registrationCount == 1) "" else "s"}", Tone.ACCENT)
                if (breachCount > 0) Pill("$breachCount breach record${if (breachCount == 1) "" else "s"}", Tone.DANGER)
            }
        }
    }
}

@Composable
fun EmailProfileStatsRow(
    registrations: List<RegistrationHit>,
    handleHits: List<UsernameHit>,
    breachReport: BreachReport?,
    attribution: EmailAttributionReport?,
    isLoading: Boolean,
) {
    val accounts = registrations.distinctBy { it.site.lowercase() }.size
    val breaches = breachReport?.breachNames.orEmpty().distinct().size
    val firstSeen = shortDate(attribution?.firstSeen)
    val lastSeen = shortDate(attribution?.lastSeen)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                value = if (accounts > 0 || !isLoading) accounts.toString() else "…",
                label = "Accounts",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = breaches.toString(),
                label = "Breaches",
                tone = if (breaches > 0) Tone.DANGER else Tone.NEUTRAL,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = handleHits.size.toString(),
                label = "Profiles",
                tone = if (handleHits.isNotEmpty()) Tone.INFO else Tone.NEUTRAL,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                value = firstSeen ?: "—",
                label = "First seen",
                tone = Tone.NEUTRAL,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = lastSeen ?: "—",
                label = "Last seen",
                tone = Tone.NEUTRAL,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmailIdentitySummarySection(
    attribution: EmailAttributionReport?,
    isLoading: Boolean,
    onOpenUrl: (String) -> Unit,
) {
    val names = attribution?.namesFound.orEmpty().distinctBy { it.value.lowercase() }
    val usernames = attribution?.usernamesFound.orEmpty().distinctBy { it.value.lowercase() }
    val phones = attribution?.phonesFound.orEmpty().distinctBy { it.value.lowercase() }
    val locations = attribution?.locationsFound.orEmpty().distinctBy { it.value.lowercase() }
    val photos = attribution?.avatarUrls.orEmpty()
        .filter { it.startsWith("http://") || it.startsWith("https://") }
        .distinct()
    val links = attribution?.profileLinks.orEmpty().distinctBy { it.url.lowercase() }
    val isEmpty = names.isEmpty() && usernames.isEmpty() && phones.isEmpty() && locations.isEmpty() &&
        photos.isEmpty() && links.isEmpty()

    SectionCard(
        title = "Summary",
        subtitle = "Values returned by the public sources this scan queried, each with its source",
        tone = if (isEmpty) Tone.NEUTRAL else Tone.INFO,
    ) {
        when {
            isEmpty && isLoading -> LoadingRow("Collecting names, handles, phone numbers, photos and locations…")
            isEmpty -> Notice(
                "None of the checked public sources returned a name, handle, phone number, photo, link or location " +
                    "for this address. Registration checks below can still confirm the services it is used on.",
                tone = Tone.NEUTRAL,
            )
            else -> {
                SummaryValues("Names Found", names, Tone.ACCENT)
                SummaryValues("Usernames", usernames, Tone.INFO)
                SummaryValues("Phone Numbers", phones, Tone.WARNING)
                if (photos.isNotEmpty()) {
                    SummaryBlockTitle("Profile Pictures")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        photos.take(25).forEachIndexed { index, url ->
                            AsyncImage(
                                model = url,
                                contentDescription = "Public profile picture ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onOpenUrl(url) },
                            )
                        }
                    }
                }
                if (links.isNotEmpty()) {
                    SummaryBlockTitle("Profile Links")
                    links.take(25).forEach { link ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenUrl(link.url) }
                                .padding(vertical = 4.dp),
                        ) {
                            Text(
                                text = link.handle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Tone.ACCENT.color(),
                                softWrap = true,
                            )
                            Text(
                                text = "(${link.platform})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                softWrap = true,
                            )
                        }
                    }
                    if (links.size > 25) {
                        Text(
                            text = "+ ${links.size - 25} more links",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (locations.isNotEmpty()) {
                    SummaryBlockTitle("Locations")
                    locations.take(25).forEach { loc ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = loc.value,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    softWrap = true
                                )
                                Text(
                                    text = loc.source,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    softWrap = true
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
private fun SummaryValues(title: String, values: List<AttributedValue>, tone: Tone) {
    if (values.isEmpty()) return
    SummaryBlockTitle(title)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        values.take(25).forEach { value ->
            Pill("${value.value} · ${value.source}", tone)
        }
        if (values.size > 25) Pill("+${values.size - 25} more", Tone.NEUTRAL)
    }
}

@Composable
private fun SummaryBlockTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmailBreachDetailsSection(
    details: List<BreachDetail>,
    onOpenUrl: (String) -> Unit,
) {
    if (details.isEmpty()) return
    SectionCard(
        title = "Data breaches",
        subtitle = "${details.size} record${if (details.size == 1) "" else "s"} described from the public breach catalogue",
        tone = Tone.DANGER,
    ) {
        details.forEach { detail -> BreachDetailCard(detail, onOpenUrl) }
    }
}

@Composable
private fun BreachDetailCard(detail: BreachDetail, onOpenUrl: (String) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(
                if (detail.domain != null) {
                    Modifier.clickable { onOpenUrl("https://${detail.domain}") }
                } else Modifier
            ),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, Tone.DANGER.color().copy(alpha = 0.35f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                detail.logoUrl?.let { logo ->
                    AsyncImage(
                        model = logo,
                        contentDescription = "${detail.name} logo",
                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = detail.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = true,
                    )
                    detail.domain?.let { domain ->
                        Text(
                            text = domain,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (detail.verified) Pill("Verified", Tone.DANGER)
            }

            val facts = buildList {
                detail.breachDate?.let { add("Breached $it") }
                if (detail.pwnCount > 0) add("${formatCount(detail.pwnCount)} accounts")
            }
            if (facts.isNotEmpty()) {
                Text(
                    text = facts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Tone.DANGER.color(),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (detail.dataClasses.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    detail.dataClasses.take(25).forEach { dataClass ->
                        Pill(dataClass, Tone.NEUTRAL)
                    }
                }
            }

            if (detail.description.isNotBlank()) {
                Text(
                    text = detail.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = true,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private fun formatCount(count: Long): String = when {
    count >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", count / 1_000_000_000.0)
    count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format(Locale.US, "%.0fK", count / 1_000.0)
    else -> count.toString()
}

@Composable
fun EmailActivityTimelineSection(
    attribution: EmailAttributionReport?,
    breachDetails: List<BreachDetail> = emptyList(),
    isLoading: Boolean,
) {
    val events = (attribution?.timeline.orEmpty() +
        breachDetails.mapNotNull { detail ->
            detail.breachDate?.let { date ->
                TimelineEvent(date, "Data Breach", detail.domain ?: detail.name)
            }
        })
        .distinctBy { "${it.date}|${it.label}|${it.source}" }
        .sortedByDescending { it.date }

    SectionCard(
        title = "Activity timeline",
        subtitle = "First-seen and last-seen signals returned by the checked sources",
        tone = Tone.INFO,
    ) {
        when {
            events.isEmpty() && isLoading -> LoadingRow("Collecting dated signals…")
            events.isEmpty() -> Notice(
                "No dated signal was returned for this address, so there is no timeline to plot yet.",
                tone = Tone.NEUTRAL,
            )
            else -> events.take(25).forEach { event -> TimelineRow(event) }
        }
    }
}

@Composable
private fun TimelineRow(event: TimelineEvent) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = shortDate(event.date) ?: event.date,
            style = MaterialTheme.typography.labelSmall,
            color = Tone.ACCENT.color(),
            modifier = Modifier.width(88.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = event.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = event.source,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmailLinkedAccountsSection(
    accounts: List<EmailLinkedAccount>,
    email: String,
    isLoading: Boolean,
    onOpenUrl: (String) -> Unit,
) {
    val confirmed = accounts.filter { it.confirmed }
    SectionCard(
        title = "Linked accounts",
        subtitle = if (accounts.isEmpty()) {
            "Service checks and public profile endpoints, run from this device"
        } else {
            "${accounts.size} profile${if (accounts.size == 1) "" else "s"} discovered · " +
                "${confirmed.size} confirmed by a service-specific response"
        },
        tone = if (confirmed.isNotEmpty()) Tone.ACCENT else Tone.INFO,
    ) {
        if (accounts.isEmpty()) {
            if (isLoading) {
                LoadingRow("Checking services, registrations and public profiles…")
            } else {
                Notice(
                    "No service reported this address yet. $email may simply not be registered on " +
                        "the sources that can be checked without an account.",
                    tone = Tone.NEUTRAL,
                )
            }
            return@SectionCard
        }

        if (confirmed.isNotEmpty()) {
            SubHeading("Registrations")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                confirmed.take(30).forEach { Pill(it.name, Tone.ACCENT) }
                if (confirmed.size > 30) Pill("+${confirmed.size - 30} more", Tone.NEUTRAL)
            }
            Spacer(Modifier.height(6.dp))
        }

        accounts.forEach { account ->
            LinkedAccountBlock(account, onOpenUrl)
        }
    }
}

@Composable
private fun LinkedAccountBlock(account: EmailLinkedAccount, onOpenUrl: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(
            1.dp,
            (if (account.confirmed) Tone.ACCENT else Tone.NEUTRAL).color().copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (account.url != null) Modifier.clickable { onOpenUrl(account.url) } else Modifier
                )
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = account.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = true,
                    modifier = Modifier.weight(1f),
                )
                if (account.confirmed) Pill("Registered", Tone.ACCENT)
            }
            account.domain?.let { domain ->
                Text(
                    text = domain,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            account.fields.forEach { (label, value) ->
                Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(
                        text = label.uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = true,
                    )
                }
            }
        }
    }
}

private fun EmailProfileSummary.tone(): Tone = when (risk) {
    "High" -> Tone.DANGER
    "Moderate" -> Tone.WARNING
    else -> Tone.ACCENT
}

private fun shortDate(raw: String?): String? {
    val value = raw?.takeIf { it.isNotBlank() } ?: return null
    val day = value.take(10)
    return runCatching {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            .format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day) as Date)
    }.getOrNull() ?: day
}
