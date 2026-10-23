package app.discoredact.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunReportsTest {
    private fun progress(id: Long) = RunProgress(
        runId = id, startedAt = 10, endedAt = null, deletedCount = 0, stopReason = null, dryRun = false, oldestFirst = false,
        resumeNotBefore = null, total = 5, deleted = 1, alreadyGone = 0, skippedSystem = 0, failed = 0, pending = 4, blocked = 0,
        dryWouldDelete = 0, channels = 1,
    )

    @Test
    fun everyRunAppearsNewestFirstAsGivenWithItsOwnErrorsOnly() {
        val reports = assembleRunReports(
            progress = listOf(progress(3), progress(2), progress(1)),
            errors = listOf(RunErrorGroup(2, 429, 20016, 4), RunErrorGroup(2, 500, null, 1), RunErrorGroup(1, 403, 50001, 2)),
            totals = listOf(RunEventTotals(2, noResponse = 3, transientCount = 5)),
            halts = listOf(RunLastHalt(1, "HALT unknown_4xx 403 50001")),
        )
        assertEquals(listOf(3L, 2L, 1L), reports.map { it.progress.runId })
        assertEquals(emptyList<RunErrorGroup>(), reports[0].errors)
        assertEquals(listOf(RunErrorGroup(2, 429, 20016, 4), RunErrorGroup(2, 500, null, 1)), reports[1].errors)
        assertEquals(listOf(RunErrorGroup(1, 403, 50001, 2)), reports[2].errors)
    }

    @Test
    fun aRunWithNoEventsHasZeroCountsAndNoHalt() {
        val r = assembleRunReports(listOf(progress(1)), emptyList(), emptyList(), emptyList()).single()
        assertEquals(0, r.noResponse)
        assertEquals(0, r.transientCount)
        assertNull(r.lastHalt)
    }

    @Test
    fun totalsAndHaltsAreMatchedByRunId() {
        val reports = assembleRunReports(
            progress = listOf(progress(2), progress(1)),
            errors = emptyList(),
            totals = listOf(RunEventTotals(1, noResponse = 2, transientCount = 1)),
            halts = listOf(RunLastHalt(2, "HALT token_invalid 401")),
        )
        assertEquals(0, reports[0].noResponse)
        assertEquals("HALT token_invalid 401", reports[0].lastHalt)
        assertEquals(2, reports[1].noResponse)
        assertEquals(1, reports[1].transientCount)
        assertNull(reports[1].lastHalt)
    }
}
