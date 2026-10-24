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
    private val lock = Mutex()
    @Volatile private var clean = false
    @Volatile private var startCounts = true

    /** True once a sweep has succeeded since process start or the last [invalidate]; false means the page must not load. */
    suspend fun clean(): Boolean = lock.withLock {
        if (clean) return@withLock true
        val fromStart = startCounts && runCatching { startSweep() }.getOrDefault(false)
        startCounts = false
        val ok = fromStart || runCatching { sweep() }.getOrDefault(false)
        if (ok) clean = true
        ok
    }

    /** The directories were deleted under a page that may have run: the next page sweeps again first. */
    fun invalidate() {
        startCounts = false
        clean = false
    }
}
