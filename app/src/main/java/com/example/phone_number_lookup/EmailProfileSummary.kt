package com.example.phone_number_lookup

/**
 * A short, site-style profile summary built in the app from evidence the scans actually returned.
 *
 * Nothing here is synthesised from the email address itself: every bullet is a count or a value that
 * one of the sources below produced. It exists so the email tab can present the same shape of
 * report as a commercial email lookup without depending on a paid API.
 */
data class EmailProfileSummary(
    val headline: String,
    /** "High", "Moderate" or "Low". */
    val risk: String,
    val bullets: List<String>,
    val riskNote: String
)

object EmailProfileSummaryBuilder {

    /** Returns null when no source returned anything worth summarising. */
    fun email(
        email: String,
        attribution: EmailAttributionReport?,
        registrations: List<RegistrationHit>,
        handleHits: List<UsernameHit>,
        breachReport: BreachReport?
    ): EmailProfileSummary? {
        val confirmed = registrations.distinctBy { it.site.lowercase() }
        val names = attribution?.namesFound.orEmpty().distinctBy { it.value.lowercase() }
        val usernames = attribution?.usernamesFound.orEmpty().distinctBy { it.value.lowercase() }
        val locations = attribution?.locationsFound.orEmpty().distinctBy { it.value.lowercase() }
        val breaches = breachReport?.breachNames.orEmpty().distinct()
        val stealer = attribution?.stealerProfile

        if (confirmed.isEmpty() && names.isEmpty() && usernames.isEmpty() &&
            locations.isEmpty() && breaches.isEmpty() && stealer == null && handleHits.isEmpty()
        ) {
            return null
        }

        val risk = when {
            stealer != null || breaches.size >= 3 -> "High"
            breaches.isNotEmpty() || confirmed.size >= 3 || names.isNotEmpty() -> "Moderate"
            else -> "Low"
        }

        val bullets = buildList {
            if (confirmed.isNotEmpty()) {
                add("Accounts found on ${list(confirmed.map { it.site }, 6)}.")
            }
            if (handleHits.isNotEmpty()) {
                add("${handleHits.size} public profiles matched the handle “${handleOf(email)}”.")
            }
            if (names.isNotEmpty()) {
                add("Names returned by public sources: ${list(names.map { it.value }, 4)}${sourceSuffix(names.map { it.source })}")
            }
            if (usernames.isNotEmpty()) {
                add("Usernames returned: ${list(usernames.map { it.value }, 4)}${sourceSuffix(usernames.map { it.source })}")
            }
            if (locations.isNotEmpty()) {
                add("Locations published by public sources: ${list(locations.map { it.value }, 3)}.")
            }
            if (breaches.isNotEmpty()) {
                add(
                    if (breaches.size == 1) "A single breach record was returned: ${breaches.first()}."
                    else "${breaches.size} breach records were returned, including ${list(breaches, 3)}."
                )
            }
            stealer?.let { profile ->
                add(
                    buildString {
                        append("An infostealer log names this email")
                        profile.dateCompromised?.takeIf { it.isNotBlank() }?.let { append(" (captured $it)") }
                        profile.operatingSystem?.takeIf { it.isNotBlank() }?.let { append(" on $it") }
                        profile.computerUsername?.takeIf { it.isNotBlank() }?.let { append(" under the machine user “$it”") }
                        append(".")
                    }
                )
            }
            val first = attribution?.firstSeen
            val last = attribution?.lastSeen
            if (first != null || last != null) {
                add(
                    buildString {
                        append("Dated signals span ")
                        append(first ?: "unknown")
                        append(" to ")
                        append(last ?: "unknown")
                        append(" across the checked sources.")
                    }
                )
            }
        }

        return EmailProfileSummary(
            headline = headline(confirmed.size, breaches.size, stealer != null, risk),
            risk = risk,
            bullets = bullets,
            riskNote = riskNote(risk, stealer != null, breaches.size, confirmed.size)
        )
    }

    /** The email's local part, which is what the handle scans actually test. */
    fun handleOf(email: String): String = email.substringBefore('@').trim()

    private fun headline(accounts: Int, breaches: Int, stealer: Boolean, risk: String): String = buildString {
        append("$risk exposure")
        when {
            accounts > 0 && breaches > 0 -> append(": $accounts linked service${plural(accounts)} and $breaches breach record${plural(breaches)}")
            accounts > 0 -> append(": linked to $accounts service${plural(accounts)}")
            breaches > 0 -> append(": $breaches breach record${plural(breaches)} returned")
            else -> append(": no confirmed accounts yet")
        }
        if (stealer) append(", plus an infostealer capture")
    }

    private fun riskNote(risk: String, stealer: Boolean, breaches: Int, accounts: Int): String = when (risk) {
        "High" -> "Credential exposure was found for this address, so treat it as compromised: change the " +
            "password on the services listed below and turn on two-factor authentication. Full name, " +
            "address or photo values are only shown where a public source returned them."
        "Moderate" -> buildString {
            append(
                if (stealer) "Credentials tied to this address appear in an infostealer log. "
                else if (breaches > 0) "This address appears in breach records, which raises the chance of credential stuffing. "
                else "The address is registered on several services, which is worth noting but is not itself a compromise. "
            )
            append("Review the accounts and links below before reusing this address anywhere sensitive.")
            if (accounts == 0) append(" No account confirmations were returned by the checked sources.")
        }
        else -> "No breach or infostealer records were returned by the checked sources, and no name, " +
            "address or photo values came back. That is not proof the address is unexposed: sources " +
            "cover different breaches and can be incomplete or rate-limited."
    }

    private fun list(values: List<String>, limit: Int): String {
        val shown = values.take(limit)
        val extra = values.size - shown.size
        val joined = when (shown.size) {
            0 -> ""
            1 -> shown.first()
            2 -> "${shown[0]} and ${shown[1]}"
            else -> shown.dropLast(1).joinToString(", ") + " and " + shown.last()
        }
        return if (extra > 0) "$joined (and $extra more)" else joined
    }

    private fun sourceSuffix(sources: List<String>): String {
        val distinct = sources.filter { it.isNotBlank() }.distinct().take(3)
        return if (distinct.isEmpty()) "" else " (${distinct.joinToString(", ")})"
    }

    private fun plural(count: Int): String = if (count == 1) "" else "s"
}
