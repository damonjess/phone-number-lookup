package com.example.phone_number_lookup

import com.example.phone_number_lookup.OsintDatasets.SRC_CURATED

object CuratedEmailChecks {

    val PROBES: List<SiteProbe> = listOf(
        mozillaFirefox(),
        chessCom(),
        gravatar(),
        duolingo(),
        wordpress(),
        spotify(),
        github(),
        keybase(),
        mastodonSocial(),
        openPgpKeys(),
        replit(),
        gitlab(),
        figma(),
        dockerHub(),
        substack(),
        letterboxd(),
        imgur(),
        tumblr(),
        pornhub(),
        atlassian()
    )

    private fun mozillaFirefox() = SiteProbe(
        name = "Mozilla Firefox",
        category = "tech",
        source = SRC_CURATED,
        method = "POST",
        urlTemplate = "https://api.accounts.firefox.com/v1/account/status",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://accounts.firefox.com/",
        headers = mapOf("Content-Type" to "application/json"),
        bodyTemplate = "{\"email\":\"{account}\"}",
        foundCodes = setOf(200),
        foundStrings = listOf("\"exists\":true"),
        missingCodes = setOf(404),
        missingStrings = listOf("\"exists\":false"),
        missingUrls = emptyList()
    )

    private fun chessCom() = SiteProbe(
        name = "Chess.com",
        category = "games",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.chess.com/callback/email/available?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.chess.com/",
        headers = emptyMap(),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"isEmailAvailable\":false"),
        missingCodes = setOf(417),
        missingStrings = listOf("\"isEmailAvailable\":true"),
        missingUrls = emptyList()
    )

    private fun gravatar() = SiteProbe(
        name = "Gravatar",
        category = "images",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://gravatar.com/{account}.json",
        accountMode = AccountMode.HASH_MD5,
        profileTemplate = "https://gravatar.com/",
        headers = emptyMap(),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = emptyList(),
        missingCodes = setOf(404),
        missingStrings = listOf("User not found"),
        missingUrls = emptyList()
    )

    private fun duolingo() = SiteProbe(
        name = "Duolingo",
        category = "learning",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.duolingo.com/2017-06-30/users?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.duolingo.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"username\":"),
        missingCodes = setOf(404),
        missingStrings = listOf("\"users\":[]"),
        missingUrls = emptyList()
    )

    private fun wordpress() = SiteProbe(
        name = "WordPress.com",
        category = "blog",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://public-api.wordpress.com/rest/v1.1/users/{account}/auth-options",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://wordpress.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"passwordless\":"),
        missingCodes = setOf(400, 404),
        missingStrings = listOf("unknown_user", "invalid_user"),
        missingUrls = emptyList()
    )

    private fun spotify() = SiteProbe(
        name = "Spotify",
        category = "music",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://spclient.wg.spotify.com/signup/public/v1/account?validate=1&email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://open.spotify.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"status\":20", "\"status\": 20"),
        missingCodes = setOf(404),
        missingStrings = listOf("\"status\":1,", "\"status\": 1,", "\"status\":0", "\"status\":130"),
        missingUrls = emptyList()
    )

    private fun github() = SiteProbe(
        name = "GitHub",
        category = "coding",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://api.github.com/search/users?q={account}+in:email",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://github.com/",
        headers = mapOf("Accept" to "application/vnd.github+json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"login\":"),
        missingCodes = setOf(404, 422),
        missingStrings = listOf("\"total_count\":0", "\"total_count\": 0"),
        missingUrls = emptyList()
    )

    private fun keybase() = SiteProbe(
        name = "Keybase",
        category = "tech",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://keybase.io/_/api/1.0/user/lookup.json?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://keybase.io/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"basics\":"),
        missingCodes = setOf(400, 404),
        missingStrings = listOf("\"them\":[]", "\"them\":[null]", "\"status\":{\"code\":100"),
        missingUrls = emptyList()
    )

    private fun mastodonSocial() = SiteProbe(
        name = "Mastodon (mastodon.social)",
        category = "social",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://mastodon.social/api/v1/accounts/lookup?acct={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://mastodon.social/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"acct\":"),
        missingCodes = setOf(404),
        missingStrings = listOf("Record not found"),
        missingUrls = emptyList()
    )

    private fun openPgpKeys() = SiteProbe(
        name = "OpenPGP Keyserver",
        category = "tech",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://keys.openpgp.org/vks/v1/by-email/{account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://keys.openpgp.org/",
        headers = emptyMap(),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("-----BEGIN PGP PUBLIC KEY BLOCK-----"),
        missingCodes = setOf(404),
        missingStrings = listOf("No key found", "not found"),
        missingUrls = emptyList()
    )

    private fun replit() = SiteProbe(
        name = "Replit",
        category = "coding",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://replit.com/data/user/exists?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://replit.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"exists\":true"),
        missingCodes = setOf(404),
        missingStrings = listOf("\"exists\":false"),
        missingUrls = emptyList()
    )

    private fun gitlab() = SiteProbe(
        name = "GitLab",
        category = "coding",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://gitlab.com/api/v4/users?search={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://gitlab.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"username\":"),
        missingCodes = setOf(404),
        missingStrings = listOf("[]"),
        missingUrls = emptyList()
    )

    private fun figma() = SiteProbe(
        name = "Figma",
        category = "design",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.figma.com/api/users/check_email?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.figma.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"exists\":true"),
        missingCodes = setOf(404),
        missingStrings = listOf("\"exists\":false"),
        missingUrls = emptyList()
    )

    private fun dockerHub() = SiteProbe(
        name = "Docker Hub",
        category = "tech",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://hub.docker.com/v2/users/{account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://hub.docker.com/u/{account}",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"username\":"),
        missingCodes = setOf(404),
        missingStrings = listOf("\"detail\":\"Not found\""),
        missingUrls = emptyList()
    )

    private fun substack() = SiteProbe(
        name = "Substack",
        category = "blog",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://substack.com/api/v1/user/email_availability?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://substack.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"exists\":true", "\"available\":false"),
        missingCodes = setOf(404),
        missingStrings = listOf("\"exists\":false", "\"available\":true"),
        missingUrls = emptyList()
    )

    private fun letterboxd() = SiteProbe(
        name = "Letterboxd",
        category = "movies",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://letterboxd.com/user/check-email/?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://letterboxd.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"exists\":true", "\"result\":false"),
        missingCodes = setOf(404),
        missingStrings = listOf("\"exists\":false", "\"result\":true"),
        missingUrls = emptyList()
    )

    private fun imgur() = SiteProbe(
        name = "Imgur",
        category = "images",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://imgur.com/signin/ajax/check_email?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://imgur.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"available\":false", "\"exists\":true"),
        missingCodes = setOf(404),
        missingStrings = listOf("\"available\":true", "\"exists\":false"),
        missingUrls = emptyList()
    )

    private fun tumblr() = SiteProbe(
        name = "Tumblr",
        category = "blog",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.tumblr.com/svc/account/register_check?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.tumblr.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"email_taken\":true", "\"taken\":true"),
        missingCodes = setOf(404),
        missingStrings = listOf("\"email_taken\":false", "\"taken\":false"),
        missingUrls = emptyList()
    )

    private fun pornhub() = SiteProbe(
        name = "Pornhub",
        category = "nsfw",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.pornhub.com/user/check_email?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.pornhub.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"status\":\"taken\"", "\"exists\":true"),
        missingCodes = setOf(404),
        missingStrings = listOf("\"status\":\"available\"", "\"exists\":false"),
        missingUrls = emptyList()
    )

    private fun atlassian() = SiteProbe(
        name = "Atlassian",
        category = "tech",
        source = SRC_CURATED,
        method = "POST",
        urlTemplate = "https://id.atlassian.com/login/id-check",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://id.atlassian.com/",
        headers = mapOf("Content-Type" to "application/json"),
        bodyTemplate = "{\"username\":\"{account}\"}",
        foundCodes = setOf(200),
        foundStrings = listOf("\"accountExists\":true", "\"account_exists\":true"),
        missingCodes = setOf(404),
        missingStrings = listOf("\"accountExists\":false", "\"account_exists\":false"),
        missingUrls = emptyList()
    )
}
