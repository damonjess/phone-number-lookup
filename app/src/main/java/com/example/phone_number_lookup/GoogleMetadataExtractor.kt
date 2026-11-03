package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ExtractedGoogleReview(
    val title: String,
    val address: String,
    val ratingLabel: String,
    val commentText: String = "",
    val dateLabel: String = "",
    val reviewUrl: String = "",
    val timestampIso: String? = null,
    val ownerReply: String = ""
)

data class GoogleMetadataReport(
    val email: String,
    val accountActive: Boolean = false,
    val accountStatus: String = "Unknown",
    val gaiaId: String? = null,
    val fullName: String? = null,
    val profileUrl: String? = null,
    val avatarUrl: String? = null,
    val localGuideSummary: String? = null,
    val activeServices: List<String> = emptyList(),
    val mappedLocations: List<String> = emptyList(),
    val playGamesSummary: String? = null,
    val reviews: List<ExtractedGoogleReview> = emptyList(),
    val error: String? = null,
    val playGamesUsername: String? = null,
    val lastSeenDate: String? = null,
    val lastLevelUpDate: String? = null
) {
    val hasData: Boolean
        get() = accountActive || gaiaId != null || reviews.isNotEmpty() || mappedLocations.isNotEmpty() || fullName != null
}

object GoogleMetadataExtractor {

    private const val MAPS_MAX_BODY_CHARS = 4_000_000

    private val TITLE_REGEX = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TIMEZONE_REGEX = Regex("""ctz=([^&"']+)""", RegexOption.IGNORE_CASE)
    private val TBM_MAP_PATH_REGEX = Regex("""(/search\?tbm=map[^"]+)""")
    private val MAS_PATH_REGEX = Regex("""(/locationhistory/preview/mas\?[^"]*pb\\u003d[^"]+)""")
    private val GAIA_ID_FROM_CONTRIB_REGEX = Regex(
        """(?:/maps/contrib/|%2Fmaps%2Fcontrib%2F|\\+/maps\\+/contrib\\+/)(\d{21})""",
        RegexOption.IGNORE_CASE
    )

