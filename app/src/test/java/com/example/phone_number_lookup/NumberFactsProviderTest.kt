package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the libphonenumber glue. Numbers were chosen from ranges libphonenumber classifies this way. */
class NumberFactsProviderTest {

    @Test
    fun londonLandline() {
        val f = NumberFactsProvider.from("+442079460958")
        assertTrue(f.valid)
        assertEquals(NumberKind.FIXED_LINE, f.kind)
        assertEquals("GB", f.regionCode)
        assertEquals(44, f.countryCallingCode)
    }

    @Test
    fun ukPersonalNumber_070() {
        assertEquals(NumberKind.PERSONAL_NUMBER, NumberFactsProvider.from("+447012345678").kind)
    }

    @Test
    fun ukPremiumRate_09() {
        assertEquals(NumberKind.PREMIUM_RATE, NumberFactsProvider.from("+449098790123").kind)
    }

    @Test
    fun ukVoip_056() {
        assertEquals(NumberKind.VOIP, NumberFactsProvider.from("+445612345678").kind)
    }

    @Test
    fun jamaicaNumber_hasCaribbeanRegion() {
        val f = NumberFactsProvider.from("+18765551234")
        assertEquals("JM", f.regionCode)
        assertEquals(1, f.countryCallingCode)
    }

    @Test
    fun unallocatedRange_isNotValid() {
        // 07700 900xxx is reserved for TV drama, so it isn't a real allocated mobile range.
        assertFalse(NumberFactsProvider.from("+447700900123").valid)
    }

    @Test
    fun garbage_isNotParsed() {
        val f = NumberFactsProvider.from("abc")
        assertFalse(f.parsed)
        assertEquals(NumberKind.UNKNOWN, f.kind)
    }
}
