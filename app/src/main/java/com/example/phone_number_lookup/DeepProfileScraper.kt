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
    private val SOCIAL_LINK_REGEX = Regex("""https?://(?:www\.)?(?:github\.com|x\.com|twitter\.com|linkedin\.com/in|instagram\.com|t\.me|youtube\.com/@|bsky\.app/profile|mastodon\.social/@)[a-zA-Z0-9_./@-]+""", RegexOption.IGNORE_CASE)

    /**
     * Queries public JSON APIs for the username AND scrapes HTML metadata from confirmed profile hits.
     */
    suspend fun scrapeUsernameDossier(
        username: String,
        confirmedHits: List<UsernameHit> = emptyList()
    ): List<EnrichedProfileCard> = withContext(Dispatchers.IO) {
        val clean = username.trim().removePrefix("@")
        if (clean.isBlank()) return@withContext emptyList()

        coroutineScope {
            val apiJobs = listOf(
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
                async { fetchStackOverflow(clean) },
                async { fetchGitHubDeep(clean) }
            )

            // Scrape OpenGraph / bio / links from up to 12 confirmed profile URLs
            val htmlSemaphore = Semaphore(4)
            val htmlJobs = confirmedHits
                .filter { it.verificationStatus == "Matched" && it.profileUrl.startsWith("http") }
                .take(12)
                .map { hit ->
                    async {
                        htmlSemaphore.withPermit {
                            scrapeHtmlProfileMetadata(hit.site, clean, hit.profileUrl)
                        }
                    }
                }

            (apiJobs.awaitAll().filterNotNull() + htmlJobs.awaitAll().filterNotNull())
                .distinctBy { "${it.platform.lowercase()}|${it.profileUrl.lowercase()}" }
        }
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
            displayName = gh.name,
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
                displayName = displayName,
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
                displayName = root.optString("displayName").takeIf { it.isNotBlank() },
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
                displayName = root.optString("display_name").takeIf { it.isNotBlank() },
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
                displayName = realName,
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
                displayName = root.optString("name").takeIf { it.isNotBlank() },
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
                displayName = root.optString("name").takeIf { it.isNotBlank() && it != "null" },
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
                displayName = root.optString("full_name").takeIf { it.isNotBlank() },
                location = root.optString("location").takeIf { it.isNotBlank() },
                externalLinks = listOfNotNull(root.optString("profile_url").takeIf { it.isNotBlank() }),
                createdDate = root.optString("date_joined").substringBefore('T').takeIf { it.isNotBlank() },
                statsSummary = company?.let { "Company: $it" }
            )
        }.getOrNull()
    }

    /**
     * Queries the public npm registry for packages maintained by this username—frequently
     * exposes the author's direct email address in the maintainer record.
     */
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
                displayName = authorName,
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
            EnrichedProfileCard(
                platform = "StackOverflow",
                handle = exact.optString("display_name", u),
                profileUrl = exact.optString("link", "https://stackoverflow.com"),
                displayName = exact.optString("display_name"),
                location = exact.optString("location").takeIf { it.isNotBlank() },
                externalLinks = listOfNotNull(exact.optString("website_url").takeIf { it.isNotBlank() }),
                avatarUrl = exact.optString("profile_image").takeIf { it.startsWith("http") },
                createdDate = if (created > 0) formatEpochSeconds(created) else null,
                statsSummary = "Reputation: ${exact.optInt("reputation", 0)}"
            )
        }.getOrNull()
    }

    /**
     * Universal HTML scraper for matched profile pages: extracts OpenGraph titles, descriptions,
     * avatars, mailto links, emails, phone numbers, and outbound social links.
     */
    internal fun scrapeHtmlProfileMetadata(
        siteName: String,
        handle: String,
        url: String
    ): EnrichedProfileCard? {
        val resp = ScanHttp.get(url, timeoutMs = 8_000, maxBodyChars = 250_000)
        if (!resp.isOk || resp.body.isBlank()) return null
        val html = resp.body

        val ogTitle = OG_TITLE.find(html)?.groupValues?.get(1)?.let { BreachCatalog.plainText(it) }?.takeIf { it.isNotBlank() }
        val ogDesc = OG_DESC.find(html)?.groupValues?.get(1)?.let { BreachCatalog.plainText(it) }?.takeIf { it.isNotBlank() }
        val ogImage = OG_IMAGE.find(html)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }

        val emails = (MAILTO_REGEX.findAll(html).map { it.groupValues[1].lowercase() } +
            extractEmails(ogDesc.orEmpty())).distinct().toList()
        val phones = EmailAttributionEngine.extractPhoneNumbersFromText(ogDesc.orEmpty(), siteName).map { it.value }
        val outboundLinks = extractSocialLinks(html).filterNot { it.contains(url, ignoreCase = true) }.take(8)

        if (ogTitle == null && ogDesc == null && emails.isEmpty() && outboundLinks.isEmpty()) return null

        return EnrichedProfileCard(
            platform = siteName,
            handle = handle,
            profileUrl = url,
            displayName = ogTitle?.take(80),
            bio = ogDesc?.take(280),
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
            .distinct()
            .toList()

    private fun formatEpochSeconds(sec: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(sec * 1000L))
}
