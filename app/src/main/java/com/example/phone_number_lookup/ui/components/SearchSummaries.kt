package com.example.phone_number_lookup.ui.components

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.phone_number_lookup.UsernameHit

/**
 * At-a-glance summary of a username scan: how many profiles matched, which categories they fall
 * into and which site databases produced them. Counts only — every number comes from the scan.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsernameProfileSummaryCard(
    handle: String,
    hits: List<UsernameHit>,
    sitesChecked: Int,
    isLoading: Boolean,
) {
    val matched = hits.count { it.verificationStatus == "Matched" }
    val categories = hits
        .groupBy { it.category.ifBlank { "other" }.lowercase() }
        .toList()
        .sortedByDescending { it.second.size }
    val sources = hits.groupingBy { it.source }.eachCount().toList().sortedByDescending { it.second }

    SectionCard(
        title = "Profile summary",
        subtitle = "Public profiles matching “$handle”, from the sites this scan could reach",
        tone = when {
            matched > 0 -> Tone.ACCENT
            hits.isNotEmpty() -> Tone.INFO
            else -> Tone.NEUTRAL
        },
    ) {
        when {
            hits.isEmpty() && isLoading -> LoadingRow("Checking public sites for this handle…")
            hits.isEmpty() -> Notice(
                "No public profile matched “$handle” on the $sitesChecked sites this scan could reach. " +
                    "A handle that is only used privately will not appear here.",
                tone = Tone.NEUTRAL,
            )
            else -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        value = matched.toString(),
                        label = "Matched",
                        tone = if (matched > 0) Tone.ACCENT else Tone.NEUTRAL,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = hits.size.toString(),
                        label = "Results",
                        tone = Tone.INFO,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = if (sitesChecked > 0) sitesChecked.toString() else "—",
                        label = "Checked",
                        modifier = Modifier.weight(1f),
                    )
                }

                if (categories.isNotEmpty()) {
                    Text(
                        text = "Categories",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        categories.take(10).forEach { (category, list) ->
                            Pill("$category ${list.size}", Tone.INFO)
                        }
                    }
                }

                if (sources.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Found via " + sources.joinToString(", ") { "${it.first} (${it.second})" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val strongest = hits.firstOrNull { it.confidence.startsWith("High", ignoreCase = true) }
                if (strongest != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Strongest match",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Pill(strongest.site, Tone.ACCENT)
                    }
                }
            }
        }
    }
}

/**
 * The number's own facts in one block: identity, numbering plan, line class and coverage zone.
 * These are computed locally from the number itself, so they are available even when no web
 * source answers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneProfileSummaryCard(
    e164: String,
    national: String,
    country: String,
    area: String?,
    lineType: String,
    timezone: String,
    carrier: String?,
    valid: Boolean,
    riskLabel: String?,
) {
    SectionCard(
        title = "Number summary",
        subtitle = "Numbering facts resolved on this device; web matches appear below",
        tone = if (valid) Tone.ACCENT else Tone.WARNING,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = e164,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Pill(if (valid) "Valid format" else "Unverified format", if (valid) Tone.ACCENT else Tone.WARNING)
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            Pill(country, Tone.INFO)
            Pill(lineType, Tone.NEUTRAL)
            if (timezone.isNotBlank()) Pill(timezone, Tone.NEUTRAL)
            riskLabel?.takeIf { it.isNotBlank() }?.let { Pill(it, Tone.WARNING) }
        }

        Spacer(Modifier.height(6.dp))
        ResultRow("International", e164)
        ResultRow("National", national)
        area?.let { ResultRow("Area / region", it) }
        carrier?.let { ResultRow("Carrier / range holder", it) }
    }
}
