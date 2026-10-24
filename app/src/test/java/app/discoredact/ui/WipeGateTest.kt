package app.discoredact.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WipeGateTest {
    @Test
    fun noActiveWorkerIsReadyAtOnce() {
        assertEquals(WipeGate.Decision.Ready, WipeGate.decide(workerActive = false, waitedMs = 0))
        assertEquals(WipeGate.Decision.Ready, WipeGate.decide(workerActive = false, waitedMs = WipeGate.WORKER_WAIT_MS * 2))
    }

    @Test
    fun anActiveWorkerWaitsUntilTheLimit() {
        assertEquals(WipeGate.Decision.WaitForWorker, WipeGate.decide(workerActive = true, waitedMs = 0))
        assertEquals(WipeGate.Decision.WaitForWorker, WipeGate.decide(workerActive = true, waitedMs = WipeGate.WORKER_WAIT_MS - 1))
    }

    @Test
    fun anActiveWorkerAtTheLimitRefusesAndWipesNothing() {
        val d = WipeGate.decide(workerActive = true, waitedMs = WipeGate.WORKER_WAIT_MS)
        assertEquals(WipeGate.Decision.Refuse("A run is still writing; try again in a moment. Nothing was wiped."), d)
        assertTrue(WipeGate.decide(workerActive = true, waitedMs = 10 * WipeGate.WORKER_WAIT_MS) is WipeGate.Decision.Refuse)
    }

    @Test
    fun theWaitIsAboutFifteenSeconds() {
        assertEquals(15_000L, WipeGate.WORKER_WAIT_MS)
    }

    @Test
    fun nothingWritingGivesNoNotice() {
        assertNull(WipeGate.busyNotice(importRunning = false, filterApplying = false, freezing = false, workerRunning = false, runId = 4))
    }

    @Test
    fun theNoticeKeepsItsWordsForAnImportFilterOrFreeze() {
        assertEquals(
            "An import is running; Purge and Wipe wait until it ends.",
            WipeGate.busyNotice(true, false, false, false, null),
        )
        assertEquals(
            "A filter is running; Purge and Wipe wait until it ends.",
            WipeGate.busyNotice(false, true, false, false, null),
        )
        assertEquals(
            "A freeze is running; Purge and Wipe wait until it ends.",
            WipeGate.busyNotice(false, false, true, false, null),
        )
    }

    @Test
    fun anExportIsNamed() {
        assertEquals(
            "An export is running; Purge and Wipe wait until it ends.",
            WipeGate.busyNotice(false, false, false, false, null, exporting = true),
        )
    }

    @Test
    fun aRunningRunIsNamedAndToldToStopFirst() {
        assertEquals(
            "Run #12 is running; Purge and Wipe wait until it ends. Pause or Stop the run on the Run screen first.",
            WipeGate.busyNotice(false, false, false, true, 12),
        )
        assertEquals(
            "A run is running; Purge and Wipe wait until it ends. Pause or Stop the run on the Run screen first.",
            WipeGate.busyNotice(false, false, false, true, null),
        )
    }

    @Test
    fun severalWritersAreListed() {
        assertEquals(
            "An import and run #3 are running; Purge and Wipe wait until it ends. Pause or Stop the run on the Run screen first.",
            WipeGate.busyNotice(true, false, false, true, 3),
        )
        assertEquals(
            "An import, a filter and a freeze are running; Purge and Wipe wait until it ends.",
            WipeGate.busyNotice(true, true, true, false, null),
        )
    }

    @Test
    fun aRunningFetchIsNamedWithItsOwnScreen() {
        assertEquals(
            "A fetch is running; Purge and Wipe wait until it ends. Pause or Stop the fetch on the Fetch screen first.",
            WipeGate.busyNotice(false, false, false, false, null, fetchRunning = true),
        )
        assertEquals(
            "Run #3 and a fetch are running; Purge and Wipe wait until it ends. Pause or Stop the run and the fetch first.",
            WipeGate.busyNotice(false, false, false, true, 3, fetchRunning = true),
        )
    }
}
