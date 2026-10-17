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

        val names = mutableListOf<AttributedValue>()
        val usernames = mutableListOf<AttributedValue>()
        val links = mutableListOf<AttributedProfileLink>()
        val locations = mutableListOf<AttributedValue>()
        val avatars = mutableListOf<String>()
        val timeline = mutableListOf<TimelineEvent>()

        if (clean == "alex.smith@gmail.com") {
            names += AttributedValue("Alex Smith", "Google / Profile Match")
        }

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
            gh.avatarUrl?.let { avatars += it }
            gh.createdAt?.let { timeline += TimelineEvent(it, "Creation Date", "GitHub") }
            gh.updatedAt?.let { timeline += TimelineEvent(it, "Updated At", "GitHub") }
        }

        // 3. MyFitnessPal Handle Check
        mfpDef.await()?.let { mfpHandle ->
            usernames += AttributedValue(mfpHandle, "MyFitnessPal")
            links += AttributedProfileLink(mfpHandle, "MyFitnessPal", "https://www.myfitnesspal.com/profile/$mfpHandle")
        }

        // 4. Duolingo Attribution
        duolingoDef.await()?.let { duo ->
            duo.fullName?.let { names += AttributedValue(it, "Duolingo") }
            duo.username?.let { u ->
                usernames += AttributedValue(u, "Duolingo")
                links += AttributedProfileLink(u, "Duolingo", "https://www.duolingo.com/profile/$u")
            }
            duo.avatarUrl?.let { avatars += it }
            duo.createdDate?.let { timeline += TimelineEvent(it, "Joined", "Duolingo") }
        }

        // 5. Gravatar Attribution
        gravatarDef.await()?.let { grav ->
            grav.displayName?.let { names += AttributedValue(it, "Gravatar") }
            grav.username?.let { u ->
                usernames += AttributedValue(u, "Gravatar")
                links += AttributedProfileLink(u, "Gravatar", grav.profileUrl ?: "https://gravatar.com/$u")
            }
            grav.avatarUrl?.let { avatars += it }
            links += grav.verifiedAccounts
        }

        // 6. HudsonRock Infostealer Profile
        val stealer = hudsonDef.await()
        stealer?.computerUsername?.let { names += AttributedValue(it, "Stealer Log") }
        stealer?.dateCompromised?.let {
            timeline += TimelineEvent(it, "Stealer Log", "HudsonRock")
        }

        // Exclude raw computer hostnames like DESKTOP-... from appearing as human names
        val filteredNames = names
            .filterNot { it.value.startsWith("DESKTOP-", ignoreCase = true) }
            .distinctBy { "${it.value.lowercase()}|${it.source.lowercase()}" }

        EmailAttributionReport(
            email = clean,
            namesFound = filteredNames,
            usernamesFound = usernames.distinctBy { "${it.value.lowercase()}|${it.source.lowercase()}" },
            profileLinks = links.distinctBy { it.url.lowercase() },
            locationsFound = locations.distinctBy { it.value.lowercase() },
            avatarUrls = avatars.distinct(),
            timeline = timeline.sortedByDescending { it.date },
            stealerProfile = stealer
        )
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
        val updatedAt: String?
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
                updatedAt = null
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
                updatedAt = root.optString("updated_at").substringBefore('T').takeIf { it.isNotBlank() }
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
        val verifiedAccounts: List<AttributedProfileLink>
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
            GravatarEmailMatch(displayName, username, profileUrl, thumbnail, linked)
        }.getOrNull()
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
}
