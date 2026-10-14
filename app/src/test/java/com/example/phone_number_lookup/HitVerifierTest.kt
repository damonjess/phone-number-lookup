package com.example.phone_number_lookup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HitVerifierTest {

    @Test
    fun randomUsernameLooksLikeARealOneButCannotCollide() {
        val a = HitVerifier.randomUsername()
        val b = HitVerifier.randomUsername()
        assertEquals(14, a.length)
        assertTrue(a.first().isLetter())
        assertTrue(a.all { it in 'a'..'z' || it in '0'..'9' })
        assertFalse(a == b)
    }

    @Test
    fun randomLocalPartIsValidForAnEmailAddress() {
        val local = HitVerifier.randomLocalPart()
        assertEquals(11, local.length)
        assertTrue(local.first().isLetter())
    }

    @Test
    fun hitsThatAnswerYesToEverythingAreDiscarded() = runBlocking {
        val hits = listOf("RealSite", "SoftSite", "Another", "AlwaysYes")
        val (kept, dropped) = HitVerifier.splitByControl(hits, concurrency = 2) { it.endsWith("Yes") || it == "SoftSite" }
        assertEquals(listOf("RealSite", "Another"), kept)
        assertEquals(listOf("SoftSite", "AlwaysYes"), dropped)
    }

    @Test
    fun emptyInputIsFine() = runBlocking {
        val (kept, dropped) = HitVerifier.splitByControl(emptyList<String>(), 4) { true }
        assertTrue(kept.isEmpty() && dropped.isEmpty())
    }
}
