package app.discoredact.core.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartRulesTest {
    private val now = 1_000_000L

    private fun decide(
        runId: Long = 7,
        openRunId: Long? = null,
        startedAt: Long? = null,
        dryRun: Boolean = false,
        hasHeaders: Boolean = true,
        holdUntil: Long? = null,
    ) = StartRules.decide(runId, openRunId, startedAt, dryRun, hasHeaders, holdUntil, now)

    @Test
    fun aDifferentOpenRunRefusesNamingIt() {
        assertEquals(StartDecision.Refuse(StartRefusal.OTHER_RUN_OPEN, openRunId = 3), decide(openRunId = 3))
        // Even a dry run, and even one that has started, is refused while another run is open.
        assertEquals(StartDecision.Refuse(StartRefusal.OTHER_RUN_OPEN, openRunId = 3), decide(openRunId = 3, dryRun = true))
        assertEquals(StartDecision.Refuse(StartRefusal.OTHER_RUN_OPEN, openRunId = 3), decide(openRunId = 3, startedAt = 5))
    }

    @Test
    fun theOpenRunItselfResumes() {
        assertEquals(StartDecision.Resume, decide(openRunId = 7, startedAt = 5))
    }

    @Test
    fun aRunThatHasStartedResumesAndNeverStartsAgain() {
        assertEquals(StartDecision.Resume, decide(startedAt = 5))
        assertEquals(StartDecision.Resume, decide(startedAt = 5, dryRun = true, hasHeaders = false))
    }

    @Test
    fun aRunThatNeverStartedStarts() {
        assertEquals(StartDecision.Start, decide())
    }

    @Test
    fun aRealRunWithNoCredentialsRefusesToStartAndToResume() {
        assertEquals(StartDecision.Refuse(StartRefusal.NO_CREDENTIALS_START), decide(hasHeaders = false))
        assertEquals(StartDecision.Refuse(StartRefusal.NO_CREDENTIALS_RESUME), decide(startedAt = 5, hasHeaders = false))
    }

    @Test
    fun aDryRunWithNoCredentialsStarts() {
        assertEquals(StartDecision.Start, decide(dryRun = true, hasHeaders = false))
    }

    @Test
    fun aHoldInTheFutureRefusesWithTheTime() {
        val d = decide(startedAt = 5, holdUntil = now + 1)
        assertEquals(StartDecision.Refuse(StartRefusal.HELD, notBefore = now + 1), d)
        // The hold is checked before the credentials, as the resume always did.
        assertEquals(StartDecision.Refuse(StartRefusal.HELD, notBefore = now + 1), decide(startedAt = 5, holdUntil = now + 1, hasHeaders = false))
    }

    @Test
    fun aHoldThatHasPassedDoesNotRefuse() {
        assertEquals(StartDecision.Resume, decide(startedAt = 5, holdUntil = now))
        assertEquals(StartDecision.Resume, decide(startedAt = 5, holdUntil = now - 1))
    }

    @Test
    fun theOpenRunCheckComesFirst() {
        val d = decide(openRunId = 3, startedAt = 5, holdUntil = now + 5, hasHeaders = false)
        assertTrue(d is StartDecision.Refuse && d.reason == StartRefusal.OTHER_RUN_OPEN)
    }
}
