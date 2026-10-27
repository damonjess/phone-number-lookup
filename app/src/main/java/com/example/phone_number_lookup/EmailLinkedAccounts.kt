package com.example.phone_number_lookup

/**
 * One service the email was found on, in the shape a reverse-email report shows it: a service
 * name, its domain and the facts that were actually returned for it.
 *
 * Built only from the app's own free scans (registration probes, the embedded holehe/Blackbird
 * cores and the public attribution endpoints) — no value is inferred from the address itself.
 */
data class EmailLinkedAccount(
    val name: String,
    val domain: String? = null,
    val fields: List<Pair<String, String>> = emptyList(),
    val url: String? = null,
    /** True when a service-specific check answered that the account exists. */
    val confirmed: Boolean = false
)

object EmailLinkedAccounts {

    fun build(
        email: String,
        registrations: List<RegistrationHit>,
        handleHits: List<UsernameHit>,
        attribution: EmailAttributionReport?
    ): List<EmailLinkedAccount> {
        val accounts = LinkedHashMap<String, EmailLinkedAccount>()

        registrations
            .distinctBy { it.site.lowercase().trim() }
            .forEach { hit ->
                val url = hit.profileUrl.takeIf { it.startsWith("http", ignoreCase = true) }
                val fields = buildList {
                    add("Email" to email)
                    add("Status" to hit.verificationStatus)
                    add("Source" to hit.source)
                    if (hit.evidence.isNotBlank() && !hit.evidence.startsWith("Matched a site-specific positive marker")) {
                        add("Evidence" to hit.evidence)
                    }
                    add("Confidence" to hit.confidence)
                }
                val key = key(hit.site)
                accounts[key] = EmailLinkedAccount(
                    name = display(hit.site),
                    domain = host(url),
                    fields = fields,
                    url = url,
                    confirmed = hit.verificationStatus == "Registered response"
                )
            }

        handleHits
            .distinctBy { it.site.lowercase().trim() }
            .forEach { hit ->
                val key = key(hit.site)
                if (accounts.containsKey(key)) return@forEach
                val url = hit.profileUrl.takeIf { it.startsWith("http", ignoreCase = true) }
                accounts[key] = EmailLinkedAccount(
                    name = display(hit.site),
                    domain = host(url),
                    fields = buildList {
                        add("Handle" to hit.queriedName.ifBlank { handle(email) })
                        add("Status" to hit.verificationStatus)
                        add("Source" to hit.source)
                        if (hit.category.isNotBlank()) add("Category" to hit.category)
                        add("Confidence" to hit.confidence)
                    },
                    url = url,
                    confirmed = hit.verificationStatus == "Matched"
                )
            }

        attribution?.profileLinks
            .orEmpty()
            .distinctBy { it.url.lowercase() }
            .forEach { link ->
                val key = key(link.platform)
                if (accounts.containsKey(key)) return@forEach
                accounts[key] = EmailLinkedAccount(
                    name = display(link.platform),
                    domain = host(link.url),
                    fields = buildList {
                        add("Handle" to link.handle)
                        add("Type" to "Public profile link")
                    },
                    url = link.url.takeIf { it.startsWith("http", ignoreCase = true) },
                    confirmed = false
                )
            }

        return accounts.values.sortedWith(
            compareByDescending<EmailLinkedAccount> { it.confirmed }.thenBy { it.name.lowercase() }
        )
    }

    fun handle(email: String): String = email.substringBefore('@').trim()

    private fun key(site: String): String = site.lowercase().trim()

    /**
     * Service names arrive as lowercase identifiers ("eventbrite") or bare hosts
     * ("my.flightradar24.com"); reports show the brand, so reduce hosts to their main label.
     */
    private fun display(raw: String): String {
        val value = raw.trim().ifBlank { return "Unknown service" }
        val label = if ('.' in value) {
            value.split('.')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .let { parts ->
                    if (parts.size >= 2) parts[parts.size - 2] else parts.firstOrNull().orEmpty()
                }
        } else {
            value
        }
        val cleaned = label.ifBlank { value }
        return cleaned.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    /** Host of a profile URL, without the leading "www.". */
    private fun host(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            java.net.URI(url).host?.removePrefix("www.")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
