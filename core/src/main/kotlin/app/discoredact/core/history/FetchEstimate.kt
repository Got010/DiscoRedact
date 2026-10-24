package app.discoredact.core.history

import app.discoredact.core.executor.GetRequest
import app.discoredact.core.pacing.PacingConfig

/** The notice's estimate (B34 API history, section 6): about one read per 100 messages, plus one per DM, plus two. */
object FetchEstimate {
    /**
     * Reads for [dms] DMs holding [messages] messages in all (everyone's, not only the account's: every row of a page
     * counts toward its 100): one page per 100 messages and one more per DM (the short last page), plus `/users/@me` and
     * the DM list.
     */
    fun requests(dms: Int, messages: Long): Long {
        require(dms >= 0 && messages >= 0)
        return 2L + dms + messages / GetRequest.PAGE_LIMIT
    }

    /**
     * Wall time at [config]'s pacing for [requests] reads over [dms] DMs: the mean request delay per read, the mean
     * channel-switch pause per DM after the first, and the mean break per full session. Quiet hours and the daily cap
     * come on top (the caller words those).
     */
    fun durationMs(requests: Long, dms: Int, config: PacingConfig): Long {
        val active = requests * (config.baseDelayMs + config.jitterMaxMs / 2) +
            maxOf(0, dms - 1).toLong() * ((config.channelSwitchMinMs + config.channelSwitchMaxMs) / 2)
        val breaks = active / config.sessionMaxMs
        return active + breaks * ((config.breakMinMs + config.breakMaxMs) / 2)
    }

    /** Days the daily cap spreads [requests] over (at least one). */
    fun days(requests: Long, config: PacingConfig): Long = maxOf(1L, (requests + config.dailyCap - 1) / config.dailyCap)
}
