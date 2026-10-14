package com.example.phone_number_lookup.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.phone_number_lookup.RiskLevel
import com.example.phone_number_lookup.RiskVerdict

/** One-glance answer shown above the detailed results: verdict first, evidence underneath. */
@Composable
fun VerdictCard(verdict: RiskVerdict, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val container = when (verdict.level) {
        RiskLevel.HIGH -> colors.errorContainer
        RiskLevel.ELEVATED -> colors.tertiaryContainer
        RiskLevel.NO_WARNING_SIGNS -> colors.secondaryContainer
        RiskLevel.CHECKING -> colors.surfaceVariant
    }
    val content = when (verdict.level) {
        RiskLevel.HIGH -> colors.onErrorContainer
        RiskLevel.ELEVATED -> colors.onTertiaryContainer
        RiskLevel.NO_WARNING_SIGNS -> colors.onSecondaryContainer
        RiskLevel.CHECKING -> colors.onSurfaceVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (verdict.level) {
                    RiskLevel.CHECKING -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                    RiskLevel.HIGH, RiskLevel.ELEVATED -> Icon(Icons.Default.Warning, contentDescription = null)
                    RiskLevel.NO_WARNING_SIGNS -> Icon(Icons.Default.CheckCircle, contentDescription = null)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = verdict.headline,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = verdict.summary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (verdict.stillChecking && verdict.level != RiskLevel.CHECKING) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Still checking caller databases. This may change.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (verdict.reasons.isNotEmpty()) {
                SectionTitle("Why")
                verdict.reasons.forEach { reason ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            text = if (reason.points < 0) "✓" else "•",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(18.dp),
                        )
                        Text(reason.detail, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (verdict.nextSteps.isNotEmpty()) {
                SectionTitle("What to do")
                verdict.nextSteps.forEachIndexed { index, step ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(24.dp),
                        )
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Text(
                text = verdict.coverage,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp).alpha(0.8f),
            )
            Text(
                text = verdict.caveat,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp).alpha(0.8f),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}
