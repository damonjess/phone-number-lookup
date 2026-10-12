package com.example.phone_number_lookup

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun GitHubOsintTab() {
    var query by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<GitHubUserResult>>(emptyList()) }
    var repos by remember { mutableStateOf<List<GitHubRepoResult>>(emptyList()) }
    var profile by remember { mutableStateOf<GitHubUserResult?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    fun finish(list: List<*>) {
        if (list.isEmpty()) {
            error = "No GitHub results returned (you may be rate-limited — the public API allows ~10 searches/min)."
        }
        isScanning = false
    }

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
            value = query,
            onValueChange = { query = it },
            label = { Text("Username, name, org, email or keyword") },
            placeholder = { Text("e.g. torvalds or \"@gmail.com\"") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    TextButton(onClick = { query = "" }) { Text("✕") }
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val q = query.trim()
                    if (q.isNotEmpty()) {
                        isScanning = true
                        error = null
                        profile = null
                        repos = emptyList()
                        users = emptyList()
                        coroutineScope.launch {
                            val result = GitHubOsint.searchUsers(q)
                            users = result
                            finish(result)
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("Search Users") }

            OutlinedButton(
                onClick = {
                    val q = query.trim()
                    if (q.isNotEmpty()) {
                        isScanning = true
                        error = null
                        profile = null
                        users = emptyList()
                        repos = emptyList()
                        coroutineScope.launch {
                            val result = GitHubOsint.searchRepos(q)
                            repos = result
                            finish(result)
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("Search Repos") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isScanning) {
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

        error?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        profile?.let { p ->
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

        if (users.isNotEmpty()) {
            Text(
                text = "${users.size} user(s) matched — tap to load full profile",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start).padding(bottom = 6.dp)
            )
            users.forEach { user ->
                OutlinedCard(
                    onClick = {
                        error = null
                        coroutineScope.launch {
                            profile = GitHubOsint.getUser(user.login) ?: user
                        }
                    },
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

        if (repos.isNotEmpty()) {
            Text(
                text = "${repos.size} repositor(ies) matched",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start).padding(bottom = 6.dp)
            )
            repos.forEach { repo ->
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
