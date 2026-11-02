package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsintPilotEngineTest {

    @Test
    fun testDetectTargetType() {
        assertEquals(PilotTargetType.PHONE, OsintPilotEngine.detectTargetType("+447700900123"))
        assertEquals(PilotTargetType.PHONE, OsintPilotEngine.detectTargetType("07700900123"))
        assertEquals(PilotTargetType.PHONE, OsintPilotEngine.detectTargetType("1-555-123-4567"))
        assertEquals(PilotTargetType.EMAIL, OsintPilotEngine.detectTargetType("investigator@example.com"))
        assertEquals(PilotTargetType.NAME, OsintPilotEngine.detectTargetType("Ryan Montgomery"))
        assertEquals(PilotTargetType.USERNAME, OsintPilotEngine.detectTargetType("0day"))
        assertEquals(PilotTargetType.USERNAME, OsintPilotEngine.detectTargetType("@secops"))
    }

    @Test
    fun testReportStructure() {
        val initialStatuses = listOf(
            PilotAgentStatus("agent_id", "Identity Resolution Agent", "Who is this, really?", "Resolved persona", countBadge = "1 name", isComplete = true, findingsCount = 1),
            PilotAgentStatus("agent_footprint", "Digital Footprint Agent", "Where are they active?", "Probed platforms", countBadge = "3 accounts", isComplete = true, findingsCount = 3)
        )
        val findings = listOf(
            SourcedFinding(category = "Identity", claim = "Name: John Doe", sourceName = "Test Directory", confidence = ConfidenceLevel.HIGH),
            SourcedFinding(category = "Registration", claim = "Active account on Microsoft", sourceName = "Microsoft", confidence = ConfidenceLevel.HIGH)
        )
        val report = PilotInvestigationReport(
            investigationId = "TEST1234",
            target = "+447700900123",
            detectedType = PilotTargetType.PHONE,
            agentStatuses = initialStatuses,
            findings = findings
        )

        assertNotNull(report.investigationId)
        assertEquals(2, report.agentStatuses.size)
        assertEquals(2, report.findings.size)
        assertTrue(report.findings.any { it.sourceName == "Microsoft" })
    }
}
