package com.example.phone_number_lookup

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostGateTest {

    @Test
    fun hostKeyIgnoresSubdomainsAndPaths() {
        assertEquals("tumblr.com", HostGate.hostKey("https://{account}.tumblr.com"))
        assertEquals("tumblr.com", HostGate.hostKey("https://www.tumblr.com/{account}"))
        assertEquals("github.com", HostGate.hostKey("https://api.github.com/users/{account}"))
        assertEquals("github.com", HostGate.hostKey("https://github.com/{account}"))
    }

    @Test
    fun countrySecondLevelDomainsKeepThreeLabels() {
        assertEquals("example.co.uk", HostGate.hostKey("https://www.example.co.uk/u/{account}"))
        assertEquals("site.com.au", HostGate.hostKey("https://site.com.au/{account}"))
    }

    @Test
    fun requestsToOneHostAreSpacedOut() = runBlocking {
        val key = "spacing-test-${System.nanoTime()}.com"
        val starts = (1..3).map { async { HostGate.run(key, minGapMs = 120) { System.currentTimeMillis() } } }
            .awaitAll().sorted()
        assertTrue("gap 1 was ${starts[1] - starts[0]}", starts[1] - starts[0] >= 100)
        assertTrue("gap 2 was ${starts[2] - starts[1]}", starts[2] - starts[1] >= 100)
    }

    @Test
    fun differentHostsDoNotWaitForEachOther() = runBlocking {
        val a = "a-${System.nanoTime()}.com"
        val b = "b-${System.nanoTime()}.com"
        val begin = System.currentTimeMillis()
        listOf(a, b).map { async { HostGate.run(it, minGapMs = 500) { } } }.awaitAll()
        assertTrue(System.currentTimeMillis() - begin < 300)
    }
}
