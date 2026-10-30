package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AttributedValue(
    val value: String,
    val source: String
)

data class AttributedProfileLink(
    val handle: String,
    val platform: String,
    val url: String
)

data class TimelineEvent(
    val date: String,
    val label: String,
    val source: String
)

data class StealerSubjectProfile(
    val computerUsername: String?,
    val maskedIp: String?,
    val operatingSystem: String?,
    val dateCompromised: String?,
    val antiviruses: List<String> = emptyList()
)

data class EmailAttributionReport(
    val email: String,
    val namesFound: List<AttributedValue> = emptyList(),
    val usernamesFound: List<AttributedValue> = emptyList(),
    val profileLinks: List<AttributedProfileLink> = emptyList(),
    val locationsFound: List<AttributedValue> = emptyList(),
    val phonesFound: List<AttributedValue> = emptyList(),
    val avatarUrls: List<String> = emptyList(),
    val timeline: List<TimelineEvent> = emptyList(),
    val stealerProfile: StealerSubjectProfile? = null
) {
    val firstSeen: String?
        get() = timeline.mapNotNull { it.date.takeIf { d -> d.matches(Regex("\\d{4}-\\d{2}-\\d{2}.*")) } }
            .minOrNull()

    val lastSeen: String?
        get() = timeline.mapNotNull { it.date.takeIf { d -> d.matches(Regex("\\d{4}-\\d{2}-\\d{2}.*")) } }
            .maxOrNull()

    val hasData: Boolean
        get() = namesFound.isNotEmpty() ||
            usernamesFound.isNotEmpty() ||
            profileLinks.isNotEmpty() ||
            locationsFound.isNotEmpty() ||
            phonesFound.isNotEmpty() ||
            timeline.isNotEmpty() ||
            stealerProfile != null
}

object EmailAttributionEngine {

    private val GITHUB_HEADERS = mapOf("Accept" to "application/vnd.github+json")

