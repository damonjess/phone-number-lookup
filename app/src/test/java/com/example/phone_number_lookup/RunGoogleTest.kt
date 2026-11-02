package com.example.phone_number_lookup

import kotlinx.coroutines.runBlocking
import org.junit.Test

class RunGoogleTest {
    @Test
    fun doRun() = runBlocking {
        val email = "damonkirby86@gmail.com"
        val engine = EmailAttributionEngine.analyze(email)
        println("ENGINE TIMELINE: ${engine.timeline}")
        println("FIRST SEEN: ${engine.firstSeen}")
        println("LAST SEEN: ${engine.lastSeen}")
        val google = GoogleMetadataExtractor.extract(email)
        println("GOOGLE: $google")
    }
}
