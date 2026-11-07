package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class MaskedEmailHint(
    val rawMasked: String,
    val source: String,
    val exactLengthKnown: Boolean = true
)

data class UnmaskedEmailCandidate(
    val maskedHint: String,
    val resolvedEmail: String,
    val matchedFrom: String,
    val confidenceScore: Int,
    val reasoning: String
)

data class MaskedPivotReport(
    val hintsFound: List<MaskedEmailHint> = emptyList(),
    val unmaskedCandidates: List<UnmaskedEmailCandidate> = emptyList(),
    val permutationsTested: Int = 0
)

object MaskedEmailPivotEngine {

    // Matches patterns like "d*******s@gmail.com", "da***86@g****.com", "j***@outlook.co.uk"
    private val MASKED_EMAIL_REGEX = Regex(
        """\b([a-zA-Z0-9._-]{1,8}\*{1,16}[a-zA-Z0-9._-]{0,6})@([a-zA-Z0-9*.-]+\.[a-zA-Z]{2,12})\b"""
    )

    private val COMMON_UK_AND_GLOBAL_DOMAINS = listOf(
        "gmail.com",
        "googlemail.com",
        "outlook.com",
        "hotmail.com",
        "hotmail.co.uk",
        "live.co.uk",
        "live.com",
        "icloud.com",
        "yahoo.com",
        "yahoo.co.uk",
        "btinternet.com",
        "sky.com",
        "virginmedia.com",
        "talktalk.net",
        "proton.me",
        "protonmail.com",
        "aol.com"
    )

    suspend fun runPivot(
        e164Phone: String,
        compactNationalPhone: String,
        discoveredNames: List<AttributedValue>,
        discoveredHandles: List<String>,
        discoveredEmails: List<AttributedValue>,
        webSnippets: List<String> = emptyList(),
        manualMaskedHints: List<String> = emptyList()
    ): MaskedPivotReport = withContext(Dispatchers.IO) {
        val hints = LinkedHashMap<String, MaskedEmailHint>()

        // 1. Add any manually supplied masked hints
        manualMaskedHints.forEach { raw ->
            val clean = raw.trim().lowercase()
            if ('*' in clean && '@' in clean) {
                hints[clean] = MaskedEmailHint(clean, "Manual / Recovery Input", exactLengthKnown = true)
            }
        }

        // 2. Extract masked emails from scraped web snippets
        webSnippets.forEach { snippet ->
            extractMaskedEmailsFromText(snippet, "Web Footprint").forEach { hint ->
                hints.putIfAbsent(hint.rawMasked.lowercase(), hint)
            }
        }

        // 3. Extract masked emails from any recent In-App Browser recovery page
        NativeWebScraper.lastCapturedHtml.value?.second?.let { html ->
            extractMaskedEmailsFromText(html, "In-App Browser DOM").forEach { hint ->
                hints.putIfAbsent(hint.rawMasked.lowercase(), hint)
            }
        }

        // 4. Probe Instagram / Meta lookup for obfuscated recovery email tied to phone or handles
        val lookupTargets = buildList {
            add(e164Phone)
            add(compactNationalPhone)
            addAll(discoveredHandles.take(3))
        }.distinct()

        for (target in lookupTargets) {
            probeInstagramMaskedEmail(target)?.let { hint ->
                hints.putIfAbsent(hint.rawMasked.lowercase(), hint)
            }
        }

        // 5. Build the candidate dictionary (handles + real-name permutations + known emails)
        val candidatePool = generateCandidateLocalParts(
            names = discoveredNames.map { it.value },
            handles = discoveredHandles,
            knownEmails = discoveredEmails.map { it.value }
        )

        val knownDomains = (discoveredEmails.mapNotNull {
            it.value.substringAfter('@', "").lowercase().takeIf { d -> '.' in d }
        } + COMMON_UK_AND_GLOBAL_DOMAINS).distinct()

        // 6. Unmask every discovered hint against the candidate pool
        val unmasked = mutableListOf<UnmaskedEmailCandidate>()
        for (hint in hints.values) {
            unmasked += unmaskHint(
                hint = hint,
                candidateLocalParts = candidatePool,
                candidateDomains = knownDomains
            )
        }

        MaskedPivotReport(
            hintsFound = hints.values.toList(),
            unmaskedCandidates = unmasked
                .distinctBy { "${it.maskedHint}|${it.resolvedEmail}" }
                .sortedByDescending { it.confidenceScore },
            permutationsTested = candidatePool.size
        )
    }

