package com.example.phone_number_lookup

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class HttpResponse(
    val code: Int,
    val body: String,
    val finalUrl: String = ""
) {
    val isNetworkError: Boolean get() = code == -1
    val isOk: Boolean get() = code in 200..299
}

object ScanHttp {

    val USER_AGENTS = listOf(
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    )

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private const val MAX_REDIRECTS = 5

    fun get(
        url: String,
        timeoutMs: Int = 12_000,
        headers: Map<String, String> = emptyMap(),
        maxBodyChars: Int = DEFAULT_MAX_BODY_CHARS
    ): HttpResponse = request("GET", url, null, timeoutMs, headers, maxBodyChars)

    fun post(
        url: String,
        body: String? = null,
        contentType: String? = "application/json",
        timeoutMs: Int = 12_000,
        headers: Map<String, String> = emptyMap(),
        maxBodyChars: Int = DEFAULT_MAX_BODY_CHARS
    ): HttpResponse {
        val merged = if (contentType != null && !headers.keys.any { it.equals("Content-Type", true) }) {
            headers + ("Content-Type" to contentType)
        } else {
            headers
        }
        return request("POST", url, body, timeoutMs, merged, maxBodyChars)
    }

    fun request(
        method: String,
        url: String,
        body: String?,
        timeoutMs: Int = 12_000,
        headers: Map<String, String> = emptyMap(),
        maxBodyChars: Int = DEFAULT_MAX_BODY_CHARS
    ): HttpResponse {
        var currentUrl = url
        var currentMethod = method.uppercase()
        var currentBody = body

        for (hop in 0..MAX_REDIRECTS) {
            var connection: HttpURLConnection? = null
            try {
                val targetUrl = URL(currentUrl)
                connection = (targetUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = currentMethod
                    instanceFollowRedirects = false
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("User-Agent", headers["User-Agent"] ?: USER_AGENTS.random())
                    setRequestProperty(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7"
                    )
                    setRequestProperty("Accept-Language", "en-GB,en-US;q=0.9,en;q=0.8")
                    headers.forEach { (key, value) -> setRequestProperty(key, value) }
                }

                if (currentBody != null && currentMethod == "POST") {
                    connection.doOutput = true
                    OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                        writer.write(currentBody)
                    }
                }

                val code = connection.responseCode
                if (code in setOf(301, 302, 303, 307, 308) && hop < MAX_REDIRECTS) {
                    val location = connection.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        currentUrl = URL(targetUrl, location).toString()
                        if (code == 303 || ((code == 301 || code == 302) && currentMethod == "POST")) {
                            currentMethod = "GET"
                            currentBody = null
                        }
                        connection.disconnect()
                        continue
                    }
                }

                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.let {
                    BufferedReader(InputStreamReader(it, "UTF-8")).use { reader ->
                        readCapped(reader, maxBodyChars)
                    }
                } ?: ""
                val resolvedUrl = connection.url?.toString()?.takeIf { it.isNotBlank() } ?: currentUrl
                return HttpResponse(code, text, resolvedUrl)
            } catch (_: Exception) {
                return HttpResponse(-1, "", currentUrl)
            } finally {
                try {
                    connection?.disconnect()
                } catch (_: Exception) {
                }
            }
        }
        return HttpResponse(-1, "", currentUrl)
    }

    const val DEFAULT_MAX_BODY_CHARS = 512_000

    private fun readCapped(reader: Reader, maxChars: Int): String {
        val sb = StringBuilder()
        val buffer = CharArray(8192)
        while (sb.length < maxChars) {
            val n = reader.read(buffer, 0, minOf(buffer.size, maxChars - sb.length))
            if (n < 0) break
            sb.append(buffer, 0, n)
        }
        return sb.toString()
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
