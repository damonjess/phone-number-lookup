package com.example.phone_number_lookup.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.phone_number_lookup.HistoryEntry
import com.example.phone_number_lookup.HistoryStore
import com.example.phone_number_lookup.NumberFactsProvider
import com.example.phone_number_lookup.PhoneReputation
import com.example.phone_number_lookup.PhoneNumberUtils
import com.example.phone_number_lookup.PhoneInfogaNative
import com.example.phone_number_lookup.PhoneInfogaNumverifyResult
import com.example.phone_number_lookup.ReputationReport
import com.example.phone_number_lookup.RiskAssessor
import com.example.phone_number_lookup.RiskVerdict
import com.example.phone_number_lookup.SearchRepository
import com.example.phone_number_lookup.SearchResultItem
import com.example.phone_number_lookup.SpamReport
import com.example.phone_number_lookup.SpamScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PhoneLookupUiState(
    val phoneNumberInput: String = "",
    val resultNumber: String? = null,
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
)

class PhoneLookupViewModel(application: Application) : AndroidViewModel(application) {

    private val historyStore = HistoryStore(application)

    private val _uiState = MutableStateFlow(PhoneLookupUiState())
    val uiState: StateFlow<PhoneLookupUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(history = historyStore.load()) }
    }

    private val scans = ScanCoordinator(viewModelScope)

    fun onPhoneNumberInputChanged(input: String) {
        _uiState.update { it.copy(phoneNumberInput = input) }
    }

    fun scanNumber() {
        val input = _uiState.value.phoneNumberInput.trim()
        if (input.isBlank()) return

        _uiState.update {
            it.copy(
                resultNumber = input,
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
            )
        }

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
        _uiState.value = PhoneLookupUiState(history = _uiState.value.history)
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
