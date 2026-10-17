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
 * Live username enumeration across the merged site databases.
 *
 * Every row in the merged list is an *actual scan*: we send a real HTTP request to the
 * site's documented check endpoint and classify the response. Nothing here just opens
 * a browser tab.
 *
 * Coverage comes from [OsintDatasets], which merges the community lists the Termux
 * tools use (WhatsMyName + Sherlock + Tookie-OSINT). A site appearing in more than one
 * list is probed once.
 *
 * Accuracy measures:
 *  - Sites are scanned in [SitePriority] order, so Quick/Standard scans cover popular sites
 *    instead of whichever categories sort first alphabetically.
 *  - Every hit is re-checked with a made-up username ([HitVerifier]). A site that says
 *    "found" for that too answers yes to everything, so its hit is discarded.
 *  - [scanWithVariants] also tries likely spellings of the handle on the most popular sites.
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
    val verificationStatus: String = "Matched",
    /** The spelling that produced this hit (differs from the typed name for variant matches). */
    val queriedName: String = ""
)

data class UsernameScanOutcome(
    val sitesChecked: Int,
    val confirmed: List<UsernameHit>,
    /** How many sites each source contributed to the merged database. */
    val datasetSizes: Map<String, Int> = emptyMap(),
    /** Requests that failed outright (timeout, DNS, blocked) rather than answering. */
    val unreachable: Int = 0,
    /** Sites that said "found" even for a made-up username, so their hits were thrown away. */
    val discarded: List<String> = emptyList(),
    /** Every spelling that was searched, original first. */
    val namesChecked: List<String> = emptyList(),
    val issues: List<SiteIssue> = emptyList()
) {
    val databasesLoaded: Boolean get() = sitesChecked > 0
}

data class UsernameRetryOutcome(
    val confirmed: List<UsernameHit> = emptyList(),
    val issues: List<SiteIssue> = emptyList(),
    val discarded: List<String> = emptyList()
)

object UsernameScanner {

    /** How many sites each bundled database is currently contributing. */
    fun datasetBreakdown(): Map<String, Int> =
        OsintDatasets.cachedUsernameBreakdown()

    private class SiteScan(
        val hits: List<UsernameHit>,
        val discarded: List<String>,
        val unreachable: Int
    )

    private val HIT_ORDER = compareBy<UsernameHit>({ it.category.lowercase() }, { it.site.lowercase() })

    /** Probes [sites] for [name], then control-checks every hit. */
    private suspend fun scanSites(
        sites: List<SiteProbe>,
        name: String,
        concurrency: Int,
        onEach: () -> Unit = {}
    ): SiteScan = coroutineScope {
        val semaphore = Semaphore(concurrency)
        val unreachable = AtomicInteger(0)

        val results = sites.map { site ->
            async {
                semaphore.withPermit {
                    val result = OsintDatasets.evaluate(site, name)
                    if (result.verdict == ProbeVerdict.UNKNOWN) unreachable.incrementAndGet()
                    onEach()
                    result
                }
            }
        }.awaitAll()

        val found = results.filter { it.verdict == ProbeVerdict.FOUND }
        val (kept, dropped) = HitVerifier.splitByControl(found, concurrency) { hit ->
            OsintDatasets.evaluate(hit.site, HitVerifier.randomUsername(), timeoutMs = 10_000)
                .verdict == ProbeVerdict.FOUND
        }

        SiteScan(
            hits = kept.map { toHit(it, name) },
            discarded = dropped.map { it.site.name },
            unreachable = unreachable.get()
        )
    }

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

        val ordered = SitePriority.prioritise(all)
        val sites = if (limit <= 0) ordered else ordered.take(limit)
        val counter = AtomicInteger(0)

        val scanned = scanSites(sites, clean, concurrency) {
            onProgress(counter.incrementAndGet(), sites.size)
        }

