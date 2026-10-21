package app.discoredact.core.executor

import app.discoredact.core.pacing.PacingConfig

/** A rough time estimate for the Run screen: average delays, session breaks, the daily cap. Quiet hours are shown separately. */
object Eta {
    data class Estimate(
        /** Messages that can still go today under the daily cap. */
        val today: Int,
        /** Milliseconds of running time for [today], breaks included. */
        val todayMs: Long,
        /** Messages left for later days. */
        val later: Int,
        /** Whole days after today the rest needs at the cap. */
        val laterDays: Int,
    )

    fun estimate(remaining: Int, config: PacingConfig, deletedToday: Int, multiplier: Long = 1): Estimate {
        require(remaining >= 0 && multiplier >= 1)
        val cap = (config.dailyCap - deletedToday).coerceAtLeast(0)
        val today = minOf(remaining, cap)
        val later = remaining - today
        val perMessage = (config.baseDelayMs + config.jitterMaxMs / 2) * multiplier
        val active = today * perMessage
        val sessions = if (active == 0L) 0 else ((active - 1) / config.sessionMaxMs).toInt()
        val breaks = sessions * ((config.breakMinMs + config.breakMaxMs) / 2)
        val laterDays = if (later == 0) 0 else ((later + config.dailyCap - 1) / config.dailyCap)
        return Estimate(today, active + breaks, later, laterDays)
    }
}
