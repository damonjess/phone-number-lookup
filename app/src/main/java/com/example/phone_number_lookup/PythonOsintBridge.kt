package com.example.phone_number_lookup

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class PythonToolMode(val label: String) {
    SHERLOCK_PY("Python: Sherlock"),
    TOOKIE_PY("Python: Tookie"),
    BLACKBIRD_PY("Python: Blackbird"),
    ALL_PYTHON("Python: All 3")
}

object PythonOsintBridge {

    fun ensureStarted(context: Context) {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
        } catch (_: Exception) {
            // Python is already initialized; safely ignore
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
        val module = py.getModule("upstream_osint")
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
                val raw = module.callAttr("run_all_python", username, effectiveLimit).toString()
                val results = org.json.JSONArray(raw)
                (0 until results.length()).map { results.getJSONObject(it).toString() }
            }
        }

        mergeUsernamePayloads(username, payloads)
    }

    suspend fun scanEmailBlackbirdPython(
        context: Context,
        email: String
    ): EmailRegistrationOutcome = withContext(Dispatchers.IO) {
        ensureStarted(context)
        val py = Python.getInstance()
        val module = py.getModule("upstream_osint")
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
                        confidence = "Medium (Upstream Match)",
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
                            confidence = "Medium (Upstream Match)",
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
                        val reason = when (code) {
                            0 -> ProbeFailure.NETWORK
                            401, 403 -> ProbeFailure.FORBIDDEN
                            429 -> ProbeFailure.RATE_LIMITED
                            in 500..599 -> ProbeFailure.SERVER_ERROR
                            else -> ProbeFailure.HTTP_ERROR
                        }
                        issues += SiteIssue(
                            site = iss.optString("site", "Unknown"),
                            profileUrl = iss.optString("profileUrl", ""),
                            reason = reason,
                            detail = iss.optString("detail", "")
                        )
                    }
                }
            }
        }

        val dedupedHits = hits.distinctBy { SiteKey.of(it.profileUrl).ifEmpty { it.site.lowercase() } }
            .sortedWith(compareBy({ it.category.lowercase() }, { it.site.lowercase() }))

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
