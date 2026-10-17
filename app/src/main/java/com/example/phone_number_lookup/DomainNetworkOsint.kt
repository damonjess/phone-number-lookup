package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ShodanHostReport(
    val ip: String,
    val ports: List<Int>,
    val hostnames: List<String>,
    val cpes: List<String>,
    val vulns: List<String>,
    val tags: List<String>
)

data class RdapDomainSummary(
    val handle: String?,
    val registrar: String?,
    val registeredDate: String?,
    val expirationDate: String?,
    val status: List<String>,
    val nameservers: List<String>
)

data class DomainOsintReport(
    val domain: String,
    val aRecords: List<String> = emptyList(),
    val aaaaRecords: List<String> = emptyList(),
    val mxRecords: List<String> = emptyList(),
    val nsRecords: List<String> = emptyList(),
    val txtRecords: List<String> = emptyList(),
    val rdap: RdapDomainSummary? = null,
    val subdomains: List<String> = emptyList(),
    val shodanHost: ShodanHostReport? = null,
    val wayback: WaybackSummary? = null,
    val error: String? = null
)

object DomainNetworkOsint {

    fun cleanDomain(raw: String): String {
        var d = raw.trim().lowercase()
        if ("@" in d) d = d.substringAfterLast('@')
        d = d.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        d = d.substringBefore('/').substringBefore('?').substringBefore(':')
        return d.trim('.')
    }

    suspend fun scan(rawInput: String): DomainOsintReport = withContext(Dispatchers.IO) {
        val domain = cleanDomain(rawInput)
        if (domain.isBlank() || '.' !in domain) {
            return@withContext DomainOsintReport(domain, error = "Enter a valid domain name (e.g. example.com)")
        }

        val aDef = async { queryDns(domain, "A") }
        val aaaaDef = async { queryDns(domain, "AAAA") }
        val mxDef = async { queryDns(domain, "MX") }
        val nsDef = async { queryDns(domain, "NS") }
        val txtDef = async { queryDns(domain, "TXT") }
        val rdapDef = async { fetchRdap(domain) }
        val crtDef = async { fetchCrtSubdomains(domain) }
        val waybackDef = async { IdentityEnricher.queryWaybackCdx(domain) }

        val aRecords = aDef.await()
        val primaryIp = aRecords.firstOrNull { it.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) }
        val shodanDef = async { primaryIp?.let { fetchShodanInternetDb(it) } }

