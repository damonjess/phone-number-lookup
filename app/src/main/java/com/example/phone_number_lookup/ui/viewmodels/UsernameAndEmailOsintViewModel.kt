package com.example.phone_number_lookup.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.phone_number_lookup.BreachReport
import com.example.phone_number_lookup.DomainNetworkOsint
import com.example.phone_number_lookup.DomainOsintReport
import com.example.phone_number_lookup.EmailAttributionEngine
import com.example.phone_number_lookup.EmailAttributionReport
import com.example.phone_number_lookup.EmailDomainReport
import com.example.phone_number_lookup.EmailDomainScanner
import com.example.phone_number_lookup.EmailRegistrationScanner
import com.example.phone_number_lookup.EmailScanner
import com.example.phone_number_lookup.GoogleMetadataExtractor
import com.example.phone_number_lookup.GoogleMetadataReport
import com.example.phone_number_lookup.IdentityEnricher
import com.example.phone_number_lookup.IdentityEnrichmentReport
import com.example.phone_number_lookup.PythonOsintBridge
import com.example.phone_number_lookup.PythonToolMode
import com.example.phone_number_lookup.RegistrationHit
import com.example.phone_number_lookup.SiteIssue
import com.example.phone_number_lookup.UsernameHit
import com.example.phone_number_lookup.UsernameScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UsernameAndEmailOsintUiState(
    val subTabState: Int = 0, // 0: Username, 1: Email, 2: Domain & Network
    val pythonToolMode: PythonToolMode = PythonToolMode.ALL_PYTHON,
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
    val discardedCount: Int = 0,
    val registrationDiscarded: Int = 0,
    val registrationFailed: Boolean = false,
    val registrationNotice: String? = null,
    val usernameIssues: List<SiteIssue> = emptyList(),
    val isRetryingIssues: Boolean = false,
    val identityEnrichment: IdentityEnrichmentReport? = null,
    val isEnrichingIdentity: Boolean = false,
    val networkOsintReport: DomainOsintReport? = null,
    val isNetworkScanning: Boolean = false,
    val googleMetadata: GoogleMetadataReport? = null,
    val isGoogleMetadataScanning: Boolean = false,
    val attributionReport: EmailAttributionReport? = null,
)

class UsernameAndEmailOsintViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UsernameAndEmailOsintUiState())
    val uiState: StateFlow<UsernameAndEmailOsintUiState> = _uiState.asStateFlow()

    private val scans = ScanCoordinator(viewModelScope)

    fun onSubTabChanged(tabIndex: Int) {
        scans.cancelAll()
        _uiState.update {
            UsernameAndEmailOsintUiState(
                subTabState = tabIndex,
                scanDepth = it.scanDepth,
                pythonToolMode = it.pythonToolMode
            )
        }
    }

    fun onPythonToolModeChanged(mode: PythonToolMode) {
        _uiState.update { it.copy(pythonToolMode = mode) }
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
        val mode = _uiState.value.pythonToolMode
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
                identityEnrichment = null,
                isEnrichingIdentity = false,
                networkOsintReport = null,
                isNetworkScanning = false,
                googleMetadata = null,
                isGoogleMetadataScanning = false,
                attributionReport = null,
            )
        }

        when (currentSubTab) {
            0 -> runUsernameScan(target, depth, mode, token)
            1 -> runEmailScan(target, mode, token)
            else -> runDomainNetworkScan(target, token)
        }
    }

    private fun runUsernameScan(target: String, depth: Int, mode: PythonToolMode, token: Int) {
        _uiState.update { it.copy(isLiveScanning = true, isEnrichingIdentity = true) }

        scans.start(token) { active ->
            val limit = UsernameScanner.limitForDepth(depth)
            val outcome = try {
                PythonOsintBridge.scanUsername(getApplication(), target, mode, limit)
            } catch (e: Exception) {
                if (scans.isCurrent(active)) {
                    _uiState.update {
                        it.copy(liveError = "Embedded Python scan failed: ${e.message ?: "unknown error"}", isLiveScanning = false)
                    }
                }
                return@start
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
                            "Could not load site database in ${mode.label}. Check your connection and retry."
                        } else {
                            null
                        },
                        isLiveScanning = false,
                    )
                }
            }
        }

        scans.start(token) { active ->
            val enrichment = IdentityEnricher.enrich(target, isEmail = false)
            if (scans.isCurrent(active)) {
                _uiState.update {
                    it.copy(identityEnrichment = enrichment, isEnrichingIdentity = false)
                }
            }
        }
    }

    private fun runEmailScan(target: String, mode: PythonToolMode, token: Int) {
        _uiState.update {
            it.copy(
                isRegistrationScanning = true,
                isBreachScanning = true,
                isDomainScanning = true,
                isEnrichingIdentity = true,
                isGoogleMetadataScanning = true
            )
        }

        scans.start(token) { active ->
            val meta = try {
                GoogleMetadataExtractor.extract(target)
            } catch (e: Exception) {
                GoogleMetadataReport(
                    email = target,
                    accountStatus = "Google account status is unknown",
                    error = "Public calendar check failed: ${e.message ?: "unknown error"}"
                )
            }
            if (scans.isCurrent(active)) {
                _uiState.update {
                    it.copy(googleMetadata = meta, isGoogleMetadataScanning = false)
                }
            }
        }

        scans.start(token) { active ->
            // Email account discovery uses the upstream Blackbird Python scan core.
            val outcome = try {
                PythonOsintBridge.scanEmailBlackbirdPython(getApplication(), target)
            } catch (e: Exception) {
                if (scans.isCurrent(active)) {
                    _uiState.update {
                        it.copy(
                            registrationFailed = true,
                            registrationNotice = "Embedded Python email scan failed: ${e.message ?: "unknown error"}",
                            isRegistrationScanning = false
                        )
                    }
                }
                return@start
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

        scans.start(token) { active ->
            val attribution = EmailAttributionEngine.analyze(target)
            if (scans.isCurrent(active)) {
                _uiState.update { it.copy(attributionReport = attribution) }
            }
        }

        scans.start(token) { active ->
            val enrichment = IdentityEnricher.enrich(target, isEmail = true)
            if (scans.isCurrent(active)) {
                _uiState.update {
                    it.copy(identityEnrichment = enrichment, isEnrichingIdentity = false)
                }
            }
        }

        val handle = EmailRegistrationScanner.localPart(target) ?: return
        scans.start(token) { active ->
            _uiState.update { it.copy(isLiveScanning = true) }
            // Correlate the email local-part with public usernames using the selected Python toolset.
            val outcome = try {
                PythonOsintBridge.scanUsername(
                    getApplication(), handle, mode, UsernameScanner.STANDARD_LIMIT
                )
            } catch (e: Exception) {
                if (scans.isCurrent(active)) {
                    _uiState.update {
                        it.copy(liveError = "Embedded Python username correlation failed: ${e.message ?: "unknown error"}", isLiveScanning = false)
                    }
                }
                return@start
            }
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

    private fun runDomainNetworkScan(target: String, token: Int) {
        _uiState.update { it.copy(isNetworkScanning = true) }
        scans.start(token) { active ->
            val report = DomainNetworkOsint.scan(target)
            if (scans.isCurrent(active)) {
                _uiState.update {
                    it.copy(networkOsintReport = report, isNetworkScanning = false)
                }
            }
        }
    }

    fun clear() {
        scans.cancelAll()
        val currentSubTab = _uiState.value.subTabState
        val depth = _uiState.value.scanDepth
        val mode = _uiState.value.pythonToolMode
        _uiState.value = UsernameAndEmailOsintUiState(
            subTabState = currentSubTab,
            scanDepth = depth,
            pythonToolMode = mode
        )
    }

    fun retryBlockedUsernameSites() {
        val state = _uiState.value
        val target = state.activeTarget ?: return
        val before = state.usernameIssues
        if (state.isRetryingIssues || before.none { it.retryable }) return

        _uiState.update { it.copy(isRetryingIssues = true) }
        val token = scans.supersede()
        scans.start(token) { active ->
            val outcome = UsernameScanner.retry(target, before)
            if (scans.isCurrent(active)) {
                _uiState.update { s ->
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
    }

    fun recheckUsername(site: String, profileUrl: String = "") {
        val target = _uiState.value.activeTarget ?: return
        _uiState.update { it.copy(recheckingSite = site) }
        val token = scans.supersede()
        scans.start(token) { active ->
            val updated = UsernameScanner.recheck(target, site, profileUrl)
            if (scans.isCurrent(active)) {
                if (updated != null) {
                    _uiState.update { state ->
                        val remaining = state.liveHits.filterNot { it.site.equals(site, true) }
                        state.copy(
                            // A recheck that now says "not found" removes the stale hit instead
                            // of leaving a false positive in the results list.
                            liveHits = if (updated.verificationStatus == "Not found") remaining
                            else remaining + updated,
                            recheckingSite = null
                        )
                    }
                } else {
                    _uiState.update { it.copy(recheckingSite = null) }
                }
            }
        }
    }

    fun recheckEmail(site: String) {
        val target = _uiState.value.activeTarget ?: return
        _uiState.update { it.copy(recheckingSite = site) }
        val token = scans.supersede()
        scans.start(token) { active ->
            val updated = EmailRegistrationScanner.recheck(target, site)
            if (scans.isCurrent(active)) {
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
}
