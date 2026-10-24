package app.discoredact.core.signin

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Whether a sign-in page may load over the sign-in page's data directories (CLAUDE.md rules 9 and 19). A page loads only
 * after a sweep (deleting what an earlier page left) has succeeded in this process. The sweep made at process start
 * counts once; a failed sweep is tried again on the next ask. [invalidate] (Wipe everything, which deletes the
 * directories while a page may have run earlier in the same process) makes the next page sweep again before it
 * loads, instead of trusting the earlier success. Pure, tested on the JVM; the app passes the two sweeps.
 */
class SignInSweepGate(
    /** The start-of-process sweep's result (awaited, not repeated). */
    private val startSweep: suspend () -> Boolean,
    /** A fresh sweep, run now. */
    private val sweep: suspend () -> Boolean,
) {
    /** One ask at a time. */
    private val lock = Mutex()

    /** Guards the three fields below; never held across a sweep, so [invalidate] needs no suspension. */
    private val state = Any()
    private var clean = false
    private var startCounts = true

    /** Bumped by [invalidate]: a sweep that began before a wipe never marks the directories clean after it. */
    private var generation = 0

    /** True once a sweep has succeeded since process start or the last [invalidate]; false means the page must not load. */
    suspend fun clean(): Boolean = lock.withLock {
        val (gen, useStart) = synchronized(state) {
            if (clean) return@withLock true
            val use = startCounts
            startCounts = false
            generation to use
        }
        val fromStart = useStart && runCatching { startSweep() }.getOrDefault(false)
        val ok = fromStart || runCatching { sweep() }.getOrDefault(false)
        synchronized(state) {
            // A wipe that ran during the sweep may have left a provider-held directory behind it: not clean.
            val stillValid = ok && generation == gen
            if (stillValid) clean = true
            stillValid
        }
    }

    /** The directories were deleted under a page that may have run: the next page sweeps again first. */
    fun invalidate() {
        synchronized(state) {
            generation++
            startCounts = false
            clean = false
        }
    }
}
