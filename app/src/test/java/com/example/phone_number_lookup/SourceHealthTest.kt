package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceHealthTest {

    @Test
    fun networkFailureIsUnreachable() {
        assertEquals(HealthState.UNREACHABLE, SourceHealth.classifyHttp(-1, formatOk = false))
    }

    @Test
    fun botChecksAndRateLimitsAreBlocked() {
        listOf(202, 401, 403, 429).forEach {
            assertEquals("HTTP $it", HealthState.BLOCKED, SourceHealth.classifyHttp(it, formatOk = true))
        }
    }

    @Test
    fun goodAnswerIsOkOnlyWhenTheFormatIsRight() {
        assertEquals(HealthState.OK, SourceHealth.classifyHttp(200, formatOk = true))
        assertEquals(HealthState.CHANGED, SourceHealth.classifyHttp(200, formatOk = false))
    }

    @Test
    fun serverErrorsAreUnreachableAndMissingPagesAreChanged() {
        assertEquals(HealthState.UNREACHABLE, SourceHealth.classifyHttp(503, formatOk = false))
        assertEquals(HealthState.CHANGED, SourceHealth.classifyHttp(404, formatOk = false))
        assertEquals(HealthState.CHANGED, SourceHealth.classifyHttp(301, formatOk = false))
    }

    @Test
    fun descriptionsMentionTheStatusCode() {
        assertTrue(SourceHealth.describe(HealthState.BLOCKED, 429).contains("429"))
        assertTrue(SourceHealth.describe(HealthState.UNREACHABLE, -1).contains("network"))
    }
}
