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
        dockerHub(),
        twitter(),
        adobe(),
        lastFm(),
        eventbrite(),
        evernote(),
        samsung(),
        xing(),
        strava()
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

    private fun twitter() = SiteProbe(
        name = "Twitter / X",
        category = "social",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://api.twitter.com/i/users/email_available.json?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://x.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"taken\":true"),
        missingCodes = setOf(200),
        missingStrings = listOf("\"taken\":false"),
        missingUrls = emptyList()
    )

    private fun adobe() = SiteProbe(
        name = "Adobe",
        category = "software",
        source = SRC_CURATED,
        method = "POST",
        urlTemplate = "https://auth.services.adobe.com/signin/v2/users/accounts",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://adobe.com/",
        headers = mapOf("Content-Type" to "application/json"),
        bodyTemplate = "{\"username\":\"{account}\"}",
        foundCodes = setOf(200),
        foundStrings = listOf("\"accountState\":"),
        missingCodes = setOf(404),
        missingStrings = listOf("account_not_found"),
        missingUrls = emptyList()
    )

    private fun pinterest() = SiteProbe(
        name = "Pinterest",
        category = "social",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.pinterest.com/_ngapi/v3/check_email/?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.pinterest.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"exists\":true", "\"is_taken\":true"),
        missingCodes = setOf(200),
        missingStrings = listOf("\"exists\":false", "\"is_taken\":false"),
        missingUrls = emptyList()
    )

    private fun patreon() = SiteProbe(
        name = "Patreon",
        category = "social",
        source = SRC_CURATED,
        method = "POST",
        urlTemplate = "https://www.patreon.com/api/user/email",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.patreon.com/",
        headers = mapOf("Content-Type" to "application/json"),
        bodyTemplate = "{\"email\":\"{account}\"}",
        foundCodes = setOf(200),
        foundStrings = listOf("\"exists\":true"),
        missingCodes = setOf(200, 404),
        missingStrings = listOf("\"exists\":false"),
        missingUrls = emptyList()
    )

    private fun lastFm() = SiteProbe(
        name = "Last.fm",
        category = "music",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.last.fm/join/check_email?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.last.fm/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"exists\":true", "\"taken\":true"),
        missingCodes = setOf(200),
        missingStrings = listOf("\"exists\":false", "\"taken\":false"),
        missingUrls = emptyList()
    )

    private fun codecademy() = SiteProbe(
        name = "Codecademy",
        category = "learning",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.codecademy.com/api/v1/users/check_email?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.codecademy.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"exists\":true"),
        missingCodes = setOf(200, 404),
        missingStrings = listOf("\"exists\":false"),
        missingUrls = emptyList()
    )

    private fun eventbrite() = SiteProbe(
        name = "Eventbrite",
        category = "events",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.eventbrite.com/api/v3/users/check_email/?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.eventbrite.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"is_registered\":true", "\"exists\":true"),
        missingCodes = setOf(200, 404),
        missingStrings = listOf("\"is_registered\":false", "\"exists\":false"),
        missingUrls = emptyList()
    )

    private fun evernote() = SiteProbe(
        name = "Evernote",
        category = "productivity",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.evernote.com/check_email?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://evernote.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"exists\":true"),
        missingCodes = setOf(200),
        missingStrings = listOf("\"exists\":false"),
        missingUrls = emptyList()
    )

    private fun quora() = SiteProbe(
        name = "Quora",
        category = "social",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.quora.com/api/logged_out_check_email?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.quora.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"email_exists\":true"),
        missingCodes = setOf(200),
        missingStrings = listOf("\"email_exists\":false"),
        missingUrls = emptyList()
    )

    private fun samsung() = SiteProbe(
        name = "Samsung",
        category = "tech",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://account.samsung.com/membership/checkEmailAuth?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://account.samsung.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"result\":\"DUPLICATE\"", "\"exists\":true"),
        missingCodes = setOf(200),
        missingStrings = listOf("\"result\":\"SUCCESS\"", "\"exists\":false"),
        missingUrls = emptyList()
    )

    private fun wattpad() = SiteProbe(
        name = "Wattpad",
        category = "social",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.wattpad.com/api/v3/users/check_email?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.wattpad.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"exists\":true"),
        missingCodes = setOf(200, 404),
        missingStrings = listOf("\"exists\":false"),
        missingUrls = emptyList()
    )

    private fun xing() = SiteProbe(
        name = "Xing",
        category = "professional",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.xing.com/api/signup/check_email?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.xing.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"exists\":true"),
        missingCodes = setOf(200, 404),
        missingStrings = listOf("\"exists\":false"),
        missingUrls = emptyList()
    )

    private fun anyDo() = SiteProbe(
        name = "Any.do",
        category = "productivity",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://sm-api.any.do/check_email?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.any.do/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"userExists\":true", "\"exists\":true"),
        missingCodes = setOf(200, 404),
        missingStrings = listOf("\"userExists\":false", "\"exists\":false"),
        missingUrls = emptyList()
    )

    private fun notion() = SiteProbe(
        name = "Notion",
        category = "productivity",
        source = SRC_CURATED,
        method = "POST",
        urlTemplate = "https://www.notion.so/api/v3/getsignupauthparams",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.notion.so/",
        headers = mapOf("Content-Type" to "application/json"),
        bodyTemplate = "{\"email\":\"{account}\"}",
        foundCodes = setOf(200),
        foundStrings = listOf("\"hasPassword\":true", "\"isDomainAllowed\":"),
        missingCodes = setOf(200),
        missingStrings = listOf("\"hasPassword\":false"),
        missingUrls = emptyList()
    )

    private fun strava() = SiteProbe(
        name = "Strava",
        category = "fitness",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://www.strava.com/athletes/check_email?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.strava.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"exists\":true"),
        missingCodes = setOf(200, 404),
        missingStrings = listOf("\"exists\":false"),
        missingUrls = emptyList()
    )

    private fun deliveroo() = SiteProbe(
        name = "Deliveroo",
        category = "shopping",
        source = SRC_CURATED,
        method = "POST",
        urlTemplate = "https://deliveroo.co.uk/api/v2/check-email",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://deliveroo.co.uk/",
        headers = mapOf("Content-Type" to "application/json"),
        bodyTemplate = "{\"email_address\":\"{account}\"}",
        foundCodes = setOf(200),
        foundStrings = listOf("\"registered\":true"),
        missingCodes = setOf(200),
        missingStrings = listOf("\"registered\":false"),
        missingUrls = emptyList()
    )

    private fun codepen() = SiteProbe(
        name = "CodePen",
        category = "coding",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://codepen.io/api/v1/users/check_email?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://codepen.io/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"taken\":true", "\"exists\":true"),
        missingCodes = setOf(200, 404),
        missingStrings = listOf("\"taken\":false", "\"exists\":false"),
        missingUrls = emptyList()
    )

    private fun garmin() = SiteProbe(
        name = "Garmin",
        category = "fitness",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://sso.garmin.com/sso/verify-email?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.garmin.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"exists\":true"),
        missingCodes = setOf(200),
        missingStrings = listOf("\"exists\":false"),
        missingUrls = emptyList()
    )

    private fun myspace() = SiteProbe(
        name = "Myspace",
        category = "social",
        source = SRC_CURATED,
        method = "GET",
        urlTemplate = "https://myspace.com/ajax/account/emailavailable?email={account}",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://myspace.com/",
        headers = mapOf("Accept" to "application/json"),
        bodyTemplate = null,
        foundCodes = setOf(200),
        foundStrings = listOf("\"available\":false", "\"exists\":true"),
        missingCodes = setOf(200),
        missingStrings = listOf("\"available\":true", "\"exists\":false"),
        missingUrls = emptyList()
    )

    private fun bitmoji() = SiteProbe(
        name = "Bitmoji",
        category = "social",
        source = SRC_CURATED,
        method = "POST",
        urlTemplate = "https://api.bitmoji.com/user/check_email",
        accountMode = AccountMode.RAW,
        profileTemplate = "https://www.bitmoji.com/",
        headers = mapOf("Content-Type" to "application/json"),
        bodyTemplate = "{\"email\":\"{account}\"}",
        foundCodes = setOf(200),
        foundStrings = listOf("\"user_exists\":true"),
        missingCodes = setOf(200, 404),
        missingStrings = listOf("\"user_exists\":false"),
        missingUrls = emptyList()
    )
}
