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



data class ProbeResult(
    val site: SiteProbe,
    val verdict: ProbeVerdict,
    val profileUrl: String,
    val statusCode: Int,
    val finalUrl: String = "",
    val evidence: String = "",
    /** Set when [verdict] is UNKNOWN: why no usable answer came back. */
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
        val profileUrl = cleanProfileUrl(probe.name, probe.profileTemplate.replace("{account}", account))
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

        // A 404/410 is the ordinary "no such profile" answer. Tookie entries (and some others) declare no
        // missing codes, so these used to come back inconclusive and were counted as "blocked".
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

    suspend fun findUsernameProbe(
        siteName: String,
        profileUrl: String? = null,
        username: String? = null
    ): SiteProbe? {
        val sites = loadUsernameDatabases()
        sites.firstOrNull { it.name.equals(siteName, ignoreCase = true) }?.let { return it }
        // Python-only hits (Tookie) can name a site that was merged away in favour of another
        // source with the same profile URL, so fall back to matching the rendered URL.
        if (profileUrl.isNullOrBlank() || username.isNullOrBlank()) return null
        val key = SiteKey.of(profileUrl)
        if (key.isEmpty()) return null
        return sites.firstOrNull { SiteKey.of(it.profileTemplate.replace("{account}", username)) == key }
    }

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

    private val UNRELIABLE_EMAIL_SITES = setOf(
        "olx", "imageshack", "picsart", "substack", "tumblr",
        "codepen", "gitlab", "replit", "figma", "imgur", "pornhub",
        "notion", "any.do", "anydo", "milanuncios", "netshoes",
        "myspace", "wattpad", "quora", "patreon", "pinterest"
    )

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

                val siteName = s.optString("name", "Unknown")
                out.add(
                    SiteProbe(
                        name = siteName,
                        category = s.optString("cat", "general"),
                        source = SRC_BLACKBIRD,
                        method = if (method == "POST") "POST" else "GET",
                        urlTemplate = uriCheck,
                        accountMode = op,
                        profileTemplate = cleanProfileUrl(siteName, uriCheck),
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

    fun cleanProfileUrl(siteName: String, rawUrl: String): String {
        val siteLower = siteName.trim().lowercase()
        val rawLower = rawUrl.trim().lowercase()

        if (siteLower == "twitter" || siteLower == "x" || siteLower == "x / twitter" ||
            siteLower.contains("twitter") || rawLower.contains("twitter.com") || 
            rawLower.contains("//x.com") || rawLower.contains(".x.com")
        ) {
            if (rawLower.contains("email_available") || rawLower.contains("/i/users/") ||
                rawLower.contains("api.twitter.com") || rawLower.contains("api.x.com") ||
                rawLower.endsWith(".json") || rawLower.contains(".json?")
            ) {
                return "https://x.com/"
            }
            val cleaned = rawUrl.replace("{account}", "").trimEnd('/')
            if (cleaned == "https://twitter.com" || cleaned == "https://x.com" ||
                cleaned == "https://twitter.com/i" || cleaned == "https://x.com/i" ||
                cleaned.contains("/i/users")
            ) {
                return "https://x.com/"
            }
            return if (rawUrl.contains("{account}")) {
                rawUrl.replace("{account}", "")
            } else {
                rawUrl
            }.replace("twitter.com", "x.com")
        }

        if (siteLower == "spotify" || rawLower.contains("spotify.com")) {
            return "https://open.spotify.com/"
        }
        if (siteLower.contains("firefox") || rawLower.contains("accounts.firefox.com")) {
            return "https://accounts.firefox.com/"
        }
        if (siteLower == "chess.com" || rawLower.contains("chess.com")) {
            return "https://www.chess.com/"
        }
        if (siteLower == "github" || rawLower.contains("github.com")) {
            return "https://github.com/"
        }
        if (siteLower == "duolingo" || rawLower.contains("duolingo.com")) {
            return "https://www.duolingo.com/"
        }
        if (siteLower == "gravatar" || rawLower.contains("gravatar.com")) {
            return "https://gravatar.com/"
        }
        if (siteLower.contains("wordpress") || rawLower.contains("wordpress.com")) {
            return "https://wordpress.com/"
        }
        if (siteLower == "keybase" || rawLower.contains("keybase.io")) {
            return "https://keybase.io/"
        }
        if (siteLower.contains("mastodon") || rawLower.contains("mastodon.social")) {
            return "https://mastodon.social/"
        }
        if (siteLower == "replit" || rawLower.contains("replit.com")) {
            return "https://replit.com/"
        }
        if (siteLower == "gitlab" || rawLower.contains("gitlab.com")) {
            return "https://gitlab.com/"
        }
        if (siteLower == "figma" || rawLower.contains("figma.com")) {
            return "https://www.figma.com/"
        }
        if (siteLower.contains("docker") || rawLower.contains("docker.com")) {
            return "https://hub.docker.com/"
        }
        if (siteLower == "substack" || rawLower.contains("substack.com")) {
            return "https://substack.com/"
        }
        if (siteLower == "letterboxd" || rawLower.contains("letterboxd.com")) {
            return "https://letterboxd.com/"
        }
        if (siteLower == "imgur" || rawLower.contains("imgur.com")) {
            return "https://imgur.com/"
        }
        if (siteLower == "tumblr" || rawLower.contains("tumblr.com")) {
            return "https://www.tumblr.com/"
        }
        if (siteLower == "pornhub" || rawLower.contains("pornhub.com")) {
            return "https://www.pornhub.com/"
        }
        if (siteLower == "atlassian" || rawLower.contains("atlassian.com")) {
            return "https://id.atlassian.com/"
        }

        if (rawLower.contains("spclient") || rawLower.contains("/api/") || rawLower.contains("/v1/") ||
            rawLower.contains("/v2/") || rawLower.contains("check_email") || rawLower.contains("validate=")
        ) {
            val uri = runCatching { java.net.URI(rawUrl) }.getOrNull()
            if (uri?.host != null) {
                val scheme = uri.scheme ?: "https"
                var host = uri.host
                if (host.startsWith("spclient.wg.")) {
                    host = "open.spotify.com"
                } else if (host.startsWith("api.")) {
                    host = host.removePrefix("api.")
                }
                return "$scheme://$host/"
            }
        }

        return rawUrl.replace("{account}", "").trimEnd('/')
    }

    private fun sanitizeProfile(template: String): String = cleanProfileUrl("", template)

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
