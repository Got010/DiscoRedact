package app.discoredact.core.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripwireTest {
    @Test fun firesAtFiveWithinTenMinutes() {
        val t = Tripwire()
        var now = 1_000_000L
        repeat(4) { assertFalse(t.record(now)); now += 60_000 }
        assertTrue(t.record(now))
    }

    @Test fun doesNotFireAtFiveSpreadOverElevenMinutes() {
        val t = Tripwire()
        var now = 0L
        val step = 2 * 60_000L + 46_000
        repeat(5) { assertFalse(t.record(now)); now += step }
        assertEquals(4, t.count(now - step))
    }

    @Test fun windowIsRollingAndExactlyTenMinutes() {
        val t = Tripwire()
        t.record(0); t.record(1); t.record(2); t.record(3)
        assertEquals(4, t.count(599_999))
        assertTrue(t.record(599_999)) // the fifth, with the first still inside the window
        assertEquals(4, t.count(600_000)) // the first has aged out exactly at ten minutes
    }

    @Test fun seedingReplacesTheWindowSoReseedingAfterABreakCountsNothingTwice() {
        val t = Tripwire()
        t.seed(listOf(1L, 2L, 3L, 4L))
        t.seed(listOf(1L, 2L, 3L, 4L))
        assertEquals(4, t.count(5L))
        assertTrue(t.record(6L))
    }
}
