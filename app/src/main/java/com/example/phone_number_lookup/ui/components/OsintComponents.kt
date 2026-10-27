package com.example.phone_number_lookup.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.phone_number_lookup.OsintDork
import com.example.phone_number_lookup.SiteIssue

/** A label/value pair. Values stay right-aligned so long lists of metadata can be scanned. */
@Composable
fun ResultRow(label: String, value: String, mono: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Space.md))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = if (mono) FontFamily.Monospace else null,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.35f),
        )
    }
}

/**
 * Compatibility wrapper kept for the existing call sites: the colour they pass is now a tone
 * hint for the section's accent bar instead of the whole card's background.
 */
@Composable
fun OsintToolSectionCard(
    toolTitle: String,
    subtitle: String,
    containerColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    SectionCard(
        title = toolTitle,
        subtitle = subtitle,
        tone = toneFor(containerColor),
        content = content,
    )
}

/**
 * One OSINT finding, shared by the username and email result lists.
 *
 * The site name gets its own line so a long name can never squeeze the provenance chip, and the
 * verification facts sit on a short status row rather than a "status • confidence • HTTP"
 * sentence. Recheck is a quiet trailing action, not a footer button.
 */
@Composable
fun OsintResultCard(
    site: String,
    source: String,
    verificationStatus: String,
    confidence: String,
    evidence: String,
    isRechecking: Boolean,
    onClick: () -> Unit,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = site,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Space.sm))
                // Display-only trim: the chip stays short so the site name keeps its full width.
                Pill(
                    source.removePrefix("Python: ").removeSuffix(" upstream"),
                    tone = Tone.INFO,
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (verificationStatus.isNotBlank()) {
                    Pill(verificationStatus, tone = Tone.ACCENT)
                }
                if (confidence.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = confidence,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (evidence.isNotBlank()) {
                Text(
                    text = evidence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(top = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onRecheck,
                    enabled = !isRechecking,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = if (isRechecking) "Rechecking…" else "Recheck",
                        style = MaterialTheme.typography.labelMedium,
                    )
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

    SectionCard(
        title = title,
        subtitle = "Numbered queue · ${visitedUrls.size}/${distinctLinks.size} opened",
        tone = Tone.NEUTRAL,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            if (distinctLinks.isNotEmpty()) {
                val nextPair = distinctLinks.firstOrNull { it.second !in visitedUrls }
                AppButton(
                    text = nextPair?.let { "Open next #${distinctLinks.indexOf(it) + 1}" } ?: "All opened",
                    onClick = {
                        nextPair?.let { (_, url) ->
                            if (url !in visitedUrls) visitedUrls.add(url)
                            openUrl(context, url)
                        }
                    },
                    enabled = nextPair != null,
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    text = "Copy list",
                    onClick = {
                        val formatted = distinctLinks.mapIndexed { i, (label, url) ->
                            "${i + 1}\t$label - $url"
                        }.joinToString("\n")
                        copyToClipboard(context, "OSINT Links", formatted)
                    },
                    subdued = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (onExportPdf != null) {
            Spacer(Modifier.height(Space.sm))
            AppButton(
                text = "Export PDF report",
                onClick = onExportPdf,
                subdued = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (distinctLinks.isNotEmpty()) {
            Spacer(Modifier.height(Space.md))
            distinctLinks.take(40).forEachIndexed { index, (label, url) ->
                val isVisited = url in visitedUrls
                Surface(
                    onClick = {
                        if (!isVisited) visitedUrls.add(url)
                        openUrl(context, url)
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isVisited) 0.4f else 0.7f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Pill("#${index + 1}", tone = if (isVisited) Tone.NEUTRAL else Tone.ACCENT)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = url,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(Space.sm))
                        Text(
                            text = if (isVisited) "✓" else "↗",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isVisited) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            if (distinctLinks.size > 40) {
                Text(
                    "+${distinctLinks.size - 40} more links in the PDF report",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
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
    SectionCard(
        title = title,
        subtitle = subtitle,
        tone = toneFor(containerColor),
    ) {
        dorks.forEach { dork ->
            Surface(
                onClick = { openUrl(context, dork.url) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = dork.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = dork.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(Space.sm))
                    Pill(dork.toolSource, tone = Tone.NEUTRAL)
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

fun sanitizeUrlForOpening(url: String): String {
    val trimmed = url.trim()
    val lower = trimmed.lowercase()
    if (lower.contains("twitter.com/i/users/email_available") ||
        lower.contains("api.twitter.com") ||
        lower.contains("email_available.json") ||
        (lower.contains("twitter.com") && (lower.contains("/i/users/") || lower.endsWith(".json") || lower.contains(".json?"))) ||
        (lower.contains("x.com") && (lower.contains("/i/users/") || lower.endsWith(".json") || lower.contains(".json?")))
    ) {
        return "https://x.com/"
    }
    return trimmed
}

fun openUrl(context: Context, url: String) {
    try {
        val sanitized = sanitizeUrlForOpening(url)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sanitized))
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app available to handle this link", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open link: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
    }
}
