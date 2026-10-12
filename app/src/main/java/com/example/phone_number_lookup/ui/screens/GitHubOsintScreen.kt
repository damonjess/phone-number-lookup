package com.example.phone_number_lookup.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.phone_number_lookup.ui.components.OsintToolSectionCard
import com.example.phone_number_lookup.ui.components.ResultRow
import com.example.phone_number_lookup.ui.components.openUrl
import com.example.phone_number_lookup.ui.viewmodels.GitHubOsintViewModel

@Composable
fun GitHubOsintScreen(
    viewModel: GitHubOsintViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "GitHub OSINT",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Live queries against the public GitHub REST API (no key required)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        OutlinedTextField(
            value = uiState.query,
            onValueChange = { viewModel.onQueryChanged(it) },
            label = { Text("Username, name, org, email or keyword") },
            placeholder = { Text("e.g. torvalds or \"@gmail.com\"") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.searchUsers() }),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                if (uiState.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChanged("") }) {
                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear input")
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.searchUsers() },
                modifier = Modifier.weight(1f)
            ) { Text("Search Users") }

            OutlinedButton(
                onClick = { viewModel.searchRepos() },
                modifier = Modifier.weight(1f)
            ) { Text("Search Repos") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.isScanning) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("Querying GitHub...")
            }
        }

        uiState.error?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        uiState.profile?.let { p ->
            OsintToolSectionCard(
                toolTitle = "Profile: ${p.login}",
                subtitle = "Live GitHub profile data",
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                p.name?.let { ResultRow("Name", it) }
                ResultRow("Username", "@${p.login}")
                p.bio?.let { ResultRow("Bio", it) }
                p.company?.let { ResultRow("Company", it) }
                p.location?.let { ResultRow("Location", it) }
                p.blog?.let { ResultRow("Website", it) }
                p.email?.let { ResultRow("Public Email", it) }
                p.publicRepos?.let { ResultRow("Public Repos", it.toString()) }
                p.followers?.let { ResultRow("Followers", it.toString()) }
                p.createdAt?.let { ResultRow("Joined", it.substringBefore("T")) }
                TextButton(onClick = { openUrl(context, p.htmlUrl) }) { Text("Open GitHub Profile ↗") }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (uiState.users.isNotEmpty()) {
            Text(
                text = "${uiState.users.size} user(s) matched — tap to load full profile",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start).padding(bottom = 6.dp)
            )
            uiState.users.forEach { user ->
                OutlinedCard(
                    onClick = { viewModel.loadProfile(user) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(user.login, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                "tap for profile",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        if (uiState.repos.isNotEmpty()) {
            Text(
                text = "${uiState.repos.size} repositor(ies) matched",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start).padding(bottom = 6.dp)
            )
            uiState.repos.forEach { repo ->
                OutlinedCard(
                    onClick = { openUrl(context, repo.htmlUrl) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(repo.fullName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        repo.description?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            buildString {
                                repo.language?.let { append("$it  ") }
                                repo.stars?.let { append("★ $it  ") }
                                repo.forks?.let { append("⑂ $it") }
                            }.trim(),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}
