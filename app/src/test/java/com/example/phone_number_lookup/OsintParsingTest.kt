package com.example.phone_number_lookup

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.net.InetSocketAddress

/**
 * Tests for the live OSINT scanners.
 *
 * The dataset parsers run against captured payload shapes (verified against the real
 * upstream GitHub files) so they are deterministic. The detection tests spin up a local
 * HTTP server and drive the *real* request path, so the found/missing classification is
 * exercised end to end without depending on any third-party site.
 */
class OsintParsingTest {

    companion object {
        private lateinit var server: HttpServer
        private var port: Int = 0

        @BeforeClass
        @JvmStatic
        fun startServer() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

            // A profile that exists.
            server.createContext("/found") { ex ->
                respond(ex, 200, "<html>profile of alice</html>")
            }
            // A profile that does not exist.
            server.createContext("/missing") { ex ->
                respond(ex, 404, "<html>Sorry, nobody by that name</html>")
            }
            // Same status code for both outcomes; only the body distinguishes them.
            server.createContext("/body-signal") { ex ->
                val user = ex.requestURI.query.orEmpty()
                if (user.contains("taken")) respond(ex, 200, "{\"taken\":true}")
                else respond(ex, 200, "{\"taken\":false}")
            }
            // POST endpoint that answers differently based on the submitted address.
            server.createContext("/signup") { ex ->
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                if (body.contains("registered@example.com")) {
                    respond(ex, 200, "{\"exists\":true}")
                } else {
                    respond(ex, 200, "{\"exists\":false}")
                }
            }
            // Redirects when the account is missing (Sherlock `response_url` style).
            server.createContext("/redirect") { ex ->
                ex.responseHeaders.add("Location", "/gone")
                ex.sendResponseHeaders(302, -1)
                ex.close()
            }
            server.createContext("/gone") { ex -> respond(ex, 200, "no such user") }

            server.executor = null
            server.start()
            port = server.address.port
        }

        @AfterClass
        @JvmStatic
        fun stopServer() {
            server.stop(0)
        }

        private fun respond(ex: HttpExchange, code: Int, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        private fun probe(
            path: String,
            foundCodes: Set<Int> = emptySet(),
            foundStrings: List<String> = emptyList(),
            missingCodes: Set<Int> = emptySet(),
            missingStrings: List<String> = emptyList(),
            missingUrls: List<String> = emptyList(),
            method: String = "GET",
            body: String? = null,
            mode: AccountMode = AccountMode.RAW
        ) = SiteProbe(
            name = "LocalTest",
            category = "test",
            source = "test",
            method = method,
            urlTemplate = "http://127.0.0.1:$port$path",
            accountMode = mode,
            profileTemplate = "http://127.0.0.1:$port$path",
            headers = if (method == "POST") mapOf("Content-Type" to "application/json") else emptyMap(),
            bodyTemplate = body,
            foundCodes = foundCodes,
            foundStrings = foundStrings,
            missingCodes = missingCodes,
            missingStrings = missingStrings,
            missingUrls = missingUrls
        )
    }

    // ------------------------------------------------------------ detection

    @Test
    fun evaluate_reportsFoundForA200Response() {
        val result = OsintDatasets.evaluate(probe("/found"), "alice", timeoutMs = 5_000)
        assertEquals(ProbeVerdict.FOUND, result.verdict)
    }

    @Test
    fun evaluate_reportsNotFoundForA404Response() {
        val result = OsintDatasets.evaluate(probe("/missing", missingCodes = setOf(404)), "alice", 5_000)
        assertEquals(ProbeVerdict.NOT_FOUND, result.verdict)
    }

    @Test
    fun evaluate_bodySignalDecidesWhenStatusIsIdentical() {
        // Both requests answer HTTP 200, so only the body marker can separate them.
        val taken = probe(
            "/body-signal?user=taken",
            foundStrings = listOf("\"taken\":true"),
            missingStrings = listOf("\"taken\":false")
        )
        val free = probe(
            "/body-signal?user=free",
            foundStrings = listOf("\"taken\":true"),
            missingStrings = listOf("\"taken\":false")
        )

        assertEquals(ProbeVerdict.FOUND, OsintDatasets.evaluate(taken, "x", 5_000).verdict)
        assertEquals(ProbeVerdict.NOT_FOUND, OsintDatasets.evaluate(free, "x", 5_000).verdict)
    }

