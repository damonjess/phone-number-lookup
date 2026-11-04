package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class ExposedPiiSummary(
    val exposedFields: List<String> = emptyList(),
    val passwordPatterns: List<String> = emptyList(),
    val leakCheckSources: List<String> = emptyList(),
    val stealerUsernameHits: List<String> = emptyList()
)

data class PublicRecordFiling(
    val category: String, // "Corporate Registry", "Court Filing", "Official Gazette", "Property / Postcode"
    val title: String,
    val detail: String,
    val addressOrLocation: String? = null,
    val date: String? = null,
    val associates: List<String> = emptyList(),
    val url: String
)

data class AliasCorrelationMatch(
    val handleA: String,
    val handleB: String,
    val reason: String,
    val confidenceScore: Int
)

data class DatedAddressEntry(
    val date: String,
    val location: String,
    val source: String
)

data class DeepPublicRecordsReport(
    val piiSummary: ExposedPiiSummary = ExposedPiiSummary(),
    val filings: List<PublicRecordFiling> = emptyList(),
    val associates: List<AttributedValue> = emptyList(),
    val aliasCorrelations: List<AliasCorrelationMatch> = emptyList(),
    val addressTimeline: List<DatedAddressEntry> = emptyList()
)

object FreePublicRecordsAndBreachIntel {

    private val TAG_STRIP = Regex("<[^>]*>")
    private val WS_CLEAN = Regex("\\s+")
    private val UK_POSTCODE_EXTRACT = Regex("""\b([A-Z]{1,2}\d[A-Z\d]?\s+\d[A-Z]{2})\b""", RegexOption.IGNORE_CASE)

    /**
     * Runs all 4 free OSINT modules (Breach PII/Password Patterns, Real-Time Public Records,
     * Alias Correlation, and Co-Director/Address Timelines) with strict timeouts.
     */
    suspend fun enrichTarget(
        email: String? = null,
        username: String? = null,
        fullName: String? = null,
        discoveredHandles: List<String> = emptyList(),
        existingLocations: List<AttributedValue> = emptyList(),
        existingTimeline: List<TimelineEvent> = emptyList()
    ): DeepPublicRecordsReport = withContext(Dispatchers.IO) {
        coroutineScope {
            val candidateName = fullName?.takeIf { ' ' in it && it.length >= 5 }

            // 1. Breach PII & Password Patterns (LeakCheck Public API + XposedOrNot Metrics + HudsonRock Username)
            val piiDef = async {
                withTimeoutOrNull(9_000L) {
                    fetchBreachPiiAndPasswordPatterns(email, username ?: email?.substringBefore('@'))
                } ?: ExposedPiiSummary()
            }

            // 2. Real-Time Public Records (UK Companies House, The Gazette, UK Case Law, GLEIF)
            val recordsQuery = candidateName ?: username ?: email?.substringBefore('@')
            val companiesHouseDef = async {
                if (!recordsQuery.isNullOrBlank() && recordsQuery.length >= 4) {
                    withTimeoutOrNull(10_000L) { searchUkCompaniesHouseOfficers(recordsQuery) }.orEmpty()
                } else emptyList()
            }
            val gazetteDef = async {
                if (!candidateName.isNullOrBlank()) {
                    withTimeoutOrNull(8_000L) { searchUkOfficialGazette(candidateName) }.orEmpty()
                } else emptyList()
            }
            val caseLawDef = async {
                if (!candidateName.isNullOrBlank()) {
                    withTimeoutOrNull(8_000L) { searchUkNationalArchivesCaseLaw(candidateName) }.orEmpty()
                } else emptyList()
            }
            val gleifDef = async {
                if (!recordsQuery.isNullOrBlank() && recordsQuery.length >= 4) {
                    withTimeoutOrNull(8_000L) { searchGleifCorporateRegistry(recordsQuery) }.orEmpty()
                } else emptyList()
            }

            val allFilings = (companiesHouseDef.await() + gazetteDef.await() + caseLawDef.await() + gleifDef.await())
                .distinctBy { it.url.lowercase() }

            // 3. Extract Associates / Co-Officers & Postcode Ward/District Details
            val associates = mutableListOf<AttributedValue>()
            allFilings.forEach { filing ->
                filing.associates.forEach { assoc ->
                    associates += AttributedValue(assoc, filing.category)
                }
            }

            // Enrich any discovered UK postcodes via free Postcodes.io API
            val allLocationStrings = (existingLocations.map { it.value } + allFilings.mapNotNull { it.addressOrLocation }).distinct()
            val postcodes = allLocationStrings.flatMap { loc ->
                UK_POSTCODE_EXTRACT.findAll(loc).map { it.groupValues[1].uppercase() }.toList()
            }.distinct().take(3)

            val postcodeFilings = postcodes.map { pc ->
                async {
                    withTimeoutOrNull(5_000L) { lookupUkPostcodeRegistry(pc) }
                }
            }.awaitAll().filterNotNull()

            // 4. Algorithmic Alias Correlation across all discovered handles
            val allHandles = (listOfNotNull(username, email?.substringBefore('@')) + discoveredHandles)
                .map { it.trim().removePrefix("@") }
                .filter { it.length >= 3 }
                .distinctBy { it.lowercase() }
            val correlations = correlateAliases(allHandles)

            // 5. Historical Address & Location Timeline
            val addressTimeline = buildAddressTimeline(
                existingLocations = existingLocations,
                existingTimeline = existingTimeline,
                filings = allFilings + postcodeFilings
            )

            DeepPublicRecordsReport(
                piiSummary = piiDef.await(),
                filings = allFilings + postcodeFilings,
                associates = associates.distinctBy { it.value.lowercase() },
                aliasCorrelations = correlations,
                addressTimeline = addressTimeline
            )
        }
    }

