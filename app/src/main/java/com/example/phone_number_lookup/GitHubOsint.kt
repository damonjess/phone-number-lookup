package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

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
    val twitterUsername: String? = null,
    val publicRepos: Int? = null,
    val followers: Int? = null,
    val createdAt: String? = null,
    val commitIdentities: List<String> = emptyList(),
    val sshKeys: List<String> = emptyList(),
    val gpgKeys: List<String> = emptyList(),
    val gists: List<GitHubGistResult> = emptyList()
)

data class GitHubRepoResult(
    val fullName: String,
    val description: String?,
    val htmlUrl: String,
    val language: String?,
    val stars: Int?,
    val forks: Int?
)

data class GitHubCommitMatch(
    val repoName: String,
    val message: String,
    val authorName: String,
    val authorEmail: String,
    val date: String,
    val htmlUrl: String
)

data class GitHubGistResult(
    val id: String,
    val description: String,
    val files: List<String>,
    val createdAt: String,
    val htmlUrl: String
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
        val encoded = URLEncoder.encode(login, "UTF-8")
        val userDef = async { getJson("$API/users/$encoded") }
        val eventsDef = async { getJsonArray("$API/users/$encoded/events/public?per_page=40") }
        val sshDef = async { getJsonArray("$API/users/$encoded/keys") }
        val gpgDef = async { getJsonArray("$API/users/$encoded/gpg_keys") }
        val gistsDef = async { getUserGists(login) }

        val body = userDef.await() ?: return@withContext null
        val commitIdentities = extractCommitIdentities(eventsDef.await())
        val sshKeys = extractSshKeys(sshDef.await())
        val gpgKeys = extractGpgKeys(gpgDef.await())
        val gists = gistsDef.await()

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
            twitterUsername = body.optStringOrNull("twitter_username"),
            publicRepos = body.optInt("public_repos", -1).takeIf { it >= 0 },
            followers = body.optInt("followers", -1).takeIf { it >= 0 },
            createdAt = body.optStringOrNull("created_at"),
            commitIdentities = commitIdentities,
            sshKeys = sshKeys,
            gpgKeys = gpgKeys,
            gists = gists
        )
    }

    suspend fun getUserGists(login: String, limit: Int = 10): List<GitHubGistResult> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(login, "UTF-8")
            val arr = getJsonArray("$API/users/$encoded/gists?per_page=$limit") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val filesObj = obj.optJSONObject("files")
                val fileNames = filesObj?.keys()?.asSequence()?.toList().orEmpty()
                GitHubGistResult(
                    id = obj.optString("id"),
                    description = obj.optStringOrNull("description") ?: fileNames.firstOrNull() ?: "Untitled Gist",
                    files = fileNames,
                    createdAt = obj.optString("created_at").substringBefore('T'),
                    htmlUrl = obj.optString("html_url")
                )
            }
        }

    suspend fun searchCommits(query: String, limit: Int = 15): List<GitHubCommitMatch> =
        withContext(Dispatchers.IO) {
            val url = "$API/search/commits?per_page=$limit&q=${URLEncoder.encode(query, "UTF-8")}"
            val body = getJson(url) ?: return@withContext emptyList()
            val items = body.optJSONArray("items") ?: return@withContext emptyList()
            (0 until items.length()).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                val commit = item.optJSONObject("commit") ?: return@mapNotNull null
                val author = commit.optJSONObject("author")
                val repo = item.optJSONObject("repository")?.optString("full_name").orEmpty()
                GitHubCommitMatch(
                    repoName = repo,
                    message = commit.optString("message").lineSequence().firstOrNull().orEmpty(),
                    authorName = author?.optString("name").orEmpty(),
                    authorEmail = author?.optString("email").orEmpty(),
                    date = author?.optString("date")?.substringBefore('T').orEmpty(),
                    htmlUrl = item.optString("html_url")
                )
            }
        }

    internal fun extractCommitIdentities(events: JSONArray?): List<String> {
        if (events == null) return emptyList()
        val found = LinkedHashSet<String>()
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val commits = ev.optJSONObject("payload")?.optJSONArray("commits") ?: continue
            for (j in 0 until commits.length()) {
                val author = commits.optJSONObject(j)?.optJSONObject("author") ?: continue
                val email = author.optString("email").trim()
                val name = author.optString("name").trim()
                if (email.isNotBlank() && !email.endsWith("noreply.github.com", ignoreCase = true)) {
                    found += if (name.isNotBlank()) "$name <$email>" else email
                }
            }
            if (found.size >= 8) break
        }
        return found.toList()
    }

    private fun extractSshKeys(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val key = arr.optJSONObject(i)?.optString("key")?.trim().orEmpty()
            if (key.isNotBlank()) {
                val parts = key.split(' ')
                val type = parts.getOrNull(0).orEmpty()
                val body = parts.getOrNull(1)?.let { "${it.take(16)}…${it.takeLast(12)}" }.orEmpty()
                "[Key Snippet] $type $body".trim()
            } else {
                null
            }
        }.take(6)
    }

    private fun extractGpgKeys(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val keyId = obj.optString("key_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val emails = obj.optJSONArray("emails")
            val emailList = if (emails != null) {
                (0 until emails.length()).mapNotNull { j ->
                    emails.optJSONObject(j)?.optString("email")?.takeIf { it.isNotBlank() }
                }
            } else {
                emptyList()
            }
            if (emailList.isNotEmpty()) "$keyId (${emailList.joinToString(", ")})" else keyId
        }.take(6)
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
        } catch (_: Exception) {
            null
        }
    }

    private fun getJsonArray(url: String): JSONArray? {
        val response = ScanHttp.get(url, timeoutMs = 12_000, headers = JSON_HEADERS)
        if (!response.isOk || response.body.isBlank()) return null
        return try {
            JSONArray(response.body)
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key, "")
        return v.takeIf { it.isNotBlank() && it != "null" }
    }
}
