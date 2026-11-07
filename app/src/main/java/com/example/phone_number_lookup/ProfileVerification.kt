package com.example.phone_number_lookup

/**
 * Pre-check for site-specific username constraints (e.g. Strava disallowing dots,
 * Flightradar24 requiring 3-20 alphanumeric characters or underscores).
 */
fun isValidSiteUsername(siteName: String, username: String): Boolean {
    val lowerSite = siteName.lowercase()
    return when {
        // Strava does not allow dots; dots get parsed as file extensions (/athletes/user.ext)
        lowerSite.contains("strava") -> !username.contains(".") && !username.contains(" ")
        // Flightradar24 only allows 3-20 alphanumeric characters or underscores
        lowerSite.contains("flightradar") -> Regex("^[a-zA-Z0-9_]{3,20}$").matches(username)
        else -> true
    }
}

/**
 * Post-check for homepage redirects, soft-404 pages, login walls, and mismatched profiles.
 */
fun isFalsePositiveProfilePage(
    siteName: String,
    requestedUsername: String,
    finalUrl: String,
    pageTitle: String?,
    pageBio: String?
): Boolean {
    val lowerSite = siteName.lowercase()
    val lowerUrl = finalUrl.lowercase().trimEnd('/')
    val lowerTitle = pageTitle?.lowercase().orEmpty()
    val lowerBio = pageBio?.lowercase().orEmpty()

    // 1. Reject if the server redirected back to the site's root homepage
    val strippedPath = lowerUrl.substringAfter("://").substringAfter("/", "")
    if (strippedPath.isBlank()) return true

    // 2. Site-specific soft-404 and wrong-profile checks
    if (lowerSite.contains("flightradar")) {
        if (lowerTitle.contains("your personal flight logbook") ||
            lowerBio.contains("your personal flight logbook") ||
            !lowerUrl.contains(requestedUsername.lowercase())
        ) return true
    }

    if (lowerSite.contains("strava")) {
        // Reject login walls or when Strava strips a suffix and matches a different name
        if (lowerTitle.contains("log in") || lowerBio.contains("log in to see")) return true
        @Suppress("UNUSED_VARIABLE")
        val handlePrefix = requestedUsername.BeforeDotOrFull().lowercase()
        if (requestedUsername.contains(".") && !lowerUrl.endsWith(requestedUsername.lowercase())) {
            return true
        }
    }

    if (lowerSite.contains("calendly") || lowerSite.contains("calendy")) {
        if (lowerBio.contains("calendly connects and simplifies")) return true
    }

    return false
}

@Suppress("FunctionName")
private fun String.BeforeDotOrFull(): String = substringBefore(".")

private val TITLE_TAG_REGEX = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

fun extractPageTitle(html: String): String? =
    TITLE_TAG_REGEX.find(html)?.groupValues?.get(1)?.trim()
