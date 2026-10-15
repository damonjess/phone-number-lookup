package com.example.phone_number_lookup

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class PhoneInfogaClientTest {

    // ------------------------------------------------------------------ addresses

    @Test
    fun addressesAreNormalised() {
        assertEquals("http://127.0.0.1:5000", PhoneInfogaClient.normaliseBaseUrl("127.0.0.1:5000"))
        assertEquals("http://host:5000", PhoneInfogaClient.normaliseBaseUrl(" http://host:5000/ "))
        assertEquals("http://host:5000", PhoneInfogaClient.normaliseBaseUrl("http://host:5000/api"))
        assertEquals("https://pi.example.com", PhoneInfogaClient.normaliseBaseUrl("https://pi.example.com/"))
    }

    @Test
    fun unusableAddressesAreRejected() {
        assertNull(PhoneInfogaClient.normaliseBaseUrl(""))
        assertNull(PhoneInfogaClient.normaliseBaseUrl("   "))
        assertNull(PhoneInfogaClient.normaliseBaseUrl("ftp://host"))
        assertNull(PhoneInfogaClient.normaliseBaseUrl("http://"))
    }

    @Test
    fun theApiGetsDigitsOnly() {
        assertEquals("442079460958", PhoneInfogaClient.apiNumber("+44 20 7946 0958"))
    }

    // ----------------------------------------------------------------- responses

    @Test
    fun scannerListIsReadFromTheScannersKey() {
        val body = """{"success":true,"scanners":[{"name":"local","description":"Local info"},{"name":"ovh"}]}"""
        val result = PhoneInfogaClient.interpretScanners(200, body)
        assertNull(result.error)
        assertEquals(listOf("local", "ovh"), result.scanners.map { it.name })
    }

    @Test
    fun aServerThatIsNotPhoneInfogaIsReportedClearly() {
        assertTrue(PhoneInfogaClient.interpretScanners(200, "<html>hello</html>").error!!.contains("not with a PhoneInfoga"))
        assertTrue(PhoneInfogaClient.interpretScanners(404, "").error!!.contains("HTTP 404"))
        assertTrue(PhoneInfogaClient.interpretScanners(-1, "").error!!.contains("Couldn't reach"))
    }

    @Test
    fun dryRunSaysWhyAScannerCannotRun() {
        assertNull(PhoneInfogaClient.interpretDryRun(200, """{"success":true}"""))
        assertEquals(
            "NUMVERIFY_API_KEY is not set",
            PhoneInfogaClient.interpretDryRun(500, """{"success":false,"error":"NUMVERIFY_API_KEY is not set"}""")
        )
        assertEquals("nope", PhoneInfogaClient.interpretDryRun(200, """{"success":false,"error":"nope"}"""))
    }

    @Test
    fun flattenSeparatesLinksFromFacts() {
        val tree = mapOf(
            "country" to "GB",
            "valid" to true,
            "social_media" to listOf(
                mapOf("url" to "https://www.google.com/search?q=a", "dork" to "site:facebook.com 44"),
                mapOf("url" to "https://www.google.com/search?q=b", "dork" to "site:twitter.com 44"),
            ),
            "general" to listOf("https://www.google.com/search?q=c", "plain text"),
        )
        val (rows, links) = PhoneInfogaClient.flatten(tree)
        assertTrue(rows.contains("Country" to "GB"))
        assertTrue(rows.contains("Valid" to "true"))
        assertTrue(rows.any { it.second == "site:facebook.com 44" })
        assertEquals(3, links.size)
        assertTrue(links.all { it.startsWith("https://") })
    }

    @Test
    fun linksAreLabelledWithTheirDorkText() {
        val tree = mapOf(
            "social_media" to listOf(
                mapOf("url" to "https://www.google.com/search?q=a", "dork" to "site:facebook.com 44"),
                mapOf("url" to "https://www.google.com/search?q=b", "dork" to ""),
            ),
            "general" to listOf(mapOf("number" to "44", "url" to "https://www.google.com/search?q=c")),
        )
        assertEquals(
            listOf(
                "site:facebook.com 44" to "https://www.google.com/search?q=a",
                "https://www.google.com/search?q=b" to "https://www.google.com/search?q=b",
                "https://www.google.com/search?q=c" to "https://www.google.com/search?q=c",
            ),
            PhoneInfogaClient.labeledLinks(tree)
        )
    }

    @Test
    fun flattenIsCappedSoAHugeAnswerCannotFloodTheScreen() {
        val tree = (1..500).associate { "k$it" to "v$it" }
        val (rows, _) = PhoneInfogaClient.flatten(tree)
        assertEquals(60, rows.size)
    }

    // ------------------------------------------------------- against a fake server

    private fun reply(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun withFakePhoneInfoga(received: MutableList<String>, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v2/scanners") { ex ->
            val path = ex.requestURI.path
            val body = ex.requestBody.readBytes().decodeToString()
            received += "${ex.requestMethod} $path $body"
            when (path) {
                "/api/v2/scanners" -> reply(
                    ex, 200,
                    """{"success":true,"scanners":[
                        {"name":"local","description":"Local info"},
                        {"name":"numverify","description":"Numverify"},
                        {"name":"googlesearch","description":"Dork links"}]}"""
                )
                "/api/v2/scanners/numverify/dryrun" ->
                    reply(ex, 500, """{"success":false,"error":"NUMVERIFY_API_KEY is not set"}""")
                "/api/v2/scanners/local/dryrun", "/api/v2/scanners/googlesearch/dryrun" ->
                    reply(ex, 200, """{"success":true}""")
                "/api/v2/scanners/local/run" ->
                    reply(ex, 200, """{"success":true,"result":{"rawLocal":"020 7946 0958","country":"GB","valid":true}}""")
                "/api/v2/scanners/googlesearch/run" ->
                    reply(ex, 200, """{"success":true,"result":{"general":["https://www.google.com/search?q=x"]}}""")
                else -> reply(ex, 404, """{"success":false,"error":"not found"}""")
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fullScanRunsWhatCanRunAndExplainsWhatCannot() {
        val received = java.util.Collections.synchronizedList(mutableListOf<String>())
        withFakePhoneInfoga(received) { base ->
            val report = runBlocking { PhoneInfogaClient.scan(base, "+442079460958") }
            assertNull(report.error)
            assertEquals(listOf("local", "numverify", "googlesearch"), report.scans.map { it.scanner })

            val local = report.scans[0]
            assertEquals(PhoneInfogaStatus.OK, local.status)
            assertTrue(local.rows.contains("Country" to "GB"))

            val numverify = report.scans[1]
            assertEquals(PhoneInfogaStatus.SKIPPED, numverify.status)
            assertEquals("NUMVERIFY_API_KEY is not set", numverify.message)

            val dorks = report.scans[2]
            assertEquals(PhoneInfogaStatus.OK, dorks.status)
            assertEquals(listOf("https://www.google.com/search?q=x"), dorks.links)

            // The skipped scanner was never run, and the number went out as digits only.
            assertTrue(received.none { it.contains("numverify/run") })
            assertTrue(received.any { it.startsWith("POST /api/v2/scanners/local/run") && it.contains("\"number\":\"442079460958\"") })
        }
    }

    @Test
    fun unreachableServerGivesAClearErrorAndNoScans() {
        val report = runBlocking { PhoneInfogaClient.scan("http://127.0.0.1:1", "+442079460958") }
        assertTrue(report.scans.isEmpty())
        assertTrue(report.error!!.contains("Couldn't reach"))
    }

    @Test
    fun emptyNumberIsRefusedBeforeAnyRequest() {
        val report = runBlocking { PhoneInfogaClient.scan("http://127.0.0.1:1", "abc") }
        assertEquals("Enter a phone number first.", report.error)
    }
}
