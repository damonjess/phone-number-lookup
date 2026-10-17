package com.example.phone_number_lookup

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class PythonToolMode(val label: String) {
    NATIVE_KOTLIN("Native Kotlin"),
    SHERLOCK_PY("Python: Sherlock"),
    TOOKIE_PY("Python: Tookie"),
    BLACKBIRD_PY("Python: Blackbird"),
    ALL_PYTHON("Python: All 3")
}

object PythonOsintBridge {

    fun ensureStarted(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
    }

    suspend fun scanUsername(
        context: Context,
        username: String,
        mode: PythonToolMode,
        limit: Int
    ): UsernameScanOutcome = withContext(Dispatchers.IO) {
        ensureStarted(context)
        val py = Python.getInstance()
        val module = py.getModule("osint_bridge")
        val effectiveLimit = if (limit <= 0) 0 else limit

        val payloads: List<String> = when (mode) {
            PythonToolMode.SHERLOCK_PY -> listOf(
                module.callAttr("run_sherlock_python", username, effectiveLimit).toString()
            )
            PythonToolMode.TOOKIE_PY -> listOf(
                module.callAttr("run_tookie_python", username, effectiveLimit).toString()
            )
            PythonToolMode.BLACKBIRD_PY -> listOf(
                module.callAttr("run_blackbird_python", username, false, effectiveLimit).toString()
            )
            PythonToolMode.ALL_PYTHON -> {
                val perTool = if (effectiveLimit > 0) (effectiveLimit / 3).coerceAtLeast(50) else 0
                val s = async { module.callAttr("run_sherlock_python", username, perTool).toString() }
                val t = async { module.callAttr("run_tookie_python", username, perTool).toString() }
                val b = async { module.callAttr("run_blackbird_python", username, false, perTool).toString() }
                listOf(s.await(), t.await(), b.await())
            }
            PythonToolMode.NATIVE_KOTLIN -> emptyList()
        }

        mergeUsernamePayloads(username, payloads)
    }

    suspend fun scanEmailBlackbirdPython(
        context: Context,
        email: String
    ): EmailRegistrationOutcome = withContext(Dispatchers.IO) {
        ensureStarted(context)
        val py = Python.getInstance()
        val module = py.getModule("osint_bridge")
        val raw = module.callAttr("run_blackbird_python", email, true, 0).toString()

        runCatching {
            val root = JSONObject(raw)
            val err = root.optString("error").takeIf { it.isNotBlank() }
            val sitesChecked = root.optInt("sitesChecked", 0)
            val hitsArr = root.optJSONArray("hits")
            val hits = mutableListOf<RegistrationHit>()
            if (hitsArr != null) {
                for (i in 0 until hitsArr.length()) {
                    val h = hitsArr.optJSONObject(i) ?: continue
                    hits += RegistrationHit(
                        site = h.optString("site", "Unknown"),
                        category = h.optString("category", "general"),
                        source = h.optString("source", "Python: Blackbird"),
                        profileUrl = h.optString("profileUrl", ""),
                        statusCode = h.optInt("statusCode", 200),
                        evidence = h.optString("evidence", "Matched via Python Blackbird"),
                        confidence = "High",
                        verificationStatus = "Registered response"
                    )
                }
            }
            val discardedArr = root.optJSONArray("discarded")
            val discarded = if (discardedArr != null) {
                (0 until discardedArr.length()).mapNotNull { discardedArr.optString(it).takeIf { s -> s.isNotBlank() } }
            } else emptyList()
            val issuesCount = root.optJSONArray("issues")?.length() ?: 0

            EmailRegistrationOutcome(
                email = email,
                sitesChecked = sitesChecked,
                registered = hits,
                unreachable = issuesCount,
                error = err,
                discarded = discarded
            )
        }.getOrElse { ex ->
            EmailRegistrationOutcome(
                email = email,
                sitesChecked = 0,
                registered = emptyList(),
                error = "Python Blackbird error: ${ex.message}"
            )
        }
    }

    private fun mergeUsernamePayloads(username: String, payloads: List<String>): UsernameScanOutcome {
        var totalChecked = 0
        val hits = ArrayList<UsernameHit>()
        val discarded = ArrayList<String>()
        val issues = ArrayList<SiteIssue>()
        val datasetCounts = LinkedHashMap<String, Int>()

        for (raw in payloads) {
            runCatching {
                val root = JSONObject(raw)
                val checked = root.optInt("sitesChecked", 0)
                totalChecked += checked

                val hitsArr = root.optJSONArray("hits")
                if (hitsArr != null) {
                    for (i in 0 until hitsArr.length()) {
                        val h = hitsArr.optJSONObject(i) ?: continue
                        val src = h.optString("source", "Python")
                        datasetCounts[src] = (datasetCounts[src] ?: 0) + 1
                        hits += UsernameHit(
                            site = h.optString("site", "Unknown"),
                            category = h.optString("category", "general"),
                            source = src,
                            profileUrl = h.optString("profileUrl", ""),
                            statusCode = h.optInt("statusCode", 200),
                            evidence = h.optString("evidence", "Matched in Python"),
                            confidence = "High",
                            verificationStatus = "Matched",
                            queriedName = username
                        )
                    }
                }

                val discArr = root.optJSONArray("discarded")
                if (discArr != null) {
                    for (i in 0 until discArr.length()) {
                        discArr.optString(i).takeIf { it.isNotBlank() }?.let { discarded += it }
                    }
                }

                val issArr = root.optJSONArray("issues")
                if (issArr != null) {
                    for (i in 0 until issArr.length()) {
                        val iss = issArr.optJSONObject(i) ?: continue
                        val code = iss.optInt("code", 0)
                        val reasonStr = when (code) {
                            0 -> "Network error / timeout"
                            401, 403 -> "HTTP 403 Forbidden"
                            429 -> "HTTP 429 Rate Limited"
                            in 500..599 -> "HTTP Server Error ($code)"
                            else -> "HTTP Error ($code)"
                        } + (iss.optString("detail").takeIf { it.isNotBlank() }?.let { " - $it" } ?: "")
                        issues += SiteIssue(
                            site = iss.optString("site", "Unknown"),
                            reason = reasonStr,
                            url = iss.optString("profileUrl", "")
                        )
                    }
                }
            }
        }

        val dedupedHits = hits.distinctBy { h ->
            h.profileUrl.lowercase().trimEnd('/').replace("https://", "").replace("http://", "").replace("www.", "").ifEmpty { h.site.lowercase() }
        }.sortedWith(compareBy({ it.category.lowercase() }, { it.site.lowercase() }))

        return UsernameScanOutcome(
            sitesChecked = totalChecked,
            confirmed = dedupedHits,
            datasetSizes = datasetCounts,
            unreachable = issues.size,
            discarded = discarded.distinct(),
            namesChecked = listOf(username),
            issues = issues.distinctBy { it.site.lowercase() }
        )
    }
}
