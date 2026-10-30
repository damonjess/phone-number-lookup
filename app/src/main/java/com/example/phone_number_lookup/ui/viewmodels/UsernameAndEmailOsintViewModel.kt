package com.example.phone_number_lookup.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.phone_number_lookup.BreachReport
import com.example.phone_number_lookup.DeepProfileScraper
import com.example.phone_number_lookup.DomainNetworkOsint
import com.example.phone_number_lookup.DomainOsintReport
import com.example.phone_number_lookup.EmailAttributionEngine
import com.example.phone_number_lookup.EmailAttributionReport
import com.example.phone_number_lookup.EmailDomainReport
import com.example.phone_number_lookup.EmailDomainScanner
import com.example.phone_number_lookup.EmailRegistrationScanner
import com.example.phone_number_lookup.EmailScanner
import com.example.phone_number_lookup.EnrichedProfileCard
import com.example.phone_number_lookup.GoogleMetadataExtractor
import com.example.phone_number_lookup.GoogleMetadataReport
import com.example.phone_number_lookup.IdentityEnricher
import com.example.phone_number_lookup.IdentityEnrichmentReport
import com.example.phone_number_lookup.OpenWebOsintScraper
import com.example.phone_number_lookup.PublicProfileApis
import com.example.phone_number_lookup.PythonOsintBridge
import com.example.phone_number_lookup.PythonToolMode
import com.example.phone_number_lookup.RegistrationHit
import com.example.phone_number_lookup.ScrapedWebReport
import com.example.phone_number_lookup.SiteIssue
import com.example.phone_number_lookup.UsernameHit
import com.example.phone_number_lookup.UsernameScanner
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UsernameAndEmailOsintUiState(
    val subTabState: Int = 0, // 0: Username, 1: Email, 2: Domain & Network
    val pythonToolMode: PythonToolMode = PythonToolMode.ALL_PYTHON,
    val targetInput: String = "",
    val activeTarget: String? = null,
    val scanStartedAt: Long = 0L,
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
    val registrationSitesTotal: Int = 0,
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
    val registrationIssues: List<SiteIssue> = emptyList(),
    val isRetryingRegistrationIssues: Boolean = false,
    val usernameIssues: List<SiteIssue> = emptyList(),
    val isRetryingIssues: Boolean = false,
    val identityEnrichment: IdentityEnrichmentReport? = null,
    val isEnrichingIdentity: Boolean = false,
    val networkOsintReport: DomainOsintReport? = null,
    val isNetworkScanning: Boolean = false,
    val googleMetadata: GoogleMetadataReport? = null,
    val isGoogleMetadataScanning: Boolean = false,
    val attributionReport: EmailAttributionReport? = null,
    val isAttributionScanning: Boolean = false,
    val enrichedProfiles: List<EnrichedProfileCard> = emptyList(),
    val openWebReport: ScrapedWebReport? = null,
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
        val scanStartedAt = System.currentTimeMillis()
        val token = scans.supersede()

        _uiState.update {
            it.copy(
                activeTarget = target,
                scanStartedAt = scanStartedAt,
                liveHits = emptyList(),
                liveSitesChecked = 0,
                liveError = null,
                scanProgress = 0,
                scanTotal = 0,
                breachReport = null,
                registeredSites = emptyList(),
                registrationChecked = 0,
                registrationSitesTotal = 0,
                emailUnreachable = 0,
                domainReport = null,
                isDomainScanning = false,
                publicSuggestions = emptyList(),
                correlationHints = emptyList(),
                discardedCount = 0,
                registrationDiscarded = 0,
                registrationFailed = false,
                registrationNotice = null,
                registrationIssues = emptyList(),
                isRetryingRegistrationIssues = false,
                usernameIssues = emptyList(),
                isRetryingIssues = false,
                identityEnrichment = null,
                isEnrichingIdentity = false,
                networkOsintReport = null,
                isNetworkScanning = false,
                googleMetadata = null,
                isGoogleMetadataScanning = false,
                attributionReport = null,
                isAttributionScanning = false,
                enrichedProfiles = emptyList(),
                openWebReport = null,
            )
        }

        when (currentSubTab) {
            0 -> runUsernameScan(target, depth, mode, token)
            1 -> runEmailScan(target, mode, token)
            else -> runDomainNetworkScan(target, token)
        }
    }

    /**
     * Merges any phones, locations, names, or handles discovered by Holehe, DeepProfileScraper,
     * or OpenWebOsintScraper directly into `attributionReport` so the top dashboard cards
     * ("Phone numbers" and "Locations") always display every discovered value.
     */
    private fun mergeIntoAttribution(
        extraPhones: List<com.example.phone_number_lookup.AttributedValue> = emptyList(),
        extraLocations: List<com.example.phone_number_lookup.AttributedValue> = emptyList(),
        extraNames: List<com.example.phone_number_lookup.AttributedValue> = emptyList(),
        extraUsernames: List<com.example.phone_number_lookup.AttributedValue> = emptyList()
    ) {
        if (extraPhones.isEmpty() && extraLocations.isEmpty() && extraNames.isEmpty() && extraUsernames.isEmpty()) return
        _uiState.update { state ->
            val base = state.attributionReport ?: EmailAttributionReport(email = state.activeTarget.orEmpty())
            val mergedPhones = (base.phonesFound + extraPhones)
                .distinctBy { it.value.lowercase().trim() }
            val mergedLocations = (base.locationsFound + extraLocations)
                .distinctBy { it.value.lowercase().trim() }
            val mergedNames = (base.namesFound + extraNames)
                .distinctBy { "${it.value.lowercase()}|${it.source.lowercase()}" }
            val mergedUsernames = (base.usernamesFound + extraUsernames)
                .distinctBy { it.value.removePrefix("@").lowercase() }
            state.copy(
                attributionReport = base.copy(
                    phonesFound = mergedPhones,
                    locationsFound = mergedLocations,
                    namesFound = mergedNames,
                    usernamesFound = mergedUsernames
                )
            )
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
                        it.copy(
                            liveError = "Embedded Python scan failed: ${e.message ?: "unknown error"}",
                            isLiveScanning = false
                        )
                    }
                }
                return@start
            }

            val apiHits = runCatching { PublicProfileApis.lookup(target) }.getOrDefault(emptyList())
            val mergedHits = (outcome.confirmed + apiHits)
                .distinctBy { it.profileUrl.lowercase().trimEnd('/') }

            if (scans.isCurrent(active)) {
                _uiState.update {
                    it.copy(
                        liveHits = mergedHits,
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

            coroutineScope {
                val dossiersDef = async {
                    runCatching {
                        DeepProfileScraper.scrapeUsernameDossier(target, mergedHits)
                    }.getOrDefault(emptyList())
                }
                val webMentionsDef = async {
                    runCatching {
                        OpenWebOsintScraper.scrapeAll(listOf(target))
                    }.getOrNull()
                }

                val dossiers = dossiersDef.await()
                val webMentions = webMentionsDef.await()

                if (!scans.isCurrent(active)) return@coroutineScope

                _uiState.update { state ->
                    state.copy(
                        enrichedProfiles = dossiers,
                        openWebReport = webMentions
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
                isGoogleMetadataScanning = true,
                isAttributionScanning = true
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
            val outcome = try {
                val nativeOutcome = EmailRegistrationScanner.scan(target) { checked, total ->
                    if (scans.isCurrent(active)) {
                        _uiState.update { it.copy(registrationChecked = checked, registrationSitesTotal = total) }
                    }
                }
                val holeheOutcome = PythonOsintBridge.scanEmailHolehePython(getApplication(), target)
                val holehePhones = holeheOutcome.registered.mapNotNull { hit ->
                    val marker = "Recovery phone: "
                    if (marker in hit.evidence) {
                        val phone = hit.evidence.substringAfter(marker).substringBefore(" |").substringBefore(" •").trim()
                        if (phone.isNotBlank()) com.example.phone_number_lookup.AttributedValue(phone, "Holehe (${hit.site})") else null
                    } else null
                }
                if (scans.isCurrent(active)) {
                    mergeIntoAttribution(extraPhones = holehePhones)
                }
                val nativeConfirmedNames = nativeOutcome.registered
                    .filter { it.verificationStatus == "Registered response" }
                    .map { it.site.lowercase() }
                    .toSet()
                val holeheOnly = holeheOutcome.registered.filterNot { it.site.lowercase() in nativeConfirmedNames }
                nativeOutcome.copy(
                    sitesChecked = nativeOutcome.sitesChecked + holeheOutcome.sitesChecked,
                    registered = (nativeOutcome.registered + holeheOnly)
                        .distinctBy { "${it.site.lowercase()}|${it.verificationStatus}" },
                    unreachable = nativeOutcome.unreachable + holeheOutcome.unreachable,
                    notice = listOfNotNull(
                        nativeOutcome.notice,
                        holeheOutcome.error,
                        if (holeheOnly.isNotEmpty()) "Holehe-only results are upstream reports and are not independently confirmed." else null
                    ).joinToString(" ").takeIf { it.isNotBlank() }
                )
            } catch (e: Exception) {
                if (scans.isCurrent(active)) {
                    _uiState.update {
                        it.copy(
                            registrationFailed = true,
                            registrationNotice = "Email registration scan failed: ${e.message ?: "unknown error"}",
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
                        registrationSitesTotal = outcome.sitesChecked,
                        emailUnreachable = outcome.unreachable,
                        registrationFailed = outcome.error != null,
                        registrationNotice = outcome.error ?: outcome.notice,
                        registrationDiscarded = outcome.discarded.size,
                        registrationIssues = outcome.issues,
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
                _uiState.update { it.copy(attributionReport = attribution, isAttributionScanning = false) }
                // Re-merge any already-finished web or dossier hits
                val cur = _uiState.value
                mergeIntoAttribution(
                    extraPhones = cur.openWebReport?.extractedPhones.orEmpty() +
                        cur.enrichedProfiles.flatMap { c -> c.discoveredPhones.map { com.example.phone_number_lookup.AttributedValue(it, c.platform) } },
                    extraLocations = cur.enrichedProfiles.mapNotNull { c -> c.location?.let { com.example.phone_number_lookup.AttributedValue(it, c.platform) } }
                )
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

        scans.start(token) { active ->
            val handle = EmailRegistrationScanner.localPart(target)
            val queries = listOfNotNull(target, handle?.takeIf { it.length >= 4 })
            val openWeb = runCatching { OpenWebOsintScraper.scrapeAll(queries) }.getOrNull()
            if (scans.isCurrent(active)) {
                _uiState.update { it.copy(openWebReport = openWeb) }
                if (openWeb != null) {
                    mergeIntoAttribution(
                        extraPhones = openWeb.extractedPhones,
                        extraUsernames = openWeb.extractedHandles
                    )
                }
            }
        }

        val handle = EmailRegistrationScanner.localPart(target) ?: return
        scans.start(token) { active ->
            _uiState.update { it.copy(isLiveScanning = true) }
            val outcome = try {
                PythonOsintBridge.scanUsername(
                    getApplication(), handle, mode, UsernameScanner.STANDARD_LIMIT
                )
            } catch (e: Exception) {
                if (scans.isCurrent(active)) {
                    _uiState.update {
                        it.copy(
                            liveError = "Embedded Python username correlation failed: ${e.message ?: "unknown error"}",
                            isLiveScanning = false
                        )
                    }
                }
                return@start
            }
            val apiHits = runCatching { PublicProfileApis.lookup(handle) }.getOrDefault(emptyList())
            val mergedHits = (outcome.confirmed + apiHits)
                .distinctBy { it.profileUrl.lowercase().trimEnd('/') }

            if (scans.isCurrent(active)) {
                _uiState.update {
                    it.copy(
                        liveHits = mergedHits,
                        liveSitesChecked = outcome.sitesChecked,
                        publicSuggestions = mergedHits.take(8).map { "${it.site}: ${it.profileUrl}" },
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

            if (handle.length >= 4) {
                val handleDossiers = runCatching {
                    DeepProfileScraper.scrapeUsernameDossier(handle, mergedHits)
                }.getOrDefault(emptyList())
                if (scans.isCurrent(active)) {
                    _uiState.update { it.copy(enrichedProfiles = handleDossiers) }
                    mergeIntoAttribution(
                        extraPhones = handleDossiers.flatMap { c ->
                            c.discoveredPhones.map { com.example.phone_number_lookup.AttributedValue(it, c.platform) }
                        },
                        extraLocations = handleDossiers.mapNotNull { c ->
                            c.location?.let { com.example.phone_number_lookup.AttributedValue(it, c.platform) }
                        },
                        extraNames = handleDossiers.mapNotNull { c ->
                            c.displayName?.let { com.example.phone_number_lookup.AttributedValue(it, c.platform) }
                        }
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
        val token = scans.currentGeneration
        _uiState.update { it.copy(recheckingSite = site) }
        scans.start(token) { active ->
            try {
                val updated = UsernameScanner.recheck(target, site, profileUrl)
                if (!scans.isCurrent(active) || _uiState.value.activeTarget != target) return@start
                _uiState.update { state ->
                    if (updated != null) {
                        val remaining = state.liveHits.filterNot { it.site.equals(site, true) }
                        state.copy(
                            liveHits = if (updated.verificationStatus == "Not found" || updated.verificationStatus == "Unavailable / uncertain") remaining
                            else remaining + updated
                        )
                    } else state
                }
            } finally {
                if (scans.isCurrent(active)) {
                    _uiState.update { it.copy(recheckingSite = null) }
                }
            }
        }
    }

    fun recheckEmail(site: String) {
        val target = _uiState.value.activeTarget ?: return
        val token = scans.currentGeneration
        _uiState.update { it.copy(recheckingSite = site) }
        scans.start(token) { active ->
            try {
                val updated = EmailRegistrationScanner.recheck(target, site)
                if (!scans.isCurrent(active) || _uiState.value.activeTarget != target) return@start
                _uiState.update { state ->
                    if (updated != null) {
                        val remaining = state.registeredSites.filterNot { it.site.equals(site, true) }
                        state.copy(
                            registeredSites = if (updated.verificationStatus == "Not found" || updated.verificationStatus == "Unavailable / uncertain") remaining
                            else remaining + updated,
                            registrationIssues = state.registrationIssues.filterNot { it.site.equals(site, true) }
                        )
                    } else state
                }
            } finally {
                if (scans.isCurrent(active)) {
                    _uiState.update { it.copy(recheckingSite = null) }
                }
            }
        }
    }

    fun retryEmailIssues() {
        val state = _uiState.value
        val target = state.activeTarget ?: return
        val before = state.registrationIssues
        if (state.isRetryingRegistrationIssues || before.none { it.retryable }) return
        _uiState.update { it.copy(isRetryingRegistrationIssues = true) }
        val token = scans.currentGeneration
        scans.start(token) { active ->
            try {
                val outcome = EmailRegistrationScanner.retry(target, before)
                if (!scans.isCurrent(active) || _uiState.value.activeTarget != target) return@start
                _uiState.update { current ->
                    val retriedNames = before.filter { it.retryable }.map { it.site.lowercase() }.toSet()
                    val remaining = current.registrationIssues
                        .filterNot { it.site.lowercase() in retriedNames } + outcome.issues
                    current.copy(
                        registeredSites = (current.registeredSites.filterNot { hit ->
                            outcome.registered.any { it.site.equals(hit.site, true) }
                        } + outcome.registered).distinctBy { it.site.lowercase() },
                        registrationIssues = remaining.distinctBy { it.site.lowercase() },
                        registrationChecked = (current.registrationChecked + outcome.sitesChecked).coerceAtMost(current.registrationSitesTotal),
                        emailUnreachable = remaining.size,
                        registrationDiscarded = current.registrationDiscarded + outcome.discarded.size,
                        isRetryingRegistrationIssues = false,
                    )
                }
            } finally {
                if (scans.isCurrent(active) && _uiState.value.activeTarget == target) {
                    _uiState.update { it.copy(isRetryingRegistrationIssues = false) }
                }
            }
        }
    }
}