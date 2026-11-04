package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class SocialMediaHit(
    val platform: String,
    val handleOrTitle: String,
    val snippet: String,
    val url: String,
    val matchedIdentifier: String,
    val verifiedByEmail: Boolean
)

object SocialMediaEmailScraper {

    private val GENERIC_HANDLES = setOf(
        "info", "admin", "support", "contact", "sales", "help", "office",
        "mail", "user", "test", "hello", "team", "enquiries", "service"
    )

    /**
     * Searches major social media networks (Facebook, Instagram, LinkedIn, TikTok, X/Twitter,
     * Threads, YouTube, Pinterest, Reddit) for an email address and its exact linked handles.
     */
    suspend fun scanForEmail(
        email: String,
        exactHandles: List<String> = emptyList(),
        fullNames: List<String> = emptyList(),
        locationHint: String? = null
    ): List<SocialMediaHit> = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        if (cleanEmail.isBlank() || !cleanEmail.contains('@')) return@withContext emptyList()

        val localPart = cleanEmail.substringBefore('@').trim()
        val handles = buildList {
            if (localPart.length >= 4 && localPart !in GENERIC_HANDLES) add(localPart)
            exactHandles.forEach { h ->
                val cleanH = h.trim().removePrefix("@")
                if (cleanH.length >= 4 && cleanH.lowercase() !in GENERIC_HANDLES && !cleanH.contains(' ')) {
                    add(cleanH)
                }
            }
        }.distinctBy { it.lowercase() }.take(4)

        val queries = buildSocialQueries(cleanEmail, handles, fullNames, locationHint)
        val strictIds = (listOf(cleanEmail) + handles).map { it.lowercase() }.distinct()

