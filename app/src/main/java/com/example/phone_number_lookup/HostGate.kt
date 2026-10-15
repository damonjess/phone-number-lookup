package com.example.phone_number_lookup

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Spaces out requests to the same website. Several entries can point at one host (GitHub User and
 * GitHub Gists, many blogs on one domain), and firing them together is a quick way to be rate-limited.
 * Requests to different hosts are not affected.
 */
object HostGate {

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val lastStart = ConcurrentHashMap<String, Long>()

    private val SECOND_LEVEL = setOf("co", "com", "org", "net", "gov", "ac", "edu")

    /** "https://{account}.tumblr.com/x" and "https://www.tumblr.com/{account}" both give "tumblr.com". */
    internal fun hostKey(urlTemplate: String): String {
        val url = urlTemplate.replace("{account}", "x").replace("{}", "x")
        val host = try {
            URI(if ("://" in url) url else "https://$url").host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return url.lowercase()

        val labels = host.removePrefix("www.").split('.')
        if (labels.size <= 2) return labels.joinToString(".")
        val takeThree = labels.last().length == 2 && labels[labels.size - 2] in SECOND_LEVEL
        return labels.takeLast(if (takeThree) 3 else 2).joinToString(".")
    }

    /** Waits until [minGapMs] has passed since the last request to this host started, then runs [block]. */
    suspend fun <T> run(key: String, minGapMs: Long = 250, block: suspend () -> T): T {
        val mutex = locks.getOrPut(key) { Mutex() }
        mutex.withLock {
            val wait = (lastStart[key] ?: 0L) + minGapMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastStart[key] = System.currentTimeMillis()
        }
        return block()
    }
}
