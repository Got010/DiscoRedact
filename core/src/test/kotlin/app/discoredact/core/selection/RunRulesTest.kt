package app.discoredact.core.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunRulesTest {
    @Test
    fun aNewFreezeSupersedesEveryUnstartedRunAndOnlyTheNewestIsRunnable() {
        val runs = listOf(
            RunRow(1, startedAt = 10, endedAt = 20), // finished
            RunRow(2, startedAt = null, endedAt = 30), // superseded earlier
            RunRow(3, startedAt = null, endedAt = null), // frozen, never started
            RunRow(4, startedAt = 40, endedAt = null), // started, not ended
            RunRow(5, startedAt = null, endedAt = null), // frozen, never started
        )
        assertEquals(listOf(3L, 5L), RunRules.toSupersede(runs))
        assertEquals(5L, RunRules.runnable(runs))
        // After the freeze transaction: 3 and 5 superseded, new run 6 inserted.
        val after = runs.map { if (it.runId in setOf(3L, 5L)) it.copy(endedAt = 50) else it } + RunRow(6, null, null)
        assertEquals(6L, RunRules.runnable(after))
        assertEquals(listOf(6L), RunRules.toSupersede(after))
    }

    @Test
    fun noRunIsRunnableWithoutAnOpenFrozenRun() {
        assertNull(RunRules.runnable(emptyList()))
        assertNull(RunRules.runnable(listOf(RunRow(1, 1, 2), RunRow(2, null, 3))))
    }
}
