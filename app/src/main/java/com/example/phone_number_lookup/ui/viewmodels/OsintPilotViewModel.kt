package com.example.phone_number_lookup.ui.viewmodels

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.phone_number_lookup.OsintPdfExporter
import com.example.phone_number_lookup.OsintPilotEngine
import com.example.phone_number_lookup.PdfSection
import com.example.phone_number_lookup.PilotContext
import com.example.phone_number_lookup.PilotInvestigationReport
import com.example.phone_number_lookup.PilotTargetType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OsintPilotUiState(
    val targetInput: String = "",
    val targetType: PilotTargetType = PilotTargetType.AUTO,
    val locationHint: String = "",
    val aliasesInput: String = "",
    val isContextExpanded: Boolean = false,
    val isInvestigating: Boolean = false,
    val report: PilotInvestigationReport? = null,
    val investigationHistory: List<PilotInvestigationReport> = emptyList(),
    val exportStatus: String? = null
)

class OsintPilotViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(OsintPilotUiState())
    val uiState: StateFlow<OsintPilotUiState> = _uiState.asStateFlow()

    private var investigationJob: Job? = null

    fun onTargetInputChanged(input: String) {
        _uiState.update { it.copy(targetInput = input) }
    }

    fun onTargetTypeSelected(type: PilotTargetType) {
        _uiState.update { it.copy(targetType = type) }
    }

    fun onLocationHintChanged(hint: String) {
        _uiState.update { it.copy(locationHint = hint) }
    }

    fun onAliasesInputChanged(aliases: String) {
        _uiState.update { it.copy(aliasesInput = aliases) }
    }

    fun toggleContextExpanded() {
        _uiState.update { it.copy(isContextExpanded = !it.isContextExpanded) }
    }

    fun launchInvestigation() {
        val target = _uiState.value.targetInput.trim()
        if (target.isBlank()) return

        investigationJob?.cancel()

        val context = PilotContext(
            locationHint = _uiState.value.locationHint.trim().takeIf { it.isNotBlank() },
            knownAliases = _uiState.value.aliasesInput.split(',', ';', ' ')
                .map { it.trim().removePrefix("@") }
                .filter { it.isNotBlank() }
        )

        _uiState.update {
            it.copy(
                isInvestigating = true,
                report = null,
                exportStatus = null
            )
        }

        investigationJob = viewModelScope.launch {
            runCatching {
                val finalReport = OsintPilotEngine.investigate(
                    query = target,
                    explicitType = _uiState.value.targetType,
                    context = context
                ) { update ->
                    _uiState.update { state ->
                        state.copy(
                            report = update,
                            isInvestigating = update.isRunning
                        )
                    }
                }
                _uiState.update { state ->
                    val history = (listOf(finalReport) + state.investigationHistory).distinctBy { it.investigationId }
                    state.copy(
                        report = finalReport,
                        isInvestigating = false,
                        investigationHistory = history
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(isInvestigating = false, exportStatus = "Investigation failed: ${err.message}") }
            }
        }
    }

    fun clear() {
        investigationJob?.cancel()
        _uiState.update {
            it.copy(
                targetInput = "",
                locationHint = "",
                aliasesInput = "",
                isInvestigating = false,
                report = null,
                exportStatus = null
            )
        }
    }

    fun exportPdf(context: Context) {
        val rep = _uiState.value.report ?: return
        val sections = mutableListOf<PdfSection>()

        sections += PdfSection(
            heading = "Autonomous Agent Investigation Status",
            lines = rep.agentStatuses.map {
                "${it.agentName} [${it.question}]: ${it.statusText} (${it.countBadge ?: "Complete"})"
            }
        )

        if (rep.resolvedNames.isNotEmpty() || rep.locations.isNotEmpty()) {
            sections += PdfSection(
                heading = "Identity & Location Profile",
                lines = rep.resolvedNames.map { "Name: ${it.value} (${it.source})" } +
                    rep.locations.map { "Location: ${it.value} (${it.source})" } +
                    listOfNotNull(
                        rep.telecomCarrier?.let { "Carrier: $it" },
                        rep.telecomLineType?.let { "Line Type: $it" }
                    )
            )
        }

        if (rep.linkedAccounts.isNotEmpty()) {
            sections += PdfSection(
                heading = "Digital Footprint & Linked Registrations",
                lines = rep.linkedAccounts.map {
                    "${it.platform} [${it.status}]: ${it.detail}${it.url?.let { u -> " ($u)" } ?: ""}"
                }
            )
        }

        if (rep.breachRecords.isNotEmpty()) {
            sections += PdfSection(
                heading = "Breach Intelligence Records",
                lines = rep.breachRecords.map {
                    "${it.name} (${it.breachDate ?: "Undated"}): Exposed ${it.pwnCount} accounts. Data: ${it.dataClasses.joinToString(", ")}"
                }
            )
        }

        if (rep.publicRecords.isNotEmpty()) {
            sections += PdfSection(
                heading = "Public Records & Corporate Filings",
                lines = rep.publicRecords.map { "${it.source}: ${it.value}" }
            )
        }

        sections += PdfSection(
            heading = "Sourced Findings & Evidence Log",
            lines = rep.findings.map {
                "[${it.confidence}] ${it.category}: ${it.claim} (Source: ${it.sourceName})"
            }
        )

        OsintPdfExporter.exportReport(
            context = context,
            title = "OSINT Pilot Intelligence Report",
            target = rep.target,
            sections = sections
        )
    }

    fun shareMarkdown(context: Context) {
        val rep = _uiState.value.report ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "OSINT Pilot Report - ${rep.target}")
            putExtra(Intent.EXTRA_TEXT, rep.rawMarkdownReport)
        }
        val chooser = Intent.createChooser(intent, "Share OSINT Pilot Report")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
