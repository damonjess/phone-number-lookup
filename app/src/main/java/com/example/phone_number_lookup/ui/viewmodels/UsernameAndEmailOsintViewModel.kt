package com.example.phone_number_lookup.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.phone_number_lookup.BreachReport
import com.example.phone_number_lookup.EmailRegistrationScanner
import com.example.phone_number_lookup.EmailScanner
import com.example.phone_number_lookup.EmailDomainReport
import com.example.phone_number_lookup.EmailDomainScanner
import com.example.phone_number_lookup.RegistrationHit
import com.example.phone_number_lookup.SiteIssue
import com.example.phone_number_lookup.UsernameHit
import com.example.phone_number_lookup.UsernameScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UsernameAndEmailOsintUiState(
    val subTabState: Int = 0, // 0: Username, 1: Email
    val targetInput: String = "",
    val activeTarget: String? = null,
    val liveHits: List<UsernameHit> = emptyList(),
    val liveSitesChecked: Int = 0,
    val isLiveScanning: Boolean = false,
    val liveError: String? = null,
    val breachReport: BreachReport? = null,
    val isBreachScanning: Boolean = false,
    val scanDepth: Int = 0, // 0: Quick (150), 1: Standard (300), 2: Full
    val scanProgress: Int = 0,
    val scanTotal: Int = 0,
    val datasetSizes: Map<String, Int> = emptyMap(),
    val unreachableCount: Int = 0,
    val registeredSites: List<RegistrationHit> = emptyList(),
    val isRegistrationScanning: Boolean = false,
    val registrationChecked: Int = 0,
    val emailUnreachable: Int = 0,
    val recheckingSite: String? = null,
    val domainReport: EmailDomainReport? = null,
    val isDomainScanning: Boolean = false,
    val publicSuggestions: List<String> = emptyList(),
    val correlationHints: List<String> = emptyList(),
    /** Username sites that said "found" for a made-up name and were ignored as unreliable. */
    val discardedCount: Int = 0,
    val registrationDiscarded: Int = 0,
    /** True when the registration check could not run at all (not "nothing found"). */
    val registrationFailed: Boolean = false,
    /** Error or warning for the registration check, e.g. incomplete site list. */
    val registrationNotice: String? = null,
    /** Username sites that couldn't be checked automatically, with the reason for each. */
    val usernameIssues: List<SiteIssue> = emptyList(),
    val isRetryingIssues: Boolean = false,
)

class UsernameAndEmailOsintViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UsernameAndEmailOsintUiState())
    val uiState: StateFlow<UsernameAndEmailOsintUiState> = _uiState.asStateFlow()

    private val scans = ScanCoordinator(viewModelScope)

    fun onSubTabChanged(tabIndex: Int) {
        // The sub-tabs are separate investigations, so anything still running belongs to
        // the tab being left and must not publish into the one being opened.
        scans.cancelAll()
        _uiState.update {
            UsernameAndEmailOsintUiState(subTabState = tabIndex, scanDepth = it.scanDepth)
        }
    }

    fun onTargetInputChanged(input: String) {
        _uiState.update { it.copy(targetInput = input) }
    }

    fun onScanDepthChanged(depth: Int) {
        _uiState.update { it.copy(scanDepth = depth) }
    }

    fun runScan() {
        val target = _uiState.value.targetInput.trim()
        if (target.isBlank()) return

        val currentSubTab = _uiState.value.subTabState
        val depth = _uiState.value.scanDepth
        val token = scans.supersede()

        _uiState.update {
            it.copy(
                activeTarget = target,
                liveHits = emptyList(),
                liveSitesChecked = 0,
                liveError = null,
                scanProgress = 0,
                scanTotal = 0,
                breachReport = null,
                registeredSites = emptyList(),
                registrationChecked = 0,
                emailUnreachable = 0,
                domainReport = null,
                isDomainScanning = false,
                publicSuggestions = emptyList(),
                correlationHints = emptyList(),
                discardedCount = 0,
                registrationDiscarded = 0,
                registrationFailed = false,
                registrationNotice = null,
                usernameIssues = emptyList(),
                isRetryingIssues = false,
            )
        }

        if (currentSubTab == 0) {
            runUsernameScan(target, depth, token)
        } else {
            runEmailScan(target, token)
        }
    }

    private fun runUsernameScan(target: String, depth: Int, token: Int) {
        _uiState.update { it.copy(isLiveScanning = true) }
        scans.start(token) { active ->
            val limit = UsernameScanner.limitForDepth(depth)
            val outcome = UsernameScanner.scan(target, limit = limit) { checked, total ->
                if (scans.isCurrent(active)) {
                    _uiState.update { it.copy(scanProgress = checked, scanTotal = total) }
                }
            }
            if (scans.isCurrent(active)) {
                _uiState.update {
                    it.copy(
                        liveHits = outcome.confirmed,
                        liveSitesChecked = outcome.sitesChecked,
                        datasetSizes = outcome.datasetSizes,
                        unreachableCount = outcome.unreachable,
                        usernameIssues = outcome.issues,
                        discardedCount = outcome.discarded.size,
                        liveError = if (outcome.sitesChecked == 0) {
                            "Could not load any site database. Check your connection and retry."
                        } else {
                            null
                        },
                        isLiveScanning = false,
                    )
                }
            }
        }
    }

    private fun runEmailScan(target: String, token: Int) {
        _uiState.update { it.copy(isRegistrationScanning = true, isBreachScanning = true, isDomainScanning = true) }

        scans.start(token) { active ->
            val outcome = EmailRegistrationScanner.scan(target) { checked, _ ->
                if (scans.isCurrent(active)) {
                    _uiState.update { it.copy(registrationChecked = checked) }
                }
            }
            if (scans.isCurrent(active)) {
                _uiState.update {
                    it.copy(
                        registeredSites = outcome.registered,
                        emailUnreachable = outcome.unreachable,
                        registrationFailed = outcome.error != null,
                        registrationNotice = outcome.error ?: outcome.notice,
                        registrationDiscarded = outcome.discarded.size,
                        isRegistrationScanning = false,
                    )
                }
            }
        }

        scans.start(token) { active ->
            val report = EmailScanner.check(target)
            if (scans.isCurrent(active)) {
                _uiState.update { it.copy(breachReport = report, isBreachScanning = false) }
            }
        }

        scans.start(token) { active ->
            val report = EmailDomainScanner.check(target)
            if (scans.isCurrent(active)) {
                _uiState.update { it.copy(domainReport = report, isDomainScanning = false) }
            }
        }

        // The handle before the @ is frequently reused, so scan it as a username too.
        val handle = EmailRegistrationScanner.localPart(target) ?: return
        scans.start(token) { active ->
            _uiState.update { it.copy(isLiveScanning = true) }
            val outcome = UsernameScanner.scanWithVariants(handle, limit = UsernameScanner.QUICK_LIMIT)
            if (scans.isCurrent(active)) {
                _uiState.update {
                    it.copy(
                        liveHits = outcome.confirmed,
                        liveSitesChecked = outcome.sitesChecked,
                        publicSuggestions = outcome.confirmed.take(8).map { "${it.site}: ${it.profileUrl}" },
                        correlationHints = buildList {
                            if (outcome.namesChecked.size > 1) add("Also tried likely spellings on popular sites: ${outcome.namesChecked.drop(1).joinToString(", ")}.")
                            val categories = outcome.confirmed.map { it.category }.distinct()
                            if (outcome.confirmed.size >= 2) add("The handle matched ${outcome.confirmed.size} public profiles across ${categories.size} category group(s).")
                            if (outcome.confirmed.map { it.source }.distinct().size >= 2) add("Independent public datasets reported overlapping matches; this is a correlation hint, not identity proof.")
                        },
                        isLiveScanning = false,
                    )
                }
            }
        }
    }

    fun clear() {
        scans.cancelAll()
        val currentSubTab = _uiState.value.subTabState
        val depth = _uiState.value.scanDepth
        _uiState.value = UsernameAndEmailOsintUiState(subTabState = currentSubTab, scanDepth = depth)
    }

    /** Re-checks only the sites that failed for a temporary reason, 2 at a time. */
    fun retryBlockedUsernameSites() {
        val state = _uiState.value
        val target = state.activeTarget ?: return
        val before = state.usernameIssues
        if (state.isRetryingIssues || before.none { it.retryable }) return

        _uiState.update { it.copy(isRetryingIssues = true) }
        viewModelScope.launch {
            val outcome = UsernameScanner.retry(target, before)
            _uiState.update { s ->
                // A new scan started meanwhile: don't mix this answer into it.
                if (s.usernameIssues !== before) return@update s.copy(isRetryingIssues = false)
                val retried = before.filter { it.retryable }.map { it.site.lowercase() }.toSet()
                val remaining = s.usernameIssues.filterNot { it.site.lowercase() in retried } + outcome.issues
                s.copy(
                    liveHits = (s.liveHits + outcome.confirmed)
                        .sortedWith(compareBy({ it.category.lowercase() }, { it.site.lowercase() })),
                    usernameIssues = remaining,
                    unreachableCount = remaining.size,
                    discardedCount = s.discardedCount + outcome.discarded.size,
                    isRetryingIssues = false,
                )
            }
        }
    }

    fun recheckUsername(site: String) {
        val target = _uiState.value.activeTarget ?: return
        _uiState.update { it.copy(recheckingSite = site) }
        viewModelScope.launch {
            val updated = UsernameScanner.recheck(target, site)
            if (updated != null) {
                _uiState.update { state ->
                    state.copy(
                        liveHits = state.liveHits.filterNot { it.site.equals(site, true) } + updated,
                        recheckingSite = null
                    )
                }
            } else {
                _uiState.update { it.copy(recheckingSite = null) }
            }
        }
    }

    fun recheckEmail(site: String) {
        val target = _uiState.value.activeTarget ?: return
        _uiState.update { it.copy(recheckingSite = site) }
        viewModelScope.launch {
            val updated = EmailRegistrationScanner.recheck(target, site)
            if (updated != null) {
                _uiState.update { state ->
                    state.copy(
                        registeredSites = state.registeredSites.filterNot { it.site.equals(site, true) } + updated,
                        recheckingSite = null
                    )
                }
            } else {
                _uiState.update { it.copy(recheckingSite = null) }
            }
        }
    }
}
