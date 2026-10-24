package app.discoredact.core.history

import app.discoredact.core.executor.EdgeHold
import app.discoredact.core.executor.Executor
import app.discoredact.core.executor.FakeClock
import app.discoredact.core.executor.FakeSleeper
import app.discoredact.core.executor.FakeStore
import app.discoredact.core.executor.FakeTransport
import app.discoredact.core.executor.HaltReason
import app.discoredact.core.executor.InMemoryCredentialStore
import app.discoredact.core.executor.Responses
import app.discoredact.core.executor.SessionEnd
import app.discoredact.core.executor.TEST_HEADERS
import app.discoredact.core.history.Pages.ME
import app.discoredact.core.importer.ImportedChannel
import app.discoredact.core.model.ChannelType
import app.discoredact.core.pacing.PacingConfig
import app.discoredact.core.pacing.PacingPreset
import app.discoredact.core.selection.FetchDecision
import app.discoredact.core.selection.FetchRefusal
import app.discoredact.core.selection.StartDecision
import app.discoredact.core.selection.StartRefusal
import app.discoredact.core.selection.StartRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * M-1 of the review (rule 8): an edge block holds the whole app for an hour, whichever operation met it. The hold is
 * read from the shared event log (the newest "HALT edge" row), as the app reads it, and fed to both start rules.
 */
class EdgeHoldTest {
    private val config = PacingConfig.forPreset(PacingPreset.SLOWER)
    private val clock = FakeClock(LocalDateTime.of(2026, 10, 24, 12, 0))
    private val creds = InMemoryCredentialStore(TEST_HEADERS)

    /** The app's query, on the fakes' event rows: the newest edge halt, plus an hour. */
    private fun holdFrom(events: List<Pair<Long, String>>): Long? =
        EdgeHold.until(events.filter { EdgeHold.isEdgeHalt(it.second) }.maxOfOrNull { it.first })

    private fun fetchHaltsOnAnEdgeBlock(): FakeFetchStore {
        val store = FakeFetchStore()
        store.fetch!!.accountId = ME
        store.fetch!!.capturedAt = TEST_HEADERS.capturedAt
        store.fetch!!.listedAt = 1
        store.channelsSeen(listOf(ImportedChannel(10, ChannelType.DM, "dm", emptyList())), 1)
        val end = runBlocking { FetchSession(store, FakeTransport(Responses.html), creds, config, clock, FakeSleeper(clock), Random(1)).runSession() }
        assertEquals(HaltReason.EDGE_BLOCK, (end as SessionEnd.Halted).reason)
        return store
    }

    private fun runHaltsOnAnEdgeBlock(): FakeStore {
        val store = FakeStore()
        store.channel(10, 1, 2)
        store.freeze(1, 1, 2)
        val end = runBlocking { Executor(store, FakeTransport(Responses.html), creds, config, clock, FakeSleeper(clock), Random(1)).runSession(1, false, false) }
        assertEquals(HaltReason.EDGE_BLOCK, (end as SessionEnd.Halted).reason)
        return store
    }

    @Test fun aFetchsEdgeHaltRefusesARunsStartAndResume() {
        val store = fetchHaltsOnAnEdgeBlock()
        val hold = holdFrom(store.events.map { it.ts to it.actionTaken })!!
        assertEquals(clock.now + EdgeHold.HOLD_MS, hold)
        assertEquals(StartDecision.Refuse(StartRefusal.HELD, notBefore = hold), StartRules.decide(7, null, null, false, true, null, clock.now, false, hold))
        assertEquals(StartDecision.Refuse(StartRefusal.HELD, notBefore = hold), StartRules.decide(7, 7, 5, false, true, null, clock.now, false, hold))
    }

    @Test fun aRunsEdgeHaltRefusesAFetch() {
        val store = runHaltsOnAnEdgeBlock()
        val hold = holdFrom(store.events.map { it.ts to it.actionTaken })!!
        assertEquals(FetchDecision.Refuse(FetchRefusal.HELD, hold), StartRules.decideFetch(false, false, true, false, null, clock.now, hold))
        assertEquals(FetchDecision.Refuse(FetchRefusal.HELD, hold), StartRules.decideFetch(false, false, true, true, null, clock.now, hold))
    }

    @Test fun stopThenANewFetchIsStillHeld() {
        val store = fetchHaltsOnAnEdgeBlock()
        store.fetchEnded(clock.now + 1_000, Executor.STOP_STOPPED) // the user's Stop
        val hold = holdFrom(store.events.map { it.ts to it.actionTaken })!!
        // "New fetch": no open fetch, no hold of its own, and still refused.
        assertEquals(FetchDecision.Refuse(FetchRefusal.HELD, hold), StartRules.decideFetch(false, false, true, false, null, clock.now + 2_000, hold))
    }

    @Test fun theHoldEndsAfterTheHour() {
        val store = fetchHaltsOnAnEdgeBlock()
        val hold = holdFrom(store.events.map { it.ts to it.actionTaken })!!
        val later = clock.now + EdgeHold.HOLD_MS
        assertEquals(hold, later)
        assertEquals(FetchDecision.Start, StartRules.decideFetch(false, false, true, false, null, later, hold))
        assertEquals(StartDecision.Start, StartRules.decide(7, null, null, false, true, null, later, false, hold))
        assertTrue(StartRules.decide(7, null, null, false, true, null, later - 1, false, hold) is StartDecision.Refuse)
    }

    @Test fun onlyEdgeHaltsHold() {
        assertTrue(EdgeHold.isEdgeHalt("HALT edge"))
        assertTrue(EdgeHold.isEdgeHalt("HALT edge 503"))
        for (a in listOf("HALT 401", "HALT captcha", "HALT tripwire 429", "HALT unknown_4xx 403 40002", "HALT edgeX", "EDGE", "CHANNEL_GONE")) {
            assertTrue(a, !EdgeHold.isEdgeHalt(a))
        }
        assertEquals(null, EdgeHold.until(null))
    }
}