    suspend fun analyze(email: String): EmailAttributionReport = withContext(Dispatchers.IO) {
        val clean = email.trim().lowercase()
        if (clean.isBlank() || !clean.contains('@')) {
            return@withContext EmailAttributionReport(clean)
        }

        val handle = clean.substringBefore('@')

        val googleDef = async { GoogleMetadataExtractor.extract(clean) }
        val githubDef = async { lookupGitHubByEmail(clean) }
        val duolingoDef = async { lookupDuolingoByEmail(clean) }
        val gravatarDef = async { lookupGravatarByEmail(clean) }
        val hudsonDef = async { lookupHudsonRockProfile(clean) }
        val mfpDef = async { lookupMyFitnessPalHandle(handle) }
        val keybaseDef = async { lookupKeybase("email=${ScanHttp.encode(clean)}") }
        val openPgpDef = async { lookupOpenPgpKeysByEmail(clean) }
        val pgpKeysDef = async { IdentityEnricher.fetchPgpKeys(clean) }
        val webSearchDef = async {
            OpenWebOsintScraper.scrapeAll(listOf(clean, handle))
        }
        val instaPhoneDef = async { lookupInstagramRecoveryPhone(clean) }
        val handleDef = if (isDistinctiveHandle(handle)) async { lookupProfilesByHandle(handle) } else null

        val names = mutableListOf<AttributedValue>()
        val usernames = mutableListOf<AttributedValue>()
        val links = mutableListOf<AttributedProfileLink>()
        val locations = mutableListOf<AttributedValue>()
        val phones = mutableListOf<AttributedValue>()
        val avatars = mutableListOf<String>()
        val timeline = mutableListOf<TimelineEvent>()

        instaPhoneDef.await()?.let { phones += it }

        // 1. Google & Play Games Attribution
        val google = googleDef.await()
        google.fullName?.let { names += AttributedValue(it, "Google") }
        google.playGamesUsername?.let { usernames += AttributedValue(it, "Google Play Games") }
        google.profileUrl?.let { url ->
            links += AttributedProfileLink(google.fullName ?: "Google Profile", "Google Maps", url)
        }
        google.mappedLocations.forEach { loc ->
            locations += AttributedValue(loc, "Google")
        }
        google.lastSeenDate?.let {
            timeline += TimelineEvent(it, "Last Active", "Google / Play Games")
        }
        google.lastLevelUpDate?.let {
            timeline += TimelineEvent(it, "Last Level Up", "Google Play Games")
        }

        // 2. GitHub Attribution (by Commit / Email Search)
        githubDef.await()?.let { gh ->
            gh.fullName?.let { names += AttributedValue(it, "GitHub") }
            usernames += AttributedValue(gh.login, "GitHub")
            links += AttributedProfileLink(gh.login, "GitHub", gh.profileUrl)
            gh.location?.let { locations += AttributedValue(it, "GitHub") }
            gh.inferredTimezoneLocation?.let { locations += AttributedValue(it, "GitHub Commit Timezone") }
            gh.bio?.let { bio ->
                phones += extractPhoneNumbersFromText(bio, "GitHub Bio")
                locations += extractLocationsFromText(bio, "GitHub Bio")
            }
            gh.avatarUrl?.let { avatars += it }
            gh.createdAt?.let { timeline += TimelineEvent(it, "Creation Date", "GitHub") }
            gh.updatedAt?.let { timeline += TimelineEvent(it, "Updated At", "GitHub") }
        }

        // 3. Keybase Attribution
        keybaseDef.await()?.let { kb ->
            kb.fullName?.let { names += AttributedValue(it, "Keybase") }
            usernames += AttributedValue(kb.username, "Keybase")
            kb.location?.let { locations += AttributedValue(it, "Keybase") }
            kb.bio?.let { bio ->
                phones += extractPhoneNumbersFromText(bio, "Keybase Bio")
                locations += extractLocationsFromText(bio, "Keybase Bio")
            }
            kb.avatarUrl?.let { avatars += it }
            links += kb.proofs
        }

        // 4. OpenPGP Keyserver Attribution
        openPgpDef.await()?.let { pgp ->
            links += AttributedProfileLink(pgp.email, "OpenPGP Key", pgp.profileUrl)
            timeline += TimelineEvent("Public Key Verified", "OpenPGP Key Found", "OpenPGP Keyserver")
        }
        runCatching { pgpKeysDef.await() }.getOrNull()?.forEach { record ->
            record.uids.forEach { uid ->
                extractNameFromPgpUid(uid)?.let { name ->
                    names += AttributedValue(name, "OpenPGP Key")
                }
            }
        }

        // 5. MyFitnessPal Handle Check
        mfpDef.await()?.let { mfpHandle ->
            usernames += AttributedValue(mfpHandle, "MyFitnessPal")
            links += AttributedProfileLink(mfpHandle, "MyFitnessPal", "https://www.myfitnesspal.com/profile/$mfpHandle")
        }

        // 6. Duolingo Attribution
        duolingoDef.await()?.let { duo ->
            duo.fullName?.let { names += AttributedValue(it, "Duolingo") }
            duo.username?.let { u ->
                usernames += AttributedValue(u, "Duolingo")
                links += AttributedProfileLink(u, "Duolingo", "https://www.duolingo.com/profile/$u")
            }
            duo.country?.let { locations += AttributedValue(it, "Duolingo") }
            duo.avatarUrl?.let { avatars += it }
            duo.createdDate?.let { timeline += TimelineEvent(it, "Joined", "Duolingo") }
        }

        // 7. Gravatar Attribution
        gravatarDef.await()?.let { grav ->
            grav.displayName?.let { names += AttributedValue(it, "Gravatar") }
            grav.username?.let { u ->
                usernames += AttributedValue(u, "Gravatar")
                links += AttributedProfileLink(u, "Gravatar", grav.profileUrl ?: "https://gravatar.com/$u")
            }
            grav.location?.let { locations += AttributedValue(it, "Gravatar") }
            grav.aboutMe?.let { bio ->
                phones += extractPhoneNumbersFromText(bio, "Gravatar Bio")
                locations += extractLocationsFromText(bio, "Gravatar Bio")
            }
            grav.avatarUrl?.let { avatars += it }
            links += grav.verifiedAccounts
        }

        // 8. Multi-Engine Public Web Search Results for Email & Handle
        val webReport = runCatching { webSearchDef.await() }.getOrNull()
        if (webReport != null) {
            phones += webReport.extractedPhones
            usernames += webReport.extractedHandles
            webReport.results.forEach { result ->
                val blob = "${result.title} ${result.snippet}"
                phones += extractPhoneNumbersFromText(blob, result.source)
                locations += extractLocationsFromText(blob, result.source)
            }
        }

        // 9. Public profiles for the email's handle
        handleDef?.await().orEmpty().forEach { profile ->
            profile.name?.let { names += AttributedValue(it, profile.platform) }
            usernames += AttributedValue(profile.username, profile.platform)
            links += AttributedProfileLink(profile.username, profile.platform, profile.profileUrl)
            profile.location?.let { locations += AttributedValue(it, profile.platform) }
            profile.avatarUrl?.let { avatars += it }
            profile.joined?.let { timeline += TimelineEvent(it, "Joined", profile.platform) }
            profile.lastActive?.let { timeline += TimelineEvent(it, "Last Active", profile.platform) }
        }

        // 10. HudsonRock Infostealer Profile + Masked IP Geolocation
        val stealer = hudsonDef.await()
        stealer?.computerUsername?.let { names += AttributedValue(it, "Stealer Log") }
        stealer?.dateCompromised?.let {
            timeline += TimelineEvent(it, "Stealer Log", "HudsonRock")
        }
        stealer?.maskedIp?.let { ip ->
            resolveMaskedIpLocation(ip)?.let { geo ->
                locations += AttributedValue(geo, "HudsonRock IP ($ip)")
            }
        }

        // 11. SECOND-HOP PIVOT: Scrape discovered usernames (e.g., damonjess, @damonkirby89)
        // for profiles, bios, GitHub repo locations, and web mentions!
        val pivotHandles = usernames
            .map { it.value.removePrefix("@").trim() }
            .filter { isDistinctiveHandle(it) }
            .distinctBy { it.lowercase() }
            .take(5)

        if (pivotHandles.isNotEmpty()) {
            coroutineScope {
                val dossierJobs = pivotHandles.map { h ->
                    async { runCatching { DeepProfileScraper.scrapeUsernameDossier(h) }.getOrDefault(emptyList()) }
                }
                val pivotWebJob = async {
                    val q = pivotHandles.map { "\"$it\"" }
                    runCatching { OpenWebOsintScraper.scrapeAll(q) }.getOrNull()
                }
                val ghDeepJobs = pivotHandles.map { h ->
                    async { runCatching { scrapeGitHubUserReposForLocationAndPhone(h) }.getOrNull() }
                }

                dossierJobs.awaitAll().flatten().forEach { card ->
                    card.displayName?.let { names += AttributedValue(it, card.platform) }
                    card.location?.let { locations += AttributedValue(it, "${card.platform} (@${card.handle})") }
                    card.avatarUrl?.let { avatars += it }
                    card.discoveredPhones.forEach { p ->
                        phones += AttributedValue(p, "${card.platform} (@${card.handle})")
                    }
                    card.bio?.let { bio ->
                        phones += extractPhoneNumbersFromText(bio, "${card.platform} (@${card.handle})")
                        locations += extractLocationsFromText(bio, "${card.platform} (@${card.handle})")
                    }
                    links += AttributedProfileLink(card.handle, card.platform, card.profileUrl)
                }

                pivotWebJob.await()?.let { pw ->
                    phones += pw.extractedPhones
                    pw.results.forEach { item ->
                        val blob = "${item.title} ${item.snippet}"
                        phones += extractPhoneNumbersFromText(blob, item.source)
                        locations += extractLocationsFromText(blob, item.source)
                    }
                }

                ghDeepJobs.awaitAll().filterNotNull().forEach { (ghLocs, ghPhones) ->
                    locations += ghLocs
                    phones += ghPhones
                }
            }
        }

        // 12. Infer UK Area Locations from any discovered UK landline numbers
        phones.forEach { pv ->
            val norm = PhoneNumberUtils.normalize(pv.value)
            val area = PhoneNumberUtils.getAreaInfo(norm)
            if (!area.isNullOrBlank()) {
                locations += AttributedValue("$area, UK (from ${pv.value})", pv.source)
            }
        }

        val filteredNames = names
            .filterNot { it.value.startsWith("DESKTOP-", ignoreCase = true) }
            .distinctBy { "${it.value.lowercase()}|${it.source.lowercase()}" }

        val filteredPhones = phones
            .distinctBy { it.value.filter { c -> c.isDigit() || c == '*' }.ifEmpty { it.value.lowercase() } }

        val filteredLocations = locations
            .filter { it.value.length >= 2 }
            .distinctBy { it.value.lowercase().trim() }

        EmailAttributionReport(
            email = clean,
            namesFound = filteredNames,
            usernamesFound = usernames.distinctBy { it.value.removePrefix("@").lowercase() },
            profileLinks = links.distinctBy { it.url.lowercase() },
            locationsFound = filteredLocations,
            phonesFound = filteredPhones,
            avatarUrls = avatars.distinct(),
            timeline = timeline.sortedByDescending { it.date },
            stealerProfile = stealer
        )
    }

