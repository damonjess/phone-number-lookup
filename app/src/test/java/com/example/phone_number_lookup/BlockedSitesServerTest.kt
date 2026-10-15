package com.example.phone_number_lookup

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Runs the real site check against a local server that behaves like sites that block or rate-limit. */
class BlockedSitesServerTest {

    private fun probe(base: String, kind: String) = SiteProbe(
        name = kind, category = "social", source = "Test", method = "GET",
        urlTemplate = "$base/$kind/{account}", accountMode = AccountMode.RAW,
        profileTemplate = "$base/$kind/{account}", headers = emptyMap(), bodyTemplate = null,
        foundCodes = emptySet(), foundStrings = listOf("Profile of"), missingCodes = emptySet(),
        missingStrings = emptyList(), missingUrls = emptyList(),
    )

    private fun reply(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun withSites(block: (base: String, requests: (String) -> Int) -> Unit) {
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val parts = ex.requestURI.path.trim('/').split('/')
            val kind = parts[0]
            val account = parts.getOrElse(1) { "" }
            val n = counts.getOrPut(kind) { AtomicInteger() }.incrementAndGet()
            val agent = ex.requestHeaders.getFirst("User-Agent").orEmpty()
            val profile = "Profile of $account"
            when (kind) {
                "found" -> if (account == "alice") reply(ex, 200, profile) else reply(ex, 404, "nope")
                "missing404" -> reply(ex, 404, "not found")
                "forbidden" -> reply(ex, 403, "Forbidden")
                "uasensitive" -> if ("X11; Linux" in agent) reply(ex, 200, profile) else reply(ex, 403, "Forbidden")
                "ratelimited" -> if (n == 1) reply(ex, 429, "slow down") else reply(ex, 200, profile)
                "server500" -> if (n == 1) reply(ex, 500, "oops") else reply(ex, 200, profile)
                "cloudflare" -> reply(ex, 403, "<title>Just a moment...</title><div id=\"cf-chl-widget\"></div>")
                "soft" -> reply(ex, 200, "Welcome to the site")
                else -> reply(ex, 404, "?")
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}") { kind -> counts[kind]?.get() ?: 0 }
        } finally {
            server.stop(0)
        }
    }

    private fun check(base: String, kind: String) =
        runBlocking { ResilientProbe.evaluate(probe(base, kind), "alice", timeoutMs = 3_000, pause = { }) }

    @Test
    fun anOrdinary404IsNotFoundNotBlocked() = withSites { base, requests ->
        val r = check(base, "missing404")
        assertEquals(ProbeVerdict.NOT_FOUND, r.verdict)
        assertEquals(null, r.failure)
        assertEquals(1, requests("missing404"))
    }

    @Test
    fun aRealProfileIsFoundFirstTime() = withSites { base, requests ->
        assertEquals(ProbeVerdict.FOUND, check(base, "found").verdict)
        assertEquals(1, requests("found"))
    }

    @Test
    fun aSiteThatRefusesTheDefaultAgentIsFoundOnTheRetry() = withSites { base, requests ->
        assertEquals(ProbeVerdict.FOUND, check(base, "uasensitive").verdict)
        assertEquals(2, requests("uasensitive"))
    }

    @Test
    fun rateLimitsAndServerErrorsRecoverOnTheRetry() = withSites { base, requests ->
        assertEquals(ProbeVerdict.FOUND, check(base, "ratelimited").verdict)
        assertEquals(ProbeVerdict.FOUND, check(base, "server500").verdict)
        assertEquals(2, requests("ratelimited"))
        assertEquals(2, requests("server500"))
    }

    @Test
    fun aRealRefusalIsReportedAsForbiddenAfterOneRetry() = withSites { base, requests ->
        val r = check(base, "forbidden")
        assertEquals(ProbeVerdict.UNKNOWN, r.verdict)
        assertEquals(ProbeFailure.FORBIDDEN, r.failure)
        assertEquals(2, requests("forbidden"))
    }

    @Test
    fun aBotCheckPageIsReportedAndNotWorkedAround() = withSites { base, requests ->
        val r = check(base, "cloudflare")
        assertEquals(ProbeFailure.CHALLENGE, r.failure)
        assertEquals(1, requests("cloudflare"))
    }

    @Test
    fun aPageWithNoMarkerIsInconclusiveNotBlocked() = withSites { base, requests ->
        val r = check(base, "soft")
        assertEquals(ProbeFailure.INCONCLUSIVE, r.failure)
        assertEquals(1, requests("soft"))
    }

    @Test
    fun aServerThatIsDownIsANetworkIssue() {
        val r = runBlocking {
            ResilientProbe.evaluate(probe("http://127.0.0.1:1", "found"), "alice", timeoutMs = 1_000, pause = { })
        }
        assertEquals(ProbeFailure.NETWORK, r.failure)
        assertTrue(r.evidence.contains("Network"))
    }
}
