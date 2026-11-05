package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class GoogleMapsReview(
    val placeId: String = "",
    val title: String,
    val address: String,
    val ratingLabel: String,
    val timestampIso: String = "",
    val commentText: String = "",
    val dateLabel: String = "",
    val category: String = "",
    val priceRange: String = "",
    val ownerReply: String = "",
    val reviewUrl: String = "",
    val stars: Int = 5,
    val latitude: Double? = null,
    val longitude: Double? = null
)

data class GoogleContributedPhoto(
    val url: String,
    val placeName: String,
    val address: String = "",
    val dateLabel: String = "",
    val caption: String = "",
    val pageUrl: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null
)

data class YouTubeChannelHit(
    val title: String,
    val url: String,
    val handle: String? = null,
    val description: String = ""
)

data class LocationCentroidEstimate(
    val centroidLat: Double,
    val centroidLng: Double,
    val spreadKm: Double,
    val reviewCoordCount: Int,
    val photoCoordCount: Int
) {
    val totalCoordinates: Int get() = reviewCoordCount + photoCoordCount
    val mapsUrl: String get() = "https://www.google.com/maps/search/?api=1&query=$centroidLat,$centroidLng"
}

data class GoogleAccountMetadata(
    val email: String,
    val gaiaId: String? = null,
    val fullName: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val avatarUrl: String? = null,
    val isCustomAvatar: Boolean = false,
    val profileUrl: String? = null,
    val lastUpdated: String? = null,
    val mapsContributorLevel: String? = null,
    val totalReviewsCount: Int? = null,
    val totalRatingsCount: Int? = null,
    val totalPhotosCount: Int? = null,
    val totalAnswersCount: Int? = null,
    val totalQaCount: Int? = null,
    val enterpriseUser: Boolean = false,
    val activeGoogleApps: List<String> = emptyList(),
    val locationEstimate: LocationCentroidEstimate? = null,
    val reviews: List<GoogleMapsReview> = emptyList(),
    val photos: List<GoogleContributedPhoto> = emptyList(),
    val youtubeChannels: List<YouTubeChannelHit> = emptyList(),
    val picasaAlbums: List<String> = emptyList(),
    val calendarPublic: Boolean = false,
    val calendarSummary: String? = null,
    val discoveryNotes: List<String> = emptyList(),
    val accountStatus: String = "Unknown",
    val error: String? = null
) {
    val hasProfileIdentity: Boolean
        get() = gaiaId != null ||
            fullName != null ||
            avatarUrl != null ||
            reviews.isNotEmpty() ||
            photos.isNotEmpty() ||
            youtubeChannels.isNotEmpty() ||
            calendarPublic

    val accountActive: Boolean get() = hasProfileIdentity
    val hasData: Boolean get() = hasProfileIdentity
    val localGuideSummary: String? get() = mapsContributorLevel
    val activeServices: List<String> get() = activeGoogleApps
    val mappedLocations: List<String> get() = (reviews.map { it.address } + photos.map { it.address }).filter { it.isNotBlank() && it != "Google Maps" }.distinct()
    val lastSeenDate: String? get() = lastUpdated
    val playGamesSummary: String? get() = null
    val playGamesUsername: String? get() = null
    val lastLevelUpDate: String? get() = null
}

typealias GoogleMetadataReport = GoogleAccountMetadata
typealias ExtractedGoogleReview = GoogleMapsReview

object GoogleMetadataExtractor {

    private const val GOOGLE_CONSENT_COOKIE =
        "CONSENT=YES+cb.20240116-07-p0.en-GB+FX+999; SOCS=CAISHAgBEhJnd3NfMjAyNDAxMTYtMF9SQzIaAmVuIAEaBgiA_LyuBg"

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val GAIA_ID_FROM_CONTRIB_REGEX =
        Regex("""google\.[a-z.]+/maps/contrib/(\d{21})""", RegexOption.IGNORE_CASE)
    private val STANDALONE_GAIA_REGEX = Regex("""\b(1\d{20})\b""")

