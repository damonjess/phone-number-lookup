package com.example.phone_number_lookup

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

data class OsintChannelRow(
    val email: String,
    val channel: String,
    val category: String,
    val found: Boolean = true,
    /** "email_account" (direct email check) or "handle_presence" (verified handle follow-up) */
    val type: String,
    val username: String? = null,
    val profileUrl: String? = null,
    val handleOrigin: String? = null,
    val emailLinkVerified: Boolean = true
)

data class ApifyStyleSummary(
    val email: String,
    val accountsFound: Int,
    val accountsChecked: Int,
    val accountsUnresolved: Int,
    val isActiveEmail: Boolean,
    /** Letter grade A (lowest risk) through F (critical compromise/fraud risk) */
    val riskGrade: String,
    val breached: Boolean,
    val breachCount: Int,
    val breachRiskLabel: String,
    val breachRiskScore: Int,
    val channelRows: List<OsintChannelRow>,
    val unresolvedChannels: List<String>
)

object ApifyStyleEmailIntel {

    /**
     * Builds the structured `FULL_RESULTS` summary, A-F risk grade, and provenance-tagged channel rows.
     */
    fun buildSummary(
        email: String,
        registrations: List<RegistrationHit>,
        registrationChecked: Int,
        registrationIssues: List<SiteIssue>,
        handleHits: List<UsernameHit>,
        attribution: EmailAttributionReport?,
        breachReport: BreachReport?,
        domainReport: EmailDomainReport?,
        enrichedProfiles: List<EnrichedProfileCard> = emptyList()
    ): ApifyStyleSummary {
        val cleanEmail = email.trim().lowercase()
        val rows = LinkedHashMap<String, OsintChannelRow>()

        // Map of handle -> provenance origin (e.g. "GitHub", "Gravatar", "Keybase")
        val handleOrigins = LinkedHashMap<String, String>()
        attribution?.usernamesFound?.forEach { uv ->
            val cleanH = uv.value.removePrefix("@").trim().lowercase()
            if (cleanH.isNotBlank() && !handleOrigins.containsKey(cleanH)) {
                handleOrigins[cleanH] = uv.source
            }
        }
        val localPart = cleanEmail.substringBefore('@')
        handleOrigins.putIfAbsent(localPart, "Email local-part ($localPart@)")

        // 1. Direct email account findings (including Curated probes AND Holehe/Blackbird upstream matches)
        registrations
            .filter {
                it.verificationStatus.contains("Registered", ignoreCase = true) ||
                    it.verificationStatus.contains("Upstream match", ignoreCase = true) ||
                    it.verificationStatus.contains("Matched", ignoreCase = true)
            }
            .forEach { reg ->
                val key = reg.site.lowercase().trim()
                val url = OsintDatasets.cleanProfileUrl(reg.site, reg.profileUrl)
                    .takeIf { it.startsWith("http") }
                rows[key] = OsintChannelRow(
                    email = cleanEmail,
                    channel = reg.site,
                    category = reg.category.ifBlank { "service" },
                    found = true,
                    type = "email_account",
                    username = null,
                    profileUrl = url,
                    handleOrigin = if (reg.source.isNotBlank()) "Direct email probe (${reg.source})" else "Direct email probe",
                    emailLinkVerified = true
                )
            }

        // 1B. Promote known service breaches (e.g. MyFitnessPal, Strava, Canva, Adobe, Facebook) into Registrations
        breachReport?.details?.forEach { breach ->
            val bName = breach.name.trim()
            val key = bName.lowercase()
            val isGenericDump = key.contains("comb") ||
                key.contains("naz") ||
                key.contains("alien") ||
                key.contains("stealer") ||
                key.contains("collection") ||
                key.contains("pastebin")
            if (bName.isNotBlank() && !isGenericDump && !rows.containsKey(key)) {
                rows[key] = OsintChannelRow(
                    email = cleanEmail,
                    channel = bName,
                    category = "breach-confirmed account",
                    found = true,
                    type = "email_account",
                    username = null,
                    profileUrl = breach.domain?.takeIf { it.isNotBlank() }?.let { "https://$it" },
                    handleOrigin = "Confirmed via historical breach record (${breach.breachDate ?: "verified"})",
                    emailLinkVerified = true
                )
            }
        }

        // 2. Attribution direct profile links (Gravatar, GitHub, Keybase, Duolingo, Google, Facebook, Instagram, LinkedIn)
        attribution?.profileLinks?.forEach { link ->
            val key = link.platform.lowercase().trim()
            val existing = rows[key]
            val cleanUrl = OsintDatasets.cleanProfileUrl(link.platform, link.url)
            val isDirectEmail = link.platform.contains("Gravatar", true) ||
                link.platform.contains("GitHub", true) ||
                link.platform.contains("Duolingo", true) ||
                link.platform.contains("Keybase", true) ||
                link.platform.contains("Google", true) ||
                link.platform.contains("Facebook", true) ||
                link.platform.contains("Instagram", true) ||
                link.platform.contains("LinkedIn", true)

            if (existing == null || existing.username == null) {
                rows[key] = OsintChannelRow(
                    email = cleanEmail,
                    channel = link.platform,
                    category = "identity",
                    found = true,
                    type = if (isDirectEmail) "email_account" else "handle_presence",
                    username = link.handle.takeIf { it.isNotBlank() && !it.contains(' ') },
                    profileUrl = cleanUrl.takeIf { it.startsWith("http") },
                    handleOrigin = if (isDirectEmail) "Direct email identity resolution"
                    else "Handle follow-up (${handleOrigins[link.handle.removePrefix("@").lowercase()] ?: "verified handle"})",
                    emailLinkVerified = isDirectEmail
                )
            }
        }

        // 3. Enriched profile dossiers
        enrichedProfiles.forEach { card ->
            val key = card.platform.lowercase().trim()
            if (!rows.containsKey(key)) {
                val origin = handleOrigins[card.handle.removePrefix("@").lowercase()] ?: "Handle follow-up"
                rows[key] = OsintChannelRow(
                    email = cleanEmail,
                    channel = card.platform,
                    category = "profile",
                    found = true,
                    type = "handle_presence",
                    username = card.handle,
                    profileUrl = OsintDatasets.cleanProfileUrl(card.platform, card.profileUrl),
                    handleOrigin = "Via @${card.handle} ($origin)",
                    emailLinkVerified = false
                )
            }
        }

        // 4. Handle presence candidates (_type = "handle_presence", email_link_verified = false)
        handleHits
            .filter { it.verificationStatus == "Matched" }
            .forEach { hit ->
                val key = hit.site.lowercase().trim()
                if (!rows.containsKey(key)) {
                    val queried = hit.queriedName.ifBlank { localPart }
                    val origin = handleOrigins[queried.lowercase()] ?: "Email local-part"
                    rows[key] = OsintChannelRow(
                        email = cleanEmail,
                        channel = hit.site,
                        category = hit.category.ifBlank { "social" },
                        found = true,
                        type = "handle_presence",
                        username = queried,
                        profileUrl = OsintDatasets.cleanProfileUrl(hit.site, hit.profileUrl),
                        handleOrigin = "Via @$queried ($origin)",
                        emailLinkVerified = false
                    )
                }
            }

        // Sort: Direct email findings first, then rows with username/profileUrl, then alphabetical
        val sortedRows = rows.values.sortedWith(
            compareByDescending<OsintChannelRow> { it.emailLinkVerified }
                .thenByDescending { it.username != null && it.profileUrl != null }
                .thenBy { it.channel.lowercase() }
        )

        val unresolved = registrationIssues.map { it.site }.distinct().sorted()
        val breachCount = breachReport?.breachNames.orEmpty().distinct().size
        val hasStealer = attribution?.stealerProfile != null
        val acceptsMail = domainReport?.acceptsMail == true
        val disposable = domainReport?.disposable == true

        val isActive = acceptsMail || sortedRows.isNotEmpty() || breachCount > 0

        // Calculate 0..100 Breach & Exposure Risk Score
        var breachScore = 0
        if (breachCount > 0) breachScore += (breachCount * 14).coerceAtMost(55)
        if (hasStealer) breachScore += 35
        val exposedSensitiveClasses = breachReport?.details.orEmpty()
            .flatMap { it.dataClasses }
            .any {
                it.contains("password", true) ||
                    it.contains("phone", true) ||
                    it.contains("address", true) ||
                    it.contains("date of birth", true)
            }
        if (exposedSensitiveClasses) breachScore += 10
        if (disposable) breachScore += 25
        breachScore = breachScore.coerceIn(0, 100)

        val breachLabel = when {
            breachScore >= 70 -> "Critical"
            breachScore >= 40 -> "High"
            breachScore >= 15 -> "Moderate"
            else -> "Low"
        }

        // Calculate A-F Letter Grade
        val grade = when {
            hasStealer || breachScore >= 80 || (disposable && sortedRows.isEmpty()) -> "F"
            breachScore >= 60 -> "D"
            breachScore >= 35 || disposable -> "C"
            breachScore >= 15 || sortedRows.isEmpty() -> "B"
            else -> "A"
        }

        val totalChecked = maxOf(registrationChecked, sortedRows.size + unresolved.size)

        return ApifyStyleSummary(
            email = cleanEmail,
            accountsFound = sortedRows.size,
            accountsChecked = totalChecked,
            accountsUnresolved = unresolved.size,
            isActiveEmail = isActive,
            riskGrade = grade,
            breached = breachCount > 0 || hasStealer,
            breachCount = breachCount,
            breachRiskLabel = breachLabel,
            breachRiskScore = breachScore,
            channelRows = sortedRows,
            unresolvedChannels = unresolved
        )
    }

