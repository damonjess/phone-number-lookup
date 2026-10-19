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
    val notice: String? = null,
    /** Site-level failures for transparent reporting and targeted retry. */
    val issues: List<SiteIssue> = emptyList()
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
                    confidence = if (controlInconclusive) "Uncertain" else confidenceFor(hit),
                    // The UI counts hits by this exact string, so it must stay unchanged.
                    verificationStatus = if (controlInconclusive) "Unavailable / uncertain" else "Registered response"
                )
            }
            .sortedWith(compareBy({ it.category.lowercase() }, { it.site.lowercase() }))

        val fullListLoaded = probes.any { it.source == OsintDatasets.SRC_BLACKBIRD }
        val issues = results
            .filter { it.verdict == ProbeVerdict.UNKNOWN }
            .map { result ->
                SiteIssue(
                    site = result.site.name,
                    profileUrl = result.profileUrl,
                    reason = result.failure ?: ProbeFailure.INCONCLUSIVE,
                    detail = result.evidence
                )
            }

        EmailRegistrationOutcome(
            email = clean,
            sitesChecked = probes.size,
            registered = registered,
            unreachable = unreachable.get(),
            discarded = dropped,
            issues = issues,
            notice = if (fullListLoaded) null else
                "Only ${probes.size} built-in checks ran because the full site list could not be downloaded. " +
                    "Check your connection and scan again for complete coverage."
        )
    }

    /** Retries only transiently failed email endpoints; known bot challenges are left alone. */
    suspend fun retry(
        email: String,
        issues: List<SiteIssue>,
        concurrency: Int = 2
    ): EmailRegistrationOutcome = withContext(Dispatchers.IO) {
        val clean = email.trim()
        val retryable = issues.filter { it.retryable }
        if (retryable.isEmpty()) return@withContext EmailRegistrationOutcome(clean, 0, emptyList(), issues = issues)
        val wanted = retryable.map { it.site.lowercase() }.toSet()
        val probes = OsintDatasets.loadEmailDatabase().filter { it.name.lowercase() in wanted }
        val foundNames = probes.map { it.name.lowercase() }.toSet()
        val pending = (issues.filterNot { it.retryable } + retryable.filter { it.site.lowercase() !in foundNames }).toMutableList()
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        val results = coroutineScope {
            probes.map { probe ->
                async {
                    HostGate.run(HostGate.hostKey(probe.urlTemplate)) {
                        semaphore.withPermit { probe to ResilientProbe.evaluate(probe, clean, timeoutMs = 12_000) }
                    }
                }
            }.awaitAll()
        }
        val found = results.filter { it.second.verdict == ProbeVerdict.FOUND }
        val controls = coroutineScope {
            val controlSemaphore = Semaphore(concurrency.coerceAtLeast(1))
            found.map { (probe, _) ->
                async {
                    controlSemaphore.withPermit {
                        probe.name.lowercase() to ResilientProbe.evaluate(
                            probe,
                            HitVerifier.randomLocalPart() + "@" + clean.substringAfter('@'),
                            timeoutMs = 12_000
                        )
                    }
                }
            }.awaitAll().toMap()
        }
        val hits = mutableListOf<RegistrationHit>()
        val discarded = mutableListOf<String>()
        val stillFailed = mutableListOf<SiteIssue>()
        results.forEach { (probe, result) ->
            when (result.verdict) {
                ProbeVerdict.NOT_FOUND -> Unit
                ProbeVerdict.UNKNOWN -> stillFailed += SiteIssue(
                    probe.name, result.profileUrl, result.failure ?: ProbeFailure.INCONCLUSIVE, result.evidence
                )
                ProbeVerdict.FOUND -> {
                    val control = controls[probe.name.lowercase()]
                    if (control?.verdict == ProbeVerdict.FOUND) {
                        discarded += probe.name
                    } else {
                        val controlUnknown = control?.verdict == ProbeVerdict.UNKNOWN
                        hits += RegistrationHit(
                            site = probe.name,
                            category = probe.category,
                            source = probe.source,
                            profileUrl = OsintDatasets.cleanProfileUrl(probe.name, result.profileUrl),
                            statusCode = result.statusCode,
                            finalUrl = result.finalUrl,
                            evidence = result.evidence + if (controlUnknown) " • control check inconclusive" else " • control check passed",
                            confidence = if (controlUnknown) "Uncertain" else confidenceFor(result),
                            verificationStatus = if (controlUnknown) "Unavailable / uncertain" else "Registered response"
                        )
                    }
                }
            }
        }
        val issuesLeft = (pending + stillFailed).distinctBy { it.site.lowercase() }
        EmailRegistrationOutcome(
            email = clean,
            sitesChecked = probes.size,
            registered = hits,
            unreachable = issuesLeft.size,
            discarded = discarded,
            issues = issuesLeft
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
            confidence = if (controlInconclusive) "Uncertain" else confidenceFor(result),
            verificationStatus = if (controlInconclusive) "Unavailable / uncertain" else "Registered response"
        )
    }

    private fun confidenceFor(result: ProbeResult): String = when (result.verdict) {
        ProbeVerdict.FOUND -> if (result.evidence.contains("site-specific")) "High" else "Medium"
        ProbeVerdict.UNKNOWN -> "Uncertain"
        ProbeVerdict.NOT_FOUND -> "None"
    }
}