    /**
     * Queries LeakCheck's free public API, XposedOrNot's password strength metrics,
     * and HudsonRock's free username stealer endpoint.
     */
    internal fun fetchBreachPiiAndPasswordPatterns(
        email: String?,
        handle: String?
    ): ExposedPiiSummary {
        val exposedFields = LinkedHashSet<String>()
        val passwordPatterns = LinkedHashSet<String>()
        val leakSources = LinkedHashSet<String>()
        val stealerHits = LinkedHashSet<String>()

        // 1. LeakCheck Free Public API (works for both email and username)
        val queryTarget = email?.takeIf { it.isNotBlank() } ?: handle?.takeIf { it.isNotBlank() }
        if (queryTarget != null) {
            val lcResp = ScanHttp.get(
                "https://leakcheck.io/api/public?check=${ScanHttp.encode(queryTarget)}",
                timeoutMs = 7_000,
                headers = mapOf("Accept" to "application/json")
            )
            if (lcResp.isOk && lcResp.body.isNotBlank()) {
                runCatching {
                    val root = JSONObject(lcResp.body)
                    if (root.optBoolean("success", false)) {
                        val fieldsArr = root.optJSONArray("fields")
                        if (fieldsArr != null) {
                            for (i in 0 until fieldsArr.length()) {
                                fieldsArr.optString(i).takeIf { it.isNotBlank() }?.let { exposedFields += it }
                            }
                        }
                        val sourcesArr = root.optJSONArray("sources")
                        if (sourcesArr != null) {
                            for (i in 0 until sourcesArr.length()) {
                                val s = sourcesArr.optJSONObject(i) ?: continue
                                val name = s.optString("name").takeIf { it.isNotBlank() } ?: continue
                                val date = s.optString("date").takeIf { it.isNotBlank() }
                                leakSources += if (date != null) "$name ($date)" else name
                            }
                        }
                    }
                }
            }
        }

        // 2. XposedOrNot Password Storage & PII Metrics (for emails)
        if (!email.isNullOrBlank() && '@' in email) {
            val xonResp = ScanHttp.get(
                "https://api.xposedornot.com/v1/breach-analytics?email=${ScanHttp.encode(email)}",
                timeoutMs = 8_000,
                headers = mapOf("Accept" to "application/json")
            )
            if (xonResp.isOk && xonResp.body.isNotBlank()) {
                runCatching {
                    val root = JSONObject(xonResp.body)
                    val metrics = root.optJSONObject("BreachMetrics")
                    val pwdStrength = metrics?.optJSONArray("passwords_strength")?.optJSONObject(0)
                    if (pwdStrength != null) {
                        val plain = pwdStrength.optInt("PlainText", 0)
                        val easy = pwdStrength.optInt("EasyToCrack", 0)
                        val strong = pwdStrength.optInt("StrongHash", 0)
                        if (plain > 0) passwordPatterns += "$plain breach(es) stored PlainText passwords"
                        if (easy > 0) passwordPatterns += "$easy breach(es) stored Easy-to-Crack password hashes"
                        if (strong > 0) passwordPatterns += "$strong breach(es) stored Strong password hashes"
                    }
                    val details = root.optJSONObject("ExposedBreaches")?.optJSONArray("breaches_details")
                    if (details != null) {
                        for (i in 0 until details.length()) {
                            val d = details.optJSONObject(i) ?: continue
                            d.optString("xposed_data")
                                .split(';', ',')
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .forEach { exposedFields += it }
                        }
                    }
                }
            }
        }

        // 3. HudsonRock Free Username Infostealer Lookup
        if (!handle.isNullOrBlank() && handle.length >= 4) {
            val hrResp = ScanHttp.get(
                "https://cavalier.hudsonrock.com/api/json/v2/osint-tools/search-by-username?username=${ScanHttp.encode(handle)}",
                timeoutMs = 7_000,
                headers = mapOf("Accept" to "application/json")
            )
            if (hrResp.isOk && hrResp.body.isNotBlank()) {
                runCatching {
                    val stealers = JSONObject(hrResp.body).optJSONArray("stealers")
                    if (stealers != null) {
                        for (i in 0 until stealers.length()) {
                            val s = stealers.optJSONObject(i) ?: continue
                            val date = s.optString("date_compromised").substringBefore('T').ifBlank { "recorded" }
                            val os = s.optString("operating_system").ifBlank { "system" }
                            val ip = s.optString("ip").takeIf { it.isNotBlank() }
                            stealerHits += "Username @$handle in Stealer Log ($date • $os${ip?.let { " • IP $it" }.orEmpty()})"
                        }
                    }
                }
            }
        }

        return ExposedPiiSummary(
            exposedFields = exposedFields.toList(),
            passwordPatterns = passwordPatterns.toList(),
            leakCheckSources = leakSources.toList(),
            stealerUsernameHits = stealerHits.toList()
        )
    }