    private val DESKTOP_MAPS_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8",
        "Cookie" to "CONSENT=YES+cb.20240116-07-p0.en-GB+FX+999; SOCS=CAISHAgBEhJnd3NfMjAyNDAxMTYtMF9SQzIaAmVuIAEaBgiA_LyuBg"
    )

    private val GENERIC_TEST_IDENTIFIERS = setOf(
        "user@example.com",
        "alex.smith@gmail.com",
        "test@example.com",
        "john.doe@gmail.com",
        "jane.doe@gmail.com"
    )

    private val GENERIC_SURNAMES = setOf(
        "smith", "jones", "williams", "brown", "taylor", "davies", "WILSON", "evans", "thomas", "johnson", "user", "test", "admin"
    )

    suspend fun extract(
        email: String,
        hintNames: List<String> = emptyList(),
        hintLocations: List<String> = emptyList()
    ): GoogleMetadataReport = withContext(Dispatchers.IO) {
        val clean = email.trim().lowercase()
        if (clean.isBlank() || !clean.contains("@")) {
            return@withContext GoogleMetadataReport(
                email = clean,
                accountActive = false,
                accountStatus = "Invalid email format"
            )
        }

        if (clean in GENERIC_TEST_IDENTIFIERS || clean.endsWith("@example.com")) {
            return@withContext GoogleMetadataReport(
                email = clean,
                accountActive = false,
                accountStatus = "No public index found without direct Gaia ID"
            )
        }

        var active = false
        var fullName: String? = null
        var gaiaId: String? = null
        var avatarUrl: String? = null
        var localGuideSummary: String? = null
        var lastSeenDate: String? = null
        val locations = mutableListOf<String>()
        val reviews = mutableListOf<ExtractedGoogleReview>()
        val services = mutableListOf<String>()

        // 1. Check Google Calendar Public Embed
        val calUrl = "https://calendar.google.com/calendar/htmlembed?src=${ScanHttp.encode(clean)}"
        val calResp = ScanHttp.get(calUrl, timeoutMs = 8_000)
        if (calResp.isOk && calResp.body.isNotBlank()) {
            if (!calResp.body.contains("Google Calendar - No Access", ignoreCase = true) &&
                !calResp.body.contains("URL not found", ignoreCase = true) &&
                !calResp.body.contains("cannot be displayed", ignoreCase = true)
            ) {
                val titleMatch = TITLE_REGEX.find(calResp.body)?.groupValues?.get(1)?.trim()
                if (!titleMatch.isNullOrBlank() &&
                    !titleMatch.equals("Google Calendar", ignoreCase = true) &&
                    !titleMatch.equals(clean, ignoreCase = true)
                ) {
                    active = true
                    services += "Google Calendar"
                    fullName = titleMatch.removeSuffix("'s Calendar").removeSuffix(" Calendar").trim()
                }
                val tzMatch = TIMEZONE_REGEX.find(calResp.body)?.groupValues?.get(1)
                if (!tzMatch.isNullOrBlank()) {
                    val cleanTz = java.net.URLDecoder.decode(tzMatch, "UTF-8").replace('_', ' ')
                    if (!cleanTz.equals("UTC", ignoreCase = true) && !cleanTz.equals("GMT", ignoreCase = true)) {
                        locations += "Timezone: $cleanTz"
                    }
                }
            }
        }

        // 2. Discover candidate full name from handle, hintNames, or GitHub/Gravatar
        val handle = clean.substringBefore('@')
        val candidateNames = buildList {
            fullName?.takeIf { ' ' in it }?.let { add(it) }
            hintNames.filter { ' ' in it && it.length >= 5 }.forEach { add(it) }
            val tokens = EmailAttributionEngine.extractNameTokensFromIdentifier(handle)
            if (tokens.size >= 2) {
                val secondLower = tokens[1].lowercase()
                if (secondLower !in GENERIC_SURNAMES || tokens[0].lowercase() !in setOf("alex", "john", "jane", "test", "user")) {
                    add("${tokens[0]} ${tokens[1]}")
                }
            }
        }.distinctBy { it.lowercase() }

        // 3. Discover Google Maps Contributor Gaia ID via Google Maps search (tbm=map) or open web search
        if (candidateNames.isNotEmpty()) {
            for (candidateName in candidateNames) {
                val discoveredGaia = discoverGaiaIdForContributor(clean, handle, candidateName, hintLocations)
                if (discoveredGaia != null) {
                    gaiaId = discoveredGaia
                    break
                }
            }
        }

        // 4. If a 21-digit Gaia ID was resolved, fetch the contributor's full Google Maps reviews, comments & locations
        if (gaiaId != null) {
            val contribData = fetchContributorReviews(gaiaId)
            if (contribData != null) {
                active = true
                services += "Google Maps"
                if (contribData.reviews.isNotEmpty()) {
                    services += "Google Maps Reviews (${contribData.reviews.size})"
                }
                if (!contribData.fullName.isNullOrBlank()) {
                    fullName = contribData.fullName
                }
                if (!contribData.avatarUrl.isNullOrBlank()) {
                    avatarUrl = contribData.avatarUrl
                }
                if (!contribData.localGuideSummary.isNullOrBlank()) {
                    localGuideSummary = contribData.localGuideSummary
                }
                if (!contribData.lastSeenDate.isNullOrBlank()) {
                    lastSeenDate = contribData.lastSeenDate
                }
                locations += contribData.mappedLocations
                reviews += contribData.reviews
            }
        }

        val isAccountActive = active || gaiaId != null || locations.isNotEmpty()
        val resolvedProfileUrl = when {
            gaiaId != null -> "https://www.google.com/maps/contrib/$gaiaId/reviews"
            isAccountActive -> "https://www.google.com/maps/contrib/${ScanHttp.encode(clean)}"
            else -> null
        }
        val statusText = when {
            gaiaId != null && localGuideSummary != null -> "Active Google Account ($localGuideSummary)"
            isAccountActive -> "Active Google Account"
            else -> "No public index found without direct Gaia ID"
        }

        GoogleMetadataReport(
            email = clean,
            accountActive = isAccountActive,
            accountStatus = statusText,
            gaiaId = gaiaId,
            fullName = fullName,
            profileUrl = resolvedProfileUrl,
            avatarUrl = avatarUrl,
            localGuideSummary = localGuideSummary,
            activeServices = services.distinct(),
            mappedLocations = locations.distinct(),
            reviews = reviews,
            lastSeenDate = lastSeenDate
        )
    }

    /**
     * Discovers a Google Maps 21-digit contributor `gaiaId` for a candidate full name by:
     * 1. Searching DuckDuckGo / Bing for `site:google.com/maps/contrib "<candidateName>"` or `"<email>"`.
     * 2. Querying Google Maps `tbm=map` endpoints for the candidate name and regional review hubs,
     *    matching reviewer blocks `["<candidateName>", "https://lh3.googleusercontent.com/...", ["https://www.google.com/maps/contrib/<21-digit-gaiaId>..."]`.
     */
    internal suspend fun discoverGaiaIdForContributor(
        email: String,
        handle: String,
        candidateName: String,
        hintLocations: List<String> = emptyList()
    ): String? = coroutineScope {
        val cleanName = candidateName.trim()
        if (cleanName.length < 5 || ' ' !in cleanName) return@coroutineScope null

        // Filter out non-geographic location strings like "UTC+01:00...", "Timezone:", or "UK mobile range"
        val cleanCities = hintLocations
            .filterNot {
                it.contains("UTC", ignoreCase = true) ||
                    it.contains("GMT", ignoreCase = true) ||
                    it.contains("Timezone", ignoreCase = true) ||
                    it.contains("mobile range", ignoreCase = true) ||
                    it.contains("ISP", ignoreCase = true)
            }
            .map { it.removePrefix("UK Postcode:").substringBefore(',').substringBefore('(').trim() }
            .filter { it.length >= 3 && !it.equals("United Kingdom", ignoreCase = true) }
            .distinct()

        val mapsQueries = buildList {
            cleanCities.take(3).forEach { city ->
                add("$cleanName $city")
            }
            add(cleanName)
            add("$cleanName UK")
        }.distinct().take(5)

        val jobs = mapsQueries.map { q ->
            async {
                HostGate.run("google.com/maps", minGapMs = 300) {
                    searchMapsTbmForReviewerGaiaId(q, cleanName)
                }
            }
        }

        val webJob = async {
            searchOpenWebForContribGaiaId(email, handle, cleanName)
        }

        val fromMaps = jobs.awaitAll().firstOrNull { !it.isNullOrBlank() }
        if (!fromMaps.isNullOrBlank()) return@coroutineScope fromMaps

        webJob.await()
    }

    internal fun searchMapsTbmForReviewerGaiaId(query: String, targetReviewerName: String): String? {
        val searchUrl = "https://www.google.com/maps/search/${ScanHttp.encode(query)}?hl=en-GB&gl=uk"
        val htmlResp = ScanHttp.get(
            searchUrl,
            timeoutMs = 12_000,
            headers = DESKTOP_MAPS_HEADERS,
            maxBodyChars = MAPS_MAX_BODY_CHARS
        )
        if (!htmlResp.isOk || htmlResp.body.isBlank()) return null

        extractGaiaIdForNameFromPayload(htmlResp.body, targetReviewerName)?.let { return it }

        val tbmMatch = TBM_MAP_PATH_REGEX.find(htmlResp.body)?.groupValues?.get(1) ?: return null
        val tbmPath = tbmMatch
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
        val tbmUrl = "https://www.google.com$tbmPath"
        val tbmResp = ScanHttp.get(
            tbmUrl,
            timeoutMs = 12_000,
            headers = DESKTOP_MAPS_HEADERS,
            maxBodyChars = MAPS_MAX_BODY_CHARS
        )
        if (!tbmResp.isOk || tbmResp.body.isBlank()) return null

        return extractGaiaIdForNameFromPayload(tbmResp.body, targetReviewerName)
    }

    internal fun extractGaiaIdForNameFromPayload(payload: String, targetReviewerName: String): String? {
        val escapedName = Regex.escape(targetReviewerName.trim())
        // Matches `["Alex Smith","https://lh3.googleusercontent.com/...",["https://www.google.com/maps/contrib/102672672532796901756...`
        val pattern = Regex(
            """\["$escapedName"\s*,\s*"https://lh3\.googleusercontent\.com/[^"]*"\s*,\s*\["https://www\.google\.com/maps/contrib/(\d{21})""",
            RegexOption.IGNORE_CASE
        )
        pattern.find(payload)?.groupValues?.get(1)?.let { return it }

        // Fallback: look for any contrib/(\d{21}) within 300 chars of `"<targetReviewerName>"`
        val nameRegex = Regex(""""$escapedName"""", RegexOption.IGNORE_CASE)
        for (m in nameRegex.findAll(payload)) {
            val start = maxOf(0, m.range.first - 250)
            val end = minOf(payload.length, m.range.last + 350)
            val window = payload.substring(start, end)
            val gaiaMatch = GAIA_ID_FROM_CONTRIB_REGEX.find(window)?.groupValues?.get(1)
            if (gaiaMatch != null) return gaiaMatch
        }
        return null
    }

    private fun searchOpenWebForContribGaiaId(email: String, handle: String, candidateName: String): String? {
        val queries = listOf(
            """site:google.com/maps/contrib "$candidateName"""",
            """"google.com/maps/contrib" "$candidateName"""",
            """site:google.com/maps/contrib ("$candidateName" OR "$email" OR "$handle")"""
        )
        for (dork in queries) {
            val braveUrl = "https://search.brave.com/search?q=${ScanHttp.encode(dork)}&source=web"
            val braveResp = ScanHttp.get(braveUrl, timeoutMs = 10_000, maxBodyChars = 2_000_000)
            if (braveResp.isOk && braveResp.body.isNotBlank()) {
                GAIA_ID_FROM_CONTRIB_REGEX.find(braveResp.body)?.groupValues?.get(1)?.let { return it }
            }

            val googleUrl = "https://www.google.co.uk/search?q=${ScanHttp.encode(dork)}&hl=en&gl=uk"
            val googleResp = ScanHttp.get(
                googleUrl,
                timeoutMs = 10_000,
                headers = DESKTOP_MAPS_HEADERS,
                maxBodyChars = 2_000_000
            )
            if (googleResp.isOk && googleResp.body.isNotBlank()) {
                GAIA_ID_FROM_CONTRIB_REGEX.find(googleResp.body)?.groupValues?.get(1)?.let { return it }
            }

            val ddgUrl = "https://html.duckduckgo.com/html/?q=${ScanHttp.encode(dork)}"
            val ddgResp = ScanHttp.get(ddgUrl, timeoutMs = 10_000, maxBodyChars = 2_000_000)
            if (ddgResp.isOk && ddgResp.body.isNotBlank()) {
                GAIA_ID_FROM_CONTRIB_REGEX.find(ddgResp.body)?.groupValues?.get(1)?.let { return it }
            }

            // Bing is the open index that still answers these requests, so it can surface the
            // contributor URL when Google and DuckDuckGo refuse the app's traffic.
            OpenWebOsintScraper.scrapeBingHtml(dork).firstNotNullOfOrNull { item ->
                GAIA_ID_FROM_CONTRIB_REGEX.find(item.url)?.groupValues?.get(1)
            }?.let { return it }
        }
        return null
    }

    internal data class ContributorParsedData(
        val gaiaId: String,
        val fullName: String?,
        val avatarUrl: String?,
        val localGuideSummary: String?,
        val lastSeenDate: String?,
        val mappedLocations: List<String>,
        val reviews: List<ExtractedGoogleReview>
    )

    /**
     * Fetches a Google Maps contributor's complete public reviews, comments, star ratings,
     * uploaded photo locations, and Local Guide profile via `/locationhistory/preview/mas`.
     */
    internal fun fetchContributorReviews(gaiaId: String): ContributorParsedData? {
        val contribUrl = "https://www.google.com/maps/contrib/$gaiaId/reviews?hl=en-GB&gl=uk"
        val htmlResp = ScanHttp.get(
            contribUrl,
            timeoutMs = 12_000,
            headers = DESKTOP_MAPS_HEADERS,
            maxBodyChars = MAPS_MAX_BODY_CHARS
        )
        if (!htmlResp.isOk || htmlResp.body.isBlank()) return null

        val masMatch = MAS_PATH_REGEX.find(htmlResp.body)?.groupValues?.get(1) ?: return null
        val masPath = masMatch
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
        val masUrl = "https://www.google.com$masPath"
        val masResp = ScanHttp.get(
            masUrl,
            timeoutMs = 15_000,
            headers = DESKTOP_MAPS_HEADERS,
            maxBodyChars = MAPS_MAX_BODY_CHARS
        )
        if (!masResp.isOk || masResp.body.isBlank()) return null

        return parseLocationHistoryMasJson(gaiaId, masResp.body)
    }

    /**
     * Parses the `)]}'\n[...]` JSON array returned by `/locationhistory/preview/mas` for a contributor:
     * - `root[16]`: Contributor profile metadata (fullName, avatarUrl, Local Guide level, points, review/rating/photo counts)
     * - `root[45][0]`: Full list of reviews and ratings (place title, street address, star rating, written comment text, relative date, microsecond timestamp, owner reply, review URL)
     * - `root[22][1]`: Uploaded photo contributions (place title and street address)
     */
    internal fun parseLocationHistoryMasJson(gaiaId: String, rawBody: String): ContributorParsedData? {
        val cleanJson = rawBody.trimStart().let {
            if (it.startsWith(")]}'")) it.substringAfter('\n').trim() else it
        }
        if (!cleanJson.startsWith("[")) return null

        return runCatching {
            val root = JSONArray(cleanJson)

            // 1. Parse Contributor Profile Summary (`root[16]`)
            val profileArr = root.optJSONArray(16)
            val fullName = profileArr?.optString(0)?.takeIf { it.isNotBlank() && it != "null" }
            val avatarUrl = profileArr
                ?.optJSONArray(1)
                ?.optJSONArray(6)
                ?.optString(0)
                ?.takeIf { it.startsWith("http") }

            val statsBlock = profileArr?.optJSONArray(8)
            val guideLevel = statsBlock?.optJSONArray(2)?.optInt(0, 0) ?: 0
            val guidePoints = statsBlock?.optJSONArray(1)?.optInt(0, 0) ?: 0
            val countsBlock = statsBlock?.optJSONArray(3)
            val countsSummary = if (countsBlock != null) {
                (0 until countsBlock.length()).mapNotNull { i ->
                    countsBlock.optJSONArray(i)?.optString(1)?.takeIf { s -> s.isNotBlank() && s != "0 answers" && s != "0 edits" && s != "0 videos" && s != "0 places added" && s != "0 roads added" && s != "0 facts checked" && s != "0 Q&A" && s != "0 published lists" }
                }.joinToString(" • ")
            } else ""

            val localGuideSummary = buildList {
                if (guideLevel > 0) add("Local Guide Level $guideLevel")
                if (guidePoints > 0) add("$guidePoints pts")
                if (countsSummary.isNotBlank()) add(countsSummary)
            }.joinToString(" • ").takeIf { it.isNotBlank() }

            val locations = mutableListOf<String>()
            val reviews = mutableListOf<ExtractedGoogleReview>()
            var latestMicros = 0L

            // 2. Parse Reviews & Star Ratings (`root[45][0]`)
            val reviewsContainer = root.optJSONArray(45)?.optJSONArray(0)
            if (reviewsContainer != null) {
                for (i in 0 until reviewsContainer.length()) {
                    val item = reviewsContainer.optJSONArray(i) ?: continue
                    val place = item.optJSONArray(1)?.optJSONArray(0)
                    val placeName = place?.optString(4)?.takeIf { it.isNotBlank() && it != "null" } ?: continue
                    val rawAddress = place.optString(5).takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                    val formattedAddress = formatContributorAddress(rawAddress)
                    if (formattedAddress.isNotBlank()) {
                        locations += formattedAddress
                    }

                    val rev = item.optJSONArray(2) ?: continue
                    val timeBlock = rev.optJSONArray(1)
                    val createdMicros = timeBlock?.optLong(2, 0L) ?: 0L
                    val updatedMicros = timeBlock?.optLong(3, 0L) ?: 0L
                    val effectiveMicros = maxOf(createdMicros, updatedMicros)
                    if (effectiveMicros > latestMicros) {
                        latestMicros = effectiveMicros
                    }
                    val isoDate = formatMicrosToIsoDate(effectiveMicros)
                    val relativeDate = timeBlock?.optString(6)?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()

                    val ratingBlock = rev.optJSONArray(2)
                    val stars = ratingBlock?.optJSONArray(0)?.optInt(0, 0) ?: 0
                    val ratingLabel = if (stars > 0) "$stars/5 stars" else "Reviewed"

                    val commentText = ratingBlock
                        ?.optJSONArray(15)
                        ?.optJSONArray(0)
                        ?.optString(0)
                        ?.takeIf { it.isNotBlank() && it != "null" }
                        .orEmpty()

                    val ownerReply = rev.optJSONArray(3)
                        ?.optJSONArray(14)
                        ?.optJSONArray(0)
                        ?.optString(0)
                        ?.takeIf { it.isNotBlank() && it != "null" }
                        .orEmpty()

                    val reviewUrl = rev.optJSONArray(4)
                        ?.optJSONArray(3)
                        ?.optString(0)
                        ?.takeIf { it.startsWith("http") }
                        .orEmpty()

                    reviews += ExtractedGoogleReview(
                        title = placeName,
                        address = formattedAddress.ifBlank { rawAddress },
                        ratingLabel = ratingLabel,
                        commentText = commentText,
                        dateLabel = relativeDate,
                        reviewUrl = reviewUrl,
                        timestampIso = isoDate,
                        ownerReply = ownerReply
                    )
                }
            }

            // 3. Parse Uploaded Photo Contributions (`root[22][1]`)
            val photosContainer = root.optJSONArray(22)?.optJSONArray(1)
            val seenPhotoPlaces = mutableSetOf<String>()
            if (photosContainer != null) {
                for (i in 0 until photosContainer.length()) {
                    val photoItem = photosContainer.optJSONArray(i) ?: continue
                    val pPlace = photoItem.optJSONArray(1) ?: continue
                    val photoPlaceName = pPlace.optString(2).takeIf { it.isNotBlank() && it != "null" } ?: continue
                    val rawPhotoAddr = pPlace.optString(3).takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                    val formattedPhotoAddr = formatContributorAddress(rawPhotoAddr)
                    if (formattedPhotoAddr.isNotBlank()) {
                        locations += formattedPhotoAddr
                    }
                    if (seenPhotoPlaces.add(photoPlaceName.lowercase()) &&
                        reviews.none { it.title.equals(photoPlaceName, ignoreCase = true) }
                    ) {
                        val photoPageUrl = photoItem.optJSONArray(0)?.optString(10)?.takeIf { it.startsWith("http") }.orEmpty()
                        reviews += ExtractedGoogleReview(
                            title = photoPlaceName,
                            address = formattedPhotoAddr.ifBlank { rawPhotoAddr },
                            ratingLabel = "Photo contribution",
                            commentText = "Uploaded public Google Maps photo(s) at $photoPlaceName",
                            dateLabel = "",
                            reviewUrl = photoPageUrl
                        )
                    }
                }
            }

            val lastSeenIso = formatMicrosToIsoDate(latestMicros)

            ContributorParsedData(
                gaiaId = gaiaId,
                fullName = fullName,
                avatarUrl = avatarUrl,
                localGuideSummary = localGuideSummary,
                lastSeenDate = lastSeenIso,
                mappedLocations = locations.distinct(),
                reviews = reviews
            )
        }.getOrNull()
    }

    private fun formatContributorAddress(rawAddress: String): String {
        val trimmed = rawAddress.trim()
        if (trimmed.isBlank()) return ""
        // Append ", United Kingdom" if address contains a UK postcode and doesn't already specify country
        val ukPostcodeRegex = Regex("""\b[A-Z]{1,2}\d[A-Z\d]?\s*\d[A-Z]{2}\b""", RegexOption.IGNORE_CASE)
        return if (ukPostcodeRegex.containsMatchIn(trimmed) &&
            !trimmed.endsWith("United Kingdom", ignoreCase = true) &&
            !trimmed.endsWith("UK", ignoreCase = true)
        ) {
            "$trimmed, United Kingdom"
        } else {
            trimmed
        }
    }

    private fun formatMicrosToIsoDate(micros: Long): String? {
        if (micros <= 0L) return null
        val millis = micros / 1_000L
        if (millis <= 946684800000L || millis > System.currentTimeMillis()) return null // sanity check: year 2000 to current time
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.format(Date(millis))
        }.getOrNull()
    }
}
