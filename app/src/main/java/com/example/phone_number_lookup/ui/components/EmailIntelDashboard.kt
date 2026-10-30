package com.example.phone_number_lookup.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.phone_number_lookup.BreachReport
import com.example.phone_number_lookup.EmailAttributionReport
import com.example.phone_number_lookup.RegistrationHit
import com.example.phone_number_lookup.UsernameHit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmailIntelDashboard(
    target: String,
    attribution: EmailAttributionReport?,
    registrations: List<RegistrationHit>,
    handleHits: List<UsernameHit>,
    breachReport: BreachReport?,
    isLoading: Boolean,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sourceFilter by remember(target) { mutableStateOf("") }
    val confirmed = registrations.filter { it.verificationStatus == "Registered response" }
    val reportedOnly = registrations.filter { it.verificationStatus != "Registered response" }
    val names = attribution?.namesFound.orEmpty().distinctBy { it.value.lowercase() }
    val usernames = attribution?.usernamesFound.orEmpty().distinctBy { it.value.lowercase() }
    val phones = attribution?.phonesFound.orEmpty().distinctBy { it.value.lowercase() }
    val locations = attribution?.locationsFound.orEmpty().distinctBy { it.value.lowercase() }
    val photos = attribution?.avatarUrls.orEmpty().filter { it.startsWith("https://") || it.startsWith("http://") }.distinct()
    val links = buildList {
        attribution?.profileLinks?.forEach { add(it.platform to it.url) }
        registrations.forEach { if (it.profileUrl.startsWith("http")) add(it.site to it.profileUrl) }
        handleHits.forEach { if (it.profileUrl.startsWith("http")) add(it.site to it.profileUrl) }
    }.distinctBy { it.second.lowercase() }
    val dates = attribution?.timeline.orEmpty().distinctBy { "${it.date}|${it.label}|${it.source}" }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Email intelligence", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            text = buildString {
                append("${confirmed.size} independently confirmed")
                if (reportedOnly.isNotEmpty()) append(" · ${reportedOnly.size} additional unverified reports")
                append(" · ${links.size} public links")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = sourceFilter,
            onValueChange = { sourceFilter = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Find a service") },
            singleLine = true,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            EmailDashboardCard("Profile pictures", photos.size.toString(), Modifier.weight(1f)) {
                if (photos.isEmpty()) {
                    EmptyCardText(if (isLoading) "Looking for public profile photos…" else "No public profile photos returned")
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        photos.take(25).forEachIndexed { index, url ->
                            AsyncImage(
                                model = url,
                                contentDescription = "Public profile image ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onOpenUrl(url) },
                            )
                        }
                    }
                }
            }
            EmailDashboardCard("Has accounts", confirmed.size.toString(), Modifier.weight(1f)) {
                if (registrations.isEmpty() && isLoading) {
                    LoadingCardText("Checking service endpoints…")
                } else if (registrations.isEmpty()) {
                    EmptyCardText("No account reports yet")
                } else {
                    registrations.take(25).forEach { hit ->
                        DashboardValue(
                            text = hit.site,
                            detail = if (hit.verificationStatus == "Registered response") "Confirmed" else "Unverified report",
                            onClick = hit.profileUrl.takeIf { it.startsWith("http") }?.let { { onOpenUrl(it) } },
                        )
                    }
                    if (registrations.size > 25) EmptyCardText("+ ${registrations.size - 25} more in service results")
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            EmailDashboardCard("Names", names.size.toString(), Modifier.weight(1f)) {
                ValueList(names.map { it.value to it.source }, isLoading)
            }
            EmailDashboardCard("Usernames", usernames.size.toString(), Modifier.weight(1f)) {
                ValueList(usernames.map { it.value to it.source }, isLoading)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            EmailDashboardCard("Phone numbers", phones.size.toString(), Modifier.weight(1f)) {
                ValueList(phones.map { it.value to it.source }, isLoading)
            }
            EmailDashboardCard("Locations", locations.size.toString(), Modifier.weight(1f)) {
                ValueList(locations.map { it.value to it.source }, isLoading)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            EmailDashboardCard("Links", links.size.toString(), Modifier.weight(1f)) {
                if (links.isEmpty()) EmptyCardText(if (isLoading) "Collecting public links…" else "No public links returned")
                else links.take(25).forEach { (name, url) ->
                    DashboardValue(name, url.removePrefix("https://").removePrefix("http://"), onClick = { onOpenUrl(url) })
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            EmailDashboardCard("Data breach", breachReport?.breachNames?.size?.toString() ?: "—", Modifier.weight(1f)) {
                when {
                    breachReport == null && isLoading -> LoadingCardText("Checking breach sources…")
                    breachReport == null -> EmptyCardText("Not checked")
                    breachReport.error != null -> EmptyCardText("Could not check: ${breachReport.error}")
                    breachReport.breached -> breachReport.breachNames.take(25).forEach { DashboardValue(it, "Reported breach") }
                    else -> EmptyCardText("No records returned by checked breach sources")
                }
            }
            EmailDashboardCard("Dates", dates.size.toString(), Modifier.weight(1f)) {
                if (dates.isEmpty() && isLoading) LoadingCardText("Collecting dated public signals…")
                else if (dates.isEmpty()) EmptyCardText("No dated public signals returned")
                else dates.take(25).forEach { event ->
                    DashboardValue(event.date, "${event.label} · ${event.source}")
                }
                breachReport?.let {
                    Spacer(Modifier.height(4.dp))
                    EmptyCardText("Breach lookup checked ${SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(it.checkedAt))}")
                }
            }
        }

        EmailDashboardCard("Service results", registrations.size.toString(), Modifier.fillMaxWidth()) {
            val matching = registrations.filter {
                sourceFilter.isBlank() || it.site.contains(sourceFilter, ignoreCase = true) || it.source.contains(sourceFilter, ignoreCase = true)
            }
            when {
                matching.isEmpty() && sourceFilter.isNotBlank() -> EmptyCardText("No service matches ‘$sourceFilter’")
                matching.isEmpty() && isLoading -> LoadingCardText("Waiting for service results…")
                matching.isEmpty() -> EmptyCardText("Run an email scan to see service results")
                else -> matching.take(40).forEach { hit ->
                    DashboardValue(
                        text = hit.site,
                        detail = "${hit.verificationStatus} · ${hit.source}",
                        onClick = hit.profileUrl.takeIf { it.startsWith("http") }?.let { { onOpenUrl(it) } },
                    )
                }
            }
            if (matching.size > 40) EmptyCardText("Showing 40 of ${matching.size}; use the service search to filter")
        }
    }
}

@Composable
private fun EmailDashboardCard(
    title: String,
    count: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, softWrap = true)
                Spacer(Modifier.width(6.dp))
                Text(count, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ValueList(values: List<Pair<String, String>>, isLoading: Boolean) {
    if (values.isEmpty()) EmptyCardText(if (isLoading) "Collecting public signals…" else "No public values returned")
    else values.take(25).forEach { (value, source) -> DashboardValue(value, source) }
    if (values.size > 25) EmptyCardText("+ ${values.size - 25} more")
}

@Composable
private fun DashboardValue(text: String, detail: String? = null, onClick: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = true
        )
        if (!detail.isNullOrBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                softWrap = true
            )
        }
    }
}

@Composable
private fun EmptyCardText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, softWrap = true)
}

@Composable
private fun LoadingCardText(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, softWrap = true)
    }
}
