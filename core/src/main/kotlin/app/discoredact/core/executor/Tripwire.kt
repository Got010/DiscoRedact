package app.discoredact.core.executor

/**
 * The invalid-request tripwire (spec section 8): a rolling count of 401, 403 and non-shared 429 responses within
 * [windowMs]; the run halts when the count reaches [limit]. Discord's own ban threshold is 10,000 in 10 minutes;
 * five is already a sign that something is wrong.
 */
class Tripwire(private val windowMs: Long = 10 * 60 * 1000L, val limit: Int = 5) {
    private val stamps = ArrayDeque<Long>()

    init {
        require(windowMs > 0 && limit > 0)
    }

    /** Records an invalid response at [nowMs]; true when the run must halt. */
    fun record(nowMs: Long): Boolean {
        stamps.addLast(nowMs)
        trim(nowMs)
        return stamps.size >= limit
    }

    fun count(nowMs: Long): Int {
        trim(nowMs)
        return stamps.size
    }

    private fun trim(nowMs: Long) {
        while (stamps.isNotEmpty() && nowMs - stamps.first() >= windowMs) stamps.removeFirst()
    }
}