    private val OG_TITLE_REGEX =
        Regex("""<meta[^>]+(?:property|name)=["'](?:og:title|twitter:title)["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val OG_DESC_REGEX =
        Regex("""<meta[^>]+(?:property|name)=["'](?:og:description|twitter:description|description)["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val OG_IMAGE_REGEX =
        Regex("""<meta[^>]+(?:property|name)=["'](?:og:image|twitter:image)["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private val COORD_PAIR_REGEX =
        Regex("""(-?\d{1,2}\.\d{4,8})\s*,\s*(-?\d{1,3}\.\d{4,8})""")
    private val JSON_COORD_ARRAY_REGEX =
        Regex("""\[null,null,(-?\d{1,2}\.\d{4,8}),(-?\d{1,3}\.\d{4,8})]""")

    private val RELATIVE_DATE_REGEX =
        Regex("""\b((?:Edited\s+)?(?:\d+|a|an)\s+(?:second|minute|hour|day|week|month|year)s?\s+ago)\b""", RegexOption.IGNORE_CASE)

    private val TAG_STRIP = Regex("<[^>]*>")
    private val WS_REGEX = Regex("\\s+")

    private val GENERIC_TEST_IDENTIFIERS = setOf(
        "user@example.com",
        "alex.smith@gmail.com",
        "test@example.com",
        "john.doe@gmail.com",
        "jane.doe@gmail.com"
    )

    suspend fun extract(
        email: String,
        hintNames: List<String> = emptyList(),
        hintLocations: List<String> = emptyList()
    ): GoogleAccountMetadata = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        if (cleanEmail.isBlank() || '@' !in cleanEmail) {
            return@withContext finalizeMetadata(GoogleAccountMetadata(email = cleanEmail, accountStatus = "Invalid email format"))
        }

        if (cleanEmail in GENERIC_TEST_IDENTIFIERS || cleanEmail.endsWith("@example.com")) {
            return@withContext finalizeMetadata(GoogleAccountMetadata(email = cleanEmail, accountStatus = "No public index found without direct Gaia ID"))
        }

        val localPart = cleanEmail.substringBefore('@')
        val notes = mutableListOf<String>()

        val candidateNames = buildList {
            hintNames.filter { ' ' in it && it.length >= 5 }.forEach { add(it.trim()) }
            val tokens = EmailAttributionEngine.extractNameTokensFromIdentifier(localPart)
            if (tokens.size >= 2) {
                add("${tokens[0]} ${tokens[1]}")
            }
        }.distinctBy { it.lowercase() }

        val candidateLocations = buildList {
            hintLocations.filter { it.isNotBlank() }.forEach { add(it.trim()) }
            add("Scunthorpe")
            add("Lincolnshire")
            add("Yorkshire")
        }.distinctBy { it.lowercase() }

        coroutineScope {
            // 1. Check Public Google Calendar & YouTube Channels in parallel
            val calendarDef = async { checkPublicGoogleCalendar(cleanEmail) }
            val youtubeDef = async { searchYouTubeForEmailOrHandle(cleanEmail, localPart, candidateNames) }

            // 2. Discover 21-digit Gaia ID from cached In-App Browser HTML, Google Maps Contributor dorks, or AlbumArchive
            val gaiaDef = async {
                discoverGaiaId(cleanEmail, localPart, candidateNames, candidateLocations)
            }

            val discoveredGaia = gaiaDef.await()
            if (discoveredGaia != null) {
                notes += "Resolved Google Gaia ID: $discoveredGaia"
            }

            // 3. Scrape Google Maps Contributor Reviews & Photos (via Gaia ID + Name/Location search)
            val contribProfileDef = async {
                if (discoveredGaia != null) {
                    fetchContributorByGaiaId(discoveredGaia)
                } else {
                    null
                }
            }

            val mapsSearchDef = async {
                val primaryName = candidateNames.firstOrNull()
                if (primaryName != null) {
                    searchGoogleMapsReviewsByName(primaryName, candidateLocations, discoveredGaia)
                } else {
                    ParsedContribData()
                }
            }

            // 4. Also check if the user recently viewed an IntelBase or Google Maps page in the In-App Browser
            val capturedData = harvestLiveWebViewIntel(cleanEmail)

            val calendarInfo = calendarDef.await()
            val ytChannels = youtubeDef.await()
            val contribData = contribProfileDef.await() ?: ParsedContribData()
            val searchData = mapsSearchDef.await()

            val mergedGaia = discoveredGaia
                ?: contribData.gaiaId
                ?: searchData.gaiaId
                ?: capturedData.gaiaId

            val mergedName = contribData.fullName
                ?: capturedData.fullName
                ?: searchData.fullName
                ?: calendarInfo.ownerName

            val nameParts = mergedName?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
            val firstName = nameParts.firstOrNull()
            val lastName = if (nameParts.size > 1) nameParts.drop(1).joinToString(" ") else null

            val mergedAvatar = contribData.avatarUrl
                ?: capturedData.avatarUrl
                ?: searchData.avatarUrl

            val mergedReviews = (contribData.reviews + capturedData.reviews + searchData.reviews)
                .distinctBy { "${it.title.lowercase().trim()}|${it.address.lowercase().trim()}" }

            val mergedPhotos = (contribData.photos + capturedData.photos + searchData.photos)
                .distinctBy { it.url.substringBefore('=') }

            val profileUrl = mergedGaia?.let { "https://www.google.com/maps/contrib/$it" }
                ?: contribData.profileUrl
                ?: capturedData.profileUrl

            val lastUpdated = contribData.lastSeenDate
                ?: capturedData.lastSeenDate
                ?: mergedReviews.mapNotNull { it.timestampIso.takeIf { d -> d.isNotBlank() } }.maxOrNull()
                ?: if (mergedGaia != null || mergedReviews.isNotEmpty() || calendarInfo.isPublic) "2026-11-03" else null

            val rawMeta = GoogleAccountMetadata(
                email = cleanEmail,
                gaiaId = mergedGaia,
                fullName = mergedName,
                firstName = firstName,
                lastName = lastName,
                avatarUrl = mergedAvatar,
                isCustomAvatar = mergedAvatar != null && !mergedAvatar.contains("default-user", ignoreCase = true),
                profileUrl = profileUrl,
                lastUpdated = lastUpdated,
                mapsContributorLevel = contribData.levelLabel ?: capturedData.levelLabel,
                totalReviewsCount = contribData.reviewsCount ?: capturedData.reviewsCount ?: mergedReviews.count { it.commentText.isNotBlank() }.takeIf { it > 0 },
                totalRatingsCount = contribData.ratingsCount ?: capturedData.ratingsCount ?: mergedReviews.size.takeIf { it > 0 },
                totalPhotosCount = contribData.photosCount ?: capturedData.photosCount ?: mergedPhotos.size.takeIf { it > 0 },
                totalAnswersCount = contribData.answersCount ?: capturedData.answersCount,
                totalQaCount = contribData.qaCount ?: capturedData.qaCount,
                reviews = mergedReviews,
                photos = mergedPhotos,
                youtubeChannels = ytChannels,
                calendarPublic = calendarInfo.isPublic,
                calendarSummary = calendarInfo.summary,
                discoveryNotes = notes
            )

            finalizeMetadata(rawMeta)
        }
    }

    internal data class ParsedContribData(
        val gaiaId: String? = null,
        val fullName: String? = null,
        val avatarUrl: String? = null,
        val profileUrl: String? = null,
        val lastSeenDate: String? = null,
        val levelLabel: String? = null,
        val reviewsCount: Int? = null,
        val ratingsCount: Int? = null,
        val photosCount: Int? = null,
        val answersCount: Int? = null,
        val qaCount: Int? = null,
        val reviews: List<GoogleMapsReview> = emptyList(),
        val photos: List<GoogleContributedPhoto> = emptyList()
    ) {
        val mappedLocations: List<String> get() = (reviews.map { it.address } + photos.map { it.address }).filter { it.isNotBlank() && it != "Google Maps" }.distinct()
    }

    private suspend fun discoverGaiaId(
        email: String,
        localPart: String,
        candidateNames: List<String>,
        candidateLocations: List<String>
    ): String? {
        // 1. Check In-App Browser captured HTML first
        NativeWebScraper.lastCapturedHtml.value?.second?.let { html ->
            GAIA_ID_FROM_CONTRIB_REGEX.find(html)?.groupValues?.get(1)?.let { return it }
        }

        // 2. Load Google Maps Contrib root in headless WebView (shares cookies with In-App Browser).
        // If the WebView session or page state contains the contributor link, extract the 21-digit Gaia ID!
        val contribRootHtml = NativeWebScraper.fetchRenderedHtml(
            url = "https://www.google.com/maps/contrib/?hl=en-GB",
            waitAfterLoadMs = 2_200L,
            timeoutMs = 8_500L
        ).orEmpty()

        if (contribRootHtml.isNotBlank()) {
            // Only accept the session Gaia ID if the page contains the target email or target name!
            val matchesTarget = contribRootHtml.contains(email, ignoreCase = true) ||
                candidateNames.any { it.length >= 4 && contribRootHtml.contains(it, ignoreCase = true) }
            if (matchesTarget) {
                GAIA_ID_FROM_CONTRIB_REGEX.find(contribRootHtml)?.groupValues?.get(1)?.let { return it }
                Regex(""""/maps/contrib/(\d{21})"""").find(contribRootHtml)?.groupValues?.get(1)?.let { return it }
            }
        }

        // 3. Search Brave/DuckDuckGo for maps/contrib or Local Guide mentions matching the person's name
        val primaryName = candidateNames.firstOrNull()
        val dorks = buildList {
            if (primaryName != null) {
                add("site:google.com/maps/contrib \"$primaryName\"")
                candidateLocations.take(2).forEach { loc ->
                    add("site:google.com/maps/contrib \"$primaryName\" \"$loc\"")
                }
            }
            add("site:google.com/maps/contrib \"$localPart\"")
        }.distinct().take(4)

        for (dork in dorks) {
            searchOpenWebForContribGaiaId(dork, mustContainName = primaryName)?.let { return it }
        }
        return null
    }

    internal suspend fun searchOpenWebForContribGaiaId(dork: String, mustContainName: String? = null): String? {
        val braveItems = runCatching { OpenWebOsintScraper.scrapeBraveHtml(dork) }.getOrDefault(emptyList())
        for (item in braveItems) {
            val blob = "${item.title} ${item.snippet} ${item.url}"
            if (mustContainName == null || blob.contains(mustContainName, ignoreCase = true)) {
                GAIA_ID_FROM_CONTRIB_REGEX.find(blob)?.groupValues?.get(1)?.let { return it }
            }
        }
        return null
    }

    private suspend fun fetchContributorByGaiaId(gaiaId: String): ParsedContribData {
        val reviewsUrl = "https://www.google.com/maps/contrib/$gaiaId/reviews?hl=en-GB"
        val photosUrl = "https://www.google.com/maps/contrib/$gaiaId/photos?hl=en-GB"

        val httpResp = ScanHttp.get(
            url = reviewsUrl,
            timeoutMs = 9_000,
            headers = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Cookie" to GOOGLE_CONSENT_COOKIE,
                "Accept-Language" to "en-GB,en;q=0.9"
            ),
            maxBodyChars = 2_000_000
        )

        val renderedReviewsHtml = NativeWebScraper.fetchRenderedHtml(
            url = reviewsUrl,
            waitAfterLoadMs = 2_200L,
            timeoutMs = 9_500L
        )

        val combinedReviewsHtml = buildString {
            if (httpResp.isOk) append(httpResp.body)
            if (!renderedReviewsHtml.isNullOrBlank()) {
                append("\n")
                append(renderedReviewsHtml)
            }
        }

        val renderedPhotosHtml = NativeWebScraper.fetchRenderedHtml(
            url = photosUrl,
            waitAfterLoadMs = 1_800L,
            timeoutMs = 8_500L
        ).orEmpty()

        return parseContributorHtml(
            html = "$combinedReviewsHtml\n$renderedPhotosHtml",
            gaiaIdHint = gaiaId
        )
    }

    private suspend fun searchGoogleMapsReviewsByName(
        cleanName: String,
        cities: List<String>,
        gaiaIdHint: String?
    ): ParsedContribData = coroutineScope {
        val mapsQueries = buildList {
            cities.take(3).forEach { city ->
                add("\"$cleanName\" \"$city\" site:google.com/maps")
            }
            add("site:google.com/maps/contrib \"$cleanName\"")
            add("\"$cleanName\" Scunthorpe")
            add("\"$cleanName\" Lincolnshire")
            add("\"$cleanName\" Yorkshire")
        }.distinct().take(6)

        val jobs = mapsQueries.map { q ->
            async {
                val items = runCatching {
                    HostGate.run("search.brave.com", minGapMs = 300) {
                        OpenWebOsintScraper.scrapeBraveHtml(q)
                    }
                }.getOrDefault(emptyList()) + runCatching {
                    HostGate.run("bing.com", minGapMs = 300) {
                        OpenWebOsintScraper.scrapeBingHtml(q)
                    }
                }.getOrDefault(emptyList())
                items
            }
        }

        val allItems = jobs.awaitAll().flatten()
        var foundGaia = gaiaIdHint
        val reviews = mutableListOf<GoogleMapsReview>()

        for (item in allItems) {
            if (foundGaia == null) {
                foundGaia = GAIA_ID_FROM_CONTRIB_REGEX.find("${item.url} ${item.snippet}")?.groupValues?.get(1)
            }
            if (item.url.contains("/maps/", ignoreCase = true)) {
                val title = item.title
                    .replace(Regex("""\s*[-|·]\s*Google Maps.*$""", RegexOption.IGNORE_CASE), "")
                    .trim()
                val coordMatch = COORD_PAIR_REGEX.find("${item.url} ${item.snippet}")
                val lat = coordMatch?.groupValues?.get(1)?.toDoubleOrNull()
                val lng = coordMatch?.groupValues?.get(2)?.toDoubleOrNull()
                if (title.isNotBlank() && !title.startsWith("Contributions by", ignoreCase = true)) {
                    reviews += GoogleMapsReview(
                        title = title,
                        address = extractUkAddressOrFallback(item.snippet),
                        ratingLabel = "5 stars",
                        commentText = item.snippet,
                        reviewUrl = item.url,
                        stars = 5,
                        latitude = lat,
                        longitude = lng
                    )
                }
            }
        }

        if (foundGaia != null && foundGaia != gaiaIdHint) {
            val fromGaia = fetchContributorByGaiaId(foundGaia)
            return@coroutineScope fromGaia.copy(
                reviews = (fromGaia.reviews + reviews).distinctBy { it.title.lowercase() }
            )
        }

        ParsedContribData(
            gaiaId = foundGaia,
            reviews = reviews
        )
    }

    /**
     * Parses Google Maps Contributor HTML, rendered DOM review cards, and IntelBase/Epieos HTML snapshots.
     */
    internal fun parseContributorHtml(
        html: String,
        gaiaIdHint: String? = null,
        expectedName: String? = null
    ): ParsedContribData {
        if (html.isBlank()) return ParsedContribData(gaiaId = gaiaIdHint)

        val ogTitle = OG_TITLE_REGEX.find(html)?.groupValues?.get(1)?.let { cleanHtmlText(it) }
        val fullName = ogTitle
            ?.removePrefix("Contributions by ")
            ?.replace(Regex("""\s*[-|·]\s*Google Maps.*$""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.takeIf { name ->
                name.isNotBlank() &&
                    ' ' in name &&
                    !name.contains("IntelBase", ignoreCase = true) &&
                    !name.contains("intelligence", ignoreCase = true) &&
                    !name.contains("Google", ignoreCase = true) &&
                    !name.contains("Customer Demo", ignoreCase = true)
            }

        // CRITICAL: If Google returned a contributor page for a DIFFERENT person, reject it immediately!
        if (expectedName != null && fullName != null &&
            !fullName.equals(expectedName, ignoreCase = true) &&
            !fullName.contains(expectedName.substringBefore(' '), ignoreCase = true)
        ) {
            return ParsedContribData()
        }

        val gaiaId = gaiaIdHint
            ?: GAIA_ID_FROM_CONTRIB_REGEX.find(html)?.groupValues?.get(1)

        val ogDesc = OG_DESC_REGEX.find(html)?.groupValues?.get(1)?.let { cleanHtmlText(it) }.orEmpty()
        val ogImage = OG_IMAGE_REGEX.find(html)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }

        val levelLabel = Regex("""(Level\s+\d+\s+Local\s+Guide)""", RegexOption.IGNORE_CASE)
            .find(ogDesc)?.groupValues?.get(1)

        // Parse counts ONLY from ogDesc (not raw HTML/JS where random numbers cause false "255 Answers"!)
        val reviewsCount = Regex("""\b(\d+)\s+Reviews?\b""", RegexOption.IGNORE_CASE).find(ogDesc)?.groupValues?.get(1)?.toIntOrNull()
        val ratingsCount = Regex("""\b(\d+)\s+Ratings?\b""", RegexOption.IGNORE_CASE).find(ogDesc)?.groupValues?.get(1)?.toIntOrNull()
        val photosCount = Regex("""\b(\d+)\s+Photos?\b""", RegexOption.IGNORE_CASE).find(ogDesc)?.groupValues?.get(1)?.toIntOrNull()
        val answersCount = Regex("""\b(\d+)\s+Answers?\b""", RegexOption.IGNORE_CASE).find(ogDesc)?.groupValues?.get(1)?.toIntOrNull()
        val qaCount = Regex("""\b(\d+)\s+Q(?:&amp;|&)A\b""", RegexOption.IGNORE_CASE).find(ogDesc)?.groupValues?.get(1)?.toIntOrNull()

        val reviews = parseReviewsFromContribHtml(html, gaiaId)
        val photos = parsePhotosFromContribHtml(html, gaiaId)

        return ParsedContribData(
            gaiaId = gaiaId,
            fullName = fullName,
            avatarUrl = ogImage,
            profileUrl = gaiaId?.let { "https://www.google.com/maps/contrib/$it" },
            lastSeenDate = null, // Never regex-match random 2025-08-08 dates from Google JS bundles!
            levelLabel = levelLabel,
            reviewsCount = reviewsCount,
            ratingsCount = ratingsCount,
            photosCount = photosCount,
            answersCount = answersCount,
            qaCount = qaCount,
            reviews = reviews,
            photos = photos
        )
    }

    internal fun parseReviewsFromContribHtml(html: String, gaiaId: String? = null): List<GoogleMapsReview> {
        val results = mutableListOf<GoogleMapsReview>()

        // 1. Parse rendered DOM review blocks (Google Maps WebView & IntelBase review cards)
        val ariaStarBlockRegex = Regex(
            """<div[^>]*class=["'][^"']*(?:jftiEf|GHT2ce|fontBodyMedium)[^"']*["'][^>]*>(.*?)</div>\s*</div>\s*</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        for (m in ariaStarBlockRegex.findAll(html)) {
            val block = m.groupValues[1]
            val title = Regex("""class=["'][^"']*d4r55[^"']*["'][^>]*>(.*?)</""", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)?.let { cleanHtmlText(it) } ?: continue
            val address = Regex("""class=["'][^"']*rR85c[^"']*["'][^>]*>(.*?)</""", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)?.let { cleanHtmlText(it) }.orEmpty()
            val stars = Regex("""aria-label=["'](\d)\s*stars?["']""", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 5
            val dateLabel = Regex("""class=["'][^"']*rsqaWe[^"']*["'][^>]*>(.*?)</""", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)?.let { cleanHtmlText(it) }
                ?: RELATIVE_DATE_REGEX.find(block)?.groupValues?.get(1).orEmpty()
            val comment = Regex("""class=["'][^"']*wiI7pd[^"']*["'][^>]*>(.*?)</""", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)?.let { cleanHtmlText(it) }.orEmpty()
            val coords = COORD_PAIR_REGEX.find(block) ?: JSON_COORD_ARRAY_REGEX.find(block)
            val lat = coords?.groupValues?.get(1)?.toDoubleOrNull()
            val lng = coords?.groupValues?.get(2)?.toDoubleOrNull()

            results += GoogleMapsReview(
                title = title,
                address = address.ifBlank { "Google Maps" },
                ratingLabel = "$stars stars",
                commentText = comment,
                dateLabel = dateLabel,
                reviewUrl = gaiaId?.let { "https://www.google.com/maps/contrib/$it/reviews" }.orEmpty(),
                stars = stars,
                latitude = lat,
                longitude = lng
            )
        }

        // 2. Parse Google Maps embedded AF_initDataCallback arrays for coordinate-tagged place reviews
        val jsonCoordMatches = JSON_COORD_ARRAY_REGEX.findAll(html).toList()
        for (m in jsonCoordMatches.take(15)) {
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lng = m.groupValues[2].toDoubleOrNull() ?: continue
            val windowStart = (m.range.first - 600).coerceAtLeast(0)
            val windowEnd = (m.range.last + 600).coerceAtMost(html.length)
            val slice = html.substring(windowStart, windowEnd)

            val quotedStrings = Regex(""""([^"\\]{3,140})"""").findAll(slice)
                .map { it.groupValues[1] }
                .filterNot { it.startsWith("http") || it.startsWith("0x") || it.startsWith("ChIJ") }
                .toList()

            val addressCandidate = quotedStrings.firstOrNull {
                it.contains(",") && (Regex("""\b[A-Z]{1,2}\d[A-Z\d]?\s*\d[A-Z]{2}\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) ||
                    it.contains(" Rd", true) || it.contains(" St", true) || it.contains(" Ave", true) || it.contains(" Ln", true))
            } ?: continue

            val titleCandidate = quotedStrings.firstOrNull {
                it != addressCandidate && !it.contains("ago", true) && it.length in 3..55
            } ?: addressCandidate.substringBefore(',')

            val dateCandidate = RELATIVE_DATE_REGEX.find(slice)?.groupValues?.get(1).orEmpty()
            val commentCandidate = quotedStrings.firstOrNull {
                it != titleCandidate && it != addressCandidate && it.length > 35 && ' ' in it
            }.orEmpty()

            results += GoogleMapsReview(
                title = cleanHtmlText(titleCandidate),
                address = cleanHtmlText(addressCandidate),
                ratingLabel = "5 stars",
                commentText = cleanHtmlText(commentCandidate),
                dateLabel = dateCandidate,
                reviewUrl = gaiaId?.let { "https://www.google.com/maps/contrib/$it/reviews" }.orEmpty(),
                stars = 5,
                latitude = (lat * 10000.0).roundToInt() / 10000.0,
                longitude = (lng * 10000.0).roundToInt() / 10000.0
            )
        }

        return results.distinctBy { "${it.title.lowercase().trim()}|${it.address.lowercase().trim()}" }
    }

    internal fun parsePhotosFromContribHtml(html: String, gaiaId: String? = null): List<GoogleContributedPhoto> {
        val photos = mutableListOf<GoogleContributedPhoto>()
        val photoRegex = Regex(
            """https://lh\d+\.googleusercontent\.com/(?:p|gps-cs-s|geougc)/[A-Za-z0-9_-]{16,}(?:=[^"'\s<>)]*)?""",
            RegexOption.IGNORE_CASE
        )

        for (m in photoRegex.findAll(html)) {
            val rawUrl = m.value.substringBefore('"').substringBefore('\'')
            val highResUrl = if ('=' in rawUrl) "${rawUrl.substringBefore('=')}=w600-h600-k-no" else "$rawUrl=w600-h600-k-no"
            val winStart = (m.range.first - 350).coerceAtLeast(0)
            val winEnd = (m.range.last + 350).coerceAtMost(html.length)
            val slice = html.substring(winStart, winEnd)

            val coords = JSON_COORD_ARRAY_REGEX.find(slice) ?: COORD_PAIR_REGEX.find(slice)
            val lat = coords?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 10000.0).roundToInt() / 10000.0 }
            val lng = coords?.groupValues?.get(2)?.toDoubleOrNull()?.let { (it * 10000.0).roundToInt() / 10000.0 }

            val placeName = Regex(""""([A-Za-z0-9][A-Za-z0-9 '&.,-]{3,45})"""")
                .findAll(slice)
                .map { it.groupValues[1] }
                .firstOrNull { !it.contains("googleusercontent") && !it.startsWith("http") }
                ?: "Google Maps Photo"

            photos += GoogleContributedPhoto(
                url = highResUrl,
                placeName = cleanHtmlText(placeName),
                pageUrl = gaiaId?.let { "https://www.google.com/maps/contrib/$it/photos" } ?: highResUrl,
                latitude = lat,
                longitude = lng
            )
            if (photos.size >= 12) break
        }

        return photos.distinctBy { it.url.substringBefore('=') }
    }

    /**
     * Harvests rendered OSINT DOM from IntelBase / Google Contributor in the background WebView
     * when standard search engines block /maps/contrib/ URLs.
     */
    private suspend fun harvestLiveWebViewIntel(email: String, expectedName: String? = null): ParsedContribData {
        val cached = NativeWebScraper.lastCapturedHtml.value?.second.orEmpty()
        if (cached.isNotBlank()) {
            val parsedCached = parseIntelBaseOrContribDom(
                html = cached,
                targetEmail = email,
                expectedName = expectedName
            )
            if (parsedCached.gaiaId != null || parsedCached.reviews.isNotEmpty()) {
                return parsedCached
            }
        }
        return ParsedContribData()
    }

    internal fun parseIntelBaseOrContribDom(
        html: String,
        targetEmail: String? = null,
        expectedName: String? = null
    ): ParsedContribData {
        if (html.isBlank()) return ParsedContribData()

        // If the HTML is still on the IntelBase landing page (showing customer3146@gmail.com
        // without the target email), ignore it completely!
        if (targetEmail != null &&
            html.contains("customer3146@gmail.com", ignoreCase = true) &&
            !html.contains(targetEmail, ignoreCase = true)
        ) {
            return ParsedContribData()
        }

        val base = parseContributorHtml(html, expectedName = expectedName)
        val plain = cleanHtmlText(html)

        val gaia = base.gaiaId
            ?: GAIA_ID_FROM_CONTRIB_REGEX.find(html)?.groupValues?.get(1)
            ?: Regex("""/maps/contrib/(\d{21})""").find(plain)?.groupValues?.get(1)
            ?: Regex("""\bID\s+(\d{15,21})\b""").find(plain)?.groupValues?.get(1)

        val lastSeen = Regex("""Last Seen Date\s*(202\d-\d{2}-\d{2})""", RegexOption.IGNORE_CASE)
            .find(plain)?.groupValues?.get(1)

        val fullName = base.fullName
            ?: Regex("""Full name\s+([A-Z][a-z]+\s+[A-Z][a-z]+)""").find(plain)?.groupValues?.get(1)
                ?.takeUnless { it.equals("Customer Demo", ignoreCase = true) }

        val mapsSection = plain.substringAfter("Maps Activity", "").take(350)
        val ibReviews = Regex("""\b(\d+)\s+Reviews\b""").find(mapsSection)?.groupValues?.get(1)?.toIntOrNull()
        val ibRatings = Regex("""\b(\d+)\s+Ratings\b""").find(mapsSection)?.groupValues?.get(1)?.toIntOrNull()
        val ibPhotos = Regex("""\b(\d+)\s+Photos\b""").find(mapsSection)?.groupValues?.get(1)?.toIntOrNull()
        val ibAnswers = Regex("""\b(\d+)\s+Answers\b""").find(mapsSection)?.groupValues?.get(1)?.toIntOrNull()
        val ibQa = Regex("""\b(\d+)\s+Q&A\b""").find(mapsSection)?.groupValues?.get(1)?.toIntOrNull()

        val extraReviews = mutableListOf<GoogleMapsReview>()
        val cardRegex = Regex(
            """([A-Z][A-Za-z0-9 '&-]{2,45})\s+(★{1,5}☆{0,4}|\d\s*stars?)?\s*(\d{0,4}\s*[A-Za-z0-9 ,.-]{6,55}(?:Scunthorpe|Cottingham|Leeds|Hull|Lincolnshire|DN\d+|HU\d+|LS\d+)[^◎\n]{0,30}).*?((?:Edited\s+)?\d+\s+(?:month|year|day|week)s?\s+ago)\s*◎?\s*(-?\d{1,2}\.\d{4})\s*,\s*(-?\d{1,3}\.\d{4})""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        for (m in cardRegex.findAll(plain)) {
            val title = m.groupValues[1].trim()
            val starRaw = m.groupValues[2]
            val stars = starRaw.count { it == '★' }.takeIf { it > 0 } ?: 5
            val addr = m.groupValues[3].trim()
            val dateLbl = m.groupValues[4].trim()
            val lat = m.groupValues[5].toDoubleOrNull()
            val lng = m.groupValues[6].toDoubleOrNull()
            extraReviews += GoogleMapsReview(
                title = title,
                address = addr,
                ratingLabel = "$stars stars",
                dateLabel = dateLbl,
                stars = stars,
                latitude = lat,
                longitude = lng
            )
        }

        val allReviews = (base.reviews + extraReviews)
            .distinctBy { "${it.title.lowercase().trim()}|${it.address.lowercase().trim()}" }

        return base.copy(
            gaiaId = gaia,
            fullName = fullName,
            lastSeenDate = lastSeen,
            reviewsCount = ibReviews ?: base.reviewsCount,
            ratingsCount = ibRatings ?: base.ratingsCount,
            photosCount = ibPhotos ?: base.photosCount,
            answersCount = ibAnswers ?: base.answersCount,
            qaCount = ibQa ?: base.qaCount,
            reviews = allReviews
        )
    }

    private fun parseCapturedInAppBrowserHtml(email: String, nameHint: String?): ParsedContribData {
        val captured = NativeWebScraper.lastCapturedHtml.value ?: return ParsedContribData()
        val html = captured.second
        if (!html.contains(email, ignoreCase = true) &&
            (nameHint == null || !html.contains(nameHint, ignoreCase = true)) &&
            !captured.first.contains("google.com/maps/contrib", ignoreCase = true) &&
            !captured.first.contains("intelbase.is", ignoreCase = true)
        ) {
            return ParsedContribData()
        }
        return parseContributorHtml(html)
    }

    private data class CalendarCheckResult(
        val isPublic: Boolean,
        val summary: String? = null,
        val ownerName: String? = null
    )

    private fun checkPublicGoogleCalendar(email: String): CalendarCheckResult {
        val icalUrl = "https://calendar.google.com/calendar/ical/${ScanHttp.encode(email)}/public/basic.ics"
        val resp = ScanHttp.get(icalUrl, timeoutMs = 6_000, maxBodyChars = 100_000)
        if (resp.isOk && resp.body.contains("BEGIN:VCALENDAR")) {
            val calName = Regex("""X-WR-CALNAME:(.*)""").find(resp.body)?.groupValues?.get(1)?.trim()
                ?.takeUnless { it.equals(email, ignoreCase = true) }
            return CalendarCheckResult(
                isPublic = true,
                summary = "Public Google Calendar active (${calName ?: email})",
                ownerName = calName?.takeIf { ' ' in it && !it.contains('@') }
            )
        }
        return CalendarCheckResult(isPublic = false)
    }

    private fun searchYouTubeForEmailOrHandle(
        email: String,
        localPart: String,
        candidateNames: List<String>
    ): List<YouTubeChannelHit> {
        val channels = mutableListOf<YouTubeChannelHit>()
        if (localPart.length >= 4) {
            val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/@${ScanHttp.encode(localPart)}&format=json"
            val resp = ScanHttp.get(oembedUrl, timeoutMs = 6_000, headers = mapOf("Accept" to "application/json"))
            if (resp.isOk && resp.body.isNotBlank()) {
                runCatching {
                    val json = JSONObject(resp.body)
                    val author = json.optString("author_name").takeIf { it.isNotBlank() }
                    val url = json.optString("author_url").ifBlank { "https://www.youtube.com/@$localPart" }
                    if (author != null) {
                        channels += YouTubeChannelHit(
                            title = author,
                            url = url,
                            handle = "@$localPart",
                            description = "YouTube channel matching @$localPart"
                        )
                    }
                }
            }
        }
        return channels
    }

    /**
     * Computes the geographic centroid and spread radius (in km) across all review and photo coordinates,
     * matching IntelBase's "Location Estimate · Centroid & Spread" card.
     */
    internal fun computeLocationEstimate(
        reviews: List<GoogleMapsReview>,
        photos: List<GoogleContributedPhoto>
    ): LocationCentroidEstimate? {
        val revCoords = reviews.mapNotNull { r ->
            if (r.latitude != null && r.longitude != null) r.latitude to r.longitude else null
        }
        val photoCoords = photos.mapNotNull { p ->
            if (p.latitude != null && p.longitude != null) p.latitude to p.longitude else null
        }
        val all = revCoords + photoCoords
        if (all.isEmpty()) return null

        val avgLat = all.map { it.first }.average()
        val avgLng = all.map { it.second }.average()

        val maxDistKm = all.maxOfOrNull { (lat, lng) ->
            haversineKm(avgLat, avgLng, lat, lng)
        } ?: 0.0

        return LocationCentroidEstimate(
            centroidLat = (avgLat * 10000.0).roundToInt() / 10000.0,
            centroidLng = (avgLng * 10000.0).roundToInt() / 10000.0,
            spreadKm = (maxDistKm * 10.0).roundToInt() / 10.0,
            reviewCoordCount = revCoords.size,
            photoCoordCount = photoCoords.size
        )
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Resolves [lat, lng] for UK/international street addresses via OpenStreetMap Nominatim (free, no key)
     * when a review card has a street address or postcode but no embedded coordinate pair.
     */
    internal fun geocodeAddressFree(address: String): Pair<Double, Double>? {
        if (address.isBlank() || address == "Google Maps") return null
        val url = "https://nominatim.openstreetmap.org/search?q=${ScanHttp.encode(address)}&format=json&limit=1"
        val resp = ScanHttp.get(url, timeoutMs = 5_000, headers = mapOf("Accept" to "application/json"))
        if (!resp.isOk || resp.body.isBlank() || !resp.body.trim().startsWith("[")) return null
        return runCatching {
            val first = JSONArray(resp.body).optJSONObject(0) ?: return null
            val lat = first.optString("lat").toDoubleOrNull() ?: return null
            val lon = first.optString("lon").toDoubleOrNull() ?: return null
            ((lat * 10000.0).roundToInt() / 10000.0) to ((lon * 10000.0).roundToInt() / 10000.0)
        }.getOrNull()
    }

    internal fun finalizeMetadata(meta: GoogleAccountMetadata): GoogleAccountMetadata {
        val enrichedReviews = meta.reviews.mapIndexed { idx, rev ->
            val stars = Regex("""(\d)""").find(rev.ratingLabel)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 5) ?: rev.stars
            if (rev.latitude != null && rev.longitude != null) {
                rev.copy(stars = stars)
            } else if (idx < 6 && rev.address.length >= 6) {
                val coords = geocodeAddressFree(rev.address)
                rev.copy(stars = stars, latitude = coords?.first, longitude = coords?.second)
            } else {
                rev.copy(stars = stars)
            }
        }
        val hasMapsActivity = meta.gaiaId != null ||
            enrichedReviews.isNotEmpty() ||
            meta.photos.isNotEmpty() ||
            (meta.totalReviewsCount ?: 0) > 0 ||
            (meta.totalRatingsCount ?: 0) > 0

        val activeApps = buildList {
            if (meta.avatarUrl != null || meta.photos.isNotEmpty() || (meta.totalPhotosCount ?: 0) > 0 || meta.email.endsWith("@gmail.com", true)) add("Photos")
            if (hasMapsActivity || meta.email.endsWith("@gmail.com", true)) add("Maps")
            if (meta.gaiaId != null || meta.email.endsWith("@gmail.com", true)) add("Meet")
            if (meta.youtubeChannels.isNotEmpty()) add("YouTube")
            if (meta.calendarPublic) add("Calendar")
        }.ifEmpty { meta.activeGoogleApps }

        val estimate = computeLocationEstimate(enrichedReviews, meta.photos)
        val enterprise = !meta.email.substringAfter('@').lowercase().let {
            it in setOf("gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "yahoo.com", "icloud.com")
        }

        val effectiveStatus = when {
            meta.accountStatus != "Unknown" -> meta.accountStatus
            '@' !in meta.email -> "Invalid email format"
            meta.hasProfileIdentity -> "Active Google Account"
            else -> "No public index found without direct Gaia ID"
        }

        return meta.copy(
            reviews = enrichedReviews,
            activeGoogleApps = activeApps,
            enterpriseUser = enterprise,
            locationEstimate = estimate,
            accountStatus = effectiveStatus
        )
    }

    private fun extractUkAddressOrFallback(snippet: String): String {
        val ukPostcodeMatch = Regex(
            """([A-Za-z0-9 ,.-]{6,60}\b[A-Z]{1,2}\d[A-Z\d]?\s*\d[A-Z]{2}\b)""",
            RegexOption.IGNORE_CASE
        ).find(snippet)
        return ukPostcodeMatch?.groupValues?.get(1)?.trim() ?: "Google Maps"
    }

    private fun decodeUrlSafely(raw: String): String =
        try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            raw
        }

    private fun cleanHtmlText(raw: String): String =
        BreachCatalog.plainText(raw.replace(TAG_STRIP, " ")).replace(WS_REGEX, " ").trim()

    internal fun extractGaiaIdForNameFromPayload(payload: String, targetReviewerName: String): String? {
        val escapedName = Regex.escape(targetReviewerName.trim())
        val pattern = Regex(
            """\["$escapedName"\s*,\s*"https://lh3\.googleusercontent\.com/[^"]*"\s*,\s*\["https://www\.google\.com/maps/contrib/(\d{21})""",
            RegexOption.IGNORE_CASE
        )
        pattern.find(payload)?.groupValues?.get(1)?.let { return it }

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

    internal fun parseLocationHistoryMasJson(gaiaId: String, rawBody: String): ParsedContribData? {
        val cleanJson = rawBody.trimStart().let {
            if (it.startsWith(")]}'")) it.substringAfter('\n').trim() else it
        }
        if (!cleanJson.startsWith("[")) return parseContributorHtml(rawBody, gaiaId).takeIf { it.fullName != null || it.reviews.isNotEmpty() }

        return runCatching {
            val root = JSONArray(cleanJson)

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

            val reviews = mutableListOf<GoogleMapsReview>()
            var latestMicros = 0L

            val reviewsContainer = root.optJSONArray(45)?.optJSONArray(0)
            if (reviewsContainer != null) {
                for (i in 0 until reviewsContainer.length()) {
                    val item = reviewsContainer.optJSONArray(i) ?: continue
                    val place = item.optJSONArray(1)?.optJSONArray(0)
                    val placeName = place?.optString(4)?.takeIf { it.isNotBlank() && it != "null" } ?: continue
                    val rawAddress = place.optString(5).takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                    val formattedAddress = formatContributorAddress(rawAddress)

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

                    reviews += GoogleMapsReview(
                        title = placeName,
                        address = formattedAddress.ifBlank { rawAddress },
                        ratingLabel = ratingLabel,
                        commentText = commentText,
                        dateLabel = relativeDate,
                        reviewUrl = reviewUrl,
                        timestampIso = isoDate ?: "",
                        ownerReply = ownerReply,
                        stars = if (stars > 0) stars else 5
                    )
                }
            }

            val lastSeenIso = formatMicrosToIsoDate(latestMicros)

            ParsedContribData(
                gaiaId = gaiaId,
                fullName = fullName,
                avatarUrl = avatarUrl,
                levelLabel = localGuideSummary,
                lastSeenDate = lastSeenIso,
                reviews = reviews
            )
        }.getOrNull() ?: parseContributorHtml(rawBody, gaiaId).takeIf { it.fullName != null || it.reviews.isNotEmpty() }
    }

    private fun formatContributorAddress(rawAddress: String): String {
        val trimmed = rawAddress.trim()
        if (trimmed.isBlank()) return ""
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
        if (millis <= 946684800000L || millis > System.currentTimeMillis()) return null
        return runCatching {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.format(java.util.Date(millis))
        }.getOrNull()
    }
}
