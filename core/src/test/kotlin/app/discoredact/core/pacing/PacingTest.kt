package app.discoredact.core.pacing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.random.Random

class PacingTest {
    /** Always draws the lowest value. */
    private object Low : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextLong(from: Long, until: Long) = from
    }

    /** Always draws the highest value. */
    private object High : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextLong(from: Long, until: Long) = until - 1
    }

    private val slower = PacingConfig.forPreset(PacingPreset.SLOWER)
    private val gentle = PacingConfig.forPreset(PacingPreset.GENTLE)
    private val min = 60_000L

    @Test
    fun slowerIsTheDefault() {
        assertEquals(PacingPreset.SLOWER, PacingPreset.DEFAULT)
        assertEquals(PacingPreset.SLOWER, PacingSettings().preset)
        assertEquals(1_000, PacingSettings().dailyCap)
    }

    @Test
    fun requestDelayRanges() {
        assertEquals(3_000, Pacing.requestDelayMs(slower, Low))
        assertEquals(6_000, Pacing.requestDelayMs(slower, High))
        assertEquals(1_500, Pacing.requestDelayMs(gentle, Low))
        assertEquals(3_000, Pacing.requestDelayMs(gentle, High))
        val r = Random(1)
        repeat(10_000) {
            assertTrue(Pacing.requestDelayMs(slower, r) in 3_000..6_000)
            assertTrue(Pacing.requestDelayMs(gentle, r) in 1_500..3_000)
        }
    }

    @Test
    fun sessionsAndBreaks() {
        assertFalse(Pacing.sessionOver(slower, 0, 30 * min - 1))
        assertTrue(Pacing.sessionOver(slower, 0, 30 * min))
        assertFalse(Pacing.sessionOver(gentle, 0, 45 * min - 1))
        assertTrue(Pacing.sessionOver(gentle, 0, 45 * min))
        assertEquals(15 * min, Pacing.breakMs(slower, Low))
        assertEquals(30 * min, Pacing.breakMs(slower, High))
        assertEquals(10 * min, Pacing.breakMs(gentle, Low))
        assertEquals(20 * min, Pacing.breakMs(gentle, High))
        for (c in listOf(slower, gentle)) {
            assertEquals(30_000, Pacing.channelSwitchMs(c, Low))
            assertEquals(90_000, Pacing.channelSwitchMs(c, High))
        }
    }

    @Test
    fun rateLimitWaitFollowsResetAfterPlusMargin() {
        assertEquals(1_750, Pacing.rateLimitWaitMs(gentle, 1.5, Low))
        assertEquals(2_250, Pacing.rateLimitWaitMs(gentle, 1.5, High))
        assertEquals(250, Pacing.rateLimitWaitMs(slower, 0.0, Low))
        assertEquals(1_251, Pacing.rateLimitWaitMs(slower, 1.0001, Low))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rateLimitWaitRefusesNonsense() {
        Pacing.rateLimitWaitMs(gentle, Double.NaN, Low)
    }

    @Test
    fun dailyCap() {
        assertEquals(1_000, slower.dailyCap)
        assertEquals(2_000, gentle.dailyCap)
        assertEquals(1, Pacing.remainingToday(slower, 999))
        assertFalse(Pacing.dailyCapReached(slower, 999))
        assertTrue(Pacing.dailyCapReached(slower, 1_000))
        assertEquals(0, Pacing.remainingToday(slower, 5_000))
        assertTrue(Pacing.dailyCapReached(gentle, 2_000))
        assertFalse(Pacing.dailyCapReached(gentle, 1_999))
        assertEquals(2_000, PacingSettings(dailyCap = 50_000).toConfig().dailyCap)
        assertEquals(1, PacingSettings(dailyCap = 0).toConfig().dailyCap)
        assertEquals(2_000, PacingSettings().withPreset(PacingPreset.GENTLE).dailyCap)
        assertEquals(1_000, PacingSettings(PacingPreset.GENTLE).withPreset(PacingPreset.SLOWER).dailyCap)
    }

    @Test
    fun quietHoursBoundaries() {
        val q = QuietHours.DEFAULT
        assertFalse(q.contains(LocalTime.of(0, 59, 59)))
        assertTrue(q.contains(LocalTime.of(1, 0)))
        assertTrue(q.contains(LocalTime.of(7, 59, 59)))
        assertFalse(q.contains(LocalTime.of(8, 0)))
        assertFalse(q.contains(LocalTime.NOON))
        for (c in listOf(slower, gentle)) assertEquals(q, c.quietHours)
        val wrap = QuietHours(23 * 60, 7 * 60)
        assertTrue(wrap.contains(LocalTime.of(23, 0)))
        assertTrue(wrap.contains(LocalTime.of(0, 30)))
        assertFalse(wrap.contains(LocalTime.of(7, 0)))
        assertFalse(wrap.contains(LocalTime.of(22, 59)))
        assertFalse(QuietHours(60, 60).contains(LocalTime.of(1, 0)))
    }

    @Test
    fun quietHoursWaitAndMaySend() {
        val d = LocalDateTime.of(2026, 10, 21, 1, 0)
        assertEquals(Duration.ofHours(7), QuietHours.DEFAULT.waitFrom(d))
        assertEquals(Duration.ofSeconds(30), QuietHours.DEFAULT.waitFrom(d.withHour(7).withMinute(59).withSecond(30)))
        assertEquals(Duration.ZERO, QuietHours.DEFAULT.waitFrom(d.withHour(8)))
        assertEquals(Duration.ofHours(8), QuietHours(23 * 60, 7 * 60).waitFrom(d.withHour(23)))
        assertFalse(Pacing.maySendNow(slower, d, 0))
        assertTrue(Pacing.maySendNow(slower, d.withHour(9), 0))
        assertFalse(Pacing.maySendNow(slower, d.withHour(9), 1_000))
    }
}
