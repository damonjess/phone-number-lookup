package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Official, key-free profile APIs. These are queried alongside the HTML site lists because a
 * username can exist on a platform without that platform being part of the merged Sherlock /
 * Tookie / WhatsMyName database, and because an API answer carries facts a status code cannot:
 * display name, location, avatar and join date.
 */
object PublicProfileApis {

    suspend fun lookup(handle: String): List<UsernameHit> = withContext(Dispatchers.IO) {
        val clean = handle.trim().removePrefix("@")
        if (clean.length < 2) return@withContext emptyList()

        listOf(
            async { github(clean) },
            async { gitlab(clean) },
            async { codeberg(clean) },
            async { dockerHub(clean) },
            async { stackExchange(clean) },
            async { mastodon(clean) },
        ).awaitAll().filterNotNull()
    }

    private fun hit(
        site: String,
        category: String,
        url: String,
        handle: String,
        evidence: String,
    ) = UsernameHit(
        site = site,
        category = category,
        source = "Official API",
        profileUrl = url,
        evidence = evidence,
        confidence = "High (official API)",
        verificationStatus = "Matched",
        queriedName = handle,
    )

    private fun github(handle: String): UsernameHit? {
        val response = ScanHttp.get(
            "https://api.github.com/users/${ScanHttp.encode(handle)}",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/vnd.github+json")
        )
        if (!response.isOk || response.body.isBlank()) return null
        return runCatching {
            val root = JSONObject(response.body)
            val login = root.optString("login").takeIf { it.isNotBlank() } ?: return null
            val facts = buildList {
                root.optString("name").takeIf { it.isNotBlank() && it != "null" }?.let { add("name: $it") }
                root.optString("company").takeIf { it.isNotBlank() && it != "null" }?.let { add("company: ${it.trim()}") }
                root.optString("location").takeIf { it.isNotBlank() && it != "null" }?.let { add("location: $it") }
                root.optString("created_at").substringBefore('T').takeIf { it.isNotBlank() }
                    ?.let { add("joined $it") }
                add("${root.optInt("public_repos", 0)} public repos")
            }
            hit(
                site = "GitHub",
                category = "developer",
                url = root.optString("html_url", "https://github.com/$login"),
                handle = login,
                evidence = facts.joinToString(" · "),
            )
        }.getOrNull()
    }

    private fun gitlab(handle: String): UsernameHit? {
        val response = ScanHttp.get(
            "https://gitlab.com/api/v4/users?username=${ScanHttp.encode(handle)}",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!response.isOk || response.body.isBlank()) return null
        return runCatching {
            val users = JSONArray(response.body)
            if (users.length() == 0) return null
            val user = users.optJSONObject(0) ?: return null
            val username = user.optString("username").takeIf { it.isNotBlank() } ?: return null
            val facts = buildList {
                user.optString("name").takeIf { it.isNotBlank() }?.let { add("name: $it") }
                user.optString("created_at").substringBefore('T').takeIf { it.isNotBlank() }
                    ?.let { add("joined $it") }
            }
            hit(
                site = "GitLab",
                category = "developer",
                url = user.optString("web_url", "https://gitlab.com/$username"),
                handle = username,
                evidence = facts.joinToString(" · ").ifBlank { "Profile returned by GitLab API" },
            )
        }.getOrNull()
    }

    private fun codeberg(handle: String): UsernameHit? {
        val response = ScanHttp.get(
            "https://codeberg.org/api/v1/users/${ScanHttp.encode(handle)}",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!response.isOk || response.body.isBlank()) return null
        return runCatching {
            val root = JSONObject(response.body)
            val username = root.optString("username").takeIf { it.isNotBlank() } ?: return null
            val facts = buildList {
                root.optString("full_name").takeIf { it.isNotBlank() }?.let { add("name: $it") }
                root.optString("location").takeIf { it.isNotBlank() }?.let { add("location: $it") }
                root.optString("created").substringBefore('T').takeIf { it.isNotBlank() }?.let { add("joined $it") }
            }
            hit(
                site = "Codeberg",
                category = "developer",
                url = root.optString("html_url", "https://codeberg.org/$username"),
                handle = username,
                evidence = facts.joinToString(" · ").ifBlank { "Profile returned by Codeberg API" },
            )
        }.getOrNull()
    }