    internal fun extractMaskedEmailsFromText(text: String, source: String): List<MaskedEmailHint> {
        if (text.isBlank() || !text.contains('*')) return emptyList()
        return MASKED_EMAIL_REGEX.findAll(text).mapNotNull { m ->
            val full = m.value.lowercase().trimEnd('.')
            val local = m.groupValues[1]
            if (local.count { it == '*' } >= 1) {
                MaskedEmailHint(rawMasked = full, source = source, exactLengthKnown = true)
            } else null
        }.distinctBy { it.rawMasked }.toList()
    }

    /**
     * Queries Instagram's unauthenticated lookup endpoint with a phone number or handle
     * to check if it leaks an obfuscated_email (e.g. "d*******s@gmail.com").
     */
    private fun probeInstagramMaskedEmail(phoneOrHandle: String): MaskedEmailHint? {
        if (phoneOrHandle.isBlank()) return null
        val resp = ScanHttp.post(
            url = "https://www.instagram.com/api/v1/users/lookup/",
            body = "q=${ScanHttp.encode(phoneOrHandle)}",
            contentType = "application/x-www-form-urlencoded",
            timeoutMs = 6_000,
            headers = mapOf(
                "X-IG-App-ID" to "936619743392459",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "https://www.instagram.com/accounts/password/reset/"
            )
        )
        if (!resp.isOk || resp.body.isBlank()) return null
        return runCatching {
            val json = JSONObject(resp.body)
            val obfEmail = json.optString("obfuscated_email").trim().lowercase()
            if (obfEmail.isNotBlank() && '@' in obfEmail && '*' in obfEmail) {
                MaskedEmailHint(
                    rawMasked = obfEmail,
                    source = "Instagram Recovery Lookup ($phoneOrHandle)",
                    exactLengthKnown = true
                )
            } else null
        }.getOrNull()
    }

    /**
     * Generates a map of [localPart -> originLabel] from discovered usernames, full names,
     * numeric suffixes (e.g. birth years / handle digits), and known emails.
     */
    internal fun generateCandidateLocalParts(
        names: List<String>,
        handles: List<String>,
        knownEmails: List<String>
    ): Map<String, String> {
        val pool = LinkedHashMap<String, String>()

        // 1. Direct known emails
        knownEmails.forEach { email ->
            val local = email.substringBefore('@').trim().lowercase()
            if (local.length >= 3 && '*' !in local) {
                pool[local] = "Discovered Email ($email)"
            }
        }

        // 2. Direct handles & stripped handle variants
        val discoveredDigits = LinkedHashSet<String>()
        handles.forEach { rawHandle ->
            val clean = rawHandle.trim().removePrefix("@").lowercase()
            if (clean.length >= 3 && ' ' !in clean) {
                pool.putIfAbsent(clean, "Handle @$clean")
                val stripped = clean.replace(Regex("[^a-z0-9]"), "")
                if (stripped.length >= 3) {
                    pool.putIfAbsent(stripped, "Normalized Handle @$clean")
                }
                val baseNoDigits = stripped.replace(Regex("\\d+$"), "")
                val trailingDigits = Regex("\\d+$").find(stripped)?.value
                if (baseNoDigits.length >= 3) {
                    pool.putIfAbsent(baseNoDigits, "Handle Root @$clean")
                }
                if (!trailingDigits.isNullOrBlank()) {
                    discoveredDigits += trailingDigits
                }
            }
        }

        // 3. Real-name permutations (e.g., "Damon Kirby" -> damonkirby, damon.kirby, dkirby, damonkirby86...)
        names.forEach { rawName ->
            val tokens = rawName.lowercase()
                .replace(Regex("[^a-z\\s]"), " ")
                .trim()
                .split(Regex("\\s+"))
                .filter { it.length >= 2 }

            if (tokens.size >= 2) {
                val first = tokens.first()
                val last = tokens.last()
                val fInitial = first.first()
                val lInitial = last.first()

                val basePerms = listOf(
                    "$first$last",
                    "$first.$last",
                    "${first}_$last",
                    "$fInitial$last",
                    "$fInitial.$last",
                    "$first$lInitial",
                    "$last$first",
                    "$last.$first",
                    first,
                    last
                )

                basePerms.forEach { perm ->
                    if (perm.length >= 3) {
                        pool.putIfAbsent(perm, "Name Permutation ($rawName)")
                    }
                    // Also combine with any numeric suffixes found in the target's handles (e.g. "86", "87")
                    discoveredDigits.forEach { digits ->
                        pool.putIfAbsent("$perm$digits", "Name + Handle Suffix ($rawName + $digits)")
                    }
                }
            }
        }

        return pool
    }

