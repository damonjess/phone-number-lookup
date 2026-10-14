package com.example.phone_number_lookup

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal, dependency-free HTTP client.
 *
 * Deliberately built on java.net.HttpURLConnection so the app needs no third-party
 * networking library and every OSINT request stays transparent and free.
 *
 * Supports GET and POST because the community site databases (Sherlock, Blackbird)
 * describe some checks as POST requests with a JSON/text body and custom headers.
 */
data class HttpResponse(
    val code: Int,
    val body: String,
    /** URL after redirects were followed, needed for Sherlock `response_url` checks. */
    val finalUrl: String = ""
) {
    val isNetworkError: Boolean get() = code == -1
    val isOk: Boolean get() = code in 200..299
}

object ScanHttp {

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** Performs a GET request. */
    fun get(
        url: String,
        timeoutMs: Int = 12_000,
        headers: Map<String, String> = emptyMap(),
        maxBodyChars: Int = DEFAULT_MAX_BODY_CHARS
    ): HttpResponse = request("GET", url, null, timeoutMs, headers, maxBodyChars)

    /** Performs a POST request with an optional raw body. */
    fun post(
        url: String,
        body: String? = null,
        contentType: String? = "application/json",
        timeoutMs: Int = 12_000,
        headers: Map<String, String> = emptyMap(),
        maxBodyChars: Int = DEFAULT_MAX_BODY_CHARS
    ): HttpResponse {
        val merged = if (contentType != null) headers + ("Content-Type" to contentType) else headers
        return request("POST", url, body, timeoutMs, merged, maxBodyChars)
    }

    /** Performs a request. Returns [HttpResponse] with code -1 when it fails outright. */
    fun request(
        method: String,
        url: String,
        body: String?,
        timeoutMs: Int = 12_000,
        headers: Map<String, String> = emptyMap(),
        maxBodyChars: Int = DEFAULT_MAX_BODY_CHARS
    ): HttpResponse {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method.uppercase()
                instanceFollowRedirects = true
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }

            if (body != null && method.equals("POST", ignoreCase = true)) {
                connection.doOutput = true
                OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                    writer.write(body)
                }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, "UTF-8")).use { reader -> readCapped(reader, maxBodyChars) }
            } ?: ""
            HttpResponse(code, text, connection.url?.toString().orEmpty())
        } catch (e: Exception) {
            HttpResponse(-1, "", "")
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    /**
     * Profile pages can be several MB each and a scan fires hundreds of them. The "found / not found"
     * markers sit in the first part of the page, so reading is capped. Dataset downloads pass a
     * much larger cap so the JSON is never truncated.
     */
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

    /** URL-encodes a path/query component. */
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
