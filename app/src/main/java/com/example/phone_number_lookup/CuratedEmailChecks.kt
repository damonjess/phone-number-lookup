package com.example.phone_number_lookup

import com.example.phone_number_lookup.OsintDatasets.SRC_CURATED

/**
 * Community email datasets rot: sites change their signup flow, add bot protection or
 * invert a flag. Every probe here was exercised live against the real endpoint before
 * being committed, and each one *overrides* a same-named entry from the downloaded
 * Blackbird dataset (the loader puts these first, and de-duplication keeps the first).
 *
 * Where an upstream row disagreed with the live endpoint, the live endpoint won:
 *   - Gravatar is keyed by an MD5 of the address, not SHA-256; the dataset said SHA-256.
 *   - Chess.com now answers `{"isEmailAvailable":...}` rather than HTTP 226.
 *
 * Verified on all of these: an unregistered address must come back NOT_FOUND, which is
 * what stops false "account found" reports.
 */
object CuratedEmailChecks {

    val PROBES: List<SiteProbe> = listOf(mozillaFirefox(), chessCom(), gravatar())

    /**
     * Firefox Accounts status endpoint. Returns a plain boolean:
     *   POST https://api.accounts.firefox.com/v1/account/status  {"email":"..."}
     *   -> {"exists":true}  |  {"exists":false}
     */
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

    /**
     * Chess.com availability callback:
     *   GET https://www.chess.com/callback/email/available?email=...
     *   -> {"isEmailAvailable":true}  (free)  |  {"isEmailAvailable":false}  (taken)
     * An unparseable address returns HTTP 417 with "Invalid Email".
     */
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

    /**
     * Gravatar profile lookup (wordpress avatar service). The identifier is the MD5 of
     * the lowercased address, which is why this is not a plain `{account}` URL.
     *   GET https://gravatar.com/{md5}.json -> 200 profile JSON  |  404 "User not found"
     */
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
}