    /**
     * Searches UK Companies House public officer & company filings (no API key required)
     * and extracts registered addresses, birth month/year, and co-officers.
     */
    internal suspend fun searchUkCompaniesHouseOfficers(query: String): List<PublicRecordFiling> {
        val url = "https://find-and-update.company-information.service.gov.uk/search?q=${ScanHttp.encode(query)}"
        val resp = ScanHttp.get(url, timeoutMs = 8_000, maxBodyChars = 1_500_000)
        val html = if (resp.isOk && resp.body.contains("results-list", ignoreCase = true)) {
            resp.body
        } else {
            NativeWebScraper.fetchRenderedHtml(url, waitAfterLoadMs = 1_200L, timeoutMs = 7_000L).orEmpty()
        }
        if (html.isBlank()) return emptyList()

        val results = mutableListOf<PublicRecordFiling>()
        val itemRegex = Regex(
            """<li[^>]*class=["'][^"']*type-(?:officer|company)[^"']*["'][^>]*>(.*?)</li>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val linkRegex = Regex(
            """<a[^>]*href=["'](/(?:officers|company)/[^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val metaRegex = Regex("""<p[^>]*class=["'][^"']*meta[^"']*["'][^>]*>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val addrRegex = Regex("""<p[^>]*>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        for (m in itemRegex.findAll(html)) {
            val block = m.groupValues[1]
            val linkMatch = linkRegex.find(block) ?: continue
            val path = linkMatch.groupValues[1].trim()
            val title = linkMatch.groupValues[2].cleanText()
            if (title.isBlank()) continue

            val meta = metaRegex.find(block)?.groupValues?.get(1)?.cleanText().orEmpty()
            val paragraphs = addrRegex.findAll(block).map { it.groupValues[1].cleanText() }.filter { it.isNotBlank() }.toList()
            val address = paragraphs.lastOrNull { it != meta && !it.equals(title, ignoreCase = true) }
            val dateMatch = Regex("""\b(\d{1,2}\s+[A-Za-z]+\s+\d{4}|(?:January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{4})\b""", RegexOption.IGNORE_CASE)
                .find("$meta ${paragraphs.joinToString(" ")}")?.groupValues?.get(1)

            results += PublicRecordFiling(
                category = "Corporate Registry (UK Companies House)",
                title = title,
                detail = listOfNotNull(meta.takeIf { it.isNotBlank() }, address?.let { "Address: $it" }).joinToString(" • ").ifBlank { "UK Companies House filing" },
                addressOrLocation = address,
                date = dateMatch,
                url = "https://find-and-update.company-information.service.gov.uk$path"
            )
            if (results.size >= 6) break
        }
        return results
    }

    /**
     * Queries The Gazette (Official UK Public Record) for corporate, insolvency, and probate notices.
     */
    internal fun searchUkOfficialGazette(name: String): List<PublicRecordFiling> {
        val url = "https://www.thegazette.co.uk/all-notices/notice?text=${ScanHttp.encode("\"$name\"")}"
        val resp = ScanHttp.get(url, timeoutMs = 8_000, maxBodyChars = 1_000_000)
        if (!resp.isOk || resp.body.isBlank()) return emptyList()

        val results = mutableListOf<PublicRecordFiling>()
        val articleRegex = Regex(
            """<article[^>]*>(.*?)</article>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val linkRegex = Regex(
            """<a[^>]*href=["'](/notice/\d+[^"']*)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val timeRegex = Regex("""<time[^>]*datetime=["']([^"']+)["'][^>]*>(.*?)</time>""", RegexOption.IGNORE_CASE)

        for (m in articleRegex.findAll(resp.body)) {
            val block = m.groupValues[1]
            val link = linkRegex.find(block) ?: continue
            val path = link.groupValues[1]
            val title = link.groupValues[2].cleanText()
            val date = timeRegex.find(block)?.groupValues?.get(1)?.take(10)
            val snippet = block.cleanText().take(220)

            results += PublicRecordFiling(
                category = "Official Gazette Public Record",
                title = title.ifBlank { "UK Official Gazette Notice" },
                detail = snippet,
                date = date,
                url = "https://www.thegazette.co.uk$path"
            )
            if (results.size >= 5) break
        }
        return results
    }

    /**
     * Searches the UK National Archives "Find Case Law" portal for public court judgments & tribunal decisions.
     */
    internal fun searchUkNationalArchivesCaseLaw(name: String): List<PublicRecordFiling> {
        val url = "https://caselaw.nationalarchives.gov.uk/judgments/search?query=${ScanHttp.encode("\"$name\"")}"
        val resp = ScanHttp.get(url, timeoutMs = 8_000, maxBodyChars = 1_000_000)
        if (!resp.isOk || resp.body.isBlank()) return emptyList()

        val results = mutableListOf<PublicRecordFiling>()
        val rowRegex = Regex(
            """<a[^>]*class=["'][^"']*judgment-listing__title[^"']*["'][^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        for (m in rowRegex.findAll(resp.body)) {
            val path = m.groupValues[1].trim()
            val title = m.groupValues[2].cleanText()
            if (title.isBlank()) continue
            val yearMatch = Regex("""\[(\d{4})]""").find(title)?.groupValues?.get(1)
            results += PublicRecordFiling(
                category = "Court Filing (UK National Archives)",
                title = title,
                detail = "Published UK Court / Tribunal Judgment matching \"$name\"",
                date = yearMatch,
                url = if (path.startsWith("http")) path else "https://caselaw.nationalarchives.gov.uk$path"
            )
            if (results.size >= 5) break
        }
        return results
    }

    /**
     * Queries the GLEIF (Global Legal Entity Identifier) unauthenticated JSON API for corporate entities.
     */
    internal fun searchGleifCorporateRegistry(query: String): List<PublicRecordFiling> {
        val url = "https://api.gleif.org/api/v1/lei-records?filter[fulltext]=${ScanHttp.encode(query)}&page[size]=4"
        val resp = ScanHttp.get(url, timeoutMs = 7_000, headers = mapOf("Accept" to "application/vnd.api+json,application/json"))
        if (!resp.isOk || resp.body.isBlank()) return emptyList()

        return runCatching {
            val data = JSONObject(resp.body).optJSONArray("data") ?: return emptyList()
            (0 until data.length()).mapNotNull { i ->
                val item = data.optJSONObject(i) ?: return@mapNotNull null
                val lei = item.optString("id")
                val entity = item.optJSONObject("attributes")?.optJSONObject("entity") ?: return@mapNotNull null
                val legalName = entity.optJSONObject("legalName")?.optString("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val hq = entity.optJSONObject("headquartersAddress")
                val address = listOfNotNull(
                    hq?.optJSONArray("addressLines")?.optString(0),
                    hq?.optString("city"),
                    hq?.optString("postalCode"),
                    hq?.optString("country")
                ).filter { it.isNotBlank() }.joinToString(", ")

                PublicRecordFiling(
                    category = "Corporate Registry (GLEIF)",
                    title = "$legalName (LEI: $lei)",
                    detail = if (address.isNotBlank()) "Headquarters: $address" else "Registered Legal Entity",
                    addressOrLocation = address.takeIf { it.isNotBlank() },
                    url = "https://search.gleif.org/#/record/$lei"
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Resolves UK Postcodes via the free, open Postcodes.io government-backed dataset
     * (Ward, Parish, Local Authority District, Parliamentary Constituency, Region).
     */
    internal fun lookupUkPostcodeRegistry(postcode: String): PublicRecordFiling? {
        val clean = postcode.trim().uppercase()
        val url = "https://api.postcodes.io/postcodes/${ScanHttp.encode(clean)}"
        val resp = ScanHttp.get(url, timeoutMs = 5_000, headers = mapOf("Accept" to "application/json"))
        if (!resp.isOk || resp.body.isBlank()) return null

        return runCatching {
            val res = JSONObject(resp.body).optJSONObject("result") ?: return null
            val ward = res.optString("admin_ward")
            val district = res.optString("admin_district")
            val constituency = res.optString("parliamentary_constituency")
            val region = res.optString("region")
            val lsoa = res.optString("lsoa")
            val detail = listOfNotNull(
                district.takeIf { it.isNotBlank() }?.let { "District: $it" },
                ward.takeIf { it.isNotBlank() }?.let { "Ward: $it" },
                constituency.takeIf { it.isNotBlank() }?.let { "Constituency: $it" },
                lsoa.takeIf { it.isNotBlank() }?.let { "LSOA: $it" },
                region.takeIf { it.isNotBlank() }?.let { "Region: $it" }
            ).joinToString(" • ")

            PublicRecordFiling(
                category = "Property & Civic Registry (ONS / Postcodes.io)",
                title = "UK Postcode $clean — $district",
                detail = detail,
                addressOrLocation = "$clean ($ward, $district)",
                url = "https://findthatpostcode.uk/postcodes/${ScanHttp.encode(clean)}.html"
            )
        }.getOrNull()
    }

    /**
     * Correlates aliases across platforms (matching root stems, swapped name tokens, or shared birth-year suffixes).
     */
    internal fun correlateAliases(handles: List<String>): List<AliasCorrelationMatch> {
        val clean = handles.map { it.trim().removePrefix("@") }.distinctBy { it.lowercase() }
        if (clean.size < 2) return emptyList()
        val matches = mutableListOf<AliasCorrelationMatch>()

        for (i in clean.indices) {
            for (j in i + 1 until clean.size) {
                val a = clean[i]
                val b = clean[j]
                val al = a.lowercase().replace(Regex("[^a-z0-9]"), "")
                val bl = b.lowercase().replace(Regex("[^a-z0-9]"), "")
                val aBase = al.replace(Regex("\\d+$"), "")
                val bBase = bl.replace(Regex("\\d+$"), "")
                val aDigits = Regex("\\d+$").find(al)?.value
                val bDigits = Regex("\\d+$").find(bl)?.value

                when {
                    al == bl ->
                        matches += AliasCorrelationMatch(a, b, "Identical normalized handle with different punctuation", 95)
                    aBase.length >= 4 && aBase == bBase ->
                        matches += AliasCorrelationMatch(a, b, "Shared base pseudonym \"$aBase\" across handles", 88)
                    aBase.length >= 4 && bBase.length >= 4 && (aBase.contains(bBase) || bBase.contains(aBase)) ->
                        matches += AliasCorrelationMatch(a, b, "Overlapping root token (${minOf(aBase, bBase, compareBy { it.length })})", 80)
                    !aDigits.isNullOrBlank() && aDigits == bDigits && aDigits.length >= 2 ->
                        matches += AliasCorrelationMatch(a, b, "Shared numeric/year suffix \"$aDigits\"", 75)
                }
            }
        }
        return matches
    }

    private fun buildAddressTimeline(
        existingLocations: List<AttributedValue>,
        existingTimeline: List<TimelineEvent>,
        filings: List<PublicRecordFiling>
    ): List<DatedAddressEntry> {
        val out = mutableListOf<DatedAddressEntry>()

        // 1. Dated Google Maps review locations from timeline
        existingTimeline.filter { it.source.contains("Google Maps", true) }.forEach { ev ->
            out += DatedAddressEntry(ev.date, ev.label, ev.source)
        }

        // 2. Dated Corporate / Gazette / Property filings with addresses
        filings.forEach { f ->
            if (!f.addressOrLocation.isNullOrBlank()) {
                out += DatedAddressEntry(f.date ?: "Public Filing", f.addressOrLocation, f.category)
            }
        }

        // 3. Remaining attributed locations
        existingLocations.forEach { loc ->
            if (out.none { it.location.contains(loc.value, ignoreCase = true) }) {
                out += DatedAddressEntry("Active Signal", loc.value, loc.source)
            }
        }

        return out.distinctBy { "${it.date}|${it.location.lowercase()}" }
    }

    private fun String.cleanText(): String =
        BreachCatalog.plainText(replace(TAG_STRIP, " ")).replace(WS_CLEAN, " ").trim()
}
