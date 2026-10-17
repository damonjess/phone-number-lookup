package com.example.phone_number_lookup.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.phone_number_lookup.GitHubCommitMatch
import com.example.phone_number_lookup.GitHubOsint
import com.example.phone_number_lookup.GitHubRepoResult
import com.example.phone_number_lookup.GitHubUserResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class GitHubOsintUiState(
    val query: String = "",
    val users: List<GitHubUserResult> = emptyList(),
    val repos: List<GitHubRepoResult> = emptyList(),
    val commits: List<GitHubCommitMatch> = emptyList(),
    val profile: GitHubUserResult? = null,
    val isScanning: Boolean = false,
    val error: String? = null,
)

class GitHubOsintViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(GitHubOsintUiState())
    val uiState: StateFlow<GitHubOsintUiState> = _uiState.asStateFlow()

    private val scans = ScanCoordinator(viewModelScope)

    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun searchUsers() {
        val q = _uiState.value.query.trim()
        if (q.isBlank()) return

        _uiState.update {
            it.copy(
                isScanning = true,
                error = null,
                profile = null,
                users = emptyList(),
                repos = emptyList(),
                commits = emptyList(),
            )
        }

        val token = scans.supersede()
        scans.start(token) { active ->
            val result = GitHubOsint.searchUsers(q)
            if (scans.isCurrent(active)) {
                _uiState.update {
                    it.copy(
                        users = result,
                        isScanning = false,
                        error = if (result.isEmpty()) "No GitHub users returned (you may be rate-limited — the public API allows ~10 searches/min)." else null,
                    )
                }
            }
        }
    }

    fun searchRepos() {
        val q = _uiState.value.query.trim()
        if (q.isBlank()) return

        _uiState.update {
            it.copy(
                isScanning = true,
                error = null,
                profile = null,
                users = emptyList(),
                repos = emptyList(),
                commits = emptyList(),
            )
        }

        val token = scans.supersede()
        scans.start(token) { active ->
            val result = GitHubOsint.searchRepos(q)
            if (scans.isCurrent(active)) {
                _uiState.update {
                    it.copy(
                        repos = result,
                        isScanning = false,
                        error = if (result.isEmpty()) "No GitHub repositories returned (you may be rate-limited — the public API allows ~10 searches/min)." else null,
                    )
                }
            }
        }
    }

    fun searchCommits() {
        val q = _uiState.value.query.trim()
        if (q.isBlank()) return

        _uiState.update {
            it.copy(
                isScanning = true,
                error = null,
                profile = null,
                users = emptyList(),
                repos = emptyList(),
                commits = emptyList(),
            )
        }

        val token = scans.supersede()
        scans.start(token) { active ->
            val result = GitHubOsint.searchCommits(q)
            if (scans.isCurrent(active)) {
                _uiState.update {
                    it.copy(
                        commits = result,
                        isScanning = false,
                        error = if (result.isEmpty()) "No GitHub commits matched (you may be rate-limited — the public API allows ~10 searches/min)." else null,
                    )
                }
            }
        }
    }

    fun loadProfile(user: GitHubUserResult) {
        _uiState.update { it.copy(error = null) }
        val token = scans.supersede()
        scans.start(token) { active ->
            val profile = GitHubOsint.getUser(user.login) ?: user
            if (scans.isCurrent(active)) {
                _uiState.update { it.copy(profile = profile) }
            }
        }
    }

    fun clear() {
        scans.cancelAll()
        _uiState.value = GitHubOsintUiState()
    }
}
