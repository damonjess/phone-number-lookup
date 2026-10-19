package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Account-registration discovery for an email address.
 *
 * This is the job the Holehe and Blackbird Python modules do. Instead of shelling out to
 * them we ask the services directly: each check hits the site's own signup / availability /
 * status endpoint and reads the answer ("this address is already taken"), which is exactly
 * how those tools work under the hood.
 *
 * Two layers:
 *   1. A data-driven layer that loads Blackbird's public email dataset plus
 *      [CuratedEmailChecks], which contains probes verified live against the real endpoints.
 *   2. A username layer: the part of the address before the `@` is often the same handle
 *      used elsewhere, so the caller can feed it to [UsernameScanner] for extra coverage.
 *
 * A "registered" verdict is only produced when the site gives a positive signal. Sites that
 * block automated clients (403/401/5xx) are reported as unreachable instead of guessed at.
 */
data class RegistrationHit(
    val site: String,
    val category: String,
    val source: String,
    val profileUrl: String,
    val statusCode: Int,
    val finalUrl: String = "",
    val evidence: String = "Matched a site-specific positive marker",
    val confidence: String = "Medium",
    val verificationStatus: String = "Registered response"
)

data class EmailRegistrationOutcome(
    val email: String,
    val sitesChecked: Int,
    val registered: List<RegistrationHit>,
    val unreachable: Int = 0,
    val error: String? = null,
    /** Sites that said "account exists" even for a made-up address, so their hits were discarded. */
    val discarded: List<String> = emptyList(),
    /** Non-fatal warning, e.g. only the built-in checks ran because the full list failed to download. */
    val notice: String? = null
)

object EmailRegistrationScanner {

    /** The part of the address before the `@`, used for the handle-side scan. */
    fun localPart(email: String): String? =
        email.trim().substringBefore('@').takeIf { it.isNotEmpty() && email.contains('@') }

    suspend fun scan(
        email: String,
        concurrency: Int = 10,
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> }
    ): EmailRegistrationOutcome = withContext(Dispatchers.IO) {
        val clean = email.trim()
        if (clean.isEmpty() || !clean.contains('@')) {
            return@withContext EmailRegistrationOutcome(
                email = clean, sitesChecked = 0, registered = emptyList(),
                error = "Enter a valid email address"
            )
        }

        val probes = OsintDatasets.loadEmailDatabase()
        if (probes.isEmpty()) {
            return@withContext EmailRegistrationOutcome(
                email = clean, sitesChecked = 0, registered = emptyList(),
                error = "Could not load the registration-check database. Check your connection."
            )
        }

        val semaphore = Semaphore(concurrency)
        val counter = AtomicInteger(0)
        val unreachable = AtomicInteger(0)

        val results = probes.map { probe ->
            async {
                HostGate.run(HostGate.hostKey(probe.urlTemplate)) {
                    semaphore.withPermit {
                        val result = ResilientProbe.evaluate(probe, clean, timeoutMs = 12_000)
                        if (result.verdict == ProbeVerdict.UNKNOWN) unreachable.incrementAndGet()
                        onProgress(counter.incrementAndGet(), probes.size)
                        result
                    }
                }
            }
        }.awaitAll()

        // Control check: a made-up address at the same domain must come back "not registered".
        // Routed through ResilientProbe for retries; judged as passed, failed, or unverified.
        val domain = clean.substringAfter('@')
        val found = results.filter { it.verdict == ProbeVerdict.FOUND }
        val (kept, dropped) = coroutineScope {
            val controlSemaphore = Semaphore(concurrency.coerceAtLeast(1))
            val controlResults = found.map { hit ->
                async {
                    controlSemaphore.withPermit {
                        val fakeEmail = HitVerifier.randomLocalPart() + "@" + domain
                        ResilientProbe.evaluate(hit.site, fakeEmail, timeoutMs = 12_000)
                    }
                }
            }.awaitAll()

            val keptList = ArrayList<Pair<ProbeResult, ProbeResult>>()
            val droppedList = ArrayList<String>()
            found.forEachIndexed { i, hit ->
                val control = controlResults[i]
                if (control.verdict == ProbeVerdict.FOUND) {
                    droppedList += hit.site.name
                } else {
                    keptList += hit to control
                }
            }
            keptList to droppedList
        }

        val registered = kept
            .map { (hit, control) ->
                val controlInconclusive = control.verdict == ProbeVerdict.UNKNOWN
                RegistrationHit(
                    site = hit.site.name,
                    category = hit.site.category,
                    source = hit.site.source,
                    profileUrl = OsintDatasets.cleanProfileUrl(hit.site.name, hit.profileUrl),
                    statusCode = hit.statusCode,
                    finalUrl = hit.finalUrl,
                    evidence = hit.evidence + if (controlInconclusive) " • control check inconclusive" else " • control check passed",
                    confidence = if (controlInconclusive) "Medium" else confidenceFor(hit),
                    // The UI counts hits by this exact string, so it must stay unchanged.
                    verificationStatus = "Registered response"
                )
            }
            .sortedWith(compareBy({ it.category.lowercase() }, { it.site.lowercase() }))

        val fullListLoaded = probes.any { it.source == OsintDatasets.SRC_BLACKBIRD }

        EmailRegistrationOutcome(
            email = clean,
            sitesChecked = probes.size,
            registered = registered,
            unreachable = unreachable.get(),
            discarded = dropped,
            notice = if (fullListLoaded) null else
                "Only ${probes.size} built-in checks ran because the full site list could not be downloaded. " +
                    "Check your connection and scan again for complete coverage."
        )
    }

    suspend fun recheck(email: String, siteName: String): RegistrationHit? = withContext(Dispatchers.IO) {
        val probe = OsintDatasets.findEmailProbe(siteName) ?: return@withContext null
        val result = OsintDatasets.evaluate(probe, email, timeoutMs = 12_000)
        if (result.verdict != ProbeVerdict.FOUND) {
            return@withContext RegistrationHit(
                site = result.site.name,
                category = result.site.category,
                source = result.site.source,
                profileUrl = result.profileUrl,
                statusCode = result.statusCode,
                finalUrl = result.finalUrl,
                evidence = result.evidence,
                confidence = confidenceFor(result),
                verificationStatus = when (result.verdict) {
                    ProbeVerdict.NOT_FOUND -> "Not found"
                    else -> "Unavailable / uncertain"
                }
            )
        }

        val domain = email.substringAfter('@')
        val control = ResilientProbe.evaluate(probe, HitVerifier.randomLocalPart() + "@" + domain, timeoutMs = 12_000)
        if (control.verdict == ProbeVerdict.FOUND) {
            return@withContext null
        }

        val controlInconclusive = control.verdict == ProbeVerdict.UNKNOWN
        RegistrationHit(
            site = result.site.name,
            category = result.site.category,
            source = result.site.source,
            profileUrl = OsintDatasets.cleanProfileUrl(result.site.name, result.profileUrl),
            statusCode = result.statusCode,
            finalUrl = result.finalUrl,
            evidence = result.evidence + if (controlInconclusive) " • control check inconclusive" else " • control check passed",
            confidence = if (controlInconclusive) "Medium" else confidenceFor(result),
            verificationStatus = "Registered response"
        )
    }

    private fun confidenceFor(result: ProbeResult): String = when (result.verdict) {
        ProbeVerdict.FOUND -> if (result.evidence.contains("site-specific")) "High" else "Medium"
        ProbeVerdict.UNKNOWN -> "Uncertain"
        ProbeVerdict.NOT_FOUND -> "None"
    }
}
