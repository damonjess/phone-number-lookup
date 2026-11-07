package com.example.phone_number_lookup

import org.json.JSONObject

typealias FastApiBridgeResult = FastApiPhoneResult

data class FastApiPhoneResult(
    val serverReachable: Boolean = false,
    val tgRegistered: Boolean = false,
    val tgUserId: Long? = null,
    val tgUsername: String? = null,
    val tgFullName: String? = null,
    val tgBio: String? = null,
    val tgLastSeen: String? = null,
    val tgIsPremium: Boolean = false,
    val tgIsVerified: Boolean = false,
    val tgIsScam: Boolean = false,
    val tgIsFake: Boolean = false,
    val tgHasPhoto: Boolean = false,
    val tgPhotoCount: Int = 0,
    val tgLatestPhotoDate: String? = null,
    val tgPhotoBase64: String? = null,
    val tgDcId: Int? = null,
    val tgDcRegion: String? = null,
    val tgEstimatedCreated: String? = null,
    val tgBirthday: String? = null,
    val tgCommonChatsCount: Int = 0,
    val tgPersonalChannelId: Long? = null,
    val tgBusinessLocation: String? = null,
    val tgHasPinnedStories: Boolean = false,
    val tcFound: Boolean = false,
    val tcName: String? = null,
    val tcCarrier: String? = null,
    val tcSpamScore: Int? = null,
    val tcSpamType: String? = null,
    val tcEmail: String? = null,
    val tcCity: String? = null,
    val breachChecked: Boolean = false,
    val breachCount: Int = 0,
    val breaches: List<String> = emptyList(),
    val hlrChecked: Boolean = false,
    val hlrLiveStatus: String? = null,
    val hlrCurrentNetwork: String? = null,
    val hlrIsPorted: Boolean = false,
    val hlrIsRoaming: Boolean = false,
    val signalDeepLink: String? = null,
    val viberDeepLink: String? = null,
    val aiEnabled: Boolean = false,
    val aiSummary: String? = null,
    val aiRiskLevel: String? = null,
    val notes: List<String> = emptyList()
)

object LocalFastApiPhoneBridge {

    /**
     * Linux Mint workstation running phone_osint_server.py.
     * Local Wi-Fi (this machine): 192.168.1.190
     * Tailscale: use your workstation's 100.x.y.z address instead, e.g. "http://100.64.0.1:8099".
     *
     * Whatever host/port you use here must also be listed in res/xml/network_security_config.xml,
     * otherwise Android blocks the plaintext HTTP request.
     */
    @Volatile
    var baseUrl: String = "http://192.168.1.190:8099"

