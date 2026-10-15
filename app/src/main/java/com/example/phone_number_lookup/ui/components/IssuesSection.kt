package com.example.phone_number_lookup.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.phone_number_lookup.ProbeFailure
import com.example.phone_number_lookup.ProbeFailures
import com.example.phone_number_lookup.SiteIssue

private const val COLLAPSED_COUNT = 6

private fun ProbeFailure.shortLabel() = when (this) {
    ProbeFailure.NETWORK -> "timed out"
    ProbeFailure.RATE_LIMITED -> "rate-limited"
    ProbeFailure.FORBIDDEN -> "refused"
    ProbeFailure.CHALLENGE -> "bot check"
    ProbeFailure.SERVER_ERROR -> "server error"
    ProbeFailure.HTTP_ERROR -> "error"
    ProbeFailure.INCONCLUSIVE -> "no clear answer"
}

/**
 * Sites the scan couldn't check automatically: why, a retry button for the ones that might work
 * next time, and a list so each can be opened in a normal browser.
 */
@Composable
fun IssuesSection(
    issues: List<SiteIssue>,
    isRetrying: Boolean,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (issues.isEmpty()) return
    var showAll by rememberSaveable { mutableStateOf(false) }
    val retryable = issues.count { it.retryable }
    val shown = if (showAll) issues else issues.take(COLLAPSED_COUNT)

    Column(modifier = modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            ProbeFailures.summarize(issues),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (retryable > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Button(onClick = onRetry, enabled = !isRetrying) {
                    Text("Retry $retryable site(s)")
                }
                if (isRetrying) {
                    Spacer(modifier = Modifier.width(10.dp))
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }

        Text(
            "Check these yourself in your browser:",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
        shown.forEach { issue ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${issue.site} (${issue.reason.shortLabel()})",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onOpen(issue.profileUrl) }) { Text("Open") }
            }
        }
        if (issues.size > COLLAPSED_COUNT) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "Show fewer" else "Show all ${issues.size}")
            }
        }
    }
}
