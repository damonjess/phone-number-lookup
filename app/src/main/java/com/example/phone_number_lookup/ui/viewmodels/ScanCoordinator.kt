package com.example.phone_number_lookup.ui.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps concurrent scans from trampling each other.
 *
 * Scans here are long (a full username sweep is over a thousand requests) and they all
 * write into the same UI state, so without coordination two overlapping runs produce a
 * screen that is actively lying: the newest scan sets the title while the oldest one,
 * finishing last, overwrites the results. That was reproducible on device — a quick
 * `qzwx` scan displayed 563 profiles belonging to an earlier `blue` sweep.
 *
 * Every new scan calls [supersede] to cancel what is in flight and take a token. Results
 * are only applied while that token is still [isCurrent], and [cancelAll] invalidates
 * everything when the user leaves the screen or clears the form.
 *
 * The generation token matters even with cancellation: the HTTP calls are blocking and
 * not every one of them is interruptible, so a cancelled scan can still resume and try
 * to publish its results.
 */
class ScanCoordinator(private val scope: CoroutineScope) {

    private val jobs = ArrayList<Job>()
    private var generation = 0

    /** Cancels everything in flight and returns the token for the run that supersedes it. */
    fun supersede(): Int {
        jobs.forEach { it.cancel() }
        jobs.clear()
        return ++generation
    }

    /** True while [token] is still the newest run. */
    fun isCurrent(token: Int): Boolean = token == generation

    /** Launches work belonging to [token]. */
    fun start(token: Int, block: suspend (Int) -> Unit) {
        jobs += scope.launch { block(token) }
    }

    /** Drops all in-flight work and makes its results stale. */
    fun cancelAll() {
        supersede()
    }

    /** Number of runs still considered live; exposed for tests and diagnostics. */
    val activeCount: Int get() = jobs.size
}
