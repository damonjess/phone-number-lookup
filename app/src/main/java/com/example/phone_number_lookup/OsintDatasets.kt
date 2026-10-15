package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Unified OSINT site-database layer.
 *
 * The Python tools people normally run in Termux — Sherlock, Blackbird, Tookie-OSINT,
 * Maigret, Holehe — are thin engines wrapped around *public site lists*. This file
 * pulls those same upstream lists straight from GitHub at runtime and normalises them
 * into one probe model, so the app gets the upstream coverage without pip, git or a
 * Python runtime on the phone.
 *
 * Sources (all public, all keyless):
 *   WhatsMyName  WebBreacher/WhatsMyName  wmn-data.json
 *   Sherlock     sherlock-project/sherlock resources/data.json
 *   Tookie-OSINT Alfredredbird/tookie-osint sites/sites.json
 *   Blackbird    antoniaci/blackbird      data/email-data.json   (email checks)
 */
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
    /** HTTP status codes that mean "this account exists". */
    val foundCodes: Set<Int>,
    /** Substrings in the body that mean "this account exists". */
    val foundStrings: List<String>,
    /** HTTP status codes that mean "no such account". */
    val missingCodes: Set<Int>,
    /** Substrings in the body that mean "no such account". */
    val missingStrings: List<String>,
    /** Substrings in the final (redirected) URL that mean "no such account". */
    val missingUrls: List<String>
)

enum class ProbeVerdict { FOUND, NOT_FOUND, UNKNOWN }

data class ProbeResult(
    val site: SiteProbe,
    val verdict: ProbeVerdict,
    val profileUrl: String,
    val statusCode: Int,
    val finalUrl: String = "",
    val evidence: String = ""
)

object OsintDatasets {

    /** Dataset JSON must never be truncated by the per-page body cap. */
    private const val DATASET_MAX_CHARS = 16_000_000

    const val SRC_WMN = "WhatsMyName"
    const val SRC_SHERLOCK = "Sherlock"
    const val SRC_TOOKIE = "Tookie-OSINT"
    const val SRC_BLACKBIRD = "Blackbird"
    const val SRC_CURATED = "Verified"

    const val WMN_URL =
        "https://raw.githubusercontent.com/WebBreacher/WhatsMyName/main/wmn-data.json"
    const val SHERLOCK_URL =
        "https://raw.githubusercontent.com/sherlock-project/sherlock/master/sherlock_project/resources/data.json"
    const val TOOKIE_URL =
        "https://raw.githubusercontent.com/Alfredredbird/tookie-osint/main/sites/sites.json"
    const val BLACKBIRD_EMAIL_URL =
        "https://raw.githubusercontent.com/p1ngul1n0/blackbird/main/data/email-data.json"

