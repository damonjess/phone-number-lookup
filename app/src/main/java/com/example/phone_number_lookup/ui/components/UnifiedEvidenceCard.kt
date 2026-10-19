package com.example.phone_number_lookup.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.phone_number_lookup.EvidenceKind
import com.example.phone_number_lookup.EvidenceSourceState
import com.example.phone_number_lookup.UnifiedScanEvidence
import com.example.phone_number_lookup.UnifiedSourceStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UnifiedEvidenceCard(
    report: UnifiedScanEvidence,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f)),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Cross-scanner evidence", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "${report.scanType} · ${report.target} · started ${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(report.startedAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Text("SOURCES CHECKED", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            report.sources.forEach { SourceStatusLine(it) }
            Spacer(Modifier.height(8.dp))
            Text(
                "FINDINGS (${report.items.size})",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (report.items.isEmpty()) {
                Text(
                    "No findings are currently available. A source marked unknown, skipped, blocked, or incomplete is not a verified negative.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp),
                )
            } else {
                report.items.take(12).forEach { item ->
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                kindLabel(item.kind),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(78.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${item.source} · ${confidenceLabel(item.confidence)}${if (item.detail.isNotBlank()) " · ${item.detail}" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        item.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { url ->
                            TextButton(onClick = { onOpenUrl(url) }, modifier = Modifier.align(Alignment.End)) {
                                Text("Open source ↗", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (report.items.size > 12) {
                    Text("+ ${report.items.size - 12} more findings are shown in the scanner-specific sections below.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Confidence describes the strength of the source response, not proof that two records belong to the same person.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceStatusLine(source: UnifiedSourceStatus) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Pill(stateLabel(source.state), tone = stateTone(source.state))
        Column(Modifier.weight(1f)) {
            Text(source.source, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(source.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun stateLabel(state: EvidenceSourceState): String = when (state) {
    EvidenceSourceState.RESULTS -> "Results"
    EvidenceSourceState.NO_RESULTS -> "No results"
    EvidenceSourceState.PARTIAL -> "Partial"
    EvidenceSourceState.BLOCKED -> "Blocked"
    EvidenceSourceState.ERROR -> "Error"
    EvidenceSourceState.SKIPPED -> "Skipped"
    EvidenceSourceState.CHECKING -> "Checking"
    EvidenceSourceState.NOT_CHECKED -> "Not checked"
    EvidenceSourceState.UNKNOWN -> "Unknown"
}

private fun stateTone(state: EvidenceSourceState): Tone = when (state) {
    EvidenceSourceState.RESULTS -> Tone.ACCENT
    EvidenceSourceState.PARTIAL -> Tone.WARNING
    EvidenceSourceState.BLOCKED, EvidenceSourceState.ERROR -> Tone.DANGER
    EvidenceSourceState.CHECKING -> Tone.INFO
    EvidenceSourceState.NO_RESULTS, EvidenceSourceState.SKIPPED, EvidenceSourceState.NOT_CHECKED, EvidenceSourceState.UNKNOWN -> Tone.NEUTRAL
}

private fun confidenceLabel(confidence: com.example.phone_number_lookup.EvidenceConfidence): String = when (confidence) {
    com.example.phone_number_lookup.EvidenceConfidence.HIGH -> "high confidence"
    com.example.phone_number_lookup.EvidenceConfidence.MODERATE -> "moderate confidence"
    com.example.phone_number_lookup.EvidenceConfidence.LOW -> "low confidence"
    com.example.phone_number_lookup.EvidenceConfidence.UNVERIFIED -> "unverified"
    com.example.phone_number_lookup.EvidenceConfidence.UNKNOWN -> "confidence unknown"
}

private fun kindLabel(kind: EvidenceKind): String = when (kind) {
    EvidenceKind.ACCOUNT -> "ACCOUNT"
    EvidenceKind.PROFILE -> "PROFILE"
    EvidenceKind.NAME -> "NAME"
    EvidenceKind.USERNAME -> "USERNAME"
    EvidenceKind.LOCATION -> "LOCATION"
    EvidenceKind.AVATAR -> "PHOTO"
    EvidenceKind.LINK -> "LINK"
    EvidenceKind.BREACH -> "BREACH"
    EvidenceKind.ACTIVITY -> "DATE / ACTIVITY"
    EvidenceKind.PHONE_REPUTATION -> "PHONE SIGNAL"
    EvidenceKind.DOMAIN -> "DOMAIN"
    EvidenceKind.NETWORK -> "NETWORK"
}