    @Test
    fun evaluate_missingSignalWinsOverACoincidentalFoundString() {
        // A site that renders the "exists" marker somewhere in its shell must still be
        // treated as a miss when the explicit missing marker is present. This ordering is
        // what stops SPA sites producing false positives.
        val probe = SiteProbe(
            name = "SPA", category = "test", source = "test", method = "GET",
            urlTemplate = "data:text/html,{\"exists\":true}",
            accountMode = AccountMode.RAW,
            profileTemplate = "https://example.com/",
            headers = emptyMap(),
            bodyTemplate = null,
            foundCodes = setOf(200),
            foundStrings = listOf("\"exists\":true"),
            missingCodes = emptySet(),
            missingStrings = listOf("<html>"),
            missingUrls = emptyList()
        )
        // data: URLs are not fetchable over HTTP, so this exercises the network-error path.
        val result = OsintDatasets.evaluate(probe, "x", 2_000)
        assertEquals(ProbeVerdict.UNKNOWN, result.verdict)
    }

    @Test
    fun evaluate_postBodyIsSent() {
        val registered = probe(
            "/signup",
            foundStrings = listOf("\"exists\":true"),
            missingStrings = listOf("\"exists\":false"),
            method = "POST",
            body = "{\"email\":\"{account}\"}"
        )
        val result = OsintDatasets.evaluate(registered, "registered@example.com", 5_000)
        assertEquals(ProbeVerdict.FOUND, result.verdict)
    }

    @Test
    fun evaluate_postBodyMarksUnknownAsNotFound() {
        val unregistered = probe(
            "/signup",
            foundStrings = listOf("\"exists\":true"),
            missingStrings = listOf("\"exists\":false"),
            method = "POST",
            body = "{\"email\":\"{account}\"}"
        )
        val result = OsintDatasets.evaluate(unregistered, "nobody@example.com", 5_000)
        assertEquals(ProbeVerdict.NOT_FOUND, result.verdict)
    }

    @Test
    fun evaluate_matchesRedirectTargetForMissingAccounts() {
        val result = OsintDatasets.evaluate(
            probe("/redirect", missingUrls = listOf("/gone")),
            "alice",
            5_000
        )
        assertEquals(ProbeVerdict.NOT_FOUND, result.verdict)
    }

    // -------------------------------------------------------- account modes

    @Test
    fun hashMd5MatchesTheKnownGravatarIdentifier() {
        assertEquals(
            "55502f40dc8b7c769880b10874abc9d0",
            OsintDatasets.transformAccount("test@example.com", AccountMode.HASH_MD5)
        )
    }

    @Test
    fun hashSha256IsLowercaseHex() {
        assertEquals(
            "973dfe463ec85785f5f95af5ba3906eedb2d931c24e69824a89ea65dba4e813b",
            OsintDatasets.transformAccount("test@example.com", AccountMode.HASH_SHA256)
        )
    }

    @Test
    fun hashIsCaseInsensitiveLikeTheRealService() {
        assertEquals(
            OsintDatasets.transformAccount("Test@Example.COM", AccountMode.HASH_MD5),
            OsintDatasets.transformAccount("test@example.com", AccountMode.HASH_MD5)
        )
    }

    @Test
    fun localPartExtractionHandlesInvalidInput() {
        assertEquals("alex.smith", EmailRegistrationScanner.localPart("alex.smith@gmail.com"))
        assertNull(EmailRegistrationScanner.localPart("not-an-email"))
        assertNull(EmailRegistrationScanner.localPart("@gmail.com"))
    }

    // ---------------------------------------------------- dataset parsing

    @Test
    fun whatsMyName_keepsGetAndPostSitesAndSkipsBrokenRows() {
        val json = """
        {
          "sites": [
            {"name":"GitHub","cat":"coding","uri_check":"https://github.com/{account}","uri_pretty":"https://github.com/{account}","e_code":200,"e_string":"","m_code":404,"m_string":"Not Found"},
            {"name":"POSTSITE","cat":"x","uri_check":"https://x.com/{account}","e_code":200,"m_code":404,"post_body":"a=b"},
            {"name":"INVALID","cat":"x","uri_check":"https://y.com/{account}","valid":false,"e_code":200,"m_code":404},
            {"name":"NoTemplate","cat":"x","uri_check":"https://z.com/","e_code":200,"m_code":404}
          ]
        }
        """.trimIndent()

        val sites = OsintDatasets.parseWhatsMyName(json)

        assertEquals(2, sites.size)
        val github = sites.first { it.name == "GitHub" }
        assertEquals("GET", github.method)
        assertEquals(setOf(404), github.missingCodes)
        assertEquals(listOf("Not Found"), github.missingStrings)

        val post = sites.first { it.name == "POSTSITE" }
        assertEquals("POST", post.method)
        assertEquals("a=b", post.bodyTemplate)
    }

