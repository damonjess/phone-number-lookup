package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Built-in versions of two PhoneInfoga scanners, used when no PhoneInfoga server is reachable:
 *
 *  - googlesearch: Google dork links for the number (see [PhoneDorks])
 *  - ovh: asks the OVH Telecom API whether the number sits in an OVH range (OVH is a VoIP provider),
 *    using the same matching rule as PhoneInfoga: first six digits of the national number + "xxxx"
 *
 * PhoneInfoga's own "local" scanner is already covered by the number breakdown on the screen, and
 * Numverify has its own card.
 */
object PhoneInfogaBuiltIn {

    const val LABEL = "built-in"

    /** Country codes OVH's API covers: FR, BE, UK, ES, CH. */
    private val OVH_COUNTRY_CODES = setOf(33, 32, 44, 34, 41)

    private const val OVH_DESCRIPTION =
        "Checks whether the number is in an OVH Telecom range (OVH is a VoIP provider). " +
            "Downloads OVH's range list for the country, which can be a few MB."

    private const val DORK_DESCRIPTION =
        "Google searches for this number. They open in your browser; nothing is sent to Google until you tap one. " +
            "Many will find nothing, which is normal because most sites don't publish phone numbers."

    private const val LINKS_DESCRIPTION =
        "Opens a service's own page for this number. WhatsApp shows whether the number has an account, " +
            "and tellows shows its rating and community reports. Nothing is sent until you tap."

    private const val LINKS_NOTE =
        "Facebook, Instagram, X, LinkedIn and TikTok have no public link that goes from a phone number to a profile, " +
            "so they can't be offered here."

    // -------------------------------------------------------------- direct links

    internal fun linksScan(e164: String): PhoneInfogaScan {
        val links = PhoneLinks.generate(e164)
        return PhoneInfogaScan(
            scanner = "profile-links",
            description = LINKS_DESCRIPTION,
            status = if (links.isEmpty()) PhoneInfogaStatus.SKIPPED else PhoneInfogaStatus.OK,
            message = if (links.isEmpty()) "The number is too short to build links." else LINKS_NOTE,
            links = links.map { it.url },
            labeledLinks = links.map { it.label to it.url },
        )
    }

    // --------------------------------------------------------------- googlesearch

    internal fun dorkScan(number: DorkNumber): PhoneInfogaScan {
        val groups = PhoneDorks.generate(number)
        val labeled = groups.flatMap { group -> group.dorks.map { "${group.title}: ${it.label}" to it.url } }
        return PhoneInfogaScan(
            scanner = "googlesearch",
            description = DORK_DESCRIPTION,
            status = PhoneInfogaStatus.OK,
            rows = groups.map { it.title to "${it.dorks.size} ${if (it.dorks.size == 1) "search" else "searches"}" },
            links = labeled.map { it.second },
            labeledLinks = labeled,
        )
    }

    // ------------------------------------------------------------------------ ovh

    /** The range label OVH would use for this number, or null if it's too short to look up. */
    internal fun askedRange(rawLocal: String): String? =
        if (rawLocal.length > 6) rawLocal.substring(0, 6) + "xxxx" else null

    internal fun ovhMatch(zones: List<*>, rawLocal: String): Map<*, *>? {
        val asked = askedRange(rawLocal) ?: return null
        return zones.firstNotNullOfOrNull { (it as? Map<*, *>)?.takeIf { zone -> zone["number"] == asked } }
    }

    internal fun interpretOvh(code: Int, body: String, rawLocal: String): PhoneInfogaScan {
        fun failure(message: String) = PhoneInfogaScan("ovh", OVH_DESCRIPTION, PhoneInfogaStatus.ERROR, message)

        if (code == -1) return failure("Couldn't reach the OVH API.")
        val tree = JsonTree.parse(body)
        if (code >= 400) {
            return failure(((tree as? Map<*, *>)?.get("message") as? String) ?: "OVH answered HTTP $code.")
        }
        val zones = tree as? List<*> ?: return failure("OVH's answer wasn't in the expected format.")
        if (askedRange(rawLocal) == null) {
            return PhoneInfogaScan("ovh", OVH_DESCRIPTION, PhoneInfogaStatus.SKIPPED, "The number is too short to look up a range.")
        }

        val match = ovhMatch(zones, rawLocal)
        return if (match != null) {
            PhoneInfogaScan(
                scanner = "ovh",
                description = OVH_DESCRIPTION,
                status = PhoneInfogaStatus.OK,
                message = "This number is in an OVH Telecom range. OVH is a VoIP provider, so the caller may not be where the number suggests.",
                rows = listOfNotNull(
                    "Found" to "yes",
                    (match["number"] as? String)?.let { "Number range" to it },
                    (match["city"] as? String)?.takeIf { it.isNotBlank() }?.let { "City" to it },
                    (match["zipCode"] as? String)?.takeIf { it.isNotBlank() }?.let { "Zip code" to it },
                ),
            )
        } else {
            PhoneInfogaScan(
                scanner = "ovh",
                description = OVH_DESCRIPTION,
                status = PhoneInfogaStatus.OK,
                message = "Not in an OVH Telecom range. This only covers OVH, so other VoIP providers aren't detected.",
                rows = listOf("Found" to "no"),
            )
        }
    }

    // ------------------------------------------------------------------------ run

    internal suspend fun runWith(
        number: DorkNumber,
        region: String?,
        countryCode: Int,
        fetch: (String) -> HttpResponse,
    ): PhoneInfogaReport {
        val ovh = when {
            countryCode !in OVH_COUNTRY_CODES -> PhoneInfogaScan(
                "ovh", OVH_DESCRIPTION, PhoneInfogaStatus.SKIPPED, "OVH's API doesn't cover +$countryCode numbers."
            )
            region.isNullOrBlank() -> PhoneInfogaScan(
                "ovh", OVH_DESCRIPTION, PhoneInfogaStatus.SKIPPED, "Couldn't work out which country this number is in."
            )
            else -> {
                val response = fetch("https://api.ovh.com/1.0/telephony/number/detailedZones?country=${region.lowercase()}")
                interpretOvh(response.code, response.body, number.rawLocal)
            }
        }
        return PhoneInfogaReport(
            baseUrl = LABEL,
            scans = listOf(linksScan(number.e164), ovh, dorkScan(number)),
            note = "Built-in scanners (no server needed): direct links, an OVH range check and Google searches.",
        )
    }

    /** Runs the built-in scanners for a normalised number (E.164 or a UK local number). */
    suspend fun run(normalized: String): PhoneInfogaReport = withContext(Dispatchers.IO) {
        val parsed = DorkNumbers.from(normalized)
            ?: return@withContext PhoneInfogaReport(
                LABEL, emptyList(), "That doesn't look like a phone number the built-in scanners can use."
            )
        runWith(parsed.number, parsed.region, parsed.countryCode) { url ->
            ScanHttp.get(url, timeoutMs = 25_000, maxBodyChars = 16_000_000)
        }
    }
}
