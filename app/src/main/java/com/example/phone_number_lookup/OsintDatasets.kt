package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class AccountMode { RAW, HASH_MD5, HASH_SHA256, APPEND }

data class SiteProbe(
    val name: String,
    val category: String,
    val source: String,
    val method: String,
    val urlTemplate: String,
    val accountMode: AccountMode,
    val profileTemplate: String,
    val headers: Map<String, String>,
    val bodyTemplate: String?,
    val foundCodes: Set<Int>,
    val foundStrings: List<String>,
    val missingCodes: Set<Int>,
    val missingStrings: List<String>,
    val missingUrls: List<String>
)

enum class ProbeVerdict { FOUND, NOT_FOUND, UNKNOWN }

enum class ProbeFailure {
    NETWORK,
    HTTP_403,
    HTTP_429,
    HTTP_5XX,
    BLOCKED,
    OTHER
}

object ProbeFailures {
    fun classify(statusCode: Int, body: String, networkError: Boolean): ProbeFailure = when {
        networkError -> ProbeFailure.NETWORK
        statusCode == 403 -> ProbeFailure.HTTP_403
        statusCode == 429 -> ProbeFailure.HTTP_429
        statusCode in 500..599 -> ProbeFailure.HTTP_5XX
        body.contains("captcha", ignoreCase = true) || body.contains("cloudflare", ignoreCase = true) -> ProbeFailure.BLOCKED
        else -> ProbeFailure.OTHER
    }
}

object SiteKey {
    fun dedupe(sites: List<SiteProbe>, byProfile: Boolean = false): List<SiteProbe> {
        val seenName = HashSet<String>()
        val seenProfile = HashSet<String>()
        val result = ArrayList<SiteProbe>()
        for (site in sites) {
            val nameKey = SitePriority.normalise(site.name)
            val profileKey = site.profileTemplate.lowercase().trimEnd('/')
                .replace("https://", "").replace("http://", "").replace("www.", "")
            
            if (nameKey.isNotEmpty() && seenName.contains(nameKey)) {
                continue
            }
            if (byProfile && profileKey.isNotEmpty() && seenProfile.contains(profileKey)) {
                continue
            }
            if (nameKey.isNotEmpty()) seenName.add(nameKey)
            if (profileKey.isNotEmpty()) seenProfile.add(profileKey)
            result.add(site)
        }
        return result
    }
}

data class ProbeResult(
    val site: SiteProbe,
    val verdict: ProbeVerdict,
    val profileUrl: String,
    val statusCode: Int,
    val finalUrl: String = "",
    val evidence: String = "",
    val failure: ProbeFailure? = null
)

object OsintDatasets {

    private const val DATASET_MAX_CHARS = 16_000_000

    const val SRC_WMN = "WhatsMyName"
    const val SRC_SHERLOCK = "Sherlock"
    const val SRC_TOOKIE = "Tookie-OSINT"
    const val SRC_MAIGRET = "Maigret"
    const val SRC_SOCIAL_ANALYZER = "SocialAnalyzer"
    const val SRC_BLACKBIRD = "Blackbird"
    const val SRC_CURATED = "Verified"

    const val WMN_URL =
        "https://raw.githubusercontent.com/WebBreacher/WhatsMyName/main/wmn-data.json"
    const val BLACKBIRD_WMN_URL =
        "https://raw.githubusercontent.com/p1ngul1n0/blackbird/main/data/wmn-data.json"
    const val MAIGRET_URL =
        "https://raw.githubusercontent.com/soxoj/maigret/main/maigret/resources/data.json"
    const val SOCIAL_ANALYZER_URL =
        "https://raw.githubusercontent.com/qeeqbox/social-analyzer/main/data/sites.json"
    const val SHERLOCK_URL =
        "https://raw.githubusercontent.com/sherlock-project/sherlock/master/sherlock_project/resources/data.json"
    const val TOOKIE_URL =
        "https://raw.githubusercontent.com/Alfredredbird/tookie-osint/main/sites/sites.json"
    const val BLACKBIRD_EMAIL_URL =
        "https://raw.githubusercontent.com/p1ngul1n0/blackbird/main/data/email-data.json"

