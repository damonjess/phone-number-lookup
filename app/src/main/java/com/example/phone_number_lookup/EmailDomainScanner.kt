package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Public DNS-based email-domain checks; no IntelBase API or account required. */
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
    private val freeProviders = setOf("gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "live.com", "yahoo.com", "proton.me", "protonmail.com", "icloud.com", "zoho.com")
    private val disposableMarkers = setOf("mailinator", "guerrillamail", "10minutemail", "temp-mail", "yopmail", "sharklasers", "maildrop")
    private val rolePrefixes = setOf("admin", "support", "sales", "info", "contact", "security", "billing", "help", "noreply", "no-reply")

    suspend fun check(email: String): EmailDomainReport = withContext(Dispatchers.IO) {
        val clean = email.trim().lowercase()
        val at = clean.lastIndexOf('@')
        if (at <= 0 || at == clean.lastIndex) return@withContext error("Enter a valid email address")
        val local = clean.substring(0, at)
        val domain = clean.substring(at + 1)
        val free = domain in freeProviders
        val disposable = disposableMarkers.any { domain.contains(it) }
        val role = local in rolePrefixes

        val mx = query(domain, "MX")
        val txt = query(domain, "TXT")
        val dmarcTxt = query("_dmarc.$domain", "TXT")
        if (mx == null && txt == null && dmarcTxt == null) {
            return@withContext EmailDomainReport(domain, false, null, null, null, null, disposable, free, role, null, emptyList(), "DNS lookup unavailable")
        }
        val mxData = mx.orEmpty().joinToString(" ")
        val accepts = mx?.isNotEmpty()
        val spf = txt?.any { it.contains("v=spf1", true) }
        val dmarc = dmarcTxt?.any { it.contains("v=dmarc1", true) }
        val provider = mxData.split(Regex("\\s+")).firstOrNull { it.contains('.') }?.trimEnd('.')
        val reasons = mutableListOf<String>()
        var score = 50
        if (accepts == true) { score += 20; reasons += "Domain publishes an MX record" } else if (accepts == false) { score -= 20; reasons += "No MX record was returned" }
        if (spf == true) { score += 10; reasons += "Domain publishes SPF" }
        if (dmarc == true) { score += 10; reasons += "Domain publishes DMARC" }
        if (disposable) { score -= 35; reasons += "Domain resembles a disposable-mail provider" }
        if (free) reasons += "Free-mail provider domain"
        if (role) { score -= 5; reasons += "Role-style mailbox name" }
        EmailDomainReport(domain, true, accepts, provider, spf, dmarc, disposable, free, role, score.coerceIn(0, 100), reasons)
    }

    private fun query(name: String, type: String): List<String>? {
        val response = ScanHttp.get("https://cloudflare-dns.com/dns-query?name=${ScanHttp.encode(name)}&type=$type", timeoutMs = 10_000, headers = mapOf("Accept" to "application/dns-json"))
        if (!response.isOk) return null
        return runCatching {
            val answers = JSONObject(response.body).optJSONArray("Answer") ?: return@runCatching emptyList()
            (0 until answers.length()).mapNotNull { answers.optJSONObject(it)?.optString("data")?.takeIf { value -> value.isNotBlank() } }
        }.getOrNull()
    }

    private fun error(message: String) = EmailDomainReport("", false, null, null, null, null, false, false, false, null, emptyList(), message)
}