    /**
     * Cross-matches a single [MaskedEmailHint] (e.g., "d*******s@gmail.com" or "da***86@g****.com")
     * against all candidate local-parts and domains.
     */
    internal fun unmaskHint(
        hint: MaskedEmailHint,
        candidateLocalParts: Map<String, String>,
        candidateDomains: List<String>
    ): List<UnmaskedEmailCandidate> {
        val raw = hint.rawMasked.trim().lowercase()
        if ('@' !in raw) return emptyList()

        val maskedLocal = raw.substringBefore('@')
        val maskedDomain = raw.substringAfter('@')
        if (maskedLocal.isBlank() || maskedDomain.isBlank()) return emptyList()

        val resolvedDomains = resolveMaskedDomain(maskedDomain, candidateDomains)
        val primaryDomain = resolvedDomains.firstOrNull() ?: maskedDomain

        val prefix = maskedLocal.substringBefore('*')
        val suffix = maskedLocal.substringAfterLast('*')
        val starCount = maskedLocal.count { it == '*' }
        val expectedTotalLen = prefix.length + starCount + suffix.length

        val matches = mutableListOf<UnmaskedEmailCandidate>()

        for ((candidateLocal, originLabel) in candidateLocalParts) {
            val cand = candidateLocal.lowercase()
            if (!cand.startsWith(prefix)) continue
            if (suffix.isNotEmpty() && !cand.endsWith(suffix)) continue
            if (cand.length < prefix.length + suffix.length + 1) continue

            val isExactLength = cand.length == expectedTotalLen
            val hasSuffix = suffix.isNotEmpty()

            val (score, reason) = when {
                isExactLength && hasSuffix ->
                    96 to "Exact length ($expectedTotalLen chars), prefix '$prefix', and suffix '$suffix' match"
                isExactLength && !hasSuffix && prefix.length >= 2 ->
                    88 to "Exact length ($expectedTotalLen chars) and prefix '$prefix' match"
                !isExactLength && hasSuffix && prefix.length >= 2 ->
                    84 to "Prefix '$prefix' and suffix '$suffix' match (variable-length mask)"
                !isExactLength && hasSuffix ->
                    78 to "Initial '${prefix}' and ending '${suffix}' match"
                !isExactLength && prefix.length >= 3 ->
                    72 to "Prefix '$prefix' matches handle/name root"
                else -> continue
            }

            matches += UnmaskedEmailCandidate(
                maskedHint = hint.rawMasked,
                resolvedEmail = "$cand@$primaryDomain",
                matchedFrom = originLabel,
                confidenceScore = score,
                reasoning = "$reason · Source: ${hint.source}"
            )
        }

        return matches.sortedByDescending { it.confidenceScore }
    }

    internal fun resolveMaskedDomain(
        maskedDomain: String,
        candidateDomains: List<String>
    ): List<String> {
        if ('*' !in maskedDomain) return listOf(maskedDomain)

        val domRegexPattern = buildString {
            append("^")
            var i = 0
            while (i < maskedDomain.length) {
                val c = maskedDomain[i]
                if (c == '*') {
                    while (i < maskedDomain.length && maskedDomain[i] == '*') i++
                    append("[a-z0-9.-]+")
                } else {
                    append(Regex.escape(c.toString()))
                    i++
                }
            }
            append("$")
        }
        val regex = Regex(domRegexPattern, RegexOption.IGNORE_CASE)
        return candidateDomains.filter { regex.matches(it) }
    }
}