    private fun String.resolveMarker(account: String, target: String): String =
        replace("{account}", account).replace("{username}", account).replace("{}", account).replace("{target}", target)

    fun evaluate(probe: SiteProbe, account: String, timeoutMs: Int = 10_000): ProbeResult {
        val target = transformAccount(account, probe.accountMode)
        val url = probe.urlTemplate.replace("{account}", ScanHttp.encode(target))
        val profileUrl = probe.profileTemplate.replace("{account}", account)
        val body = probe.bodyTemplate?.replace("{account}", target)

        val response = if (probe.method.equals("POST", ignoreCase = true)) {
            ScanHttp.post(url, body, timeoutMs = timeoutMs, headers = probe.headers)
        } else {
            ScanHttp.get(url, timeoutMs = timeoutMs, headers = probe.headers)
        }

        if (response.isNetworkError) {
            return ProbeResult(
                probe, ProbeVerdict.UNKNOWN, profileUrl, -1, evidence = "Network error or timeout",
                failure = ProbeFailure.NETWORK
            )
        }

        val text = response.body
        val finalUrl = response.finalUrl

        val resolvedMissingStrings = probe.missingStrings.map { it.resolveMarker(account, target) }
        val resolvedMissingUrls = probe.missingUrls.map { it.resolveMarker(account, target) }
        val resolvedFoundStrings = probe.foundStrings.map { it.resolveMarker(account, target) }

        val missing = response.code in probe.missingCodes ||
            resolvedMissingStrings.any { it.isNotEmpty() && text.contains(it, ignoreCase = true) } ||
            resolvedMissingUrls.any { it.isNotEmpty() && finalUrl.contains(it, ignoreCase = true) }
        if (missing) {
            val evidence = when {
                response.code == 404 -> "HTTP 404 — profile not found"
                resolvedMissingUrls.any { it.isNotEmpty() && finalUrl.contains(it, ignoreCase = true) } ->
                    "Redirected to a missing or login page"
                else -> "Site-specific missing marker"
            }
            return ProbeResult(probe, ProbeVerdict.NOT_FOUND, profileUrl, response.code, finalUrl, evidence)
        }

        if ((response.code == 404 || response.code == 410) && response.code !in probe.foundCodes) {
            return ProbeResult(
                probe, ProbeVerdict.NOT_FOUND, profileUrl, response.code, finalUrl,
                "HTTP ${response.code} — profile not found"
            )
        }

        val hasFoundSignals = probe.foundCodes.isNotEmpty() || resolvedFoundStrings.isNotEmpty()
        val codeMatches = if (probe.foundCodes.isEmpty()) response.isOk else response.code in probe.foundCodes
        val stringMatches = resolvedFoundStrings.isEmpty() ||
            resolvedFoundStrings.any { it.isNotEmpty() && text.contains(it, ignoreCase = true) }

        val found = codeMatches && stringMatches

        val verdict = when {
            found -> ProbeVerdict.FOUND
            else -> ProbeVerdict.UNKNOWN
        }
        val evidence = when {
            found && resolvedFoundStrings.isNotEmpty() -> "Matched a site-specific positive marker"
            found && hasFoundSignals -> "Matched expected HTTP ${response.code} status"
            found -> "HTTP ${response.code} response; this site has no positive marker"
            response.isOk -> "HTTP ${response.code} response but positive marker not present"
            else -> "HTTP ${response.code} response"
        }
        val failure = if (verdict == ProbeVerdict.UNKNOWN) {
            ProbeFailures.classify(response.code, text, networkError = false)
        } else {
            null
        }
        return ProbeResult(probe, verdict, profileUrl, response.code, finalUrl, evidence, failure)
    }

