package com.example.phone_number_lookup.ui.viewmodels

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.phone_number_lookup.HistoryEntry
import com.example.phone_number_lookup.HistoryStore
import com.example.phone_number_lookup.NumberFactsProvider
import com.example.phone_number_lookup.PhoneReputation
import com.example.phone_number_lookup.PhoneNumberUtils
import com.example.phone_number_lookup.PhoneInfogaBuiltIn
import com.example.phone_number_lookup.PhoneInfogaClient
import com.example.phone_number_lookup.PhoneInfogaNative
import com.example.phone_number_lookup.PhoneInfogaNumverifyResult
import com.example.phone_number_lookup.PhoneInfogaReport
import com.example.phone_number_lookup.ReputationReport
import com.example.phone_number_lookup.RiskAssessor
import com.example.phone_number_lookup.RiskVerdict
import com.example.phone_number_lookup.SearchRepository
import com.example.phone_number_lookup.SearchResultItem
import com.example.phone_number_lookup.SourceHealth
import com.example.phone_number_lookup.SourceHealthResult
import com.example.phone_number_lookup.SpamReport
import com.example.phone_number_lookup.SpamScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PhoneLookupUiState(
    val phoneNumberInput: String = "",
    val resultNumber: String? = null,
    val scanStartedAt: Long = 0L,
    val searchResults: List<SearchResultItem> = emptyList(),
    val searchError: String? = null,
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val spamReport: SpamReport? = null,
    val isSpamScanning: Boolean = false,
    val reputationReports: List<ReputationReport> = emptyList(),
    val isReputationScanning: Boolean = false,
    val phoneInfogaResult: PhoneInfogaNumverifyResult? = null,
    val isPhoneInfogaScanning: Boolean = false,
    val history: List<HistoryEntry> = emptyList(),
    val verdict: RiskVerdict? = null,
    /** Address of the PhoneInfoga v2 server (kept across Clear and app restarts). */
    val phoneInfogaUrl: String = PhoneInfogaClient.DEFAULT_URL,
    val phoneInfogaReport: PhoneInfogaReport? = null,
    val isPhoneInfogaServerRunning: Boolean = false,
    val sourceHealth: List<SourceHealthResult> = emptyList(),
    val isCheckingHealth: Boolean = false,
)

class PhoneLookupViewModel(application: Application) : AndroidViewModel(application) {

    private val historyStore = HistoryStore(application)

    private val _uiState = MutableStateFlow(PhoneLookupUiState())
    val uiState: StateFlow<PhoneLookupUiState> = _uiState.asStateFlow()

    private val prefs: SharedPreferences? = runCatching {
        application.getSharedPreferences("phoneinfoga_prefs", Context.MODE_PRIVATE)
    }.getOrNull()

    init {
        _uiState.update {
            it.copy(
                history = historyStore.load(),
                phoneInfogaUrl = prefs?.getString(KEY_PHONEINFOGA_URL, null) ?: PhoneInfogaClient.DEFAULT_URL
            )
        }
    }

    private val scans = ScanCoordinator(viewModelScope)
    private var phoneInfogaJob: Job? = null

    fun onPhoneNumberInputChanged(input: String) {
        _uiState.update { it.copy(phoneNumberInput = input) }
    }

    fun scanNumber() {
        val input = _uiState.value.phoneNumberInput.trim()
        if (input.isBlank()) return

        _uiState.update {
            it.copy(
                resultNumber = input,
                scanStartedAt = System.currentTimeMillis(),
                hasSearched = true,
                isSearching = true,
                isSpamScanning = true,
                isReputationScanning = true,
                searchResults = emptyList(),
                searchError = null,
                spamReport = null,
                reputationReports = emptyList(),
                phoneInfogaResult = null,
                isPhoneInfogaScanning = false,
                phoneInfogaReport = null,
                isPhoneInfogaServerRunning = false,
            )
        }
        phoneInfogaJob?.cancel()

        val normalized = PhoneNumberUtils.normalize(input)
        val localFormat = if (normalized.startsWith("+44")) "0" + normalized.removePrefix("+44") else normalized
        val formats = PhoneNumberUtils.getFormattedFormats(normalized, localFormat)

        // Offline numbering-rule verdict appears instantly; online sources refine it below.
        refreshVerdict()

        // Scanning a second number while the first is still running must not let the
        // older lookup's answer land under the newer number's heading.
        val token = scans.supersede()

        scans.start(token) { active ->
            val response = SearchRepository.searchWeb(
                listOf(formats.e164, formats.international, formats.national, formats.rfc3966)
            )
            if (scans.isCurrent(active)) {
                _uiState.update {
                    it.copy(
                        searchResults = response.results,
                        searchError = response.error,
                        isSearching = false
                    )
                }
            }
        }

        scans.start(token) { active ->
            val report = SpamScanner.lookup(normalized)
            if (scans.isCurrent(active)) {
                _uiState.update { it.copy(spamReport = report, isSpamScanning = false) }
                refreshVerdict()
            }
        }

        scans.start(token) { active ->
            val reports = PhoneReputation.lookup(normalized)
            if (scans.isCurrent(active)) {
                _uiState.update { it.copy(reputationReports = reports, isReputationScanning = false) }
                refreshVerdict()
            }
        }
    }

