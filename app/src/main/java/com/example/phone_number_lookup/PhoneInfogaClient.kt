package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * Runs PhoneInfoga (github.com/sundowndev/phoneinfoga) by calling its v2 REST API.
 *
 * PhoneInfoga is a Go program, so it can't run inside an Android app. Start its server on a PC,
 * Raspberry Pi or Docker host (`phoneinfoga serve`, port 5000 by default), or build it with Go
 * inside Termux, and point the app at it. The app then asks each scanner if it can run
 * ("dry run"), runs the ones that can, and shows what they return.
 *
 * Endpoints used:
 *   GET  /api/v2/scanners
 *   POST /api/v2/scanners/{scanner}/dryrun   {"number": "...", "options": {}}
 *   POST /api/v2/scanners/{scanner}/run      {"number": "...", "options": {}}
 */
enum class PhoneInfogaStatus { OK, SKIPPED, ERROR }

data class PhoneInfogaScannerInfo(val name: String, val description: String)

data class PhoneInfogaScan(
    val scanner: String,
    val description: String,
    val status: PhoneInfogaStatus,
    /** Why a scanner was skipped or failed. */
    val message: String? = null,
    val rows: List<Pair<String, String>> = emptyList(),
    val links: List<String> = emptyList(),
    /** Links with a readable label (for example the dork text), shown instead of raw URLs when present. */
    val labeledLinks: List<Pair<String, String>> = emptyList(),
)

data class PhoneInfogaReport(
    val baseUrl: String,
    val scans: List<PhoneInfogaScan>,
    /** Set when the server itself couldn't be used (unreachable, wrong address, not PhoneInfoga). */
    val error: String? = null,
    /** Explains where these results came from, for example that the built-in scanners were used. */
    val note: String? = null,
)

data class PhoneInfogaScanners(
    val scanners: List<PhoneInfogaScannerInfo>,
    val error: String? = null,
    /** HTTP status of the scanner-list request; -1 when the server couldn't be reached. */
    val httpCode: Int = 0,
)

object PhoneInfogaClient {

    const val DEFAULT_URL = "http://127.0.0.1:5000"

    private const val MAX_ROWS = 60
    private const val MAX_LINKS = 40

    /** Accepts "192.168.1.5:5000", "http://host:5000/", or the API root; returns null if unusable. */
    fun normaliseBaseUrl(raw: String): String? {
        var url = raw.trim()
        if (url.isEmpty()) return null
        if (!url.contains("://")) url = "http://$url"
        url = url.trimEnd('/').removeSuffix("/api").trimEnd('/')
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
        if (uri.host.isNullOrBlank()) return null
        if (uri.scheme != "http" && uri.scheme != "https") return null
        return url
    }

    /** PhoneInfoga's API takes digits only, with no "+" or spaces. */
    fun apiNumber(number: String): String = number.filter { it.isDigit() }

    // ----------------------------------------------------------------- scanners

    suspend fun listScanners(baseUrl: String): PhoneInfogaScanners = withContext(Dispatchers.IO) {
        val response = ScanHttp.get(
            "$baseUrl/api/v2/scanners",
            timeoutMs = 8_000,
            headers = mapOf("Accept" to "application/json")
        )
        interpretScanners(response.code, response.body)
    }

    internal fun interpretScanners(code: Int, body: String): PhoneInfogaScanners {
        if (code == -1) {
            return PhoneInfogaScanners(
                emptyList(),
                "Couldn't reach the PhoneInfoga server. Check that it is running and the address is right.",
                code
            )
        }
        if (code != 200) {
            return PhoneInfogaScanners(
                emptyList(),
                "The server answered HTTP $code at /api/v2/scanners. It may not be PhoneInfoga v2, " +
                    "or the address may be wrong.",
                code
            )
        }
        val scanners = scannersFrom(JsonTree.parse(body))
        return if (scanners.isEmpty()) {
            PhoneInfogaScanners(
                emptyList(),
                "The server answered, but not with a PhoneInfoga scanner list.",
                code
            )
        } else {
            PhoneInfogaScanners(scanners, null, code)
        }
    }