    private fun dockerHub(handle: String): UsernameHit? {
        val response = ScanHttp.get(
            "https://hub.docker.com/v2/users/${ScanHttp.encode(handle)}/",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!response.isOk || response.body.isBlank()) return null
        return runCatching {
            val root = JSONObject(response.body)
            val username = root.optString("username").takeIf { it.isNotBlank() } ?: return null
            val facts = buildList {
                root.optString("full_name").takeIf { it.isNotBlank() }?.let { add("name: $it") }
                root.optString("company").takeIf { it.isNotBlank() }?.let { add("company: $it") }
                root.optString("location").takeIf { it.isNotBlank() }?.let { add("location: $it") }
                root.optString("date_joined").substringBefore('T').takeIf { it.isNotBlank() }
                    ?.let { add("joined $it") }
            }
            hit(
                site = "Docker Hub",
                category = "developer",
                url = "https://hub.docker.com/u/$username",
                handle = username,
                evidence = facts.joinToString(" · ").ifBlank { "Profile returned by Docker Hub API" },
            )
        }.getOrNull()
    }

    private fun stackExchange(handle: String): UsernameHit? {
        val response = ScanHttp.get(
            "https://api.stackexchange.com/2.3/users?inname=${ScanHttp.encode(handle)}&site=stackoverflow",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!response.isOk || response.body.isBlank()) return null
        return runCatching {
            val items = JSONObject(response.body).optJSONArray("items") ?: return null
            for (i in 0 until items.length()) {
                val user = items.optJSONObject(i) ?: continue
                val display = user.optString("display_name").takeIf { it.isNotBlank() } ?: continue
                // inname is a fuzzy search, so only an exact (case-insensitive) handle counts.
                if (!display.equals(handle, ignoreCase = true)) continue
                val facts = buildList {
                    user.optString("location").takeIf { it.isNotBlank() }?.let { add("location: $it") }
                    user.optLong("creation_date", 0L).takeIf { it > 0 }?.let { add("joined ${dateOf(it)}") }
                    add("reputation ${user.optInt("reputation", 0)}")
                }
                return hit(
                    site = "Stack Overflow",
                    category = "developer",
                    url = user.optString("link", "https://stackoverflow.com/users/${user.optInt("user_id")}"),
                    handle = display,
                    evidence = facts.joinToString(" · "),
                )
            }
            null
        }.getOrNull()
    }

    private fun mastodon(handle: String): UsernameHit? {
        val response = ScanHttp.get(
            "https://mastodon.social/api/v1/accounts/lookup?acct=${ScanHttp.encode(handle)}",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        if (!response.isOk || response.body.isBlank()) return null
        return runCatching {
            val root = JSONObject(response.body)
            val username = root.optString("username").takeIf { it.isNotBlank() } ?: return null
            val facts = buildList {
                root.optString("display_name").takeIf { it.isNotBlank() }?.let { add("name: $it") }
                root.optString("created_at").substringBefore('T').takeIf { it.isNotBlank() }
                    ?.let { add("joined $it") }
                root.optInt("followers_count", 0).takeIf { it > 0 }?.let { add("$it followers") }
            }
            hit(
                site = "Mastodon",
                category = "social",
                url = root.optString("url", "https://mastodon.social/@$username"),
                handle = username,
                evidence = facts.joinToString(" · ").ifBlank { "Profile returned by Mastodon API" },
            )
        }.getOrNull()
    }

    private fun dateOf(epochSeconds: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(epochSeconds * 1000L))
}