    /**
     * Exports the flat CSV table (`email,channel,found,type,handle_origin,username,profile_url`).
     */
    fun toCsv(summary: ApifyStyleSummary): String = buildString {
        appendLine("email,channel,category,found,type,email_link_verified,handle_origin,username,profile_url")
        summary.channelRows.forEach { row ->
            appendLine(
                listOf(
                    csvEscape(row.email),
                    csvEscape(row.channel),
                    csvEscape(row.category),
                    row.found.toString(),
                    csvEscape(row.type),
                    row.emailLinkVerified.toString(),
                    csvEscape(row.handleOrigin.orEmpty()),
                    csvEscape(row.username.orEmpty()),
                    csvEscape(row.profileUrl.orEmpty())
                ).joinToString(",")
            )
        }
    }

    /**
     * Exports the `FULL_RESULTS` JSON document matching the Apify schema.
     */
    fun toFullResultsJson(
        summary: ApifyStyleSummary,
        attribution: EmailAttributionReport?,
        breachReport: BreachReport?,
        domainReport: EmailDomainReport?
    ): String {
        val root = JSONObject()
        root.put("email", summary.email)
        root.put(
            "summary",
            JSONObject().apply {
                put("accounts_found", summary.accountsFound)
                put("accounts_checked", summary.accountsChecked)
                put("accounts_unresolved", summary.accountsUnresolved)
                put("is_active_email", summary.isActiveEmail)
                put("risk_score", summary.riskGrade)
            }
        )
        root.put(
            "breach_info",
            JSONObject().apply {
                put("breached", summary.breached)
                put("breach_count", summary.breachCount)
                put("risk_label", summary.breachRiskLabel)
                put("risk_score", summary.breachRiskScore)
                put("breaches", JSONArray(breachReport?.breachNames.orEmpty()))
            }
        )
        domainReport?.let { dom ->
            root.put(
                "email_analysis",
                JSONObject().apply {
                    put("domain", dom.domain)
                    put("accepts_mail", dom.acceptsMail)
                    put("mx_provider", dom.mxProvider)
                    put("is_disposable", dom.disposable)
                    put("is_free_provider", dom.freeProvider)
                    put("is_role_account", dom.roleAddress)
                    put("quality_score", dom.qualityScore)
                }
            )
        }
        attribution?.let { att ->
            root.put(
                "identity",
                JSONObject().apply {
                    put("names", JSONArray(att.namesFound.map { "${it.value} (${it.source})" }))
                    put("usernames", JSONArray(att.usernamesFound.map { "${it.value} (${it.source})" }))
                    put("locations", JSONArray(att.locationsFound.map { "${it.value} (${it.source})" }))
                    put("phones", JSONArray(att.phonesFound.map { "${it.value} (${it.source})" }))
                }
            )
        }
        val channelsArr = JSONArray()
        summary.channelRows.forEach { row ->
            channelsArr.put(
                JSONObject().apply {
                    put("channel", row.channel)
                    put("category", row.category)
                    put("found", row.found)
                    put("_type", row.type)
                    put("email_link_verified", row.emailLinkVerified)
                    put("handle_origin", row.handleOrigin)
                    put("username", row.username ?: JSONObject.NULL)
                    put("profile_url", row.profileUrl ?: JSONObject.NULL)
                }
            )
        }
        root.put("channels_found", channelsArr)
        root.put("accounts_unknown", JSONArray(summary.unresolvedChannels))
        return root.toString(2)
    }

    fun shareText(context: Context, subject: String, body: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        val chooser = Intent.createChooser(intent, subject)
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private fun csvEscape(value: String): String {
        if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            return "\"${value.replace("\"", "\"\"")}\""
        }
        return value
    }
}