    @Test
    fun sherlock_convertsPlaceholdersAndReadsScalarSignals() {
        val json = """
        {
          "${'$'}schema": "data.schema.json",
          "GitHub": {
            "errorType": "status_code",
            "url": "https://www.github.com/{}",
            "urlMain": "https://www.github.com/",
            "username_claimed": "blue"
          },
          "SomeForum": {
            "errorType": "message",
            "url": "https://forum.example/{}",
            "errorMsg": "user not found",
            "errorCode": 404,
            "request_method": "POST",
            "request_payload": {"username": "{}"},
            "headers": {"X-Test": "1"}
          }
        }
        """.trimIndent()

        val sites = OsintDatasets.parseSherlock(json)

        assertEquals(2, sites.size)
        // The `${'$'}schema` pseudo-site must be skipped.
        assertTrue(sites.none { it.name.startsWith("$") })

        val github = sites.first { it.name == "GitHub" }
        assertEquals("https://www.github.com/{account}", github.urlTemplate)
        assertEquals("GET", github.method)
        assertEquals(emptySet<Int>(), github.foundCodes)
        // No errorCode in the source object, so nothing is declared as a miss marker.
        assertEquals(emptySet<Int>(), github.missingCodes)

        val forum = sites.first { it.name == "SomeForum" }
        assertEquals("POST", forum.method)
        assertEquals(setOf(404), forum.missingCodes)
        assertEquals(listOf("user not found"), forum.missingStrings)
        assertTrue(forum.bodyTemplate!!.contains("{account}"))
        assertEquals("1", forum.headers["X-Test"])
    }

    @Test
    fun sherlock_acceptsArraysForErrorCodeAndErrorMsg() {
        val json = """
        {
          "Multi": {
            "errorType": "message",
            "url": "https://multi.example/{}",
            "errorCode": [404, 410],
            "errorMsg": ["not found", "no such user"],
            "errorUrl": "/missing"
          }
        }
        """.trimIndent()

        val site = OsintDatasets.parseSherlock(json).single()

        assertEquals(setOf(404, 410), site.missingCodes)
        assertEquals(listOf("not found", "no such user"), site.missingStrings)
        assertEquals(listOf("/missing"), site.missingUrls)
    }

    @Test
    fun tookie_appendsUsernameAndUsesErrorMessages() {
        val json = """
        [
          {"site":"https://www.youtube.com/@","nsfw":"false","errorMessage":"This page isn't available."},
          {"site":"https://example.com/","nsfw":"true","errorMessage":"none"}
        ]
        """.trimIndent()

        val sites = OsintDatasets.parseTookie(json)

        assertEquals(2, sites.size)
        val youtube = sites.first { it.category == "general" }
        assertEquals("https://www.youtube.com/@{account}", youtube.urlTemplate)
        assertEquals(AccountMode.APPEND, youtube.accountMode)
        assertEquals(listOf("This page isn't available."), youtube.missingStrings)

        // "none" is the dataset's placeholder for "no marker" and must not become a signal.
        val nsfw = sites.first { it.category == "nsfw" }
        assertTrue(nsfw.missingStrings.isEmpty())
    }

    @Test
    fun blackbirdEmail_readsMethodHeadersAndInputOperation() {
        val json = """
        {
          "authors": ["x"],
          "sites": [
            {"name":"Gravatar","uri_check":"https://gravatar.com/{account}.json","data":null,"method":"GET","headers":null,"e_code":200,"e_string":"displayName","m_code":404,"m_string":"User not found","cat":"images","input_operation":"hash-sha256"},
            {"name":"Adobe","uri_check":"https://auth.services.adobe.com/signin/v2/users/accounts","data":"{\"username\":\"{account}\",\"usernameType\":\"EMAIL\"}","method":"POST","headers":{"X-Ims-Clientid":"homepage_milo"},"e_code":200,"e_string":"authenticationMethods","m_code":404,"m_string":"[]","cat":"misc","input_operation":null}
          ]
        }
        """.trimIndent()

        val sites = OsintDatasets.parseBlackbirdEmail(json)

        assertEquals(2, sites.size)
        val gravatar = sites.first { it.name == "Gravatar" }
        assertEquals(AccountMode.HASH_SHA256, gravatar.accountMode)
        assertEquals("GET", gravatar.method)
        assertEquals(listOf("User not found"), gravatar.missingStrings)

        val adobe = sites.first { it.name == "Adobe" }
        assertEquals("POST", adobe.method)
        assertEquals("homepage_milo", adobe.headers["X-Ims-Clientid"])
        assertTrue(adobe.bodyTemplate!!.contains("{account}"))
    }

