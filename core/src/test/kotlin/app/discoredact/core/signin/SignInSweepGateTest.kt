package app.discoredact.core.signin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Carried item 1: after Wipe everything, the next sign-in page sweeps again before it loads (CLAUDE.md rule 19). */
class SignInSweepGateTest {
    private var startCalls = 0
    private var sweeps = 0
    private var sweepOk = true

    private fun gate(startOk: Boolean) = SignInSweepGate({ startCalls++; startOk }, { sweeps++; sweepOk })

    @Test fun theStartSweepCountsOnceAndIsNotRepeated() = runBlocking {
        val g = gate(true)
        assertTrue(g.clean())
        assertTrue(g.clean())
        assertEquals(1, startCalls)
        assertEquals(0, sweeps)
    }

    @Test fun aFailedStartSweepIsTriedAgainFreshUntilItSucceeds() = runBlocking {
        val g = gate(false)
        sweepOk = false
        assertFalse(g.clean())
        assertFalse(g.clean())
        sweepOk = true
        assertTrue(g.clean())
        assertTrue(g.clean())
        assertEquals(1, startCalls)
        assertEquals(3, sweeps)
    }

    @Test fun afterAWipeTheNextPageSweepsAgainAndNeverTrustsTheEarlierSuccess() = runBlocking {
        val g = gate(true)
        assertTrue(g.clean())
        g.invalidate()
        sweepOk = false
        assertFalse(g.clean()) // the page must not load
        sweepOk = true
        assertTrue(g.clean())
        assertEquals(1, startCalls)
        assertEquals(2, sweeps)
        assertTrue(g.clean())
        assertEquals(2, sweeps)
    }

    @Test fun aWipeBeforeAnyPageStillSweepsBeforeTheFirst() = runBlocking {
        val g = gate(true)
        g.invalidate()
        assertTrue(g.clean())
        assertEquals(0, startCalls)
        assertEquals(1, sweeps)
    }

    @Test fun aSweepThatThrowsCountsAsFailed() = runBlocking {
        val g = SignInSweepGate({ error("io") }, { error("io") })
        assertFalse(g.clean())
    }
}