    suspend fun findUsernameProbe(siteName: String): SiteProbe? =
        loadUsernameDatabases().firstOrNull { it.name.equals(siteName, ignoreCase = true) }

    suspend fun findEmailProbe(siteName: String): SiteProbe? =
        loadEmailDatabase().firstOrNull { it.name.equals(siteName, ignoreCase = true) }

    fun transformAccount(account: String, mode: AccountMode): String = when (mode) {
        AccountMode.RAW, AccountMode.APPEND -> account
        AccountMode.HASH_MD5 -> hash("MD5", account)
        AccountMode.HASH_SHA256 -> hash("SHA-256", account)
    }

    private fun hash(algorithm: String, value: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        return digest.digest(value.trim().lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    suspend fun loadUsernameDatabases(): List<SiteProbe> = withContext(Dispatchers.IO) {
        cachedUsername ?: run {
            val wmn = async { fetch(WMN_URL)?.let { parseWhatsMyName(it) } ?: emptyList() }
            val blackbirdWmn = async { fetch(BLACKBIRD_WMN_URL)?.let { parseWhatsMyName(it, SRC_BLACKBIRD) } ?: emptyList() }
            val maigret = async { fetch(MAIGRET_URL)?.let { parseMaigret(it) } ?: emptyList() }
            val socialAnalyzer = async { fetch(SOCIAL_ANALYZER_URL)?.let { parseSocialAnalyzer(it) } ?: emptyList() }
            val sherlock = async { fetch(SHERLOCK_URL)?.let { parseSherlock(it) } ?: emptyList() }
            val tookie = async { fetch(TOOKIE_URL)?.let { parseTookie(it) } ?: emptyList() }

            val fromWmn = wmn.await()
            val fromBlackbirdWmn = blackbirdWmn.await()
            val fromMaigret = maigret.await()
            val fromSocialAnalyzer = socialAnalyzer.await()
            val fromSherlock = sherlock.await()
            val fromTookie = tookie.await()

            val deduped = dedupe(
                CuratedUsernameChecks.PROBES +
                    fromWmn +
                    fromBlackbirdWmn +
                    fromMaigret +
                    fromSocialAnalyzer +
                    fromSherlock +
                    fromTookie,
                byProfile = true
            )
            if (fromWmn.isNotEmpty() && fromSherlock.isNotEmpty()) {
                cachedUsername = deduped
            }
            deduped
        }
    }

    @Volatile
    private var cachedUsername: List<SiteProbe>? = null

    fun cachedUsernameCount(): Int = cachedUsername?.size ?: 0

    fun cachedUsernameBreakdown(): Map<String, Int> =
        cachedUsername?.groupingBy { it.source }?.eachCount() ?: emptyMap()

    fun invalidateCache() {
        cachedUsername = null
        cachedEmail = null
    }

    private fun fetch(url: String): String? {
        val response = ScanHttp.get(url, timeoutMs = 25_000, maxBodyChars = DATASET_MAX_CHARS)
        return if (response.isOk && response.body.isNotBlank()) response.body else null
    }

    internal data class SourceCount(val source: String, val sites: Int)

    private fun dedupe(sites: List<SiteProbe>, byProfile: Boolean = false): List<SiteProbe> =
        SiteKey.dedupe(sites, byProfile)

    internal fun parseWhatsMyName(json: String, sourceLabel: String = SRC_WMN): List<SiteProbe> {
        val out = ArrayList<SiteProbe>()
        try {
            val sites = JSONObject(json).optJSONArray("sites") ?: return emptyList()
            for (i in 0 until sites.length()) {
                val s = sites.optJSONObject(i) ?: continue
                if (s.has("valid") && !s.optBoolean("valid", true)) continue
                val uriCheck = s.optString("uri_check", "")
                if (!uriCheck.contains("{account}")) continue

                val postBody = s.optString("post_body", "")
                val eCode = s.optInt("e_code", 200)
                val mCode = s.optInt("m_code", 404)
                val headers = s.optJSONObject("headers").headerMap()

                out.add(
                    SiteProbe(
                        name = s.optString("name", "Unknown"),
                        category = s.optString("cat", "general"),
                        source = sourceLabel,
                        method = if (postBody.isNotEmpty()) "POST" else "GET",
                        urlTemplate = uriCheck,
                        accountMode = AccountMode.RAW,
                        profileTemplate = s.optString("uri_pretty", uriCheck).ifBlank { uriCheck },
                        headers = headers,
                        bodyTemplate = postBody.takeIf { it.isNotEmpty() },
                        foundCodes = if (eCode > 0) setOf(eCode) else emptySet(),
                        foundStrings = listOfNotNull(
                            s.optString("e_string", "").cleanSignal()
                        ),
                        missingCodes = if (mCode > 0) setOf(mCode) else emptySet(),
                        missingStrings = listOfNotNull(
                            s.optString("m_string", "").cleanSignal()
                        ),
                        missingUrls = emptyList()
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    internal fun parseMaigret(json: String): List<SiteProbe> {
        val out = ArrayList<SiteProbe>()
        try {
            val root = JSONObject(json)
            val sites = root.optJSONObject("sites") ?: root
            for (key in sites.keys()) {
                if (key.startsWith("$")) continue
                val s = sites.optJSONObject(key) ?: continue
                if (s.optBoolean("disabled", false)) continue

                val url = s.optString("url", "")
                val urlMain = s.optString("urlMain", "")
                val probeUrl = s.optString("urlProbe", "").ifBlank { url }
                if (!probeUrl.contains("{username}") && !probeUrl.contains("{}")) continue

                val checkType = s.optString("checkType", "status_code").lowercase()
                val presence = s.optStringList("presenseStrs")
                val absence = s.optStringList("absenceStrs")
                if (checkType == "message" && presence.isEmpty() && absence.isEmpty()) continue

                val template = probeUrl
                    .replace("{username}", "{account}")
                    .replace("{}", "{account}")
                    .replace("{urlMain}", urlMain.trimEnd('/'))

                val profileTemplate = url
                    .replace("{username}", "{account}")
                    .replace("{}", "{account}")
                    .replace("{urlMain}", urlMain.trimEnd('/'))
                    .ifBlank { template }

                val tags = s.optJSONArray("tags").stringList()
                val isNsfw = tags.any { it.equals("nsfw", true) || it.equals("porn", true) }

                out.add(
                    SiteProbe(
                        name = key,
                        category = if (isNsfw) "nsfw" else (tags.firstOrNull() ?: "general"),
                        source = SRC_MAIGRET,
                        method = "GET",
                        urlTemplate = template,
                        accountMode = AccountMode.RAW,
                        profileTemplate = profileTemplate,
                        headers = s.optJSONObject("headers").headerMap(),
                        bodyTemplate = null,
                        foundCodes = if (checkType == "status_code") setOf(200) else emptySet(),
                        foundStrings = presence,
                        missingCodes = setOf(404, 410),
                        missingStrings = absence,
                        missingUrls = s.optStringList("errorUrl")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    internal fun parseSocialAnalyzer(json: String): List<SiteProbe> {
        val out = ArrayList<SiteProbe>()
        try {
            val arr = JSONObject(json).optJSONArray("websites_entries") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val url = s.optString("url", "").trim()
                if (!url.contains("{username}")) continue
                val template = url.replace("{username}", "{account}")
                val detections = s.optJSONArray("detections") ?: continue
                val foundStrings = mutableListOf<String>()
                for (j in 0 until detections.length()) {
                    val d = detections.optJSONObject(j) ?: continue
                    val str = d.optString("string", "").cleanSignal() ?: continue
                    if (d.optString("return", "").equals("true", true)) {
                        foundStrings += str.replace("{username}", "{account}")
                    }
                }
                if (foundStrings.isEmpty()) continue
                val label = hostLabel(template)
                out.add(
                    SiteProbe(
                        name = label,
                        category = s.optString("type", "social").lowercase().ifBlank { "social" },
                        source = SRC_SOCIAL_ANALYZER,
                        method = "GET",
                        urlTemplate = template,
                        accountMode = AccountMode.RAW,
                        profileTemplate = template,
                        headers = emptyMap(),
                        bodyTemplate = null,
                        foundCodes = setOf(200),
                        foundStrings = foundStrings,
                        missingCodes = setOf(404, 410),
                        missingStrings = emptyList(),
                        missingUrls = emptyList()
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    internal fun parseSherlock(json: String): List<SiteProbe> {
        val out = ArrayList<SiteProbe>()
        try {
            val root = JSONObject(json)
            for (key in root.keys()) {
                if (key.startsWith("$")) continue
                val s = root.optJSONObject(key) ?: continue

                val url = s.optString("url", "")
                val probeUrl = s.optString("urlProbe", "").ifBlank { url }
                val method = s.optString("request_method", "GET").uppercase()

                val rawPayload = s.optJSONObject("request_payload")?.toString()
                    ?: s.optString("request_payload", "").cleanSignal()
                val hasPlaceholder = probeUrl.contains("{}") || url.contains("{}") ||
                    rawPayload?.contains("{}") == true
                if (!hasPlaceholder) continue

                val template = probeUrl.replace("{}", "{account}")
                val payload = rawPayload
                    ?.replace("{}", "{account}")
                    ?.takeIf { method == "POST" }

                val errorCodes = s.optIntSet("errorCode")
                val errorMsgs = s.optStringList("errorMsg")
                val errorUrls = s.optStringList("errorUrl")

                out.add(
                    SiteProbe(
                        name = key,
                        category = if (s.optBoolean("isNSFW", false)) {
                            "nsfw"
                        } else {
                            s.optJSONArray("tags").stringList().firstOrNull() ?: "general"
                        },
                        source = SRC_SHERLOCK,
                        method = if (method == "POST") "POST" else "GET",
                        urlTemplate = template,
                        accountMode = AccountMode.RAW,
                        profileTemplate = url.replace("{}", "{account}").ifBlank { template },
                        headers = s.optJSONObject("headers").headerMap(),
                        bodyTemplate = payload,
                        foundCodes = emptySet(),
                        foundStrings = emptyList(),
                        missingCodes = errorCodes,
                        missingStrings = errorMsgs,
                        missingUrls = errorUrls
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    internal fun parseTookie(json: String): List<SiteProbe> {
        val out = ArrayList<SiteProbe>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val base = s.optString("site", "")
                if (base.isEmpty()) continue
                val nsfw = s.optString("nsfw", "false").equals("true", ignoreCase = true)
                val errorMessage = s.optString("errorMessage", "").cleanSignal()
                    ?.takeIf { !it.equals("none", ignoreCase = true) }

                out.add(
                    SiteProbe(
                        name = hostLabel(base),
                        category = if (nsfw) "nsfw" else "general",
                        source = SRC_TOOKIE,
                        method = "GET",
                        urlTemplate = base + "{account}",
                        accountMode = AccountMode.APPEND,
                        profileTemplate = base + "{account}",
                        headers = emptyMap(),
                        bodyTemplate = null,
                        foundCodes = emptySet(),
                        foundStrings = emptyList(),
                        missingCodes = emptySet(),
                        missingStrings = listOfNotNull(errorMessage),
                        missingUrls = emptyList()
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    private val UNRELIABLE_EMAIL_SITES = setOf("olx", "imageshack")

    suspend fun loadEmailDatabase(): List<SiteProbe> = withContext(Dispatchers.IO) {
        cachedEmail ?: run {
            val parsed = fetch(BLACKBIRD_EMAIL_URL)?.let { parseBlackbirdEmail(it) } ?: emptyList()
            val reliable = parsed.filterNot { it.name.trim().lowercase() in UNRELIABLE_EMAIL_SITES }
            val merged = dedupe(CuratedEmailChecks.PROBES + reliable)
            if (parsed.isNotEmpty()) cachedEmail = merged
            merged
        }
    }

    @Volatile
    private var cachedEmail: List<SiteProbe>? = null

    internal fun parseBlackbirdEmail(json: String): List<SiteProbe> {
        val out = ArrayList<SiteProbe>()
        try {
            val sites = JSONObject(json).optJSONArray("sites") ?: return emptyList()
            for (i in 0 until sites.length()) {
                val s = sites.optJSONObject(i) ?: continue
                val uriCheck = s.optString("uri_check", "")
                val data = s.optString("data", "").cleanSignal()
                if (!uriCheck.contains("{account}") && data?.contains("{account}") != true) continue

                val op = when (s.optString("input_operation", "")) {
                    "hash-sha256" -> AccountMode.HASH_SHA256
                    "hash-md5" -> AccountMode.HASH_MD5
                    else -> AccountMode.RAW
                }
                val eCode = s.optInt("e_code", 200)
                val mCode = s.optInt("m_code", 404)
                val method = s.optString("method", "GET").uppercase()

                out.add(
                    SiteProbe(
                        name = s.optString("name", "Unknown"),
                        category = s.optString("cat", "general"),
                        source = SRC_BLACKBIRD,
                        method = if (method == "POST") "POST" else "GET",
                        urlTemplate = uriCheck,
                        accountMode = op,
                        profileTemplate = sanitizeProfile(uriCheck),
                        headers = s.optJSONObject("headers").headerMap(),
                        bodyTemplate = data,
                        foundCodes = if (eCode > 0) setOf(eCode) else emptySet(),
                        foundStrings = listOfNotNull(s.optString("e_string", "").cleanSignal()),
                        missingCodes = if (mCode > 0) setOf(mCode) else emptySet(),
                        missingStrings = listOfNotNull(s.optString("m_string", "").cleanSignal()),
                        missingUrls = emptyList()
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun sanitizeProfile(template: String): String =
        template.replace("{account}", "").trimEnd('/')

    private fun hostLabel(url: String): String =
        url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .substringBefore('/').substringBefore('?').ifBlank { url }

    private fun String.cleanSignal(): String? {
        val v = trim()
        return v.takeIf { it.isNotEmpty() && it != "null" && it != "none" }
    }

    private fun JSONObject?.headerMap(): Map<String, String> {
        val obj = this ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (key in obj.keys()) {
            obj.optString(key, "").cleanSignal()?.let { map[key] = it }
        }
        return map
    }

    private fun JSONArray?.stringList(): List<String> {
        val arr = this ?: return emptyList()
        val list = ArrayList<String>()
        for (i in 0 until arr.length()) {
            arr.opt(i)?.toString()?.cleanSignal()?.let { list.add(it) }
        }
        return list
    }

    private fun JSONObject?.optIntSet(key: String): Set<Int> {
        val obj = this ?: return emptySet()
        if (!obj.has(key) || obj.isNull(key)) return emptySet()
        val arr = obj.optJSONArray(key)
        if (arr != null) {
            val out = LinkedHashSet<Int>()
            for (i in 0 until arr.length()) {
                val v = arr.optInt(i, -1)
                if (v > 0) out.add(v)
            }
            return out
        }
        val single = obj.optInt(key, -1)
        return if (single > 0) setOf(single) else emptySet()
    }

    private fun JSONObject?.optStringList(key: String): List<String> {
        val obj = this ?: return emptyList()
        if (!obj.has(key) || obj.isNull(key)) return emptyList()
        obj.optJSONArray(key)?.let { return it.stringList() }
        return listOfNotNull(obj.optString(key, "").cleanSignal())
    }
}