    /**
     * Runs [probe] against the target and classifies the response.
     *
     * Detection order mirrors the upstream tools: a *missing* signal always wins
     * (this is what keeps SPA/JS sites from producing false positives), then an
     * explicit *found* signal, then a plain 2xx fallback when the dataset declares
     * no positive marker at all (Sherlock `status_code` sites, Tookie sites).
     */
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
                probe, ProbeVerdict.UNKNOWN, profileUrl, -1, evidence = "Network error or timeout"
            )
        }

        val text = response.body
        val finalUrl = response.finalUrl

        val missing = response.code in probe.missingCodes ||
            probe.missingStrings.any { it.isNotEmpty() && text.contains(it, ignoreCase = true) } ||
            probe.missingUrls.any { it.isNotEmpty() && finalUrl.contains(it, ignoreCase = true) }
        if (missing) {
            val evidence = when {
                response.code == 404 -> "HTTP 404 — profile not found"
                probe.missingUrls.any { it.isNotEmpty() && finalUrl.contains(it, ignoreCase = true) } ->
                    "Redirected to a missing or login page"
                else -> "Site-specific missing marker"
            }
            return ProbeResult(probe, ProbeVerdict.NOT_FOUND, profileUrl, response.code, finalUrl, evidence)
        }

        val hasFoundSignals = probe.foundCodes.isNotEmpty() || probe.foundStrings.isNotEmpty()
        val found = if (!hasFoundSignals) {
            response.isOk
        } else {
            response.code in probe.foundCodes ||
                probe.foundStrings.any { it.isNotEmpty() && text.contains(it, ignoreCase = true) }
        }

        val verdict = when {
            found -> ProbeVerdict.FOUND
            response.isOk -> ProbeVerdict.UNKNOWN
            else -> ProbeVerdict.UNKNOWN
        }
        val evidence = when {
            found && hasFoundSignals -> "Matched a site-specific positive marker"
            found -> "HTTP ${response.code} response; this site has no positive marker"
            response.isOk -> "HTTP ${response.code} response but no positive marker"
            else -> "HTTP ${response.code} response"
        }
        return ProbeResult(probe, verdict, profileUrl, response.code, finalUrl, evidence)
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

    // ---------------------------------------------------------------- loading

    /**
     * Downloads every username dataset in parallel and returns the merged, de-duplicated
     * probe list. Failures in one dataset do not prevent the others from loading.
     */
    suspend fun loadUsernameDatabases(): List<SiteProbe> = withContext(Dispatchers.IO) {
        cachedUsername ?: run {
            val wmn = async { fetch(WMN_URL)?.let { parseWhatsMyName(it) } ?: emptyList() }
            val sherlock = async { fetch(SHERLOCK_URL)?.let { parseSherlock(it) } ?: emptyList() }
            val tookie = async { fetch(TOOKIE_URL)?.let { parseTookie(it) } ?: emptyList() }
            val fromWmn = wmn.await()
            val fromSherlock = sherlock.await()
            val fromTookie = tookie.await()
            val deduped = dedupe(fromWmn + fromSherlock + fromTookie, byProfile = true)
            // Keep the result for the session only when the two main lists arrived. Caching a failed
            // download made one bad start (offline, slow network) stick until restart. Tookie is the
            // smallest list and mostly overlaps the others, so if only it fails (for example its file
            // format changes again) that must not force re-downloading everything on every scan.
            if (fromWmn.isNotEmpty() && fromSherlock.isNotEmpty()) {
                cachedUsername = deduped
            }
            deduped
        }
    }

    @Volatile
    private var cachedUsername: List<SiteProbe>? = null

    /** Probes available in the merged username database (0 until the first load). */
    fun cachedUsernameCount(): Int = cachedUsername?.size ?: 0

    /** How many probes each source contributed to the merged database. */
    fun cachedUsernameBreakdown(): Map<String, Int> =
        cachedUsername?.groupingBy { it.source }?.eachCount() ?: emptyMap()

    /** Clears the in-memory dataset cache so the next scan re-downloads the latest lists. */
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

    // ------------------------------------------------------- WhatsMyName parse

    internal fun parseWhatsMyName(json: String): List<SiteProbe> {
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
                        source = SRC_WMN,
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
            // Partially fetched/malformed dataset: return whatever parsed.
        }
        return out
    }

    // ---------------------------------------------------------- Sherlock parse

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

                // Sherlock writes the account as `{}`. It can appear in the profile URL,
                // in a separate probe URL, or only in a POST payload (Discord and Anilist
                // post to a fixed endpoint), so all three must be checked.
                val rawPayload = s.optJSONObject("request_payload")?.toString()
                    ?: s.optString("request_payload", "").cleanSignal()
                val hasPlaceholder = probeUrl.contains("{}") || url.contains("{}") ||
                    rawPayload?.contains("{}") == true
                if (!hasPlaceholder) continue

                // The probeUrl is used for the check; `url` remains the human profile link.
                val template = probeUrl.replace("{}", "{account}")
                // Rewrite the payload's placeholder too, or the body would be sent with a
                // literal `{}` instead of the username.
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
            // ignore
        }
        return out
    }

    // ----------------------------------------------------------- Tookie parse

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
            // ignore
        }
        return out
    }

    // ------------------------------------------------------- Blackbird email

    /**
     * Checks observed to answer inconsistently. They are dropped rather than allowed to
     * raise false "this address is registered here" reports, which would make the whole
     * feature untrustworthy:
     *
     *  - OLX        answers a Cloudflare 403 to non-browser clients, and intermittently a
     *               200 whose body happens to contain the dataset's "exists" marker.
     *  - Imageshack returns a captcha error whose numeric code collides with the
     *               dataset's exists/not-exists markers, so the answer flips per request.
     *
     * Anything added here should be re-tested before removal.
     */
    private val UNRELIABLE_EMAIL_SITES = setOf("olx", "imageshack")

    /** Email-registration dataset (Blackbird). Loaded lazily and cached. */
    suspend fun loadEmailDatabase(): List<SiteProbe> = withContext(Dispatchers.IO) {
        cachedEmail ?: run {
            val parsed = fetch(BLACKBIRD_EMAIL_URL)?.let { parseBlackbirdEmail(it) } ?: emptyList()
            val reliable = parsed.filterNot { it.name.trim().lowercase() in UNRELIABLE_EMAIL_SITES }
            // Hand-ported checks that were verified live; they take precedence over
            // same-named dataset rows, which is why they are de-duplicated first.
            val merged = dedupe(CuratedEmailChecks.PROBES + reliable)
            // Without the Blackbird list only the 3 hand-ported checks run, so do not remember it.
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
                // Some checks (Adobe, for example) carry the account in the POST body
                // rather than the URL, so a missing placeholder in the URL is not fatal.
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
            // ignore
        }
        return out
    }

    /** Strips the `{account}` placeholder from a check URL so it can be used as a link. */
    private fun sanitizeProfile(template: String): String =
        template.replace("{account}", "").trimEnd('/')

    private fun hostLabel(url: String): String =
        url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .substringBefore('/').substringBefore('?').ifBlank { url }

    // ------------------------------------------------------------- json helpers

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

    /** Flattens a JSON array of strings, dropping empty/placeholder values. */
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

    /** Reads a value that the datasets write either as a single string or an array. */
    private fun JSONObject?.optStringList(key: String): List<String> {
        val obj = this ?: return emptyList()
        if (!obj.has(key) || obj.isNull(key)) return emptyList()
        obj.optJSONArray(key)?.let { return it.stringList() }
        return listOfNotNull(obj.optString(key, "").cleanSignal())
    }
}
