package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live username enumeration across the merged site databases.
 *
 * Every row in the merged list is an *actual scan*: we send a real HTTP request to the
 * site's documented check endpoint and classify the response. Nothing here just opens
 * a browser tab.
 *
 * Coverage comes from [OsintDatasets], which merges the community lists the Termux
 * tools use (WhatsMyName + Sherlock + Tookie-OSINT). A site appearing in more than one
 * list is probed once.
 */
data class UsernameHit(
    val site: String,
    val category: String,
    val source: String,
    val profileUrl: String,
    val statusCode: Int = 200,
    val finalUrl: String = "",
    val evidence: String = "Matched a site-specific positive marker",
    val confidence: String = "Medium",
    val verificationStatus: String = "Matched"
)

data class UsernameScanOutcome(
    val sitesChecked: Int,
    val confirmed: List<UsernameHit>,
    /** How many sites each source contributed to the merged database. */
    val datasetSizes: Map<String, Int> = emptyMap(),
    /** Requests that failed outright (timeout, DNS, blocked) rather than answering. */
    val unreachable: Int = 0
) {
    val databasesLoaded: Boolean get() = sitesChecked > 0
}

object UsernameScanner {

    /** How many sites each bundled database is currently contributing. */
    fun datasetBreakdown(): Map<String, Int> =
        OsintDatasets.cachedUsernameBreakdown()

    /**
     * Scans up to [limit] sites for [username]. Requests run [concurrency] at a time so
     * we stay polite to the target sites, and [onProgress] reports `checked/total` so the
     * UI can show a live counter.
     */
    suspend fun scan(
        username: String,
        limit: Int = DEFAULT_LIMIT,
        concurrency: Int = 6,
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> }
    ): UsernameScanOutcome = withContext(Dispatchers.IO) {
        val clean = username.trim().removePrefix("@")
        if (clean.isEmpty()) return@withContext UsernameScanOutcome(0, emptyList())

        val all = OsintDatasets.loadUsernameDatabases()
        if (all.isEmpty()) return@withContext UsernameScanOutcome(0, emptyList())

        val sites = if (limit <= 0) all else all.take(limit)
        val semaphore = Semaphore(concurrency)
        val counter = AtomicInteger(0)
        val unreachable = AtomicInteger(0)

        val results = sites.map { site ->
            async {
                semaphore.withPermit {
                    val result = OsintDatasets.evaluate(site, clean)
                    if (result.verdict == ProbeVerdict.UNKNOWN) unreachable.incrementAndGet()
                    onProgress(counter.incrementAndGet(), sites.size)
                    result
                }
            }
        }.awaitAll()

        val confirmed = results
            .filter { it.verdict == ProbeVerdict.FOUND }
            .map {
                UsernameHit(
                    site = it.site.name,
                    category = it.site.category,
                    source = it.site.source,
                    profileUrl = it.profileUrl,
                    statusCode = it.statusCode,
                    finalUrl = it.finalUrl,
                    evidence = it.evidence,
                    confidence = confidenceFor(it),
                    verificationStatus = statusFor(it)
                )
            }
            .sortedWith(compareBy({ it.category.lowercase() }, { it.site.lowercase() }))

        UsernameScanOutcome(
            sitesChecked = sites.size,
            confirmed = confirmed,
            datasetSizes = OsintDatasets.cachedUsernameBreakdown(),
            unreachable = unreachable.get()
        )
    }

    suspend fun recheck(username: String, siteName: String): UsernameHit? = withContext(Dispatchers.IO) {
        val probe = OsintDatasets.findUsernameProbe(siteName) ?: return@withContext null
        val result = OsintDatasets.evaluate(probe, username, timeoutMs = 12_000)
        UsernameHit(
            site = result.site.name,
            category = result.site.category,
            source = result.site.source,
            profileUrl = result.profileUrl,
            statusCode = result.statusCode,
            finalUrl = result.finalUrl,
            evidence = result.evidence,
            confidence = confidenceFor(result),
            verificationStatus = statusFor(result)
        )
    }

    private fun confidenceFor(result: ProbeResult): String = when (result.verdict) {
        ProbeVerdict.FOUND -> if (result.evidence.contains("site-specific")) "High" else "Medium"
        ProbeVerdict.UNKNOWN -> "Uncertain"
        ProbeVerdict.NOT_FOUND -> "None"
    }

    private fun statusFor(result: ProbeResult): String = when (result.verdict) {
        ProbeVerdict.FOUND -> "Matched"
        ProbeVerdict.NOT_FOUND -> "Not found"
        ProbeVerdict.UNKNOWN -> "Unavailable / uncertain"
    }

    /** Total probes available across every merged database (0 until first load). */
    fun availableSiteCount(): Int = OsintDatasets.cachedUsernameCount()

    const val DEFAULT_LIMIT = 100
    const val FULL_SCAN = 0
}
