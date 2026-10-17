package com.example.phone_number_lookup.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.phone_number_lookup.OsintDork
import com.example.phone_number_lookup.SiteIssue

@Composable
fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.2f),
        )
    }
}

@Composable
fun OsintToolSectionCard(
    toolTitle: String,
    subtitle: String,
    containerColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = toolTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            content()
        }
    }
}

@Composable
fun IssuesSection(
    issues: List<SiteIssue>,
    isRetrying: Boolean,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit
) {
    if (issues.isEmpty()) return
    val retryableCount = issues.count { it.retryable }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${issues.size} site(s) couldn't be checked automatically",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall
            )
            issues.forEach { issue ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "• ${issue.site}: ${issue.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (issue.profileUrl.isNotBlank()) {
                        TextButton(onClick = { onOpen(issue.profileUrl) }) {
                            Text("Open", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (retryableCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onRetry,
                    enabled = !isRetrying,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(if (isRetrying) "Retrying..." else "Retry $retryableCount blocked site(s)")
                }
            }
        }
    }
}

/**
 * Replaces the Termux `grep -oE 'https?://...' > ~/links.txt`, `cat -n ~/links.txt`,
 * and `termux-open-url "$(sed -n '3p' ~/links.txt)"` workflow with a numbered queue.
 */
@Composable
fun NumberedLinksHubCard(
    title: String,
    links: List<Pair<String, String>>, // Pair(Label, URL)
    onExportPdf: (() -> Unit)? = null
) {
    val distinctLinks = remember(links) {
        links.filter { it.second.startsWith("http") || it.second.startsWith("viber://") }
            .distinctBy { it.second }
    }
    if (distinctLinks.isEmpty() && onExportPdf == null) return

    val context = LocalContext.current
    val visitedUrls = remember(distinctLinks) { mutableStateListOf<String>() }

    OsintToolSectionCard(
        toolTitle = title,
        subtitle = "Numbered link queue (${visitedUrls.size}/${distinctLinks.size} opened) — replaces ~/links.txt & termux-open-url",
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (distinctLinks.isNotEmpty()) {
                val nextPair = distinctLinks.firstOrNull { it.second !in visitedUrls }
                Button(
                    onClick = {
                        nextPair?.let { (_, url) ->
                            if (url !in visitedUrls) visitedUrls.add(url)
                            openUrl(context, url)
                        }
                    },
                    enabled = nextPair != null,
                    modifier = Modifier.weight(1f)
                ) {
                    val nextIndex = nextPair?.let { distinctLinks.indexOf(it) + 1 }
                    Text(if (nextIndex != null) "Open Next (#$nextIndex)" else "All Opened ✓")
                }

                OutlinedButton(
                    onClick = {
                        val formatted = distinctLinks.mapIndexed { i, (label, url) ->
                            "${i + 1}\t$label -$url"
                        }.joinToString("\n")
                        copyToClipboard(context, "OSINT Links", formatted)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Copy links.txt")
                }
            }
        }

        if (onExportPdf != null) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onExportPdf,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Export PDF Report (Blackbird --pdf style)")
            }
        }

        if (distinctLinks.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            distinctLinks.take(40).forEachIndexed { index, (label, url) ->
                val isVisited = url in visitedUrls
                OutlinedCard(
                    onClick = {
                        if (!isVisited) visitedUrls.add(url)
                        openUrl(context, url)
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = if (isVisited) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = if (isVisited) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = "#${index + 1}",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = url,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isVisited) "✓ Opened" else "Open ↗",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DorkLauncherCard(
    title: String,
    subtitle: String,
    dorks: List<OsintDork>,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer
) {
    if (dorks.isEmpty()) return
    val context = LocalContext.current
    OsintToolSectionCard(
        toolTitle = title,
        subtitle = subtitle,
        containerColor = containerColor
    ) {
        dorks.forEach { dork ->
            OutlinedCard(
                onClick = { openUrl(context, dork.url) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = dork.title,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = dork.toolSource,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = dork.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

fun openUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app available to handle this link", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open link: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
    }
}
