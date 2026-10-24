package app.discoredact.core.history

import app.discoredact.core.pacing.PacingConfig
import app.discoredact.core.pacing.PacingPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchEstimateTest {
    private val slower = PacingConfig.forPreset(PacingPreset.SLOWER)

    @Test fun oneReadPerHundredMessagesPlusOnePerDmPlusTwo() {
        assertEquals(2L, FetchEstimate.requests(0, 0))
        assertEquals(3L, FetchEstimate.requests(1, 0))
        assertEquals(4L, FetchEstimate.requests(1, 100))
        // The memo's example: 100,000 messages across 200 DMs is about 1,200 reads.
        assertEquals(1_202L, FetchEstimate.requests(200, 100_000))
    }

    @Test fun durationAndDaysAtTheSlowerPreset() {
        // 1,000 reads at 3 s + 0-3 s is 75 minutes of requests, plus two breaks of 15-30 minutes.
        val ms = FetchEstimate.durationMs(1_000, 1, slower)
        assertEquals(75 * 60_000L + 2 * (22 * 60_000L + 30_000L), ms)
        assertEquals(1L, FetchEstimate.days(1_000, slower))
        assertEquals(2L, FetchEstimate.days(1_202, slower))
        assertTrue(FetchEstimate.durationMs(1_202, 200, slower) > FetchEstimate.durationMs(1_202, 1, slower))
    }
}
