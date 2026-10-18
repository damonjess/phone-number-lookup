package com.example.phone_number_lookup.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.phone_number_lookup.RiskLevel
import com.example.phone_number_lookup.RiskVerdict

/** One-glance answer shown above the detailed results: verdict first, evidence underneath. */
@Composable
fun VerdictCard(verdict: RiskVerdict, modifier: Modifier = Modifier) {
    val tone = when (verdict.level) {
        RiskLevel.HIGH -> Tone.DANGER
        RiskLevel.ELEVATED -> Tone.WARNING
        RiskLevel.NO_WARNING_SIGNS -> Tone.ACCENT
        RiskLevel.CHECKING -> Tone.NEUTRAL
    }
    val eyebrow = when (verdict.level) {
        RiskLevel.HIGH -> "High risk"
        RiskLevel.ELEVATED -> "Elevated"
        RiskLevel.NO_WARNING_SIGNS -> "No warning signs"
        RiskLevel.CHECKING -> "Checking"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(eyebrow, tone = tone)
                Spacer(Modifier.width(10.dp))
                if (verdict.level == RiskLevel.CHECKING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(
                text = verdict.headline,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                text = verdict.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (verdict.stillChecking && verdict.level != RiskLevel.CHECKING) {
                Spacer(Modifier.height(Space.sm))
                Notice("Still checking caller databases. This may change.", tone = Tone.WARNING)
            }

            if (verdict.reasons.isNotEmpty()) {
                ListHeading("Why")
                verdict.reasons.forEach { reason ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            text = if (reason.points < 0) "✓" else "•",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tone.color(),
                            modifier = Modifier.width(20.dp),
                        )
                        Text(
                            text = reason.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            if (verdict.nextSteps.isNotEmpty()) {
                ListHeading("What to do")
                verdict.nextSteps.forEachIndexed { index, step ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(24.dp),
                        )
                        Text(
                            text = step,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(top = 14.dp, bottom = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                text = verdict.coverage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = verdict.caveat,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ListHeading(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}