        DomainOsintReport(
            domain = domain,
            aRecords = aRecords,
            aaaaRecords = aaaaDef.await(),
            mxRecords = mxDef.await(),
            nsRecords = nsDef.await(),
            txtRecords = txtDef.await(),
            rdap = rdapDef.await(),
            subdomains = crtDef.await(),
            shodanHost = shodanDef.await(),
            wayback = waybackDef.await()
        )
    }

    private fun queryDns(domain: String, type: String): List<String> {
        val url = "https://cloudflare-dns.com/dns-query?name=${ScanHttp.encode(domain)}&type=$type"
        val response = ScanHttp.get(url, timeoutMs = 8_000, headers = mapOf("Accept" to "application/dns-json"))
        if (!response.isOk || response.body.isBlank()) return emptyList()
        return runCatching {
            val answers = JSONObject(response.body).optJSONArray("Answer") ?: return emptyList()
            (0 until answers.length()).mapNotNull { i ->
                answers.optJSONObject(i)?.optString("data")?.trim('"', '.')?.takeIf { it.isNotBlank() }
            }.distinct()
        }.getOrDefault(emptyList())
    }

    private fun fetchRdap(domain: String): RdapDomainSummary? {
        val response = ScanHttp.get(
            "https://rdap.org/domain/${ScanHttp.encode(domain)}",
            timeoutMs = 12_000,
            headers = mapOf("Accept" to "application/rdap+json,application/json")
        )
        if (!response.isOk || response.body.isBlank()) return null

        return runCatching {
            val root = JSONObject(response.body)
            val handle = root.optString("handle").takeIf { it.isNotBlank() }

            var registered: String? = null
            var expiration: String? = null
            val events = root.optJSONArray("events")
            if (events != null) {
                for (i in 0 until events.length()) {
                    val ev = events.optJSONObject(i) ?: continue
                    val action = ev.optString("eventAction").lowercase()
                    val date = ev.optString("eventDate").substringBefore('T').takeIf { it.isNotBlank() } ?: continue
                    if (action == "registration") registered = date
                    if (action == "expiration") expiration = date
                }
            }

            var registrar: String? = null
            val entities = root.optJSONArray("entities")
            if (entities != null) {
                for (i in 0 until entities.length()) {
                    val ent = entities.optJSONObject(i) ?: continue
                    val roles = ent.optJSONArray("roles")
                    val isRegistrar = roles != null && (0 until roles.length()).any {
                        roles.optString(it).equals("registrar", ignoreCase = true)
                    }
                    if (isRegistrar) {
                        val vcard = ent.optJSONArray("vcardArray")?.optJSONArray(1)
                        if (vcard != null) {
                            for (j in 0 until vcard.length()) {
                                val item = vcard.optJSONArray(j) ?: continue
                                if (item.optString(0) == "fn") {
                                    registrar = item.optString(3).takeIf { it.isNotBlank() }
                                }
                            }
                        }
                    }
                }
            }

            val statuses = mutableListOf<String>()
            val statusArr = root.optJSONArray("status")
            if (statusArr != null) {
                for (i in 0 until statusArr.length()) {
                    statusArr.optString(i).takeIf { it.isNotBlank() }?.let { statuses += it }
                }
            }

            val nsList = mutableListOf<String>()
            val nsArr = root.optJSONArray("nameservers")
            if (nsArr != null) {
                for (i in 0 until nsArr.length()) {
                    nsArr.optJSONObject(i)?.optString("ldhName")?.lowercase()?.takeIf { it.isNotBlank() }?.let {
                        nsList += it
                    }
                }
            }

            RdapDomainSummary(
                handle = handle,
                registrar = registrar,
                registeredDate = registered,
                expirationDate = expiration,
                status = statuses.distinct(),
                nameservers = nsList.distinct()
            )
        }.getOrNull()
    }

    private fun fetchCrtSubdomains(domain: String): List<String> {
        val url = "https://crt.sh/?q=${ScanHttp.encode("%.$domain")}&output=json"
        val response = ScanHttp.get(url, timeoutMs = 15_000, headers = mapOf("Accept" to "application/json"))
        if (!response.isOk || response.body.isBlank()) return emptyList()

        return runCatching {
            val arr = JSONArray(response.body)
            val subs = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val nameValue = obj.optString("name_value")
                for (line in nameValue.lineSequence()) {
                    val clean = line.trim().lowercase().removePrefix("*.")
                    if (clean.endsWith(domain) && clean != domain && !clean.contains(' ')) {
                        subs += clean
                    }
                }
                if (subs.size >= 40) break
            }
            subs.sorted()
        }.getOrDefault(emptyList())
    }

    private fun fetchShodanInternetDb(ip: String): ShodanHostReport? {
        val response = ScanHttp.get(
            "https://internetdb.shodan.io/$ip",
            timeoutMs = 10_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!response.isOk || response.body.isBlank()) return null

        return runCatching {
            val root = JSONObject(response.body)
            val ports = root.optJSONArray("ports").toIntList()
            val hostnames = root.optJSONArray("hostnames").toStringList()
            val cpes = root.optJSONArray("cpes").toStringList()
            val vulns = root.optJSONArray("vulns").toStringList()
            val tags = root.optJSONArray("tags").toStringList()
            ShodanHostReport(
                ip = root.optString("ip", ip),
                ports = ports,
                hostnames = hostnames,
                cpes = cpes,
                vulns = vulns,
                tags = tags
            )
        }.getOrNull()
    }

    private fun JSONArray?.toStringList(): List<String> {
        val arr = this ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    private fun JSONArray?.toIntList(): List<Int> {
        val arr = this ?: return emptyList()
        return (0 until arr.length()).mapNotNull {
            val v = arr.optInt(it, -1)
            if (v > 0) v else null
        }
    }
}