    fun clear() {
        scans.cancelAll()
        phoneInfogaJob?.cancel()
        _uiState.value = PhoneLookupUiState(
            history = _uiState.value.history,
            phoneInfogaUrl = _uiState.value.phoneInfogaUrl
        )
    }

    fun saveCurrentToHistory(note: String) {
        val raw = _uiState.value.resultNumber ?: return
        val normalized = PhoneNumberUtils.normalize(raw)
        val label = PhoneNumberUtils.getAreaInfo(normalized) ?: PhoneNumberUtils.getCountry(normalized)
        _uiState.update { it.copy(history = historyStore.add(normalized, label, note)) }
        refreshVerdict()
    }

    fun clearHistory() {
        historyStore.clear()
        _uiState.update { it.copy(history = emptyList()) }
        refreshVerdict()
    }

    fun deleteHistoryEntry(id: Long) {
        _uiState.update { it.copy(history = historyStore.delete(id)) }
        refreshVerdict()
    }

    fun updateHistoryNote(id: Long, note: String) {
        _uiState.update { it.copy(history = historyStore.update(id, note = note)) }
    }

    fun updateHistoryStatus(id: Long, status: String) {
        _uiState.update { it.copy(history = historyStore.update(id, status = status)) }
        refreshVerdict()
    }

    fun rescanHistoryEntry(number: String) {
        onPhoneNumberInputChanged(number)
        scanNumber()
    }

    fun onPhoneInfogaUrlChanged(url: String) {
        prefs?.edit()?.putString(KEY_PHONEINFOGA_URL, url)?.apply()
        _uiState.update { it.copy(phoneInfogaUrl = url) }
    }

    /**
     * Runs PhoneInfoga: through the configured server when one is reachable, otherwise with the
     * built-in versions of its Google-dork and OVH scanners, so the button always gives a result.
     */
    fun runPhoneInfogaServer() {
        val state = _uiState.value
        val normalized = PhoneNumberUtils.normalize(state.resultNumber ?: state.phoneNumberInput)
        if (normalized.isBlank()) return

        val base = PhoneInfogaClient.normaliseBaseUrl(state.phoneInfogaUrl)
        phoneInfogaJob?.cancel()
        _uiState.update { it.copy(isPhoneInfogaServerRunning = true, phoneInfogaReport = null) }
        phoneInfogaJob = viewModelScope.launch {
            val serverReport = base?.let { PhoneInfogaClient.scan(it, normalized) }
            val report = if (serverReport != null && serverReport.error == null) {
                serverReport
            } else {
                val why = serverReport?.error ?: "No PhoneInfoga server address is set."
                PhoneInfogaBuiltIn.run(normalized).let { builtIn ->
                    builtIn.copy(note = "No PhoneInfoga server was used ($why) so the built-in scanners ran instead.")
                }
            }
            // Don't let an old run's answer land under a newer number.
            val current = PhoneNumberUtils.normalize(_uiState.value.resultNumber ?: _uiState.value.phoneNumberInput)
            if (current == normalized) {
                _uiState.update { it.copy(phoneInfogaReport = report, isPhoneInfogaServerRunning = false) }
            }
        }
    }

    /** Tests every lookup source once so failures aren't mistaken for "nothing found". */
    fun runSourceHealth(numverifyKey: String) {
        if (_uiState.value.isCheckingHealth) return
        _uiState.update { it.copy(isCheckingHealth = true, sourceHealth = emptyList()) }
        viewModelScope.launch {
            val results = SourceHealth.checkAll(_uiState.value.phoneInfogaUrl, numverifyKey)
            _uiState.update { it.copy(sourceHealth = results, isCheckingHealth = false) }
        }
    }

    private companion object {
        const val KEY_PHONEINFOGA_URL = "phoneinfoga_url"
    }

    /** Recomputes the combined verdict from whatever has arrived so far. */
    private fun refreshVerdict() {
        _uiState.update { s ->
            val raw = s.resultNumber ?: return@update s
            val normalized = PhoneNumberUtils.normalize(raw)
            val userStatus = s.history.firstOrNull { it.number == normalized }?.status
            s.copy(
                verdict = RiskAssessor.assess(
                    facts = NumberFactsProvider.from(normalized),
                    spam = s.spamReport,
                    reputation = s.reputationReports,
                    userStatus = userStatus,
                    stillChecking = s.isSpamScanning || s.isReputationScanning,
                )
            )
        }
    }

    fun runPhoneInfogaNumverify(apiKey: String) {
        val raw = _uiState.value.resultNumber ?: _uiState.value.phoneNumberInput
        val normalized = PhoneNumberUtils.normalize(raw)
        if (normalized.isBlank()) return
        _uiState.update { it.copy(isPhoneInfogaScanning = true, phoneInfogaResult = null) }
        viewModelScope.launch {
            val result = PhoneInfogaNative.queryNumverify(normalized, apiKey)
            _uiState.update { it.copy(phoneInfogaResult = result, isPhoneInfogaScanning = false) }
        }
    }
}
