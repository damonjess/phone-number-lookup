package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class EmailDomainReport(
    val domain: String,
    val checked: Boolean,
    val acceptsMail: Boolean?,
    val mxProvider: String?,
    val spf: Boolean?,
    val dmarc: Boolean?,
    val disposable: Boolean,
    val freeProvider: Boolean,
    val roleAddress: Boolean,
    val qualityScore: Int?,
    val reasons: List<String>,
    val error: String? = null
)

object EmailDomainScanner {
    private const val DISPOSABLE_LIST_URL =
        "https://raw.githubusercontent.com/disposable-email-domains/disposable-email-domains/master/disposable_email_blocklist.conf"

    @Volatile
    private var cachedDisposableDomains: Set<String>? = null

    private val freeProviders = setOf(
        "gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "hotmail.co.uk",
        "live.com", "live.co.uk", "msn.com", "yahoo.com", "yahoo.co.uk", "ymail.com",
        "proton.me", "protonmail.com", "pm.me", "icloud.com", "me.com", "mac.com",
        "zoho.com", "aol.com", "gmx.com", "gmx.co.uk", "mail.com", "fastmail.com",
        "btinternet.com", "btopenworld.com", "talk21.com", "sky.com", "virginmedia.com",
        "blueyonder.co.uk", "ntlworld.com", "talktalk.net", "tiscali.co.uk", "plus.com"
    )

    private val disposableMarkers = setOf(
        "mailinator", "guerrillamail", "10minutemail", "temp-mail", "tempmail", "yopmail",
        "sharklasers", "maildrop", "trashmail", "getnada", "mohmal", "throwawaymail",
        "dispostable", "mailnesia", "fakeinbox", "mintemail", "mytemp.email", "burnermail",
        "33mail", "anonaddy", "addy.io", "simplelogin", "duck.com", "mozmail.com",
        "harakirimail", "spamgourmet", "crazymailing", "emailondeck", "tempmailo", "internxt"
    )

    private val rolePrefixes = setOf(
        "admin", "administrator", "support", "sales", "info", "contact", "security",
        "billing", "help", "noreply", "no-reply", "office", "enquiries", "inquiries",
        "team", "hr", "careers", "press", "media", "legal", "privacy", "abuse", "postmaster",
        "webmaster", "hostmaster", "accounts", "finance", "orders", "hello"
    )

    private fun isDisposableDomain(domain: String): Boolean {
        if (disposableMarkers.any { domain.contains(it) }) return true
        val blocklist = cachedDisposableDomains ?: run {
            val response = ScanHttp.get(DISPOSABLE_LIST_URL, timeoutMs = 8_000, maxBodyChars = 2_000_000)
            if (response.isOk && response.body.isNotBlank()) {
                response.body.lineSequence()
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toSet()
                    .also { cachedDisposableDomains = it }
            } else {
                emptySet()
            }
        }
        return domain in blocklist
    }

    suspend fun check(email: String): EmailDomainReport = withContext(Dispatchers.IO) {
        val clean = email.trim().lowercase()
        val at = clean.lastIndexOf('@')
        if (at <= 0 || at == clean.lastIndex) return@withContext error("Enter a valid email address")
        val local = clean.substring(0, at)
        val domain = clean.substring(at + 1)
        val free = domain in freeProviders
        val baseLocal = local.substringBefore('+')
        val role = baseLocal in rolePrefixes

        val disposableDeferred = async { isDisposableDomain(domain) }
        val mxDeferred = async { query(domain, "MX") }
        val txtDeferred = async { query(domain, "TXT") }
        val dmarcDeferred = async { query("_dmarc.$domain", "TXT") }
        val bimiDeferred = async { query("default._bimi.$domain", "TXT") }
        val gravatarDeferred = async { fetchGravatarSummary(clean) }

        val disposable = disposableDeferred.await()
        val mx = mxDeferred.await()
        val txt = txtDeferred.await()
        val dmarcTxt = dmarcDeferred.await()
        val bimiTxt = bimiDeferred.await()
        val gravatarInfo = gravatarDeferred.await()

        if (mx == null && txt == null && dmarcTxt == null) {
            return@withContext EmailDomainReport(
                domain, false, null, null, null, null,
                disposable, free, role, null, emptyList(), "DNS lookup unavailable"
            )
        }

        val mxHosts = mx.orEmpty().mapNotNull { line ->
            line.trim().split(Regex("\\s+")).lastOrNull { it.contains('.') }?.trimEnd('.')?.lowercase()
        }
        val accepts = mx?.isNotEmpty()
        val spfRecord = txt?.firstOrNull { it.contains("v=spf1", true) }
        val spf = spfRecord != null
        val dmarcRecord = dmarcTxt?.firstOrNull { it.contains("v=dmarc1", true) }
        val dmarc = dmarcRecord != null
        val bimi = bimiTxt?.any { it.contains("v=BIMI1", true) } == true

        val rawMxHost = mxHosts.firstOrNull()
        val provider = classifyProvider(rawMxHost, spfRecord)

        val reasons = mutableListOf<String>()
        var score = 50

        if (accepts == true) {
            score += 20
            reasons += "Domain publishes ${mxHosts.size} MX record(s) (${provider ?: rawMxHost ?: "configured"})"
        } else if (accepts == false) {
            score -= 25
            reasons += "No MX record was returned — domain may not accept incoming mail"
        }

        if (spf) {
            score += 10
            val strictSpf = spfRecord?.contains("-all") == true
            reasons += if (strictSpf) "Domain publishes strict SPF (-all)" else "Domain publishes SPF"
        }

        if (dmarc) {
            score += 10
            val policy = Regex("""p\s*=\s*(reject|quarantine|none)""", RegexOption.IGNORE_CASE)
                .find(dmarcRecord.orEmpty())?.groupValues?.get(1)?.lowercase()
            if (policy == "reject") score += 5
            reasons += if (policy != null) "Domain publishes DMARC (policy: $policy)" else "Domain publishes DMARC"
        }

        if (bimi) {
            score += 5
            reasons += "Domain publishes a verified BIMI brand indicator record"
        }

        if ('+' in local) {
            reasons += "Address uses sub-addressing (+tag); canonical mailbox is $baseLocal@$domain"
        } else if ((domain == "gmail.com" || domain == "googlemail.com") && '.' in local) {
            reasons += "Gmail ignores dots in usernames; canonical mailbox is ${local.replace(".", "")}@gmail.com"
        }

        if (gravatarInfo != null) {
            score += 10
            reasons += "Public Gravatar identity linked: $gravatarInfo"
        }

        if (disposable) {
            score -= 35
            reasons += "Domain matches the disposable/burner email blocklist"
        }
        if (free) reasons += "Free/consumer mail provider domain"
        if (role) {
            score -= 5
            reasons += "Role-style mailbox name ($baseLocal@)"
        }

        EmailDomainReport(
            domain = domain,
            checked = true,
            acceptsMail = accepts,
            mxProvider = provider ?: rawMxHost,
            spf = spf,
            dmarc = dmarc,
            disposable = disposable,
            freeProvider = free,
            roleAddress = role,
            qualityScore = score.coerceIn(0, 100),
            reasons = reasons
        )
    }

