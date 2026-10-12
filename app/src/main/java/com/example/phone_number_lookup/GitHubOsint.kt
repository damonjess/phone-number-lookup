package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * GitHub OSINT through the public REST API. These endpoints work without a
 * token (rate limited to ~60 requests/hour, search ~10/minute per IP):
 *
 *   GET https://api.github.com/search/users?q={query}
 *   GET https://api.github.com/search/repositories?q={query}
 *   GET https://api.github.com/users/{login}
 *   GET https://api.github.com/search/commits?q={query}
 */
data class GitHubUserResult(
    val login: String,
    val htmlUrl: String,
    val avatarUrl: String,
    val name: String? = null,
    val bio: String? = null,
    val company: String? = null,
    val location: String? = null,
    val blog: String? = null,
    val email: String? = null,
    val publicRepos: Int? = null,
    val followers: Int? = null,
    val createdAt: String? = null
)

data class GitHubRepoResult(
    val fullName: String,
    val description: String?,
    val htmlUrl: String,
    val language: String?,
    val stars: Int?,
    val forks: Int?
)

data class GitHubScanError(val message: String)

object GitHubOsint {

    private const val API = "https://api.github.com"
    private val JSON_HEADERS = mapOf("Accept" to "application/vnd.github+json")

    suspend fun searchUsers(query: String, limit: Int = 20): List<GitHubUserResult> =
        withContext(Dispatchers.IO) {
            val url = "$API/search/users?per_page=$limit&q=${URLEncoder.encode(query, "UTF-8")}"
            val body = getJson(url) ?: return@withContext emptyList()
            val items = body.optJSONArray("items") ?: return@withContext emptyList()
            (0 until items.length()).mapNotNull { i ->
                val o = items.optJSONObject(i) ?: return@mapNotNull null
                GitHubUserResult(
                    login = o.optString("login"),
                    htmlUrl = o.optString("html_url"),
                    avatarUrl = o.optString("avatar_url")
                )
            }
        }

    suspend fun getUser(login: String): GitHubUserResult? = withContext(Dispatchers.IO) {
        val body = getJson("$API/users/${URLEncoder.encode(login, "UTF-8")}") ?: return@withContext null
        GitHubUserResult(
            login = body.optString("login", login),
            htmlUrl = body.optString("html_url"),
            avatarUrl = body.optString("avatar_url"),
            name = body.optStringOrNull("name"),
            bio = body.optStringOrNull("bio"),
            company = body.optStringOrNull("company"),
            location = body.optStringOrNull("location"),
            blog = body.optStringOrNull("blog"),
            email = body.optStringOrNull("email"),
            publicRepos = body.optInt("public_repos", -1).takeIf { it >= 0 },
            followers = body.optInt("followers", -1).takeIf { it >= 0 },
            createdAt = body.optStringOrNull("created_at")
        )
    }

    suspend fun searchRepos(query: String, limit: Int = 20): List<GitHubRepoResult> =
        withContext(Dispatchers.IO) {
            val url = "$API/search/repositories?per_page=$limit&q=${URLEncoder.encode(query, "UTF-8")}"
            val body = getJson(url) ?: return@withContext emptyList()
            val items = body.optJSONArray("items") ?: return@withContext emptyList()
            (0 until items.length()).mapNotNull { i ->
                val o = items.optJSONObject(i) ?: return@mapNotNull null
                GitHubRepoResult(
                    fullName = o.optString("full_name"),
                    description = o.optStringOrNull("description"),
                    htmlUrl = o.optString("html_url"),
                    language = o.optStringOrNull("language"),
                    stars = o.optInt("stargazers_count", -1).takeIf { it >= 0 },
                    forks = o.optInt("forks_count", -1).takeIf { it >= 0 }
                )
            }
        }

    private fun getJson(url: String): JSONObject? {
        val response = ScanHttp.get(url, timeoutMs = 15_000, headers = JSON_HEADERS)
        if (!response.isOk || response.body.isBlank()) return null
        return try {
            JSONObject(response.body)
        } catch (e: Exception) {
            null
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key, "")
        return v.takeIf { it.isNotBlank() && it != "null" }
    }
}
