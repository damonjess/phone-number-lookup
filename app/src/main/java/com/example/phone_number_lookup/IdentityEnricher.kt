package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class KeybaseProof(
    val service: String,
    val handle: String,
    val proofUrl: String
)

data class PgpKeyRecord(
    val keyId: String,
    val createdDate: String?,
    val uids: List<String>
)

data class WaybackSummary(
    val targetUrl: String,
    val snapshotCount: Int,
    val firstSeen: String?,
    val lastSeen: String?,
    val latestArchiveUrl: String?
)

typealias IdentityEnrichmentReport = IdentityEnrichment

data class IdentityEnrichment(
    val query: String = "",
    val keybaseUsername: String? = null,
    val keybaseDisplayName: String? = null,
    val keybaseBio: String? = null,
    val keybaseLocation: String? = null,
    val keybaseAvatarUrl: String? = null,
    val keybaseProofs: List<KeybaseProof> = emptyList(),
    val pgpKeys: List<PgpKeyRecord> = emptyList(),
    val waybackSnapshots: List<WaybackSummary> = emptyList(),
    val providersChecked: Int = 3,
    val providersFailed: Int = 0
) {
    val pgpRecords: List<PgpKeyRecord> get() = pgpKeys
    val hasData: Boolean get() = hasAnyData

    val hasAnyData: Boolean
        get() = keybaseUsername != null ||
            keybaseProofs.isNotEmpty() ||
            pgpKeys.isNotEmpty() ||
            waybackSnapshots.isNotEmpty()

    val completedCleanly: Boolean
        get() = providersChecked > 0 && providersFailed < providersChecked
}

object IdentityEnricher {

    suspend fun enrich(query: String, isEmail: Boolean): IdentityEnrichment = withContext(Dispatchers.IO) {
        val clean = query.trim().removePrefix("@")
        if (clean.isBlank()) return@withContext IdentityEnrichment(query = query)

        var failedCount = 0

        val keybaseDeferred = async {
            runCatching {
                val primary = fetchKeybase(clean, isEmail)
                if (isEmail && primary.first == null && primary.third.isEmpty()) {
                    val handle = EmailRegistrationScanner.localPart(clean)
                    if (handle != null && handle.length >= 4) {
                        fetchKeybase(handle, isEmail = false)
                    } else {
                        primary
                    }
                } else {
                    primary
                }
            }.getOrElse {
                synchronized(this) { failedCount++ }
                Triple(null, null, emptyList())
            }
        }
        val pgpDeferred = async {
            runCatching { fetchPgpKeys(clean) }
                .getOrElse {
                    synchronized(this) { failedCount++ }
                    emptyList()
                }
        }
        val waybackDeferred = async {
            runCatching {
                if (!isEmail) {
                    fetchWaybackForUsername(clean)
                } else {
                    val handle = EmailRegistrationScanner.localPart(clean)
                    if (handle != null && handle.length >= 4) {
                        fetchWaybackForUsername(handle)
                    } else {
                        emptyList()
                    }
                }
            }.getOrElse {
                synchronized(this) { failedCount++ }
                emptyList()
            }
        }

        val (kbName, kbBio, kbProofs) = keybaseDeferred.await()
        IdentityEnrichment(
            query = query,
            keybaseDisplayName = kbName,
            keybaseBio = kbBio,
            keybaseProofs = kbProofs,
            pgpKeys = pgpDeferred.await(),
            waybackSnapshots = waybackDeferred.await(),
            providersChecked = 3,
            providersFailed = failedCount
        )
    }

    private fun fetchKeybase(
        query: String,
        isEmail: Boolean
    ): Triple<String?, String?, List<KeybaseProof>> {
        val param = if (isEmail) "email" else "usernames"
        val url = "https://keybase.io/_/api/1.0/user/lookup.json?$param=${ScanHttp.encode(query)}"
        val response = ScanHttp.get(url, timeoutMs = 10_000, headers = mapOf("Accept" to "application/json"))
        if (!response.isOk || response.body.isBlank()) return Triple(null, null, emptyList())

        return runCatching {
            val root = JSONObject(response.body)
            val them = root.optJSONArray("them") ?: return Triple(null, null, emptyList())
            val user = them.optJSONObject(0) ?: return Triple(null, null, emptyList())

            val profile = user.optJSONObject("profile")
            val fullName = profile?.optString("full_name")?.takeIf { it.isNotBlank() }
            val bio = profile?.optString("bio")?.takeIf { it.isNotBlank() }

            val proofs = mutableListOf<KeybaseProof>()
            val allProofs = user.optJSONObject("proofs_summary")?.optJSONArray("all")
            if (allProofs != null) {
                for (i in 0 until allProofs.length()) {
                    val p = allProofs.optJSONObject(i) ?: continue
                    val type = p.optString("proof_type").takeIf { it.isNotBlank() } ?: continue
                    val nametag = p.optString("nametag").takeIf { it.isNotBlank() } ?: continue
                    val humanUrl = p.optString("human_url").takeIf { it.isNotBlank() }
                        ?: p.optString("service_url").takeIf { it.isNotBlank() }.orEmpty()
                    proofs += KeybaseProof(type.uppercase(), nametag, humanUrl)
                }
            }
            Triple(fullName, bio, proofs)
        }.getOrDefault(Triple(null, null, emptyList()))
    }