    private fun classifyProvider(mxHost: String?, spf: String?): String? {
        val h = mxHost?.lowercase().orEmpty()
        val s = spf?.lowercase().orEmpty()
        return when {
            h.isEmpty() -> null
            "google.com" in h || "googlemail.com" in h -> "Google Workspace / Gmail ($h)"
            "protection.outlook.com" in h || "olc.protection.outlook.com" in h -> "Microsoft 365 / Outlook ($h)"
            "protonmail.ch" in h || "proton.me" in h -> "Proton Mail ($h)"
            "icloud.com" in h -> "Apple iCloud Mail ($h)"
            "yahoodns.net" in h -> "Yahoo / AOL Mail ($h)"
            "pphosted.com" in h -> "Proofpoint Secure Email Gateway ($h)"
            "mimecast.com" in h -> "Mimecast Secure Email Gateway ($h)"
            "cloudflare.net" in h -> "Cloudflare Email Routing ($h)"
            "zoho.com" in h || "zoho.eu" in h -> "Zoho Mail ($h)"
            "messagingengine.com" in h -> "Fastmail ($h)"
            "secureserver.net" in h -> "GoDaddy Email ($h)"
            "amazonaws.com" in h -> "Amazon SES / WorkMail ($h)"
            "btinternet.com" in h || "btopenworld.com" in h -> "BT Mail ($h)"
            "_spf.google.com" in s -> "Google Workspace ($h)"
            "spf.protection.outlook.com" in s -> "Microsoft 365 ($h)"
            else -> h
        }
    }

    internal fun fetchGravatarSummary(email: String): String? {
        val md5 = OsintDatasets.transformAccount(email, AccountMode.HASH_MD5)
        val response = ScanHttp.get("https://en.gravatar.com/$md5.json", timeoutMs = 8_000)
        if (!response.isOk || response.body.isBlank()) return null
        return runCatching {
            val entry = JSONObject(response.body).optJSONArray("entry")?.optJSONObject(0) ?: return null
            val displayName = entry.optString("displayName").takeIf { it.isNotBlank() }
            val username = entry.optString("preferredUsername").takeIf { it.isNotBlank() }
            val location = entry.optString("currentLocation").takeIf { it.isNotBlank() }
            val accounts = entry.optJSONArray("accounts")
            val verifiedLinks = mutableListOf<String>()
            if (accounts != null) {
                for (i in 0 until accounts.length()) {
                    val acc = accounts.optJSONObject(i) ?: continue
                    val shortname = acc.optString("shortname").takeIf { it.isNotBlank() }
                    val url = acc.optString("url").takeIf { it.isNotBlank() }
                    if (shortname != null && url != null) verifiedLinks += "$shortname ($url)"
                }
            }
            buildList {
                displayName?.let { add("Name: $it") }
                username?.let { add("Handle: @$it") }
                location?.let { add("Location: $it") }
                if (verifiedLinks.isNotEmpty()) add("Linked: ${verifiedLinks.joinToString(", ")}")
            }.joinToString(" • ").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun query(name: String, type: String): List<String>? {
        val encoded = ScanHttp.encode(name)
        val endpoints = listOf(
            "https://cloudflare-dns.com/dns-query?name=$encoded&type=$type",
            "https://dns.google/resolve?name=$encoded&type=$type"
        )
        for (url in endpoints) {
            val response = ScanHttp.get(url, timeoutMs = 8_000, headers = mapOf("Accept" to "application/dns-json"))
            if (!response.isOk || response.body.isBlank()) continue
            val parsed = runCatching {
                val answers = JSONObject(response.body).optJSONArray("Answer") ?: return@runCatching emptyList()
                (0 until answers.length()).mapNotNull {
                    answers.optJSONObject(it)?.optString("data")?.trim('"')?.takeIf { v -> v.isNotBlank() }
                }
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun error(message: String) =
        EmailDomainReport("", false, null, null, null, null, false, false, false, null, emptyList(), message)
}
