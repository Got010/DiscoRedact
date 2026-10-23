package app.discoredact.core.signin

/**
 * How long the sign-in page may live while the app is not on screen (time to read a code in another app). Times are
 * on a clock that keeps running in deep sleep (the screen's elapsed-realtime clock), so a phone that slept through the
 * grace ends the page as soon as anything runs again.
 */
object SignInGrace {
    const val GRACE_MS: Long = 2L * 60 * 1000

    /**
     * True when the page must end now: the app left the screen at [stoppedAtMs] (null: it has not, or came back) and
     * the grace is over at [nowMs]. A clock that went backwards ends the page too (fail closed).
     */
    fun expired(stoppedAtMs: Long?, nowMs: Long): Boolean =
        stoppedAtMs != null && (nowMs - stoppedAtMs >= GRACE_MS || nowMs < stoppedAtMs)
}
