package com.example.phone_number_lookup

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress

class ScanHttpBodyCapTest {

    private fun withServer(chars: Int, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/big") { ex ->
            val bytes = "a".repeat(chars).toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/big")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun profilePagesAreCappedByDefault() = withServer(1_000_000) { url ->
        val response = ScanHttp.get(url)
        assertEquals(200, response.code)
        assertEquals(ScanHttp.DEFAULT_MAX_BODY_CHARS, response.body.length)
    }

    @Test
    fun datasetDownloadsCanAskForMore() = withServer(1_000_000) { url ->
        val response = ScanHttp.get(url, maxBodyChars = 16_000_000)
        assertEquals(1_000_000, response.body.length)
    }

    @Test
    fun smallBodiesAreReturnedWhole() = withServer(1_234) { url ->
        assertEquals(1_234, ScanHttp.get(url).body.length)
    }
}
