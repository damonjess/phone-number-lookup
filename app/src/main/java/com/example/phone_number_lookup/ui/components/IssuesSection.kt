package com.example.phone_number_lookup.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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

private fun ProbeFailure.pillTone() = when (this) {
    ProbeFailure.NETWORK, ProbeFailure.SERVER_ERROR, ProbeFailure.HTTP_ERROR -> Tone.WARNING
    ProbeFailure.RATE_LIMITED, ProbeFailure.FORBIDDEN, ProbeFailure.CHALLENGE -> Tone.DANGER
    ProbeFailure.INCONCLUSIVE -> Tone.NEUTRAL
}

/**
 * Sites the scan couldn't check automatically: why, a retry action for the ones that might work
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

    Column(modifier = modifier.fillMaxWidth().padding(top = Space.sm)) {
        Notice(ProbeFailures.summarize(issues), tone = Tone.WARNING)

        if (isRetrying) {
            Spacer(Modifier.height(Space.sm))
            LoadingRow("Retrying the blocked sites…")
        } else if (retryable > 0) {
            Spacer(Modifier.height(Space.sm))
            AppButton(
                text = "Retry $retryable ${if (retryable == 1) "site" else "sites"}",
                onClick = onRetry,
                subdued = true,
            )
        }

        SubHeading("Check these yourself")
        shown.forEach { issue ->
            Surface(
                onClick = { onOpen(issue.profileUrl) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = issue.site,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Space.sm))
                    Pill(issue.reason.shortLabel(), tone = issue.reason.pillTone())
                }
            }
        }
        if (issues.size > COLLAPSED_COUNT) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(
                    text = if (showAll) "Show fewer" else "Show all ${issues.size}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
