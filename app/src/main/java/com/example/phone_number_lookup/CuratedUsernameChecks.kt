package com.example.phone_number_lookup

import com.example.phone_number_lookup.OsintDatasets.SRC_CURATED

/**
 * Curated, API-first username probes that override broken or bot-blocked HTML entries
 * from WhatsMyName, Sherlock, and Tookie.
 */
object CuratedUsernameChecks {

    val PROBES: List<SiteProbe> = listOf(
        github(),
        reddit(),
        instagram(),
        facebook(),
        threads(),
        snapchat(),
        twitterX(),
        bluesky(),
        tiktok(),
        youtube(),
        steam(),
        twitch(),
        chessCom(),
        lichess(),
        gitlab(),
        keybase(),
        soundcloud(),
        vimeo(),
        dailymotion(),
        devTo(),
        hackerNews(),
        duolingo(),
        mastodonSocial(),
        gravatar(),
        myFitnessPal(),
        strava(),
        medium(),
        telegram(),
        minecraft(),
        scratch(),
        dockerHub(),
        letterboxd(),
        pinterest(),
        linktree()
    )

    private fun instagram() = SiteProbe(
        name = "Instagram",
        category = "social",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.instagram.com/{account}/",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.instagram.com/{account}/",
        headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "en-GB,en;q=0.9"
        ),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("(@{account})", "instagram.com/{account}"),
        missingCodes = setOf(404),
        missingStrings = listOf("Sorry, this page isn't available", "Page Not Found"),
        missingUrls = listOf("instagram.com/accounts/login")
    )

    private fun facebook() = SiteProbe(
        name = "Facebook",
        category = "social",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.facebook.com/{account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.facebook.com/{account}",
        headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "en-GB,en;q=0.9"
        ),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("facebook.com/{account}\"", "fb://profile/"),
        missingCodes = setOf(404),
        missingStrings = listOf(
            "This content isn't available right now",
            "The link you followed may be broken",
            "Page Not Found"
        ),
        missingUrls = listOf("facebook.com/login")
    )

    private fun threads() = SiteProbe(
        name = "Threads",
        category = "social",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.threads.net/@{account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.threads.net/@{account}",
        headers = mapOf("Accept" to "text/html,application/xhtml+xml"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("(@{account})", "threads.net/@{account}"),
        missingCodes = setOf(404),
        missingStrings = listOf("Sorry, this page isn't available", "Page not found"),
        missingUrls = emptyList()
    )

    private fun snapchat() = SiteProbe(
        name = "Snapchat",
        category = "social",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.snapchat.com/add/{account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.snapchat.com/add/{account}",
        headers = mapOf("Accept" to "text/html,application/xhtml+xml"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("on Snapchat!", "\"snapcodeImageUrl\""),
        missingCodes = setOf(404),
        missingStrings = listOf("Sorry, This Content Was Not Found", "Page Not Found"),
        missingUrls = emptyList()
    )

    private fun twitterX() = SiteProbe(
        name = "X / Twitter",
        category = "social",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://syndication.twitter.com/srv/timeline-profile/screen-name/{account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://x.com/{account}",
        headers = mapOf("Accept" to "text/html,application/xhtml+xml,application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"screen_name\":\"{account}\"", "twitter.com/{account}"),
        missingCodes = setOf(404),
        missingStrings = listOf("This account doesn’t exist", "User is suspended"),
        missingUrls = emptyList()
    )

    private fun github() = SiteProbe(
        name = "GitHub",
        category = "coding",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://api.github.com/users/{account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://github.com/{account}",
        headers = mapOf("Accept" to "application/vnd.github+json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"login\":"),
        missingCodes = setOf(404),
        missingStrings = listOf("\"Not Found\""),
        missingUrls = emptyList()
    )

    private fun reddit() = SiteProbe(
        name = "Reddit",
        category = "social",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.reddit.com/user/{account}/about.json",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.reddit.com/user/{account}",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"kind\": \"t2\"", "\"kind\":\"t2\""),
        missingCodes = setOf(404),
        missingStrings = listOf("\"error\": 404", "\"is_suspended\": true"),
        missingUrls = emptyList()
    )

    private fun bluesky() = SiteProbe(
        name = "Bluesky",
        category = "social",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor={account}.bsky.social",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://bsky.app/profile/{account}.bsky.social",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"did\":"),
        missingCodes = setOf(400, 404),
        missingStrings = listOf("Profile not found", "InvalidRequest"),
        missingUrls = emptyList()
    )

    private fun tiktok() = SiteProbe(
        name = "TikTok",
        category = "video",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.tiktok.com/@{account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.tiktok.com/@{account}",
        headers = emptyMap(),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"uniqueId\":\"{account}\""),
        missingCodes = setOf(404),
        missingStrings = listOf("\"statusCode\":10221", "\"statusCode\":10202", "Couldn't find this account"),
        missingUrls = emptyList()
    )

    private fun youtube() = SiteProbe(
        name = "YouTube",
        category = "video",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.youtube.com/oembed?url=https://www.youtube.com/@{account}&format=json",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.youtube.com/@{account}",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"author_name\":"),
        missingCodes = setOf(400, 404),
        missingStrings = listOf("Not Found", "Bad Request"),
        missingUrls = emptyList()
    )

    private fun steam() = SiteProbe(
        name = "Steam",
        category = "gaming",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://steamcommunity.com/id/{account}?xml=1",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://steamcommunity.com/id/{account}",
        headers = mapOf("Accept" to "application/xml,text/xml"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("<steamID64>"),
        missingCodes = setOf(404),
        missingStrings = listOf("The specified profile could not be found"),
        missingUrls = emptyList()
    )

    private fun twitch() = SiteProbe(
        name = "Twitch",
        category = "video",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://m.twitch.tv/{account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.twitch.tv/{account}",
        headers = emptyMap(),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("twitch.tv/{account}"),
        missingCodes = setOf(404),
        missingStrings = listOf("Sorry. Unless you've got a time machine"),
        missingUrls = emptyList()
    )

    private fun chessCom() = SiteProbe(
        name = "Chess.com",
        category = "gaming",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://api.chess.com/pub/player/{account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.chess.com/member/{account}",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"player_id\":"),
        missingCodes = setOf(404),
        missingStrings = listOf("User not found"),
        missingUrls = emptyList()
    )

    private fun lichess() = SiteProbe(
        name = "Lichess",
        category = "gaming",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://lichess.org/api/user/{account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://lichess.org/@/{account}",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"username\":"),
        missingCodes = setOf(404),
        missingStrings = emptyList(),
        missingUrls = emptyList()
    )

    private fun gitlab() = SiteProbe(
        name = "GitLab",
        category = "coding",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://gitlab.com/api/v4/users?username={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://gitlab.com/{account}",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"username\":\""),
        missingCodes = setOf(404),
        missingStrings = listOf("[]"),
        missingUrls = emptyList()
    )

    private fun keybase() = SiteProbe(
        name = "Keybase",
        category = "tech",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://keybase.io/_/api/1.0/user/lookup.json?usernames={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://keybase.io/{account}",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"basics\":"),
        missingCodes = setOf(404),
        missingStrings = listOf("\"them\":[null]"),
        missingUrls = emptyList()
    )

    private fun soundcloud() = SiteProbe(
        name = "SoundCloud",
        category = "music",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://soundcloud.com/oembed?url=https://soundcloud.com/{account}&format=json",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://soundcloud.com/{account}",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"author_name\":"),
        missingCodes = setOf(404),
        missingStrings = emptyList(),
        missingUrls = emptyList()
    )

    private fun vimeo() = SiteProbe(
        name = "Vimeo",
        category = "video",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://vimeo.com/api/v2/{account}/info.json",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://vimeo.com/{account}",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"display_name\":"),
        missingCodes = setOf(404),
        missingStrings = listOf("not found"),
        missingUrls = emptyList()
    )

    private fun dailymotion() = SiteProbe(
        name = "Dailymotion",
        category = "video",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://api.dailymotion.com/user/{account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.dailymotion.com/{account}",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"screenname\":"),
        missingCodes = setOf(404),
        missingStrings = listOf("\"error\":"),
        missingUrls = emptyList()
    )

    private fun devTo() = SiteProbe(
        name = "DEV Community",
        category = "coding",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://dev.to/api/users/by_username?url={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://dev.to/{account}",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"username\":"),
        missingCodes = setOf(404),
        missingStrings = listOf("\"error\":"),
        missingUrls = emptyList()
    )

    private fun hackerNews() = SiteProbe(
        name = "HackerNews",
        category = "tech",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://hacker-news.firebaseio.com/v0/user/{account}.json",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://news.ycombinator.com/user?id={account}",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"id\":"),
        missingCodes = setOf(404),
        missingStrings = listOf("null"),
        missingUrls = emptyList()
    )

    private fun duolingo() = SiteProbe(
        name = "Duolingo",
        category = "hobby",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.duolingo.com/2017-06-30/users?username={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.duolingo.com/profile/{account}",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"streak\":"),
        missingCodes = setOf(404),
        missingStrings = listOf("\"users\":[]"),
        missingUrls = emptyList()
    )

    private fun mastodonSocial() = SiteProbe(
        name = "Mastodon (mastodon.social)",
        category = "social",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://mastodon.social/api/v1/accounts/lookup?acct={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://mastodon.social/@{account}",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"acct\":"),
        missingCodes = setOf(404),
        missingStrings = listOf("Record not found"),
        missingUrls = emptyList()
    )

    private fun gravatar() = SiteProbe(
        name = "Gravatar",
        category = "social",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://en.gravatar.com/{account}.json",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://gravatar.com/{account}",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"preferredUsername\":"),
        missingCodes = setOf(404),
        missingStrings = listOf("User not found"),
        missingUrls = emptyList()
    )

    private fun myFitnessPal() = SiteProbe(
        name = "MyFitnessPal",
        category = "hobby",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.myfitnesspal.com/profile/{account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.myfitnesspal.com/profile/{account}",
        headers = emptyMap(),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("{account}"),
        missingCodes = setOf(404),
        missingStrings = listOf("This Username Is Invalid", "Page Not Found"),
        missingUrls = emptyList()
    )

    private fun strava() = SiteProbe(
        name = "Strava",
        category = "hobby",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.strava.com/athletes/{account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.strava.com/athletes/{account}",
        headers = emptyMap(),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("strava.com/athletes/"),
        missingCodes = setOf(404),
        missingStrings = listOf("Page Not Found"),
        missingUrls = emptyList()
    )

    private fun medium() = SiteProbe(
        name = "Medium",
        category = "blog",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://medium.com/feed/@{account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://medium.com/@{account}",
        headers = mapOf("Accept" to "application/rss+xml,application/xml,text/xml"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("<rss", "<channel>"),
        missingCodes = setOf(404),
        missingStrings = emptyList(),
        missingUrls = emptyList()
    )

    private fun telegram() = SiteProbe(
        name = "Telegram",
        category = "social",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://t.me/{account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://t.me/{account}",
        headers = emptyMap(),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("tgme_page_title"),
        missingCodes = setOf(404),
        missingStrings = listOf("tgme_page_icon_user"),
        missingUrls = emptyList()
    )

    private fun minecraft() = SiteProbe(
        name = "Minecraft",
        category = "gaming",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://api.mojang.com/users/profiles/minecraft/{account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://namemc.com/profile/{account}",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"id\""),
        missingCodes = setOf(204, 404),
        missingStrings = listOf("Couldn't find any profile"),
        missingUrls = emptyList()
    )

    private fun scratch() = SiteProbe(
        name = "Scratch",
        category = "coding",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://api.scratch.mit.edu/users/{account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://scratch.mit.edu/users/{account}",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"history\":"),
        missingCodes = setOf(404),
        missingStrings = listOf("\"code\":\"ResourceNotFound\""),
        missingUrls = emptyList()
    )

    private fun dockerHub() = SiteProbe(
        name = "Docker Hub",
        category = "coding",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://hub.docker.com/v2/users/{account}/",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://hub.docker.com/u/{account}",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"username\":"),
        missingCodes = setOf(404),
        missingStrings = listOf("Object not found"),
        missingUrls = emptyList()
    )

    private fun letterboxd() = SiteProbe(
        name = "Letterboxd",
        category = "hobby",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://letterboxd.com/{account}/rss/",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://letterboxd.com/{account}/",
        headers = mapOf("Accept" to "application/rss+xml,application/xml"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("<rss"),
        missingCodes = setOf(404),
        missingStrings = emptyList(),
        missingUrls = emptyList()
    )

    private fun pinterest() = SiteProbe(
        name = "Pinterest",
        category = "social",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.pinterest.com/{account}/",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.pinterest.com/{account}/",
        headers = emptyMap(),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"username\":\"{account}\""),
        missingCodes = setOf(404),
        missingStrings = listOf("User not found"),
        missingUrls = emptyList()
    )

    private fun linktree() = SiteProbe(
        name = "Linktree",
        category = "social",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://linktr.ee/{account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://linktr.ee/{account}",
        headers = emptyMap(),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"username\":\"{account}\""),
        missingCodes = setOf(404),
        missingStrings = listOf("The page you’re looking for doesn’t exist"),
        missingUrls = emptyList()
    )
}
