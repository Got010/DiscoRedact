package app.discoredact.core.executor

import app.discoredact.core.pacing.PacingConfig
import app.discoredact.core.pacing.PacingPreset
import org.junit.Assert.assertEquals
import org.junit.Test

class EtaTest {
    private val slower = PacingConfig.forPreset(PacingPreset.SLOWER)

    @Test fun underTheCapIsDelaysPlusBreaks() {
        // 100 messages at 4.5 s = 450 s < one session: no break.
        val e = Eta.estimate(100, slower, 0)
        assertEquals(100, e.today); assertEquals(450_000L, e.todayMs); assertEquals(0, e.later); assertEquals(0, e.laterDays)
        // 500 messages at 4.5 s = 2,250 s = 37.5 min: one break (average 22.5 min).
        val f = Eta.estimate(500, slower, 0)
        assertEquals(2_250_000L + 22.5.times(60_000).toLong(), f.todayMs)
    }

    @Test fun theCapSplitsTodayFromLaterDays() {
        val e = Eta.estimate(2_500, slower, 300)
        assertEquals(700, e.today); assertEquals(1_800, e.later); assertEquals(2, e.laterDays)
        val capped = Eta.estimate(10, slower, 1_000)
        assertEquals(0, capped.today); assertEquals(0L, capped.todayMs); assertEquals(10, capped.later); assertEquals(1, capped.laterDays)
    }

    @Test fun theMultiplierDoublesTheDelays() {
        assertEquals(900_000L, Eta.estimate(100, slower, 0, multiplier = 2).todayMs)
    }
}