    fun queryWorkstation(e164Phone: String, countryCode: String = "GB"): FastApiPhoneResult {
        val cleanBase = baseUrl.trim().trimEnd('/')
        if (cleanBase.isBlank()) return FastApiPhoneResult(serverReachable = false)

        val url = "$cleanBase/api/v1/phone-osint?phone=${ScanHttp.encode(e164Phone)}&country=${ScanHttp.encode(countryCode)}"
        val resp = ScanHttp.get(
            url = url,
            timeoutMs = 5_500,
            headers = mapOf("Accept" to "application/json")
        )
        if (!resp.isOk || resp.body.isBlank()) {
            return FastApiPhoneResult(
                serverReachable = false,
                notes = listOf("Workstation FastAPI unreachable ($cleanBase)")
            )
        }

        return runCatching {
            val root = JSONObject(resp.body)
            val tg = root.optJSONObject("telegram")
            val tc = root.optJSONObject("truecaller")
            val breachObj = root.optJSONObject("breach_intel")
            val breachList = mutableListOf<String>()
            breachObj?.optJSONArray("breaches")?.let { arr ->
                for (i in 0 until arr.length()) {
                    breachList.add(arr.optString(i))
                }
            }

            val hlrObj = root.optJSONObject("hlr_intel")
            val msgObj = root.optJSONObject("messaging_intel")
            val aiObj = root.optJSONObject("ai_synthesis")

            val notesArr = root.optJSONArray("notes")
            val notes = buildList {
                if (notesArr != null) {
                    for (i in 0 until notesArr.length()) {
                        notesArr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            }

            FastApiPhoneResult(
                serverReachable = true,
                tgRegistered = tg?.optBoolean("registered", false) == true,
                tgUserId = tg?.optLong("user_id", -1L)?.takeIf { it > 0L },
                tgUsername = tg?.optString("username")?.takeIf { it.isNotBlank() && it != "null" },
                tgFullName = tg?.optString("full_name")?.takeIf { it.isNotBlank() && it != "null" },
                tgBio = tg?.optString("bio")?.takeIf { it.isNotBlank() && it != "null" },
                tgLastSeen = tg?.optString("last_seen")?.takeIf { it.isNotBlank() && it != "null" },
                tgIsPremium = tg?.optBoolean("is_premium", false) == true,
                tgIsVerified = tg?.optBoolean("is_verified", false) == true,
                tgIsScam = tg?.optBoolean("is_scam", false) == true,
                tgIsFake = tg?.optBoolean("is_fake", false) == true,
                tgHasPhoto = tg?.optBoolean("has_photo", false) == true,
                tgPhotoCount = tg?.optInt("photo_count", 0) ?: 0,
                tgLatestPhotoDate = tg?.optString("latest_photo_date", "")?.takeIf { it.isNotBlank() && it != "null" },
                tgPhotoBase64 = tg?.optString("photo_base64", "")?.takeIf { it.isNotBlank() && it != "null" },
                tgDcId = if (tg == null || tg.isNull("dc_id")) null else tg.optInt("dc_id"),
                tgDcRegion = tg?.optString("dc_region", "")?.takeIf { it.isNotBlank() && it != "null" },
                tgEstimatedCreated = tg?.optString("estimated_created", "")?.takeIf { it.isNotBlank() && it != "null" },
                tgBirthday = tg?.optString("birthday", "")?.takeIf { it.isNotBlank() && it != "null" },
                tgCommonChatsCount = tg?.optInt("common_chats_count", 0) ?: 0,
                tgPersonalChannelId = if (tg == null || tg.isNull("personal_channel_id")) null else tg.optLong("personal_channel_id"),
                tgBusinessLocation = tg?.optString("business_location", "")?.takeIf { it.isNotBlank() && it != "null" },
                tgHasPinnedStories = tg?.optBoolean("has_pinned_stories", false) == true,
                tcFound = tc?.optBoolean("found", false) == true,
                tcName = tc?.optString("name")?.takeIf { it.isNotBlank() && it != "null" },
                tcCarrier = tc?.optString("carrier")?.takeIf { it.isNotBlank() && it != "null" },
                tcSpamScore = tc?.optInt("spam_score", -1)?.takeIf { it >= 0 },
                tcSpamType = tc?.optString("spam_type")?.takeIf { it.isNotBlank() && it != "null" },
                tcEmail = tc?.optString("email")?.takeIf { it.isNotBlank() && it != "null" },
                tcCity = tc?.optString("city")?.takeIf { it.isNotBlank() && it != "null" },
                breachChecked = breachObj?.optBoolean("checked", false) ?: false,
                breachCount = breachObj?.optInt("breach_count", 0) ?: 0,
                breaches = breachList,
                hlrChecked = hlrObj?.optBoolean("checked", false) ?: false,
                hlrLiveStatus = hlrObj?.optString("live_status", "")?.takeIf { it.isNotBlank() && it != "null" },
                hlrCurrentNetwork = hlrObj?.optString("current_network", "")?.takeIf { it.isNotBlank() && it != "null" },
                hlrIsPorted = hlrObj?.optBoolean("is_ported", false) ?: false,
                hlrIsRoaming = hlrObj?.optBoolean("is_roaming", false) ?: false,
                signalDeepLink = msgObj?.optString("signal_deep_link", "")?.takeIf { it.isNotBlank() && it != "null" },
                viberDeepLink = msgObj?.optString("viber_deep_link", "")?.takeIf { it.isNotBlank() && it != "null" },
                aiEnabled = aiObj?.optBoolean("enabled", false) ?: false,
                aiSummary = aiObj?.optString("summary", "")?.takeIf { it.isNotBlank() && it != "null" },
                aiRiskLevel = aiObj?.optString("risk_level", "UNRATED"),
                notes = notes
            )
        }.getOrDefault(FastApiPhoneResult(serverReachable = false))
    }
}