    internal fun scannersFrom(tree: Any?): List<PhoneInfogaScannerInfo> {
        val list = when (tree) {
            is Map<*, *> -> tree["scanners"] as? List<*>
            is List<*> -> tree
            else -> null
        } ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val name = (map["name"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PhoneInfogaScannerInfo(name, (map["description"] as? String).orEmpty())
        }
    }

    // --------------------------------------------------------------------- scan

    suspend fun scan(baseUrl: String, number: String): PhoneInfogaReport = withContext(Dispatchers.IO) {
        val digits = apiNumber(number)
        if (digits.isEmpty()) return@withContext PhoneInfogaReport(baseUrl, emptyList(), "Enter a phone number first.")

        val list = listScanners(baseUrl)
        if (list.error != null) return@withContext PhoneInfogaReport(baseUrl, emptyList(), list.error)

        val scans = coroutineScope {
            list.scanners.map { scanner -> async { runOne(baseUrl, scanner, digits) } }.awaitAll()
        }
        PhoneInfogaReport(baseUrl, scans)
    }

    private fun runOne(baseUrl: String, scanner: PhoneInfogaScannerInfo, digits: String): PhoneInfogaScan {
        val name = ScanHttp.encode(scanner.name)
        // The number is digits only, so building the JSON by hand is safe.
        val body = """{"number":"$digits","options":{}}"""

        val dry = ScanHttp.post("$baseUrl/api/v2/scanners/$name/dryrun", body, timeoutMs = 15_000)
        interpretDryRun(dry.code, dry.body)?.let { reason ->
            return PhoneInfogaScan(scanner.name, scanner.description, PhoneInfogaStatus.SKIPPED, reason)
        }

        val run = ScanHttp.post("$baseUrl/api/v2/scanners/$name/run", body, timeoutMs = 45_000)
        return interpretRun(scanner, run.code, run.body)
    }

    /** Returns null when the scanner can run, otherwise why it can't (missing API key, unsupported country...). */
    internal fun interpretDryRun(code: Int, body: String): String? {
        if (code == -1) return "No response from the server."
        val tree = JsonTree.parse(body)
        if (code == 200 && !(tree is Map<*, *> && tree["success"] == false)) return null
        return errorMessage(tree) ?: "Can't run for this number (HTTP $code)."
    }

    internal fun interpretRun(scanner: PhoneInfogaScannerInfo, code: Int, body: String): PhoneInfogaScan {
        fun failure(message: String) =
            PhoneInfogaScan(scanner.name, scanner.description, PhoneInfogaStatus.ERROR, message)

        if (code == -1) return failure("No response from the server.")
        val tree = JsonTree.parse(body)
        if (code != 200) return failure(errorMessage(tree) ?: "The scanner failed (HTTP $code).")
        if (tree !is Map<*, *>) return failure("The server's answer wasn't in the expected format.")
        if (tree["success"] == false) return failure(errorMessage(tree) ?: "The scanner reported a failure.")

        val payload = tree["result"] ?: tree.filterKeys { it != "success" }
        val (rows, links) = flatten(payload)
        return PhoneInfogaScan(
            scanner = scanner.name,
            description = scanner.description,
            status = PhoneInfogaStatus.OK,
            message = if (rows.isEmpty() && links.isEmpty()) "The scanner ran but returned nothing." else null,
            rows = rows,
            links = links,
            labeledLinks = labeledLinks(payload),
        )
    }

    private fun errorMessage(tree: Any?): String? {
        val map = tree as? Map<*, *> ?: return null
        return listOf("error", "message").firstNotNullOfOrNull { key ->
            (map[key] as? String)?.takeIf { it.isNotBlank() }
        }
    }

    // ------------------------------------------------------------------ flatten

    /** Pairs each link with its dork text when the scanner supplies one (`{"url": ..., "dork": ...}`). */
    internal fun labeledLinks(tree: Any?): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        fun walk(node: Any?) {
            if (out.size >= MAX_LINKS) return
            when (node) {
                is Map<*, *> -> {
                    val url = node["url"] as? String
                    if (url != null && isUrl(url)) {
                        val label = (node["dork"] as? String)?.takeIf { it.isNotBlank() } ?: url
                        out += label to url
                    } else {
                        node.values.forEach(::walk)
                    }
                }
                is List<*> -> node.forEach(::walk)
            }
        }
        walk(tree)
        return out
    }

    private fun isUrl(text: String) = text.startsWith("http://") || text.startsWith("https://")

    private fun pretty(path: String): String =
        path.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** Turns a nested scanner result into display rows and a de-duplicated list of links. */
    internal fun flatten(tree: Any?): Pair<List<Pair<String, String>>, List<String>> {
        val rows = ArrayList<Pair<String, String>>()
        val links = LinkedHashSet<String>()

        fun addScalar(path: String, text: String) {
            if (text.isBlank()) return
            if (isUrl(text)) {
                if (links.size < MAX_LINKS) links += text
            } else if (rows.size < MAX_ROWS) {
                rows += pretty(path.ifEmpty { "Value" }) to text
            }
        }

        fun walk(node: Any?, path: String) {
            when (node) {
                null -> Unit
                is Map<*, *> -> node.forEach { (key, value) ->
                    walk(value, if (path.isEmpty()) key.toString() else "$path ${key}")
                }
                is List<*> -> {
                    val scalars = node.all { it == null || it is String || it is Number || it is Boolean }
                    if (scalars) {
                        val (urls, plain) = node.filterNotNull().map { it.toString() }.partition { isUrl(it) }
                        urls.forEach { if (links.size < MAX_LINKS) links += it }
                        if (plain.isNotEmpty() && rows.size < MAX_ROWS) rows += pretty(path.ifEmpty { "Values" }) to plain.joinToString(", ")
                    } else {
                        node.forEach { walk(it, path) }
                    }
                }
                else -> addScalar(path, node.toString())
            }
        }

        walk(tree, "")
        return rows to links.toList()
    }
}
