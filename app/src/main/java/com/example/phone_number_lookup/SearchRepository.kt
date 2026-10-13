package com.example.phone_number_lookup

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class SearchResultItem(
    val title: String,
    val snippet: String,
    val url: String,
    val source: String
)

data class SearchResponse(
    val results: List<SearchResultItem>,
    val error: String? = null
)

object SearchRepository {
    private data class Cached(val response: SearchResponse, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private const val CACHE_MS = 24L * 60L * 60L * 1000L

    suspend fun searchWeb(numberFormats: List<String>): SearchResponse = withContext(Dispatchers.IO) {
        val formats = numberFormats.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val cacheKey = formats.joinToString("|")
        cache[cacheKey]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return@withContext it.response }
        var connection: HttpURLConnection? = null
        try {
            val query = formats.joinToString(" OR ") { "\"$it\"" }
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            connection = URL("https://html.duckduckgo.com/html/?q=$encodedQuery")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
            )

            val code = connection.responseCode
            if (code !in 200..299) {
                return@withContext SearchResponse(emptyList(), "Web search unavailable (HTTP $code).")
            }

            val html = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
            val resultPattern = Regex(
                """<a class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>.*?<a class="result__snippet"[^>]*>(.*?)</a>""",
                RegexOption.DOT_MATCHES_ALL
            )
            val results = resultPattern.findAll(html).take(8).mapNotNull { match ->
                var link = match.groupValues[1]
                if (link.contains("uddg=")) {
                    link = Uri.parse("https://duckduckgo.com$link").getQueryParameter("uddg") ?: link
                }
                val title = unescapeHtml(match.groupValues[2].replace(Regex("<[^>]*>"), "").trim())
                val snippet = unescapeHtml(match.groupValues[3].replace(Regex("<[^>]*>"), "").trim())
                if (title.isBlank() || snippet.isBlank()) null
                else SearchResultItem(title, snippet, link, "DuckDuckGo")
            }.toList()
            SearchResponse(results).also {
                cache[cacheKey] = Cached(it, System.currentTimeMillis() + CACHE_MS)
            }
        } catch (_: java.net.SocketTimeoutException) {
            SearchResponse(emptyList(), "Web search timed out.")
        } catch (_: Exception) {
            SearchResponse(emptyList(), "Web search failed or was blocked.")
        } finally {
            connection?.disconnect()
        }
    }

    private fun unescapeHtml(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
}
