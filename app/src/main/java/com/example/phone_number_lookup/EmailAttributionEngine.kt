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
    val antiviruses: List<String> = emptyList(),
    val allUsernames: List<String> = emptyList(),
    val allMaskedIps: List<String> = emptyList(),
    val allDates: List<String> = emptyList()
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
        // Generate likely handle variants (e.g., john.smith89 -> johnsmith89, john_smith89, johnsmith)
        val handleVariants = buildList {
            add(handle)
            addAll(UsernameVariants.fromHandle(handle))
            val strippedDigits = handle.replace(Regex("\\d+$"), "").trim('.', '_', '-')
            if (strippedDigits.length >= 5 && strippedDigits != handle) {
                add(strippedDigits)
            }
        }.filter { isDistinctiveHandle(it) }.distinctBy { it.lowercase() }.take(3)

        // STAGE 1: Fast primary identity & account lookups
        val githubDef = async { lookupGitHubByEmail(clean) }
        val duolingoDef = async { lookupDuolingoByEmail(clean) }
        val gravatarDef = async { lookupGravatarByEmail(clean) }
        val hudsonDef = async { lookupHudsonRockProfile(clean) }
        val mfpDef = async { lookupMyFitnessPalHandle(handle) }
        val keybaseDef = async { lookupKeybase("email=${ScanHttp.encode(clean)}") }
        val openPgpDef = async { lookupOpenPgpKeysByEmail(clean) }
        val pgpKeysDef = async { IdentityEnricher.fetchPgpKeys(clean) }
        val protonPksDef = async { lookupProtonMailKeyCreation(clean) }
        val npmEmailDef = async { lookupNpmByEmail(clean) }
        val webSearchDef = async {
            OpenWebOsintScraper.scrapeAll(listOf(clean, handle))
        }
        val instaPhoneDef = async { lookupInstagramRecoveryPhone(clean) }
        val handleDef = if (handleVariants.isNotEmpty()) {
            async { handleVariants.flatMap { lookupProfilesByHandle(it) }.distinctBy { "${it.platform}|${it.username.lowercase()}" } }
        } else null

        val names = mutableListOf<AttributedValue>()
        val usernames = mutableListOf<AttributedValue>()
        val links = mutableListOf<AttributedProfileLink>()
        val locations = mutableListOf<AttributedValue>()
        val phones = mutableListOf<AttributedValue>()
        val avatars = mutableListOf<String>()
        val timeline = mutableListOf<TimelineEvent>()

        instaPhoneDef.await()?.let { phones += it }

        // 1. GitHub Attribution (Author/Committer Email + Public Push Events)
        githubDef.await()?.let { gh ->
            gh.fullName?.let { names += AttributedValue(it, "GitHub") }
            gh.extraNames.forEach { n -> names += AttributedValue(n, "GitHub Commits") }
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

        // 2. Keybase Attribution
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

        // 3. OpenPGP & ProtonMail Keyserver Attribution
        openPgpDef.await()?.let { pgp ->
            links += AttributedProfileLink(pgp.email, "OpenPGP Key", pgp.profileUrl)
            timeline += TimelineEvent("Public Key Verified", "OpenPGP Key Found", "OpenPGP Keyserver")
        }
        runCatching { pgpKeysDef.await() }.getOrNull()?.forEach { record ->
            record.createdDate?.let { timeline += TimelineEvent(it, "PGP Key Created", "Ubuntu OpenPGP") }
            record.uids.forEach { uid ->
                extractNameFromPgpUid(uid)?.let { name ->
                    names += AttributedValue(name, "OpenPGP Key")
                }
            }
        }
        protonPksDef.await()?.let { (createdDate, uidName) ->
            createdDate?.let { timeline += TimelineEvent(it, "ProtonMail Encryption Key Created", "ProtonMail PKS") }
            uidName?.let { names += AttributedValue(it, "ProtonMail PKS") }
        }

        // 4. npm Maintainer Attribution
        npmEmailDef.await().forEach { npm ->
            usernames += AttributedValue(npm.username, "npm Registry")
            links += AttributedProfileLink(npm.username, "npm (${npm.packageName})", "https://www.npmjs.com/~${npm.username}")
            npm.authorName?.let { names += AttributedValue(it, "npm Registry") }
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
            locations += webReport.extractedLocations
            webReport.results.forEach { result ->
                val blob = "${result.title} ${result.snippet}"
                phones += extractPhoneNumbersFromText(blob, result.source)
                locations += extractLocationsFromText(blob, result.source)
            }
        }

        // 9. Public profiles for the email's handle & variants
        handleDef?.await().orEmpty().forEach { profile ->
            profile.name?.let { names += AttributedValue(it, profile.platform) }
            usernames += AttributedValue(profile.username, profile.platform)
            links += AttributedProfileLink(profile.username, profile.platform, profile.profileUrl)
            profile.location?.let { locations += AttributedValue(it, profile.platform) }
            profile.avatarUrl?.let { avatars += it }
            profile.joined?.let { timeline += TimelineEvent(it, "Joined", profile.platform) }
            profile.lastActive?.let { timeline += TimelineEvent(it, "Last Active", profile.platform) }
        }

        // 10. HudsonRock Infostealer Profile (All stealer entries + Masked IP Geolocation)
        val stealer = hudsonDef.await()
        if (stealer != null) {
            stealer.allUsernames.forEach { u ->
                if (!u.startsWith("DESKTOP-", ignoreCase = true) && !u.startsWith("LAPTOP-", ignoreCase = true)) {
                    usernames += AttributedValue(u, "Stealer Log")
                }
            }
            stealer.allDates.forEach { d ->
                timeline += TimelineEvent(d, "Compromised in Stealer Log", "HudsonRock")
            }
            stealer.allMaskedIps.forEach { ip ->
                resolveMaskedIpLocation(ip)?.let { geo ->
                    locations += AttributedValue(geo, "Stealer Log ($ip)")
                }
            }
        }

        // 11. SECOND-HOP PIVOT: Scrape discovered usernames for profiles, bios, repos & locations
        val pivotHandles = (usernames.map { it.value.removePrefix("@").trim() } + handleVariants)
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
                    locations += pw.extractedLocations
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

        // STAGE 2: Run GoogleMetadataExtractor WITH the discovered real names & locations!
        val discoveredRealNames = names.map { it.value }.filter { ' ' in it && it.length >= 5 }.distinct()
        val discoveredLocations = locations.map { it.value }.distinct()
        val google = runCatching {
            GoogleMetadataExtractor.extract(
                email = clean,
                hintNames = discoveredRealNames,
                hintLocations = discoveredLocations
            )
        }.getOrNull()

        if (google != null) {
            google.fullName?.let { names += AttributedValue(it, "Google") }
            google.playGamesUsername?.let { usernames += AttributedValue(it, "Google Play Games") }
            google.profileUrl?.let { url ->
                links += AttributedProfileLink(google.fullName ?: "Google Profile", "Google Maps", url)
            }
            google.avatarUrl?.let { avatars += it }
            google.mappedLocations.forEach { loc ->
                locations += AttributedValue(loc, "Google Maps")
            }
            google.reviews.forEach { rev ->
                rev.timestampIso?.let { iso ->
                    timeline += TimelineEvent(iso, "Reviewed ${rev.title} (${rev.ratingLabel})", "Google Maps")
                }
            }
            google.lastSeenDate?.let {
                timeline += TimelineEvent(it, "Last Active", "Google / Maps")
            }
            google.lastLevelUpDate?.let {
                timeline += TimelineEvent(it, "Last Level Up", "Google Play Games")
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
            .filterNot { it.value.startsWith("DESKTOP-", ignoreCase = true) || it.value.startsWith("LAPTOP-", ignoreCase = true) }
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

    private data class NpmEmailHit(val username: String, val authorName: String?, val packageName: String)

    private fun lookupNpmByEmail(email: String): List<NpmEmailHit> {
        val url = "https://registry.npmjs.org/-/v1/search?text=${ScanHttp.encode(email)}&size=5"
        val resp = ScanHttp.get(url, timeoutMs = 8_000, headers = mapOf("Accept" to "application/json"))
        if (!resp.isOk || resp.body.isBlank()) return emptyList()
        return runCatching {
            val objects = JSONObject(resp.body).optJSONArray("objects") ?: return emptyList()
            val hits = mutableListOf<NpmEmailHit>()
            for (i in 0 until objects.length()) {
                val pkg = objects.optJSONObject(i)?.optJSONObject("package") ?: continue
                val pkgName = pkg.optString("name")
                val authorName = pkg.optJSONObject("author")?.optString("name")?.takeIf { it.isNotBlank() }
                val maintainers = pkg.optJSONArray("maintainers") ?: continue
                for (j in 0 until maintainers.length()) {
                    val m = maintainers.optJSONObject(j) ?: continue
                    if (m.optString("email").equals(email, ignoreCase = true)) {
                        val user = m.optString("username").takeIf { it.isNotBlank() } ?: continue
                        hits += NpmEmailHit(user, authorName, pkgName)
                    }
                }
            }
            hits.distinctBy { it.username.lowercase() }
        }.getOrDefault(emptyList())
    }

    /**
     * Queries ProtonMail's public keyserver (`api.protonmail.ch/pks/lookup?op=index&search=`)
     * to extract the exact PGP key creation date and display name for ProtonMail/custom Proton addresses.
     */
    private fun lookupProtonMailKeyCreation(email: String): Pair<String?, String?>? {
        val url = "https://api.protonmail.ch/pks/lookup?op=index&search=${ScanHttp.encode(email)}"
        val resp = ScanHttp.get(url, timeoutMs = 8_000)
        if (!resp.isOk || !resp.body.contains("pub:")) return null
        val records = IdentityEnricher.parsePgpMachineReadable(resp.body)
        val first = records.firstOrNull() ?: return null
        val uidName = first.uids.firstNotNullOfOrNull { extractNameFromPgpUid(it) }
        return first.createdDate to uidName
    }

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
            val region = json.optString("region").takeIf { it.isNotBlank() }?.replace(Regex("""\s*\[.*?\]"""), "")?.trim()
            val city = json.optString("city").takeIf { it.isNotBlank() }
            buildString {
                if (city != null) append("$city, ")
                if (region != null && region != city) append("$region, ")
                append(country)
            }
        }.getOrNull()
    }

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
        val inferredTimezoneLocation: String? = null,
        val extraNames: List<String> = emptyList()
    )

    private fun lookupGitHubByEmail(email: String): GitHubEmailMatch? {
        val encoded = ScanHttp.encode(email)
        var login: String? = null
        var commitAuthorName: String? = null
        var commitTzLocation: String? = null
        val extraNames = LinkedHashSet<String>()

        // Search both author-email and committer-email
        for (qualifier in listOf("author-email", "committer-email")) {
            val commitResp = ScanHttp.get(
                "https://api.github.com/search/commits?per_page=5&q=$qualifier:$encoded",
                timeoutMs = 10_000,
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
                                    ?: item.optJSONObject("committer")?.optString("login")?.takeIf { it.isNotBlank() }
                            }
                            val authorObj = item.optJSONObject("commit")?.optJSONObject("author")
                            val dateStr = authorObj?.optString("date").orEmpty()
                            if (commitTzLocation == null && dateStr.length >= 6) {
                                commitTzLocation = mapIsoOffsetToRegionHint(dateStr.takeLast(6))
                            }
                            val nameCandidate = authorObj?.optString("name")?.trim()
                            if (!nameCandidate.isNullOrBlank() && !nameCandidate.equals(login, ignoreCase = true)) {
                                if (commitAuthorName == null || (' ' in nameCandidate && ' ' !in commitAuthorName)) {
                                    commitAuthorName = nameCandidate
                                } else {
                                    extraNames += nameCandidate
                                }
                            } else if (commitAuthorName == null && !nameCandidate.isNullOrBlank()) {
                                commitAuthorName = nameCandidate
                            }
                        }
                    }
                }
            }
            if (login != null) break
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

        // Also inspect public push events to extract real commit author names
        val eventsResp = ScanHttp.get(
            "https://api.github.com/users/${ScanHttp.encode(resolvedLogin)}/events/public?per_page=30",
            timeoutMs = 8_000,
            headers = GITHUB_HEADERS
        )
        if (eventsResp.isOk && eventsResp.body.isNotBlank()) {
            runCatching {
                GitHubOsint.extractCommitIdentities(JSONArray(eventsResp.body)).forEach { id ->
                    val namePart = id.substringBefore('<').trim()
                    if (namePart.isNotBlank() && !namePart.equals(resolvedLogin, ignoreCase = true)) {
                        extraNames += namePart
                    }
                }
            }
        }

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
                inferredTimezoneLocation = commitTzLocation,
                extraNames = extraNames.toList()
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
                inferredTimezoneLocation = commitTzLocation,
                extraNames = extraNames.toList()
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

    private val MASKED_PHONE_REGEX = Regex("""(?:\+\d{1,4}[\s.-]*)?(?:[\*•xX]{2,6}[-\s.]*)+\d{2,4}""")
    private val INT_PHONE_REGEX = Regex("""\+\d{1,4}[-.\s]?\(?\d{1,4}\)?[-.\s]?\d{2,4}[-.\s]?\d{3,6}""")
    private val UK_MOBILE_REGEX = Regex("""\b07\d{3}[\s.-]?\d{3}[\s.-]?\d{3}\b|\b07\d{9}\b""")
    private val UK_LANDLINE_REGEX = Regex("""\b0(?:1\d{2,4}|2[03489]|3\d{2}|800)[\s.-]?\d{3,4}[\s.-]?\d{3,4}\b""")

    private val UK_LOCATION_KEYWORDS = Regex(
        """\b(Scunthorpe|North Lincolnshire|Lincolnshire|Humberside|Barton-upon-Humber|Grimsby|Hull|Cottingham|Doncaster|Sheffield|Leeds|Yorkshire|Manchester|London|Birmingham|Liverpool|Nottingham|Bristol|Newcastle|Glasgow|Edinburgh|Cardiff|Belfast|United Kingdom|England|Scotland|Wales)\b""",
        RegexOption.IGNORE_CASE
    )
    private val UK_POSTCODE_REGEX = Regex("""\b([A-Z]{1,2}\d[A-Z\d]?\s+\d[A-Z]{2})\b""")
    private val FULL_UK_ADDRESS_REGEX = Regex(
        """\b(\d+[\w\s]+(?:Rd|Road|St|Street|Ave|Avenue|Lane|Way|Close|Drive|Dr|Court|Ct|High St),?[\w\s,]+(?:[A-Z]{1,2}\d[A-Z\d]?\s+\d[A-Z]{2})(?:,\s*(?:United Kingdom|UK))?)\b""",
        RegexOption.IGNORE_CASE
    )
    private val CITY_REGION_COUNTRY_REGEX = Regex(
        """\b([A-Z][a-z]+(?:[\s-][A-Z][a-z]+)*,\s*(?:England|Scotland|Wales|Northern Ireland|Yorkshire|Humberside|Lincolnshire),\s*(?:United Kingdom|UK))\b""",
        RegexOption.IGNORE_CASE
    )

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
        FULL_UK_ADDRESS_REGEX.findAll(text).forEach { m ->
            out += AttributedValue(m.value.trim().replace(Regex("\\s+"), " "), sourceName)
        }
        CITY_REGION_COUNTRY_REGEX.findAll(text).forEach { m ->
            out += AttributedValue(m.value.trim().replace(Regex("\\s+"), " "), sourceName)
        }
        UK_LOCATION_KEYWORDS.findAll(text).forEach { m ->
            val loc = m.value.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            if (out.none { it.value.contains(loc, ignoreCase = true) }) {
                out += AttributedValue(loc, sourceName)
            }
        }
        UK_POSTCODE_REGEX.findAll(text).forEach { m ->
            val pc = m.value.trim()
            if (out.none { it.value.contains(pc, ignoreCase = true) }) {
                out += AttributedValue("UK Postcode: $pc", sourceName)
            }
        }
        return out
    }

    internal fun extractNameTokensFromIdentifier(handle: String): List<String> {
        val clean = handle.trim().replace(Regex("\\d+$"), "").replace(Regex("[-._]+"), " ")
        val tokens = clean.split(' ').map { it.trim().lowercase() }.filter { it.length >= 3 }
        if (tokens.size == 1) {
            val single = tokens[0]
            val commonNames = listOf("alex", "john", "smith", "david", "james", "michael", "robert", "william", "chris")
            val matches = mutableListOf<String>()
            var rem = single
            for (cn in commonNames) {
                if (rem.startsWith(cn)) {
                    matches += cn.replaceFirstChar { it.titlecase() }
                    rem = rem.substring(cn.length)
                } else if (rem.endsWith(cn)) {
                    val prefix = rem.substring(0, rem.length - cn.length)
                    if (prefix.length >= 3) {
                        matches += prefix.replaceFirstChar { it.titlecase() }
                    }
                    matches += cn.replaceFirstChar { it.titlecase() }
                    break
                }
            }
            if (matches.isNotEmpty()) return matches
        }
        return tokens.map { it.replaceFirstChar { c -> c.titlecase() } }
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
            if (stealers.length() == 0) return null

            val allUsers = LinkedHashSet<String>()
            val allIps = LinkedHashSet<String>()
            val allDates = LinkedHashSet<String>()
            val allAvs = LinkedHashSet<String>()
            var primaryOs: String? = null

            for (i in 0 until stealers.length()) {
                val s = stealers.optJSONObject(i) ?: continue
                s.optString("username").takeIf { it.isNotBlank() && it != "null" }?.let { allUsers += it }
                s.optString("computer_name").takeIf { it.isNotBlank() && it != "null" }?.let { allUsers += it }
                s.optString("ip").takeIf { it.isNotBlank() && it != "null" }?.let { allIps += it }
                s.optString("date_compromised").substringBefore('T').takeIf { it.isNotBlank() }?.let { allDates += it }
                if (primaryOs == null) {
                    primaryOs = s.optString("operating_system").takeIf { it.isNotBlank() && it != "null" }
                }
                val avArr = s.optJSONArray("antiviruses")
                if (avArr != null) {
                    for (j in 0 until avArr.length()) {
                        avArr.optString(j).takeIf { a -> a.isNotBlank() }?.let { allAvs += it }
                    }
                }
            }

            StealerSubjectProfile(
                computerUsername = allUsers.firstOrNull(),
                maskedIp = allIps.firstOrNull(),
                operatingSystem = primaryOs,
                dateCompromised = allDates.firstOrNull(),
                antiviruses = allAvs.toList(),
                allUsernames = allUsers.toList(),
                allMaskedIps = allIps.toList(),
                allDates = allDates.toList()
            )
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