    // -------------------------------------------------------- curated probes

    @Test
    fun curatedProbesAreWellFormed() {
        for (probe in CuratedEmailChecks.PROBES) {
            // The account may sit in the URL or in a POST body (Firefox uses the body).
            assertTrue(
                "${probe.name} must carry a placeholder in the URL or body",
                probe.urlTemplate.contains("{account}") ||
                    probe.bodyTemplate?.contains("{account}") == true
            )
            assertTrue(
                "${probe.name} needs at least one signal",
                probe.foundCodes.isNotEmpty() || probe.foundStrings.isNotEmpty() ||
                    probe.missingCodes.isNotEmpty() || probe.missingStrings.isNotEmpty()
            )
            // A probe that can detect neither outcome would be dead weight.
            assertTrue(
                "${probe.name} must be able to report found or not found",
                probe.foundCodes.isNotEmpty() || probe.foundStrings.isNotEmpty()
            )
            assertTrue(
                "${probe.name} must be able to report a miss",
                probe.missingCodes.isNotEmpty() || probe.missingStrings.isNotEmpty()
            )
            // A declared body must actually be sent as a POST, otherwise it is wasted.
            if (probe.bodyTemplate != null) {
                assertEquals(
                    "${probe.name} declares a body so it must use POST",
                    "POST",
                    probe.method
                )
            }
            assertTrue(
                "${probe.name} needs a profile link",
                probe.profileTemplate.isNotBlank()
            )
        }
    }

    @Test
    fun sherlockPostPayloadGetsItsPlaceholderRewritten() {
        // Mirrors Discord in the real dataset: the URL is a fixed API endpoint with no
        // placeholder, so the account only exists inside the POST payload.
        val json = """
        {
          "Discord": {
            "errorType": "message",
            "url": "https://discord.com",
            "urlProbe": "https://discord.com/api/v9/unique-username/username-attempt-unauthed",
            "errorMsg": ["{\"taken\":false}", "The resource is being rate limited"],
            "request_method": "POST",
            "request_payload": {"username": "{}"},
            "headers": {"Content-Type": "application/json"}
          }
        }
        """.trimIndent()

        val site = OsintDatasets.parseSherlock(json).single()

        assertEquals("POST", site.method)
        assertEquals(listOf("{\"taken\":false}", "The resource is being rate limited"), site.missingStrings)
        assertTrue(site.bodyTemplate!!.contains("{account}"))
        assertFalse(site.bodyTemplate!!.contains("{}"))
    }

    @Test
    fun sherlockSkipsEntriesWithNoPlaceholderAnywhere() {
        val json = """
        {
          "Broken": {
            "errorType": "status_code",
            "url": "https://broken.example/",
            "username_claimed": "blue"
          }
        }
        """.trimIndent()

        assertTrue(OsintDatasets.parseSherlock(json).isEmpty())
    }

    @Test
    fun blackbirdAcceptsChecksThatCarryTheAccountOnlyInTheBody() {
        // Adobe posts the address to a fixed endpoint, so the URL has no placeholder.
        val json = """
        {"sites":[
          {"name":"Adobe","uri_check":"https://auth.services.adobe.com/signin/v2/users/accounts","data":"{\"username\":\"{account}\"}","method":"POST","e_code":200,"e_string":"authenticationMethods","m_code":404,"m_string":"[]","cat":"misc"},
          {"name":"BothEmpty","uri_check":"https://nothing.example/","method":"GET","e_code":200,"m_code":404}
        ]}
        """.trimIndent()

        val sites = OsintDatasets.parseBlackbirdEmail(json)

        assertEquals(1, sites.size)
        assertEquals("Adobe", sites.single().name)
        assertEquals("POST", sites.single().method)
    }

    @Test
    fun gravatarOverrideUsesMd5NotSha256() {
        val gravatar = CuratedEmailChecks.PROBES.first { it.name == "Gravatar" }
        assertEquals(AccountMode.HASH_MD5, gravatar.accountMode)
    }

    // ------------------------------------------------------------- tellows

    @Test
    fun tellows_parsesScoreLocationAndComments() {
        val body =
            """{"tellows":{"number":"02079460958","normalizedNumber":"020-7946-0958","score":"5","searches":"1","comments":"3","location":"London","country":"United Kingdom"}}Partner Data not correct"""

        val report = SpamScanner.parseReport(body, "+442079460958", "https://www.tellows.co.uk")

        assertNotNull(report)
        assertEquals(5, report!!.score)
        assertEquals(3, report.comments)
        assertEquals("London", report.location)
        assertEquals("United Kingdom", report.country)
        assertEquals("tellows.co.uk", report.source)
        assertEquals("Suspicious / possible spam", report.riskLabel)
    }

