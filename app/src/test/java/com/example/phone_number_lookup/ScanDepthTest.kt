package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDepthTest {

    @Test
    fun quickStandardAndFullMapToTheirLabels() {
        assertEquals(150, UsernameScanner.limitForDepth(0))
        assertEquals(300, UsernameScanner.limitForDepth(1))
        assertEquals(UsernameScanner.FULL_SCAN, UsernameScanner.limitForDepth(2))
    }

    @Test
    fun standardIsLargerThanQuick() {
        // This regressed once: Standard shared a default of 100, below Quick's 150.
        assertTrue(UsernameScanner.limitForDepth(1) > UsernameScanner.limitForDepth(0))
    }

    @Test
    fun theUnchosenDefaultIsStandard() {
        assertEquals(UsernameScanner.STANDARD_LIMIT, UsernameScanner.DEFAULT_LIMIT)
    }
}
