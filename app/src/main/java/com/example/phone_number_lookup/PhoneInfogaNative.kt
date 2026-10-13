package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Native PhoneInfoga-compatible modules.
 *
 * The local module is provided by PhoneNumberUtils. Numverify is optional because
 * PhoneInfoga requires an apilayer API key for carrier and line-type data.
 */
data class PhoneInfogaNumverifyResult(
    val valid: Boolean?,
    val number: String?,
    val localFormat: String?,
    val internationalFormat: String?,
    val countryPrefix: String?,
    val countryCode: String?,
    val countryName: String?,
    val location: String?,
    val carrier: String?,
    val lineType: String?,
    val error: String? = null
)

object PhoneInfogaNative {
    private const val API_URL = "https://api.apilayer.com/number_verification/validate"

    suspend fun queryNumverify(number: String, apiKey: String): PhoneInfogaNumverifyResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext PhoneInfogaNumverifyResult(
                    valid = null, number = null, localFormat = null, internationalFormat = null,
                    countryPrefix = null, countryCode = null, countryName = null, location = null,
                    carrier = null, lineType = null,
                    error = "Numverify requires an apilayer API key."
                )
            }

            val response = ScanHttp.get(
                "$API_URL?number=${ScanHttp.encode(number)}",
                timeoutMs = 15_000,
                headers = mapOf("apikey" to apiKey.trim(), "Accept" to "application/json")
            )
            if (response.isNetworkError) return@withContext error("Numverify network request failed.")
            if (!response.isOk) {
                val message = when (response.code) {
                    401, 403 -> "Numverify rejected the API key. Check it and try again."
                    429 -> "Numverify rate limit reached. Try again later."
                    else -> "Numverify unavailable (HTTP ${response.code})."
                }
                return@withContext error(message)
            }

            try {
                val json = JSONObject(response.body)
                if (json.has("success") && !json.optBoolean("success", true)) {
                    return@withContext error(json.optString("error", "Numverify returned an error."))
                }
                PhoneInfogaNumverifyResult(
                    valid = json.optBooleanOrNull("valid"),
                    number = json.optStringOrNull("number"),
                    localFormat = json.optStringOrNull("local_format"),
                    internationalFormat = json.optStringOrNull("international_format"),
                    countryPrefix = json.optStringOrNull("country_prefix"),
                    countryCode = json.optStringOrNull("country_code"),
                    countryName = json.optStringOrNull("country_name"),
                    location = json.optStringOrNull("location"),
                    carrier = json.optStringOrNull("carrier"),
                    lineType = json.optStringOrNull("line_type")
                )
            } catch (_: Exception) {
                error("Numverify returned an invalid response.")
            }
        }

    private fun error(message: String) = PhoneInfogaNumverifyResult(
        valid = null, number = null, localFormat = null, internationalFormat = null,
        countryPrefix = null, countryCode = null, countryName = null, location = null,
        carrier = null, lineType = null, error = message
    )

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() && it != "null" } else null

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null
}
