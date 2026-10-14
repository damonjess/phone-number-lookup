package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsernameVariantsTest {

    @Test
    fun dottedHandleGetsCommonSpellings() {
        assertEquals(listOf("john.smith", "johnsmith", "john_smith"), UsernameVariants.fromHandle("john.smith"))
    }

    @Test
    fun underscoreHandleGetsDotAndJoinedForms() {
        assertEquals(listOf("john_smith", "johnsmith", "john.smith"), UsernameVariants.fromHandle("john_smith"))
    }

    @Test
    fun trailingDigitsAreDroppedAsAVariant() {
        val v = UsernameVariants.fromHandle("johnsmith1985")
        assertEquals(listOf("johnsmith1985", "johnsmith"), v)
    }

    @Test
    fun originalIsAlwaysFirstAndCountIsCapped() {
        val v = UsernameVariants.fromHandle("john_smith92", max = 3)
        assertEquals("john_smith92", v.first())
        assertEquals(3, v.size)
    }

    @Test
    fun plusTagIsStrippedFromEmailLocalPart() {
        assertEquals("jane.doe", UsernameVariants.fromHandle("jane.doe+shopping").first())
    }

    @Test
    fun plainHandleHasNoVariants() {
        assertEquals(listOf("alice"), UsernameVariants.fromHandle("alice"))
    }

    @Test
    fun tooShortOrEmptyInputsAreSafe() {
        assertTrue(UsernameVariants.fromHandle("").isEmpty())
        assertTrue(UsernameVariants.fromHandle("@").isEmpty())
        assertTrue(UsernameVariants.fromHandle("ab").isEmpty())
    }

    @Test
    fun leadingAtSignIsRemoved() {
        assertEquals("alice", UsernameVariants.fromHandle("@alice").first())
    }
}