        UsernameScanOutcome(
            sitesChecked = sites.size,
            confirmed = scanned.hits.sortedWith(HIT_ORDER),
            // Counted from the list actually used, so it is right even when a download was partial.
            datasetSizes = all.groupingBy { it.source }.eachCount(),
            unreachable = scanned.unreachable,
            discarded = scanned.discarded,
            namesChecked = listOf(clean)
        )
    }

    /**
     * Like [scan], and also tries likely spellings of [handle] (john.smith -> johnsmith, john_smith)
     * on the [variantSiteLimit] most popular sites.
     */
    suspend fun scanWithVariants(
        handle: String,
        limit: Int = DEFAULT_LIMIT,
        variantSiteLimit: Int = VARIANT_SITE_LIMIT,
        concurrency: Int = 6,
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> }
    ): UsernameScanOutcome = withContext(Dispatchers.IO) {
        val names = UsernameVariants.fromHandle(handle)
        if (names.isEmpty()) return@withContext UsernameScanOutcome(0, emptyList())

        val main = scan(names.first(), limit, concurrency, onProgress)
        if (names.size == 1 || main.sitesChecked == 0) return@withContext main

        val popular = SitePriority.prioritise(OsintDatasets.loadUsernameDatabases()).take(variantSiteLimit)
        val extraHits = ArrayList<UsernameHit>()
        val discarded = ArrayList(main.discarded)
        var unreachable = main.unreachable

        for (name in names.drop(1)) {
            val scanned = scanSites(popular, name, concurrency)
            extraHits += scanned.hits
            discarded += scanned.discarded
            unreachable += scanned.unreachable
        }

        main.copy(
            confirmed = (main.confirmed + extraHits).sortedWith(HIT_ORDER),
            unreachable = unreachable,
            discarded = discarded.distinct(),
            namesChecked = names
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
            verificationStatus = statusFor(result),
            queriedName = username
        )
    }

    private fun toHit(result: ProbeResult, name: String) = UsernameHit(
        site = result.site.name,
        category = result.site.category,
        source = result.site.source,
        profileUrl = result.profileUrl,
        statusCode = result.statusCode,
        finalUrl = result.finalUrl,
        evidence = result.evidence + " • control check passed",
        confidence = confidenceFor(result),
        // The UI counts hits by this exact string, so it must stay "Matched".
        verificationStatus = statusFor(result),
        queriedName = name
    )

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

    suspend fun retry(username: String, issues: List<SiteIssue>): UsernameRetryOutcome = withContext(Dispatchers.IO) {
        val retryable = issues.filter { it.retryable }
        if (retryable.isEmpty()) return@withContext UsernameRetryOutcome()

        val hits = mutableListOf<UsernameHit>()
        val newIssues = mutableListOf<SiteIssue>()
        val discarded = mutableListOf<String>()

        for (issue in retryable) {
            val probe = OsintDatasets.findUsernameProbe(issue.site)
            if (probe == null) {
                newIssues += issue
                continue
            }
            val result = OsintDatasets.evaluate(probe, username, timeoutMs = 12_000)
            when (result.verdict) {
                ProbeVerdict.FOUND -> {
                    val controlHit = OsintDatasets.evaluate(probe, HitVerifier.randomUsername(), timeoutMs = 10_000)
                        .verdict == ProbeVerdict.FOUND
                    if (controlHit) {
                        discarded += result.site.name
                    } else {
                        hits += toHit(result, username)
                    }
                }
                ProbeVerdict.UNKNOWN -> {
                    newIssues += issue.copy(reason = "Retry failed (${result.evidence})")
                }
                ProbeVerdict.NOT_FOUND -> {
                    // Site answered; not found
                }
            }
        }
        UsernameRetryOutcome(confirmed = hits, issues = newIssues, discarded = discarded)
    }

    /** Total probes available across every merged database (0 until first load). */
    fun availableSiteCount(): Int = OsintDatasets.cachedUsernameCount()

    fun limitForDepth(depth: Int): Int = when (depth) {
        0 -> QUICK_LIMIT
        1 -> DEFAULT_LIMIT
        else -> FULL_SCAN
    }

    const val QUICK_LIMIT = 150
    const val DEFAULT_LIMIT = 300
    const val STANDARD_LIMIT = DEFAULT_LIMIT
    const val FULL_SCAN = 0
    const val VARIANT_SITE_LIMIT = 40
}
