package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SearchResultItem(
    val title: String,
    val snippet: String,
    val url: String,
    val source: String
)

object SearchRepository {

    suspend fun searchWeb(phoneNumber: String, localNumber: String): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SearchResultItem>()
        
        try {
            val query = "\"$phoneNumber\" OR \"$localNumber\""
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlString = "https://html.duckduckgo.com/html/?q=$encodedQuery"
            
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            if (connection.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val html = reader.readText()
                reader.close()

                // Parse HTML results using Regex
                // DuckDuckGo html format has result__a for title/link and result__snippet for snippet
                val resultPattern = Regex(
                    """<a class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>.*?<a class="result__snippet"[^>]*>(.*?)</a>""",
                    RegexOption.DOT_MATCHES_ALL
                )

                val matches = resultPattern.findAll(html)
                for (match in matches.take(8)) {
                    var link = match.groupValues[1]
                    // Clean up DuckDuckGo redirect link if present
                    if (link.contains("uddg=")) {
                        val uri = android.net.Uri.parse("https://duckduckgo.com$link")
                        link = uri.getQueryParameter("uddg") ?: link
                    }

                    val rawTitle = match.groupValues[2].replace(Regex("<[^>]*>"), "").trim()
                    val rawSnippet = match.groupValues[3].replace(Regex("<[^>]*>"), "").trim()

                    val cleanTitle = unescapeHtml(rawTitle)
                    val cleanSnippet = unescapeHtml(rawSnippet)

                    if (cleanTitle.isNotEmpty() && cleanSnippet.isNotEmpty()) {
                        results.add(
                            SearchResultItem(
                                title = cleanTitle,
                                snippet = cleanSnippet,
                                url = link,
                                source = "DuckDuckGo"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext results
    }

    private fun unescapeHtml(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
    }
}
