package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
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

        val googleDef = async { GoogleMetadataExtractor.extract(clean) }
        val githubDef = async { lookupGitHubByEmail(clean) }
        val duolingoDef = async { lookupDuolingoByEmail(clean) }
        val gravatarDef = async { lookupGravatarByEmail(clean) }
        val hudsonDef = async { lookupHudsonRockProfile(clean) }
        val mfpDef = async { lookupMyFitnessPalHandle(clean.substringBefore('@')) }
        val keybaseDef = async { lookupKeybase("email=${ScanHttp.encode(clean)}") }
        val gitlabDef = async { lookupGitLabByEmail(clean) }
        val openPgpDef = async { lookupOpenPgpKeysByEmail(clean) }
        val pgpKeysDef = async { IdentityEnricher.fetchPgpKeys(clean) }
        val webSearchDef = async { SearchRepository.searchWeb(listOf(clean)) }
        val instaPhoneDef = async { lookupInstagramRecoveryPhone(clean) }

        // The local part of the address is the handle most services expose publicly. When it is
        // distinctive enough to be a real handle, ask the free profile APIs for that handle so the
        // report can show a name, photo or location even when the email itself is not indexed.
        val handle = clean.substringBefore('@')
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
            gh.bio?.let { bio -> phones += extractPhoneNumbersFromText(bio, "GitHub Bio") }
            gh.avatarUrl?.let { avatars += it }
            gh.createdAt?.let { timeline += TimelineEvent(it, "Creation Date", "GitHub") }
            gh.updatedAt?.let { timeline += TimelineEvent(it, "Updated At", "GitHub") }
        }

        // 3. Keybase Attribution
        keybaseDef.await()?.let { kb ->
            kb.fullName?.let { names += AttributedValue(it, "Keybase") }
            usernames += AttributedValue(kb.username, "Keybase")
            kb.location?.let { locations += AttributedValue(it, "Keybase") }
            kb.bio?.let { bio -> phones += extractPhoneNumbersFromText(bio, "Keybase Bio") }
            kb.avatarUrl?.let { avatars += it }
            links += kb.proofs
        }

        // 4. GitLab Attribution
        gitlabDef.await()?.let { gl ->
            gl.fullName?.let { names += AttributedValue(it, "GitLab") }
            usernames += AttributedValue(gl.username, "GitLab")
            links += AttributedProfileLink(gl.username, "GitLab", gl.profileUrl)
            gl.avatarUrl?.let { avatars += it }
            gl.createdAt?.let { timeline += TimelineEvent(it, "Account Created", "GitLab") }
        }

        // 5. OpenPGP Keyserver Attribution
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

        // 6. MyFitnessPal Handle Check
        mfpDef.await()?.let { mfpHandle ->
            usernames += AttributedValue(mfpHandle, "MyFitnessPal")
            links += AttributedProfileLink(mfpHandle, "MyFitnessPal", "https://www.myfitnesspal.com/profile/$mfpHandle")
        }

        // 7. Duolingo Attribution
        duolingoDef.await()?.let { duo ->
            duo.fullName?.let { names += AttributedValue(it, "Duolingo") }
            duo.username?.let { u ->
                usernames += AttributedValue(u, "Duolingo")
                links += AttributedProfileLink(u, "Duolingo", "https://www.duolingo.com/profile/$u")
            }
            duo.avatarUrl?.let { avatars += it }
            duo.createdDate?.let { timeline += TimelineEvent(it, "Joined", "Duolingo") }
        }

        // 8. Gravatar Attribution
        gravatarDef.await()?.let { grav ->
            grav.displayName?.let { names += AttributedValue(it, "Gravatar") }
            grav.username?.let { u ->
                usernames += AttributedValue(u, "Gravatar")
                links += AttributedProfileLink(u, "Gravatar", grav.profileUrl ?: "https://gravatar.com/$u")
            }
            grav.location?.let { locations += AttributedValue(it, "Gravatar") }
            grav.aboutMe?.let { bio -> phones += extractPhoneNumbersFromText(bio, "Gravatar Bio") }
            grav.avatarUrl?.let { avatars += it }
            links += grav.verifiedAccounts
        }

        // 9. Public Web Search Results for Email
        runCatching { webSearchDef.await() }.getOrNull()?.results.orEmpty().forEach { result ->
            phones += extractPhoneNumbersFromText("${result.title} ${result.snippet}", "Web Search")
        }

        // 10. Public profiles for the email's handle
        handleDef?.await().orEmpty().forEach { profile ->
            profile.name?.let { names += AttributedValue(it, profile.platform) }
            usernames += AttributedValue(profile.username, profile.platform)
            links += AttributedProfileLink(profile.username, profile.platform, profile.profileUrl)
            profile.location?.let { locations += AttributedValue(it, profile.platform) }
            profile.avatarUrl?.let { avatars += it }
            profile.joined?.let { timeline += TimelineEvent(it, "Joined", profile.platform) }
            profile.lastActive?.let { timeline += TimelineEvent(it, "Last Active", profile.platform) }
        }

        // 11. HudsonRock Infostealer Profile
        val stealer = hudsonDef.await()
        stealer?.computerUsername?.let { names += AttributedValue(it, "Stealer Log") }
        stealer?.dateCompromised?.let {
            timeline += TimelineEvent(it, "Stealer Log", "HudsonRock")
        }

        // Exclude raw computer hostnames like DESKTOP-... from appearing as human names
        val filteredNames = names
            .filterNot { it.value.startsWith("DESKTOP-", ignoreCase = true) }
            .distinctBy { "${it.value.lowercase()}|${it.source.lowercase()}" }

        val filteredPhones = phones
            .distinctBy { "${it.value.lowercase()}|${it.source.lowercase()}" }

        EmailAttributionReport(
            email = clean,
            namesFound = filteredNames,
            usernamesFound = usernames.distinctBy { "${it.value.lowercase()}|${it.source.lowercase()}" },
            profileLinks = links.distinctBy { it.url.lowercase() },
            locationsFound = locations.distinctBy { it.value.lowercase() },
            phonesFound = filteredPhones,
            avatarUrls = avatars.distinct(),
            timeline = timeline.sortedByDescending { it.date },
            stealerProfile = stealer
        )
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
                    return AttributedValue(phone, "Instagram")
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
        val bio: String? = null
    )

    private fun lookupGitHubByEmail(email: String): GitHubEmailMatch? {
        val encoded = ScanHttp.encode(email)
        var login: String? = null
        var commitAuthorName: String? = null

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
                        val nameCandidate = item.optJSONObject("commit")
                            ?.optJSONObject("author")
                            ?.optString("name")
                            ?.trim()
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
                location = null,
                bio = null
            )
        }

        return runCatching {
            val root = JSONObject(profileResp.body)
            val rawProfileName = root.optString("name").takeIf { it.isNotBlank() && it != "null" }
            // Prefer a human full name with a space over a repeated username
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
                bio = root.optString("bio").takeIf { it.isNotBlank() && it != "null" }
            )
        }.getOrNull()
    }

    private data class DuolingoEmailMatch(
        val username: String?,
        val fullName: String?,
        val avatarUrl: String?,
        val createdDate: String?
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
            val creationTs = u.optLong("creationDate", 0L)
            val created = if (creationTs > 0L) {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(java.util.Date(creationTs * 1000L))
            } else null
            if (username == null && name == null) return null
            DuolingoEmailMatch(username, name, picture, created)
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

    private val MASKED_PHONE_REGEX = Regex("""(?:\+\d{1,4}\s*)?(?:\*{2,5}[-\s.]*)+\d{2,4}""")
    private val INT_PHONE_REGEX = Regex("""\+\d{1,4}[-.\s]?\(?\d{1,4}\)?[-.\s]?\d{1,4}[-.\s]?\d{1,9}""")
    private val UK_MOBILE_REGEX = Regex("""\b07\d{9}\b""")

    internal fun extractPhoneNumbersFromText(text: String, sourceName: String): List<AttributedValue> {
        val out = mutableListOf<AttributedValue>()
        MASKED_PHONE_REGEX.findAll(text).forEach { m ->
            val num = m.value.trim()
            if (num.length >= 6) out += AttributedValue(num, sourceName)
        }
        INT_PHONE_REGEX.findAll(text).forEach { m ->
            val num = m.value.trim()
            if (num.length >= 8 && !num.contains("-202") && !num.contains("-2023") && !num.contains("-2024") && !num.contains("-2025")) {
                out += AttributedValue(num, sourceName)
            }
        }
        UK_MOBILE_REGEX.findAll(text).forEach { m ->
            out += AttributedValue(m.value.trim(), sourceName)
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

    /**
     * Handles that belong to a company or a role ("info", "support", "admin") identify nobody,
     * so they are skipped rather than attaching a stranger's profile to the address.
     */
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

    /** Free public profile APIs that accept a bare username and return identity fields. */
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

    /** [query] is either `email=...` or `username=...`, already URL-encoded. */
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

    private data class GitLabEmailMatch(
        val username: String,
        val fullName: String?,
        val profileUrl: String,
        val avatarUrl: String?,
        val createdAt: String?
    )

    private fun lookupGitLabByEmail(email: String): GitLabEmailMatch? {
        val url = "https://gitlab.com/api/v4/users?search=${ScanHttp.encode(email)}"
        val resp = ScanHttp.get(url, timeoutMs = 8_000, headers = mapOf("Accept" to "application/json"))
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val arr = org.json.JSONArray(resp.body)
            if (arr.length() == 0) return null
            val u = arr.optJSONObject(0) ?: return null
            val username = u.optString("username").takeIf { it.isNotBlank() } ?: return null
            val name = u.optString("name").takeIf { it.isNotBlank() }
            val webUrl = u.optString("web_url").takeIf { it.isNotBlank() } ?: "https://gitlab.com/$username"
            val avatar = u.optString("avatar_url").takeIf { it.isNotBlank() }
            val created = u.optString("created_at").substringBefore('T').takeIf { it.isNotBlank() }
            GitLabEmailMatch(username, name, webUrl, avatar, created)
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