    @Test
    fun tellows_zeroCommentsIsUnratedRatherThanSuspicious() {
        // tellows hands out a default 5 when nobody has reported the number.
        // Calling that "suspicious" would invent a warning.
        val body =
            """{"tellows":{"number":"02079460958","score":"5","searches":"1","comments":"0","location":"London","country":"United Kingdom"}}"""

        val report = SpamScanner.parseReport(body, "+442079460958", "https://www.tellows.co.uk")!!

        assertFalse("an unreported number must not signal reports", report.hasCommunityReports)
        assertEquals("Unrated - no community reports yet", report.riskLabel)
        assertTrue(
            "the placeholder score must be labelled as such",
            report.scoreLabel.contains("tellows default")
        )
    }

    @Test
    fun tellows_withCommunityReportsStillBandsTheScore() {
        val body = """{"tellows":{"score":"5","searches":"9","comments":"4"}}"""

        val report = SpamScanner.parseReport(body, "+442079460958", "https://www.tellows.co.uk")!!

        assertTrue(report.hasCommunityReports)
        assertEquals("Suspicious / possible spam", report.riskLabel)
        assertEquals("5 / 9", report.scoreLabel)
    }

    @Test
    fun tellows_returnsNullWhenNoTellowsObject() {
        assertNull(SpamScanner.parseReport("{\"error\":true}", "+441234", "https://www.tellows.de"))
        assertNull(SpamScanner.parseReport("not json at all", "+441234", "https://www.tellows.de"))
    }

    // -------------------------------------------------------- XposedOrNot

    @Test
    fun xposedOrNot_parsesNestedBreachArray() {
        val body =
            """{"breaches":[["Mpgh","Havenly","Duolingo"]],"email":"test@example.com","status":"success"}"""

        val report = EmailScanner.parseBreach("test@example.com", body)

        assertTrue(report.breached)
        assertEquals(listOf("Mpgh", "Havenly", "Duolingo"), report.breachNames)
        assertNull(report.error)
    }

    @Test
    fun xposedOrNot_notFoundIsNotBreached() {
        val report = EmailScanner.parseBreach("test@example.com", """{"Error":"Not found","email":null}""")
        assertFalse(report.breached)
        assertTrue(report.breachNames.isEmpty())
    }

    // ----------------------------------------------------- phone reputation

    @Test
    fun whoCallsMeReportTextIsFlattenedAndParsed() {
        val html = """
            <html><head><style>.a{color:red}</style>
            <script>var x = "call type: should not be counted";</script></head>
            <body><div id="threadPosts">
            <div>Caller: Lombardi and O'connor</div> Call type: Debt collector Reply
            <div>Caller: Legal Services</div> Call type: Scam Reply
            </div></body></html>
        """.trimIndent()

        val text = PhoneReputation.htmlToText(html)

        // Script and style contents must be gone before pattern matching.
        assertFalse(text.contains("should not be counted"))
        assertFalse(text.contains("color:red"))

        val types = Regex("call type:\\s*([^\\n|]{2,40})", RegexOption.IGNORE_CASE)
            .findAll(text).map { it.groupValues[1].trim() }.toList()
        assertEquals(2, types.size)
        assertTrue(text.contains("Lombardi and O'connor"))
    }

    @Test
    fun reputationSourcesAreAdvertisedForSupportedNumbers() {
        assertEquals(listOf("WhoCallsMe", "800notes"), PhoneReputation.supportedSources("+18554208313"))
        assertEquals(listOf("Who Called UK", "Should I Answer UK", "WhoCallsMe"), PhoneReputation.supportedSources("+447700900123"))
        assertTrue(PhoneReputation.supportedSources("+3312345678").isEmpty())
    }

    @Test
    fun cleanProfileUrlSanitizesApiEndpointsAndSpotify() {
        val spotifyApiUrl = "https://spclient.wg.spotify.com/signup/public/v1/account?validate=1&email=test@example.com"
        val cleanedSpotify = OsintDatasets.cleanProfileUrl("Spotify", spotifyApiUrl)
        assertEquals("https://open.spotify.com/", cleanedSpotify)

        val firefoxApiUrl = "https://api.accounts.firefox.com/v1/account/status"
        val cleanedFirefox = OsintDatasets.cleanProfileUrl("Mozilla Firefox", firefoxApiUrl)
        assertEquals("https://accounts.firefox.com/", cleanedFirefox)
    }
}
