package com.example.phone_number_lookup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live smoke tests. These hit the real public endpoints, so they are skipped (JUnit
 * `assumeTrue`) rather than failed when the machine is offline.
 *
 * They exist because the dataset URLs and the upstream site lists change without notice:
 * a green offline suite would not prove the app still has coverage on a real device.
 */
class LiveEndpointSmokeTest {

    @Test
    fun mergedUsernameDatabaseLoadsFromEverySource() {
        val sites = runBlocking { OsintDatasets.loadUsernameDatabases() }
        assumeTrue("offline: could not download any dataset", sites.isNotEmpty())

        val breakdown = sites.groupingBy { it.source }.eachCount()
        println("[live] merged username probes: ${sites.size} -> $breakdown")

        assertTrue("expected a large merged database, got ${sites.size}", sites.size > 800)
        assertTrue("WhatsMyName contributed nothing", (breakdown[OsintDatasets.SRC_WMN] ?: 0) > 300)
        assertTrue("Sherlock contributed nothing", (breakdown[OsintDatasets.SRC_SHERLOCK] ?: 0) > 100)
        // Most of Tookie's list also appears in WhatsMyName/Sherlock and is merged away by profile URL,
        // so only its genuinely new sites remain (about 50 at the time of writing).
        assertTrue("Tookie contributed nothing", (breakdown[OsintDatasets.SRC_TOOKIE] ?: 0) >= 10)

        // De-duplication must have removed overlapping rows from the merge, by name and by profile page.
        val names = sites.map { it.name.lowercase() }
        assertTrue("duplicate sites survived the merge", names.size == names.toSet().size)
        val profiles = sites.map { SiteKey.of(it.profileTemplate) }.filter { it.isNotEmpty() }
        assertTrue("the same profile page is probed twice", profiles.size == profiles.toSet().size)
    }

    @Test
    fun aClaimedUsernameIsFoundWhileAJunkOneMostlyIsNot() {
        val sites = runBlocking { OsintDatasets.loadUsernameDatabases() }
        assumeTrue("offline: could not download any dataset", sites.isNotEmpty())

        // `blue` is the example account Sherlock itself uses as `username_claimed`.
        val real = runBlocking { UsernameScanner.scan("blue", limit = 80) }
        assumeTrue("offline: scan checked no sites", real.sitesChecked > 0)

        val junk = runBlocking { UsernameScanner.scan("zzqxjunk9917qx", limit = 80) }

        println("[live] 'blue' -> ${real.confirmed.size} hits of ${real.sitesChecked} sites")
        println("[live] junk   -> ${junk.confirmed.size} hits of ${junk.sitesChecked} sites")

        assertTrue("expected at least one real profile for 'blue'", real.confirmed.isNotEmpty())
        assertTrue(
            "a random handle should never out-score a real one",
            junk.confirmed.size <= real.confirmed.size
        )
    }

    @Test
    fun gravatarDetectsARealAddressAndRejectsAMadeUpOne() {
        val probes = runBlocking { OsintDatasets.loadEmailDatabase() }
        assumeTrue("offline: could not load the email dataset", probes.isNotEmpty())

        val gravatar = probes.firstOrNull { it.name == "Gravatar" }
        assumeTrue("the Gravatar probe is missing", gravatar != null)

        // A real, publicly documented commit address versus a random string. This is the
        // only test that proves both directions against a live service: the engine must
        // say FOUND for a real account and NOT_FOUND for a made-up one.
        val real = OsintDatasets.evaluate(gravatar!!, "torvalds@linux-foundation.org", 15_000)
        val junk = OsintDatasets.evaluate(gravatar, "zzqxjunk9917qx@gmail.com", 15_000)
        assumeTrue("offline: Gravatar did not answer", real.verdict != ProbeVerdict.UNKNOWN)

        println("[live] gravatar real=${real.verdict} (HTTP ${real.statusCode}), junk=${junk.verdict}")

        assertEquals("a real address should be detected", ProbeVerdict.FOUND, real.verdict)
        assertEquals("a made-up address must not be", ProbeVerdict.NOT_FOUND, junk.verdict)
    }

    @Test
    fun emailRegistrationDatabaseLoadsAndRejectsAnUnregisteredAddress() {
        val probes = runBlocking { OsintDatasets.loadEmailDatabase() }
        assumeTrue("offline: could not download the email dataset", probes.isNotEmpty())

        println("[live] email registration probes: ${probes.size}")

        // Firefox, Chess.com and Gravatar are the curated, live-verified probes.
        val curated = probes.filter { it.source == OsintDatasets.SRC_CURATED }
        assertTrue("the verified probes went missing", curated.size == CuratedEmailChecks.PROBES.size)

        with(EmailRegistrationScanner) {
            val outcome = runBlocking { scan("zzqxjunk9917qx@gmail.com") }
            assumeTrue("offline: no probe answered", outcome.sitesChecked > 0 && outcome.unreachable < outcome.sitesChecked)

            println("[live] unregistered address -> ${outcome.registered.size} registrations, " +
                "${outcome.unreachable} unreachable of ${outcome.sitesChecked}")

            // The single most important property: a made-up address must not be reported
            // as registered anywhere, or the feature is worse than useless.
            assertTrue(
                "a made-up address produced false registrations: " +
                    outcome.registered.map { it.site },
                outcome.registered.isEmpty()
            )
        }
    }
}