        coroutineScope {
            // 1. Direct Instagram Email Lookup Probe
            val igDirectDef = async {
                withTimeoutOrNull(7_000L) { probeInstagramEmailPresence(cleanEmail) }
            }

            // 2. Multi-engine social media dork scraping (Brave, Bing, Yahoo, DuckDuckGo, Google)
            val searchJobs = queries.map { (query, matchLabel) ->
                async {
                    withTimeoutOrNull(10_000L) {
                        val brave = HostGate.run("search.brave.com", minGapMs = 350) {
                            runCatching { OpenWebOsintScraper.scrapeBraveHtml(query) }.getOrDefault(emptyList())
                        }
                        val bing = HostGate.run("bing.com", minGapMs = 350) {
                            runCatching { OpenWebOsintScraper.scrapeBingHtml(query) }.getOrDefault(emptyList())
                        }
                        val yahoo = HostGate.run("yahoo.com", minGapMs = 350) {
                            runCatching { GoogleCommentScraper.scrapeYahooSearchHtml(query) }.getOrDefault(emptyList())
                        }
                        (brave + bing + yahoo).mapNotNull { item ->
                            toSocialHitIfValid(item, cleanEmail, matchLabel, strictIds)
                        }
                    }.orEmpty()
                }
            }

            val hits = mutableListOf<SocialMediaHit>()
            igDirectDef.await()?.let { hits += it }
            hits += searchJobs.awaitAll().flatten()

            hits.distinctBy { normalizeSocialUrl(it.url) }
        }
    }

    private fun buildSocialQueries(
        email: String,
        handles: List<String>,
        fullNames: List<String>,
        locationHint: String?
    ): List<Pair<String, String>> = buildList {
        val primaryHandle = handles.firstOrNull() ?: email.substringBefore('@')
        GoogleCommentScraper.buildFacebookEmailQueries(email, primaryHandle).forEach { q ->
            add(q to email)
        }

        // 1. Direct email across Facebook, Instagram, LinkedIn, X/Twitter, TikTok, Threads & YouTube
        add(
            "\"$email\" (site:facebook.com OR site:instagram.com OR site:linkedin.com OR site:x.com OR site:twitter.com OR site:tiktok.com OR site:youtube.com)" to email
        )
        add("\"$email\" (site:instagram.com OR site:threads.net OR site:pinterest.com)" to email)

        // 2. Exact handles across Facebook, Instagram, LinkedIn, TikTok, X & Reddit
        for (h in handles.take(3)) {
            add("\"$h\" (site:facebook.com OR site:instagram.com OR site:linkedin.com/in)" to "@$h")
            add("\"$h\" (site:tiktok.com OR site:x.com OR site:twitter.com OR site:threads.net)" to "@$h")
            add("\"$h\" (site:youtube.com OR site:pinterest.com OR site:reddit.com)" to "@$h")
        }

        // 3. Full Name + Location on Facebook & LinkedIn UK
        val primaryName = fullNames.firstOrNull { ' ' in it && it.length >= 5 }
        if (primaryName != null) {
            val loc = locationHint?.takeIf { it.isNotBlank() } ?: "UK"
            add("\"$primaryName\" \"$loc\" (site:linkedin.com/in OR site:facebook.com)" to primaryName)
        }
    }.take(8)

    private fun probeInstagramEmailPresence(email: String): SocialMediaHit? {
        val resp = ScanHttp.post(
            "https://www.instagram.com/api/v1/users/lookup/",
            "q=${ScanHttp.encode(email)}",
            contentType = "application/x-www-form-urlencoded",
            timeoutMs = 7_000,
            headers = mapOf(
                "X-CSRFToken" to "missing",
                "X-IG-App-ID" to "936619743392459",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
            )
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val json = JSONObject(resp.body)
            val phone = json.optString("obfuscated_phone").takeIf { it.isNotBlank() }
                ?: json.optJSONObject("user")?.optString("obfuscated_phone")?.takeIf { it.isNotBlank() }
            val emailSent = json.optBoolean("email_sent", false) || json.optBoolean("can_email_reset", false)
            if (phone != null || emailSent) {
                SocialMediaHit(
                    platform = "Instagram",
                    handleOrTitle = "Registered Instagram Account ($email)",
                    snippet = buildString {
                        append("Instagram lookup confirmed an active account for $email")
                        if (phone != null) append(" • Recovery phone: $phone")
                    },
                    url = "https://www.instagram.com/",
                    matchedIdentifier = email,
                    verifiedByEmail = true
                )
            } else null
        }.getOrNull()
    }

    private fun toSocialHitIfValid(
        item: SearchResultItem,
        email: String,
        matchLabel: String,
        strictIds: List<String>
    ): SocialMediaHit? {
        if (GoogleCommentScraper.isLikelySpamOrNoise(item.title, item.snippet, item.url)) return null
        val platform = detectSocialPlatform(item.url) ?: return null
        val blob = "${item.title} ${item.snippet} ${item.url}"

        // Require exact word-boundary match if matched by handle/email so "damonkirby" never matches "damonkirby86"
        val isExactIdMatch = strictIds.any { id ->
            if ('@' in id) blob.contains(id, ignoreCase = true)
            else Regex("""(?<![a-zA-Z0-9_])${Regex.escape(id)}(?![a-zA-Z0-9_])""", RegexOption.IGNORE_CASE)
                .containsMatchIn(blob)
        }
        val isNameMatch = matchLabel.contains(' ') && blob.contains(matchLabel, ignoreCase = true)
        if (!isExactIdMatch && !isNameMatch) return null

        val verifiedByEmail = blob.contains(email, ignoreCase = true)
        return SocialMediaHit(
            platform = platform,
            handleOrTitle = item.title.take(110),
            snippet = item.snippet.take(240),
            url = item.url,
            matchedIdentifier = if (verifiedByEmail) email else matchLabel,
            verifiedByEmail = verifiedByEmail
        )
    }

    internal fun detectSocialPlatform(url: String): String? {
        val u = url.lowercase()
        return when {
            "facebook.com/" in u || "fb.com/" in u -> "Facebook"
            "instagram.com/" in u -> "Instagram"
            "linkedin.com/in/" in u || "linkedin.com/pub/" in u || "linkedin.com/posts/" in u -> "LinkedIn"
            "tiktok.com/@" in u || "tiktok.com/t/" in u -> "TikTok"
            "x.com/" in u || "twitter.com/" in u -> "X / Twitter"
            "threads.net/" in u -> "Threads"
            "youtube.com/" in u || "youtu.be/" in u -> "YouTube"
            "pinterest.com/" in u || "pinterest.co.uk/" in u -> "Pinterest"
            "reddit.com/user/" in u || "reddit.com/r/" in u -> "Reddit"
            "snapchat.com/add/" in u -> "Snapchat"
            else -> null
        }
    }

    /**
     * Builds 1-tap social media search dorks that open natively inside the app's In-App Browser.
     */
    fun buildInAppSocialSearchDorks(
        email: String,
        handles: List<String>,
        fullName: String? = null
    ): List<OsintDork> {
        val cleanEmail = email.trim()
        val localPart = cleanEmail.substringBefore('@')
        val allHandles = (listOf(localPart) + handles.map { it.removePrefix("@") }).distinct().take(3)
        val queryGroup = (listOf("\"$cleanEmail\"") + allHandles.map { "\"$it\"" } + listOfNotNull(fullName?.let { "\"$it\"" }))
            .joinToString("+OR+") { ScanHttp.encode(it) }

        return listOf(
            OsintDork(
                title = "Facebook: Search Posts, Bio & Profiles ($cleanEmail)",
                description = "Searches Facebook pages, public posts, and profiles mentioning your email or handles inside the in-app browser.",
                url = "https://www.google.com/search?q=site:facebook.com+($queryGroup)",
                toolSource = "Facebook Recon"
            ),
            OsintDork(
                title = "Instagram & Threads: Search Bios & Mentions",
                description = "Finds Instagram and Threads profiles or posts containing your email or linked handles.",
                url = "https://www.google.com/search?q=(site:instagram.com+OR+site:threads.net)+($queryGroup)",
                toolSource = "Instagram / Threads"
            ),
            OsintDork(
                title = "LinkedIn: Search Public Profiles & Posts",
                description = "Searches LinkedIn public profiles and activity for your email, handle, or full name.",
                url = "https://www.google.com/search?q=site:linkedin.com+($queryGroup)",
                toolSource = "LinkedIn Recon"
            ),
            OsintDork(
                title = "TikTok, X (Twitter) & YouTube: Search Profiles & Videos",
                description = "Searches TikTok, X/Twitter, and YouTube channels for your email or handles.",
                url = "https://www.google.com/search?q=(site:tiktok.com+OR+site:x.com+OR+site:twitter.com+OR+site:youtube.com)+($queryGroup)",
                toolSource = "Social Video / X"
            )
        )
    }

    private fun normalizeSocialUrl(url: String): String =
        url.lowercase().substringBefore('?').trimEnd('/').removePrefix("https://").removePrefix("http://").removePrefix("www.")
}
