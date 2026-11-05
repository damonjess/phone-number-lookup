package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class EnrichedProfileCard(
    val platform: String,
    val handle: String,
    val profileUrl: String,
    val displayName: String? = null,
    val bio: String? = null,
    val location: String? = null,
    val externalLinks: List<String> = emptyList(),
    val discoveredEmails: List<String> = emptyList(),
    val discoveredPhones: List<String> = emptyList(),
    val avatarUrl: String? = null,
    val createdDate: String? = null,
    val statsSummary: String? = null
)

object DeepProfileScraper {

    private val OG_TITLE = Regex("""<meta[^>]+(?:property|name)=["'](?:og:title|twitter:title)["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val OG_DESC = Regex("""<meta[^>]+(?:property|name)=["'](?:og:description|twitter:description|description)["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val OG_IMAGE = Regex("""<meta[^>]+(?:property|name)=["'](?:og:image|twitter:image)["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val MAILTO_REGEX = Regex("""mailto:([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,12})""", RegexOption.IGNORE_CASE)
    private val EMAIL_REGEX = Regex("""[a-zA-Z0-9._%+-]{2,64}@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,12}""")
    private val SOCIAL_LINK_REGEX = Regex("""https?://(?:www\.)?(?:github\.com|x\.com|twitter\.com|linkedin\.com/in|instagram\.com|t\.me|youtube\.com/@|tiktok\.com/@|bsky\.app/profile|mastodon\.social/@)[a-zA-Z0-9_./@-]+""", RegexOption.IGNORE_CASE)

    // Platform-owned social handles that must NEVER be extracted as a user's personal links
    private val PLATFORM_BOILERPLATE_HANDLES = setOf(
        "discord", "twitch", "soundcloud", "tiktok", "youtube", "reddit", "github",
        "duolingo", "snapchat", "pinterest", "linktree", "roblox", "steam", "vimeo",
        "dailymotion", "medium", "strava", "chess", "lichess", "docker", "gitlab",
        "share", "intent", "sharer", "login", "signup", "privacy", "terms", "explore"
    )

    // Generic soft-404 descriptions returned by platforms when an account does not exist or has no bio
    private val GENERIC_SITE_BIOS = listOf(
        "twitch is the world's leading video platform",
        "learn a language for free",
        "play ",
        " and discover followers on soundcloud",
        "listen to songs, albums",
        "join the conversation",
        "couldn't find this account"
    )

    suspend fun scrapeUsernameDossier(
        username: String,
        confirmedHits: List<UsernameHit> = emptyList()
    ): List<EnrichedProfileCard> = withContext(Dispatchers.IO) {
        val clean = username.trim().removePrefix("@")
        if (clean.isBlank()) return@withContext emptyList()

        coroutineScope {
            val apiJobs = listOf(
                async { fetchGitHubDeep(clean) },
                async { fetchTikTokOembed(clean) },
                async { fetchYouTubeOembed(clean) },
                async { fetchSoundCloudOembed(clean) },
                async { fetchTelegramPublic(clean) },
                async { fetchSteamXml(clean) },
                async { fetchDuolingoUser(clean) },
                async { fetchGravatarUser(clean) },
                async { fetchRobloxUser(clean) },
                async { fetchHuggingFaceUser(clean) },
                async { fetchRubyGemsUser(clean) },
                async { fetchCratesIoUser(clean) },
                async { fetchVimeoUser(clean) },
                async { fetchDailymotionUser(clean) },
                async { fetchScratchUser(clean) },
                async { fetchMinecraftUser(clean) },
                async { fetchReddit(clean) },
                async { fetchBluesky(clean) },
                async { fetchMastodon(clean) },
                async { fetchLichess(clean) },
                async { fetchChessCom(clean) },
                async { fetchDevTo(clean) },
                async { fetchHackerNews(clean) },
                async { fetchDockerHub(clean) },
                async { fetchNpmMaintainer(clean) },
                async { fetchWikipediaGlobal(clean) },
                async { fetchStackOverflow(clean) }
            )

            val effectiveHits = if (confirmedHits.isNotEmpty()) {
                confirmedHits
            } else {
                val probeSem = Semaphore(6)
                CuratedUsernameChecks.PROBES.map { probe ->
                    async {
                        probeSem.withPermit {
                            val res = OsintDatasets.evaluate(probe, clean, timeoutMs = 8_000)
                            if (res.verdict == ProbeVerdict.FOUND) {
                                UsernameHit(
                                    site = probe.name,
                                    category = probe.category,
                                    source = probe.source,
                                    profileUrl = OsintDatasets.cleanProfileUrl(probe.name, res.profileUrl),
                                    statusCode = res.statusCode,
                                    finalUrl = res.finalUrl,
                                    evidence = res.evidence,
                                    confidence = "High",
                                    verificationStatus = "Matched",
                                    queriedName = clean
                                )
                            } else null
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            val apiResults = apiJobs.awaitAll().filterNotNull()
            val coveredPlatforms = apiResults.map { it.platform.lowercase() }.toSet()

            val htmlSemaphore = Semaphore(4)
            val htmlJobs = effectiveHits
                .filter {
                    it.verificationStatus == "Matched" &&
                        it.profileUrl.startsWith("http") &&
                        it.site.lowercase() !in coveredPlatforms
                }
                .take(14)
                .map { hit ->
                    async {
                        htmlSemaphore.withPermit {
                            scrapeHtmlProfileMetadata(hit.site, clean, hit.profileUrl)
                                ?: EnrichedProfileCard(
                                    platform = hit.site,
                                    handle = clean,
                                    profileUrl = hit.profileUrl,
                                    bio = "Confirmed public profile on ${hit.site} (${hit.category})"
                                )
                        }
                    }
                }

            (apiResults + htmlJobs.awaitAll().filterNotNull())
                .distinctBy { "${it.platform.lowercase()}|${it.profileUrl.lowercase().trimEnd('/')}" }
        }
    }

    /**
     * Cleans a scraped display name and returns ONLY genuine human names (rejecting page titles
     * like "Twitch", "Duolingo - Learn a language...", "Damon Kirby on Snapchat", or bare usernames).
     */
    internal fun cleanHumanDisplayName(raw: String?, handle: String, platform: String): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim()
            .replace(Regex("""\s*[-|•·]\s*(?:Twitch|Duolingo|Snapchat|SoundCloud|TikTok|YouTube|Reddit|GitHub|Instagram|Twitter|X).*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+on\s+(?:Snapchat|TikTok|Instagram|Twitter|X|Twitch|SoundCloud).*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(@[^)]+\)"""), "")
            .trim()

        val lower = cleaned.lowercase()
        if (lower.isBlank() ||
            lower == handle.lowercase() ||
            lower == platform.lowercase() ||
            lower in PLATFORM_BOILERPLATE_HANDLES ||
            lower.contains("learn a language") ||
            lower.contains("stream ") ||
            lower.contains("world's leading") ||
            lower.contains("creator profile") ||
            lower.contains("http") ||
            '@' in lower
        ) {
            return null
        }
        return cleaned.takeIf { it.length in 3..50 }
    }

    private fun fetchTikTokOembed(u: String): EnrichedProfileCard? {
        val profileUrl = "https://www.tiktok.com/@$u"
        val resp = ScanHttp.get(
            "https://www.tiktok.com/oembed?url=${ScanHttp.encode(profileUrl)}",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val root = JSONObject(resp.body)
            val authorName = root.optString("author_name").takeIf { it.isNotBlank() } ?: return null
            val title = root.optString("title").takeIf { it.isNotBlank() && !it.endsWith("Creator Profile", true) }
            val thumb = root.optString("thumbnail_url").takeIf { it.startsWith("http") }
            EnrichedProfileCard(
                platform = "TikTok",
                handle = u,
                profileUrl = profileUrl,
                displayName = cleanHumanDisplayName(authorName, u, "TikTok"),
                bio = title,
                avatarUrl = thumb
            )
        }.getOrNull()
    }

    private fun fetchYouTubeOembed(u: String): EnrichedProfileCard? {
        val profileUrl = "https://www.youtube.com/@$u"
        val resp = ScanHttp.get(
            "https://www.youtube.com/oembed?url=${ScanHttp.encode(profileUrl)}&format=json",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val root = JSONObject(resp.body)
            val authorName = root.optString("author_name").takeIf { it.isNotBlank() } ?: return null
            val authorUrl = root.optString("author_url").takeIf { it.startsWith("http") } ?: profileUrl
            val thumb = root.optString("thumbnail_url").takeIf { it.startsWith("http") }
            EnrichedProfileCard(
                platform = "YouTube",
                handle = u,
                profileUrl = authorUrl,
                displayName = cleanHumanDisplayName(authorName, u, "YouTube"),
                bio = root.optString("title").takeIf { it.isNotBlank() },
                avatarUrl = thumb
            )
        }.getOrNull()
    }

    private fun fetchSoundCloudOembed(u: String): EnrichedProfileCard? {
        val profileUrl = "https://soundcloud.com/$u"
        val resp = ScanHttp.get(
            "https://soundcloud.com/oembed?url=${ScanHttp.encode(profileUrl)}&format=json",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val root = JSONObject(resp.body)
            val author = root.optString("author_name").takeIf { it.isNotBlank() } ?: return null
            val rawDesc = root.optString("description").takeIf { it.isNotBlank() }
            val cleanDesc = rawDesc?.takeUnless { d -> GENERIC_SITE_BIOS.any { d.lowercase().contains(it) } }
            val thumb = root.optString("thumbnail_url").takeIf { it.startsWith("http") }
            EnrichedProfileCard(
                platform = "SoundCloud",
                handle = u,
                profileUrl = profileUrl,
                displayName = cleanHumanDisplayName(author, u, "SoundCloud"),
                bio = cleanDesc,
                avatarUrl = thumb
            )
        }.getOrNull()
    }

    private fun fetchTelegramPublic(u: String): EnrichedProfileCard? {
        val url = "https://t.me/$u"
        val resp = ScanHttp.get(url, timeoutMs = 8_000)
        if (!resp.isOk || resp.body.isBlank() || resp.body.contains("tgme_page_icon_user")) return null
        val html = resp.body
        val title = Regex("""<div[^>]*class=["'][^"']*tgme_page_title[^"']*["'][^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { BreachCatalog.plainText(it) }?.takeIf { it.isNotBlank() }
            ?: return null
        val desc = Regex("""<div[^>]*class=["'][^"']*tgme_page_description[^"']*["'][^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { BreachCatalog.plainText(it) }?.takeIf { it.isNotBlank() }
        val extra = Regex("""<div[^>]*class=["'][^"']*tgme_page_extra[^"']*["'][^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { BreachCatalog.plainText(it) }?.takeIf { it.isNotBlank() }
        val img = OG_IMAGE.find(html)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }

        return EnrichedProfileCard(
            platform = "Telegram",
            handle = u,
            profileUrl = url,
            displayName = cleanHumanDisplayName(title, u, "Telegram"),
            bio = desc,
            discoveredEmails = extractEmails(desc.orEmpty()),
            discoveredPhones = EmailAttributionEngine.extractPhoneNumbersFromText(desc.orEmpty(), "Telegram").map { it.value },
            avatarUrl = img,
            statsSummary = extra
        )
    }

    private fun fetchSteamXml(u: String): EnrichedProfileCard? {
        val url = "https://steamcommunity.com/id/${ScanHttp.encode(u)}?xml=1"
        val resp = ScanHttp.get(url, timeoutMs = 8_000)
        if (!resp.isOk || !resp.body.contains("<steamID64>")) return null
        val xml = resp.body
        val steamId = Regex("""<steamID><!\[CDATA\[(.*?)]]></steamID>""").find(xml)?.groupValues?.get(1)
            ?: Regex("""<steamID>(.*?)</steamID>""").find(xml)?.groupValues?.get(1)
        val realName = Regex("""<realname><!\[CDATA\[(.*?)]]></realname>""").find(xml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val location = Regex("""<location><!\[CDATA\[(.*?)]]></location>""").find(xml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: Regex("""<location>(.*?)</location>""").find(xml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val summary = Regex("""<summary><!\[CDATA\[(.*?)]]></summary>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.let { BreachCatalog.plainText(it) }?.takeIf { it.isNotBlank() }
        val avatar = Regex("""<avatarFull><!\[CDATA\[(.*?)]]></avatarFull>""").find(xml)?.groupValues?.get(1)
        val memberSince = Regex("""<memberSince>(.*?)</memberSince>""").find(xml)?.groupValues?.get(1)

        return EnrichedProfileCard(
            platform = "Steam",
            handle = u,
            profileUrl = "https://steamcommunity.com/id/$u",
            displayName = cleanHumanDisplayName(realName ?: steamId, u, "Steam"),
            bio = summary?.take(260),
            location = location,
            avatarUrl = avatar,
            createdDate = memberSince
        )
    }

    private fun fetchDuolingoUser(u: String): EnrichedProfileCard? {
        val resp = ScanHttp.get(
            "https://www.duolingo.com/2017-06-30/users?username=${ScanHttp.encode(u)}",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val user = JSONObject(resp.body).optJSONArray("users")?.optJSONObject(0) ?: return null
            val username = user.optString("username").takeIf { it.isNotBlank() } ?: return null
            if (!username.equals(u, ignoreCase = true)) return null
            val name = user.optString("name").takeIf { it.isNotBlank() && it != "null" }
            val streak = user.optInt("streak", 0)
            val totalXp = user.optInt("totalXp", 0)
            val createdSec = user.optLong("creationDate", 0L)
            EnrichedProfileCard(
                platform = "Duolingo",
                handle = username,
                profileUrl = "https://www.duolingo.com/profile/$username",
                displayName = cleanHumanDisplayName(name, username, "Duolingo"),
                createdDate = if (createdSec > 0) formatEpochSeconds(createdSec) else null,
                statsSummary = "$totalXp XP • $streak day streak"
            )
        }.getOrNull()
    }

    private fun fetchGravatarUser(u: String): EnrichedProfileCard? {
        val resp = ScanHttp.get(
            "https://en.gravatar.com/${ScanHttp.encode(u)}.json",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val entry = JSONObject(resp.body).optJSONArray("entry")?.optJSONObject(0) ?: return null
            val pref = entry.optString("preferredUsername").ifBlank { u }
            val display = entry.optString("displayName").takeIf { it.isNotBlank() }
            val about = entry.optString("aboutMe").takeIf { it.isNotBlank() }
            val loc = entry.optString("currentLocation").takeIf { it.isNotBlank() }
            val thumb = entry.optString("thumbnailUrl").takeIf { it.startsWith("http") }
            EnrichedProfileCard(
                platform = "Gravatar",
                handle = pref,
                profileUrl = entry.optString("profileUrl", "https://gravatar.com/$pref"),
                displayName = cleanHumanDisplayName(display, pref, "Gravatar"),
                bio = about,
                location = loc,
                avatarUrl = thumb
            )
        }.getOrNull()
    }

    private fun fetchRobloxUser(u: String): EnrichedProfileCard? {
        val resp = ScanHttp.post(
            "https://users.roblox.com/v1/usernames/users",
            """{"usernames":["$u"],"excludeBannedUsers":false}""",
            contentType = "application/json",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val data = JSONObject(resp.body).optJSONArray("data")?.optJSONObject(0) ?: return null
            val id = data.optLong("id", -1L)
            if (id <= 0L) return null
            val name = data.optString("name", u)
            val display = data.optString("displayName").takeIf { it.isNotBlank() }
            EnrichedProfileCard(
                platform = "Roblox",
                handle = name,
                profileUrl = "https://www.roblox.com/users/$id/profile",
                displayName = cleanHumanDisplayName(display, name, "Roblox"),
                statsSummary = "Roblox User ID: $id"
            )
        }.getOrNull()
    }

    private fun fetchHuggingFaceUser(u: String): EnrichedProfileCard? {
        val resp = ScanHttp.get(
            "https://huggingface.co/api/users/${ScanHttp.encode(u)}/overview",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val root = JSONObject(resp.body)
            val user = root.optString("user").ifBlank { u }
            val fullname = root.optString("fullname").takeIf { it.isNotBlank() && it != "null" }
            val bio = root.optString("details").takeIf { it.isNotBlank() && it != "null" }
            val avatar = root.optString("avatarUrl").takeIf { it.startsWith("http") }
            val models = root.optInt("numModels", 0)
            val datasets = root.optInt("numDatasets", 0)
            val spaces = root.optInt("numSpaces", 0)
            if (fullname == null && bio == null && models == 0 && datasets == 0 && spaces == 0) return null
            EnrichedProfileCard(
                platform = "HuggingFace",
                handle = user,
                profileUrl = "https://huggingface.co/$user",
                displayName = cleanHumanDisplayName(fullname, user, "HuggingFace"),
                bio = bio,
                avatarUrl = avatar,
                statsSummary = "$models models • $datasets datasets • $spaces spaces"
            )
        }.getOrNull()
    }

    private fun fetchRubyGemsUser(u: String): EnrichedProfileCard? {
        val resp = ScanHttp.get(
            "https://rubygems.org/api/v1/owners/${ScanHttp.encode(u)}/gems.json",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank() || !resp.body.trim().startsWith("[")) return null
        return runCatching {
            val arr = org.json.JSONArray(resp.body)
            val gemNames = (0 until minOf(arr.length(), 6)).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
            }
            EnrichedProfileCard(
                platform = "RubyGems",
                handle = u,
                profileUrl = "https://rubygems.org/profiles/$u",
                bio = if (gemNames.isNotEmpty()) "Gems: ${gemNames.joinToString(", ")}" else "Registered RubyGems profile",
                statsSummary = "${arr.length()} published gem(s)"
            )
        }.getOrNull()
    }

    private fun fetchCratesIoUser(u: String): EnrichedProfileCard? {
        val resp = ScanHttp.get(
            "https://crates.io/api/v1/users/${ScanHttp.encode(u)}",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val user = JSONObject(resp.body).optJSONObject("user") ?: return null
            val login = user.optString("login").takeIf { it.isNotBlank() } ?: return null
            val name = user.optString("name").takeIf { it.isNotBlank() && it != "null" }
            val avatar = user.optString("avatar").takeIf { it.startsWith("http") }
            EnrichedProfileCard(
                platform = "Crates.io (Rust)",
                handle = login,
                profileUrl = user.optString("url", "https://crates.io/users/$login"),
                displayName = cleanHumanDisplayName(name, login, "Crates.io"),
                avatarUrl = avatar
            )
        }.getOrNull()
    }

    private fun fetchVimeoUser(u: String): EnrichedProfileCard? {
        val resp = ScanHttp.get("https://vimeo.com/api/v2/${ScanHttp.encode(u)}/info.json", timeoutMs = 8_000)
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val root = JSONObject(resp.body)
            val name = root.optString("display_name").takeIf { it.isNotBlank() } ?: return null
            val bio = BreachCatalog.plainText(root.optString("bio")).takeIf { it.isNotBlank() }
            val loc = root.optString("location").takeIf { it.isNotBlank() }
            val created = root.optString("created_on").substringBefore(' ').takeIf { it.isNotBlank() }
            val thumb = root.optString("portrait_large").takeIf { it.startsWith("http") }
            EnrichedProfileCard(
                platform = "Vimeo",
                handle = u,
                profileUrl = root.optString("profile_url", "https://vimeo.com/$u"),
                displayName = cleanHumanDisplayName(name, u, "Vimeo"),
                bio = bio,
                location = loc,
                avatarUrl = thumb,
                createdDate = created
            )
        }.getOrNull()
    }

    private fun fetchDailymotionUser(u: String): EnrichedProfileCard? {
        val resp = ScanHttp.get(
            "https://api.dailymotion.com/user/${ScanHttp.encode(u)}?fields=username,screenname,description,created_time,avatar_120_url",
            timeoutMs = 8_000
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val root = JSONObject(resp.body)
            if (root.has("error")) return null
            val screen = root.optString("screenname").takeIf { it.isNotBlank() } ?: return null
            val desc = BreachCatalog.plainText(root.optString("description")).takeIf { it.isNotBlank() }
            val created = root.optLong("created_time", 0L)
            EnrichedProfileCard(
                platform = "Dailymotion",
                handle = root.optString("username", u),
                profileUrl = "https://www.dailymotion.com/$u",
                displayName = cleanHumanDisplayName(screen, u, "Dailymotion"),
                bio = desc,
                avatarUrl = root.optString("avatar_120_url").takeIf { it.startsWith("http") },
                createdDate = if (created > 0) formatEpochSeconds(created) else null
            )
        }.getOrNull()
    }

    private fun fetchScratchUser(u: String): EnrichedProfileCard? {
        val resp = ScanHttp.get("https://api.scratch.mit.edu/users/${ScanHttp.encode(u)}", timeoutMs = 8_000)
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val root = JSONObject(resp.body)
            val username = root.optString("username").takeIf { it.isNotBlank() } ?: return null
            val profile = root.optJSONObject("profile")
            val bio = profile?.optString("bio")?.takeIf { it.isNotBlank() }
            val country = profile?.optString("country")?.takeIf { it.isNotBlank() }
            val joined = root.optJSONObject("history")?.optString("joined")?.substringBefore('T')
            EnrichedProfileCard(
                platform = "Scratch",
                handle = username,
                profileUrl = "https://scratch.mit.edu/users/$username",
                bio = bio,
                location = country,
                createdDate = joined
            )
        }.getOrNull()
    }

    private fun fetchMinecraftUser(u: String): EnrichedProfileCard? {
        val resp = ScanHttp.get("https://api.mojang.com/users/profiles/minecraft/${ScanHttp.encode(u)}", timeoutMs = 8_000)
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val root = JSONObject(resp.body)
            val id = root.optString("id").takeIf { it.isNotBlank() } ?: return null
            val name = root.optString("name", u)
            EnrichedProfileCard(
                platform = "Minecraft (Mojang)",
                handle = name,
                profileUrl = "https://namemc.com/profile/$name",
                avatarUrl = "https://mc-heads.net/avatar/$id/100",
                statsSummary = "UUID: $id"
            )
        }.getOrNull()
    }

    private suspend fun fetchGitHubDeep(u: String): EnrichedProfileCard? {
        val gh = GitHubOsint.getUser(u) ?: return null
        val emails = buildList {
            gh.email?.let { add(it) }
            gh.commitIdentities.forEach { id ->
                val e = id.substringAfter('<', "").substringBefore('>', "")
                if (e.isNotBlank()) add(e)
            }
        }.distinct()
        return EnrichedProfileCard(
            platform = "GitHub",
            handle = gh.login,
            profileUrl = gh.htmlUrl,
            displayName = cleanHumanDisplayName(gh.name, gh.login, "GitHub"),
            bio = gh.bio,
            location = gh.location,
            externalLinks = listOfNotNull(gh.blog, gh.twitterUsername?.let { "https://x.com/$it" }),
            discoveredEmails = emails,
            avatarUrl = gh.avatarUrl,
            createdDate = gh.createdAt?.substringBefore('T'),
            statsSummary = listOfNotNull(
                gh.publicRepos?.let { "$it repos" },
                gh.followers?.let { "$it followers" },
                gh.company?.let { "Org: $it" }
            ).joinToString(" • ")
        )
    }

    private fun fetchReddit(u: String): EnrichedProfileCard? {
        val resp = ScanHttp.get(
            "https://www.reddit.com/user/${ScanHttp.encode(u)}/about.json",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val data = JSONObject(resp.body).optJSONObject("data") ?: return null
            val name = data.optString("name").ifBlank { return null }
            val sub = data.optJSONObject("subreddit")
            val displayName = sub?.optString("title")?.takeIf { it.isNotBlank() }
            val bio = sub?.optString("public_description")?.takeIf { it.isNotBlank() }
            val karma = data.optInt("total_karma", 0)
            val createdUtc = data.optLong("created_utc", 0L)
            val created = if (createdUtc > 0) formatEpochSeconds(createdUtc) else null
            val icon = data.optString("icon_img").substringBefore('?').takeIf { it.startsWith("http") }
            EnrichedProfileCard(
                platform = "Reddit",
                handle = name,
                profileUrl = "https://www.reddit.com/user/$name",
                displayName = cleanHumanDisplayName(displayName, name, "Reddit"),
                bio = bio,
                discoveredEmails = extractEmails(bio.orEmpty()),
                discoveredPhones = EmailAttributionEngine.extractPhoneNumbersFromText(bio.orEmpty(), "Reddit").map { it.value },
                avatarUrl = icon,
                createdDate = created,
                statsSummary = "$karma total karma"
            )
        }.getOrNull()
    }

    private fun fetchBluesky(u: String): EnrichedProfileCard? {
        val actor = if ('.' in u) u else "$u.bsky.social"
        val resp = ScanHttp.get(
            "https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=${ScanHttp.encode(actor)}",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val root = JSONObject(resp.body)
            val handle = root.optString("handle").ifBlank { return null }
            val desc = root.optString("description").takeIf { it.isNotBlank() }
            EnrichedProfileCard(
                platform = "Bluesky",
                handle = handle,
                profileUrl = "https://bsky.app/profile/$handle",
                displayName = cleanHumanDisplayName(root.optString("displayName"), u, "Bluesky"),
                bio = desc,
                externalLinks = extractSocialLinks(desc.orEmpty()),
                discoveredEmails = extractEmails(desc.orEmpty()),
                avatarUrl = root.optString("avatar").takeIf { it.startsWith("http") },
                createdDate = root.optString("createdAt").substringBefore('T').takeIf { it.isNotBlank() },
                statsSummary = "${root.optInt("followersCount", 0)} followers • ${root.optInt("postsCount", 0)} posts"
            )
        }.getOrNull()
    }

    private fun fetchMastodon(u: String): EnrichedProfileCard? {
        val resp = ScanHttp.get(
            "https://mastodon.social/api/v1/accounts/lookup?acct=${ScanHttp.encode(u)}",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val root = JSONObject(resp.body)
            val acct = root.optString("acct").ifBlank { return null }
            val rawNote = root.optString("note")
            val bio = BreachCatalog.plainText(rawNote).takeIf { it.isNotBlank() }
            val fields = root.optJSONArray("fields")
            val links = mutableListOf<String>()
            if (fields != null) {
                for (i in 0 until fields.length()) {
                    val f = fields.optJSONObject(i) ?: continue
                    val v = BreachCatalog.plainText(f.optString("value"))
                    if (v.isNotBlank()) links += "${f.optString("name")}: $v"
                }
            }
            EnrichedProfileCard(
                platform = "Mastodon",
                handle = acct,
                profileUrl = root.optString("url", "https://mastodon.social/@$acct"),
                displayName = cleanHumanDisplayName(root.optString("display_name"), acct, "Mastodon"),
                bio = bio,
                externalLinks = links,
                discoveredEmails = extractEmails(bio.orEmpty()),
                avatarUrl = root.optString("avatar").takeIf { it.startsWith("http") },
                createdDate = root.optString("created_at").substringBefore('T').takeIf { it.isNotBlank() },
                statsSummary = "${root.optInt("followers_count", 0)} followers • ${root.optInt("statuses_count", 0)} posts"
            )
        }.getOrNull()
    }

    private fun fetchLichess(u: String): EnrichedProfileCard? {
        val resp = ScanHttp.get(
            "https://lichess.org/api/user/${ScanHttp.encode(u)}",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val root = JSONObject(resp.body)
            val id = root.optString("username").ifBlank { return null }
            val profile = root.optJSONObject("profile")
            val firstName = profile?.optString("firstName").orEmpty()
            val lastName = profile?.optString("lastName").orEmpty()
            val realName = "$firstName $lastName".trim().ifBlank { profile?.optString("realName").orEmpty() }.takeIf { it.isNotBlank() }
            val bio = profile?.optString("bio")?.takeIf { it.isNotBlank() }
            val location = listOfNotNull(
                profile?.optString("location")?.takeIf { it.isNotBlank() },
                profile?.optString("country")?.takeIf { it.isNotBlank() }
            ).joinToString(", ").takeIf { it.isNotBlank() }
            val links = profile?.optString("links")?.lines()?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
            val createdMs = root.optLong("createdAt", 0L)
            EnrichedProfileCard(
                platform = "Lichess",
                handle = id,
                profileUrl = root.optString("url", "https://lichess.org/@/$id"),
                displayName = cleanHumanDisplayName(realName, id, "Lichess"),
                bio = bio,
                location = location,
                externalLinks = links,
                createdDate = if (createdMs > 0) formatEpochSeconds(createdMs / 1000) else null
            )
        }.getOrNull()
    }

    private fun fetchChessCom(u: String): EnrichedProfileCard? {
        val resp = ScanHttp.get(
            "https://api.chess.com/pub/player/${ScanHttp.encode(u)}",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val root = JSONObject(resp.body)
            val username = root.optString("username").ifBlank { return null }
            val joined = root.optLong("joined", 0L)
            EnrichedProfileCard(
                platform = "Chess.com",
                handle = username,
                profileUrl = root.optString("url", "https://www.chess.com/member/$username"),
                displayName = cleanHumanDisplayName(root.optString("name"), username, "Chess.com"),
                location = root.optString("location").takeIf { it.isNotBlank() },
                avatarUrl = root.optString("avatar").takeIf { it.startsWith("http") },
                createdDate = if (joined > 0) formatEpochSeconds(joined) else null,
                statsSummary = "${root.optInt("followers", 0)} followers"
            )
        }.getOrNull()
    }

    private fun fetchDevTo(u: String): EnrichedProfileCard? {
        val resp = ScanHttp.get(
            "https://dev.to/api/users/by_username?url=${ScanHttp.encode(u)}",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val root = JSONObject(resp.body)
            val username = root.optString("username").ifBlank { return null }
            val links = buildList {
                root.optString("website_url").takeIf { it.isNotBlank() && it != "null" }?.let { add(it) }
                root.optString("github_username").takeIf { it.isNotBlank() && it != "null" }?.let { add("https://github.com/$it") }
                root.optString("twitter_username").takeIf { it.isNotBlank() && it != "null" }?.let { add("https://x.com/$it") }
            }
            EnrichedProfileCard(
                platform = "DEV Community",
                handle = username,
                profileUrl = "https://dev.to/$username",
                displayName = cleanHumanDisplayName(root.optString("name"), username, "DEV Community"),
                bio = root.optString("summary").takeIf { it.isNotBlank() && it != "null" },
                location = root.optString("location").takeIf { it.isNotBlank() && it != "null" },
                externalLinks = links,
                avatarUrl = root.optString("profile_image").takeIf { it.startsWith("http") },
                createdDate = root.optString("joined_at").takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }

    private fun fetchHackerNews(u: String): EnrichedProfileCard? {
        val resp = ScanHttp.get(
            "https://hacker-news.firebaseio.com/v0/user/${ScanHttp.encode(u)}.json",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank() || resp.body.trim() == "null") return null
        return runCatching {
            val root = JSONObject(resp.body)
            val id = root.optString("id").ifBlank { return null }
            val about = BreachCatalog.plainText(root.optString("about")).takeIf { it.isNotBlank() }
            val created = root.optLong("created", 0L)
            EnrichedProfileCard(
                platform = "HackerNews",
                handle = id,
                profileUrl = "https://news.ycombinator.com/user?id=$id",
                bio = about,
                externalLinks = extractSocialLinks(about.orEmpty()),
                discoveredEmails = extractEmails(about.orEmpty()),
                createdDate = if (created > 0) formatEpochSeconds(created) else null,
                statsSummary = "${root.optInt("karma", 0)} karma"
            )
        }.getOrNull()
    }

    private fun fetchDockerHub(u: String): EnrichedProfileCard? {
        val resp = ScanHttp.get(
            "https://hub.docker.com/v2/users/${ScanHttp.encode(u)}/",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val root = JSONObject(resp.body)
            val username = root.optString("username").ifBlank { return null }
            val company = root.optString("company").takeIf { it.isNotBlank() }
            EnrichedProfileCard(
                platform = "Docker Hub",
                handle = username,
                profileUrl = "https://hub.docker.com/u/$username",
                displayName = cleanHumanDisplayName(root.optString("full_name"), username, "Docker Hub"),
                location = root.optString("location").takeIf { it.isNotBlank() },
                externalLinks = listOfNotNull(root.optString("profile_url").takeIf { it.isNotBlank() }),
                createdDate = root.optString("date_joined").substringBefore('T').takeIf { it.isNotBlank() },
                statsSummary = company?.let { "Company: $it" }
            )
        }.getOrNull()
    }

    internal fun fetchNpmMaintainer(u: String): EnrichedProfileCard? {
        val resp = ScanHttp.get(
            "https://registry.npmjs.org/-/v1/search?text=maintainer:${ScanHttp.encode(u)}&size=5",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val objects = JSONObject(resp.body).optJSONArray("objects") ?: return null
            if (objects.length() == 0) return null
            val emails = LinkedHashSet<String>()
            val packages = mutableListOf<String>()
            var authorName: String? = null
            for (i in 0 until objects.length()) {
                val pkg = objects.optJSONObject(i)?.optJSONObject("package") ?: continue
                pkg.optString("name").takeIf { it.isNotBlank() }?.let { packages += it }
                if (authorName == null) {
                    authorName = pkg.optJSONObject("author")?.optString("name")?.takeIf { it.isNotBlank() }
                }
                val maintainers = pkg.optJSONArray("maintainers")
                if (maintainers != null) {
                    for (j in 0 until maintainers.length()) {
                        val m = maintainers.optJSONObject(j) ?: continue
                        if (m.optString("username").equals(u, ignoreCase = true)) {
                            m.optString("email").takeIf { it.isNotBlank() }?.let { emails += it }
                        }
                    }
                }
            }
            if (emails.isEmpty() && packages.isEmpty()) return null
            EnrichedProfileCard(
                platform = "npm Registry",
                handle = u,
                profileUrl = "https://www.npmjs.com/~$u",
                displayName = cleanHumanDisplayName(authorName, u, "npm Registry"),
                bio = "Published packages: ${packages.joinToString(", ")}",
                discoveredEmails = emails.toList(),
                statsSummary = "${packages.size}+ public npm packages"
            )
        }.getOrNull()
    }

    private fun fetchWikipediaGlobal(u: String): EnrichedProfileCard? {
        val url = "https://en.wikipedia.org/w/api.php?action=query&meta=globaluserinfo&guiuser=${ScanHttp.encode(u)}&guiprop=editcount|merged&format=json"
        val resp = ScanHttp.get(url, timeoutMs = 8_000, headers = mapOf("Accept" to "application/json"))
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val gui = JSONObject(resp.body).optJSONObject("query")?.optJSONObject("globaluserinfo") ?: return null
            if (gui.has("missing")) return null
            val name = gui.optString("name").ifBlank { return null }
            val reg = gui.optString("registration").substringBefore('T').takeIf { it.isNotBlank() }
            val edits = gui.optInt("editcount", 0)
            val home = gui.optString("home")
            EnrichedProfileCard(
                platform = "Wikimedia / Wikipedia",
                handle = name,
                profileUrl = "https://en.wikipedia.org/wiki/User:${ScanHttp.encode(name)}",
                createdDate = reg,
                statsSummary = "$edits global edits (Home wiki: $home)"
            )
        }.getOrNull()
    }

    private fun fetchStackOverflow(u: String): EnrichedProfileCard? {
        val url = "https://api.stackexchange.com/2.3/users?inname=${ScanHttp.encode(u)}&site=stackoverflow&pagesize=3"
        val resp = ScanHttp.get(url, timeoutMs = 8_000, headers = mapOf("Accept" to "application/json"))
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val items = JSONObject(resp.body).optJSONArray("items") ?: return null
            val exact = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                .firstOrNull { it.optString("display_name").equals(u, ignoreCase = true) } ?: return null
            val created = exact.optLong("creation_date", 0L)
            val disp = exact.optString("display_name", u)
            EnrichedProfileCard(
                platform = "StackOverflow",
                handle = disp,
                profileUrl = exact.optString("link", "https://stackoverflow.com"),
                displayName = cleanHumanDisplayName(disp, u, "StackOverflow"),
                location = exact.optString("location").takeIf { it.isNotBlank() },
                externalLinks = listOfNotNull(exact.optString("website_url").takeIf { it.isNotBlank() }),
                avatarUrl = exact.optString("profile_image").takeIf { it.startsWith("http") },
                createdDate = if (created > 0) formatEpochSeconds(created) else null,
                statsSummary = "Reputation: ${exact.optInt("reputation", 0)}"
            )
        }.getOrNull()
    }

    internal fun scrapeHtmlProfileMetadata(
        siteName: String,
        handle: String,
        url: String
    ): EnrichedProfileCard? {
        val resp = ScanHttp.get(url, timeoutMs = 8_000, maxBodyChars = 250_000)
        if (!resp.isOk || resp.body.isBlank()) return null
        val html = resp.body

        val rawOgTitle = OG_TITLE.find(html)?.groupValues?.get(1)?.let { BreachCatalog.plainText(it) }?.takeIf { it.isNotBlank() }
        val rawOgDesc = OG_DESC.find(html)?.groupValues?.get(1)?.let { BreachCatalog.plainText(it) }?.takeIf { it.isNotBlank() }
        val ogImage = OG_IMAGE.find(html)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }

        // Reject soft-404 pages (like Twitch's generic homepage meta tags when a user doesn't exist)
        if (rawOgDesc != null && GENERIC_SITE_BIOS.any { rawOgDesc.lowercase().contains(it) } &&
            rawOgTitle?.lowercase()?.contains(handle.lowercase()) != true
        ) {
            return null
        }

        val cleanName = cleanHumanDisplayName(rawOgTitle, handle, siteName)
        val cleanBio = rawOgDesc?.takeUnless { d -> GENERIC_SITE_BIOS.any { d.lowercase().contains(it) } }

        val emails = (MAILTO_REGEX.findAll(html).map { it.groupValues[1].lowercase() } +
            extractEmails(cleanBio.orEmpty())).distinct().toList()
        val phones = EmailAttributionEngine.extractPhoneNumbersFromText(cleanBio.orEmpty(), siteName).map { it.value }
        val outboundLinks = extractSocialLinks(html)
            .filterNot { it.contains(url, ignoreCase = true) }
            .take(6)

        if (cleanName == null && cleanBio == null && emails.isEmpty() && outboundLinks.isEmpty()) return null

        return EnrichedProfileCard(
            platform = siteName,
            handle = handle,
            profileUrl = url,
            displayName = cleanName,
            bio = cleanBio?.take(280),
            externalLinks = outboundLinks,
            discoveredEmails = emails,
            discoveredPhones = phones,
            avatarUrl = ogImage
        )
    }

    private fun extractEmails(text: String): List<String> =
        EMAIL_REGEX.findAll(text)
            .map { it.value.lowercase().trimEnd('.') }
            .filterNot { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".svg") }
            .distinct()
            .toList()

    private fun extractSocialLinks(text: String): List<String> =
        SOCIAL_LINK_REGEX.findAll(text)
            .map { it.value.trimEnd('/', '"', '\'', ')', '>') }
            .filterNot { link ->
                val tail = link.substringAfterLast('/').substringBefore('?').removePrefix("@").lowercase()
                tail in PLATFORM_BOILERPLATE_HANDLES
            }
            .distinct()
            .toList()

    private fun formatEpochSeconds(sec: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(sec * 1000L))
}
