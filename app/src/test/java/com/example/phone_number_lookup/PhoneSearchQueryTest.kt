package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSearchQueryTest {
    @Test
    fun createsExactSpamAndCallerDirectorySearches() {
        val queries = SearchRepository.buildPhoneInvestigationQueries(
            listOf("020 3688 7393", "02036887393", "+442036887393")
        )
        assertEquals(3, queries.size)
        assertTrue(queries[0].contains("\"020 3688 7393\""))
        assertTrue(queries[0].contains("\"02036887393\""))
        assertTrue(queries[1].contains("spam OR scam"))
        assertTrue(queries[2].contains("site:tellows.co.uk"))
        assertTrue(queries[2].contains("site:who-called.co.uk"))
        assertTrue(queries[2].contains("site:whosringing.co.uk/number"))
    }

    @Test
    fun emptyInputDoesNotCreateQueries() {
        assertTrue(SearchRepository.buildPhoneInvestigationQueries(emptyList()).isEmpty())
        assertTrue(SearchRepository.buildPhoneInvestigationQueries(listOf(" ")).isEmpty())
    }

    @Test
    fun searchesDoNotProbeAccountSignupServices() {
        val queries = SearchRepository.buildPhoneInvestigationQueries(listOf("+442036887393"))
        val all = queries.joinToString(" ").lowercase()
        assertFalse(all.contains("signup"))
        assertFalse(all.contains("register"))
        assertFalse(all.contains("account"))
    }
}