    /**
     * Resolves a HudsonRock masked IP (e.g. "81.104.***.***") by substituting ".1.1" for masked
     * octets in the /16 ISP block and querying ipwho.is for the ISP/country/region.
     */
    internal fun resolveMaskedIpLocation(maskedIp: String): String? {
        val parts = maskedIp.trim().split('.')
        if (parts.size != 4) return null
        val p0 = parts[0].toIntOrNull() ?: return null
        val p1 = parts[1].toIntOrNull() ?: return null
        val candidateIp = "$p0.$p1.${parts[2].toIntOrNull() ?: 1}.${parts[3].toIntOrNull() ?: 1}"

        val resp = ScanHttp.get("https://ipwho.is/$candidateIp", timeoutMs = 6_000, headers = mapOf("Accept" to "application/json"))
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val json = JSONObject(resp.body)
            if (!json.optBoolean("success", false)) return null
            val country = json.optString("country").takeIf { it.isNotBlank() } ?: return null
            val region = json.optString("region").takeIf { it.isNotBlank() }
            val isp = json.optJSONObject("connection")?.optString("isp")?.takeIf { it.isNotBlank() }
            buildString {
                if (region != null && !maskedIp.contains("*")) append("$region, ")
                append(country)
                if (isp != null) append(" (ISP block: $isp)")
            }
        }.getOrNull()
    }

    /**
     * Scans a discovered GitHub user's public repositories and recent events for location/region
     * names and phone numbers (e.g. regional UK data projects or contact info in repo descriptions).
     */
    private fun scrapeGitHubUserReposForLocationAndPhone(login: String): Pair<List<AttributedValue>, List<AttributedValue>> {
        val locs = mutableListOf<AttributedValue>()
        val phones = mutableListOf<AttributedValue>()
        val encoded = ScanHttp.encode(login)

        val reposResp = ScanHttp.get(
            "https://api.github.com/users/$encoded/repos?per_page=30&sort=updated",
            timeoutMs = 8_000,
            headers = GITHUB_HEADERS
        )
        if (reposResp.isOk && reposResp.body.isNotBlank()) {
            runCatching {
                val arr = JSONArray(reposResp.body)
                for (i in 0 until arr.length()) {
                    val repo = arr.optJSONObject(i) ?: continue
                    val name = repo.optString("name")
                    val desc = repo.optString("description").takeIf { it != "null" }.orEmpty()
                    val blob = "$name $desc"
                    locs += extractLocationsFromText(blob, "GitHub Repo ($login/$name)")
                    phones += extractPhoneNumbersFromText(blob, "GitHub Repo ($login/$name)")
                }
            }
        }
        return locs to phones
    }

    private fun lookupInstagramRecoveryPhone(email: String): AttributedValue? {
        val encoded = ScanHttp.encode(email)
        val resp = ScanHttp.post(
            "https://www.instagram.com/api/v1/users/lookup/",
            "q=$encoded",
            contentType = "application/x-www-form-urlencoded",
            timeoutMs = 8_000,
            headers = mapOf(
                "X-CSRFToken" to "missing",
                "X-IG-App-ID" to "936619743392459",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
            )
        )
        if (resp.isOk && resp.body.isNotBlank()) {
            runCatching {
                val json = JSONObject(resp.body)
                val phone = json.optString("obfuscated_phone").takeIf { it.isNotBlank() }
                    ?: json.optJSONObject("user")?.optString("obfuscated_phone")?.takeIf { it.isNotBlank() }
                if (phone != null) {
                    return AttributedValue(phone, "Instagram Recovery")
                }
            }
        }
        return null
    }

    private fun lookupMyFitnessPalHandle(handle: String): String? {
        if (handle.length < 3) return null
        val url = "https://www.myfitnesspal.com/profile/${ScanHttp.encode(handle)}"
        val resp = ScanHttp.get(url, timeoutMs = 8_000)
        if (resp.isOk && !resp.body.contains("This Username Is Invalid", true) &&
            resp.body.contains(handle, ignoreCase = true)
        ) {
            return handle
        }
        return null
    }

    private data class GitHubEmailMatch(
        val login: String,
        val fullName: String?,
        val profileUrl: String,
        val avatarUrl: String?,
        val createdAt: String?,
        val updatedAt: String?,
        val location: String? = null,
        val bio: String? = null,
        val inferredTimezoneLocation: String? = null
    )

    private fun lookupGitHubByEmail(email: String): GitHubEmailMatch? {
        val encoded = ScanHttp.encode(email)
        var login: String? = null
        var commitAuthorName: String? = null
        var commitTzLocation: String? = null

        val commitResp = ScanHttp.get(
            "https://api.github.com/search/commits?per_page=5&q=author-email:$encoded",
            timeoutMs = 12_000,
            headers = GITHUB_HEADERS
        )
        if (commitResp.isOk && commitResp.body.isNotBlank()) {
            runCatching {
                val items = JSONObject(commitResp.body).optJSONArray("items")
                if (items != null && items.length() > 0) {
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        if (login == null) {
                            login = item.optJSONObject("author")?.optString("login")?.takeIf { it.isNotBlank() }
                        }
                        val authorObj = item.optJSONObject("commit")?.optJSONObject("author")
                        val dateStr = authorObj?.optString("date").orEmpty()
                        if (commitTzLocation == null && dateStr.length >= 6) {
                            val tzOffset = dateStr.takeLast(6)
                            commitTzLocation = mapIsoOffsetToRegionHint(tzOffset)
                        }
                        val nameCandidate = authorObj?.optString("name")?.trim()
                        if (!nameCandidate.isNullOrBlank() && !nameCandidate.equals(login, ignoreCase = true)) {
                            commitAuthorName = nameCandidate
                            break
                        } else if (commitAuthorName == null && !nameCandidate.isNullOrBlank()) {
                            commitAuthorName = nameCandidate
                        }
                    }
                }
            }
        }

        if (login == null) {
            val userSearch = ScanHttp.get(
                "https://api.github.com/search/users?per_page=1&q=$encoded+in:email",
                timeoutMs = 10_000,
                headers = GITHUB_HEADERS
            )
            if (userSearch.isOk && userSearch.body.isNotBlank()) {
                runCatching {
                    val items = JSONObject(userSearch.body).optJSONArray("items")
                    if (items != null && items.length() > 0) {
                        login = items.optJSONObject(0)?.optString("login")?.takeIf { it.isNotBlank() }
                    }
                }
            }
        }

        val resolvedLogin = login ?: return null
        val profileResp = ScanHttp.get(
            "https://api.github.com/users/${ScanHttp.encode(resolvedLogin)}",
            timeoutMs = 10_000,
            headers = GITHUB_HEADERS
        )
        if (!profileResp.isOk || profileResp.body.isBlank()) {
            return GitHubEmailMatch(
                login = resolvedLogin,
                fullName = commitAuthorName,
                profileUrl = "https://github.com/$resolvedLogin",
                avatarUrl = null,
                createdAt = null,
                updatedAt = null,
                inferredTimezoneLocation = commitTzLocation
            )
        }

        return runCatching {
            val root = JSONObject(profileResp.body)
            val rawProfileName = root.optString("name").takeIf { it.isNotBlank() && it != "null" }
            val bestName = when {
                rawProfileName != null && ' ' in rawProfileName -> rawProfileName
                commitAuthorName != null && ' ' in commitAuthorName -> commitAuthorName
                else -> rawProfileName ?: commitAuthorName
            }
            GitHubEmailMatch(
                login = root.optString("login", resolvedLogin),
                fullName = bestName,
                profileUrl = root.optString("html_url", "https://github.com/$resolvedLogin"),
                avatarUrl = root.optString("avatar_url").takeIf { it.isNotBlank() },
                createdAt = root.optString("created_at").substringBefore('T').takeIf { it.isNotBlank() },
                updatedAt = root.optString("updated_at").substringBefore('T').takeIf { it.isNotBlank() },
                location = root.optString("location").takeIf { it.isNotBlank() && it != "null" },
                bio = root.optString("bio").takeIf { it.isNotBlank() && it != "null" },
                inferredTimezoneLocation = commitTzLocation
            )
        }.getOrNull()
    }

    private fun mapIsoOffsetToRegionHint(offset: String): String? = when (offset) {
        "+00:00", "-00:00" -> "UTC+00:00 (UK / Ireland / Portugal GMT)"
        "+01:00" -> "UTC+01:00 (UK BST / Western & Central Europe)"
        "+02:00" -> "UTC+02:00 (Eastern Europe / CEST)"
        "-04:00", "-05:00" -> "UTC$offset (US / Canada Eastern)"
        "-07:00", "-08:00" -> "UTC$offset (US / Canada Pacific)"
        else -> if (offset.startsWith("+") || offset.startsWith("-")) "Timezone UTC$offset" else null
    }

    private data class DuolingoEmailMatch(
        val username: String?,
        val fullName: String?,
        val avatarUrl: String?,
        val createdDate: String?,
        val country: String? = null
    )

    private fun lookupDuolingoByEmail(email: String): DuolingoEmailMatch? {
        val resp = ScanHttp.get(
            "https://www.duolingo.com/2017-06-30/users?email=${ScanHttp.encode(email)}",
            timeoutMs = 10_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val users = JSONObject(resp.body).optJSONArray("users") ?: return null
            val u = users.optJSONObject(0) ?: return null
            val username = u.optString("username").takeIf { it.isNotBlank() && it != "null" }
            val name = u.optString("name").takeIf { it.isNotBlank() && it != "null" }
            val picture = u.optString("picture").takeIf { it.isNotBlank() && it != "null" }
            val country = u.optString("learningLanguage").takeIf { it.isNotBlank() && it != "null" }?.let { "Duolingo lang: $it" }
            val creationTs = u.optLong("creationDate", 0L)
            val created = if (creationTs > 0L) {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(java.util.Date(creationTs * 1000L))
            } else null
            if (username == null && name == null) return null
            DuolingoEmailMatch(username, name, picture, created, country)
        }.getOrNull()
    }

    private data class GravatarEmailMatch(
        val displayName: String?,
        val username: String?,
        val profileUrl: String?,
        val avatarUrl: String?,
        val verifiedAccounts: List<AttributedProfileLink>,
        val location: String? = null,
        val aboutMe: String? = null
    )

    private fun lookupGravatarByEmail(email: String): GravatarEmailMatch? {
        val md5 = OsintDatasets.transformAccount(email, AccountMode.HASH_MD5)
        val resp = ScanHttp.get("https://en.gravatar.com/$md5.json", timeoutMs = 8_000)
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val entry = JSONObject(resp.body).optJSONArray("entry")?.optJSONObject(0) ?: return null
            val displayName = entry.optString("displayName").takeIf { it.isNotBlank() }
            val username = entry.optString("preferredUsername").takeIf { it.isNotBlank() }
            val profileUrl = entry.optString("profileUrl").takeIf { it.isNotBlank() }
            val thumbnail = entry.optString("thumbnailUrl").takeIf { it.isNotBlank() }
            val location = entry.optString("currentLocation").takeIf { it.isNotBlank() }
            val aboutMe = entry.optString("aboutMe").takeIf { it.isNotBlank() }
            val linked = mutableListOf<AttributedProfileLink>()
            val accounts = entry.optJSONArray("accounts")
            if (accounts != null) {
                for (i in 0 until accounts.length()) {
                    val acc = accounts.optJSONObject(i) ?: continue
                    val domain = acc.optString("shortname").takeIf { it.isNotBlank() } ?: "External"
                    val url = acc.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val handle = acc.optString("username").ifBlank { username ?: domain }
                    linked += AttributedProfileLink(handle, domain, url)
                }
            }
            GravatarEmailMatch(displayName, username, profileUrl, thumbnail, linked, location, aboutMe)
        }.getOrNull()
    }

    // Matches masked numbers ("+44 *******89", "07*** ****89"), international numbers,
    // spaced/unspaced UK mobiles ("07123 456789"), and UK landlines ("01724 123456", "020 7946 0958")
    private val MASKED_PHONE_REGEX = Regex("""(?:\+\d{1,4}[\s.-]*)?(?:[\*•xX]{2,6}[-\s.]*)+\d{2,4}""")
    private val INT_PHONE_REGEX = Regex("""\+\d{1,4}[-.\s]?\(?\d{1,4}\)?[-.\s]?\d{2,4}[-.\s]?\d{3,6}""")
    private val UK_MOBILE_REGEX = Regex("""\b07\d{3}[\s.-]?\d{3}[\s.-]?\d{3}\b|\b07\d{9}\b""")
    private val UK_LANDLINE_REGEX = Regex("""\b0(?:1\d{2,4}|2[03489]|3\d{2}|800)[\s.-]?\d{3,4}[\s.-]?\d{3,4}\b""")

    private val UK_LOCATION_KEYWORDS = Regex(
        """\b(Scunthorpe|North Lincolnshire|Lincolnshire|Humberside|Barton-upon-Humber|Grimsby|Hull|Doncaster|Sheffield|Leeds|Yorkshire|Manchester|London|Birmingham|Liverpool|Nottingham|Bristol|Newcastle|Glasgow|Edinburgh|Cardiff|Belfast|United Kingdom|England|Scotland|Wales)\b""",
        RegexOption.IGNORE_CASE
    )
    private val UK_POSTCODE_REGEX = Regex("""\b([A-Z]{1,2}\d[A-Z\d]?\s+\d[A-Z]{2})\b""")

    internal fun extractPhoneNumbersFromText(text: String, sourceName: String): List<AttributedValue> {
        val out = mutableListOf<AttributedValue>()
        MASKED_PHONE_REGEX.findAll(text).forEach { m ->
            val num = m.value.trim()
            if (num.length >= 6) out += AttributedValue(num, sourceName)
        }
        INT_PHONE_REGEX.findAll(text).forEach { m ->
            val num = m.value.trim()
            val digits = num.filter { it.isDigit() }
            if (digits.length in 10..15 && !num.contains("-202")) {
                out += AttributedValue(num, sourceName)
            }
        }
        UK_MOBILE_REGEX.findAll(text).forEach { m ->
            out += AttributedValue(m.value.trim(), sourceName)
        }
        UK_LANDLINE_REGEX.findAll(text).forEach { m ->
            val digits = m.value.filter { it.isDigit() }
            if (digits.length in 10..11) {
                out += AttributedValue(m.value.trim(), sourceName)
            }
        }
        return out
    }

    internal fun extractLocationsFromText(text: String, sourceName: String): List<AttributedValue> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<AttributedValue>()
        UK_LOCATION_KEYWORDS.findAll(text).forEach { m ->
            val loc = m.value.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            out += AttributedValue(loc, sourceName)
        }
        UK_POSTCODE_REGEX.findAll(text).forEach { m ->
            out += AttributedValue("UK Postcode: ${m.value.trim()}", sourceName)
        }
        return out
    }

    internal fun extractNameFromPgpUid(uid: String): String? {
        val beforeAngle = uid.substringBefore('<').trim()
        if (beforeAngle.isNotBlank() && ' ' in beforeAngle && !beforeAngle.contains('@')) {
            return beforeAngle
        }
        return null
    }

    private fun isDistinctiveHandle(handle: String): Boolean {
        val value = handle.trim().lowercase()
        if (value.length < 4) return false
        if (value.all { it.isDigit() } || value.all { it in "._-+" }) return false
        return value !in setOf(
            "info", "admin", "support", "contact", "sales", "hello", "office", "mail",
            "email", "team", "help", "billing", "accounts", "noreply", "no-reply", "postmaster"
        )
    }

    private data class HandleProfile(
        val platform: String,
        val username: String,
        val profileUrl: String,
        val name: String?,
        val location: String?,
        val avatarUrl: String?,
        val joined: String?,
        val lastActive: String?
    )

    private fun lookupProfilesByHandle(handle: String): List<HandleProfile> {
        val encoded = ScanHttp.encode(handle)
        val profiles = mutableListOf<HandleProfile>()

        val github = ScanHttp.get(
            "https://api.github.com/users/$encoded",
            timeoutMs = 8_000,
            headers = GITHUB_HEADERS
        )
        if (github.isOk && github.body.isNotBlank()) {
            runCatching {
                val root = JSONObject(github.body)
                val login = root.optString("login").takeIf { it.isNotBlank() } ?: return@runCatching
                profiles += HandleProfile(
                    platform = "GitHub",
                    username = login,
                    profileUrl = root.optString("html_url", "https://github.com/$login"),
                    name = root.optString("name").takeIf { it.isNotBlank() && it != "null" },
                    location = root.optString("location").takeIf { it.isNotBlank() && it != "null" },
                    avatarUrl = root.optString("avatar_url").takeIf { it.isNotBlank() },
                    joined = root.optString("created_at").substringBefore('T').takeIf { it.isNotBlank() },
                    lastActive = root.optString("updated_at").substringBefore('T').takeIf { it.isNotBlank() }
                )
            }
        }

        val keybase = lookupKeybase("username=$encoded")
        keybase?.let { kb ->
            profiles += HandleProfile(
                platform = "Keybase",
                username = kb.username,
                profileUrl = kb.profileUrl,
                name = kb.fullName,
                location = kb.location,
                avatarUrl = kb.avatarUrl,
                joined = null,
                lastActive = null
            )
        }

        return profiles
    }

    private fun lookupHudsonRockProfile(email: String): StealerSubjectProfile? {
        val resp = ScanHttp.get(
            "https://cavalier.hudsonrock.com/api/json/v2/osint-tools/search-by-email?email=${ScanHttp.encode(email)}",
            timeoutMs = 10_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val stealers = JSONObject(resp.body).optJSONArray("stealers") ?: return null
            val s = stealers.optJSONObject(0) ?: return null
            val user = s.optString("username").takeIf { it.isNotBlank() && it != "null" }
                ?: s.optString("computer_name").takeIf { it.isNotBlank() && it != "null" }
            val ip = s.optString("ip").takeIf { it.isNotBlank() && it != "null" }
            val os = s.optString("operating_system").takeIf { it.isNotBlank() && it != "null" }
            val date = s.optString("date_compromised").substringBefore('T').takeIf { it.isNotBlank() }
            val avArr = s.optJSONArray("antiviruses")
            val avs = if (avArr != null) {
                (0 until avArr.length()).mapNotNull { avArr.optString(it).takeIf { a -> a.isNotBlank() } }
            } else emptyList()
            StealerSubjectProfile(user, ip, os, date, avs)
        }.getOrNull()
    }

    private data class KeybaseEmailMatch(
        val username: String,
        val fullName: String?,
        val profileUrl: String,
        val avatarUrl: String?,
        val bio: String?,
        val proofs: List<AttributedProfileLink>,
        val location: String? = null
    )

    private fun lookupKeybase(query: String): KeybaseEmailMatch? {
        val url = "https://keybase.io/_/api/1.0/user/lookup.json?$query"
        val resp = ScanHttp.get(url, timeoutMs = 8_000, headers = mapOf("Accept" to "application/json"))
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val root = JSONObject(resp.body)
            val them = root.optJSONArray("them") ?: return null
            if (them.length() == 0) return null
            val user = them.optJSONObject(0) ?: return null
            val basics = user.optJSONObject("basics") ?: return null
            val username = basics.optString("username").takeIf { it.isNotBlank() } ?: return null
            val profile = user.optJSONObject("profile")
            val fullName = profile?.optString("full_name")?.takeIf { it.isNotBlank() }
            val bio = profile?.optString("bio")?.takeIf { it.isNotBlank() }
            val location = profile?.optString("location")?.takeIf { it.isNotBlank() }
            val avatar = user.optJSONObject("pictures")?.optJSONObject("primary")?.optString("url")?.takeIf { it.isNotBlank() }

            val proofLinks = mutableListOf<AttributedProfileLink>()
            proofLinks += AttributedProfileLink(username, "Keybase", "https://keybase.io/$username")

            val proofsSummary = user.optJSONObject("proofs_summary")
            val allProofs = proofsSummary?.optJSONArray("all")
            if (allProofs != null) {
                for (i in 0 until allProofs.length()) {
                    val p = allProofs.optJSONObject(i) ?: continue
                    val type = p.optString("proof_type").takeIf { it.isNotBlank() } ?: continue
                    val valName = p.optString("nametag").takeIf { it.isNotBlank() } ?: continue
                    val pUrl = p.optString("service_url").takeIf { it.isNotBlank() }
                        ?: "https://keybase.io/$username"
                    proofLinks += AttributedProfileLink(valName, "Keybase Proof ($type)", pUrl)
                }
            }
            KeybaseEmailMatch(username, fullName, "https://keybase.io/$username", avatar, bio, proofLinks, location)
        }.getOrNull()
    }

    private data class OpenPgpEmailMatch(
        val email: String,
        val keyFound: Boolean,
        val profileUrl: String
    )

    private fun lookupOpenPgpKeysByEmail(email: String): OpenPgpEmailMatch? {
        val url = "https://keys.openpgp.org/vks/v1/by-email/${ScanHttp.encode(email)}"
        val resp = ScanHttp.get(url, timeoutMs = 8_000)
        if (resp.isOk && resp.body.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----")) {
            return OpenPgpEmailMatch(email, true, "https://keys.openpgp.org/search?q=${ScanHttp.encode(email)}")
        }
        return null
    }
}