    /** Parses the machine-readable (`options=mr`) index from Ubuntu's global OpenPGP keyserver. */
    internal fun fetchPgpKeys(query: String): List<PgpKeyRecord> {
        val url = "https://keyserver.ubuntu.com/pks/lookup?search=${ScanHttp.encode(query)}&options=mr&op=index"
        val response = ScanHttp.get(url, timeoutMs = 10_000)
        if (!response.isOk || response.body.isBlank()) return emptyList()
        return parsePgpMachineReadable(response.body)
    }

    internal fun parsePgpMachineReadable(body: String): List<PgpKeyRecord> {
        val records = mutableListOf<PgpKeyRecord>()
        var currentKeyId: String? = null
        var currentCreated: String? = null
        val currentUids = mutableListOf<String>()

        for (rawLine in body.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("pub:") -> {
                    if (currentKeyId != null && currentUids.isNotEmpty()) {
                        records += PgpKeyRecord(currentKeyId, currentCreated, currentUids.toList())
                    }
                    val parts = line.split(':')
                    currentKeyId = parts.getOrNull(1)?.takeLast(16)
                    val epochSec = parts.getOrNull(4)?.toLongOrNull()
                    currentCreated = epochSec?.let {
                        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                            .format(java.util.Date(it * 1000L))
                    }
                    currentUids.clear()
                }
                line.startsWith("uid:") -> {
                    val rawUid = line.split(':').getOrNull(1).orEmpty()
                    val decoded = runCatching { java.net.URLDecoder.decode(rawUid, "UTF-8") }.getOrDefault(rawUid).trim()
                    if (decoded.isNotBlank() && decoded !in currentUids) {
                        currentUids += decoded
                    }
                }
            }
        }
        if (currentKeyId != null && currentUids.isNotEmpty()) {
            records += PgpKeyRecord(currentKeyId, currentCreated, currentUids.toList())
        }
        return records.take(6)
    }

    private suspend fun fetchWaybackForUsername(username: String): List<WaybackSummary> = withContext(Dispatchers.IO) {
        val targets = listOf(
            "twitter.com/$username",
            "instagram.com/$username",
            "github.com/$username",
            "reddit.com/user/$username",
            "tiktok.com/@$username"
        )
        targets.map { target ->
            async { queryWaybackCdx(target) }
        }.mapNotNull { it.await() }
    }

    internal fun queryWaybackCdx(targetUrl: String): WaybackSummary? {
        val encoded = ScanHttp.encode(targetUrl)
        val cdxUrl = "https://web.archive.org/cdx/search/cdx?url=$encoded&output=json&fl=timestamp,original&collapse=timestamp:6&limit=50"
        val response = ScanHttp.get(cdxUrl, timeoutMs = 10_000)
        if (!response.isOk || response.body.isBlank()) return null

        return runCatching {
            val arr = JSONArray(response.body)
            if (arr.length() <= 1) return null // Row 0 is the ["timestamp","original"] header
            val count = arr.length() - 1
            val firstRow = arr.optJSONArray(1) ?: return null
            val lastRow = arr.optJSONArray(arr.length() - 1) ?: return null
            val firstTs = firstRow.optString(0)
            val lastTs = lastRow.optString(0)
            val original = lastRow.optString(1).ifBlank { "https://$targetUrl" }

            WaybackSummary(
                targetUrl = targetUrl,
                snapshotCount = count,
                firstSeen = formatWaybackTimestamp(firstTs),
                lastSeen = formatWaybackTimestamp(lastTs),
                latestArchiveUrl = "https://web.archive.org/web/$lastTs/$original"
            )
        }.getOrNull()
    }

    private fun formatWaybackTimestamp(ts: String): String? {
        if (ts.length < 8) return null
        return "${ts.substring(0, 4)}-${ts.substring(4, 6)}-${ts.substring(6, 8)}"
    }
}
