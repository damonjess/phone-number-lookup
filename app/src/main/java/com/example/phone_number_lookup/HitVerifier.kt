package com.example.phone_number_lookup

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random

/**
 * Removes false positives with a control check.
 *
 * Some sites answer "found" to everything: a soft-404 page that returns HTTP 200, a signup
 * endpoint that always says "taken", a captcha page. A made-up account that cannot exist
 * should come back "not found". If the site still says "found", its answer for the real
 * account means nothing, so the hit is discarded.
 *
 * Only sites that already reported a hit are re-checked, so this costs a few dozen extra
 * requests rather than doubling the scan.
 */
object HitVerifier {

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** A plausible but effectively impossible username, e.g. "k4q9vtzm2w7hbd". */
    fun randomUsername(random: Random = Random.Default): String = token(random, 14)

    /** Local part for an address that effectively cannot exist, e.g. "q8vtzm2w7hb". */
    fun randomLocalPart(random: Random = Random.Default): String = token(random, 11)

    private fun token(random: Random, length: Int): String = buildString {
        append(('a'..'z').random(random)) // start with a letter; some sites reject leading digits
        repeat(length - 1) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    /**
     * @param controlSaysFound runs the control check for one hit and returns true when the site
     *        also reports "found" for a made-up account.
     * @return the hits that passed (kept) and those that were discarded as unreliable
     */
    suspend fun <T> splitByControl(
        hits: List<T>,
        concurrency: Int,
        controlSaysFound: suspend (T) -> Boolean,
    ): Pair<List<T>, List<T>> = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        val flags = hits.map { hit -> async { semaphore.withPermit { controlSaysFound(hit) } } }.awaitAll()
        val kept = ArrayList<T>()
        val dropped = ArrayList<T>()
        hits.forEachIndexed { i, hit -> if (flags[i]) dropped += hit else kept += hit }
        kept to dropped
    }
}
