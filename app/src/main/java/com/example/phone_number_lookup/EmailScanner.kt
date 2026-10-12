package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Live email breach check using the XposedOrNot public API. No API key is
 * required for the basic lookup:
 *
 *   https://api.xposedornot.com/v1/check-email/{email}
 *
 * A hit looks like: {"breaches":[["BreachA","BreachB"]],"email":"...","status":"success"}
 * A miss looks like: {"Error":"Not found","email":null}
 */
data class BreachReport(
    val email: String,
    val breached: Boolean,
    val breachNames: List<String>,
    val error: String? = null
)

object EmailScanner {

    suspend fun check(email: String): BreachReport = withContext(Dispatchers.IO) {
        val clean = email.trim()
        if (clean.isEmpty() || !clean.contains("@")) {
            return@withContext BreachReport(clean, false, emptyList(), "Enter a valid email address")
        }

        val encoded = URLEncoder.encode(clean, "UTF-8")
        val response = ScanHttp.get(
            "https://api.xposedornot.com/v1/check-email/$encoded",
            timeoutMs = 15_000
        )

        if (response.isNetworkError) {
            return@withContext BreachReport(clean, false, emptyList(), "Network error contacting XposedOrNot")
        }
        if (response.body.isBlank()) {
            return@withContext BreachReport(clean, false, emptyList(), "Empty response from XposedOrNot")
        }

        parseBreach(clean, response.body)
    }

    internal fun parseBreach(email: String, body: String): BreachReport {
        return try {
            val root = JSONObject(body)
            if (root.has("Error")) {
                return BreachReport(email, false, emptyList())
            }
            val breaches = root.optJSONArray("breaches")
            val names = mutableListOf<String>()
            if (breaches != null) {
                // Shape is a nested array: [[ "BreachA", "BreachB", ... ]]
                for (i in 0 until breaches.length()) {
                    val inner: JSONArray? = breaches.optJSONArray(i)
                    if (inner != null) {
                        for (j in 0 until inner.length()) {
                            inner.optString(j).takeIf { it.isNotBlank() }?.let { names.add(it) }
                        }
                    } else {
                        breaches.optString(i).takeIf { it.isNotBlank() }?.let { names.add(it) }
                    }
                }
            }
            BreachReport(email, names.isNotEmpty(), names.distinct())
        } catch (e: Exception) {
            BreachReport(email, false, emptyList(), "Could not parse XposedOrNot response")
        }
    }
}
