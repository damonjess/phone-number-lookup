package com.example.phone_number_lookup.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.phone_number_lookup.ui.components.AppButton
import com.example.phone_number_lookup.ui.components.LoadingRow
import com.example.phone_number_lookup.ui.components.Notice
import com.example.phone_number_lookup.ui.components.Pill
import com.example.phone_number_lookup.ui.components.ResultRow
import com.example.phone_number_lookup.ui.components.ScreenHeader
import com.example.phone_number_lookup.ui.components.SearchField
import com.example.phone_number_lookup.ui.components.SectionCard
import com.example.phone_number_lookup.ui.components.Space
import com.example.phone_number_lookup.ui.components.StatTile
import com.example.phone_number_lookup.ui.components.Tone
import com.example.phone_number_lookup.ui.components.openUrl
import com.example.phone_number_lookup.ui.viewmodels.GitHubOsintViewModel

@Composable
fun GitHubOsintScreen(
    viewModel: GitHubOsintViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
    ) {
        ScreenHeader(
            eyebrow = "GitHub",
            title = "Code & identity search",
            subtitle = "Live queries against the public GitHub API. No key, no account."
        )

        SearchField(
            value = uiState.query,
            onValueChange = { viewModel.onQueryChanged(it) },
            label = "Search GitHub",
            placeholder = "torvalds, @gmail.com, a project name",
            onSearch = { viewModel.searchUsers() },
            trailing = {
                if (uiState.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChanged("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear input")
                    }
                }
            },
        )

        Spacer(Modifier.height(Space.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            AppButton(
                text = "Search people",
                onClick = { viewModel.searchUsers() },
                modifier = Modifier.weight(1f),
            )
            AppButton(
                text = "Search repos",
                onClick = { viewModel.searchRepos() },
                subdued = true,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(Space.xl))

        if (
            uiState.query.isBlank() && !uiState.isScanning &&
            uiState.profile == null && uiState.users.isEmpty() &&
            uiState.repos.isEmpty() && uiState.error == null
        ) {
            Notice(
                "Search a handle, public email or project name. Results load the full profile or open the repository.",
                tone = Tone.NEUTRAL,
            )
            Spacer(Modifier.height(Space.xl))
        }

        if (uiState.isScanning) {
            LoadingRow("Querying GitHub…")
            Spacer(Modifier.height(Space.lg))
        }

        uiState.error?.let {
            Notice(it, tone = Tone.WARNING)
            Spacer(Modifier.height(Space.lg))
        }

        uiState.profile?.let { p ->
            SectionCard(
                title = "@${p.login}",
                subtitle = "Public profile, commit identities and published keys",
                tone = Tone.ACCENT,
            ) {
                if (p.publicRepos != null || p.followers != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        p.publicRepos?.let {
                            StatTile(it.toString(), "repositories", tone = Tone.ACCENT, modifier = Modifier.weight(1f))
                        }
                        p.followers?.let {
                            StatTile(it.toString(), "followers", tone = Tone.INFO, modifier = Modifier.weight(1f))
                        }
                        p.createdAt?.let {
                            StatTile(it.substringBefore("T").take(4), "joined", tone = Tone.NEUTRAL, modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(Space.md))
                }

                p.name?.let { ResultRow("Name", it) }
                p.bio?.let { ResultRow("Bio", it) }
                p.company?.let { ResultRow("Company", it) }
                p.location?.let { ResultRow("Location", it) }
                p.blog?.let { ResultRow("Website", it) }
                p.email?.let { ResultRow("Public email", it, mono = true) }
                p.twitterUsername?.let { ResultRow("X / Twitter", "@$it") }
                p.createdAt?.let { ResultRow("Joined", it.substringBefore("T")) }

                if (p.commitIdentities.isNotEmpty()) {
                    MetadataBlock("Commit author emails", p.commitIdentities)
                }
                if (p.gpgKeys.isNotEmpty()) {
                    MetadataBlock("Published GPG keys", p.gpgKeys)
                }
                if (p.sshKeys.isNotEmpty()) {
                    MetadataBlock("Published SSH keys (${p.sshKeys.size})", p.sshKeys)
                }

                Spacer(Modifier.height(Space.md))
                AppButton(
                    text = "Open on GitHub",
                    onClick = { openUrl(context, p.htmlUrl) },
                    subdued = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(Space.lg))
        }

        if (uiState.users.isNotEmpty()) {
            SectionCard(
                title = "${uiState.users.size} ${if (uiState.users.size == 1) "person" else "people"} matched",
                subtitle = "Tap a result to load the full profile",
                tone = Tone.INFO,
            ) {
                uiState.users.forEach { user ->
                    Surface(
                        onClick = { viewModel.loadProfile(user) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                contentColor = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(30.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = user.login.take(1).uppercase(),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = user.login,
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(Space.sm))
                            Text(
                                text = "Open ›",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Space.lg))
        }

        if (uiState.repos.isNotEmpty()) {
            SectionCard(
                title = "${uiState.repos.size} ${if (uiState.repos.size == 1) "repository" else "repositories"}",
                subtitle = "Sorted by GitHub relevance",
                tone = Tone.INFO,
            ) {
                uiState.repos.forEach { repo ->
                    Surface(
                        onClick = { openUrl(context, repo.htmlUrl) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                text = repo.fullName,
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            repo.description?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                repo.language?.let { Pill(it, Tone.NEUTRAL) }
                                repo.stars?.let { Pill("★ $it", Tone.WARNING) }
                                repo.forks?.let { Pill("⑂ $it", Tone.NEUTRAL) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(Space.lg))
        }

        Spacer(Modifier.height(Space.xl))
    }
}

@Composable
private fun MetadataBlock(title: String, values: List<String>) {
    Spacer(Modifier.height(Space.md))
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(4.dp))
    values.forEach { value ->
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}
