package app.discoredact.core.pacing

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.random.Random

/** Pacing presets (spec section 8; RJ OQ332). [SLOWER] is the default. */
enum class PacingPreset {
    /** Base 3000 ms + 0-3000 ms jitter, 30-min sessions, 15-30 min breaks, 1,000 deletes a day. */
    SLOWER,

    /** The spec's section 8 defaults: base 1500 ms + 0-1500 ms jitter, 45-min sessions, 10-20 min breaks, 2,000 a day. */
    GENTLE;

    companion object {
        val DEFAULT = SLOWER
    }
}

/** Quiet hours, local time: no requests from [startMinute] (inclusive) to [endMinute] (exclusive); may wrap midnight. */
data class QuietHours(val startMinute: Int, val endMinute: Int) {
    init {
        require(startMinute in 0 until MINUTES_PER_DAY && endMinute in 0 until MINUTES_PER_DAY) { "minute of day out of range" }
    }

    /** Equal start and end means no quiet hours. */
    val isNone: Boolean get() = startMinute == endMinute

    fun contains(time: LocalTime): Boolean {
        if (isNone) return false
        val m = time.hour * 60 + time.minute
        return if (startMinute < endMinute) m in startMinute until endMinute else m >= startMinute || m < endMinute
    }

    /** How long until requests may resume; zero outside quiet hours. */
    fun waitFrom(now: LocalDateTime): Duration {
        if (!contains(now.toLocalTime())) return Duration.ZERO
        val endToday = now.toLocalDate().atTime(endMinute / 60, endMinute % 60)
        val end = if (endToday.isAfter(now)) endToday else endToday.plusDays(1)
        return Duration.between(now, end)
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
        val DEFAULT = QuietHours(1 * 60, 8 * 60)
    }
}

/** Every pacing number the executor uses (M2). All configurable; none is a Discord limit (CLAUDE.md rule 7). */
data class PacingConfig(
    val baseDelayMs: Long,
    val jitterMaxMs: Long,
    val sessionMaxMs: Long,
    val breakMinMs: Long,
    val breakMaxMs: Long,
    val channelSwitchMinMs: Long,
    val channelSwitchMaxMs: Long,
    val rateLimitMarginMinMs: Long,
    val rateLimitMarginMaxMs: Long,
    val dailyCap: Int,
    val quietHours: QuietHours,
) {
    init {
        require(baseDelayMs >= 0 && jitterMaxMs >= 0)
        require(breakMinMs in 0..breakMaxMs && channelSwitchMinMs in 0..channelSwitchMaxMs)
        require(rateLimitMarginMinMs in 0..rateLimitMarginMaxMs)
        require(sessionMaxMs > 0 && dailyCap > 0)
    }

    companion object {
        private const val SEC = 1_000L
        private const val MIN = 60 * SEC

        fun forPreset(preset: PacingPreset): PacingConfig = when (preset) {
            PacingPreset.SLOWER -> PacingConfig(
                baseDelayMs = 3_000, jitterMaxMs = 3_000,
                sessionMaxMs = 30 * MIN, breakMinMs = 15 * MIN, breakMaxMs = 30 * MIN,
                channelSwitchMinMs = 30 * SEC, channelSwitchMaxMs = 90 * SEC,
                rateLimitMarginMinMs = 250, rateLimitMarginMaxMs = 750,
                dailyCap = 1_000, quietHours = QuietHours.DEFAULT,
            )
            PacingPreset.GENTLE -> PacingConfig(
                baseDelayMs = 1_500, jitterMaxMs = 1_500,
                sessionMaxMs = 45 * MIN, breakMinMs = 10 * MIN, breakMaxMs = 20 * MIN,
                channelSwitchMinMs = 30 * SEC, channelSwitchMaxMs = 90 * SEC,
                rateLimitMarginMinMs = 250, rateLimitMarginMaxMs = 750,
                dailyCap = 2_000, quietHours = QuietHours.DEFAULT,
            )
        }
    }
}

/** What Settings stores: the preset plus the two values the user may change (daily cap, quiet hours). */
data class PacingSettings(
    val preset: PacingPreset = PacingPreset.DEFAULT,
    val dailyCap: Int = PacingConfig.forPreset(preset).dailyCap,
    val quietHours: QuietHours = QuietHours.DEFAULT,
) {
    /** The effective config: the preset's timings with the user's cap (clamped) and quiet hours. */
    fun toConfig(): PacingConfig =
        PacingConfig.forPreset(preset).copy(dailyCap = dailyCap.coerceIn(1, MAX_DAILY_CAP), quietHours = quietHours)

    /** Choosing a preset resets the cap to that preset's default; quiet hours stay as the user set them. */
    fun withPreset(newPreset: PacingPreset): PacingSettings =
        copy(preset = newPreset, dailyCap = PacingConfig.forPreset(newPreset).dailyCap)

    companion object {
        /** The cap never goes above the spec's Gentle default. */
        const val MAX_DAILY_CAP = 2_000
    }
}

/** Pacing math (spec section 8). Every random draw is uniform and inclusive of both ends. */
object Pacing {
    private fun uniform(random: Random, min: Long, max: Long): Long = if (max <= min) min else random.nextLong(min, max + 1)

    /** Delay before the next request: base plus uniform jitter. */
    fun requestDelayMs(c: PacingConfig, random: Random): Long = c.baseDelayMs + uniform(random, 0, c.jitterMaxMs)

    /** Break after a session. */
    fun breakMs(c: PacingConfig, random: Random): Long = uniform(random, c.breakMinMs, c.breakMaxMs)

    /** Pause when switching channels. */
    fun channelSwitchMs(c: PacingConfig, random: Random): Long = uniform(random, c.channelSwitchMinMs, c.channelSwitchMaxMs)

    /** Sleep when `X-RateLimit-Remaining` <= 1: `X-RateLimit-Reset-After` (seconds) plus a 250-750 ms margin. */
    fun rateLimitWaitMs(c: PacingConfig, resetAfterSeconds: Double, random: Random): Long {
        require(resetAfterSeconds.isFinite() && resetAfterSeconds >= 0) { "reset-after must be a finite, non-negative number" }
        return Math.ceil(resetAfterSeconds * 1000.0).toLong() + uniform(random, c.rateLimitMarginMinMs, c.rateLimitMarginMaxMs)
    }

    /** A session ends once it has run for [PacingConfig.sessionMaxMs]. */
    fun sessionOver(c: PacingConfig, sessionStartMs: Long, nowMs: Long): Boolean = nowMs - sessionStartMs >= c.sessionMaxMs

    /** Deletes still allowed today; zero once the cap is reached. */
    fun remainingToday(c: PacingConfig, deletedToday: Int): Int = (c.dailyCap - deletedToday).coerceAtLeast(0)

    fun dailyCapReached(c: PacingConfig, deletedToday: Int): Boolean = remainingToday(c, deletedToday) == 0

    /** Whether a request may be sent now: outside quiet hours and under the daily cap. */
    fun maySendNow(c: PacingConfig, now: LocalDateTime, deletedToday: Int): Boolean =
        !c.quietHours.contains(now.toLocalTime()) && !dailyCapReached(c, deletedToday)
}
