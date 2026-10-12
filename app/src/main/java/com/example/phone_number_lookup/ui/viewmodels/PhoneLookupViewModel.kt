package com.example.phone_number_lookup.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.phone_number_lookup.PhoneReputation
import com.example.phone_number_lookup.PhoneNumberUtils
import com.example.phone_number_lookup.ReputationReport
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
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val spamReport: SpamReport? = null,
    val isSpamScanning: Boolean = false,
    val reputationReports: List<ReputationReport> = emptyList(),
    val isReputationScanning: Boolean = false,
)

class PhoneLookupViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PhoneLookupUiState())
    val uiState: StateFlow<PhoneLookupUiState> = _uiState.asStateFlow()

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
                spamReport = null,
                reputationReports = emptyList(),
            )
        }

        val normalized = PhoneNumberUtils.normalize(input)
        val localFormat = if (normalized.startsWith("+44")) "0" + normalized.removePrefix("+44") else normalized

        // Scanning a second number while the first is still running must not let the
        // older lookup's answer land under the newer number's heading.
        val token = scans.supersede()

        scans.start(token) { active ->
            val items = SearchRepository.searchWeb(normalized, localFormat)
            if (scans.isCurrent(active)) {
                _uiState.update { it.copy(searchResults = items, isSearching = false) }
            }
        }

        scans.start(token) { active ->
            val report = SpamScanner.lookup(normalized)
            if (scans.isCurrent(active)) {
                _uiState.update { it.copy(spamReport = report, isSpamScanning = false) }
            }
        }

        scans.start(token) { active ->
            val reports = PhoneReputation.lookup(normalized)
            if (scans.isCurrent(active)) {
                _uiState.update { it.copy(reputationReports = reports, isReputationScanning = false) }
            }
        }
    }

    fun clear() {
        scans.cancelAll()
        _uiState.value = PhoneLookupUiState()
    }
}
