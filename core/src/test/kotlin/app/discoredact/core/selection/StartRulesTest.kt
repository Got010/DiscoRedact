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
        fetchActive: Boolean = false,
    ) = StartRules.decide(runId, openRunId, startedAt, dryRun, hasHeaders, holdUntil, now, fetchActive)

    private fun fetch(
        wiping: Boolean = false,
        runWorkerActive: Boolean = false,
        hasHeaders: Boolean = true,
        fetchOpen: Boolean = false,
        holdUntil: Long? = null,
    ) = StartRules.decideFetch(wiping, runWorkerActive, hasHeaders, fetchOpen, holdUntil, now)

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

    @Test
    fun noRunStartsOrResumesWhileAFetchWorkerIsActiveDryOrReal() {
        for (dry in listOf(true, false)) for (started in listOf(null, 5L)) {
            assertEquals(StartDecision.Refuse(StartRefusal.FETCH_ACTIVE), decide(dryRun = dry, startedAt = started, fetchActive = true))
        }
    }

    @Test
    fun aFetchStartsWithHeadersAndNothingElseActive() {
        assertEquals(FetchDecision.Start, fetch())
        assertEquals(FetchDecision.Resume, fetch(fetchOpen = true))
    }

    @Test
    fun aFetchRefusesDuringAWipeOrBesideARunWorkerOrWithoutHeaders() {
        assertEquals(FetchDecision.Refuse(FetchRefusal.WIPING), fetch(wiping = true, runWorkerActive = true, hasHeaders = false))
        assertEquals(FetchDecision.Refuse(FetchRefusal.RUN_ACTIVE), fetch(runWorkerActive = true, hasHeaders = false))
        assertEquals(FetchDecision.Refuse(FetchRefusal.NO_CREDENTIALS), fetch(hasHeaders = false))
        assertEquals(FetchDecision.Refuse(FetchRefusal.NO_CREDENTIALS), fetch(hasHeaders = false, fetchOpen = true))
    }

    @Test
    fun anEdgeHoldRefusesTheResumeUntilItEnds() {
        assertEquals(FetchDecision.Refuse(FetchRefusal.HELD, now + 5), fetch(fetchOpen = true, holdUntil = now + 5))
        assertEquals(FetchDecision.Resume, fetch(fetchOpen = true, holdUntil = now))
        // A new fetch is not held by an ended one's hold.
        assertEquals(FetchDecision.Start, fetch(holdUntil = now + 5))
    }
}
